package me.rerere.rikkahub.subagent

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.browser.BrowserToolDefaults
import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
import me.rerere.rikkahub.data.ai.tools.ToolNameSnapshot
import me.rerere.rikkahub.data.capability.CapabilityCatalog
import me.rerere.rikkahub.data.capability.ToolInvocationSurface
import kotlin.uuid.Uuid

/**
 * Phase 11 — sub-agent run record. Lives in [SubAgentRegistry]'s in-memory map for the
 * lifetime of the app process. Persistence intentionally out of scope for v1: spec says
 * "Background sub-agents survive only as long as the parent process is alive" and
 * documents that user-visibly. WorkManager-backed persistence is a v2 concern.
 *
 * The run is FROZEN once it reaches a terminal status. Mutations are done by replacing
 * the entry in the registry's StateFlow rather than mutating in place.
 */
@Serializable
data class SubAgentRun(
    val id: String,
    val parentChatId: String?,         // the parent assistant chat that dispatched this — used for /stop cascade
    val parentAssistantId: String,
    val label: String,
    val task: String,
    val modelId: String?,              // null = inherited from parent
    val tools: List<String>?,          // null = inherited from parent
    val runInBackground: Boolean,
    val timeoutSeconds: Int,
    val maxTrips: Int,
    val status: SubAgentStatus,
    val result: String? = null,
    val error: String? = null,
    val startedAtMs: Long,
    val finishedAtMs: Long? = null,
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
    val tripCount: Int = 0,
)

@Serializable
enum class SubAgentStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
}

object SubAgentDefaults {
    const val DEFAULT_TIMEOUT_SECONDS = 300
    const val MAX_TIMEOUT_SECONDS = 1800
    const val DEFAULT_MAX_TRIPS = 12
    const val MAX_MAX_TRIPS = 30
    const val MAX_LABEL_LENGTH = 60
    const val GLOBAL_CONCURRENCY_CAP = 16
    const val MIN_PER_ASSISTANT_CAP = 1
    const val MAX_PER_ASSISTANT_CAP = 8
    const val REGISTRY_LRU_CAP = 50

    /** Default system prompt used when the assistant's per-sub-agent prompt is empty. */
    val DEFAULT_SYSTEM_PROMPT = """
        You are a focused sub-agent dispatched by a parent assistant to complete a single
        task and return a concise summary.

        Rules:
        - Stay tightly scoped to the task you were given. Do not expand scope.
        - Use tools to gather facts before answering when accuracy matters.
        - Return a clear, structured final summary as your last message — that summary is
          what the parent will see. Aim for 100-500 words unless the task asks otherwise.
        - If the task is impossible, return a single short paragraph explaining why.
        - Do not ask the parent for clarification — make the best judgment call you can
          and proceed.
    """.trimIndent()
}

@Serializable
data class SubAgentRequest(
    val task: String,
    val modelId: String? = null,
    val systemPrompt: String? = null,
    val tools: List<String>? = null,
    val runInBackground: Boolean = false,
    val timeoutSeconds: Int = SubAgentDefaults.DEFAULT_TIMEOUT_SECONDS,
    val maxTrips: Int = SubAgentDefaults.DEFAULT_MAX_TRIPS,
    val label: String? = null,
)

enum class SubAgentPromptSource {
    REQUEST,
    ASSISTANT,
    DEFAULT,
}

/**
 * A named, reusable sub-agent configuration - a name, description, custom system
 * prompt and model, defined once in settings so the dispatching model can pick a specialist by
 * NAME instead of memorizing a model uuid. Persisted via PreferencesStore (`sub_agents`) and
 * edited in SettingSubAgentsPage. `modelId` null means the profile itself defers to the
 * parent's model, mirroring the "null = inherit" convention already used by [SubAgentRequest.modelId].
 */
@Serializable
data class SubAgentProfile(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val description: String = "",
    val systemPrompt: String = "",
    val modelId: Uuid? = null,
    val enabled: Boolean = true,
)

/** Inputs available when a child run is dispatched from an already-authorized parent turn. */
data class SubAgentExecutionInputs(
    val parentEffectiveModelId: Uuid,
    val assistantDefaultModelId: Uuid?,
    val assistantSystemPrompt: String,
    val availableModelIds: Set<Uuid>,
    val callerToolNames: Set<String>,
    val headlessToolNames: Set<String>,
    val knownToolNames: Set<String> = callerToolNames + headlessToolNames,
    /**
     * Chat models offered by enabled providers, used to resolve a dispatch `model_id` that is
     * not a UUID (extv #28 SubAgentModelResolver parity: UUID → modelId → displayName, all
     * case-insensitive). Empty list = no name resolution (tests / headless snippets).
     */
    val modelCandidates: List<SubAgentModelCandidate> = emptyList(),
)

/**
 * One resolvable chat model advertised by an enabled provider. Kept deliberately tiny so the
 * engine can build it straight off the Settings provider tree without leaking provider types.
 */
data class SubAgentModelCandidate(
    val id: Uuid,
    val modelId: String,
    val displayName: String,
)

/** Immutable execution contract consumed by the existing conversation generation pipeline. */
data class SubAgentExecutionProfile(
    val runId: String,
    val effectiveModelId: Uuid,
    val promptSource: SubAgentPromptSource,
    val effectiveSystemPrompt: String,
    val effectiveToolNames: Set<String>,
    val maxToolTrips: Int,
)

internal fun SubAgentExecutionProfile.allowsTool(toolName: String): Boolean =
    toolName in effectiveToolNames

enum class SubAgentParentCompletionPolicy {
    /** A standalone background child posts its terminal result back to the parent chat. */
    NOTIFY_PARENT,

    /** A coordinator owns aggregation and is the only layer allowed to wake the parent. */
    COORDINATOR_ONLY,
}

/** Frozen caller identity and least-privilege surface captured at dispatch time. */
data class SubAgentCallerContext(
    val parentAssistantId: String,
    val parentConversationId: String?,
    val parentEffectiveModelId: Uuid?,
    val toolNames: ToolNameSnapshot,
    val completionPolicy: SubAgentParentCompletionPolicy =
        SubAgentParentCompletionPolicy.NOTIFY_PARENT,
)

/** GenerationHandler reserves the final planned step plus one extra index for summarization. */
internal fun SubAgentExecutionProfile.generationMaxSteps(): Int =
    maxToolTrips.coerceIn(1, SubAgentDefaults.MAX_MAX_TRIPS) + 1

sealed interface SubAgentExecutionProfileResolution {
    data class Resolved(val profile: SubAgentExecutionProfile) : SubAgentExecutionProfileResolution
    data class Rejected(val error: String, val detail: String) : SubAgentExecutionProfileResolution
}

/** Cap on how many candidates the loud-failure detail lists so prompts stay readable. */
private const val MAX_LISTED_MODEL_CANDIDATES = 20

/**
 * extv #28 loud-failure message: unknown/ambiguous model_id must list every available chat
 * model (displayName + modelId + uuid) so the dispatching AI can self-correct and retry.
 */
private fun describeAvailableModels(
    requested: String?,
    candidates: List<SubAgentModelCandidate>,
    prefix: String? = null,
): String {
    val body = buildString {
        if (!prefix.isNullOrBlank()) append(prefix).append(". ")
        if (requested != null) append("model_id '$requested' did not resolve. ")
        if (candidates.isEmpty()) {
            append("No chat models are advertised by enabled providers.")
        } else {
            append("Available chat models: ")
            candidates.take(MAX_LISTED_MODEL_CANDIDATES).joinTo(this, "; ") { candidate ->
                "'${candidate.displayName}' (${candidate.modelId}) = ${candidate.id}"
            }
            if (candidates.size > MAX_LISTED_MODEL_CANDIDATES) {
                append("; … and ${candidates.size - MAX_LISTED_MODEL_CANDIDATES} more")
            }
        }
    }
    return body
}

/**
 * Resolves request overrides into one frozen child-run profile without mutating the parent
 * [me.rerere.rikkahub.data.model.Assistant]. Tool names are intersected with both the caller's
 * actual tool surface and the tools that are safe in a headless conversation.
 */
fun resolveSubAgentExecutionProfile(
    runId: String,
    request: SubAgentRequest,
    inputs: SubAgentExecutionInputs,
): SubAgentExecutionProfileResolution {
    val requestedRaw = request.modelId?.trim()?.takeIf(String::isNotEmpty)
    val requestedModelId: Uuid? = if (requestedRaw == null) {
        null
    } else {
        // SubAgentModelResolver parity (extv #28, 2026-10-11 裁定③): a dispatch model_id is
        // resolved through three case-insensitive stages — exact UUID → modelId string →
        // displayName string. AI dispatchers usually only know a model by name, so a bare
        // "claude-3-5-sonnet" must resolve instead of being rejected. Unknown/ambiguous lookups
        // fail loudly with the full candidate list so the model can self-correct and retry.
        val uuid = runCatching { Uuid.parse(requestedRaw) }.getOrNull()
        if (uuid != null) {
            uuid
        } else {
            val needle = requestedRaw.lowercase()
            val byModelId = inputs.modelCandidates.filter { it.modelId.lowercase() == needle }
            val byName = inputs.modelCandidates.filter { it.displayName.lowercase() == needle }
            val matched = (byModelId.map { it.id } + byName.map { it.id }).distinct()
            when (matched.size) {
                1 -> matched.single()
                0 -> return SubAgentExecutionProfileResolution.Rejected(
                    error = "unknown_model",
                    detail = describeAvailableModels(
                        requested = requestedRaw,
                        candidates = inputs.modelCandidates,
                    ),
                )
                else -> return SubAgentExecutionProfileResolution.Rejected(
                    error = "ambiguous_model",
                    detail = describeAvailableModels(
                        requested = requestedRaw,
                        candidates = inputs.modelCandidates,
                        prefix = "model_id '$requestedRaw' matches multiple models; " +
                            "pass the exact UUID instead",
                    ),
                )
            }
        }
    }
    val effectiveModelId = requestedModelId
        ?: inputs.assistantDefaultModelId
        ?: inputs.parentEffectiveModelId
    if (effectiveModelId !in inputs.availableModelIds) {
        return SubAgentExecutionProfileResolution.Rejected(
            error = "unknown_model",
            detail = describeAvailableModels(
                requested = requestedRaw,
                candidates = inputs.modelCandidates,
                prefix = "the selected child model is not available",
            ),
        )
    }

    val requestedPrompt = request.systemPrompt?.trim().orEmpty()
    val assistantPrompt = inputs.assistantSystemPrompt.trim()
    val (promptSource, prompt) = when {
        requestedPrompt.isNotEmpty() -> SubAgentPromptSource.REQUEST to requestedPrompt
        assistantPrompt.isNotEmpty() -> SubAgentPromptSource.ASSISTANT to assistantPrompt
        else -> SubAgentPromptSource.DEFAULT to SubAgentDefaults.DEFAULT_SYSTEM_PROMPT
    }
    val requestedTools = request.tools?.map(String::trim)?.distinct()
    requestedTools?.forEach { toolName ->
        when {
            toolName.isEmpty() || toolName !in inputs.knownToolNames ->
                return SubAgentExecutionProfileResolution.Rejected(
                    error = "unknown_tool",
                    detail = "unknown child tool: ${toolName.ifEmpty { "<blank>" }}",
                )

            toolName !in inputs.callerToolNames ->
                return SubAgentExecutionProfileResolution.Rejected(
                    error = "tool_not_authorized",
                    detail = "the parent turn did not expose tool: $toolName",
                )

            toolName !in inputs.headlessToolNames ->
                return SubAgentExecutionProfileResolution.Rejected(
                    error = "tool_unavailable_headless",
                    detail = "tool requires an interactive surface: $toolName",
                )
        }
    }
    val effectiveTools = requestedTools?.toSet()
        ?: inputs.callerToolNames.intersect(inputs.headlessToolNames)

    return SubAgentExecutionProfileResolution.Resolved(
        SubAgentExecutionProfile(
            runId = runId,
            effectiveModelId = effectiveModelId,
            promptSource = promptSource,
            effectiveSystemPrompt = prompt,
            effectiveToolNames = effectiveTools,
            maxToolTrips = request.maxTrips,
        ),
    )
}

/** Returns only tools that can run without an interactive approval or Activity surface. */
internal fun subAgentHeadlessToolNames(callerToolNames: Set<String>): Set<String> =
    callerToolNames.filterTo(linkedSetOf()) { toolName ->
        val recursiveOrManagement = toolName.startsWith("subagent_") ||
            toolName.startsWith("research_") ||
            toolName.startsWith("setup_") ||
            toolName.startsWith("assistant_") ||
            toolName.startsWith("conversation_") ||
            toolName.startsWith("lorebook_") ||
            toolName.startsWith("mode_injection_") ||
            toolName.startsWith("app_settings_") ||
            toolName.startsWith("rikkahub_")
        if (recursiveOrManagement || toolName == "ask_user") {
            return@filterTo false
        }
        if (toolName in ToolApprovalDefaults.NO_ALWAYS_ALLOW) {
            return@filterTo false
        }
        when (CapabilityCatalog.toolInvocationSurface(toolName)) {
            ToolInvocationSurface.Activity -> toolName in BrowserToolDefaults.ALL_TOOLS
            ToolInvocationSurface.SystemConsent -> false
            else -> true
        }
    }

object SubAgentRequestValidator {

    sealed class Result {
        data class Ok(val request: SubAgentRequest) : Result()
        data class Reject(val error: String, val detail: String) : Result()
    }

    fun validate(request: SubAgentRequest): Result {
        val task = request.task.trim()
        if (task.isEmpty()) {
            return Result.Reject("invalid_task", "task is required and may not be blank")
        }
        if (request.timeoutSeconds < 1) {
            return Result.Reject(
                "invalid_timeout",
                "timeout_seconds must be at least 1; got ${request.timeoutSeconds}"
            )
        }
        if (request.timeoutSeconds > SubAgentDefaults.MAX_TIMEOUT_SECONDS) {
            return Result.Reject(
                "invalid_timeout",
                "timeout_seconds exceeds max ${SubAgentDefaults.MAX_TIMEOUT_SECONDS}; got ${request.timeoutSeconds}"
            )
        }
        if (request.maxTrips < 1) {
            return Result.Reject(
                "invalid_max_trips",
                "max_trips must be at least 1; got ${request.maxTrips}"
            )
        }
        if (request.maxTrips > SubAgentDefaults.MAX_MAX_TRIPS) {
            return Result.Reject(
                "invalid_max_trips",
                "max_trips exceeds max ${SubAgentDefaults.MAX_MAX_TRIPS}; got ${request.maxTrips}"
            )
        }
        request.label?.let {
            if (it.length > SubAgentDefaults.MAX_LABEL_LENGTH) {
                return Result.Reject(
                    "invalid_label",
                    "label exceeds ${SubAgentDefaults.MAX_LABEL_LENGTH} chars; got ${it.length}"
                )
            }
        }
        return Result.Ok(request.copy(task = task))
    }
}
