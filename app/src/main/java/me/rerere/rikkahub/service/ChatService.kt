package me.rerere.rikkahub.service

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.ai.ui.FinalAnswerRecoveryStatus
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UIMessageState
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.group.GroupChatEngine
import me.rerere.rikkahub.data.ai.GenerationPersistenceBarrier
import me.rerere.rikkahub.data.ai.resolveInteractiveGenerationMaxSteps
import me.rerere.rikkahub.data.ai.resolveInteractiveGenerationTurnBudgetMs
import me.rerere.rikkahub.data.ai.sanitizeTransientConversationToolResults
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.WebSearchPolicy
import me.rerere.rikkahub.data.ai.tools.createConversationTools
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.ai.waifu.WaifuMergeTransformer
import me.rerere.rikkahub.data.ai.waifu.WaifuSentenceSplitter
import me.rerere.rikkahub.data.ai.waifu.withWaifuText
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.DEFAULT_AUTO_MODEL_ID
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.getChatModelForAssistant
import me.rerere.rikkahub.data.datastore.CompressOpenAIConfig
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AutoCompressConfig
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.messagesForGeneration
import me.rerere.rikkahub.personal.heartbeat.HeartbeatUserActivity
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.withRuntimeGraph
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.ConversationSourceInvalidationMode
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.selectedMemorySourceVersions
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.MemoryRetrievalDiagnosticsStore
import me.rerere.rikkahub.data.repository.MemoryRetrievalQuerySource
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.diagnostics.agenttiming.AgentTimingEventKind
import me.rerere.rikkahub.diagnostics.agenttiming.AgentTimingEventResult
import me.rerere.rikkahub.diagnostics.agenttiming.AgentTimingHandle
import me.rerere.rikkahub.diagnostics.agenttiming.AgentTimingStore
import me.rerere.rikkahub.diagnostics.agenttiming.AgentTimingSubmissionToken
import me.rerere.rikkahub.diagnostics.agenttiming.AgentTimingTraceStatus
import me.rerere.rikkahub.diagnostics.agenttiming.hasAgentTimingRenderableContent
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.ConflictException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.rikkahub.utils.sendNotification
import me.rerere.rikkahub.utils.cancelNotification
import me.rerere.workspace.WorkspaceShellStatus
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.ai.GenerationRunControl
import me.rerere.rikkahub.data.ai.tools.CancelRequestResult
import me.rerere.rikkahub.data.ai.tools.ToolCancelReason
import me.rerere.rikkahub.service.chat.ChatCommand
import me.rerere.rikkahub.service.chat.PetDialogueCommand
import me.rerere.rikkahub.service.chat.CancelCurrentToolCommand
import me.rerere.rikkahub.service.chat.CommandEnvelope
import me.rerere.rikkahub.service.chat.CommandOrigin
import me.rerere.rikkahub.service.chat.CommandOutcome
import me.rerere.rikkahub.service.chat.ConversationRuntime
import me.rerere.rikkahub.service.chat.InterruptCommand
import me.rerere.rikkahub.service.chat.InterruptRegenerateCommand
import me.rerere.rikkahub.service.chat.PersistenceCoordinator
import me.rerere.rikkahub.service.chat.RawUserContent
import me.rerere.rikkahub.service.chat.RuntimeCommandExecutor
import me.rerere.rikkahub.service.chat.RuntimeHydrator
import me.rerere.rikkahub.service.chat.SendMessageCommand
import me.rerere.rikkahub.service.chat.StopCommand
import me.rerere.rikkahub.service.chat.SteerCommand
import me.rerere.rikkahub.service.chat.SteeringScope
import me.rerere.rikkahub.service.chat.StableCommandException
import me.rerere.rikkahub.service.chat.SubmitResult
import me.rerere.rikkahub.service.chat.ToolApprovalCommand
import me.rerere.rikkahub.service.chat.ToolDecision
import me.rerere.rikkahub.service.chat.NormalCommand
import me.rerere.rikkahub.service.chat.RegenerateCommand
import me.rerere.rikkahub.memory.MemorySourceVersion
import me.rerere.rikkahub.service.chat.RunOutcome
import me.rerere.rikkahub.service.chat.DispatcherProvider
import me.rerere.rikkahub.service.chat.DurableCommandQueue
import me.rerere.rikkahub.service.chat.EmergencyCommand
import me.rerere.rikkahub.service.chat.FastPathContext
import me.rerere.rikkahub.service.chat.FastPathDecision
import me.rerere.rikkahub.service.chat.FastPathRouter
import me.rerere.rikkahub.service.chat.FastPathCommitPlan
import me.rerere.rikkahub.service.chat.buildFastPathCommitPlan
import me.rerere.rikkahub.service.chat.toAnchoredUserMessage
import me.rerere.rikkahub.service.chat.ResumeAfterApprovalCommand
import me.rerere.rikkahub.service.chat.ResumeQueueCommand
import me.rerere.rikkahub.service.chat.ClearPendingQueueCommand
import me.rerere.rikkahub.service.chat.CancelQueuedCommand
import me.rerere.rikkahub.service.chat.CancelSteeringCommand
import me.rerere.rikkahub.service.chat.UpdateQueuedMessageCommand
import me.rerere.rikkahub.service.chat.PromoteQueuedMessageToSteeringCommand
import me.rerere.rikkahub.service.chat.QueuedMessageUiEntry
import me.rerere.rikkahub.subagent.allowsTool
import me.rerere.rikkahub.subagent.generationMaxSteps
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.prompts.buildVoiceCallAudioTagPrompt
import me.rerere.rikkahub.data.ai.prompts.buildVoiceCallAudioTaggingRequest
import me.rerere.rikkahub.data.ai.tools.REQUEST_VOICE_CALL_TOOL_NAME
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.data.voice.VOICE_CALL_UNAVAILABLE_MESSAGE
import me.rerere.rikkahub.data.voice.ChatVoiceReplyMaterializer
import me.rerere.rikkahub.data.voice.chatVoiceReply
import me.rerere.rikkahub.data.voice.updateChatVoiceReplySegment
import me.rerere.rikkahub.data.voice.VoiceCallCompletion
import me.rerere.rikkahub.data.voice.voiceCallRecord
import me.rerere.rikkahub.data.voice.VoiceCallAudioTagFormat
import me.rerere.rikkahub.data.voice.VoiceCallAudioTagSelectionResult
import me.rerere.rikkahub.data.voice.VoiceCallTaggingFallbackReason
import me.rerere.rikkahub.data.voice.consumePendingVoiceCallEndedEvent
import me.rerere.rikkahub.data.voice.createVoiceCallAudioTagSelectionTool
import me.rerere.rikkahub.data.voice.splitVoiceCallAudioTaggingSegments
import me.rerere.rikkahub.data.voice.selectVoiceCallAudioTaggingSegmentIndexes
import me.rerere.rikkahub.data.voice.parseVoiceCallAudioTagResponse
import me.rerere.rikkahub.data.voice.voiceCallAudioTagFormatOrNull
import me.rerere.rikkahub.data.voice.VoiceCallAudioTagAssignment
import me.rerere.rikkahub.data.voice.VoiceCallAudioTagMode
import me.rerere.rikkahub.data.voice.forVoiceCallProvider
import me.rerere.rikkahub.data.voice.VoiceCallTagSelectionSource
import me.rerere.rikkahub.data.voice.withSelectedVoiceCallAudioTagAssignments
import me.rerere.rikkahub.data.voice.voiceCallAudioTagAssignmentsOrEmpty
import me.rerere.rikkahub.data.voice.withIncrementalVoiceCallAudioTagAssignments
import me.rerere.rikkahub.data.voice.withoutVoiceCallAudioTagsForNormalContext
import me.rerere.rikkahub.data.voice.sanitizeVoiceCallTextForTranslation

private const val TAG = "ChatService"
private const val FAST_PATH_TOOL_BUDGET_MS = 30_000L
private const val STREAMING_UI_UPDATE_INTERVAL_NANOS = 50_000_000L

// Rolling-summary compression (ported from jude).
private const val MIN_COMPRESSION_CHUNK_TOKENS = 8000
private const val COMPRESSION_CHUNK_TOKENS_PER_TARGET_TOKEN = 8

private const val VOICE_CALL_SYSTEM_PROMPT_COMMON = """
你正在语音通话模式中回复用户。
不要再次发起、邀请、请求或切换到另一通语音通话，也不要调用打电话工具。
如果用户的表达暗示想打电话，只需在当前通话中继续回应，不要把它当成新的拨号请求。
请像真实电话聊天一样自然、简短、连贯地说话。
每一句都尽量短，适合一句一句朗读。
每一句都必须用句号、问号或感叹号结束。
只回答当前最需要回应的内容，通常使用一个短段落；内容已经完整时立即结束，不要为了凑长度继续展开。
不使用 Markdown 表格，不写长列表。
如果需要解释复杂问题，分成几个容易朗读的小块。
一次最多问用户一个问题。
不要使用任何表情、emoji 或颜文字。
不要输出贴纸、表情包、颜文字、ASCII 表情或类似“(≧▽≦)”的符号组合。
任何 emoji、颜文字、表情包文本都会被系统硬性删除。
"""

private val VOICE_CALL_ACTIVE_TOOL_STATUS = """
    Voice-call state: ACTIVE. The call is connected.
    Follow the current voice-call system instructions for audio-tag output. Do not invent a second
    tag policy in this tool result.
""".trimIndent()

private val VOICE_CALL_ENDED_TOOL_STATUS = """
    Voice-call state: ENDED. The call has been disconnected.
    The call-specific audio-tag policy no longer applies after hangup.
""".trimIndent()

private const val PROACTIVE_VOICE_CALL_SYSTEM_PROMPT = """
你可以使用 request_voice_call 工具主动邀请用户进行语音通话。
只在实时说话明显比继续打字更自然、更有帮助时发起来电，不要频繁使用，也不要为了制造效果而来电。
调用时给出一句简短、自然的来电理由，不要在正文里假装电话已经接通。
如果用户接听，立即用一句简短自然的话开始通话，并继续遵守语音通话的简短口语风格。
如果用户拒接或未接，尊重结果，不要立刻再次发起，也不要责备或施压。
"""

// Tool-history preservation (plan B, semantics from extv ContextCompactionPlanner). N equals
// extv's default autoCompactionKeepRecentToolCalls; the token budget bounds the whole ledger.
private const val COMPRESSION_TOOL_HISTORY_KEEP_RECORDS = 5
private const val COMPRESSION_TOOL_HISTORY_MAX_TOKENS = 4_000
private const val TOOL_HISTORY_HEADER = "[Tool execution history — authoritative retained context]"
private const val TOOL_HISTORY_FOOTER = "[End tool execution history]"
private const val TOOL_RECORD_HEADER = "[Retained tool execution record]"
private const val TOOL_RECORD_FOOTER = "[End retained tool execution record]"

internal fun backgroundTextGenerationParams(
    model: Model,
    reasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    // Compression/title/suggestion calls are non-interactive. On generic OpenAI-compatible
    // gateways, an explicit disabled reasoning field (for example `reasoning_effort: low`) can
    // itself be rejected with HTTP 400. Keep ordinary chat behavior unchanged, but omit it here.
    omitReasoningConfigurationWhenOff = true,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
)

/** Timeout bounding one edit outcome await; a paused/unavailable runtime must not hang the UI. */
internal val EDIT_MESSAGE_OUTCOME_TIMEOUT: kotlin.time.Duration = 15.seconds

/**
 * ExTV 语义：编辑必须给调用方一个确定的终局。超时（outcome 为 null）与任何非 Completed
 * 终局都折叠成用户可见的拒绝，绝不 error() 崩溃。
 */
internal fun messageEditSubmissionResult(
    submission: SubmitResult,
    outcome: CommandOutcome?,
): SubmitResult = when {
    outcome == null -> SubmitResult.Rejected("消息编辑等待超时，请稍后重试")
    outcome == CommandOutcome.Completed -> submission
    else -> SubmitResult.Rejected("消息编辑未能生效：$outcome")
}

/**
 * 空文本 preset 条目（手写空行、酒馆卡空 first_mes）在新会话物化成一条只剩操作行的
 * 空消息。出生点统一过滤：空条目不入会话，非空 preset 原样保留。
 */
internal fun effectivePresetMessages(presetMessages: List<UIMessage>): List<UIMessage> =
    presetMessages.filterNot { it.parts.isEmptyUIMessage() }

internal fun splitMessagesForCompression(
    messages: List<UIMessage>,
    targetTokens: Int,
): List<List<UIMessage>> = splitByEstimatedCompressionTokens(
    items = messages,
    targetTokens = targetTokens,
    textOf = { it.summaryAsText() },
)

internal fun splitTextsForCompression(
    texts: List<String>,
    targetTokens: Int,
): List<List<String>> = splitByEstimatedCompressionTokens(
    items = texts,
    targetTokens = targetTokens,
    textOf = { it },
)

internal fun compressionChunkTokenBudget(targetTokens: Int): Int {
    return maxOf(
        MIN_COMPRESSION_CHUNK_TOKENS,
        targetTokens.coerceAtLeast(1) * COMPRESSION_CHUNK_TOKENS_PER_TARGET_TOKEN,
    )
}

internal fun effectiveCompressionKeepRecentMessages(keepRecentMessages: Int): Int {
    return keepRecentMessages.coerceAtLeast(1)
}

internal fun estimateCompressionTokens(text: String): Int {
    var asciiChars = 0
    var nonAsciiChars = 0
    text.forEach { char ->
        if (char.code <= 0x7F) {
            asciiChars++
        } else {
            nonAsciiChars++
        }
    }
    return ((asciiChars + 3) / 4 + nonAsciiChars).coerceAtLeast(1)
}

private fun <T> splitByEstimatedCompressionTokens(
    items: List<T>,
    targetTokens: Int,
    textOf: (T) -> String,
): List<List<T>> {
    if (items.isEmpty()) return emptyList()

    val tokenBudget = compressionChunkTokenBudget(targetTokens)
    val chunks = mutableListOf<List<T>>()
    var currentChunk = mutableListOf<T>()
    var currentTokens = 0

    items.forEach { item ->
        val itemTokens = estimateCompressionTokens(textOf(item))
        if (currentChunk.isNotEmpty() && currentTokens + itemTokens > tokenBudget) {
            chunks += currentChunk
            currentChunk = mutableListOf()
            currentTokens = 0
        }
        currentChunk += item
        currentTokens += itemTokens
    }

    if (currentChunk.isNotEmpty()) {
        chunks += currentChunk
    }
    return chunks
}

/** A node carries at least one completed tool call worth preserving verbatim. */
internal fun MessageNode.hasCompletedToolRecord(): Boolean =
    currentMessage.parts.any { part -> part is UIMessagePart.Tool && part.isExecuted }

private fun truncateToCompressionTokenBudget(text: String, maxTokens: Int): String {
    if (maxTokens <= 0) return "[tool record omitted: tool history budget exhausted]"
    if (estimateCompressionTokens(text) <= maxTokens) return text

    var asciiChars = 0
    var nonAsciiChars = 0
    var end = 0
    while (end < text.length) {
        if (text[end].code <= 0x7F) asciiChars++ else nonAsciiChars++
        if (nonAsciiChars + (asciiChars + 3) / 4 > maxTokens) break
        end++
    }
    return text.substring(0, end).trimEnd() + " …[truncated]"
}

private fun completedToolRecords(message: UIMessage): List<String> = message.parts.mapNotNull { part ->
    when (part) {
        is UIMessagePart.Tool -> {
            if (!part.isExecuted) return@mapNotNull null
            buildString {
                appendLine("- Call ID: ${part.toolCallId}")
                appendLine("- Tool: ${part.toolName}")
                appendLine("  Result:")
                appendLine(
                    part.output.joinToString("\n") { output -> output.toLedgerText() }
                        .ifBlank { "(empty output)" },
                )
                append("  Input: ${part.input}")
            }.trim()
        }
        else -> null
    }
}

/**
 * Builds the deterministic tool-execution ledger appended after the compressed summary
 * (plan B). Records are extracted verbatim from the compressed nodes — never re-generated
 * by the summary model — and only the most recent [maxRecords] calls are retained.
 */
internal fun buildCompressedToolHistoryLedger(
    compressedNodes: List<MessageNode>,
    maxRecords: Int = COMPRESSION_TOOL_HISTORY_KEEP_RECORDS,
    maxTokens: Int = COMPRESSION_TOOL_HISTORY_MAX_TOKENS,
): String? {
    if (compressedNodes.isEmpty() || maxRecords <= 0 || maxTokens <= 0) return null
    val records = compressedNodes.flatMap { node -> completedToolRecords(node.currentMessage) }
    val retained = records.takeLast(maxRecords)
    if (retained.isEmpty()) return null

    val header = "$TOOL_HISTORY_HEADER\n"
    val perRecordBudget = (maxTokens - estimateCompressionTokens(header)).coerceAtLeast(0) / retained.size
    return buildString {
        append(header)
        retained.forEach { record ->
            appendLine(TOOL_RECORD_HEADER)
            appendLine(truncateToCompressionTokenBudget(record, perRecordBudget))
            appendLine(TOOL_RECORD_FOOTER)
        }
        appendLine(TOOL_HISTORY_FOOTER)
    }.trim()
}

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

internal data class TrackedCommandSubmission(
    val submission: SubmitResult,
    val outcome: Deferred<CommandOutcome>,
)

internal data class DurableRegenerationBaseline(
    val assistantScopeId: String,
    val selectedMessageIds: List<String>,
    val selectedSourceVersions: List<MemorySourceVersion>,
)

internal fun ChatCommand.durableRegenerationBaselineOrNull(): DurableRegenerationBaseline? {
    val regeneration = when (this) {
        is RegenerateCommand -> this
        is InterruptRegenerateCommand -> this.regeneration
        else -> return null
    }
    val assistantScopeId = regeneration.baselineAssistantScopeId
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: return null
    val selectedSourceVersions = normalizeDurableSourceVersions(
        regeneration.baselineSelectedSourceVersions,
    )
    val selectedMessageIds = (regeneration.baselineSelectedMessageIds.asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        + selectedSourceVersions.asSequence().map(MemorySourceVersion::messageId))
        .distinct()
        .sorted()
        .toList()
        .takeIf { it.isNotEmpty() }
        ?: return null
    return DurableRegenerationBaseline(
        assistantScopeId = assistantScopeId,
        selectedMessageIds = selectedMessageIds,
        selectedSourceVersions = selectedSourceVersions,
    )
}

private fun normalizeDurableSourceVersions(
    versions: Collection<MemorySourceVersion>,
): List<MemorySourceVersion> = versions.asSequence()
    .map { version ->
        MemorySourceVersion(
            messageId = version.messageId.trim(),
            consumedTextDigest = version.consumedTextDigest.trim().lowercase(),
        )
    }
    .filter { version ->
        version.messageId.isNotEmpty() &&
            version.consumedTextDigest.length == 64 &&
            version.consumedTextDigest.all { char -> char in '0'..'9' || char in 'a'..'f' }
    }
    .distinct()
    .sortedWith(compareBy(MemorySourceVersion::messageId, MemorySourceVersion::consumedTextDigest))
    .toList()

private data class DeferredGenerationPostCommit(
    val conversationId: Uuid,
    val commandOrigin: CommandOrigin,
    val toolOrigin: ToolCallOrigin,
    val assistant: Assistant,
    val conversation: Conversation,
    val isSubAgent: Boolean,
)

private fun rejectedTrackedCommand(reason: String) = TrackedCommandSubmission(
    submission = SubmitResult.Rejected(reason),
    outcome = CompletableDeferred(CommandOutcome.Rejected(reason)),
)

data class ChatEmergencyStopResult(
    val runtimeCount: Int,
    val stoppedRuntimeCount: Int,
    val clearedQueueCount: Int,
    val failures: Map<String, String> = emptyMap(),
) {
    val ok: Boolean get() = failures.isEmpty() &&
        stoppedRuntimeCount == runtimeCount && clearedQueueCount == runtimeCount
}

internal fun resolveGenerationCommandId(
    activeCommandId: Uuid?,
    runId: Uuid?,
): Uuid? = activeCommandId ?: runId

/** Authority correlation never treats an ephemeral generation run as an admitted command. */
internal fun resolveAuthoritativeCommandId(activeCommandId: Uuid?): Uuid? = activeCommandId

internal fun List<UIMessage>.withResponseCorrelation(
    annotation: UIMessageAnnotation?,
): List<UIMessage> {
    annotation ?: return this
    val sourceIndex = indexOfLast { message ->
        message.role == MessageRole.USER && annotation in message.annotations
    }
    if (sourceIndex < 0) return this
    return mapIndexed { index, message ->
        if (index > sourceIndex && message.role == MessageRole.ASSISTANT && annotation !in message.annotations) {
            message.copy(annotations = message.annotations + annotation)
        } else {
            message
        }
    }
}

private fun UIMessageAnnotation.isResponseCorrelation(): Boolean =
    this is UIMessageAnnotation.QuickCapture || this is UIMessageAnnotation.PetHandoff

internal data class ChatEmergencyRuntimeTarget(
    val conversationId: Uuid,
    val submitStop: () -> ChatEmergencyCommandSubmission,
    val clearQueue: suspend () -> ChatEmergencyCommandSubmission,
)

internal data class ChatEmergencyCommandSubmission(
    val submission: SubmitResult,
    val outcome: Deferred<CommandOutcome>,
)

internal suspend fun stopChatRuntimeSnapshot(
    targets: List<ChatEmergencyRuntimeTarget>,
): ChatEmergencyStopResult {
    val reports = coroutineScope {
        targets.map { target ->
            async {
                val failures = linkedMapOf<String, String>()
                val stop = runCatching { target.submitStop() }.getOrElse { error ->
                    ChatEmergencyCommandSubmission(
                        SubmitResult.Rejected(error.message ?: error.javaClass.simpleName),
                        CompletableDeferred(CommandOutcome.Failed(error)),
                    )
                }
                val clear = runCatching { target.clearQueue() }.getOrElse { error ->
                    ChatEmergencyCommandSubmission(
                        SubmitResult.Rejected(error.message ?: error.javaClass.simpleName),
                        CompletableDeferred(CommandOutcome.Failed(error)),
                    )
                }
                val stopConfirmed = confirmEmergencySubmission(
                    key = "${target.conversationId}:stop",
                    command = stop,
                    failures = failures,
                )
                val clearConfirmed = confirmEmergencySubmission(
                    key = "${target.conversationId}:queue",
                    command = clear,
                    failures = failures,
                )
                Triple(stopConfirmed, clearConfirmed, failures)
            }
        }.awaitAll()
    }
    val stopped = reports.count { it.first }
    val cleared = reports.count { it.second }
    val failures = linkedMapOf<String, String>().apply {
        reports.forEach { putAll(it.third) }
    }
    return ChatEmergencyStopResult(
        runtimeCount = targets.size,
        stoppedRuntimeCount = stopped,
        clearedQueueCount = cleared,
        failures = failures,
    )
}

private suspend fun confirmEmergencySubmission(
    key: String,
    command: ChatEmergencyCommandSubmission,
    failures: MutableMap<String, String>,
): Boolean {
    when (val result = command.submission) {
        is SubmitResult.Accepted -> Unit
        is SubmitResult.QueueFull -> {
            failures[key] = "Queue full (${result.limit})"
            return false
        }
        is SubmitResult.Rejected -> {
            failures[key] = result.reason
            return false
        }
        is SubmitResult.RuntimeUnavailable -> {
            failures[key] = result.reason
            return false
        }
    }
    val outcome = withTimeoutOrNull(CHAT_EMERGENCY_CONFIRM_TIMEOUT_MS) { command.outcome.await() }
    if (outcome == CommandOutcome.Completed) return true
    failures[key] = when (outcome) {
        null -> "Timed out waiting for Runtime confirmation"
        is CommandOutcome.Rejected -> outcome.reason
        is CommandOutcome.Conflict -> outcome.reason
        is CommandOutcome.NotApplied -> outcome.reason
        is CommandOutcome.Failed -> outcome.error.message ?: outcome.error.javaClass.simpleName
        else -> outcome.toString()
    }
    return false
}

private const val CHAT_EMERGENCY_CONFIRM_TIMEOUT_MS = 30_000L

internal fun Conversation.withGeneratedTitle(title: String): Conversation = copy(title = title)

internal fun Conversation.withGeneratedSuggestions(suggestions: List<String>): Conversation =
    copy(chatSuggestions = suggestions)

private fun Conversation.latestAssistantNeedsFinalAnswer(): Boolean =
    currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
        ?.state == UIMessageState.INCOMPLETE_NO_VISIBLE_ANSWER

internal fun Conversation.selectedPendingToolIds(): Set<String> =
    currentMessages.asSequence()
        .flatMap { message -> message.parts.asSequence() }
        .filterIsInstance<UIMessagePart.Tool>()
        .filter(UIMessagePart.Tool::isPending)
        .mapNotNull(UIMessagePart.Tool::toolCallId)
        .toSet()

internal fun Conversation.isEligibleForGenerationPostCommit(): Boolean =
    !latestAssistantNeedsFinalAnswer() && selectedPendingToolIds().isEmpty()

internal fun ChatCommand.requiresMemorySourceReadiness(): Boolean = when (this) {
    is SendMessageCommand,
    is InterruptCommand,
    is InterruptRegenerateCommand,
    is ToolApprovalCommand,
    is RegenerateCommand,
    ResumeAfterApprovalCommand,
    -> true

    else -> false
}

/**
 * 生成保活判定（移植自 extv，batch 11a）：会驱动模型/工具回合的前台工作命令在运行期间
 * 占用前台服务；与 [requiresMemorySourceReadiness] 同一命令集合。answer=false 的投递
 * 只落库、不触发生成（对齐 extv 的 keepAliveInBackground = answer），不占用前台。
 */
private fun ChatCommand.keepsForegroundWhileRunning(): Boolean = when (this) {
    is SendMessageCommand -> content.answer
    is InterruptCommand -> replacement.content.answer
    is InterruptRegenerateCommand -> true
    is RegenerateCommand -> true
    is ToolApprovalCommand -> true
    ResumeAfterApprovalCommand -> true
    else -> false
}

/**
 * Returns the startup reconciliation failure for model-facing commands, or null when it is safe
 * to continue. Cancellation and VM errors are never converted into an ordinary rejection.
 */
internal suspend fun memorySourceReadinessFailureOrNull(
    command: ChatCommand,
    readiness: Deferred<Unit>,
): Exception? {
    if (!command.requiresMemorySourceReadiness()) return null
    return try {
        readiness.await()
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        error
    }
}

private fun Conversation.latestFinalAnswerFailure(): StableCommandException? {
    val message = currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
        ?.takeIf { it.state == UIMessageState.INCOMPLETE_NO_VISIBLE_ANSWER }
        ?: return null
    val recovery = message.annotations
        .filterIsInstance<UIMessageAnnotation.FinalAnswerRecovery>()
        .lastOrNull()
    val reason = recovery?.reason.orEmpty()
    val code = when {
        "time_budget" in reason -> "FINAL_ANSWER_TIME_BUDGET_EXHAUSTED"
        "eof" in reason -> "FINAL_ANSWER_EOF"
        "tool_call" in reason -> "FINAL_ANSWER_ATTEMPTED_TOOL_CALL"
        else -> "FINAL_ANSWER_RECOVERY_EXHAUSTED"
    }
    val safeReason = reason
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9_:-]"), "_")
        .take(120)
        .ifBlank { "no_visible_answer" }
    return StableCommandException(
        durableErrorCode = code,
        durableErrorMessage = "The model did not produce a visible final answer ($safeReason).",
    )
}

internal suspend fun runRegenerationTransaction(
    restore: suspend () -> Unit,
    operation: suspend () -> RunOutcome,
): RunOutcome {
    return try {
        val outcome = operation()
        if (outcome !is RunOutcome.Completed && outcome !is RunOutcome.WaitingApproval) {
            withContext(NonCancellable) { restore() }
        }
        outcome
    } catch (error: Throwable) {
        runCatching {
            withContext(NonCancellable) { restore() }
        }.exceptionOrNull()?.let(error::addSuppressed)
        throw error
    }
}

internal suspend fun <T> withCommandHeadlessScope(
    conversationId: Uuid,
    origin: CommandOrigin,
    control: GenerationRunControl? = null,
    block: suspend () -> T,
): T {
    if (origin != CommandOrigin.CRON) return block()
    me.rerere.rikkahub.data.ai.tools.HeadlessConversations.markTransient(conversationId)
    val released = AtomicBoolean(false)
    val release = {
        if (released.compareAndSet(false, true)) {
            me.rerere.rikkahub.data.ai.tools.HeadlessConversations.unmarkTransient(conversationId)
        }
    }
    val cancellationRegistration = control?.registerCancellationCallback(release)
    return try {
        block()
    } finally {
        cancellationRegistration?.close()
        release()
    }
}

enum class ChatErrorSolution {
    CheckTitleModelSettings,
}

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

/** Visible text of a message: the Text parts joined verbatim (splitter offset math). */
private fun UIMessage.textPartsJoined(): String =
    parts.filterIsInstance<UIMessagePart.Text>().joinToString(separator = "") { it.text }

/**
 * Waifu typewriter streaming state, scoped to one [ChatService.handleMessageComplete]
 * run's collect loop (chunks are consumed sequentially in one coroutine, so plain
 * mutable state needs no locking).
 *
 * The sentence splitter runs on the not-yet-split remainder of the current streaming
 * message on every chunk. Finished sentences become independent annotated bubbles:
 * - Bubble 1 reuses the original streaming message id (annotated, text trimmed to the
 *   sentence).
 * - Bubbles 2..n promote the current tail bubble in place to the finished sentence and
 *   mint a fresh tail id for the remainder, so the same-id streaming fast path keeps
 *   updating exactly one node.
 * - The growing tail is itself annotated with the group, so even a process kill
 *   mid-stream leaves a shape the request-side merge transformer can reassemble.
 */
private class WaifuStreamState(
    private val splitter: WaifuSentenceSplitter,
    private val charDelayMs: Long,
    private val maxDelayMs: Long,
) {
    /** One split group == one streaming assistant message (one generation step). */
    private class GroupState(val groupId: Uuid) {
        /** Committed bubbles as (messageId, text); bubble 1's id == groupId. */
        val bubbles = mutableListOf<Pair<Uuid, String>>()
        var committedChars = 0
        var tailMessageId: Uuid? = null
        var flushed = false
    }

    private class Rebuilt(
        val messages: List<UIMessage>,
        /** The still-growing tail bubble, when one is materialized. */
        val tail: UIMessage?,
    )

    private var activeGroup: GroupState? = null
    private val finishedGroups = linkedMapOf<Uuid, GroupState>()
    private var turnHasToolCall = false

    /** True once any sentence bubble was materialized in this run. */
    var committedAnyText: Boolean = false
        private set

    val hasCommittedBubbles: Boolean
        get() = activeGroup?.bubbles?.isNotEmpty() == true ||
            finishedGroups.values.any { it.bubbles.isNotEmpty() }

    class Advance(
        val hasNewSentences: Boolean,
        val hasCommittedBubbles: Boolean,
        /** Rebuilt bubble 1 (original message id) for the same-id fast path. */
        val firstBubbleMessage: UIMessage?,
        /** Current tail bubble for the same-id fast path; null when nothing to write. */
        val tailMessage: UIMessage?,
    ) {
        companion object {
            val NO_CHANGE = Advance(
                hasNewSentences = false,
                hasCommittedBubbles = false,
                firstBubbleMessage = null,
                tailMessage = null,
            )
        }
    }

    /**
     * Advances the split state for one chunk. Suspending: applies the per-sentence
     * typewriter delay (the turn's first sentence: 0) and, after each committed
     * sentence, invokes [writeBubbles] so the bubble appears before the next delay.
     * [forceFlush] (end-of-stream FINAL barrier) commits the unfinished tail as the
     * last bubble so the final sentence always reaches disk.
     */
    suspend fun advance(
        messages: List<UIMessage>,
        forceFlush: Boolean,
        writeBubbles: suspend () -> Unit,
    ): Advance {
        val streamMessage = messages.lastOrNull() ?: return Advance.NO_CHANGE
        if (streamMessage.role != MessageRole.ASSISTANT) return Advance.NO_CHANGE
        if (streamMessage.parts.any { part ->
                part is UIMessagePart.Tool || part is UIMessagePart.ToolCall ||
                    part is UIMessagePart.ToolResult
            }
        ) {
            // A tool-call turn is never split (whole-turn rule). Bubbles committed
            // before the tool call deltas appeared stay split; the request-side merge
            // transformer keeps the provider-visible shape valid either way.
            turnHasToolCall = true
        }
        if (turnHasToolCall) return Advance.NO_CHANGE

        var hasNewSentences = false
        val current = activeGroup
        if (current == null) {
            activeGroup = GroupState(streamMessage.id)
        } else if (streamMessage.id != current.groupId) {
            // Step boundary: the previous step's message is complete — flush its tail.
            val previous = messages.firstOrNull { it.id == current.groupId }
            if (previous != null && commitTail(current, previous.textPartsJoined())) {
                hasNewSentences = true
                writeBubbles()
            }
            finishedGroups[current.groupId] = current
            activeGroup = GroupState(streamMessage.id)
        }
        val group = requireNotNull(activeGroup)

        if (!group.flushed) {
            val fullText = streamMessage.textPartsJoined()
            val safeCommitted = group.committedChars.coerceAtMost(fullText.length)
            val split = splitter.split(fullText.substring(safeCommitted))
            for (sentence in split.sentences) {
                // Turn's first sentence appears immediately (首句 0); every later
                // sentence waits out its per-character delay first.
                if (group.bubbles.isNotEmpty()) {
                    delay(sentenceDelayMs(sentence.length))
                }
                if (group.bubbles.isEmpty()) {
                    // First bubble reuses the original streaming message id.
                    group.bubbles.add(group.groupId to sentence)
                } else {
                    group.bubbles.add((group.tailMessageId ?: Uuid.random()) to sentence)
                }
                group.tailMessageId = Uuid.random()
                group.committedChars += sentence.length
                hasNewSentences = true
                committedAnyText = true
                writeBubbles()
            }
            if (forceFlush && commitTail(group, fullText)) {
                hasNewSentences = true
                writeBubbles()
            }
        }

        if (!hasCommittedBubbles) return Advance.NO_CHANGE
        val rebuilt = rebuildGroup(group, streamMessage)
        return Advance(
            hasNewSentences = hasNewSentences,
            hasCommittedBubbles = true,
            firstBubbleMessage = rebuilt.messages.firstOrNull(),
            tailMessage = rebuilt.tail,
        )
    }

    /**
     * Rebuilds the generation message list with every waifu-split message replaced by
     * its bubble sequence. Messages that do not belong to a split group pass through
     * untouched. Returns null when nothing was ever committed (zero-change guarantee).
     */
    fun project(messages: List<UIMessage>): List<UIMessage>? = projectInternal(messages)

    /**
     * Non-suspending end-of-run projection: flushes the active group's tail first (so
     * the last sentence reaches disk even when the collect loop was cancelled
     * mid-delay), then projects. Returns null when there is nothing to change.
     */
    fun projectFinal(messages: List<UIMessage>?): List<UIMessage>? {
        if (messages == null) return null
        val active = activeGroup
        if (active != null) {
            if (!active.flushed) {
                val origin = messages.firstOrNull { it.id == active.groupId }
                if (origin != null) {
                    commitTail(active, origin.textPartsJoined())
                }
            }
            finishedGroups[active.groupId] = active
            activeGroup = null
        }
        return projectInternal(messages)
    }

    private fun projectInternal(messages: List<UIMessage>): List<UIMessage>? {
        if (!committedAnyText) return null
        var touched = false
        val result = mutableListOf<UIMessage>()
        for (message in messages) {
            val group = finishedGroups[message.id]
                ?: activeGroup?.takeIf { message.id == it.groupId }
            if (group == null) {
                result.add(message)
            } else {
                touched = true
                result.addAll(rebuildGroup(group, message).messages)
            }
        }
        return if (touched) result else null
    }

    /** Commits the unfinished remainder as the final bubble; idempotent per group. */
    private fun commitTail(group: GroupState, fullText: String): Boolean {
        if (group.flushed) return false
        group.flushed = true
        val safeCommitted = group.committedChars.coerceAtMost(fullText.length)
        val remaining = fullText.substring(safeCommitted)
        if (remaining.isBlank()) return false
        if (group.bubbles.isEmpty()) {
            group.bubbles.add(group.groupId to remaining)
        } else {
            group.bubbles.add((group.tailMessageId ?: Uuid.random()) to remaining)
        }
        group.tailMessageId = null
        group.committedChars = fullText.length
        committedAnyText = true
        return true
    }

    private fun sentenceDelayMs(sentenceLength: Int): Long {
        if (charDelayMs <= 0L) return 0L
        return (sentenceLength.toLong() * charDelayMs).coerceAtMost(maxDelayMs)
    }

    private fun rebuildGroup(group: GroupState, origin: UIMessage): Rebuilt {
        if (group.bubbles.isEmpty()) return Rebuilt(listOf(origin), null)
        val groupAnnotation = UIMessageAnnotation.WaifuGroup(groupId = group.groupId.toString())
        val bubbles = group.bubbles.mapIndexed { index, (messageId, text) ->
            val isLastFlushed = group.flushed && index == group.bubbles.lastIndex
            if (messageId == group.groupId) {
                origin
                    .withWaifuText(text)
                    .copy(
                        annotations = origin.annotations + groupAnnotation,
                        state = if (isLastFlushed) origin.state else UIMessageState.COMPLETED,
                    )
            } else {
                UIMessage(
                    id = messageId,
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(text)),
                    annotations = listOf(groupAnnotation),
                    modelId = origin.modelId,
                    state = if (isLastFlushed) origin.state else UIMessageState.COMPLETED,
                    finishedAt = if (isLastFlushed) origin.finishedAt else null,
                )
            }
        }
        if (group.flushed) return Rebuilt(bubbles, null)
        val tailId = group.tailMessageId ?: return Rebuilt(bubbles, null)
        val fullText = origin.textPartsJoined()
        val safeCommitted = group.committedChars.coerceAtMost(fullText.length)
        val remaining = fullText.substring(safeCommitted)
        if (remaining.isBlank()) return Rebuilt(bubbles, null)
        val tail = UIMessage(
            id = tailId,
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(remaining)),
            annotations = listOf(groupAnnotation),
            modelId = origin.modelId,
            state = origin.state,
        )
        return Rebuilt(bubbles + tail, tail)
    }
}

/**
 * Append one applied steering audit message to Conversation JSON.
 *
 * Both persistent (yellow) and transient (purple) guidance stay visible after the run.
 * The command id is the exactly-once key: retries, process recovery, or duplicate runtime
 * callbacks return the original snapshot instead of adding a second history card.
 */
internal fun Conversation.withSteeringAuditMessage(
    note: me.rerere.rikkahub.data.ai.SteeringNote,
): Conversation {
    val alreadyStored = messageNodes.any { node ->
        node.messages.any { message ->
            message.annotations.any { annotation ->
                annotation is UIMessageAnnotation.Steering &&
                    annotation.commandId == note.commandId.toString()
            }
        }
    }
    if (alreadyStored) return this

    val message = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(note.text)),
        annotations = listOf(
            UIMessageAnnotation.Steering(
                commandId = note.commandId.toString(),
                persistent = note.historyMode ==
                    me.rerere.rikkahub.service.chat.SteeringHistoryMode.PERSISTENT,
            )
        ),
    ).toMessageNode()
    return copy(messageNodes = messageNodes + message)
}

/** Debug-only deterministic fault boundaries for the disposable Android integration gate. */
internal enum class ChatServiceProbePoint { BEFORE_ENQUEUE, AFTER_ENQUEUE, BEFORE_EXECUTION }

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val folderRepository: FolderRepository,
    private val memoryRepository: MemoryRepository,
    private val memoryRetrievalDiagnostics: MemoryRetrievalDiagnosticsStore,
    private val agentTimingStore: AgentTimingStore,
    private val memoryV2Coordinator: me.rerere.rikkahub.memory.MemoryV2Coordinator,
    private val dreamExperienceIngestor:
        me.rerere.rikkahub.memory.dreaming.experience.DreamExperienceIngestor,
    private val generationHandler: GenerationHandler,
    private val groupChatEngine: me.rerere.rikkahub.data.ai.group.GroupChatEngine,
    private val chatVoiceReplyMaterializer:
        me.rerere.rikkahub.data.voice.ChatVoiceReplyMaterializer,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val toolApprovalPreferences: me.rerere.rikkahub.data.preferences.ToolApprovalPreferences,
    private val capabilityGrantRepository:
        me.rerere.rikkahub.data.capability.CapabilityGrantRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val workflowRepository: WorkflowRepository,
    private val conversationDeletionPolicy:
        me.rerere.rikkahub.data.repository.ConversationDeletionPolicy,
    private val secondUserSecretVault: me.rerere.rikkahub.security.SecondUserSecretVault,
    private val durableCommandQueue: DurableCommandQueue,
    private val secondUserApprovalLifecycle:
        me.rerere.rikkahub.data.execution.SecondUserApprovalLifecycle,
    private val toolExecutionGate: me.rerere.rikkahub.data.ai.ToolExecutionGate,
    private val toolRuntime: me.rerere.rikkahub.data.ai.execution.ToolRuntime,
    private val pluginToolCatalog: me.rerere.rikkahub.plugin.PluginToolCatalog,
    private val pluginHookBridge: me.rerere.rikkahub.plugin.PluginHookBridge,
    private val pluginRegistryStore: me.rerere.rikkahub.plugin.PluginRegistryStore,
    private val pluginPackageInstaller: me.rerere.rikkahub.plugin.PluginPackageInstaller,
    private val agentSafetySettings: me.rerere.rikkahub.data.ai.AgentSafetySettings,
    private val shizukuBridgeManager: me.rerere.rikkahub.privilege.ShizukuBridgeManager,
    private val workspaceProcessManager: me.rerere.workspace.WorkspaceProcessManager,
    private val structuredPrivilegedCommandExecutor:
        me.rerere.rikkahub.privilege.StructuredPrivilegedCommandExecutor? = null,
    private val subAgentExecutionProfileRegistry:
        me.rerere.rikkahub.subagent.SubAgentExecutionProfileRegistry,
    private val setupTransactionCoordinator:
        me.rerere.rikkahub.setup.SetupTransactionCoordinator,
    private val displayAutomationRuntime: me.rerere.rikkahub.display.DisplayAutomationRuntime? = null,
    private val toolExperienceRepository: me.rerere.rikkahub.toolcatalog.ToolExperienceRepository,
    private val toolShortcutRepository: me.rerere.rikkahub.toolcatalog.ToolShortcutRepository,
    private val secondUserAuthorityService: me.rerere.rikkahub.assistant.SecondUserAuthorityService,
    private val hostOperationDao: me.rerere.rikkahub.owner.db.HostOperationDao,
    private val secretPlaintextSessions: me.rerere.rikkahub.security.SecretPlaintextSessionManager,
    private val ephemeralToolResults: me.rerere.rikkahub.security.EphemeralToolResultStore,
    private val runtimeSecretRedactor: me.rerere.rikkahub.security.RuntimeSecretRedactor,
    private val assistantRemovalService: me.rerere.rikkahub.data.repository.AssistantRemovalService,
    private val persistentTtsLibrary: me.rerere.rikkahub.tts.PersistentTtsLibrary,
    private val workspaceManagedProcessStarter: me.rerere.rikkahub.execution.WorkspaceManagedProcessStarter,
    private val hostLocalServiceDao: me.rerere.rikkahub.owner.db.HostLocalServiceDao,
    private val ownerHttpClient: okhttp3.OkHttpClient,
    private val workflowActionRunner: me.rerere.rikkahub.workflow.execution.WorkflowActionRunner,
    private val automationControlFacade: me.rerere.rikkahub.automation.AutomationControlFacade,
    private val doctorChecks: me.rerere.rikkahub.ui.pages.setting.doctor.DoctorChecks,
    private val executionConsistencyDoctor: me.rerere.rikkahub.diagnostics.ExecutionConsistencyDoctor,
    private val ownerLocalServiceSupervisor: me.rerere.rikkahub.owner.OwnerLocalServiceSupervisor,
    private val agentRunRepository: me.rerere.rikkahub.data.agentrun.AgentRunRepository,
    private val ownerServiceSpecStore: me.rerere.rikkahub.owner.OwnerServiceSpecStore,
    private val ownerTermuxServiceLauncher: me.rerere.rikkahub.owner.OwnerTermuxServiceLauncher,
    private val ownerOperationFingerprinter: me.rerere.rikkahub.owner.OwnerOperationFingerprinter,
    private val localBackupFacade: me.rerere.rikkahub.data.sync.LocalBackupFacade,
    private val petDialogueRepository: me.rerere.rikkahub.pet.PetDialogueRepository,
    private val telegramBotPreferences: me.rerere.rikkahub.data.telegram.TelegramBotPreferences,
    private val telegramCredentialResolver: me.rerere.rikkahub.data.telegram.TelegramCredentialResolver,
    private val reverseGeocodeProviderTestGateway:
        me.rerere.rikkahub.data.ai.tools.local.ReverseGeocodeProviderTestGateway,
    private val dreamReviewRepository:
        me.rerere.rikkahub.memory.dreaming.review.DreamReviewRepository,
    private val commandAdmissionAuthority:
        me.rerere.rikkahub.data.authority.transaction.CommandAdmissionAuthorityCoordinator,
    private val commandAdmissionAuthorityAdapter:
        me.rerere.rikkahub.data.authority.transaction.CommandStateAdmissionAuthorityAdapter,
    private val waitingApprovalAuthority:
        me.rerere.rikkahub.data.authority.transaction.WaitingApprovalAuthorityCoordinator,
    private val finalConversationAuthority:
        me.rerere.rikkahub.data.authority.transaction.FinalConversationAuthorityCoordinator,
    private val executionMessageAuthorityBinder:
        me.rerere.rikkahub.data.execution.ExecutionMessageAuthorityBinder,
) {
    @androidx.annotation.VisibleForTesting
    @Volatile
    internal var correctnessProbe: (suspend (ChatServiceProbePoint, CommandEnvelope<out ChatCommand>) -> Unit)? = null

    /** UI-only admission seam. Disabled mode performs no clock read or allocation. */
    fun beginAgentTimingSubmission(conversationId: Uuid): AgentTimingSubmissionToken? {
        val enabled = settingsStore.settingsFlow.value.displaySetting.showAgentTiming
        agentTimingStore.setEnabled(enabled)
        return agentTimingStore.beginSubmission(conversationId)
    }

    fun onConversationVisible(conversationId: Uuid) {
        val session = secretPlaintextSessions.state.value as?
            me.rerere.rikkahub.security.SecretPlaintextSessionState.Open ?: return
        if (session.binding.conversationId != conversationId.toString()) {
            secretPlaintextSessions.close(
                me.rerere.rikkahub.security.SecretPlaintextSessionCloseReason.CONVERSATION_CHANGED,
            )
        }
    }

    private val conversationLibraryReader =
        me.rerere.rikkahub.data.ai.tools.ConversationLibraryReader(conversationRepo)
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构�?
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)
    private val privilegedActionGuard = me.rerere.rikkahub.privilege.DefaultPrivilegedActionGuard(
        context.packageName
    )
    private val hardDenyPolicy = me.rerere.rikkahub.privilege.DefaultHardDenyPolicy(
        context.packageName,
        privilegedActionGuard,
    )
    private val privilegedManagementBackend by lazy {
        me.rerere.rikkahub.privilege.HostCapabilityRegistry(
            backend = me.rerere.rikkahub.privilege.RepositoryPrivilegedManagementBackend(
                settingsStore = settingsStore,
                conversationRepository = conversationRepo,
                skillManager = skillManager,
                workspaceRepository = workspaceRepository,
                workflowRepository = workflowRepository,
                conversationDeletionPolicy = conversationDeletionPolicy,
                secretVault = secondUserSecretVault,
                onConversationDeleted = ::dropSession,
            ),
        )
    }
    private val ownerOperationGateway by lazy {
        val ownerTtsHandler = me.rerere.rikkahub.owner.OwnerTtsOperationHandler(
            settingsStore = settingsStore,
            vault = secondUserSecretVault,
            library = persistentTtsLibrary,
        )
        val ownerServiceHandler = me.rerere.rikkahub.owner.OwnerLocalServiceOperationHandler(
            dao = hostLocalServiceDao,
            manager = workspaceProcessManager,
            starter = workspaceManagedProcessStarter,
            workspaces = workspaceRepository,
            httpClient = ownerHttpClient,
            specStore = ownerServiceSpecStore,
            termux = ownerTermuxServiceLauncher,
        )
        val executor = me.rerere.rikkahub.owner.OwnerOperationExecutor(
            dao = hostOperationDao,
            handler = me.rerere.rikkahub.owner.CompositeOwnerOperationHandler(
                me.rerere.rikkahub.security.SecretOwnerOperationHandler(
                    sessions = secretPlaintextSessions,
                    ephemeralResults = ephemeralToolResults,
                    settingsStore = settingsStore,
                    vault = secondUserSecretVault,
                ),
                me.rerere.rikkahub.owner.OwnerSettingsOperationHandler(
                    context = context,
                    settingsStore = settingsStore,
                    conversations = conversationRepo,
                    assistantRemoval = assistantRemovalService,
                    providerManager = providerManager,
                    vault = secondUserSecretVault,
                ),
                me.rerere.rikkahub.owner.OwnerPackageControlHandler(
                    context = context,
                    settingsStore = settingsStore,
                    files = filesManager,
                    pluginInstaller = pluginPackageInstaller,
                    plugins = pluginRegistryStore,
                ),
                me.rerere.rikkahub.owner.OwnerRunOperationHandler(
                    controller = object : me.rerere.rikkahub.owner.OwnerRunController {
                        override suspend fun snapshot(conversationId: Uuid): me.rerere.rikkahub.owner.OwnerRunSnapshot {
                            val exists = conversationRepo.existsConversationById(conversationId)
                            if (!exists) return me.rerere.rikkahub.owner.OwnerRunSnapshot(false, "Missing", null, emptySet())
                            val runtime = getRuntimeStateFlow(conversationId).value
                            val queue = getQueueStatusFlow(conversationId).value
                            return me.rerere.rikkahub.owner.OwnerRunSnapshot(
                                exists = true,
                                runtimeState = runtime::class.simpleName ?: "Unknown",
                                activeCommandId = queue.activeCommandId,
                                pendingCommandIds = queue.pendingCommandIds.toSet(),
                            )
                        }

                        override suspend fun cancel(conversationId: Uuid, commandId: Uuid?): me.rerere.rikkahub.owner.OwnerRunSubmission {
                            val queue = getQueueStatusFlow(conversationId).value
                            val result = when {
                                commandId != null && commandId in queue.pendingCommandIds -> cancelQueuedCommand(conversationId, commandId)
                                commandId != null && commandId != queue.activeCommandId -> return me.rerere.rikkahub.owner.OwnerRunSubmission(false, "RUN_COMMAND_NOT_FOUND")
                                else -> stopGeneration(conversationId)
                            }
                            return result.toOwnerRunSubmission()
                        }

                        override suspend fun retryLastAssistant(conversationId: Uuid): me.rerere.rikkahub.owner.OwnerRunSubmission =
                            submitOwnerRetryLastAssistant(conversationId).toOwnerRunSubmission()
                    },
                ),
                me.rerere.rikkahub.owner.OwnerBackupOperationHandler(
                    backups = localBackupFacade,
                    files = filesManager,
                ),
                me.rerere.rikkahub.owner.OwnerQuickCaptureOperationHandler(context),
                me.rerere.rikkahub.owner.OwnerAndroidControlHandler(context, agentSafetySettings),
                me.rerere.rikkahub.owner.OwnerChannelOperationHandler(
                    context = context,
                    settingsStore = settingsStore,
                    preferences = telegramBotPreferences,
                    credentials = telegramCredentialResolver,
                    vault = secondUserSecretVault,
                ),
                me.rerere.rikkahub.owner.OwnerApplicationControlHandler(
                    settingsStore = settingsStore,
                    plugins = pluginRegistryStore,
                    safety = agentSafetySettings,
                    operations = hostOperationDao,
                    memories = memoryRepository,
                    vault = secondUserSecretVault,
                    petDialogues = petDialogueRepository,
                    reverseGeocodeTester = reverseGeocodeProviderTestGateway,
                    dreamReviews = dreamReviewRepository,
                ),
                ownerTtsHandler,
                me.rerere.rikkahub.owner.OwnerEmotionTtsOperationHandler(
                    settingsStore = settingsStore,
                    serviceHandler = ownerServiceHandler,
                    ttsHandler = ownerTtsHandler,
                ),
                ownerServiceHandler,
                me.rerere.rikkahub.owner.OwnerMcpOperationHandler(
                    settingsStore = settingsStore,
                    manager = mcpManager,
                    httpClient = ownerHttpClient,
                    vault = secondUserSecretVault,
                ),
                me.rerere.rikkahub.owner.OwnerSkillOperationHandler(
                    settingsStore = settingsStore,
                    skillManager = skillManager,
                    httpClient = ownerHttpClient,
                ),
                me.rerere.rikkahub.owner.OwnerWorkflowOperationHandler(
                    repository = workflowRepository,
                    actionRunner = workflowActionRunner,
                    automation = automationControlFacade,
                    conversations = conversationRepo,
                    settings = settingsStore,
                ),
                me.rerere.rikkahub.owner.OwnerUiOperationHandler(),
                me.rerere.rikkahub.owner.OwnerDoctorOperationHandler(
                    checks = doctorChecks,
                    executionDoctor = executionConsistencyDoctor,
                    operationDao = hostOperationDao,
                    serviceDao = hostLocalServiceDao,
                    serviceSupervisor = ownerLocalServiceSupervisor,
                    plaintextSessions = secretPlaintextSessions,
                ),
                me.rerere.rikkahub.owner.ExistingHostOwnerOperationHandler(
                    privilegedManagementBackend,
                ),
            ),
            isEmergencyStopActive = agentSafetySettings::isEmergencyStop,
            containsRuntimeSecret = runtimeSecretRedactor::containsKnownSecret,
            fingerprinter = ownerOperationFingerprinter,
        )
        me.rerere.rikkahub.owner.AgentRunOwnerOperationGateway(
            delegate = executor,
            operations = hostOperationDao,
            runs = agentRunRepository,
        )
    }

    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val runtimes = ConcurrentHashMap<Uuid, ConversationRuntime>()
    private val sessionLifecycleLock = Any()
    private val commandSequences = ConcurrentHashMap<Uuid, AtomicLong>()
    /**
     * The tool origin of the current conversation run. Approval continuation commands are
     * intentionally INTERNAL, so retain the originating surface while the run is alive;
     * otherwise a remote approval could be misclassified as LocalChat.
     */
    private val activeToolOrigins = ConcurrentHashMap<Uuid, ToolCallOrigin>()
    private val _sessionsVersion = MutableStateFlow(0L)

    /**
     * Per-conversation mutex serialising state-mutating operations: handleToolApproval,
     * stopGeneration, the chunk-handling save path, and explicit DB writes. Without this
     * the audit reports identified multiple write races where a fresh approval mutation
     * gets clobbered by a concurrent write from a stale snapshot. Generation chunks
     * themselves are NOT held under this mutex �?only the persist boundaries.
     */

    /**
     * Hydrate the in-memory session for [conversationId] from disk if no authoritative
     * state has been installed yet. Used by entry points that may be hit
     * after a process restart with an empty session map �?without this they read an
     * empty Conversation, mutate it, and `saveConversation` then OVERWRITES the persisted
     * state with empty content (silent data loss). Idempotent and cheap after hydration.
     */
    suspend fun ensureHydrated(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        if (session.isHydrated) return
        val fromDb = conversationRepo.getConversationById(conversationId) ?: return
        session.hydrateIfNeeded(fromDb)
    }

    // 错误状�?
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    /**
     * Voice-call approval resume flag (ported from jude). [ResumeAfterApprovalCommand] is a
     * fieldless object, so the "resume as VoiceCall" decision made in executeToolApprovalInline
     * rides this per-conversation flag and is consumed exactly once by the resume execution.
     */
    private val pendingVoiceCallResumeByConversation = ConcurrentHashMap<Uuid, Boolean>()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update { it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution) }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成�?
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    // 前台状态管�?
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> _isForeground.value = true
            Lifecycle.Event.ON_STOP -> _isForeground.value = false
            else -> {}
        }
    }

    /** Recovers durable leases only; source tombstones are committed after successful generation. */
    private val durableRegenerationSourceReadiness: Deferred<Unit> = appScope.async(Dispatchers.IO) {
        try {
            // Include RUNNING rows even when the dead process's old lease has not expired yet.
            durableCommandQueue.recoverExpiredFenced()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Durable regeneration lease recovery failed", error)
            throw IllegalStateException("durable_regeneration_recovery_unavailable", error)
        }
    }

    // ---- 后台生成保活（移植自 extv，batch 11a）----
    // 生成类命令执行期间以前台服务 + 部分唤醒锁保活，用户退出 App 后生成不中断。
    // 引用计数：首个 acquire 拉起服务，最后一个 release 才停掉（多会话/排队消息并发安全）。
    private val foregroundWorkTracker = ForegroundWorkTracker(
        onFirstAcquire = { ChatGenerationForegroundService.start(context) },
        onLastRelease = { ChatGenerationForegroundService.stop(context) },
    )

    /**
     * 生成开始点位（batch 11a）：为模型面命令占用前台工作，并等待服务就绪（startForeground
     * 与唤醒锁均已生效）后才放行执行，避免流式连接先于前台提升建立。返回的合租约由
     * ConversationRuntime 在 run 结束（含取消/异常）时统一关闭——即停止点位，与 learning
     * 租约同生共死。
     */
    private suspend fun acquireForegroundGenerationLease(): AutoCloseable {
        val releaseForegroundWork = foregroundWorkTracker.acquire()
        ChatGenerationForegroundService.start(context)
        if (!ChatGenerationForegroundService.awaitReady()) {
            // 设备策略拒绝前台提升时保持可用的降级路径，仅记日志。
            Log.w(TAG, "Chat foreground service was not ready before generation started")
        }
        return AutoCloseable {
            releaseForegroundWork()
        }
    }

    init {
        // 添加生命周期观察�?
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }

    fun cleanup() = runCatching {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        val (runtimesToClose, sessionsToClose) = synchronized(sessionLifecycleLock) {
            val runtimeSnapshot = runtimes.values.toList()
            val sessionSnapshot = sessions.values.toList()
            runtimes.clear()
            sessions.clear()
            activeToolOrigins.clear()
            runtimeSnapshot to sessionSnapshot
        }
        runtimesToClose.forEach { it.close() }
        sessionsToClose.forEach { it.cleanup() }
    }.onFailure {
        // Don't let a teardown hiccup escape, but don't swallow it silently either �?
        // a failure here can leave the lifecycle observer registered (slow leak).
        Log.w(TAG, "cleanup failed", it)
    }

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession =
        synchronized(sessionLifecycleLock) {
            sessions.computeIfAbsent(conversationId) { id ->
                val settings = settingsStore.settingsFlow.value
                lateinit var createdSession: ConversationSession
                createdSession = ConversationSession(
                    id = id,
                    initial = Conversation.ofId(
                        id = id,
                        assistantId = settings.getCurrentAssistant().id,
                    ),
                    scope = appScope,
                    onIdle = { removeSession(it, createdSession) },
                    canEvict = { runtimes[id]?.hasRetainedWork != true },
                    // 语音/消息队列持久化（jude 移植，batch 9）：文件式快照，不建 Room 表；
                    // 进程被杀后未派发的排队输入不丢。
                    queueStorageFile = java.io.File(context.filesDir, "message_queue/$id.json"),
                )
                _sessionsVersion.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
                createdSession
            }
        }

    private fun removeSession(
        conversationId: Uuid,
        expectedSession: ConversationSession,
    ) {
        val removed = synchronized(sessionLifecycleLock) {
            val session = sessions[conversationId] ?: return
            if (session !== expectedSession) {
                Log.d(TAG, "removeSession: ignored stale idle callback for $conversationId")
                return
            }
            val runtime = runtimes[conversationId]
            if (session.isInUse || runtime?.hasRetainedWork == true) {
                Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
                return
            }
            if (!sessions.remove(conversationId, session)) return
            val removedRuntime = runtime?.takeIf { runtimes.remove(conversationId, it) }
            activeToolOrigins.remove(conversationId)
            Triple(session, removedRuntime, sessions.size)
        }
        removed.second?.close()
        removed.first.cleanup()
        _sessionsVersion.value++
        Log.i(TAG, "removeSession: $conversationId (remaining: ${removed.third})")
    }

    private fun resolveToolOrigin(conversationId: Uuid, origin: CommandOrigin): ToolCallOrigin {
        val resolved = when (origin) {
            CommandOrigin.APP_UI -> ToolCallOrigin.LocalChat
            CommandOrigin.EXTERNAL_AUTOMATION -> ToolCallOrigin.ExternalIntent
            CommandOrigin.TELEGRAM -> ToolCallOrigin.Telegram
            CommandOrigin.WEB_API -> ToolCallOrigin.WebServer
            CommandOrigin.CRON -> ToolCallOrigin.TrustedWorkflow
            CommandOrigin.SYSTEM_ASSISTANT -> ToolCallOrigin.SystemAssistant
            CommandOrigin.SYSTEM_ASSISTANT_KEYGUARD -> ToolCallOrigin.SystemAssistantKeyguard
            CommandOrigin.QUICK_CAPTURE -> ToolCallOrigin.QuickCapture
            CommandOrigin.PET_INTERACTION -> ToolCallOrigin.PetInteraction
            CommandOrigin.PET_HANDOFF_CONFIRMED -> ToolCallOrigin.PetHandoffConfirmed
            CommandOrigin.PET_HANDOFF_AUTO -> ToolCallOrigin.PetHandoffAuto
            // Approval continuation is an internal command, but it must retain the
            // surface that created the pending tool call. If no in-memory provenance is
            // available (for example after process death), fail closed as a workflow.
            CommandOrigin.INTERNAL -> activeToolOrigins[conversationId]
                ?: ToolCallOrigin.TrustedWorkflow
        }
        activeToolOrigins[conversationId] = resolved
        return resolved
    }

    /**
     * Origins and principals are separate: a remote request can carry the same assistant id as
     * a local conversation, but it never becomes that assistant's local second-user profile.
     */
    private fun capabilitySubjectFor(
        assistant: Assistant,
        conversationId: Uuid,
        origin: ToolCallOrigin,
        privilege: me.rerere.rikkahub.privilege.PrivilegedSessionContext? = null,
    ): me.rerere.rikkahub.data.capability.CapabilitySubject {
        if (privilege?.isPrivileged == true && privilege.expandLocalTools) {
            return me.rerere.rikkahub.data.capability.CapabilitySubject(
                id = requireNotNull(privilege.authoritySubjectId) {
                    "second_user_authority_snapshot_missing"
                },
                type = me.rerere.rikkahub.data.capability.SubjectType.LOCAL_SECOND_USER,
                privilegedConversationId = privilege.conversationId.toString(),
            )
        }
        val type = when (origin) {
            ToolCallOrigin.Telegram -> me.rerere.rikkahub.data.capability.SubjectType.TELEGRAM
            ToolCallOrigin.WebServer -> me.rerere.rikkahub.data.capability.SubjectType.WEB
            ToolCallOrigin.MCP -> me.rerere.rikkahub.data.capability.SubjectType.MCP
            ToolCallOrigin.ExternalIntent ->
                me.rerere.rikkahub.data.capability.SubjectType.EXTERNAL_AUTOMATION
            // Workflow snapshots are introduced independently; do not claim a grant exists
            // until the authoring path freezes it. Existing local workflows retain their
            // current gate while this migration is rolled out.
            ToolCallOrigin.TrustedWorkflow,
            ToolCallOrigin.LocalChat,
            ToolCallOrigin.SystemAssistant,
            ToolCallOrigin.SystemAssistantKeyguard,
            ToolCallOrigin.QuickCapture,
            ToolCallOrigin.PetInteraction,
            ToolCallOrigin.PetHandoffConfirmed,
            ToolCallOrigin.PetHandoffAuto,
            -> me.rerere.rikkahub.data.capability.SubjectType.LOCAL_ASSISTANT
        }
        val id = if (type == me.rerere.rikkahub.data.capability.SubjectType.LOCAL_ASSISTANT) {
            assistant.id.toString()
        } else {
            "${type.name.lowercase()}:${assistant.id}:$conversationId"
        }
        return me.rerere.rikkahub.data.capability.CapabilitySubject(id = id, type = type)
    }

    /**
     * The authority registry is intentionally fail-closed, but it used to be populated only by
     * an asynchronous app-start collector. A user who sent the first message immediately after a
     * cold start could therefore be demoted to an ordinary session and receive approval cards.
     * Admission re-reads the authoritative DataStore state and has no effect unless this exact
     * assistant/conversation is active, unlocked, and entered through a trusted local surface.
     */
    private suspend fun refreshSecondUserAuthorityForInvocation(
        assistant: Assistant,
        conversation: Conversation,
        origin: ToolCallOrigin,
    ) {
        if (origin !in me.rerere.rikkahub.data.ai.InvocationSurfacePolicy.CONFIRMED_LOCAL_SECOND_USER) return
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        val unlocked = keyguard?.let { !it.isDeviceLocked && !it.isKeyguardLocked } ?: true
        secondUserAuthorityService.admit(
            assistantId = assistant.id,
            conversationId = conversation.id,
            origin = origin,
            deviceUnlocked = unlocked,
        )
    }

    private fun getOrCreateRuntime(conversationId: Uuid): ConversationRuntime =
        synchronized(sessionLifecycleLock) {
            val session = getOrCreateSession(conversationId)
            runtimes.computeIfAbsent(conversationId) { id ->
                lateinit var createdRuntime: ConversationRuntime
                createdRuntime = ConversationRuntime(
                    appScope = appScope,
                    conversationId = id,
                    dispatchers = DispatcherProvider(),
                    executor = RuntimeCommandExecutor { envelope, control ->
                        executeRuntimeCommand(envelope, control)
                    },
                    hydrator = RuntimeHydrator {
                        ensureHydrated(id)
                    },
                    repairer = object : me.rerere.rikkahub.service.chat.RuntimeRepairer {
                        override suspend fun repair(
                            runId: Uuid,
                            reason: ToolCancelReason,
                        ): me.rerere.rikkahub.service.chat.InterruptCleanupResult {
                            finishInterruptedPendingTools(id, emptyMap())
                            return me.rerere.rikkahub.service.chat.InterruptCleanupResult.Completed
                        }

                        override suspend fun repair(
                            runId: Uuid,
                            reason: ToolCancelReason,
                            toolCancellationResults: Map<String, CancelRequestResult>,
                        ): me.rerere.rikkahub.service.chat.InterruptCleanupResult {
                            finishInterruptedPendingTools(id, toolCancellationResults)
                            return me.rerere.rikkahub.service.chat.InterruptCleanupResult.Completed
                        }
                    },
                    durableQueue = durableCommandQueue,
                    commandAuthority = runtimeCommandAuthority,
                    onBecameIdle = { runtimeId ->
                        synchronized(sessionLifecycleLock) {
                            if (runtimes[runtimeId] === createdRuntime) {
                                sessions[runtimeId]?.requestIdleCheck()
                            }
                        }
                        // 语音消息队列（jude 移植，batch 9）：runtime 彻底空档（无活动 run、
                        // 无待审批、无排队命令，onRunJobChanged(null) 已清空 generationJob）后，
                        // 派发排队中的待发送输入。
                        dispatchNextQueuedMessage(runtimeId)
                    },
                    onRunJobChanged = { job -> session.attachRunJob(job) },
                    onRunStarted = { envelope ->
                        // 生成开始点位（batch 11a）：模型面命令在运行期间占用前台服务保活。
                        // learning 前台注册表摘除后，此处不再返回附加租约。
                        if (envelope.command.keepsForegroundWhileRunning()) {
                            acquireForegroundGenerationLease()
                        } else {
                            null
                        }
                    },
                    onPetRunStarted = { },
                    onPersistSteering = { note ->
                        val current = session.state.value
                        val updated = current.withSteeringAuditMessage(note)
                        if (updated !== current) {
                            saveConversation(id, updated)
                        }
                    },
                    onCancellationTimeout = { envelope, error ->
                        addError(
                            error = error,
                            conversationId = id,
                            title = context.getString(
                                if (envelope.command is me.rerere.rikkahub.service.chat.InterruptRegenerateCommand) {
                                    R.string.error_title_regenerate_message
                                } else {
                                    R.string.error_title_operation
                                }
                            ),
                        )
                    },
                )
                createdRuntime
            }
        }

    suspend fun submitCommand(
        conversationId: Uuid,
        command: ChatCommand,
        origin: CommandOrigin,
        agentTimingSubmission: AgentTimingSubmissionToken? = null,
    ): SubmitResult {
        return submitCommand(
            conversationId = conversationId,
            command = command,
            origin = origin,
            dedupeKey = null,
            expiresAt = null,
            dependencies = emptyList(),
            agentTimingSubmission = agentTimingSubmission,
        )
    }

    private suspend fun submitCommand(
        conversationId: Uuid,
        command: ChatCommand,
        origin: CommandOrigin,
        dedupeKey: String?,
        expiresAt: kotlin.time.Instant?,
        dependencies: List<me.rerere.rikkahub.service.chat.CommandDependency>,
        agentTimingSubmission: AgentTimingSubmissionToken? = null,
        parentCommandId: Uuid? = null,
    ): SubmitResult = submitCommandTracked(
        conversationId = conversationId,
        command = command,
        origin = origin,
        dedupeKey = dedupeKey,
        expiresAt = expiresAt,
        dependencies = dependencies,
        agentTimingSubmission = agentTimingSubmission,
        parentCommandId = parentCommandId,
    ).submission

    private suspend fun submitCommandTracked(
        conversationId: Uuid,
        command: ChatCommand,
        origin: CommandOrigin,
        dedupeKey: String?,
        expiresAt: kotlin.time.Instant?,
        dependencies: List<me.rerere.rikkahub.service.chat.CommandDependency>,
        commandId: Uuid? = null,
        agentTimingSubmission: AgentTimingSubmissionToken? = null,
        parentCommandId: Uuid? = null,
    ): TrackedCommandSubmission {
        require(command !is me.rerere.rikkahub.service.chat.EmergencyCommand) {
            "EmergencyCommand must use submitEmergency()"
        }
        me.rerere.rikkahub.service.chat.emergencyStopCommandBlockReason(
            active = agentSafetySettings.emergencyStopFlow.first(),
            command = command,
        )?.let { reason ->
            agentTimingSubmission?.handle?.finish(AgentTimingTraceStatus.FAILED)
            return rejectedTrackedCommand(reason)
        }
        me.rerere.rikkahub.service.chat.SystemAssistantCommandSecurityPolicy
            .commandBlockReason(origin, command)
            ?.let { reason ->
                agentTimingSubmission?.handle?.finish(AgentTimingTraceStatus.FAILED)
                return rejectedTrackedCommand(reason)
        }
        val resolvedCommandId = commandId ?: Uuid.random()
        if (command is SendMessageCommand && !conversationRepo.existsConversationById(conversationId)) {
            val draft = sessions[conversationId]?.takeIf { it.isHydrated }?.state?.value
            if (draft?.newConversation == true) {
                conversationRepo.insertConversation(draft.copy(newConversation = false))
                updateConversation(conversationId, draft.copy(newConversation = false))
            }
        }
        val persistedAdmissionConversation = conversationRepo.getConversationById(conversationId)
            ?: return rejectedTrackedCommand("Conversation not found")
        if (origin == CommandOrigin.SYSTEM_ASSISTANT && command !is StopCommand) {
            val validation = me.rerere.rikkahub.service.chat.SystemAssistantCommandSecurityPolicy
                .validateAdmissionTarget(
                    command = command,
                    conversationId = conversationId,
                    settings = settingsStore.settingsFlow.first(),
                    persistedConversation = conversationRepo.getConversationById(conversationId),
                )
            if (validation is me.rerere.rikkahub.service.chat.SystemAssistantTargetValidation.Invalid) {
                agentTimingSubmission?.handle?.finish(AgentTimingTraceStatus.FAILED)
                return rejectedTrackedCommand(validation.reason)
            }
        }
        if (origin == CommandOrigin.QUICK_CAPTURE && command !is StopCommand) {
            val validation = me.rerere.rikkahub.service.chat.QuickCaptureCommandSecurityPolicy
                .validateAdmission(
                    commandId = resolvedCommandId,
                    command = command,
                    conversationId = conversationId,
                    settings = settingsStore.settingsFlow.first(),
                    persistedConversation = conversationRepo.getConversationById(conversationId),
                )
            if (validation is me.rerere.rikkahub.service.chat.QuickCaptureTargetValidation.Invalid) {
                agentTimingSubmission?.handle?.finish(AgentTimingTraceStatus.FAILED)
                return rejectedTrackedCommand(validation.reason)
            }
        }
        val resolvedParentId = parentCommandId ?: when (command) {
            is ToolApprovalCommand -> {
                val exactOwner = if (command.approvalId != null && command.executionId != null) {
                    secondUserApprovalLifecycle.findOwningCommandIdExact(
                        approvalId = command.approvalId,
                        executionId = command.executionId,
                        conversationId = conversationId.toString(),
                        toolCallId = command.toolCallId,
                    )
                } else if (command.decision is ToolDecision.Denied) {
                    secondUserApprovalLifecycle.findOwningCommandId(
                        conversationId = conversationId.toString(),
                        toolCallId = command.toolCallId,
                    )
                } else {
                    return rejectedTrackedCommand("Approval exact identity is required")
                }
                exactOwner?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                    ?: if (command.decision is ToolDecision.Denied) {
                        durableCommandQueue.findSingleWaitingForConversation(conversationId)
                            ?.id
                            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                    } else {
                        null
                    }
            }
            else -> null
        }
        val parentLineage = resolvedParentId?.let { parentId ->
            val row = durableCommandQueue.findAuthorityRow(parentId)
                ?: return rejectedTrackedCommand("Parent command not found")
            if (row.conversationId != conversationId.toString()) {
                return rejectedTrackedCommand("Parent command belongs to another conversation")
            }
            me.rerere.rikkahub.service.chat.CommandLineageContext.fromAuthorityRowOrNull(row)
                ?: return rejectedTrackedCommand("Parent command lineage is unavailable")
        }
        if ((command is ToolApprovalCommand || command is ResumeAfterApprovalCommand) && parentLineage == null) {
            return rejectedTrackedCommand("Approval command lineage could not be proven")
        }
        val admissionAssistantId = parentLineage?.assistantIdSnapshot
            ?: (command as? SendMessageCommand)?.assistantIdSnapshot
            ?: persistedAdmissionConversation.assistantId
        if (admissionAssistantId != persistedAdmissionConversation.assistantId) {
            return rejectedTrackedCommand("Command assistant scope changed before admission")
        }
        val preparedCommand = if (command is SendMessageCommand) {
            val assistant = settingsStore.settingsFlow.first()
                .getAssistantById(admissionAssistantId)
                ?: return rejectedTrackedCommand("Command assistant is unavailable")
            command.copy(
                content = command.content.copy(
                    parts = preprocessUserInputParts(command.content.parts, assistant),
                ),
                assistantIdSnapshot = admissionAssistantId,
            )
        } else {
            command
        }
        val branchAnchor = parentLineage?.branchAnchorMessageId
            ?: when (preparedCommand) {
                is SendMessageCommand -> Uuid.random()
                is RegenerateCommand -> {
                    val selected = persistedAdmissionConversation.currentMessages
                    val targetIndex = selected.indexOfFirst { it.id == preparedCommand.targetMessageId }
                    if (targetIndex < 0) {
                        return rejectedTrackedCommand("Regeneration target is unavailable")
                    }
                    selected.subList(0, targetIndex + 1)
                        .lastOrNull { it.role == MessageRole.USER }
                        ?.id
                        ?: return rejectedTrackedCommand("Regeneration user anchor is unavailable")
                }
                else -> persistedAdmissionConversation.currentMessages
                    .lastOrNull { it.role == MessageRole.USER }
                    ?.id
                    ?: return rejectedTrackedCommand("Command user anchor is unavailable")
            }
        val envelope = CommandEnvelope(
            id = resolvedCommandId,
            conversationId = conversationId,
            command = preparedCommand,
            origin = origin,
            sequence = commandSequences.getOrPut(conversationId) { AtomicLong() }.incrementAndGet(),
            dedupeKey = dedupeKey,
            expiresAt = expiresAt,
            dependencies = dependencies,
            lineage = me.rerere.rikkahub.service.chat.CommandLineageContext(
                assistantIdSnapshot = admissionAssistantId,
                lineageId = parentLineage?.lineageId ?: resolvedCommandId,
                parentCommandId = resolvedParentId,
                branchAnchorMessageId = branchAnchor,
                branchAnchorMessageRevision = parentLineage?.branchAnchorMessageRevision,
            ),
            agentTimingSubmission = agentTimingSubmission,
        )
        if (me.rerere.rikkahub.BuildConfig.DEBUG && envelope.command is SendMessageCommand) {
            correctnessProbe?.invoke(ChatServiceProbePoint.BEFORE_ENQUEUE, envelope)
        }
        val submission = getOrCreateRuntime(conversationId).enqueueEnvelope(envelope)
        if (me.rerere.rikkahub.BuildConfig.DEBUG && envelope.command is SendMessageCommand) {
            correctnessProbe?.invoke(ChatServiceProbePoint.AFTER_ENQUEUE, envelope)
        }
        if (submission !is SubmitResult.Accepted || submission.commandId != envelope.id) {
            agentTimingSubmission?.handle?.finish(AgentTimingTraceStatus.FAILED)
        }
        return TrackedCommandSubmission(
            submission = submission,
            outcome = envelope.result,
        )
    }

    suspend fun submitUserMessage(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        origin: CommandOrigin = CommandOrigin.APP_UI,
        dedupeKey: String? = null,
        expiresAt: kotlin.time.Instant? = null,
        annotations: List<UIMessageAnnotation> = emptyList(),
        agentTimingSubmission: AgentTimingSubmissionToken? = null,
        requestMode: ChatRequestMode = ChatRequestMode.Normal,
        includeVoiceCallConnectedEvent: Boolean = false,
    ): SubmitResult = submitUserMessageTracked(
        conversationId = conversationId,
        content = content,
        answer = answer,
        origin = origin,
        dedupeKey = dedupeKey,
        expiresAt = expiresAt,
        annotations = annotations,
        agentTimingSubmission = agentTimingSubmission,
        requestMode = requestMode,
        includeVoiceCallConnectedEvent = includeVoiceCallConnectedEvent,
    ).submission

    suspend fun <T> runPetInteraction(
        conversationId: Uuid,
        block: suspend () -> T,
    ): me.rerere.rikkahub.service.chat.PetInteractionSlotResult<T> =
        getOrCreateRuntime(conversationId).runPetInteraction(block)

    internal suspend fun submitUserMessageTracked(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        origin: CommandOrigin = CommandOrigin.APP_UI,
        dedupeKey: String? = null,
        expiresAt: kotlin.time.Instant? = null,
        annotations: List<UIMessageAnnotation> = emptyList(),
        assistantIdSnapshot: Uuid? = null,
        commandId: Uuid? = null,
        quickCaptureSessionId: Uuid? = null,
        agentTimingSubmission: AgentTimingSubmissionToken? = null,
        requestMode: ChatRequestMode = ChatRequestMode.Normal,
        includeVoiceCallConnectedEvent: Boolean = false,
    ): TrackedCommandSubmission {
        if (content.isEmptyInputMessage()) {
            agentTimingSubmission?.handle?.finish(AgentTimingTraceStatus.FAILED)
            return TrackedCommandSubmission(
                submission = SubmitResult.Rejected("Empty message"),
                outcome = CompletableDeferred(CommandOutcome.Rejected("Empty message")),
            )
        }
        return submitCommandTracked(
            conversationId = conversationId,
            command = SendMessageCommand(
                content = RawUserContent(
                    parts = content,
                    answer = answer,
                    annotations = annotations,
                    requestMode = requestMode,
                    includeVoiceCallConnectedEvent = includeVoiceCallConnectedEvent,
                ),
                assistantIdSnapshot = assistantIdSnapshot,
                quickCaptureSessionId = quickCaptureSessionId,
            ),
            origin = origin,
            dedupeKey = dedupeKey,
            expiresAt = expiresAt,
            dependencies = emptyList(),
            commandId = commandId,
            agentTimingSubmission = agentTimingSubmission,
        )
    }

    suspend fun submitSteer(
        conversationId: Uuid,
        text: String,
        scope: SteeringScope = SteeringScope.REMAINDER_OF_RUN,
        applyPolicy: me.rerere.rikkahub.service.chat.SteeringApplyPolicy =
            me.rerere.rikkahub.service.chat.SteeringApplyPolicy.AFTER_CHECKPOINT,
        origin: CommandOrigin = CommandOrigin.APP_UI,
        historyMode: me.rerere.rikkahub.service.chat.SteeringHistoryMode =
            me.rerere.rikkahub.service.chat.SteeringHistoryMode.TRANSIENT,
    ): SubmitResult {
        if (text.isBlank()) return SubmitResult.Rejected("Steering text cannot be blank")
        return submitCommand(
            conversationId = conversationId,
            command = SteerCommand(
                text = text,
                scope = scope,
                applyPolicy = applyPolicy,
                historyMode = historyMode,
            ),
            origin = origin,
        )
    }

    fun updateSteeringHistoryMode(
        conversationId: Uuid,
        commandId: Uuid,
        historyMode: me.rerere.rikkahub.service.chat.SteeringHistoryMode,
    ): Boolean = getOrCreateRuntime(conversationId)
        .updateSteeringHistoryMode(commandId, historyMode)

    suspend fun cancelCurrentTool(
        conversationId: Uuid,
        toolCallId: String,
        origin: CommandOrigin = CommandOrigin.APP_UI,
    ): SubmitResult {
        if (toolCallId.isBlank() || toolCallId.length > 256) {
            return SubmitResult.Rejected("Invalid tool call id")
        }
        return submitCommand(
            conversationId = conversationId,
            command = CancelCurrentToolCommand(toolCallId),
            origin = origin,
        )
    }

    fun submitInterrupt(
        conversationId: Uuid,
        replacement: List<UIMessagePart>,
        answer: Boolean = true,
        origin: CommandOrigin = CommandOrigin.APP_UI,
        agentTimingSubmission: AgentTimingSubmissionToken? = null,
    ): SubmitResult = submitEmergency(
        conversationId = conversationId,
        command = InterruptCommand(SendMessageCommand(RawUserContent(replacement, answer))),
        origin = origin,
        agentTimingSubmission = agentTimingSubmission,
    )

    suspend fun resumeQueue(conversationId: Uuid, origin: CommandOrigin = CommandOrigin.APP_UI): SubmitResult =
        submitCommand(conversationId, ResumeQueueCommand(), origin)

    suspend fun clearPendingQueue(conversationId: Uuid, reason: String = "Cleared by user"): SubmitResult =
        submitCommand(conversationId, ClearPendingQueueCommand(reason), CommandOrigin.APP_UI)

    suspend fun cancelQueuedCommand(conversationId: Uuid, commandId: Uuid): SubmitResult =
        submitCommand(conversationId, CancelQueuedCommand(commandId), CommandOrigin.APP_UI)

    suspend fun cancelSteering(conversationId: Uuid, commandId: Uuid): SubmitResult =
        submitCommand(conversationId, CancelSteeringCommand(commandId), CommandOrigin.APP_UI)

    suspend fun updateQueuedMessage(
        conversationId: Uuid,
        commandId: Uuid,
        content: RawUserContent,
    ): SubmitResult = submitCommand(
        conversationId,
        UpdateQueuedMessageCommand(commandId, content),
        CommandOrigin.APP_UI,
    )

    suspend fun promoteQueuedMessageToSteering(
        conversationId: Uuid,
        commandId: Uuid,
    ): SubmitResult = submitCommand(
        conversationId,
        PromoteQueuedMessageToSteeringCommand(commandId),
        CommandOrigin.APP_UI,
    )

    fun submitEmergency(
        conversationId: Uuid,
        command: me.rerere.rikkahub.service.chat.EmergencyCommand,
        origin: CommandOrigin,
        agentTimingSubmission: AgentTimingSubmissionToken? = null,
    ): SubmitResult {
        val envelope = CommandEnvelope(
            conversationId = conversationId,
            command = command,
            origin = origin,
            sequence = commandSequences.getOrPut(conversationId) { AtomicLong() }.incrementAndGet(),
            agentTimingSubmission = agentTimingSubmission,
        )
        val result = getOrCreateRuntime(conversationId).replaceEmergencyEnvelope(envelope)
        if (result !is SubmitResult.Accepted || result.commandId != envelope.id) {
            agentTimingSubmission?.handle?.finish(AgentTimingTraceStatus.FAILED)
        }
        return result
    }

    /**
     * Stops only the Runtime instances that already exist. This never creates a session while
     * emergency stop is active, and the captured Runtime identity prevents a stale callback
     * from affecting a later replacement instance.
     */
    suspend fun stopAllActiveRuntimesForEmergency(): ChatEmergencyStopResult {
        secondUserApprovalLifecycle.invalidateAllPending(
            reasonCode = "emergency_stop",
            orphaned = false,
            source = me.rerere.rikkahub.data.execution.ExecutionStateSource.POLICY,
        ).forEach { conversation ->
            if (sessions.containsKey(conversation.id)) {
                updateConversation(conversation.id, conversation)
            }
        }
        val targets = synchronized(sessionLifecycleLock) {
            runtimes.entries.map { (conversationId, runtime) ->
                val stopEnvelope = CommandEnvelope(
                    conversationId = conversationId,
                    command = StopCommand(pauseQueue = true),
                    origin = CommandOrigin.INTERNAL,
                    sequence = commandSequences
                        .getOrPut(conversationId) { AtomicLong() }
                        .incrementAndGet(),
                )
                val clearEnvelope = CommandEnvelope(
                    conversationId = conversationId,
                    command = ClearPendingQueueCommand("Emergency stop"),
                    origin = CommandOrigin.INTERNAL,
                    sequence = commandSequences
                        .getOrPut(conversationId) { AtomicLong() }
                        .incrementAndGet(),
                )
                ChatEmergencyRuntimeTarget(
                    conversationId = conversationId,
                    submitStop = {
                        ChatEmergencyCommandSubmission(
                            submission = runtime.replaceEmergencyEnvelope(stopEnvelope),
                            outcome = stopEnvelope.result,
                        )
                    },
                    clearQueue = {
                        ChatEmergencyCommandSubmission(
                            submission = runtime.enqueueEnvelope(clearEnvelope),
                            outcome = clearEnvelope.result,
                        )
                    },
                )
            }
        }
        return stopChatRuntimeSnapshot(targets)
    }

    /**
     * Force-drop the in-memory session for [conversationId] regardless of refcount /
     * generation status. Used by /new in TelegramBotService to make sure a straggler
     * coroutine writing back to the session can't resurrect the conversation after the
     * user reset it. Safe to call when no session exists �?no-op.
     */
    fun dropSession(conversationId: Uuid) {
        val removed = synchronized(sessionLifecycleLock) {
            val runtime = runtimes.remove(conversationId)
            val session = sessions.remove(conversationId)
            activeToolOrigins.remove(conversationId)
            Triple(session, runtime, sessions.size)
        }
        removed.second?.close()
        removed.first?.cleanup()
        if (removed.first != null || removed.second != null) {
            _sessionsVersion.value++
            Log.i(TAG, "dropSession: $conversationId (remaining: ${removed.third})")
        }
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    // ---- 对话状态访�?----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        val session = sessions[conversationId] ?: return MutableStateFlow(null)
        return session.processingStatus
    }

    fun getRuntimeStateFlow(conversationId: Uuid): StateFlow<me.rerere.rikkahub.service.chat.RuntimeState> =
        getOrCreateRuntime(conversationId).runtimeState


    fun getQueueStatusFlow(conversationId: Uuid): StateFlow<me.rerere.rikkahub.service.chat.QueueStatus> =
        getOrCreateRuntime(conversationId).queueStatus

    fun getQueuedMessagesFlow(conversationId: Uuid): StateFlow<List<QueuedMessageUiEntry>> =
        getOrCreateRuntime(conversationId).queuedMessages

    fun getSteeringStatusFlow(conversationId: Uuid): StateFlow<Map<Uuid, me.rerere.rikkahub.data.ai.SteeringState>> =
        getOrCreateRuntime(conversationId).steeringStatus

    fun getSteeringEntriesFlow(
        conversationId: Uuid,
    ): StateFlow<Map<Uuid, me.rerere.rikkahub.service.chat.SteeringUiEntry>> =
        getOrCreateRuntime(conversationId).steeringEntries

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { s ->
                    s.generationJob.map { job -> s.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    // ---- 初始化对�?----

    suspend fun initializeConversation(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        if (!session.isHydrated) {
            val conversation = conversationRepo.getConversationById(conversationId)
            if (conversation != null) {
                session.hydrateIfNeeded(conversation)
            } else {
                val currentSettings = settingsStore.settingsFlowRaw.first()
                val assistant = currentSettings.getCurrentAssistant()
                session.hydrateIfNeeded(
                    Conversation.ofId(
                        id = conversationId,
                        assistantId = assistant.id,
                        newConversation = true,
                    ).updateCurrentMessages(effectivePresetMessages(assistant.presetMessages))
                )
            }
        }
        settingsStore.updateAssistant(session.state.value.assistantId)
    }

    // ---- 发送消�?----

    fun sendMessage(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        requestMode: ChatRequestMode = ChatRequestMode.Normal,
        includeVoiceCallConnectedEvent: Boolean = false,
    ) {
        if (content.isEmptyInputMessage()) return
        // Ordinary sends are in-memory FIFO commands.  Explicit interrupt UI
        // actions use submitEmergency(InterruptCommand) instead.
        appScope.launch {
            submitUserMessage(
                conversationId = conversationId,
                content = content,
                answer = answer,
                origin = CommandOrigin.APP_UI,
                requestMode = requestMode,
                includeVoiceCallConnectedEvent = includeVoiceCallConnectedEvent,
            )
        }
    }

    // ---- 语音消息队列（移植自 extv，batch 9）----
    // 排队输入在派发前不进会话历史；语音模式借 QueuedMessage.reply 观察助手回复文本用于朗读。
    // 与目标仓的接线点：入队项经 submitUserMessageTracked 走 runtime 命令链（submit 家族），
    // 回复在命令终局（CommandOutcome）后从会话状态抽取。

    fun getMessageQueueFlow(conversationId: Uuid): StateFlow<MessageQueueState> =
        getOrCreateSession(conversationId).messageQueue.state

    fun removeQueuedMessage(conversationId: Uuid, messageId: Uuid) {
        sessions[conversationId]?.messageQueue?.remove(messageId)
        dispatchNextQueuedMessage(conversationId)
    }

    fun beginEditQueuedMessage(conversationId: Uuid, messageId: Uuid): QueuedMessage? =
        sessions[conversationId]?.messageQueue?.beginEdit(messageId)

    fun finishEditQueuedMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>? = null,
    ) {
        sessions[conversationId]?.messageQueue?.finishEdit(messageId, parts)
        dispatchNextQueuedMessage(conversationId)
    }

    fun resumeMessageQueue(conversationId: Uuid) {
        sessions[conversationId]?.messageQueue?.resume()
        dispatchNextQueuedMessage(conversationId)
    }

    /** 语音入队（移植自 extv）：入队即尝试派发；reply 在撤回（remove/pause）时以 null 结束。 */
    fun enqueueVoiceMessage(conversationId: Uuid, text: String): Deferred<String?> {
        val session = getOrCreateSession(conversationId)
        val reply = CompletableDeferred<String?>()
        synchronized(session) {
            check(text.isNotBlank()) { context.getString(R.string.chat_page_voice_empty) }
            check(
                !session.messageQueue.state.value.paused ||
                    session.messageQueue.state.value.messages.isEmpty()
            ) { context.getString(R.string.chat_page_voice_resume_queue) }
            check(session.state.value.currentMessages.none { message ->
                message.parts.any { it is UIMessagePart.Tool && it.isPending }
            }) { context.getString(R.string.chat_page_voice_tools_before_resume) }
            if (session.messageQueue.state.value.messages.isEmpty()) session.messageQueue.resume()
            session.messageQueue.enqueue(listOf(UIMessagePart.Text(text)), reply = reply)
        }
        dispatchNextQueuedMessage(conversationId)
        return reply
    }

    /**
     * 目标仓适配（batch 9）：extv 以 session.getJob() 判空档；此处改用 runtime 附着在会话上的
     * generationJob（onRunJobChanged 维护），同样拦住待审批工具。空档唤醒点见 getOrCreateRuntime
     * 的 onBecameIdle 回调。
     */
    private fun dispatchNextQueuedMessage(conversationId: Uuid): Job? {
        val session = sessions[conversationId] ?: return null
        synchronized(session) {
            if (session.generationJob.value?.isActive == true) return null
            if (session.state.value.currentMessages.any { message ->
                    message.parts.any { it is UIMessagePart.Tool && it.isPending }
                }
            ) return null
            val next = session.messageQueue.takeNext() ?: return null
            return sendQueuedMessage(session, next)
        }
    }

    private fun sendQueuedMessage(session: ConversationSession, queued: QueuedMessage): Job {
        val conversationId = session.id
        return appScope.launch {
            try {
                ensureHydrated(conversationId)
                val previousIds = session.state.value.currentMessages.map { it.id }.toSet()
                val tracked = submitUserMessageTracked(
                    conversationId = conversationId,
                    content = queued.parts,
                    answer = queued.answer,
                    origin = CommandOrigin.APP_UI,
                )
                val submission = tracked.submission
                if (submission !is SubmitResult.Accepted) {
                    throw IllegalStateException(
                        (submission as? SubmitResult.Rejected)?.reason
                            ?: (submission as? SubmitResult.RuntimeUnavailable)?.reason
                            ?: context.getString(R.string.chat_page_voice_generation_failed)
                    )
                }
                val outcome = tracked.outcome.await()
                if (outcome != CommandOutcome.Completed) {
                    throw IllegalStateException(
                        when (outcome) {
                            is CommandOutcome.Rejected -> outcome.reason
                            is CommandOutcome.Failed -> outcome.error.message.orEmpty()
                            else -> outcome.toString()
                        }.ifBlank { context.getString(R.string.chat_page_voice_generation_failed) }
                    )
                }
                queued.reply?.let { reply ->
                    runCatching {
                        session.state.value.currentMessages
                            .filter { it.id !in previousIds && it.role == MessageRole.ASSISTANT }
                            .joinToString("\n") { it.toText() }
                    }.onSuccess { reply.complete(it) }
                        .onFailure { reply.completeExceptionally(it) }
                }
            } catch (e: Exception) {
                queued.reply?.completeExceptionally(e)
                if (e is CancellationException) throw e
                session.messageQueue.pause()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
    }

    private suspend fun executeRuntimeCommand(
        envelope: CommandEnvelope<out ChatCommand>,
        control: GenerationRunControl,
    ): RunOutcome {
        if (me.rerere.rikkahub.BuildConfig.DEBUG && envelope.command is SendMessageCommand) {
            correctnessProbe?.invoke(ChatServiceProbePoint.BEFORE_EXECUTION, envelope)
        }
        val command = envelope.command
        val agentTiming = envelope.agentTimingSubmission?.handle
        agentTiming?.bindCommand(envelope.id)
        me.rerere.rikkahub.service.chat.emergencyStopCommandBlockReason(
            active = agentSafetySettings.emergencyStopFlow.first(),
            command = command,
        )?.let { reason -> return RunOutcome.Rejected(reason) }
        me.rerere.rikkahub.service.chat.SystemAssistantCommandSecurityPolicy
            .commandBlockReason(envelope.origin, command)
            ?.let { reason -> return RunOutcome.Rejected(reason) }

        memorySourceReadinessFailureOrNull(
            command = command,
            readiness = durableRegenerationSourceReadiness,
        )?.let { error ->
            Log.w(TAG, "Model-facing command blocked before durable command recovery", error)
            return RunOutcome.Rejected("durable_regeneration_recovery_unavailable")
        }

        val acceptedSystemAssistantTarget = if (
            envelope.origin == CommandOrigin.SYSTEM_ASSISTANT && command !is StopCommand
        ) {
            when (val validation =
                me.rerere.rikkahub.service.chat.SystemAssistantCommandSecurityPolicy
                    .validateAcceptedTarget(
                        command = command,
                        conversationId = envelope.conversationId,
                        settings = settingsStore.settingsFlow.first(),
                        persistedConversation = conversationRepo.getConversationById(
                            envelope.conversationId,
                        ),
                    )
            ) {
                is me.rerere.rikkahub.service.chat.SystemAssistantTargetValidation.Invalid ->
                    return RunOutcome.Rejected(validation.reason)
                is me.rerere.rikkahub.service.chat.SystemAssistantTargetValidation.Valid -> validation
            }
        } else {
            null
        }
        val acceptedQuickCaptureTarget = if (
            envelope.origin == CommandOrigin.QUICK_CAPTURE && command !is StopCommand
        ) {
            when (val validation = me.rerere.rikkahub.service.chat.QuickCaptureCommandSecurityPolicy
                .validateAccepted(
                    command = command,
                    conversationId = envelope.conversationId,
                    settings = settingsStore.settingsFlow.first(),
                    persistedConversation = conversationRepo.getConversationById(envelope.conversationId),
                )
            ) {
                is me.rerere.rikkahub.service.chat.QuickCaptureTargetValidation.Invalid ->
                    return RunOutcome.Rejected(validation.reason)
                is me.rerere.rikkahub.service.chat.QuickCaptureTargetValidation.Valid -> validation
            }
        } else {
            null
        }
        val acceptedAssistantSnapshot = acceptedSystemAssistantTarget?.assistant ?: acceptedQuickCaptureTarget?.assistant

        return when (command) {
            is PetDialogueCommand -> RunOutcome.Rejected("pet_dialogue_command_is_memory_only")
            is SendMessageCommand -> executeSendMessageLegacy(
                commandId = envelope.id,
                branchAnchorMessageId = envelope.lineage?.branchAnchorMessageId ?: Uuid.random(),
                origin = envelope.origin,
                conversationId = envelope.conversationId,
                content = command.content,
                control = control,
                acceptedAssistantSnapshot = acceptedAssistantSnapshot,
                agentTiming = agentTiming,
            )

            is InterruptCommand -> executeSendMessageLegacy(
                commandId = envelope.id,
                branchAnchorMessageId = envelope.lineage?.branchAnchorMessageId ?: Uuid.random(),
                origin = envelope.origin,
                conversationId = envelope.conversationId,
                content = command.replacement.content,
                control = control,
                acceptedAssistantSnapshot = acceptedAssistantSnapshot,
                agentTiming = agentTiming,
            )

            is InterruptRegenerateCommand -> executeRegenerateInline(
                envelope.conversationId,
                envelope.origin,
                command.regeneration,
                control,
                agentTiming,
                envelope.id,
            )

            is ToolApprovalCommand -> executeToolApprovalInline(
                conversationId = envelope.conversationId,
                command = command,
                control = control,
                origin = envelope.origin,
                envelopeId = envelope.id,
            )

            is RegenerateCommand -> executeRegenerateInline(
                envelope.conversationId,
                envelope.origin,
                command,
                control,
                agentTiming,
                envelope.id,
            )

            is ResumeAfterApprovalCommand -> {
                // Voice-call approval resume (ported from jude): an accepted request_voice_call
                // approval resumes the generation in VoiceCall mode with the connection event.
                val voiceCallResume = pendingVoiceCallResumeByConversation
                    .remove(envelope.conversationId) == true
                handleMessageComplete(
                    envelope.conversationId,
                    origin = envelope.origin,
                    runControl = control,
                    activeCommandId = envelope.id,
                    agentTiming = agentTiming,
                    requestMode = if (voiceCallResume) {
                        ChatRequestMode.VoiceCall
                    } else {
                        ChatRequestMode.Normal
                    },
                    voiceCallUserEventState = if (voiceCallResume) {
                        VoiceCallRuntimeState.ACTIVE
                    } else {
                        null
                    },
                )
                val pending = pendingToolIds(envelope.conversationId)
                if (pending.isNotEmpty()) {
                    RunOutcome.WaitingApproval(pending)
                } else {
                    RunOutcome.Completed()
                }
            }

            is me.rerere.rikkahub.service.chat.MutateMessageCommand -> {
                val current = conversationRepo.getConversationById(envelope.conversationId)
                    ?: return RunOutcome.Conflict("Conversation missing")
                val updated = me.rerere.rikkahub.data.repository.applyMessageMutation(current, command)
                    ?: return RunOutcome.Conflict("Message target missing")
                val authority = control.runtimeCommandAuthority()
                    ?: return RunOutcome.Rejected("Message mutation requires runtime authority")
                authority.finish(updated,
                    me.rerere.rikkahub.service.chat.DurableCommandState.COMPLETED,
                    me.rerere.rikkahub.service.chat.RuntimeAuthorityTerminalKind.CONTROL_ONLY,
                    resultAssistantMessageId = null)
                conversationRepo.refreshSearchProjection(updated)
                updateConversation(envelope.conversationId, updated)
                RunOutcome.Completed()
            }
            is NormalCommand -> RunOutcome.Rejected("Unsupported normal command: ${command::class.simpleName}")
            is StopCommand -> RunOutcome.Stopped(me.rerere.rikkahub.service.chat.InterruptCleanupResult.Completed)
            is me.rerere.rikkahub.service.chat.SteerCommand -> {
                finishControlAuthority(envelope, control)
                RunOutcome.Completed()
            }
            is me.rerere.rikkahub.service.chat.CancelCurrentToolCommand -> {
                val request = control.requestCancelTool(
                    command.toolCallId,
                    me.rerere.rikkahub.data.ai.tools.ToolCancelReason(
                        "User cancelled tool ${command.toolCallId}",
                    ),
                )
                if (request is me.rerere.rikkahub.data.ai.tools.CancelRequestResult.NotFound) {
                    RunOutcome.Rejected("Tool call not found")
                } else {
                    val termination = control.awaitToolTermination(
                        command.toolCallId,
                        2.seconds,
                    )
                    if (termination ==
                        me.rerere.rikkahub.data.ai.tools.ToolTerminationState.StoppedConfirmed
                    ) {
                        finishControlAuthority(envelope, control)
                        RunOutcome.Completed()
                    } else {
                        RunOutcome.Rejected("Tool termination state is unknown")
                    }
                }
            }
        }
    }

    private suspend fun executeSendMessageLegacy(
        commandId: Uuid,
        branchAnchorMessageId: Uuid,
        origin: CommandOrigin,
        conversationId: Uuid,
        content: RawUserContent,
        control: GenerationRunControl,
        acceptedAssistantSnapshot: Assistant?,
        agentTiming: AgentTimingHandle?,
    ): RunOutcome = withCommandHeadlessScope(conversationId, origin, control) {
        executeSendMessageScoped(
            commandId = commandId,
            branchAnchorMessageId = branchAnchorMessageId,
            origin = origin,
            conversationId = conversationId,
            content = content,
            control = control,
            acceptedAssistantSnapshot = acceptedAssistantSnapshot,
            agentTiming = agentTiming,
        )
    }

    private suspend fun executeSendMessageScoped(
        commandId: Uuid,
        branchAnchorMessageId: Uuid,
        origin: CommandOrigin,
        conversationId: Uuid,
        content: RawUserContent,
        control: GenerationRunControl,
        acceptedAssistantSnapshot: Assistant?,
        agentTiming: AgentTimingHandle?,
    ): RunOutcome {
        try {
            val session = getOrCreateSession(conversationId)
            val targetBeforeMutation = session.state.value
            if (acceptedAssistantSnapshot != null &&
                (targetBeforeMutation.id != conversationId ||
                    targetBeforeMutation.assistantId != acceptedAssistantSnapshot.id)
            ) {
                return RunOutcome.Rejected(
                    "The accepted assistant target no longer matches this conversation.",
                )
            }
            finishInterruptedPendingTools(conversationId)
            val conversationBeforeEndedEvent = session.state.value
            // Voice-call ended-event consumption (ported from jude): the first normal text send
            // after a call closes clears the pending ended markers on the VoiceCallRecord
            // annotations and flags the next generation to notify the model of the hangup.
            val endedEventConsumption = if (
                content.answer && content.requestMode == ChatRequestMode.Normal
            ) {
                conversationBeforeEndedEvent.consumePendingVoiceCallEndedEvent()
            } else {
                null
            }
            val currentConversation = endedEventConsumption?.conversation
                ?: conversationBeforeEndedEvent
            val settings = settingsStore.settingsFlow.first()
            val assistant = acceptedAssistantSnapshot
                ?: settings.getAssistantById(currentConversation.assistantId)
                ?: settings.getCurrentAssistant()
            // Submission freezes the exact processed payload before the authority admission.
            // Reprocessing here could change the durable branch anchor after a settings update.
            val processedContent = content.parts
            val fastPath = if (content.answer) {
                fastPathRouter.resolve(
                    FastPathContext(
                        commandId = commandId,
                        conversation = currentConversation,
                        content = processedContent,
                        origin = origin,
                        assistant = assistant,
                    )
                )
            } else FastPathDecision.NotMatched
            val fastPathPlan = buildFastPathCommitPlan(processedContent, fastPath)
            if (fastPathPlan is FastPathCommitPlan.Rejected) return RunOutcome.Rejected(fastPathPlan.reason)
            val userContent = when (fastPathPlan) {
                is FastPathCommitPlan.Handled -> fastPathPlan.userContent
                is FastPathCommitPlan.ContinueToModel -> fastPathPlan.userContent
                is FastPathCommitPlan.NotMatched -> fastPathPlan.userContent
                is FastPathCommitPlan.Rejected -> emptyList()
            }
            val anchoredUserMessage = content.toAnchoredUserMessage(
                messageId = branchAnchorMessageId,
                effectiveParts = userContent,
            )
            val existingAnchorIndex = currentConversation.messageNodes.indexOfFirst { node ->
                node.messages.any { it.id == branchAnchorMessageId }
            }
            if (existingAnchorIndex >= 0) {
                val existing = currentConversation.messageNodes[existingAnchorIndex].messages
                    .first { it.id == branchAnchorMessageId }
                if (existing.role != MessageRole.USER || existing.parts != userContent ||
                    existing.annotations != content.annotations
                ) {
                    return RunOutcome.Conflict("command_branch_anchor_identity_conflict")
                }
                val alreadyHasAssistantResult = currentConversation.messageNodes
                    .drop(existingAnchorIndex + 1)
                    .any { node -> node.messages.any { it.role == MessageRole.ASSISTANT } }
                if (alreadyHasAssistantResult) {
                    val resultAssistant = currentConversation.messageNodes
                        .drop(existingAnchorIndex + 1)
                        .asSequence()
                        .flatMap { node -> node.messages.asSequence() }
                        .last { it.role == MessageRole.ASSISTANT }
                    val authority = control.runtimeCommandAuthority()
                    val pending = pendingToolIds(conversationId)
                    if (authority != null) {
                        if (pending.isNotEmpty()) {
                            authority.checkpointWaiting(
                                conversation = currentConversation,
                                assistantMessageId = resultAssistant.id,
                                approvalMutation = { messageId, revision ->
                                    executionMessageAuthorityBinder
                                        .requireBoundInCurrentAuthorityTransaction(
                                            resultAssistant.persistedToolExecutionIds(control).map {
                                                executionId ->
                                                me.rerere.rikkahub.data.execution
                                                    .ExecutionOwningMessageAuthority(
                                                        executionId = executionId,
                                                        assistantMessageId = messageId,
                                                        assistantMessageRevision = revision,
                                                    )
                                            },
                                        )
                                },
                            )
                        } else {
                            authority.finish(
                                conversation = currentConversation,
                                terminalState = me.rerere.rikkahub.service.chat
                                    .DurableCommandState.COMPLETED,
                                kind = me.rerere.rikkahub.service.chat.RuntimeAuthorityTerminalKind
                                    .GENERATION_FINAL_SAVED,
                                resultAssistantMessageId = resultAssistant.id,
                                executionIds = resultAssistant.persistedToolExecutionIds(control),
                            )
                        }
                    }
                    agentTiming?.mark(AgentTimingEventKind.GENERATION_DONE_NOTIFY_STARTED)
                    try {
                        _generationDoneFlow.emit(conversationId)
                    } finally {
                        agentTiming?.mark(AgentTimingEventKind.GENERATION_DONE_NOTIFY_FINISHED)
                    }
                    return pending.takeIf { it.isNotEmpty() }
                        ?.let(RunOutcome::WaitingApproval)
                        ?: RunOutcome.Completed()
                }
            }
            val responseCorrelationAnnotation = content.annotations
                .filter { it.isResponseCorrelation() }
                .singleOrNull()
            val withUser = if (existingAnchorIndex >= 0) {
                currentConversation
            } else {
                currentConversation.copy(
                    messageNodes = currentConversation.messageNodes + anchoredUserMessage.toMessageNode(),
                )
            }
            HeartbeatUserActivity.record(
                context = context,
                message = anchoredUserMessage,
                assistantId = assistant.id.toString(),
            )
            when (fastPathPlan) {
                is FastPathCommitPlan.Handled -> {
                    val assistantMessage = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = fastPathPlan.assistantContent,
                        annotations = listOfNotNull(responseCorrelationAnnotation),
                    )
                    val finalConversation = withUser.copy(
                        messageNodes = withUser.messageNodes + assistantMessage.toMessageNode(),
                    )
                    val authority = control.runtimeCommandAuthority()
                    if (authority != null) {
                        try {
                            val fastExecutionId = me.rerere.rikkahub.data.execution.ExecutionRecordIds
                                .tool(commandId.toString(), "fast-$commandId")
                            val executionIds = if (
                                executionMessageAuthorityBinder.find(fastExecutionId) != null
                            ) listOf(fastExecutionId) else emptyList()
                            authority.finish(
                                conversation = finalConversation,
                                terminalState = me.rerere.rikkahub.service.chat.DurableCommandState.COMPLETED,
                                kind = me.rerere.rikkahub.service.chat.RuntimeAuthorityTerminalKind
                                    .FAST_PATH_HANDLED,
                                resultAssistantMessageId = assistantMessage.id,
                                executionIds = executionIds,
                            )
                        } catch (saveError: Throwable) {
                            if (!authority.isTerminalCommitted()) {
                                runCatching { authority.finishAfterFinalSaveFailure() }
                            }
                            throw saveError
                        }
                        updateConversation(conversationId, finalConversation)
                        conversationRepo.refreshSearchProjection(finalConversation)
                    } else {
                        saveConversation(conversationId, finalConversation)
                    }
                    me.rerere.rikkahub.skills.FastPathRouterLog.record(
                        me.rerere.rikkahub.skills.FastPathRouterLog.Entry(
                            whenMs = System.currentTimeMillis(),
                            intent = "handled",
                            toolName = "fast_path",
                            userText = processedContent.filterIsInstance<UIMessagePart.Text>()
                                .joinToString(" ") { it.text }.take(120),
                            resultPreview = fastPathPlan.assistantContent.joinToString { it.toString() }.take(200),
                            skippedLlm = true,
                        )
                    )
                }
                else -> {
                    // The exact USER anchor was already persisted with schema-v2 ADMITTED before
                    // this run could claim the command. Avoid a second non-combined graph write.
                    if (control.runtimeCommandAuthority() == null) {
                        saveConversation(conversationId, withUser)
                    } else {
                        updateConversation(conversationId, withUser)
                    }
                    if (content.answer) {
                        // 自动滚动摘要：用户消息已落库、生成开始前触发（jude 语义）。
                        // 全程 runCatching，失败只记日志，绝不阻塞聊天。
                        runCatching {
                            autoCompressConversationIfNeeded(
                                conversationId = conversationId,
                                conversation = getConversationFlow(conversationId).value,
                            )
                        }.onFailure {
                            Log.w(TAG, "autoCompressConversationIfNeeded crashed", it)
                        }
                        // 群聊分支（handover §三.B）：群会话的普通文本消息走 planner+多成员
                        // 管线，主持人不直接生成；语音通话等其他请求模式维持原路。用户消息
                        // 已在上文落库，群组轮次从当前状态起算。
                        val groupChatConfig = if (content.requestMode == ChatRequestMode.Normal) {
                            settings.groupChats.firstOrNull { it.conversationId == conversationId }
                        } else {
                            null
                        }
                        if (groupChatConfig != null) {
                            return executeGroupTurnScoped(
                                config = groupChatConfig,
                                conversationId = conversationId,
                                commandId = commandId,
                                control = control,
                                hostAssistant = assistant,
                                responseCorrelationAnnotation = responseCorrelationAnnotation,
                                agentTiming = agentTiming,
                            )
                        }
                        // Voice-call runtime state machine (ported from jude sendMessage).
                        val voiceCallRuntimeState =
                            if (endedEventConsumption?.shouldNotifyModel == true) {
                                VoiceCallRuntimeState.ENDED
                            } else {
                                content.requestMode.defaultVoiceCallRuntimeState()
                            }
                        val voiceCallUserEventState = when {
                            endedEventConsumption?.shouldNotifyModel == true ->
                                VoiceCallRuntimeState.ENDED
                            content.includeVoiceCallConnectedEvent &&
                                content.requestMode == ChatRequestMode.VoiceCall ->
                                VoiceCallRuntimeState.ACTIVE

                            else -> null
                        }
                        // Must surface generation failures as RunOutcome.Failed. Swallowing them
                        // (propagateFailure=false) marks the durable command COMPLETED after only
                        // the user message is saved — UI shows loading then silence with no reply.
                        handleMessageComplete(
                            conversationId,
                            origin = origin,
                            runControl = control,
                            activeCommandId = commandId,
                            acceptedAssistantSnapshot = acceptedAssistantSnapshot,
                            responseCorrelationAnnotation = responseCorrelationAnnotation,
                            propagateFailure = true,
                            agentTiming = agentTiming,
                            requestMode = content.requestMode,
                            voiceCallRuntimeState = voiceCallRuntimeState,
                            voiceCallUserEventState = voiceCallUserEventState,
                        )
                    } else {
                        finishControlAuthority(conversationId, control)
                    }
                }
            }
            getConversationFlow(conversationId).value.currentMessages
                .lastOrNull { it.role == MessageRole.ASSISTANT }
                ?.let { lastAssistantMessage ->
                    HeartbeatUserActivity.recordAssistantMessage(
                        context = context,
                        message = lastAssistantMessage,
                        assistantId = assistant.id.toString(),
                    )
                }
            agentTiming?.mark(AgentTimingEventKind.GENERATION_DONE_NOTIFY_STARTED)
            try {
                _generationDoneFlow.emit(conversationId)
            } finally {
                agentTiming?.mark(AgentTimingEventKind.GENERATION_DONE_NOTIFY_FINISHED)
            }
            return pendingToolIds(conversationId).takeIf { it.isNotEmpty() }
                ?.let { RunOutcome.WaitingApproval(it) }
                ?: (getConversationFlow(conversationId).value.latestFinalAnswerFailure()?.let {
                    RunOutcome.Failed(it)
                } ?: RunOutcome.Completed())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            return RunOutcome.Failed(e)
        }
    }

    /**
     * Group turn: planner picks member speakers, each member answers over a projected
     * history (own past replies stay assistant turns, everyone else becomes "[From X]"
     * user turns), replies persist as GroupMember-annotated assistant nodes. Runs inside
     * the runtime's run job — StopCommand cancels it via plain coroutine cancellation.
     */
    private suspend fun executeGroupTurnScoped(
        config: me.rerere.rikkahub.data.model.GroupChatConfig,
        conversationId: Uuid,
        commandId: Uuid,
        control: GenerationRunControl,
        hostAssistant: Assistant,
        responseCorrelationAnnotation: UIMessageAnnotation?,
        agentTiming: AgentTimingHandle?,
    ): RunOutcome {
        try {
            val settings = settingsStore.settingsFlow.first()
            val members = config.memberAssistantIds.mapNotNull { settings.getAssistantById(it) }
            if (members.isEmpty()) {
                finishControlAuthority(conversationId, control)
                return RunOutcome.Completed()
            }
            val conversation = getConversationFlow(conversationId).value
            val maxSpeakers = config.maxSpeakersPerTurn.coerceIn(1, 5)
            val plans = if (config.plannerEnabled) {
                groupChatEngine.planSpeakers(
                    config = config,
                    conversation = conversation,
                    members = members,
                    hostAssistant = hostAssistant,
                    maxSpeakers = maxSpeakers,
                )
            } else {
                emptyList()
            }
            val speakers: List<Pair<Assistant, String?>> = if (plans.isEmpty()) {
                // Planner failure/empty fallback: the first roster member answers so the
                // group never goes silent (planner errors must not break the chat).
                listOf(members.first() to null)
            } else {
                plans.mapNotNull { plan ->
                    members.find { it.id == plan.memberAssistantId }?.let { it to plan.hint }
                }
            }
            val memberNames = members.associate { it.id to it.name }
            var lastMemberMessage: UIMessage? = null
            for ((speaker, hint) in speakers) {
                val text = groupChatEngine.generateMemberReply(
                    conversation = getConversationFlow(conversationId).value,
                    member = speaker,
                    memberNames = memberNames,
                    hostAssistantName = hostAssistant.name,
                    hint = hint,
                )
                if (text.isBlank()) continue
                val memberMessage = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(text)),
                    annotations = buildList {
                        add(me.rerere.ai.ui.UIMessageAnnotation.GroupMember(
                            memberAssistantId = speaker.id,
                            displayName = speaker.name,
                        ))
                        if (responseCorrelationAnnotation != null) add(responseCorrelationAnnotation)
                    },
                )
                val current = getConversationFlow(conversationId).value
                val updated = current.copy(
                    messageNodes = current.messageNodes + memberMessage.toMessageNode(),
                )
                if (control.runtimeCommandAuthority() == null) {
                    saveConversation(conversationId, updated)
                } else {
                    updateConversation(conversationId, updated)
                }
                lastMemberMessage = memberMessage
            }
            val authority = control.runtimeCommandAuthority()
            if (lastMemberMessage != null && authority != null) {
                // A generation happened: terminalize like the normal path, anchored on the
                // last member message so a post-kill replay cannot double-run this turn.
                val graph = conversationRepo.getConversationById(conversationId)
                    ?: error("control_conversation_missing")
                try {
                    authority.finish(
                        conversation = graph,
                        terminalState = me.rerere.rikkahub.service.chat.DurableCommandState.COMPLETED,
                        kind = me.rerere.rikkahub.service.chat.RuntimeAuthorityTerminalKind
                            .GENERATION_FINAL_SAVED,
                        resultAssistantMessageId = lastMemberMessage.id,
                        executionIds = emptyList(),
                    )
                } catch (saveError: Throwable) {
                    if (!authority.isTerminalCommitted()) {
                        runCatching { authority.finishAfterFinalSaveFailure() }
                    }
                    throw saveError
                }
            } else {
                finishControlAuthority(conversationId, control)
            }
            agentTiming?.mark(AgentTimingEventKind.GENERATION_DONE_NOTIFY_STARTED)
            try {
                _generationDoneFlow.emit(conversationId)
            } finally {
                agentTiming?.mark(AgentTimingEventKind.GENERATION_DONE_NOTIFY_FINISHED)
            }
            return RunOutcome.Completed()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            return RunOutcome.Failed(e)
        }
    }

    private fun pendingToolIds(conversationId: Uuid): Set<String> =
        getConversationFlow(conversationId).value.selectedPendingToolIds()

    private fun UIMessage.toolExecutionIds(
        control: GenerationRunControl?,
    ): List<String> {
        val runId = control?.runId?.toString() ?: return emptyList()
        return parts.filterIsInstance<UIMessagePart.Tool>()
            .map { tool ->
                me.rerere.rikkahub.data.execution.ExecutionRecordIds.tool(runId, tool.toolCallId)
            }
            .distinct()
    }

    private suspend fun UIMessage.persistedToolExecutionIds(
        control: GenerationRunControl?,
    ): List<String> = toolExecutionIds(control).filter { executionId ->
        secondUserApprovalLifecycle.findExecution(executionId) != null
    }

    /**
     * Phase 16 �?fast-path router entry. Returns `true` if the router successfully handled
     * the turn (synthesised an assistant message and stored it) so the caller knows to skip
     * the normal LLM dispatch. Returns `false` to fall through.
     */

    private val fastPathRouter = FastPathRouter { context ->
        if (me.rerere.rikkahub.data.ai.tools.HeadlessConversations.isHeadless(context.conversation.id)) {
            return@FastPathRouter FastPathDecision.NotMatched
        }
        if (!context.assistant.fastPathRouterEnabled) return@FastPathRouter FastPathDecision.NotMatched
        val userText = context.content.filterIsInstance<UIMessagePart.Text>()
            .joinToString(" ") { it.text }.trim()
        if (userText.isBlank()) return@FastPathRouter FastPathDecision.NotMatched
        val match = me.rerere.rikkahub.skills.FastPathRouter.route(userText)
            ?: return@FastPathRouter FastPathDecision.NotMatched
        val tools = localTools.getTools(
            context.assistant.localTools,
            me.rerere.rikkahub.data.ai.tools.ToolInvocationContext(
                callerAssistantId = context.assistant.id.toString(),
                callerConversationId = context.conversation.id.toString(),
                callerRunId = context.commandId.toString(),
                callerWorkspaceId = context.assistant.workspaceId?.toString(),
                callOrigin = resolveToolOrigin(context.conversation.id, context.origin),
                isHeadless = false,
            ),
        )
        val tool = tools.firstOrNull { it.name == match.toolName }
            ?: return@FastPathRouter FastPathDecision.NotMatched
        val hardlineReason = me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard
            .checkTool(match.toolName, match.args.toString())
        if (hardlineReason != null) return@FastPathRouter FastPathDecision.NotMatched
        val rendered = try {
            val callOrigin = resolveToolOrigin(context.conversation.id, context.origin)
            refreshSecondUserAuthorityForInvocation(
                assistant = context.assistant,
                conversation = context.conversation,
                origin = callOrigin,
            )
            val privilege = me.rerere.rikkahub.privilege.DefaultPrivilegedSessionResolver.resolve(
                assistant = context.assistant,
                conversation = context.conversation,
                origin = callOrigin,
            )
            val capabilitySubject = capabilitySubjectFor(
                assistant = context.assistant,
                conversationId = context.conversation.id,
                origin = callOrigin,
                privilege = privilege,
            )
            val runtimeResult = toolRuntime.execute(
                me.rerere.rikkahub.data.ai.execution.ToolExecutionPlanRequest(
                    toolCallId = "fast-${context.commandId}",
                    toolName = tool.name,
                    toolSchemaFingerprint = me.rerere.rikkahub.toolcatalog.ToolCatalogSnapshot
                        .fromDefinitions(listOf(tool))
                        .entry(tool.name)
                        ?.schemaFingerprint,
                    args = match.args,
                    executionContext = me.rerere.rikkahub.data.ai.tools.ToolExecutionContext(
                        runId = context.commandId,
                        conversationId = context.conversation.id,
                        assistantId = context.assistant.id.toString(),
                        callOrigin = callOrigin,
                        commandId = context.commandId,
                        capabilitySubject = capabilitySubject,
                        selectedPrivilegedConversation = privilege.isPrivileged,
                    ),
                    startableTool = null,
                    legacyExecute = { input -> tool.execute(input.jsonObject) },
                    runControl = null,
                    wallClockBudgetMs = FAST_PATH_TOOL_BUDGET_MS,
                    preExecutionGate = {
                        when (val gate = toolExecutionGate.evaluate(
                            toolName = tool.name,
                            origin = callOrigin,
                            conversationId = context.conversation.id,
                            commandId = context.commandId,
                            arguments = match.args,
                            capabilitySubject = capabilitySubject,
                            selectedPrivilegedConversation = privilege.isPrivileged,
                            unrestrictedOverride = false,
                        )) {
                            me.rerere.rikkahub.data.ai.ToolExecutionGate.GateResult.Allowed ->
                                me.rerere.rikkahub.data.ai.execution.ToolPreExecutionDecision.Allow
                            is me.rerere.rikkahub.data.ai.ToolExecutionGate.GateResult.Denied ->
                                me.rerere.rikkahub.data.ai.execution.ToolPreExecutionDecision.Deny(
                                    errorCode = "tool_blocked",
                                    reason = gate.reason,
                                )
                        }
                    },
                )
            )
            val out = runtimeResult.output
            val rawText = out.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            val parsed = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(rawText).jsonObject
            }.getOrNull()
            val formatted = if (match.format != null && parsed != null) {
                runCatching { match.format.invoke(parsed) }.getOrNull()
            } else null
            formatted?.takeIf { it.isNotBlank() } ?: rawText
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            me.rerere.rikkahub.skills.FastPathRouterLog.record(
                me.rerere.rikkahub.skills.FastPathRouterLog.Entry(
                    whenMs = System.currentTimeMillis(),
                    intent = match.intent,
                    toolName = match.toolName,
                    userText = userText.take(120),
                    resultPreview = "tool threw: ${e.message?.take(80)}",
                    skippedLlm = false,
                )
            )
            return@FastPathRouter FastPathDecision.ContinueToModel(context.content)
        }
        FastPathDecision.Handled(listOf(UIMessagePart.Text(rendered)))
    }

    private fun preprocessUserInputParts(
        parts: List<UIMessagePart>,
        assistant: Assistant,
    ): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        )
                    )
                }

                else -> part
            }
        }
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true,
        agentTimingSubmission: AgentTimingSubmissionToken? = null,
    ) {
        val baseline = getConversationFlow(conversationId).value
        val baselineSources = baseline.selectedMemorySourceVersions()
        val policy = if (regenerateAssistantMsg) {
            me.rerere.rikkahub.service.chat.RegeneratePolicy.INTERRUPT_CURRENT
        } else {
            me.rerere.rikkahub.service.chat.RegeneratePolicy.REJECT_IF_BUSY
        }
        val command = RegenerateCommand(
            targetMessageId = message.id,
            expectedTargetVersion = 0L,
            expectedBranchHeadMessageId = message.id,
            policy = policy,
            baselineAssistantScopeId = baseline.assistantId.toString(),
            baselineSelectedMessageIds = baselineSources
                .map(MemorySourceVersion::messageId)
                .distinct()
                .sorted(),
            baselineSelectedSourceVersions = baselineSources.sortedWith(
                compareBy(MemorySourceVersion::messageId, MemorySourceVersion::consumedTextDigest),
            ),
        )
        appScope.launch {
            val tracked = submitCommandTracked(
                conversationId = conversationId,
                command = command,
                origin = CommandOrigin.APP_UI,
                dedupeKey = null,
                expiresAt = null,
                dependencies = emptyList(),
                agentTimingSubmission = agentTimingSubmission,
            )
            val submissionFailure = when (val submission = tracked.submission) {
                is SubmitResult.Accepted -> null
                is SubmitResult.QueueFull -> "Conversation queue is full (${submission.limit})"
                is SubmitResult.Rejected -> submission.reason
                is SubmitResult.RuntimeUnavailable -> submission.reason
            }
            if (submissionFailure != null) {
                addError(
                    IllegalStateException(submissionFailure),
                    conversationId,
                    title = context.getString(R.string.error_title_regenerate_message),
                )
                return@launch
            }

            val terminalFailure = when (val outcome = tracked.outcome.await()) {
                is CommandOutcome.Conflict -> outcome.reason
                is CommandOutcome.Rejected -> outcome.reason
                is CommandOutcome.NotApplied -> outcome.reason
                is CommandOutcome.Failed ->
                    outcome.error.message ?: outcome.error.toString()
                is CommandOutcome.SkippedDependencyFailed ->
                    "Required command failed: ${outcome.dependencyId}"
                else -> null
            }
            terminalFailure?.let { reason ->
                addError(
                    IllegalStateException(reason),
                    conversationId,
                    title = context.getString(R.string.error_title_regenerate_message),
                )
            }
        }
    }

    private suspend fun submitOwnerRetryLastAssistant(conversationId: Uuid): SubmitResult {
        val conversation = conversationRepo.getConversationById(conversationId)
            ?: return SubmitResult.Rejected("Conversation not found")
        val message = conversation.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
            ?: return SubmitResult.Rejected("No assistant message to retry")
        val baselineSources = conversation.selectedMemorySourceVersions()
        return submitCommand(
            conversationId = conversationId,
            command = RegenerateCommand(
                targetMessageId = message.id,
                expectedTargetVersion = 0L,
                expectedBranchHeadMessageId = message.id,
                policy = me.rerere.rikkahub.service.chat.RegeneratePolicy.REJECT_IF_BUSY,
                baselineAssistantScopeId = conversation.assistantId.toString(),
                baselineSelectedMessageIds = baselineSources
                    .map(MemorySourceVersion::messageId)
                    .distinct()
                    .sorted(),
                baselineSelectedSourceVersions = baselineSources.sortedWith(
                    compareBy(
                        MemorySourceVersion::messageId,
                        MemorySourceVersion::consumedTextDigest,
                    ),
                ),
            ),
            origin = CommandOrigin.APP_UI,
        )
    }

    private suspend fun executeRegenerateInline(
        conversationId: Uuid,
        origin: CommandOrigin,
        command: RegenerateCommand,
        control: GenerationRunControl,
        agentTiming: AgentTimingHandle?,
        commandId: Uuid,
    ): RunOutcome {
        ensureHydrated(conversationId)
        val session = getOrCreateSession(conversationId)
        val conversation = session.state.value
        val durableBaseline = command.durableRegenerationBaselineOrNull()
        val baselineAssistantScopeId = command.baselineAssistantScopeId
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: conversation.assistantId.toString()
        val currentSources = conversation.selectedMemorySourceVersions()
        val baselineSelectedMessageIds = durableBaseline?.selectedMessageIds
            ?: currentSources.map(MemorySourceVersion::messageId).distinct().sorted()
        val baselineSelectedSourceVersions = durableBaseline?.selectedSourceVersions
            ?: currentSources.sortedWith(
                compareBy(MemorySourceVersion::messageId, MemorySourceVersion::consumedTextDigest),
            )
        // Do not tombstone from this baseline yet. A rejected/failed replay restores its transient
        // graph and must keep the old memory source valid; only the final authority commit below
        // is allowed to invalidate deleted or superseded source versions.
        val message = conversation.messageNodes.asSequence()
            .flatMap { it.messages.asSequence() }
            .firstOrNull { it.id == command.targetMessageId }
            ?: return RunOutcome.Conflict("Target message no longer exists")
        // Look up by id: UIMessagePart.metadata is a var, so contains(message) equals can
        // fail after streaming updates even when the id is still present.
        val node = conversation.getMessageNodeByMessageId(command.targetMessageId)
            ?: return RunOutcome.Conflict("Target message is not in the conversation")
        val indexAt = conversation.messageNodes.indexOf(node)
        if (indexAt < 0) return RunOutcome.Conflict("Target message is not in the conversation")
        val transientWriteNowMs = System.currentTimeMillis()
        var deferredPostCommit: DeferredGenerationPostCommit? = null
        val outcome = runRegenerationTransaction(
            restore = {
                if (control.runtimeCommandAuthority()?.isTerminalCommitted() != true) {
                    val restored = mergeConversationState(conversationId) { current ->
                        current.copy(messageNodes = conversation.messageNodes)
                    }
                    saveConversation(
                        conversationId = conversationId,
                        conversation = restored,
                        sourceInvalidationMode =
                            ConversationSourceInvalidationMode.SKIP_TRANSIENT_WRITE,
                        sourceInvalidationNowMs = transientWriteNowMs,
                    )
                }
            },
        ) {
            try {
                if (message.role == MessageRole.USER) {
                    val transientConversation = conversation.copy(
                        messageNodes = conversation.messageNodes.subList(0, indexAt + 1),
                    )
                    if (control.runtimeCommandAuthority() == null) {
                        saveConversation(
                            conversationId,
                            transientConversation,
                            sourceInvalidationMode =
                                ConversationSourceInvalidationMode.SKIP_TRANSIENT_WRITE,
                            sourceInvalidationNowMs = transientWriteNowMs,
                        )
                    } else {
                        // Keep the authority graph unchanged until final/WAITING can commit graph,
                        // source and command together. Streaming state remains process-local.
                        updateConversation(conversationId, transientConversation)
                    }
                    handleMessageComplete(
                        conversationId,
                        origin = origin,
                        runControl = control,
                        activeCommandId = commandId,
                        propagateFailure = true,
                        persistenceSourceInvalidationMode =
                            ConversationSourceInvalidationMode.SKIP_TRANSIENT_WRITE,
                        persistenceSourceInvalidationNowMs = transientWriteNowMs,
                        deferPostCommitActions = true,
                        onDeferredPostCommit = { deferredPostCommit = it },
                        agentTiming = agentTiming,
                    )
                } else if (command.policy != me.rerere.rikkahub.service.chat.RegeneratePolicy.REJECT_IF_BUSY) {
                    handleMessageComplete(
                        conversationId,
                        origin = origin,
                        messageRange = 0..<indexAt,
                        runControl = control,
                        activeCommandId = commandId,
                        propagateFailure = true,
                        persistenceSourceInvalidationMode =
                            ConversationSourceInvalidationMode.SKIP_TRANSIENT_WRITE,
                        persistenceSourceInvalidationNowMs = transientWriteNowMs,
                        deferPostCommitActions = true,
                        onDeferredPostCommit = { deferredPostCommit = it },
                        agentTiming = agentTiming,
                    )
                }
                val finalConversation = getConversationFlow(conversationId).value
                val runtimeAuthority = control.runtimeCommandAuthority()
                val authorityAlreadyCommitted = runtimeAuthority?.let { authority ->
                    authority.isTerminalCommitted() || authority.isWaitingCommitted()
                } == true
                val finalization = if (authorityAlreadyCommitted) {
                    me.rerere.rikkahub.data.repository.ConversationUpdateResult.Updated(
                        finalConversation.id,
                    )
                } else if (runtimeAuthority != null) {
                    // No provider path ran (for example an unsupported regeneration policy).
                    // Still commit the final graph/source/command as one authority decision.
                    runtimeAuthority.finish(
                        conversation = finalConversation,
                        terminalState = me.rerere.rikkahub.service.chat.DurableCommandState.COMPLETED,
                        kind = me.rerere.rikkahub.service.chat.RuntimeAuthorityTerminalKind
                            .CONTROL_ONLY,
                        resultAssistantMessageId = null,
                        sourceInvalidationMode =
                            ConversationSourceInvalidationMode.SKIP_TRANSIENT_WRITE,
                    )
                    updateConversation(conversationId, finalConversation)
                    conversationRepo.refreshSearchProjection(finalConversation)
                    me.rerere.rikkahub.data.repository.ConversationUpdateResult.Updated(
                        finalConversation.id,
                    )
                } else conversationRepo.finalizeTransientConversationUpdate(
                    conversation = finalConversation,
                    baselineAssistantScopeId = baselineAssistantScopeId,
                    baselineSelectedMessageIds = baselineSelectedMessageIds,
                    baselineSelectedSourceVersions = baselineSelectedSourceVersions,
                    sourceInvalidationNowMs = System.currentTimeMillis(),
                )
                when (finalization) {
                    is me.rerere.rikkahub.data.repository.ConversationUpdateResult.Updated -> {
                        finalConversation.selectedPendingToolIds().takeIf { it.isNotEmpty() }
                            ?.let(RunOutcome::WaitingApproval)
                            ?: RunOutcome.Completed()
                    }
                    is me.rerere.rikkahub.data.repository.ConversationUpdateResult.Missing ->
                        RunOutcome.Conflict("Conversation disappeared during regeneration commit")
                    is me.rerere.rikkahub.data.repository.ConversationUpdateResult.RetainedSecondUser ->
                        RunOutcome.Rejected("Conversation assistant changed during regeneration")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
                RunOutcome.Failed(e)
            }
        }
        if (outcome !is RunOutcome.Completed && outcome !is RunOutcome.WaitingApproval) {
            val authority = control.runtimeCommandAuthority()
            if (authority != null && !authority.isTerminalCommitted()) {
                val restored = conversationRepo.getConversationById(conversationId) ?: conversation
                authority.finish(
                    conversation = restored,
                    terminalState = me.rerere.rikkahub.service.chat.DurableCommandState.FAILED,
                    kind = me.rerere.rikkahub.service.chat.RuntimeAuthorityTerminalKind.FAILED_OTHER,
                    resultAssistantMessageId = null,
                    errorCode = "REGENERATION_FAILED",
                )
            }
        }
        if (outcome is RunOutcome.Completed || outcome is RunOutcome.WaitingApproval) {
            deferredPostCommit?.let(::scheduleGenerationPostCommit)
            // This notification intentionally lives outside runRegenerationTransaction. A
            // cancelled rendezvous emit must never restore the old graph after the final source
            // invalidation and conversation graph committed atomically.
            _generationDoneFlow.emit(conversationId)
        }
        return outcome
    }

    // ---- 处理工具调用审批 ----

    /** Scope of an "approve" decision. Once = this single tool call only. ChatScope =
     *  every future call of the same tool name in this conversation (until /new). Always =
     *  every future call of this tool name across the whole app, persisted to disk. */
    enum class ApprovalScope { Once, ChatScope, Always }

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
        scope: ApprovalScope = ApprovalScope.Once,
        toolName: String? = null,
        origin: CommandOrigin = CommandOrigin.APP_UI,
    ) {
        val timingSubmission = getConversationFlow(conversationId).value.currentMessages
            .lastOrNull { message ->
                message.parts.any { part ->
                    part is UIMessagePart.Tool && part.toolCallId == toolCallId
                }
            }
            ?.id
            ?.let { messageId ->
                agentTimingStore.submissionTokenForMessage(conversationId, messageId)
            }
        timingSubmission?.handle?.approvalDecisionSubmitted(
            result = when {
                answer != null -> AgentTimingEventResult.ANSWERED
                approved -> AgentTimingEventResult.SUCCESS
                else -> AgentTimingEventResult.DENIED
            },
        )
        val decision = when {
            answer != null -> ToolDecision.Answered(answer)
            approved -> ToolDecision.Approved
            else -> ToolDecision.Denied(reason)
        }
        appScope.launch {
            val projection = secondUserApprovalLifecycle.findLatest(
                conversationId = conversationId.toString(),
                toolCallId = toolCallId,
            )
            submitCommand(
                conversationId,
                ToolApprovalCommand(
                    toolCallId = toolCallId,
                    decision = decision,
                    toolName = toolName,
                    scope = scope.name,
                    approvalId = projection?.approvalId,
                    executionId = projection?.executionId,
                    expectedStateVersion = projection?.stateVersion,
                    resolutionRequestId = Uuid.random().toString(),
                ),
                origin,
                timingSubmission,
            )
        }
    }

    /**
     * Voice-call close bookkeeping (ported from jude): strips call-only audio tags from the
     * transcript, appends the standalone VoiceCallRecord card, and writes the ended/failed
     * tool result back onto the originating request_voice_call tool call.
     */
    fun reportVoiceCallClosed(
        conversationId: Uuid,
        toolCallId: String? = null,
        failureMessage: String? = null,
        voiceCallCompletion: VoiceCallCompletion? = null,
    ) {
        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        previousJob?.cancel()

        val job = appScope.launch(Dispatchers.IO) {
            try {
                runCatching { previousJob?.join() }

                // Audio tags are call-only speech metadata. Preserve the primary text while
                // removing the selected branch's tags before it returns to normal chat.
                var conversation = session.state.value.let { current ->
                    current.updateCurrentMessages(
                        current.currentMessages.map(UIMessage::withoutVoiceCallAudioTagsForNormalContext)
                    )
                }
                val completedCallMessages = voiceCallCompletion?.let { completion ->
                    conversation.currentMessages.filter { message ->
                        message.id.toString() in completion.messageIds &&
                            (message.role == MessageRole.USER || message.role == MessageRole.ASSISTANT)
                    }
                }.orEmpty()
                val hasVoiceCallConversation = completedCallMessages.any { it.toText().isNotBlank() }
                voiceCallCompletion?.let { completion ->
                    val hasAiContent = completedCallMessages.any { message ->
                        message.role == MessageRole.ASSISTANT && message.toText().isNotBlank()
                    }
                    if (hasAiContent) {
                        val recordNode = UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = emptyList(),
                            annotations = listOf(
                                UIMessageAnnotation.VoiceCallRecord(
                                    callId = completion.callId,
                                    durationSeconds = completion.durationSeconds,
                                    cardAnchor = true,
                                    standalone = true,
                                    messageIds = completion.messageIds,
                                    audioSegmentsByMessageId = completion.audioSegmentsByMessageId,
                                    pendingEndedEvent = hasVoiceCallConversation,
                                )
                            ),
                        ).toMessageNode()
                        conversation = conversation.copy(
                            messageNodes = conversation.messageNodes + recordNode
                        )
                    }
                }
                if (toolCallId != null) {
                    val target = conversation.messageNodes.mapIndexedNotNull { nodeIndex, node ->
                        node.messages.mapIndexedNotNull { messageIndex, message ->
                            messageIndex.takeIf {
                                message.parts.any { part ->
                                    part is UIMessagePart.Tool &&
                                        part.toolCallId == toolCallId &&
                                        part.toolName == REQUEST_VOICE_CALL_TOOL_NAME
                                }
                            }?.let { messageIndex -> nodeIndex to messageIndex }
                        }.firstOrNull()
                    }.firstOrNull()

                    if (target != null) {
                        val (targetNodeIndex, targetMessageIndex) = target
                        val result = buildJsonObject {
                            put("success", failureMessage == null)
                            put("status", if (failureMessage == null) "ended" else "failed")
                            put("message", VOICE_CALL_ENDED_TOOL_STATUS)
                            if (failureMessage != null) {
                                put("error", failureMessage)
                            }
                        }.toString()
                        val updatedNodes = conversation.messageNodes.mapIndexed { nodeIndex, node ->
                            if (nodeIndex != targetNodeIndex) {
                                node
                            } else {
                                node.copy(
                                    messages = node.messages.mapIndexed { messageIndex, message ->
                                        if (messageIndex != targetMessageIndex) {
                                            message
                                        } else {
                                            message.copy(
                                                parts = message.parts.map { part ->
                                                    if (part is UIMessagePart.Tool && part.toolCallId == toolCallId) {
                                                        part.copy(output = listOf(UIMessagePart.Text(result)))
                                                    } else {
                                                        part
                                                    }
                                                }
                                            )
                                        }
                                    },
                                    selectIndex = targetMessageIndex,
                                )
                            }
                        }
                        conversation = conversation.copy(messageNodes = updatedNodes)
                    }
                }
                saveConversation(conversationId, conversation)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_generation))
            }
        }
        // repo-svc ConversationSession has no setJob; attachRunJob is the equivalent seam.
        session.attachRunJob(job)
    }

    private suspend fun executeToolApprovalInline(
        conversationId: Uuid,
        command: ToolApprovalCommand,
        control: GenerationRunControl,
        origin: CommandOrigin,
        envelopeId: Uuid,
    ): RunOutcome {
        val timing = agentTimingStore.handleForRun(control.runId)
            ?: agentTimingStore.openHandleForConversation(conversationId)
        val decision = command.decision
        val approved = decision is ToolDecision.Approved
        val answer = (decision as? ToolDecision.Answered)?.answer
        val reason = (decision as? ToolDecision.Denied)?.reason.orEmpty()
        ensureHydrated(conversationId)
        val session = getOrCreateSession(conversationId)
        val conversation = session.state.value
        // Voice-call connect flow (ported from jude handleToolApproval): approving the
        // request_voice_call tool connects the call — the tool result becomes the
        // connected status and the resume generation runs in VoiceCall mode.
        val isVoiceCallTool = conversation.messageNodes.any { node ->
            node.messages.any { message ->
                message.parts.any { part ->
                    part is UIMessagePart.Tool &&
                        part.toolCallId == command.toolCallId &&
                        part.toolName == REQUEST_VOICE_CALL_TOOL_NAME
                }
            }
        }
        val acceptedVoiceCall = approved && answer == null && isVoiceCallTool
        val connectedVoiceCallOutput = if (acceptedVoiceCall) {
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("success", true)
                        put("status", "connected")
                        put("message", VOICE_CALL_ACTIVE_TOOL_STATUS)
                    }.toString()
                )
            )
        } else {
            null
        }
        val exactIdentityPresent = command.approvalId != null && command.executionId != null &&
            command.expectedStateVersion != null
        if (decision !is ToolDecision.Denied && !exactIdentityPresent) {
            // Legacy positive payloads used a mutable latest-by-tool lookup and can approve a
            // newer execution after replay. Only denial may use that conservative legacy path.
            return RunOutcome.Rejected("approval_exact_identity_required")
        }
        val approvalProjection = if (command.approvalId != null && command.executionId != null) {
            secondUserApprovalLifecycle.findExact(
                approvalId = command.approvalId,
                executionId = command.executionId,
                conversationId = conversationId.toString(),
                toolCallId = command.toolCallId,
            )
        } else {
            secondUserApprovalLifecycle.findLatest(
                conversationId = conversationId.toString(),
                toolCallId = command.toolCallId,
            )
        }
        if (exactIdentityPresent && approvalProjection == null) {
            return RunOutcome.Rejected("approval_exact_identity_mismatch")
        }
        if (approvalProjection != null &&
            decision !is ToolDecision.Denied &&
            origin != CommandOrigin.APP_UI
        ) {
            return RunOutcome.Rejected("approval_requires_trusted_app")
        }
        if (
            approvalProjection != null &&
            approvalProjection.subjectType ==
                me.rerere.rikkahub.data.capability.SubjectType.LOCAL_SECOND_USER.name &&
            approvalProjection.subjectId != SecondUserAuthorityRegistry.current()?.subjectId &&
            decision !is ToolDecision.Denied
        ) {
            // A positive decision (including an answer that resumes execution) is tied to the
            // exact authority epoch that created the projection. A stale epoch can only be
            // revoked/denied; it can never resume a tool after reassignment.
            return RunOutcome.Rejected("second_user_authority_stale")
        }
        val newApprovalState = when {
            answer != null -> ToolApprovalState.Answered(answer)
            approved -> ToolApprovalState.Approved
            else -> ToolApprovalState.Denied(reason)
        }
        val persistedDecision = when {
            answer != null -> me.rerere.rikkahub.data.execution.PersistedApprovalDecision.ANSWERED
            approved -> me.rerere.rikkahub.data.execution.PersistedApprovalDecision.APPROVED
            else -> me.rerere.rikkahub.data.execution.PersistedApprovalDecision.DENIED
        }
        var foundPending = false
        var appliedPendingDecision = false
        var foundSameTerminal = false
        var foundConflictingTerminal = false
        val updatedNodes = conversation.messageNodes.map { node ->
            node.copy(messages = node.messages.map { msg ->
                msg.copy(parts = msg.parts.map { part ->
                    if (part !is UIMessagePart.Tool || part.toolCallId != command.toolCallId) return@map part
                    when (val transition = me.rerere.rikkahub.service.chat.resolveToolApproval(
                        current = part.approvalState,
                        requested = newApprovalState,
                    )) {
                        is me.rerere.rikkahub.service.chat.ToolApprovalTransition.Apply -> {
                            foundPending = true
                            appliedPendingDecision = true
                            part.copy(
                                approvalState = transition.state,
                                output = connectedVoiceCallOutput ?: part.output,
                            )
                        }
                        me.rerere.rikkahub.service.chat.ToolApprovalTransition.Idempotent -> {
                            foundSameTerminal = true
                            part
                        }
                        me.rerere.rikkahub.service.chat.ToolApprovalTransition.Conflict -> {
                            foundConflictingTerminal = true
                            part
                        }
                        me.rerere.rikkahub.service.chat.ToolApprovalTransition.NotPending -> part
                    }
                })
            })
        }
        if (!foundPending) {
            if (foundSameTerminal && !foundConflictingTerminal && approvalProjection != null) {
                val durable = conversationRepo.getConversationById(conversationId) ?: conversation
                val canResume = durable.messageNodes
                    .flatMap { it.messages }
                    .flatMap { it.parts }
                    .filterIsInstance<UIMessagePart.Tool>()
                    .any { it.toolCallId == command.toolCallId && it.canResumeExecution }
                when (val replay = secondUserApprovalLifecycle.resolve(
                    currentConversation = durable,
                    updatedConversation = durable,
                    approvalId = approvalProjection.approvalId,
                    executionId = approvalProjection.executionId,
                    toolCallId = command.toolCallId,
                    decision = persistedDecision,
                    expectedStateVersion = command.expectedStateVersion,
                    resolutionRequestId = command.resolutionRequestId ?: envelopeId.toString(),
                    trustedAppApproval = origin == CommandOrigin.APP_UI,
                    authorityCommitInCurrentTransaction = { projection, owningCommandId ->
                        if (!canResume) {
                            null
                        } else {
                            durableCommandQueue.ensureApprovalResumeInCurrentTransaction(
                                conversationId = conversationId,
                                approvalId = projection.approvalId,
                                resolutionRequestId = projection.resolutionRequestId.orEmpty(),
                                resolvedAtMs = projection.resolvedAtMs,
                                approvalCommandId = envelopeId,
                                owningWaitingCommandId = owningCommandId,
                            )
                        }
                    },
                    authorityPostCommit = { commit ->
                        // Publish the graph committed by the outer Room transaction before the
                        // resume row can reach a runtime channel.
                        updateConversation(conversationId, durable)
                        adoptApprovalResumeCommit(conversationId, commit, timing)
                    },
                )) {
                    is me.rerere.rikkahub.data.execution.ApprovalResolutionResult.Applied,
                    is me.rerere.rikkahub.data.execution.ApprovalResolutionResult.Idempotent -> {
                        conversationRepo.getConversationById(conversationId)?.let {
                            updateConversation(conversationId, it)
                        }
                    }
                    is me.rerere.rikkahub.data.execution.ApprovalResolutionResult.Conflict ->
                        return RunOutcome.Conflict(replay.reasonCode)
                    me.rerere.rikkahub.data.execution.ApprovalResolutionResult.Missing ->
                        return RunOutcome.Rejected("approval_projection_missing")
                    me.rerere.rikkahub.data.execution.ApprovalResolutionResult.TrustedAppRequired ->
                        return RunOutcome.Rejected("approval_requires_trusted_app")
                }
                commitApprovalScopeGrant(conversationId, command, approved)
            }
            return when {
                foundConflictingTerminal -> RunOutcome.Conflict("Tool approval already resolved with another decision")
                foundSameTerminal -> RunOutcome.Completed()
                else -> RunOutcome.Rejected("Tool approval is no longer pending")
            }
        }
        val updatedConversation = conversation.copy(messageNodes = updatedNodes)
        val hasPendingAfterUpdate = updatedNodes
            .flatMap { it.messages }
            .flatMap { it.parts }
            .any { it is UIMessagePart.Tool && it.isPending }
        val shouldEnsureResume = me.rerere.rikkahub.service.chat.shouldResumeAfterApproval(
            appliedPendingDecision = appliedPendingDecision,
            hasPendingAfterUpdate = hasPendingAfterUpdate,
        )
        if (acceptedVoiceCall && shouldEnsureResume) {
            pendingVoiceCallResumeByConversation[conversationId] = true
        }
        var resumeEnsuredInApprovalTransaction = false
        val resolvedProjection = if (approvalProjection == null) {
            saveConversation(conversationId, updatedConversation)
            timing?.checkpoint(AgentTimingEventKind.APPROVAL_COMMIT)
            null
        } else {
            when (val result = secondUserApprovalLifecycle.resolve(
                currentConversation = conversation,
                updatedConversation = updatedConversation,
                approvalId = approvalProjection.approvalId,
                executionId = approvalProjection.executionId,
                toolCallId = command.toolCallId,
                decision = persistedDecision,
                expectedStateVersion = command.expectedStateVersion,
                resolutionRequestId = command.resolutionRequestId ?: envelopeId.toString(),
                trustedAppApproval = origin == CommandOrigin.APP_UI,
                authorityCommitInCurrentTransaction = { projection, owningCommandId ->
                    if (!shouldEnsureResume) {
                        null
                    } else {
                        durableCommandQueue.ensureApprovalResumeInCurrentTransaction(
                            conversationId = conversationId,
                            approvalId = projection.approvalId,
                            resolutionRequestId = projection.resolutionRequestId.orEmpty(),
                            resolvedAtMs = projection.resolvedAtMs,
                            approvalCommandId = envelopeId,
                            owningWaitingCommandId = owningCommandId,
                        )
                    }
                },
                authorityPostCommit = { commit ->
                    // A runtime must never observe the pre-commit Pending graph after it sees the
                    // durable resume command.
                    resumeEnsuredInApprovalTransaction = true
                    updateConversation(conversationId, updatedConversation)
                    adoptApprovalResumeCommit(conversationId, commit, timing)
                },
            )) {
                is me.rerere.rikkahub.data.execution.ApprovalResolutionResult.Applied -> {
                    updateConversation(conversationId, updatedConversation)
                    timing?.checkpoint(AgentTimingEventKind.APPROVAL_COMMIT)
                    result.projection
                }
                is me.rerere.rikkahub.data.execution.ApprovalResolutionResult.Idempotent -> {
                    conversationRepo.getConversationById(conversationId)?.let {
                        updateConversation(conversationId, it)
                    }
                    result.projection
                }
                is me.rerere.rikkahub.data.execution.ApprovalResolutionResult.Conflict ->
                    return RunOutcome.Conflict(result.reasonCode)
                me.rerere.rikkahub.data.execution.ApprovalResolutionResult.Missing ->
                    return RunOutcome.Rejected("approval_projection_missing")
                me.rerere.rikkahub.data.execution.ApprovalResolutionResult.TrustedAppRequired ->
                    return RunOutcome.Rejected("approval_requires_trusted_app")
            }
        }
        // Broader allow-list authority is a consequence of a successfully committed exact
        // approval. Never grant it before the approval CAS/graph/execution transaction succeeds.
        commitApprovalScopeGrant(conversationId, command, approved)
        if (isVoiceCallTool) {
            VoiceCallNotifications.cancel(context, conversationId.toString())
        }
        val committedNodes = getConversationFlow(conversationId).value.messageNodes
        val hasPending = committedNodes
            .flatMap { it.messages }
            .flatMap { it.parts }
            .any { it is UIMessagePart.Tool && it.isPending }
        if (me.rerere.rikkahub.service.chat.shouldResumeAfterApproval(
                appliedPendingDecision = appliedPendingDecision,
                hasPendingAfterUpdate = hasPending,
            )) {
            // Resume through the runtime's dedicated approval lane. This keeps the
            // approval run single-owner and prevents a second model continuation
            // from racing ordinary queued messages.
            if (resolvedProjection == null) {
                timing?.mark(AgentTimingEventKind.RESUME_ENQUEUED)
                submitCommand(
                    conversationId = conversationId,
                    command = ResumeAfterApprovalCommand,
                    origin = CommandOrigin.INTERNAL,
                    dedupeKey = null,
                    expiresAt = null,
                    dependencies = emptyList(),
                    agentTimingSubmission = timing?.let(agentTimingStore::tokenForHandle),
                    parentCommandId = envelopeId,
                )
            } else if (!resumeEnsuredInApprovalTransaction) {
                // Exact v2 projections must have admitted their deterministic resume in the
                // approval transaction. Reaching this branch means the boundary was not proven;
                // fail closed and leave the durable WAITING owner intact for repair/replay.
                return RunOutcome.Conflict("approval_resume_atomic_commit_missing")
            }
        }
        _generationDoneFlow.emit(conversationId)
        // This command owns one persisted approval decision only. Other pending tools suspend
        // the generation lineage; they must not leave this approval child non-terminal.
        finishControlAuthority(conversationId, control)
        return RunOutcome.Completed()
    }

    private suspend fun adoptApprovalResumeCommit(
        conversationId: Uuid,
        commit: me.rerere.rikkahub.data.execution.ApprovalResumeAuthorityCommit,
        timing: AgentTimingHandle? = null,
    ) {
        timing?.mark(AgentTimingEventKind.RESUME_ENQUEUED)
        val row = durableCommandQueue.approvalResumeCommitted(commit) ?: return
        if (row.conversationId != conversationId.toString() ||
            row.state !in setOf(
                me.rerere.rikkahub.service.chat.DurableCommandState.PENDING.name,
                me.rerere.rikkahub.service.chat.DurableCommandState.INTERRUPTED.name,
            )
        ) {
            return
        }
        val envelope = durableCommandQueue.decodeFencedEnvelope(
            row,
            origin = CommandOrigin.INTERNAL,
        ) ?: return
        if (envelope.command !is ResumeAfterApprovalCommand) return
        // The row is already durable. enqueueEnvelope re-adopts the exact identity and only
        // supplies an in-memory Deferred/channel entry; it cannot create another resume.
        getOrCreateRuntime(conversationId).enqueueEnvelope(envelope)
    }

    private suspend fun commitApprovalScopeGrant(
        conversationId: Uuid,
        command: ToolApprovalCommand,
        approved: Boolean,
    ) {
        val toolName = command.toolName ?: return
        if (!approved || command.scope == ApprovalScope.Once.name) return
        withTimeout(5.seconds) {
            withContext(Dispatchers.IO) {
                when (command.scope) {
                    ApprovalScope.ChatScope.name -> me.rerere.rikkahub.data.ai.tools
                        .ToolApprovalAllowList.grantForChat(conversationId, toolName)
                    ApprovalScope.Always.name -> toolApprovalPreferences.grantAlways(toolName)
                }
            }
        }
    }

    private suspend fun finishControlAuthority(
        envelope: CommandEnvelope<out ChatCommand>,
        control: GenerationRunControl,
    ) {
        val authority = control.runtimeCommandAuthority() ?: return
        val graph = conversationRepo.getConversationById(envelope.conversationId)
            ?: error("control_conversation_missing")
        authority.finish(
            conversation = graph,
            terminalState = me.rerere.rikkahub.service.chat.DurableCommandState.COMPLETED,
            kind = me.rerere.rikkahub.service.chat.RuntimeAuthorityTerminalKind.CONTROL_ONLY,
            resultAssistantMessageId = null,
        )
    }

    private suspend fun finishControlAuthority(
        conversationId: Uuid,
        control: GenerationRunControl,
    ) {
        val authority = control.runtimeCommandAuthority() ?: return
        val graph = conversationRepo.getConversationById(conversationId)
            ?: error("control_conversation_missing")
        authority.finish(
            conversation = graph,
            terminalState = me.rerere.rikkahub.service.chat.DurableCommandState.COMPLETED,
            kind = me.rerere.rikkahub.service.chat.RuntimeAuthorityTerminalKind.CONTROL_ONLY,
            resultAssistantMessageId = null,
        )
    }

    private val runtimeAdmissionGraphProvider =
        me.rerere.rikkahub.service.chat.RuntimeCommandAdmissionGraphProvider {
            envelope, authoritySubjectId ->
            val conversation = conversationRepo.getConversationById(envelope.conversationId)
                ?: throw IllegalStateException("Conversation not found")
            val lineage = requireNotNull(envelope.lineage) { "command_lineage_required" }
            val anchorId = lineage.branchAnchorMessageId
            val admittedConversation = when (val command = envelope.command) {
                is SendMessageCommand -> {
                    val message = command.content.toAnchoredUserMessage(anchorId)
                    val existing = conversation.messageNodes
                        .flatMap { it.messages }
                        .firstOrNull { it.id == anchorId }
                    if (existing != null && existing != message) {
                        throw IllegalStateException("command_branch_anchor_identity_conflict")
                    }
                    if (existing == null) {
                        conversation.copy(messageNodes = conversation.messageNodes + message.toMessageNode())
                    } else conversation
                }
                else -> {
                    val anchor = conversation.currentMessages.firstOrNull { it.id == anchorId }
                        ?: throw IllegalStateException("command_branch_anchor_missing")
                    require(anchor.role == MessageRole.USER) { "command_branch_anchor_not_user" }
                    conversation
                }
            }
            me.rerere.rikkahub.service.chat.RuntimeCommandAdmissionGraph(
                conversation = admittedConversation,
                scope = me.rerere.rikkahub.data.authority.source.ConversationSourceScopeResolver
                    .forCommand(lineage.assistantIdSnapshot.toString(), authoritySubjectId),
                branchAnchorMessageId = anchorId,
                branchAnchorMessageRevision = lineage.branchAnchorMessageRevision ?: 1L,
            )
        }

    private val runtimeCommandAuthority by lazy {
        me.rerere.rikkahub.service.chat.ProductionRuntimeCommandAuthority(
            conversations = conversationRepo,
            admissionGraphs = runtimeAdmissionGraphProvider,
            admission = commandAdmissionAuthority,
            admissionAdapter = commandAdmissionAuthorityAdapter,
            waiting = waitingApprovalAuthority,
            final = finalConversationAuthority,
            executionMessages = executionMessageAuthorityBinder,
        )
    }


    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        origin: CommandOrigin = CommandOrigin.APP_UI,
        messageRange: ClosedRange<Int>? = null,
        runControl: GenerationRunControl? = null,
        activeCommandId: Uuid? = null,
        propagateFailure: Boolean = false,
        acceptedAssistantSnapshot: Assistant? = null,
        responseCorrelationAnnotation: UIMessageAnnotation? = null,
        persistenceSourceInvalidationMode: ConversationSourceInvalidationMode =
            ConversationSourceInvalidationMode.APPLY,
        persistenceSourceInvalidationNowMs: Long = System.currentTimeMillis(),
        deferPostCommitActions: Boolean = false,
        onDeferredPostCommit: ((DeferredGenerationPostCommit) -> Unit)? = null,
        agentTiming: AgentTimingHandle? = null,
        requestMode: ChatRequestMode = ChatRequestMode.Normal,
        voiceCallRuntimeState: VoiceCallRuntimeState = requestMode.defaultVoiceCallRuntimeState(),
        voiceCallUserEventState: VoiceCallRuntimeState? = null,
        allowVoiceCallAudioTags: Boolean = true,
    ) {
        // Some continuation paths (regenerate, resume-after-approval) do not carry the
        // original command id into this method, but every live generation still owns a
        // stable run id. Resolve the identity once and use it consistently for surface
        // authorization, capability checks, tool budgets, and transient history access.
        val effectiveCommandId = resolveGenerationCommandId(
            activeCommandId = activeCommandId,
            runId = runControl?.runId,
        )
        val authoritativeCommandId = resolveAuthoritativeCommandId(activeCommandId)
        suspend fun applyRunUpdate(block: suspend () -> Unit): Boolean =
            runControl?.runIfUpdatesAllowed(block) ?: run {
                block()
                true
            }

        val callOrigin = resolveToolOrigin(conversationId, origin)
        val settings = settingsStore.settingsFlow.first()
        // Resolve the assistant from this conversation's own assistantId �?the global
        // current-assistant pointer can have moved if the user switched assistants while
        // this generation was queued (multi-assistant crosstalk). Everything downstream
        // (model, memories, tools, sender name) keys off this resolved assistant.
        val initialConversation = getConversationFlow(conversationId).value
        val baseAssistant = acceptedAssistantSnapshot
            ?: settings.getAssistantById(initialConversation.assistantId)
            ?: if (callOrigin == ToolCallOrigin.SystemAssistant || callOrigin == ToolCallOrigin.QuickCapture) {
                throw IllegalStateException(
                    me.rerere.rikkahub.service.chat
                        .SYSTEM_ASSISTANT_TARGET_ASSISTANT_MISSING_REJECTION,
                )
            } else {
                settings.getCurrentAssistant()
            }
        val subAgentProfile = subAgentExecutionProfileRegistry.get(conversationId)
        val assistant = subAgentProfile?.let { profile ->
            baseAssistant.copy(
                chatModelId = profile.effectiveModelId,
                systemPrompt = profile.effectiveSystemPrompt,
            )
        } ?: baseAssistant
        refreshSecondUserAuthorityForInvocation(
            assistant = assistant,
            conversation = initialConversation,
            origin = callOrigin,
        )
        val privilegeContext = me.rerere.rikkahub.privilege.DefaultPrivilegedSessionResolver.resolve(
            assistant = assistant,
            conversation = initialConversation,
            origin = callOrigin,
        )
        val capabilitySubject = capabilitySubjectFor(
            assistant = assistant,
            conversationId = conversationId,
            origin = callOrigin,
            privilege = privilegeContext,
        )
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
            ?: throw IllegalStateException(
                "No chat model selected. Pick one in Settings �?Default models, or send /model in Telegram."
            )
        // Defence against an upstream-Settings bug where disabling all providers can leave
        // the assistant's chatModelId pointing at a model whose provider has enabled=false:
        // the model lookup walks every provider regardless of state, so without this gate
        // inference fires (and bills) against the "disabled" provider's API key. Surface
        // the disabled state clearly instead of silently spending tokens.
        val resolvedProvider = model.findProvider(settings.providers)
        if (resolvedProvider == null) {
            throw IllegalStateException(
                "Selected model '${model.displayName.ifBlank { model.modelId }}' has no matching provider. " +
                    "Pick a different model in Settings or with /model."
            )
        }
        if (!resolvedProvider.enabled) {
            throw IllegalStateException(
                "Provider '${resolvedProvider.name}' is disabled �?refusing to send. " +
                    "Re-enable it in Settings �?Providers, or pick a different model with /model."
            )
        }

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }
        var timingSessionContentReady = false
        val timingAppliedToolResults = if (agentTiming != null) mutableSetOf<String>() else null
        val authority = runControl?.runtimeCommandAuthority()
        var waitingAuthorityCommitted = false

        // Streaming UI throttle state (ported from jude): scoped to this single generation
        // run's collect loop, which processes chunks sequentially in one coroutine.
        var streamingMessageId: Uuid? = null
        var lastStreamingUiUpdateNanos = 0L
        // Voice-call wiring state (ported from jude handleMessageComplete).
        val voiceCallRuntimeContext = buildVoiceCallRuntimeContext(voiceCallRuntimeState)
        val voiceCallAudioTagFormat = settings.getSelectedTTSProvider()
            ?.voiceCallAudioTagFormatOrNull()
            ?.takeIf {
                allowVoiceCallAudioTags && requestMode == ChatRequestMode.VoiceCall
            }
        val voiceCallAudioTagMode = settings.voiceCallAudioTagMode.forVoiceCallProvider(
            settings.getSelectedTTSProvider(),
        )
        var voiceCallFailureReported = false
        var voiceCallNotificationSent = false
        val incrementalVoiceCallTagging =
            requestMode == ChatRequestMode.VoiceCall &&
                voiceCallAudioTagMode == VoiceCallAudioTagMode.SECOND_PASS &&
                voiceCallAudioTagFormat != null
        // Waifu typewriter (sentence-split bubbles): only local chat turns split — the
        // in-app chat UI is the surface that renders the per-sentence bubbles, while
        // remote surfaces (Telegram/WebServer) read the last assistant message as the
        // whole reply, which splitting would truncate. Voice-call paths are mutually
        // exclusive with splitting as well (WAIFU_TASK).
        val waifuSetting = settings.waifuSetting
        val waifuActive = waifuSetting.enabled &&
            requestMode == ChatRequestMode.Normal &&
            !incrementalVoiceCallTagging &&
            callOrigin == ToolCallOrigin.LocalChat
        val waifuStream = if (waifuActive) {
            WaifuStreamState(
                splitter = WaifuSentenceSplitter(
                    minSentenceChars = waifuSetting.minSentenceChars,
                    maxSentenceChars = waifuSetting.maxSentenceChars,
                ),
                charDelayMs = waifuSetting.charDelayMs.coerceAtLeast(0).toLong(),
                maxDelayMs = waifuSetting.maxDelayMs.coerceAtLeast(0).toLong(),
            )
        } else {
            null
        }
        // extraPrompt is appended to the system prompt of THIS generation call only
        // (assistant.copy — no global mutation).
        val waifuAssistant = if (waifuActive && waifuSetting.extraPrompt.isNotBlank()) {
            assistant.copy(
                systemPrompt = listOf(assistant.systemPrompt, waifuSetting.extraPrompt.trim())
                    .filter { it.isNotBlank() }
                    .joinToString(separator = "\n\n"),
            )
        } else {
            assistant
        }
        val tagAssignmentsByMessageId =
            mutableMapOf<Uuid, MutableMap<Int, VoiceCallAudioTagAssignment?>>()
        val nextTagIndexByMessageId = mutableMapOf<Uuid, Int>()
        val tagProjectionLock = Any()
        var latestPrimaryMessages: List<UIMessage>? = null
        // Set inside the generation block below, read by the onSuccess materialization step
        // (which lives outside the runCatching lambda and therefore cannot see its locals).
        var materializationBaseMessageIds: Set<Uuid>? = null
        val generationResult = runCatching {
            // reset suggestions
            updateConversation(conversationId, initialConversation.copy(chatSuggestions = emptyList()))

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (assistant.enableWebSearch ||
                    mcpManager.getAvailableToolsForAssistant(assistant.id).isNotEmpty()
                ) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value
            val isHeadless = me.rerere.rikkahub.data.ai.tools.HeadlessConversations
                .isHeadless(conversationId)
            val toolNameSurface = me.rerere.rikkahub.data.ai.tools.ToolNameSurface()
            val toolExecutionSurface = me.rerere.rikkahub.data.ai.tools.ToolExecutionSurface()
            val invocationCtx = me.rerere.rikkahub.data.ai.tools.ToolInvocationContext(
                callerAssistantId = assistant.id.toString(),
                callerConversationId = conversationId.toString(),
                callerRunId = runControl?.runId?.toString(),
                callerWorkspaceId = assistant.workspaceId?.toString(),
                callOrigin = callOrigin,
                callerModelId = model.id.toString(),
                callerProviderId = resolvedProvider.id.toString(),
                isHeadless = isHeadless,
                modelCanSeeImages = Modality.IMAGE in model.inputModalities,
                privilege = privilegeContext,
                toolNameSurface = toolNameSurface,
                toolExecutionSurface = toolExecutionSurface,
            )
            val workspaceShellSharedStorage =
                me.rerere.rikkahub.data.ai.tools.canMountSecondUserSharedStorage(
                    privilege = privilegeContext,
                    grants = capabilityGrantRepository.current(),
                )
            val secondUserDeviceAccessAddendum = if (privilegeContext.expandLocalTools) {
                val workspace = assistant.workspaceId?.toString()?.let { workspaceId ->
                    workspaceRepository.getById(workspaceId)
                }
                me.rerere.rikkahub.data.ai.tools.secondUserDeviceAccessAddendum(
                    privilege = privilegeContext,
                    workspaceId = workspace?.id,
                    workspaceStorageMode = workspace?.storageMode,
                    workspaceShellSharedStorage = workspaceShellSharedStorage,
                )
            } else {
                null
            }
            val voiceCallToolEnabled = LocalToolOption.VoiceCall in assistant.localTools &&
                voiceCallRuntimeState == VoiceCallRuntimeState.INACTIVE
            val voiceCallConfigured = settings.getSelectedTTSProvider() != null
            val localToolOptions = (if (privilegeContext.expandLocalTools) {
                me.rerere.rikkahub.data.ai.tools.LocalToolOption.PRIVILEGED_IMPLEMENTED
            } else {
                assistant.localTools
            }).filterNot {
                (voiceCallRuntimeState != VoiceCallRuntimeState.INACTIVE && it == LocalToolOption.VoiceCall) ||
                    (it == LocalToolOption.Tts && (requestMode != ChatRequestMode.Normal || !voiceCallConfigured))
            }
            val proactiveVoiceCallEnabled = voiceCallToolEnabled
            val privilegedBridgeEnabled = agentSafetySettings
                .privilegedBridgeEnabledFlow.first()
            val privilegedBridgeStatus = shizukuBridgeManager.status()
            val deviceLocked = (context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager)
                ?.let { it.isDeviceLocked || it.isKeyguardLocked } == true
            val hasAuthorizedInvocation = when (callOrigin) {
                ToolCallOrigin.QuickCapture -> me.rerere.rikkahub.quickcapture
                    .QuickCaptureInvocationRegistry
                    .hasAuthorizedRun(conversationId, effectiveCommandId)
                else -> me.rerere.rikkahub.assistant.SystemAssistantInvocationRegistry
                    .hasAuthorizedUnlockedInvocation(conversationId, effectiveCommandId)
            }
            val invocationSurfaceContext = me.rerere.rikkahub.quickcapture.InvocationSurfaceContexts
                .currentContext(callOrigin, conversationId, effectiveCommandId)
            val toolExposurePlan = me.rerere.rikkahub.data.ai.ToolExposurePlan.create(
                origin = callOrigin,
                deviceLocked = deviceLocked,
                hasAuthorizedInvocation = hasAuthorizedInvocation,
                surfaceContext = invocationSurfaceContext,
            )
            val invocationSurfaceCanExposeTools = toolExposurePlan.surfaceAvailable
            val webSearchToolsEnabled = WebSearchPolicy.canInject(
                assistant = assistant,
                origin = callOrigin,
                toolSurfaceAvailable = invocationSurfaceCanExposeTools,
            )
            fun canExposeTool(toolName: String): Boolean {
                return toolExposurePlan.canExpose(toolName)
            }
            fun canExposeLocalTool(toolName: String): Boolean {
                return canExposeTool(toolName)
            }
            val localToolDefinitions = localTools.getTools(
                localToolOptions,
                invocationCtx,
                usageLockEnabled = settings.usageReminderConfig.lockEnabled,
                voiceCallConfigured = voiceCallConfigured,
            )
                .filter { tool -> canExposeLocalTool(tool.name) }
            val pluginToolRegistrations = if (invocationSurfaceCanExposeTools) {
                pluginToolCatalog.registrations(
                    me.rerere.rikkahub.plugin.PluginToolSurfaceRequest(
                        assistantId = assistant.id.toString(),
                        conversationId = conversationId.toString(),
                        runId = runControl?.runId?.toString().orEmpty(),
                        origin = callOrigin,
                        assistantEnabledPluginIds = assistant.enabledPluginIds,
                        isHeadless = isHeadless,
                        isSubAgent = subAgentProfile != null,
                        stateProjection = buildJsonObject {
                            put("version", 1)
                            put("surface", "local_chat")
                            put("assistant_name", assistant.name.take(80))
                            put("memory_enabled", assistant.enableMemory)
                        }.toString(),
                    ),
                )
            } else {
                emptyList()
            }
            val pluginPromptAddendum = pluginHookBridge.collectPromptAddendum(
                me.rerere.rikkahub.plugin.PluginPromptHookRequest(
                    assistantId = assistant.id.toString(),
                    conversationId = conversationId.toString(),
                    runId = runControl?.runId?.toString().orEmpty(),
                    origin = callOrigin,
                    assistantEnabledPluginIds = assistant.enabledPluginIds,
                    isHeadless = isHeadless,
                    isSubAgent = subAgentProfile != null,
                ),
            )
            val privilegedShellRegistration = if (
                invocationSurfaceCanExposeTools &&
                canExposeTool(me.rerere.rikkahub.privilege.PRIVILEGED_SHELL_TOOL_NAME) &&
                me.rerere.rikkahub.privilege.shouldInjectPrivilegedShell(
                    privilege = privilegeContext,
                    origin = callOrigin,
                    isHeadless = isHeadless,
                    privilegedBridgeEnabled = privilegedBridgeEnabled,
                    bridgeStatus = privilegedBridgeStatus,
                )
            ) {
                me.rerere.rikkahub.privilege.createExternalBridgeRunCommandTool(
                    shizukuBridgeManager,
                )
            } else {
                null
            }
            val structuredPrivilegedRegistration = if (
                invocationSurfaceCanExposeTools &&
                structuredPrivilegedCommandExecutor != null &&
                me.rerere.rikkahub.privilege.shouldInjectStructuredPrivilegedTools(
                    privilege = privilegeContext,
                    origin = callOrigin,
                    isHeadless = isHeadless,
                    privilegedBridgeEnabled = privilegedBridgeEnabled,
                    bridgeStatus = privilegedBridgeStatus,
                )
            ) {
                me.rerere.rikkahub.privilege.createStructuredPrivilegedTools(
                    structuredPrivilegedCommandExecutor,
                )
            } else {
                null
            }
            val structuredPrivilegedV2Registration = if (
                invocationSurfaceCanExposeTools &&
                structuredPrivilegedCommandExecutor != null &&
                me.rerere.rikkahub.privilege.shouldInjectStructuredPrivilegedV2Tools(
                    privilege = privilegeContext,
                    origin = callOrigin,
                    isHeadless = isHeadless,
                    privilegedBridgeEnabled = privilegedBridgeEnabled,
                    bridgeStatus = privilegedBridgeStatus,
                    deviceLocked = deviceLocked,
                )
            ) {
                me.rerere.rikkahub.privilege.createStructuredPrivilegedV2Tools(
                    structuredPrivilegedCommandExecutor,
                )
            } else {
                null
            }
            val verifiedAccessibilityToolDefinitions = if (
                invocationSurfaceCanExposeTools &&
                me.rerere.rikkahub.data.ai.tools.local.shouldInjectVerifiedAccessibilityTools(
                    privilege = privilegeContext,
                    origin = callOrigin,
                    isHeadless = isHeadless,
                )
            ) {
                me.rerere.rikkahub.data.ai.tools.local.verifiedAccessibilityTools(
                    invocationContext = invocationCtx,
                    displayTargetResolver = displayAutomationRuntime?.let { runtime ->
                        me.rerere.rikkahub.data.ai.tools.local.DisplayTargetResolver(runtime)
                    },
                )
            } else {
                emptyList()
            }
            val workspaceProcessTools = if (
                invocationSurfaceCanExposeTools &&
                me.rerere.rikkahub.data.ai.tools.shouldInjectWorkspaceProcessTools(
                    privilege = privilegeContext,
                    origin = callOrigin,
                    isHeadless = isHeadless,
                )
            ) {
                me.rerere.rikkahub.data.ai.tools.createWorkspaceProcessTools(
                    manager = workspaceProcessManager,
                    workspaceRepository = workspaceRepository,
                    defaultWorkspaceId = assistant.workspaceId?.toString(),
                    defaultCwd = conversation.workspaceCwd,
                )
            } else {
                emptyList()
            }

            // start generating
            val session = getOrCreateSession(conversationId)
            // Restore the pre-directory direct tool surface for the active local second user.
            // Other assistants and remote/headless origins keep their existing behaviour.
            // Tool experience and Fast Lane remain durable library features; they are simply no
            // longer required as a schema-discovery detour on this direct surface.
            val secondUserDirectToolSurface =
                privilegeContext.isPrivileged &&
                capabilitySubject.type == me.rerere.rikkahub.data.capability.SubjectType.LOCAL_SECOND_USER &&
                callOrigin in me.rerere.rikkahub.data.ai.InvocationSurfacePolicy.CONFIRMED_LOCAL_SECOND_USER &&
                !isHeadless &&
                subAgentProfile == null
            val toolSurfaceSession = if (secondUserDirectToolSurface) {
                me.rerere.rikkahub.toolcatalog.ToolDiscoverySession(
                    snapshot = me.rerere.rikkahub.toolcatalog.ToolSurfaceBuilder.snapshot(emptyList()),
                    experienceLookup = toolExperienceRepository,
                    experienceEditor = toolExperienceRepository,
                    shortcutEditor = toolShortcutRepository,
                    onSnapshotResolved = { snapshot ->
                        appScope.launch {
                            toolShortcutRepository.reconcileSnapshot(snapshot)
                        }
                    },
                    mode = me.rerere.rikkahub.toolcatalog.ToolSurfaceMode.DIRECT,
                )
            } else {
                null
            }
            val ownerToolSurfaceAvailable =
                me.rerere.rikkahub.data.ai.tools.isOwnerToolSurfaceAvailable(invocationCtx)
            val legacyOwnerRuntimeTools = if (ownerToolSurfaceAvailable) {
                buildList {
                    addAll(
                        me.rerere.rikkahub.data.ai.tools.createPrivilegedManagementTools(
                            invocationContext = invocationCtx,
                            guard = privilegedActionGuard,
                            backend = privilegedManagementBackend,
                            hardDenyPolicy = hardDenyPolicy,
                        ),
                    )
                    addAll(
                        me.rerere.rikkahub.setup.createSetupTools(
                            invocationContext = invocationCtx,
                            coordinator = setupTransactionCoordinator,
                        ),
                    )
                }
            } else {
                emptyList()
            }
            val interactiveTurnBudgetMs = if (subAgentProfile != null) {
                me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits.turnBudgetMs
            } else {
                resolveInteractiveGenerationTurnBudgetMs(
                    configuredMinutes = assistant.generationTurnBudgetMinutes,
                    isActiveLocalSecondUser = secondUserDirectToolSurface,
                    globalTurnBudgetMs = me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits.turnBudgetMs,
                )
            }
            // Freeze one time boundary for standing, expiry, FTS and eventual lastAccess. A long
            // tool loop must not see internally inconsistent memory validity decisions.
            val memoryFrozenNowMs = System.currentTimeMillis()
            // Rolling-summary compression: generation sees only visible nodes; the compressed
            // range is replaced by the persisted summary plus the verbatim tool-history ledger.
            val compressedMessageNodes = conversation.messageNodes.filter { node ->
                node.id in conversation.activeCompressedMessageNodeIds
            }
            val conversationContextSummary = conversation.compressedSummary?.takeIf { it.isNotBlank() }
            val conversationToolHistory = buildCompressedToolHistoryLedger(compressedMessageNodes)
            val generationInputMessages = conversation.messagesForGeneration(messageRange)
            // Voice-call generation context (ported from jude handleMessageComplete).
            val generationBaseMessageIds = conversation.currentMessages.mapTo(mutableSetOf()) { it.id }
            materializationBaseMessageIds = generationBaseMessageIds
            val generationMessages = when (requestMode) {
                ChatRequestMode.Normal ->
                    generationInputMessages.map(UIMessage::withoutVoiceCallAudioTagsForNormalContext)

                // Voice-call directions are injected through the system addendum below.
                // Keep persisted/UI messages untouched so temporary protocol text
                // can never leak into the user's bubble.
                ChatRequestMode.VoiceCall -> generationInputMessages
            }
            val transientLastContextMessage = if (
                requestMode == ChatRequestMode.VoiceCall || voiceCallUserEventState != null
            ) {
                generationMessages.lastOrNull()
                    ?.takeIf { it.role == MessageRole.USER }
                    ?.withVoiceCallRuntimeInstructionForRequest(
                        state = voiceCallRuntimeState,
                        includeConnectionEvent = voiceCallUserEventState != null,
                    )
            } else {
                null
            }
            // repo-svc generateText has no transientLastContextMessage channel; the transient
            // runtime-instruction copy of the last user turn is spliced into the model-visible
            // message list instead (generationBaseMessageIds above stays on the real messages).
            val messagesForModel = if (
                transientLastContextMessage != null && generationMessages.isNotEmpty()
            ) {
                generationMessages.dropLast(1) + transientLastContextMessage
            } else {
                generationMessages
            }
            // Stage D needs the exact command authority even when the independently reviewed
            var memoryRetrievalTraceId: String? = null
            val generationMemories = if (!assistant.enableMemory) {
                emptyList()
            } else {
                agentTiming?.mark(AgentTimingEventKind.MEMORY_RETRIEVAL_STARTED)
                try {
                    val standingPreferences = memoryRepository.getUserApprovedStandingMemories(
                        assistantId = assistant.id,
                        includeGlobal = assistant.useGlobalMemory,
                        frozenNowMs = memoryFrozenNowMs,
                        scopeIdOverride = if (assistant.useConversationMemory) {
                            "conversation:$conversationId"
                        } else {
                            null
                        },
                    )
                    val query = generationInputMessages
                        .lastOrNull { it.role == MessageRole.USER }
                        ?.parts
                        ?.filterIsInstance<UIMessagePart.Text>()
                        ?.joinToString("\n") { it.text }
                        .orEmpty()
                    val retrieval = memoryRepository.retrieveRelevant(
                        assistantId = assistant.id,
                        query = query,
                        includeGlobal = assistant.useGlobalMemory,
                        excludeMemoryIds = standingPreferences.mapTo(hashSetOf()) { it.id },
                        frozenNowMs = memoryFrozenNowMs,
                        querySource = MemoryRetrievalQuerySource.LATEST_USER_TEXT,
                        scopeIdOverride = if (assistant.useConversationMemory) {
                            "conversation:$conversationId"
                        } else {
                            null
                        },
                    )
                    memoryRetrievalTraceId = memoryRetrievalDiagnostics.record(
                        trace = retrieval.trace,
                        recordedAtMs = memoryFrozenNowMs,
                    )
                    (standingPreferences + retrieval.matches.map { it.memory }).distinctBy { it.id }
                } finally {
                    agentTiming?.mark(AgentTimingEventKind.MEMORY_RETRIEVAL_FINISHED)
                }
            }
            agentTiming?.mark(AgentTimingEventKind.TOOL_SURFACE_STARTED)
            // MCP discovery + upstream name validation happen OUTSIDE the generation
            // coroutineScope below: the invalid-name guard aborts the turn with a plain
            // `return`, which suspend lambdas prohibit.
            agentTiming?.mark(AgentTimingEventKind.MCP_DISCOVERY_STARTED)
            val availableMcpTools = try {
                mcpManager.getAvailableToolsForAssistant(assistant.id)
            } finally {
                agentTiming?.mark(AgentTimingEventKind.MCP_DISCOVERY_FINISHED)
            }
            run {
                // Upstream name validation: a server name that isn't pure English+digits
                // would produce an invalid `mcp__<name>__tool` surface, so surface it as an
                // error rather than emit a tool the model can't address.
                val invalidNames = availableMcpTools
                    .map { it.second }
                    .distinct()
                    .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
                if (invalidNames.isNotEmpty()) {
                    addError(
                        error = IllegalStateException(
                            context.getString(
                                R.string.error_mcp_invalid_server_name,
                                invalidNames.joinToString(", ")
                            )
                        ),
                        conversationId = conversationId,
                    )
                    return
                }
            }
            // Voice-call incremental tagging (jude L1004-1248 port). The generation flow runs
            // inside a coroutine scope so that per-sentence SECOND_PASS tagging jobs are
            // children of this turn: they are cancelled with the turn and joined before the
            // final projection at the end of this scope. The wrapped block below keeps its
            // original indentation.
            val generationFlow = coroutineScope {
            val tagJobs = mutableListOf<Job>()
            fun enqueueVoiceCallTagging(messages: List<UIMessage>, includeUnfinishedTail: Boolean) {
                if (!incrementalVoiceCallTagging) return
                // 本仓的 run fencing：被取代/冻结的 run 不再发起标注、不再写会话状态
                // （jude 原版没有 run fence 概念，此处为本仓最小适配）。
                if (runControl?.isUpdateFenced() == true) return
                val primaryReply = messages.lastOrNull { it.role == MessageRole.ASSISTANT && it.toText().isNotBlank() }
                    ?: return
                val segments = splitVoiceCallAudioTaggingSegments(primaryReply.toText().trim())
                val lastEligibleIndex = if (includeUnfinishedTail) {
                    segments.lastIndex
                } else {
                    segments.indexOfLast { segment ->
                        segment.lastOrNull() in setOf('。', '.', '！', '!', '？', '?', '；', ';', '\n')
                    }
                }
                if (lastEligibleIndex < 0) return
                val nextIndex = synchronized(tagProjectionLock) {
                    nextTagIndexByMessageId[primaryReply.id] ?: 0
                }
                if (nextIndex > lastEligibleIndex) return
                for (index in nextIndex..lastEligibleIndex) {
                    val sentence = segments.getOrNull(index) ?: continue
                    synchronized(tagProjectionLock) {
                        nextTagIndexByMessageId[primaryReply.id] = index + 1
                    }
                    tagJobs += launch {
                        val assignmentResult = try {
                            Result.success(
                                tagVoiceCallSentence(
                                    settings = settings,
                                    model = model,
                                    assistant = assistant,
                                    processingStatus = session.processingStatus,
                                    primaryReply = primaryReply,
                                    sentence = sentence,
                                    format = voiceCallAudioTagFormat!!,
                                )
                            )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Result.failure(error)
                        }
                        if (assignmentResult.isFailure) {
                            Logging.log(TAG, "sentence voice-call tagging failed; using no-tag fallback: ${assignmentResult.exceptionOrNull()}")
                        }
                        synchronized(tagProjectionLock) {
                            tagAssignmentsByMessageId
                                .getOrPut(primaryReply.id) { mutableMapOf() }[index] = assignmentResult.getOrNull()
                            val currentConversation = getConversationFlow(conversationId).value
                            val currentReply = currentConversation.currentMessages
                                .firstOrNull { it.id == primaryReply.id }
                            if (currentReply != null && runControl?.isUpdateFenced() != true) {
                                val projectedReply = currentReply.withIncrementalVoiceCallAudioTagAssignments(
                                    assignments = tagAssignmentsByMessageId.getValue(primaryReply.id),
                                    // voiceCallAudioTagFormat is non-null on every path that can
                                    // reach this branch (incrementalVoiceCallTagging gate).
                                    format = voiceCallAudioTagFormat!!,
                                )
                                updateConversation(
                                    conversationId,
                                    currentConversation.updateMessageAtNodeIndex(
                                        nodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
                                            node.messages.any { it.id == projectedReply.id }
                                        }.takeIf { it >= 0 },
                                        message = projectedReply,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            generationHandler.generateText(
                settings = settings,
                model = model,
                processingStatus = session.processingStatus,
                // Read once per call so the surface that wrote the addendum (Telegram bot,
                // anything else) gets its runtime context into the system prompt without
                // having to plumb a parameter all the way through sendMessage. Returns null
                // for in-app conversations that didn't register one.
                // jude 传 runtimeStateSystemPrompt + extraSystemPrompt 两个独立通道；本仓
                // generateText 只有 systemAddendum 一个附加系统提示通道，故语音运行时状态、
                // 语音通话系统提示、主动来电提示全部并入此处（行为对齐，通道合并）。
                systemAddendum = listOfNotNull(
                    me.rerere.rikkahub.data.ai.tools.ConversationSystemAddendum
                        .get(conversationId),
                    secondUserDeviceAccessAddendum,
                    pluginPromptAddendum,
                    voiceCallRuntimeContext.systemPrompt.takeIf { it.isNotBlank() },
                    when (requestMode) {
                        ChatRequestMode.Normal -> null
                        ChatRequestMode.VoiceCall -> listOf(
                            VOICE_CALL_SYSTEM_PROMPT_COMMON.trimIndent(),
                            buildVoiceCallAudioTagPrompt(voiceCallAudioTagMode, voiceCallAudioTagFormat),
                        ).joinToString("\n\n")
                    },
                    PROACTIVE_VOICE_CALL_SYSTEM_PROMPT.trimIndent().takeIf { proactiveVoiceCallEnabled },
                    if (toolSurfaceSession != null) {
                        """
                        Direct tool surface: all currently eligible tool schemas are available in this turn.
                        Use a visible tool directly; do not search or open a directory first. Tool experiences
                        and Fast Lane metadata are hints, never authorization. Re-check the current schema,
                        permission state, and approval requirements before acting.
                        The host library tools `tool_experience_update` and `tool_fast_lane_manage` are
                        available in this trusted second-user surface: use the former only to edit an
                        existing host-created experience, and the latter to list, pin, or unpin a shortcut.
                        """.trimIndent()
                    } else null,
                ).joinToString("\n\n").ifBlank { null },
                isToolAutoApproved = { toolName ->
                    // YOLO mode ("I AM STUPID" toggle in Settings �?Tool approvals): every
                    // tool auto-approves. User opted into this explicitly. HARDLINE still
                    // blocks rm -rf / et al �?that check runs BEFORE auto-approval in
                    // GenerationHandler, so YOLO can't smuggle one through.
                    //
                    // Headless conversations (cron-driven) also auto-approve EVERY tool;
                    // the user pre-authorised the schedule itself at job-creation time
                    // and there's no UI surface to prompt at fire time.
                    //
                    // Otherwise: "Allow for this chat" (in-memory, per-conversation) OR
                    // "Always Allow" (DataStore-backed, across the whole app). The
                    // Once-grant lives in the message itself as
                    // ToolApprovalState.Approved, so it's already handled by the regular
                    // Pending �?Approved transition.
                    //
                    // ask_user is a human-input request, NOT a permission gate. It must pause
                    // for the user whenever there's a surface to ask on (the in-app question card
                    // or the Telegram clarify flow), so it ignores YOLO and the allow-lists �?
                    // otherwise it auto-executes its placeholder body and returns
                    // ask_user_unavailable. In a headless run (cron / sub-agent) there's nobody to
                    // answer, so it still auto-approves there and falls through to that graceful
                    // envelope instead of hanging the turn.
                    if (toolName == "ask_user") {
                        me.rerere.rikkahub.data.ai.tools.HeadlessConversations
                            .shouldAutoApprove(conversationId)
                    } else if (callOrigin in me.rerere.rikkahub.data.ai.InvocationSurfacePolicy.REMOTE) {
                        // Telegram/Web/MCP/external origins are separate principals. They never
                        // inherit second-user, YOLO, or local allow-list decisions. A future
                        // scoped AccessGrant is the only path that may pre-authorize them.
                        false
                    } else if (me.rerere.rikkahub.owner.OwnerAutonomyPolicy.canAutoApprove(
                            privilege = privilegeContext,
                            origin = callOrigin,
                            toolName = toolName,
                        )) {
                        true
                    } else if (
                        me.rerere.rikkahub.plugin.isPluginModelToolName(toolName) ||
                        toolName == "linux_grant_request" ||
                        toolName == "linux_grant_revoke"
                    ) {
                        // Ordinary assistants retain the existing fresh-approval floor. Only the
                        // live local Owner principal above bypasses it.
                        false
                    } else {
                        privilegeContext.autoApproveTools ||
                            (toolName == "call_phone" && privilegeContext.unrestrictedOverride &&
                            toolExecutionGate.canAutoApproveUnrestrictedCallNow(
                                callOrigin,
                            )) ||
                            toolApprovalPreferences.currentYolo() ||
                            me.rerere.rikkahub.data.ai.tools.HeadlessConversations
                                .shouldAutoApprove(conversationId) ||
                            me.rerere.rikkahub.data.ai.tools.ToolApprovalAllowList
                                .isAllowedForChat(conversationId, toolName) ||
                            toolApprovalPreferences.current().contains(toolName)
                    }
                },
                runtimeOnlyTools = legacyOwnerRuntimeTools,
                messages = messagesForModel,
                assistant = waifuAssistant,
                unrestrictedOverride = privilegeContext.unrestrictedOverride,
                capabilitySubject = capabilitySubject,
                selectedPrivilegedConversation = privilegeContext.isPrivileged,
                conversationSystemPrompt = conversation.customSystemPrompt,
                conversationContextSummary = conversationContextSummary,
                conversationToolHistory = conversationToolHistory,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds,
                workspaceCwd = conversation.workspaceCwd,
                callOrigin = callOrigin,
                commandOrigin = origin,
                conversationId = conversationId,
                commandId = effectiveCommandId,
                authoritativeCommandId = authoritativeCommandId,
                memoryFrozenNowMs = memoryFrozenNowMs,
                memoryRetrievalTraceId = memoryRetrievalTraceId,
                runControl = runControl,
                agentTiming = agentTiming,
                isHeadless = isHeadless,
                isSubAgent = subAgentProfile != null,
                maxSteps = subAgentProfile?.generationMaxSteps()
                    ?: resolveInteractiveGenerationMaxSteps(
                        configured = assistant.generationMaxSteps,
                        isActiveLocalSecondUser = secondUserDirectToolSurface,
                    ),
                turnBudgetMs = interactiveTurnBudgetMs,
                memoryToolAllowed = subAgentProfile?.allowsTool("memory_tool") ?: true,
                invocationSurfaceContextProvider =
                    me.rerere.rikkahub.quickcapture.InvocationSurfaceContexts,
                isEmergencyStopActive = {
                    agentSafetySettings.emergencyStopFlow.first()
                },
                startableTools = buildMap {
                    privilegedShellRegistration?.let { registration ->
                        if (canExposeTool(registration.definition.name)) {
                            put(registration.definition.name, registration.startable)
                        }
                    }
                    structuredPrivilegedRegistration?.let { registration ->
                        putAll(registration.startables.filterKeys(::canExposeTool))
                    }
                    structuredPrivilegedV2Registration?.let { registration ->
                        putAll(registration.startables.filterKeys(::canExposeTool))
                    }
                    pluginToolRegistrations.forEach { registration ->
                        put(registration.definition.name, registration.startable)
                    }
                },
                memories = generationMemories,
                inputTransformers = buildList {
                    // Waifu typewriter request-side merge runs FIRST so prompt-injection
                    // transformers never insert between two bubbles of the same group.
                    add(WaifuMergeTransformer)
                    addAll(inputTransformers)
                    add(templateTransformer)
                    add(workspaceReminderTransformer)
                },
                outputTransformers = outputTransformers,
                tools = buildList {
                    if (webSearchToolsEnabled) {
                        addAll(createSearchTools(settings))
                    }
                    if (!privilegeContext.isPrivileged) {
                        addAll(
                            createConversationTools(conversationRepo, assistant.id).filter { tool ->
                                callOrigin == ToolCallOrigin.LocalChat || tool.name != "conversation_search"
                            }
                        )
                    } else if (assistant.allowConversationHistoryRead) {
                        addAll(
                            me.rerere.rikkahub.data.ai.tools.createSecondUserConversationReaderTools(
                                reader = conversationLibraryReader,
                                invocationContext = invocationCtx,
                                commandId = effectiveCommandId,
                                historyReadEnabled = true,
                                deviceUnlocked = { !deviceLocked },
                            )
                        )
                    }
                    addAll(localToolDefinitions)
                    addAll(pluginToolRegistrations.map { it.definition })
                    privilegedShellRegistration?.let { add(it.definition) }
                    structuredPrivilegedRegistration?.let { addAll(it.definitions) }
                    structuredPrivilegedV2Registration?.let { addAll(it.definitions) }
                    addAll(verifiedAccessibilityToolDefinitions)
                    if (privilegeContext.isPrivileged) {
                        add(
                            me.rerere.rikkahub.data.ai.tools.createConversationSendMessageTool(
                                invocationContext = invocationCtx,
                                conversationExists = conversationRepo::existsConversationById,
                                submit = { message ->
                                    submitUserMessage(
                                        conversationId = message.conversationId,
                                        content = message.parts,
                                        answer = message.answer,
                                        origin = me.rerere.rikkahub.service.chat.CommandOrigin.INTERNAL,
                                        dedupeKey = message.dedupeKey,
                                        annotations = message.annotations,
                                    )
                                },
                            )
                        )
                        if (ownerToolSurfaceAvailable) {
                            addAll(
                                me.rerere.rikkahub.data.ai.tools.createOwnerManagementTools(
                                    invocationContext = invocationCtx,
                                    gateway = ownerOperationGateway,
                                ),
                            )
                        }
                        addAll(workspaceProcessTools)
                    }
                    addAll(
                        createWorkspaceToolsIfReady(
                            workspaceId = assistant.workspaceId?.toString(),
                            cwd = conversation.workspaceCwd,
                            allowSharedStorage = workspaceShellSharedStorage,
                        ),
                    )
                    if (assistant.enabledSkills.isNotEmpty()) {
                        addAll(
                            createSkillTools(
                                enabledSkills = assistant.enabledSkills,
                                allSkills = skillManager.listSkills(),
                                skillManager = skillManager,
                                // Direct mode exposes current schemas in the provider request, so
                                // the legacy tool reference must not point the model back into a
                                // search/open directory that is intentionally absent here.
                                redirectSecondUserToolReference = false,
                            )
                        )
                    }
                    availableMcpTools.forEach { (serverId, serverName, tool) ->
                        // Namespace MCP tools by a server-id slug so two enabled servers that
                        // each expose a tool of the same name don't collide (which would 400 or
                        // mis-route to whichever server registered last). Keep the `mcp__` prefix
                        // intact: HardlineCommandGuard and ToolApprovalDefaults both branch on
                        // `startsWith("mcp__")`. The slug is the first 8 hex chars of the id with
                        // dashes stripped; the validated server name follows for human-readable
                        // disambiguation, keeping the name within the 64-char /
                        // ^[a-zA-Z0-9_-]+$ limit. The execute lambda below still calls callTool
                        // with the REAL tool.name, since the namespacing exists only on the
                        // model-facing surface.
                        val serverSlug = serverId.toString().take(8).replace("-", "")
                        val mcpToolName = "mcp__" + serverSlug + "_" + serverName + "__" + tool.name
                        add(
                            Tool(
                                name = mcpToolName,
                                description = tool.description ?: "",
                                parameters = { tool.inputSchema },
                                // MCP servers' tool surfaces are opaque to us �?we can't
                                // tell read from write or safe from destructive �?so
                                // every MCP call is approval-gated by default. The user
                                // can grant Always-Allow per-tool to suppress prompts on
                                // a known-safe MCP server. The HARDLINE floor still
                                // applies via HardlineCommandGuard's `mcp__*` branch,
                                // which scans every string arg for shell-content
                                // patterns (rm -rf /, mkfs, shutdown, encoded payloads).
                                needsApproval = {
                                    me.rerere.rikkahub.data.ai.tools
                                        .ToolApprovalDefaults.requiresApproval(mcpToolName) ||
                                        tool.needsApproval
                                },
                                execute = {
                                    mcpManager.callTool(serverId, tool.name, it.jsonObject)
                                },
                            )
                        )
                    }
                }
                    .asSequence()
                    .filter { tool -> canExposeTool(tool.name) }
                    .filter { tool -> subAgentProfile?.allowsTool(tool.name) ?: true }
                    .toList()
                    .let { definitions ->
                        me.rerere.rikkahub.data.ai.stableProviderToolOrder(definitions)
                    }
                    .also { definitions ->
                        val memoryToolAvailable = assistant.enableMemory &&
                            (subAgentProfile?.allowsTool("memory_tool") ?: true)
                        val availableNames = buildSet {
                            definitions.mapTo(this) { it.name }
                            if (memoryToolAvailable) {
                                add("memory_tool")
                                add("memory_query")
                            }
                            // ToolDiscoverySession adds these compact library tools after the
                            // candidate surface has been assembled. Publish their names too so
                            // nested owner/workflow handoffs do not incorrectly report them as
                            // unknown, while keeping them scoped to the same trusted session.
                            toolSurfaceSession?.managementToolNames()?.let(::addAll)
                        }
                        val knownNames = buildSet {
                            me.rerere.rikkahub.data.capability.CapabilityCatalog
                                .allCapabilities()
                                .flatMapTo(this) { it.toolNames }
                            add("memory_tool")
                            add("memory_query")
                            addAll(availableNames)
                        }
                        check(toolNameSurface.publish(availableNames, knownNames)) {
                            "tool surface was already published for conversation $conversationId"
                        }
                        check(toolExecutionSurface.publish(definitions)) {
                            "tool execution surface was already published for conversation $conversationId"
                        }
                        agentTiming?.mark(AgentTimingEventKind.TOOL_SURFACE_FINISHED)
                    },
                toolDiscoverySession = toolSurfaceSession,
            ).onCompletion {
                if (runControl?.isUpdateFenced() == true) return@onCompletion
                // 取消 Live Update 通知
                cancelLiveUpdateNotification(conversationId)

                // 可能被取消了，或者意外结束，兜底更新
                val baseConversation = getConversationFlow(conversationId).value
                // Voice-call tagging fallback (jude): merge the tag assignments collected so
                // far into the last streamed reply before the reasoning-state sweep. The repo's
                // Conversation has no visibleMessageNodeIndexAt, so the target node is located
                // by message id (the same pattern the streaming update above uses).
                val projectedConversation = synchronized(tagProjectionLock) {
                    val waifuFinalMessages = waifuStream
                        ?.takeIf { it.committedAnyText }
                        ?.projectFinal(latestPrimaryMessages)
                    if (waifuFinalMessages != null) {
                        // Waifu run: project the flushed bubble layout (tail committed,
                        // non-suspending so a cancelled run still lands the last sentence)
                        // instead of writing the raw stream message back over bubble 1.
                        baseConversation.updateCurrentMessages(waifuFinalMessages)
                    } else {
                        val finalStreamMessage = latestPrimaryMessages?.lastOrNull()?.let { message ->
                            tagAssignmentsByMessageId[message.id]?.let { assignments ->
                                message.withIncrementalVoiceCallAudioTagAssignments(
                                    assignments = assignments,
                                    format = voiceCallAudioTagFormat!!,
                                )
                            } ?: message
                        }
                        if (finalStreamMessage != null) {
                            val nodeIndex = baseConversation.messageNodes.indexOfFirst { node ->
                                node.messages.any { it.id == finalStreamMessage.id }
                            }.takeIf { it >= 0 }
                            baseConversation.updateMessageAtNodeIndex(
                                nodeIndex = nodeIndex,
                                message = finalStreamMessage,
                            )
                        } else {
                            baseConversation
                        }
                    }
                }
                val updatedConversation = projectedConversation.copy(
                    messageNodes = projectedConversation.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { it.finishReasoning() })
                    },
                    updateAt = Instant.now()
                )
                if (!applyRunUpdate { updateConversation(conversationId, updatedConversation) }) {
                    return@onCompletion
                }

                // Show notification if app is not in foreground
                if (!updatedConversation.latestAssistantNeedsFinalAnswer() &&
                    !isForeground.value &&
                    settings.displaySetting.enableNotificationOnMessageGeneration
                ) {
                    sendGenerationDoneNotification(conversationId, senderName)
                }
            }.collect { chunk ->
                if (runControl?.isUpdateFenced() == true) return@collect
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        val correlatedMessages = chunk.messages.withResponseCorrelation(
                            responseCorrelationAnnotation,
                        ).sanitizeTransientConversationToolResults()
                        // Voice-call tagging (jude): remember the raw streamed messages for the
                        // end-of-stream incremental projection / second-pass fallback.
                        latestPrimaryMessages = correlatedMessages
                        val timingAssistantMessage = if (agentTiming != null) {
                            correlatedMessages.lastOrNull()
                                ?.takeIf(UIMessage::hasAgentTimingRenderableContent)
                        } else {
                            null
                        }
                        if (!timingSessionContentReady && timingAssistantMessage != null) {
                            agentTiming?.mark(AgentTimingEventKind.SESSION_STATE_APPLY_STARTED)
                        }
                        // Streaming UI throttle (ported from jude): per-chunk full rebuild of
                        // every node is the hot path while tokens stream. Steady-state chunks
                        // of the SAME assistant message update only that message's node, at
                        // most every STREAMING_UI_UPDATE_INTERVAL_NANOS. A new streamed
                        // message id (round boundary) keeps the full updateCurrentMessages
                        // pass so cross-message rewrites (response correlation annotations,
                        // transient tool-result sanitizer) still apply.
                        val latestChunkMessage = correlatedMessages.lastOrNull()
                        // Persist immediately when a tool transitions to "execution started
                        // but no output yet" (the executionStartedAt breadcrumb must reach
                        // disk for replay) or a FinalAnswerRecovery just started.
                        val needsImmediatePersist = latestChunkMessage?.parts?.any { p ->
                            p is UIMessagePart.Tool &&
                                p.executionStartedAt != null &&
                                p.output.isEmpty() &&
                                p.approvalState is ToolApprovalState.Approved
                        } == true || latestChunkMessage?.annotations?.any { annotation ->
                            annotation is UIMessageAnnotation.FinalAnswerRecovery &&
                                annotation.status == FinalAnswerRecoveryStatus.STARTED
                        } == true
                        // Waifu typewriter: split completed sentences off the streaming
                        // tail into their own annotated bubbles. Suspending — applies the
                        // per-sentence delay (turn's first sentence: 0) inline and writes
                        // each bubble as it lands (in-memory, fenced). The FINAL barrier
                        // also flushes the unfinished tail so the last sentence always
                        // reaches disk.
                        val waifuAdvance = waifuStream?.advance(
                            messages = correlatedMessages,
                            forceFlush = chunk.persistenceBarrier ==
                                GenerationPersistenceBarrier.FINAL,
                        ) {
                            applyRunUpdate {
                                val conversation = getConversationFlow(conversationId).value
                                val projected = waifuStream?.project(correlatedMessages)
                                    ?: correlatedMessages
                                updateConversation(
                                    conversationId,
                                    conversation.updateCurrentMessages(projected),
                                )
                            }
                            Unit
                        }
                        val forceStreamingUiUpdate = chunk.persistenceBarrier !=
                            GenerationPersistenceBarrier.NONE || needsImmediatePersist ||
                            waifuAdvance?.hasNewSentences == true
                        val nowNanos = System.nanoTime()
                        val shouldUpdateStreamingUi = forceStreamingUiUpdate || (
                            latestChunkMessage != null && (
                                streamingMessageId != latestChunkMessage.id ||
                                    nowNanos - lastStreamingUiUpdateNanos >=
                                    STREAMING_UI_UPDATE_INTERVAL_NANOS
                                )
                            )
                        // Voice-call incremental projection (jude): merge the tag assignments
                        // produced so far into the streamed copy before it reaches the UI.
                        // Waifu projection (mutually exclusive with voice-call): replace the
                        // split-group messages with their bubble sequences.
                        val projectedMessages = synchronized(tagProjectionLock) {
                            if (!incrementalVoiceCallTagging) {
                                waifuStream?.project(correlatedMessages) ?: correlatedMessages
                            } else {
                                correlatedMessages.map { message ->
                                    tagAssignmentsByMessageId[message.id]?.let { assignments ->
                                        message.withIncrementalVoiceCallAudioTagAssignments(
                                            assignments = assignments,
                                            format = voiceCallAudioTagFormat!!,
                                        )
                                    } ?: message
                                }
                            }
                        }
                        var updatedConversation: Conversation? = null
                        if (shouldUpdateStreamingUi) {
                            updatedConversation = if (latestChunkMessage == null ||
                                streamingMessageId != latestChunkMessage.id
                            ) {
                                getConversationFlow(conversationId).value
                                    .updateCurrentMessages(projectedMessages)
                            } else if (waifuAdvance?.hasCommittedBubbles == true) {
                                // Waifu hot path: after the first sentence is cut, the
                                // original message node holds bubble 1 and the growing tail
                                // lives in the LAST waifu-group node, so the same-id fast
                                // path must target those nodes instead of the original id.
                                var waifuConversation = getConversationFlow(conversationId).value
                                waifuAdvance.firstBubbleMessage?.let { firstBubble ->
                                    val firstIndex = waifuConversation.messageNodes
                                        .indexOfFirst { node ->
                                            node.messages.any { it.id == firstBubble.id }
                                        }.takeIf { it >= 0 }
                                    if (firstIndex != null) {
                                        waifuConversation = waifuConversation
                                            .updateMessageAtNodeIndex(firstIndex, firstBubble)
                                    }
                                }
                                waifuAdvance.tailMessage?.let { tail ->
                                    val tailIndex = waifuConversation.messageNodes
                                        .indexOfFirst { node ->
                                            node.messages.any { it.id == tail.id }
                                        }.takeIf { it >= 0 }
                                    waifuConversation = if (tailIndex != null) {
                                        waifuConversation.updateMessageAtNodeIndex(tailIndex, tail)
                                    } else {
                                        // Tail node not found (should not happen): fall back
                                        // to the full projection pass so no bubble is lost.
                                        waifuConversation
                                            .updateCurrentMessages(projectedMessages)
                                    }
                                }
                                waifuConversation
                            } else {
                                val currentConversation = getConversationFlow(conversationId).value
                                val nodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
                                    node.messages.any { it.id == latestChunkMessage.id }
                                }.takeIf { it >= 0 }
                                currentConversation.updateMessageAtNodeIndex(
                                    nodeIndex = nodeIndex,
                                    message = projectedMessages.lastOrNull() ?: latestChunkMessage,
                                )
                            }
                            if (latestChunkMessage != null) {
                                streamingMessageId = latestChunkMessage.id
                            }
                            lastStreamingUiUpdateNanos = nowNanos
                        }
                        if (!applyRunUpdate {
                                if (chunk.persistenceBarrier ==
                                    GenerationPersistenceBarrier.PENDING_APPROVAL
                                ) {
                                    val isSecondUser = capabilitySubject.type ==
                                        me.rerere.rikkahub.data.capability.SubjectType.LOCAL_SECOND_USER
                                    val pendingTools = if (isSecondUser) correlatedMessages
                                        .lastOrNull()
                                        ?.parts
                                        ?.filterIsInstance<UIMessagePart.Tool>()
                                        ?.filter { it.isPending }
                                        ?.map { tool ->
                                            val schemaFingerprint = me.rerere.rikkahub.toolcatalog
                                                .ToolCatalogSnapshot
                                                .fromDefinitions(toolExecutionSurface.snapshot())
                                                .entry(tool.toolName)
                                                ?.schemaFingerprint
                                                ?: error("approval_tool_schema_missing")
                                            me.rerere.rikkahub.data.execution.PendingApprovalTool(
                                                toolCallId = tool.toolCallId,
                                                toolName = tool.toolName,
                                                arguments = tool.inputAsJson() as? JsonObject
                                                    ?: JsonObject(emptyMap()),
                                                toolSchemaFingerprint = schemaFingerprint,
                                            )
                                        }
                                        .orEmpty() else emptyList()
                                    val pendingOwner = if (isSecondUser) {
                                        me.rerere.rikkahub.data.execution.PendingApprovalOwner(
                                            runId = (runControl?.runId ?: effectiveCommandId).toString(),
                                            commandId = authoritativeCommandId?.toString(),
                                            conversationId = conversationId.toString(),
                                            subjectId = capabilitySubject.id,
                                            subjectType = capabilitySubject.type,
                                            origin = callOrigin,
                                        )
                                    } else {
                                        null
                                    }
                                    val owningMessage = correlatedMessages.lastOrNull()
                                        ?: error("approval_assistant_message_missing")
                                    val existingExecutionIds = owningMessage
                                        .persistedToolExecutionIds(runControl)
                                    if (authority != null) {
                                        authority.checkpointWaiting(
                                            conversation = requireNotNull(updatedConversation) {
                                                "streaming_ui_conversation_missing"
                                            },
                                            assistantMessageId = owningMessage.id,
                                            approvalMutation = { messageId, revision ->
                                                pendingOwner?.let { owner ->
                                                    secondUserApprovalLifecycle
                                                        .persistPendingBarrierInCurrentAuthorityTransaction(
                                                            owner = owner,
                                                            tools = pendingTools,
                                                            assistantMessageId = messageId,
                                                            assistantMessageRevision = revision,
                                                        )
                                                }
                                                executionMessageAuthorityBinder
                                                    .requireBoundInCurrentAuthorityTransaction(
                                                        existingExecutionIds.map { executionId ->
                                                            me.rerere.rikkahub.data.execution
                                                                .ExecutionOwningMessageAuthority(
                                                                    executionId = executionId,
                                                                    assistantMessageId = messageId,
                                                                    assistantMessageRevision = revision,
                                                                )
                                                        },
                                                    )
                                            },
                                            occurredAtMs = persistenceSourceInvalidationNowMs,
                                        )
                                        waitingAuthorityCommitted = true
                                    } else if (pendingOwner != null) {
                                        secondUserApprovalLifecycle.persistPendingBarrier(
                                            conversation = requireNotNull(updatedConversation) {
                                                "streaming_ui_conversation_missing"
                                            },
                                            owner = pendingOwner,
                                            tools = pendingTools,
                                            sourceInvalidationMode = persistenceSourceInvalidationMode,
                                            sourceInvalidationNowMs = persistenceSourceInvalidationNowMs,
                                        )
                                    }
                                }
                                updatedConversation?.let { updateConversation(conversationId, it) }
                            }
                        ) return@collect
                        if (!timingSessionContentReady && timingAssistantMessage != null) {
                            agentTiming?.bindAssistantMessage(timingAssistantMessage.id)
                            agentTiming?.checkpointOnce(AgentTimingEventKind.SESSION_CONTENT_READY)
                            timingSessionContentReady = true
                        }
                        val newlyAppliedToolResults = timingAppliedToolResults?.let { applied ->
                            correlatedMessages.asSequence()
                                .filter { message -> message.role == MessageRole.ASSISTANT }
                                .flatMap { message ->
                                    message.parts.asSequence().mapIndexedNotNull { index, part ->
                                        (part as? UIMessagePart.Tool)
                                            ?.takeIf { it.output.isNotEmpty() }
                                            ?.let { "${message.id}:$index:${it.toolCallId}" }
                                    }
                                }
                                .count(applied::add) > 0
                        } == true
                        if (newlyAppliedToolResults) {
                            agentTiming?.checkpoint(AgentTimingEventKind.TOOL_RESULTS_COLLECTOR_APPLIED)
                        }
                        if (agentTiming != null &&
                            chunk.persistenceBarrier == GenerationPersistenceBarrier.PENDING_APPROVAL
                        ) {
                            val pendingCount = correlatedMessages.asSequence()
                                .flatMap { it.parts.asSequence() }
                                .filterIsInstance<UIMessagePart.Tool>()
                                .count(UIMessagePart.Tool::isPending)
                            agentTiming.approvalPending(pendingCount)
                        }

                        // Persist immediately when a tool transitions to "execution
                        // started but no output yet" �?this writes the executionStartedAt
                        // breadcrumb to disk so a process kill mid-execute leaves a clear
                        // signal for the next replay (see GenerationHandler.kt's replay
                        // safety pass: Approved + executionStartedAt + empty �?Denied
                        // interrupted_unknown_outcome). Without this, the marker stays in
                        // memory only and replay can't distinguish "freshly approved,
                        // never tried" from "interrupted mid-execute" �?silent re-run.
                        if (needsImmediatePersist) {
                            applyRunUpdate {
                                saveConversation(
                                    conversationId = conversationId,
                                    conversation = requireNotNull(updatedConversation) {
                                        "streaming_ui_conversation_missing"
                                    },
                                    sourceInvalidationMode =
                                        persistenceSourceInvalidationMode,
                                    sourceInvalidationNowMs =
                                        persistenceSourceInvalidationNowMs,
                                )
                            }
                        }

                        // 如果应用不在前台，发�?Live Update 通知
                        if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration && settings.displaySetting.enableLiveUpdateNotification) {
                            sendLiveUpdateNotification(conversationId, chunk.messages, senderName)
                        }
                        // Tag the sentences of this chunk that are complete enough to tag
                        // (SECOND_PASS incremental mode). Runs after the UI/persistence path so
                        // the assignments are ready before the next chunk is projected.
                        enqueueVoiceCallTagging(correlatedMessages, includeUnfinishedTail = false)
                    }
                }
            }
            // jude L1224-1247: after the stream ends, tag the unfinished tail, join every
            // sentence job, then project all collected assignments into the conversation.
            // Skipped for a fenced run (repo run-control concept; jude has no equivalent).
            if (incrementalVoiceCallTagging && runControl?.isUpdateFenced() != true) {
                enqueueVoiceCallTagging(
                    getConversationFlow(conversationId).value.currentMessages,
                    includeUnfinishedTail = true,
                )
                tagJobs.forEach { it.join() }
                synchronized(tagProjectionLock) {
                    val currentConversation = getConversationFlow(conversationId).value
                    val projectedMessages = currentConversation.currentMessages.map { message ->
                        tagAssignmentsByMessageId[message.id]?.let { assignments ->
                            message.withIncrementalVoiceCallAudioTagAssignments(
                                assignments = assignments,
                                format = voiceCallAudioTagFormat!!,
                            )
                        } ?: message
                    }
                    updateConversation(
                        conversationId,
                        currentConversation.updateCurrentMessages(projectedMessages),
                    )
                }
            }
            }

            // SECOND_PASS without the incremental stream path (jude L1250-1264): runs the
            // primary reply through the tagger once the turn completed. Incremental mode
            // already tagged in-stream, so it is excluded here exactly like jude. Fenced runs
            // (repo run-control concept) are excluded as well.
            if (runControl?.isUpdateFenced() != true && !incrementalVoiceCallTagging && voiceCallAudioTagMode == VoiceCallAudioTagMode.SECOND_PASS && voiceCallAudioTagFormat != null) {
                val primaryMessages = latestPrimaryMessages
                    ?: getConversationFlow(conversationId).value.currentMessages
                val taggedMessages = applySecondPassVoiceCallAudioTags(
                    settings = settings,
                    model = model,
                    assistant = assistant,
                    processingStatus = session.processingStatus,
                    primaryMessages = primaryMessages,
                    format = voiceCallAudioTagFormat,
                )
                val taggedConversation = getConversationFlow(conversationId).value
                    .updateCurrentMessages(taggedMessages)
                updateConversation(conversationId, taggedConversation)
            }
        }
        var authorityFailure: Throwable? = null
        generationResult.onFailure {
            if (runControl?.isUpdateFenced() == true) return@onFailure
            if (it is CancellationException) throw it
            // 取消 Live Update 通知
            cancelLiveUpdateNotification(conversationId)

            // Persist the in-memory snapshot so the Auto/Pending �?Denied transitions
            // GenerationHandler did inside its try/catch (the "generation_failed" recovery
            // path) survive a process restart. Without this, the failure path only
            // updates memory and the persisted DB row keeps the stale Pending state
            // forever �?replay would re-run the loop against unrecoverable shape.
            runCatching {
                agentTiming?.mark(AgentTimingEventKind.FINAL_SAVE_STARTED)
                try {
                    applyRunUpdate {
                        val final = getConversationFlow(conversationId).value
                        if (authority != null && !waitingAuthorityCommitted) {
                            val assistantMessage = final.currentMessages
                                .lastOrNull { message -> message.role == MessageRole.ASSISTANT }
                            authority.finish(
                                conversation = final,
                                terminalState = me.rerere.rikkahub.service.chat.DurableCommandState.FAILED,
                                kind = me.rerere.rikkahub.service.chat.RuntimeAuthorityTerminalKind
                                    .GENERATION_FINAL_SAVED,
                                resultAssistantMessageId = requireNotNull(assistantMessage).id,
                                errorCode = "GENERATION_FAILED",
                                executionIds = assistantMessage.persistedToolExecutionIds(runControl),
                                sourceInvalidationMode = persistenceSourceInvalidationMode,
                                occurredAtMs = persistenceSourceInvalidationNowMs,
                            )
                            updateConversation(conversationId, final)
                            conversationRepo.refreshSearchProjection(final)
                        } else if (!waitingAuthorityCommitted) {
                            saveConversation(
                                conversationId = conversationId,
                                conversation = final,
                                sourceInvalidationMode = persistenceSourceInvalidationMode,
                                sourceInvalidationNowMs = persistenceSourceInvalidationNowMs,
                            )
                        }
                    }
                } finally {
                    agentTiming?.mark(AgentTimingEventKind.FINAL_SAVE_FINISHED)
                }
            }.onFailure { saveErr ->
                if (authority != null && !waitingAuthorityCommitted && !authority.isTerminalCommitted()) {
                    runCatching { authority.finishAfterFinalSaveFailure() }
                }
                authorityFailure = saveErr
                Log.w(TAG, "handleMessageComplete: failure-path save failed", saveErr)
            }

            it.printStackTrace()
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            if (runControl?.isUpdateFenced() == true) return@onSuccess
            agentTiming?.mark(AgentTimingEventKind.FINAL_SAVE_STARTED)
            try {
                applyRunUpdate {
                    val finalConversation = getConversationFlow(conversationId).value
                    if (authority != null && !waitingAuthorityCommitted) {
                        val assistantMessage = finalConversation.currentMessages
                            .lastOrNull { message -> message.role == MessageRole.ASSISTANT }
                            ?: error("final_assistant_message_missing")
                        try {
                            authority.finish(
                                conversation = finalConversation,
                                terminalState = me.rerere.rikkahub.service.chat.DurableCommandState.COMPLETED,
                                kind = me.rerere.rikkahub.service.chat.RuntimeAuthorityTerminalKind
                                    .GENERATION_FINAL_SAVED,
                                resultAssistantMessageId = assistantMessage.id,
                                executionIds = assistantMessage.persistedToolExecutionIds(runControl),
                                sourceInvalidationMode = persistenceSourceInvalidationMode,
                                occurredAtMs = persistenceSourceInvalidationNowMs,
                            )
                            updateConversation(conversationId, finalConversation)
                            conversationRepo.refreshSearchProjection(finalConversation)
                        } catch (saveError: Throwable) {
                            if (!authority.isTerminalCommitted()) {
                                runCatching { authority.finishAfterFinalSaveFailure() }
                            }
                            authorityFailure = saveError
                            throw saveError
                        }
                    } else if (!waitingAuthorityCommitted) {
                        saveConversation(
                            conversationId = conversationId,
                            conversation = finalConversation,
                            sourceInvalidationMode = persistenceSourceInvalidationMode,
                            sourceInvalidationNowMs = persistenceSourceInvalidationNowMs,
                        )
                    }

                    if (finalConversation.isEligibleForGenerationPostCommit()) {
                        val postCommit = DeferredGenerationPostCommit(
                            conversationId = conversationId,
                            commandOrigin = origin,
                            toolOrigin = callOrigin,
                            assistant = baseAssistant,
                            conversation = finalConversation,
                            isSubAgent = subAgentProfile != null,
                        )
                        if (deferPostCommitActions) {
                            onDeferredPostCommit?.invoke(postCommit)
                        } else {
                            scheduleGenerationPostCommit(postCommit)
                        }
                    }
                }
            } catch (saveError: Throwable) {
                authorityFailure = saveError
                Log.w(TAG, "handleMessageComplete: final authority save failed", saveError)
            } finally {
                agentTiming?.mark(AgentTimingEventKind.FINAL_SAVE_FINISHED)
            }
            // Voice-message replies (jude): once a Normal-mode turn is durably saved,
            // materialize any 【语音条】 segments in the assistant message into voice
            // bubbles and persist the rewrite. Failures are survivable — the text stays,
            // only the audio artifacts are missing — so they must not fail the turn.
            if (requestMode == ChatRequestMode.Normal && materializationBaseMessageIds != null) {
                runCatching {
                    chatVoiceReplyMaterializer.materialize(
                        conversation = getConversationFlow(conversationId).value,
                        generationBaseMessageIds = materializationBaseMessageIds.orEmpty(),
                        settings = settings,
                        onUpdate = { materialized ->
                            updateConversation(conversationId, materialized)
                            // The final save for this turn already happened above, so the
                            // materialized voice bubbles must be persisted explicitly or
                            // they vanish on process restart.
                            saveConversation(
                                conversationId = conversationId,
                                conversation = materialized,
                                sourceInvalidationMode = persistenceSourceInvalidationMode,
                                sourceInvalidationNowMs = persistenceSourceInvalidationNowMs,
                            )
                        },
                    )
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    Log.w(TAG, "voice reply materialization failed", error)
                }
            }
        }
        authorityFailure?.let { throw it }
        if (propagateFailure || authority != null) generationResult.getOrThrow()
    }

    /**
     * Tags one sentence of the primary call reply (jude port). Used by the incremental
     * SECOND_PASS projection: the second-pass tagger runs on a single-sentence copy so each
     * completed sentence can receive its assignment while the reply is still streaming.
     */
    private suspend fun tagVoiceCallSentence(
        settings: Settings,
        model: Model,
        assistant: Assistant,
        processingStatus: MutableStateFlow<String?>,
        primaryReply: UIMessage,
        sentence: String,
        format: VoiceCallAudioTagFormat,
    ): VoiceCallAudioTagAssignment? {
        val sentenceMessage = primaryReply.copy(
            parts = listOf(UIMessagePart.Text(sentence)),
        )
        val taggedMessage = applySecondPassVoiceCallAudioTags(
            settings = settings,
            model = model,
            assistant = assistant,
            processingStatus = processingStatus,
            primaryMessages = listOf(sentenceMessage),
            format = format,
        ).firstOrNull()
        return taggedMessage?.voiceCallAudioTagAssignmentsOrEmpty()?.firstOrNull()
    }

    /**
     * Voice-call second-pass tagging (jude port). Sends the client-owned segments to the tag
     * model with only the `select_voice_call_audio_tags` tool available, then writes the
     * validated per-segment assignments into the reply message.
     *
     * Adaptations for this repo (GenerationHandler has no jude-only parameters):
     * - `extraSystemPrompt` -> `systemAddendum` (the repo's single system-prompt addendum channel).
     * - `providerOverride` / `maxTokensOverride` are not supported; the dedicated
     *   VoiceCallAudioTagConfig apiKey/baseUrl cannot be passed through. A configured
     *   `VoiceCallAudioTagConfig.modelId` is honoured only if a registered provider exposes the
     *   same modelId; otherwise the configured tag model / primary model is used.
     * - `includeMemoriesInPrompt = false` is implicit: `memories = emptyList()` and the tag
     *   assistant keeps `enableMemory = false`.
     */
    private suspend fun applySecondPassVoiceCallAudioTags(
        settings: Settings,
        model: Model,
        assistant: Assistant,
        processingStatus: MutableStateFlow<String?>,
        primaryMessages: List<UIMessage>,
        format: VoiceCallAudioTagFormat,
    ): List<UIMessage> {
        val primaryReplyIndex = primaryMessages.indexOfLast { message ->
            message.role == MessageRole.ASSISTANT && message.toText().isNotBlank()
        }
        if (primaryReplyIndex < 0) return primaryMessages

        val primaryReply = primaryMessages[primaryReplyIndex]
        val primaryReplyText = primaryReply.toText().trim()
        val originalSegments = splitVoiceCallAudioTaggingSegments(primaryReplyText)
        val taggingSegmentIndexes = selectVoiceCallAudioTaggingSegmentIndexes(
            segments = originalSegments,
            englishOnly = settings.displaySetting.ttsEnglishOnly,
        )
        if (taggingSegmentIndexes.isEmpty()) return primaryMessages
        val taggingSegments = taggingSegmentIndexes.map(originalSegments::get)
        val filteredTaggingSegmentIndexes = taggingSegmentIndexes
            .takeIf { settings.displaySetting.ttsEnglishOnly }
        val taggingContext = listOf(
            UIMessage.user(
                buildVoiceCallAudioTaggingRequest(taggingSegments)
            )
        )
        val taggingAssistant = assistant.copy(
            systemPrompt = "",
            temperature = 0f,
            topP = 1f,
            contextMessageSize = 1,
            streamOutput = false,
            enableMemory = false,
            useGlobalMemory = false,
            enableRecentChatsReference = false,
            reasoningLevel = ReasoningLevel.OFF,
            localTools = emptyList(),
            modeInjectionIds = emptySet(),
            lorebookIds = emptySet(),
            enabledSkills = emptySet(),
            enableTimeReminder = false,
            allowConversationSystemPrompt = false,
            allowConversationPromptInjection = false,
        )
        val voiceCallAudioTagConfig = settings.voiceCallAudioTagConfig
        // 二次标注只依赖本地的 select_voice_call_audio_tags 工具。模型注册表按 id 匹配能力，
        // 常见写法（Qwen3-14B / Qwen-2.5-14B-instruct）匹配不到 TOOL，各 provider 就不会把
        // tools 发出去，模型只能回文本导致 missing_tool_call 回退。这里强制带上 TOOL 能力。
        val voiceCallAudioTagModel = if (
            voiceCallAudioTagConfig.enabled &&
            voiceCallAudioTagConfig.modelId.isNotBlank()
        ) {
            val configuredModelId = voiceCallAudioTagConfig.modelId.trim()
            settings.providers.asSequence()
                .flatMap { it.models.asSequence() }
                .firstOrNull { it.modelId == configuredModelId }
                ?: settings.voiceCallAudioTagModelId?.let { modelId -> settings.findModelById(modelId) }
                ?: model
        } else {
            settings.voiceCallAudioTagModelId?.let { modelId -> settings.findModelById(modelId) } ?: model
        }
        val voiceCallAudioTaggingModel = voiceCallAudioTagModel.copy(
            abilities = (voiceCallAudioTagModel.abilities + ModelAbility.TOOL).distinct()
        )
        var toolCallCount = 0
        var selectionResult: VoiceCallAudioTagSelectionResult? = null
        val tagSelectionTool = createVoiceCallAudioTagSelectionTool(
            segmentCount = taggingSegments.size,
            format = format,
            onResult = { result ->
                toolCallCount++
                selectionResult = if (toolCallCount == 1) {
                    result
                } else {
                    VoiceCallAudioTagSelectionResult.InvalidArguments
                }
            },
        )
        var requestFailureReason: VoiceCallTaggingFallbackReason? = null
        var rawTaggingResponse = ""
        Logging.log(
            TAG,
            "applySecondPassVoiceCallAudioTags: provider=${format.providerName}, " +
                "segments=${taggingSegments.size}",
        )
        try {
            generationHandler.generateText(
                settings = settings,
                model = voiceCallAudioTaggingModel,
                processingStatus = processingStatus,
                messages = taggingContext,
                assistant = taggingAssistant,
                memories = emptyList(),
                tools = listOf(tagSelectionTool),
                maxSteps = 1,
                systemAddendum = buildVoiceCallAudioTagPrompt(format),
            ).collect { chunk ->
                if (chunk is GenerationChunk.Messages) {
                    rawTaggingResponse = chunk.messages.lastOrNull()?.toText().orEmpty()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            requestFailureReason = VoiceCallTaggingFallbackReason.REQUEST_ERROR
            Logging.log(TAG, "applySecondPassVoiceCallAudioTags: $error")
        }
        if (requestFailureReason == null) {
            requestFailureReason = when (selectionResult) {
                null -> VoiceCallTaggingFallbackReason.MISSING_TOOL_CALL
                VoiceCallAudioTagSelectionResult.InvalidArguments ->
                    VoiceCallTaggingFallbackReason.INVALID_TOOL_ARGUMENTS

                is VoiceCallAudioTagSelectionResult.Selected -> null
            }
        }
        Logging.log(
            TAG,
            "applySecondPassVoiceCallAudioTags: completed toolCalls=$toolCallCount, " +
                "selection=${selectionResult?.javaClass?.simpleName ?: "missing"}, " +
                "failure=${requestFailureReason?.displayName ?: "none"}",
        )

        val selectedAssignments = (selectionResult as? VoiceCallAudioTagSelectionResult.Selected)?.assignments
        // 部分模型不调用工具、直接回 JSON 文本：客户端只取校验过的 tagId，按索引映射回自己的
        // 原文分段来渲染，绝不使用模型回填的正文，避免模型改写文本进入语音副本。
        val parsedTextFallbackAssignments = if (selectedAssignments == null && rawTaggingResponse.isNotBlank()) {
            parseVoiceCallAudioTagResponse(rawTaggingResponse, format)
                .takeIf { parsed ->
                    parsed.segments.isNotEmpty() &&
                        parsed.segments.all { it.selectionSource != VoiceCallTagSelectionSource.FALLBACK }
                }
                ?.segments
                ?.map { segment ->
                    VoiceCallAudioTagAssignment(
                        tagId = segment.tag?.id,
                        replacementText = segment.replacementText,
                    )
                }
                ?.takeIf { it.size == taggingSegments.size }
        } else {
            null
        }
        return primaryMessages.mapIndexed { index, message ->
            if (index == primaryReplyIndex) {
                when {
                    selectedAssignments != null -> message.withSelectedVoiceCallAudioTagAssignments(
                        selectedAssignments = selectedAssignments,
                        taggingSegmentIndexes = filteredTaggingSegmentIndexes,
                        format = format,
                        selectionFailureReason = requestFailureReason,
                    )

                    parsedTextFallbackAssignments != null -> message.withSelectedVoiceCallAudioTagAssignments(
                        selectedAssignments = parsedTextFallbackAssignments,
                        taggingSegmentIndexes = filteredTaggingSegmentIndexes,
                        format = format,
                        selectionFailureReason = null,
                    )

                    else -> message.withSelectedVoiceCallAudioTagAssignments(
                        selectedAssignments = null,
                        taggingSegmentIndexes = filteredTaggingSegmentIndexes,
                        format = format,
                        selectionFailureReason = requestFailureReason,
                    )
                }
            } else {
                message
            }
        }
    }

    private fun scheduleGenerationPostCommit(postCommit: DeferredGenerationPostCommit) {
        enqueueMemoryCapture(
            conversationId = postCommit.conversationId,
            commandOrigin = postCommit.commandOrigin,
            toolOrigin = postCommit.toolOrigin,
            assistant = postCommit.assistant,
            conversation = postCommit.conversation,
            isSubAgent = postCommit.isSubAgent,
        )
        launchWithConversationReference(postCommit.conversationId) {
            generateTitle(postCommit.conversationId, postCommit.conversation)
        }
        launchWithConversationReference(postCommit.conversationId) {
            generateSuggestion(postCommit.conversationId, postCommit.conversation)
        }
    }

    private fun enqueueMemoryCapture(
        conversationId: Uuid,
        commandOrigin: CommandOrigin,
        toolOrigin: ToolCallOrigin,
        assistant: Assistant,
        conversation: Conversation,
        isSubAgent: Boolean,
    ) {
        val messages = conversation.currentMessages
        val assistantIndex = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
        if (assistantIndex <= 0) return
        val assistantMessage = messages[assistantIndex]
        val userMessage = messages.subList(0, assistantIndex)
            .lastOrNull { it.role == MessageRole.USER } ?: return
        val sourceMessages =
            me.rerere.rikkahub.memory.memoryCaptureSourcesForMessage(userMessage) +
                me.rerere.rikkahub.memory.memoryCaptureSourcesForMessage(assistantMessage)
        val userText = me.rerere.rikkahub.memory.memoryExtractionText(
            sourceMessages,
            setOf(me.rerere.rikkahub.memory.MemorySourceRole.USER),
        )
        val assistantText = me.rerere.rikkahub.memory.memoryExtractionText(
            sourceMessages,
            setOf(
                me.rerere.rikkahub.memory.MemorySourceRole.ASSISTANT,
                me.rerere.rikkahub.memory.MemorySourceRole.TOOL,
            ),
        )
        if (userText.isEmpty() || assistantText.isEmpty()) return

        val captureOrigin = when (toolOrigin) {
            ToolCallOrigin.LocalChat -> me.rerere.rikkahub.memory.MemoryCaptureOrigin.APP_UI
            ToolCallOrigin.SystemAssistant ->
                me.rerere.rikkahub.memory.MemoryCaptureOrigin.SYSTEM_ASSISTANT
            ToolCallOrigin.SystemAssistantKeyguard ->
                me.rerere.rikkahub.memory.MemoryCaptureOrigin.SYSTEM_ASSISTANT_KEYGUARD
            ToolCallOrigin.QuickCapture -> me.rerere.rikkahub.memory.MemoryCaptureOrigin.QUICK_CAPTURE
            ToolCallOrigin.Telegram -> me.rerere.rikkahub.memory.MemoryCaptureOrigin.TELEGRAM
            ToolCallOrigin.WebServer -> me.rerere.rikkahub.memory.MemoryCaptureOrigin.WEB_API
            ToolCallOrigin.TrustedWorkflow -> if (commandOrigin == CommandOrigin.CRON) {
                me.rerere.rikkahub.memory.MemoryCaptureOrigin.CRON
            } else {
                me.rerere.rikkahub.memory.MemoryCaptureOrigin.INTERNAL
            }
            ToolCallOrigin.MCP,
            ToolCallOrigin.ExternalIntent,
            ToolCallOrigin.PetInteraction,
            ToolCallOrigin.PetHandoffAuto,
            -> me.rerere.rikkahub.memory.MemoryCaptureOrigin.INTERNAL
            ToolCallOrigin.PetHandoffConfirmed ->
                me.rerere.rikkahub.memory.MemoryCaptureOrigin.APP_UI
        }
        val scopeId = when {
            assistant.useConversationMemory -> "conversation:$conversationId"
            assistant.useGlobalMemory -> MemoryRepository.GLOBAL_MEMORY_ID
            else -> assistant.id.toString()
        }
        val isHeadless = isSubAgent ||
            me.rerere.rikkahub.data.ai.tools.HeadlessConversations.isHeadless(conversationId)
        appScope.launch(Dispatchers.IO) {
            runCatching {
                memoryV2Coordinator.capture(
                    me.rerere.rikkahub.memory.CompletedMemoryTurn(
                        assistantId = assistant.id,
                        scopeId = scopeId,
                        conversationId = conversationId,
                        userMessageId = userMessage.id,
                        assistantMessageId = assistantMessage.id,
                        origin = captureOrigin,
                        userText = userText,
                        assistantText = assistantText,
                        sourceMessages = sourceMessages,
                        memoryEnabled = assistant.enableMemory,
                        autoSaveMode = assistant.memoryAutoSaveMode,
                        allowedOrigins = assistant.memoryCaptureOrigins,
                        isHeadless = isHeadless,
                        needsFinalAnswer = conversation.latestAssistantNeedsFinalAnswer(),
                        idleDelayMs = assistant.memoryIdleDelayMinutes
                            .coerceIn(1, 1_440) * 60_000L,
                        immediateCaptureThreshold = assistant.memoryImmediateCaptureThreshold
                            .coerceIn(1, 50),
                        // Freeze the selected context window on this capture. A later settings
                        // change must never alter the batch that this completed turn belongs to.
                        conversationContextTurns = assistant.memoryConversationContextTurns
                            .coerceIn(3, 30),
                        narrativeEventsEnabled = assistant.memoryNarrativeEventsEnabled,
                        insightsTheoriesEnabled = assistant.memoryInsightsTheoriesEnabled,
                    ),
                )
            }.onFailure { error ->
                Log.w(TAG, "Memory V2 capture failed after successful chat turn", error)
            }
            runCatching {
                dreamExperienceIngestor.ingestCompletedTurn(
                    assistantId = assistant.id,
                    conversationId = conversationId,
                    userMessageId = userMessage.id,
                    assistantMessageId = assistantMessage.id,
                    userText = userText,
                    assistantText = assistantText,
                    memoryScopeId = scopeId,
                    nowMs = System.currentTimeMillis(),
                )
                dreamExperienceIngestor.syncConfirmedMemories(
                    assistantId = assistant.id,
                    memoryScopeId = scopeId,
                    nowMs = System.currentTimeMillis(),
                )
            }.onFailure { error ->
                Log.w(TAG, "Dream experience ingest failed after successful chat turn", error)
            }
        }
    }

    /**
     * Queues an explicit user selection for Memory V2. Assistant messages are context only; a
     * selection containing no user-authored text is rejected before anything is persisted.
     */
    suspend fun captureMemorySelection(
        conversationId: Uuid,
        selectedNodeIds: Set<Uuid>,
    ): me.rerere.rikkahub.memory.ManualMemorySelectionResult {
        val conversation = getConversationFlow(conversationId).value
        val assistant = settingsStore.settingsFlow.first()
            .getAssistantById(conversation.assistantId)
            ?: return me.rerere.rikkahub.memory.ManualMemorySelectionResult.FAILED
        if (!assistant.enableMemory) {
            return me.rerere.rikkahub.memory.ManualMemorySelectionResult.MEMORY_DISABLED
        }
        val selectedMessages = conversation.messageNodes
            .filter { it.id in selectedNodeIds }
            .map { it.currentMessage }
        val userMessages = selectedMessages.filter { it.role == MessageRole.USER }
        val sourceMessages = selectedMessages.flatMap { message ->
            me.rerere.rikkahub.memory.memoryCaptureSourcesForMessage(message)
        }
        val userText = me.rerere.rikkahub.memory.memoryExtractionText(
            sourceMessages,
            setOf(me.rerere.rikkahub.memory.MemorySourceRole.USER),
        )
        if (userText.isBlank()) {
            return me.rerere.rikkahub.memory.ManualMemorySelectionResult.NO_USER_TEXT
        }
        val assistantMessages = selectedMessages.filter { it.role == MessageRole.ASSISTANT }
        val assistantText = me.rerere.rikkahub.memory.memoryExtractionText(
            sourceMessages,
            setOf(
                me.rerere.rikkahub.memory.MemorySourceRole.ASSISTANT,
                me.rerere.rikkahub.memory.MemorySourceRole.TOOL,
            ),
        )
        if (sourceMessages.isEmpty() ||
            sourceMessages.size > me.rerere.rikkahub.memory.MAX_MEMORY_CAPTURE_SOURCE_IDENTITIES
        ) {
            return me.rerere.rikkahub.memory.ManualMemorySelectionResult.FAILED
        }
        val evidenceAnchor = assistantMessages.lastOrNull()?.id ?: userMessages.last().id
        val scopeId = when {
            assistant.useConversationMemory -> "conversation:$conversationId"
            assistant.useGlobalMemory -> MemoryRepository.GLOBAL_MEMORY_ID
            else -> assistant.id.toString()
        }
        return runCatching {
            memoryV2Coordinator.capture(
                me.rerere.rikkahub.memory.CompletedMemoryTurn(
                    assistantId = assistant.id,
                    scopeId = scopeId,
                    conversationId = conversationId,
                    userMessageId = userMessages.first().id,
                    assistantMessageId = evidenceAnchor,
                    origin = me.rerere.rikkahub.memory.MemoryCaptureOrigin.APP_UI,
                    userText = userText,
                    assistantText = assistantText,
                    sourceMessages = sourceMessages,
                    memoryEnabled = true,
                    autoSaveMode = assistant.memoryAutoSaveMode.takeUnless {
                        it == me.rerere.rikkahub.memory.MemoryAutoSaveMode.OFF
                    } ?: me.rerere.rikkahub.memory.MemoryAutoSaveMode.REVIEW_ALL,
                    allowedOrigins = setOf(me.rerere.rikkahub.memory.MemoryCaptureOrigin.APP_UI),
                    isHeadless = false,
                    needsFinalAnswer = false,
                    captureSource = me.rerere.rikkahub.memory.MemoryCaptureSource.MANUAL_SELECTION,
                    idleDelayMs = 0L,
                    immediateCaptureThreshold = 1,
                    conversationContextTurns = assistant.memoryConversationContextTurns
                        .coerceIn(3, 30),
                    narrativeEventsEnabled = assistant.memoryNarrativeEventsEnabled,
                    insightsTheoriesEnabled = assistant.memoryInsightsTheoriesEnabled,
                ),
            )
        }.fold(
            onSuccess = { result ->
                when (result) {
                    is me.rerere.rikkahub.memory.MemoryCaptureResult.Queued,
                    is me.rerere.rikkahub.memory.MemoryCaptureResult.Duplicate,
                    -> me.rerere.rikkahub.memory.ManualMemorySelectionResult.QUEUED

                    is me.rerere.rikkahub.memory.MemoryCaptureResult.Skipped ->
                        me.rerere.rikkahub.memory.ManualMemorySelectionResult.FAILED
                }
            },
            onFailure = {
                Log.w(TAG, "Manual Memory V2 selection capture failed", it)
                me.rerere.rikkahub.memory.ManualMemorySelectionResult.FAILED
            },
        )
    }

    private suspend fun createWorkspaceToolsIfReady(
        workspaceId: String?,
        cwd: String? = null,
        allowSharedStorage: Boolean = false,
    ): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(
            workspaceId = workspaceId,
            workspaceRepository = workspaceRepository,
            cwd = cwd,
            allowSharedStorage = allowSharedStorage,
        )
    }

    // ---- 检查无效消�?----

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes

        // 移除无效 tool (未执行的 Tool)
        messagesNodes = messagesNodes.mapIndexed { _, node ->
            // Check for Tool type with non-executed tools
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }

            if (hasPendingTools) {
                // Keep messages that are ready to resume, such as approved/denied/answered tools.
                val hasResumableTool = node.currentMessage.getTools().any {
                    !it.isExecuted && it.approvalState.canResumeToolExecution()
                }
                if (hasResumableTool) {
                    return@mapIndexed node
                }

                // If all tools are executed, it's valid
                val allToolsExecuted = node.currentMessage.getTools().all { it.isExecuted }
                if (allToolsExecuted && node.currentMessage.getTools().isNotEmpty()) {
                    return@mapIndexed node
                }

                // Remove messages that still have unresolved tool approvals.
                return@mapIndexed node.copy(
                    messages = node.messages.filter { it.id != node.currentMessage.id },
                    selectIndex = node.selectIndex - 1
                )
            }
            node
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

        updateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
    }

    private fun cancelToolByUser(
        tool: UIMessagePart.Tool,
        cancellationResults: Map<String, CancelRequestResult>,
    ): UIMessagePart.Tool {
        val cancellationResult = cancellationResults[tool.toolCallId]
        val unknown = tool.isInterruptedAttempt &&
            cancellationResult !is CancelRequestResult.LocalWaitCancelledOnly
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    if (unknown) {
                        """{"status":"termination_unknown","error":"Tool execution was interrupted and its external side effect could not be confirmed."}"""
                    } else {
                        """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                    }
                )
            ),
            approvalState = ToolApprovalState.Denied(
                if (unknown) "Tool termination could not be confirmed" else "Generation cancelled by user"
            )
        )
    }

    private suspend fun finishInterruptedPendingTools(
        conversationId: Uuid,
        cancellationResults: Map<String, CancelRequestResult> = emptyMap(),
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val lastMessageId = currentConversation.messageNodes.lastOrNull()?.currentMessage?.id
        var changed = false
        val updatedNodes = currentConversation.messageNodes.map { node ->
            node.copy(messages = node.messages.map { message ->
                var updated = message.finishPendingTools {
                    cancelToolByUser(it, cancellationResults)
                }
                if (
                    message.id == lastMessageId &&
                    message.role == MessageRole.ASSISTANT &&
                    (message.state != UIMessageState.COMPLETED || message.finishedAt == null) &&
                    message.state != UIMessageState.FAILED
                ) {
                    updated = updated.copy(state = UIMessageState.INTERRUPTED)
                }
                if (updated != message) changed = true
                updated
            })
        }
        if (changed) saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return

        // 标题回退（移植自 extv，batch 11a）：标题模型缺失/禁用/失败或返回空时，用首条
        // 用户消息首行兜底，保证标题最终落定；写入门槛由 GeneratedTitle 变异在仓库层
        // 原子保证（force || 现存标题为空），此处不再重复判定。
        val fallback = titleFallbackFrom(conversation.currentMessages)

        suspend fun applyTitle(title: String?) {
            if (title.isNullOrBlank()) return
            mutateConversationMetadata(
                conversationId,
                me.rerere.rikkahub.data.repository.ConversationMetadataMutation.GeneratedTitle(title, force),
            )
        }

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.titleModelId, fallback = settings.fastModelId)
                ?: run { applyTitle(fallback); return@runCatching }
            val provider = model.findProvider(settings.providers)
                ?: run { applyTitle(fallback); return@runCatching }
            // Same defence as handleLlmTurn: don't burn tokens on a disabled provider.
            if (!provider.enabled) {
                applyTitle(fallback)
                return@runCatching
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) })
                    ),
                ),
                params = backgroundTextGenerationParams(model),
            )

            val generatedTitle = result.choices[0].message?.toText()?.trim().orEmpty()
            applyTitle(generatedTitle.ifBlank { fallback })
        }.onFailure {
            if (it is CancellationException) throw it
            // Title generation is auxiliary �?a failure here doesn't block the chat
            // and surfaces visibly as a blank conversation title in the list. Don't
            // push it onto the user-facing error stream: when the title model 429s,
            // the next message sees title.isBlank()==true, tries again, 429s again,
            // and the user gets a popup per message until they switch models. Match
            // the generateSuggestion pattern (log only) to keep the surface quiet.
            Log.w(TAG, "generateTitle failed", it)
            runCatching { applyTitle(fallback) }
                .onFailure { e -> Log.w(TAG, "generateTitle fallback apply failed", e) }
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            if (!settings.enableSuggestion) return
            val model = settings.findModelById(settings.suggestionModelId, fallback = settings.fastModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return
            // Same defence as handleLlmTurn: don't burn tokens on a disabled provider.
            if (!provider.enabled) return

            sessions[conversationId]?.updateState { current ->
                current.withGeneratedSuggestions(emptyList())
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText(maxLength = 500) }),
                    )
                ),
                params = backgroundTextGenerationParams(model),
            )
            val suggestions =
                result.choices[0].message?.toText()?.split("\n")?.map { it.trim() }
                    ?.filter { it.isNotBlank() } ?: emptyList()

            val limitedSuggestions = suggestions.take(10)
            mergeConversationState(conversationId) { current ->
                current.withGeneratedSuggestions(limitedSuggestions)
            }
            conversationRepo.updateConversationSuggestions(conversationId, limitedSuggestions)
        }.onFailure {
            if (it is CancellationException) throw it
            // Suggestion generation is auxiliary �?log only, don't push onto the
            // user-facing error stream (mirrors the generateTitle failure handling).
            Log.w(TAG, "generateSuggestion failed", it)
        }
    }

    // ---- 滚动摘要压缩（jude 移植主体 + extv 工具记录保留语义）----

    /**
     * Rolling-summary compression. Nodes stay in place and are hidden behind a persisted
     * summary; the tool-bearing nodes inside the compressed range are preserved verbatim in
     * a tool-history ledger at generation time instead of being fed into the prose summary.
     */
    suspend fun compressConversationRolling(
        conversationId: Uuid,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32,
        autoCompressConfig: AutoCompressConfig? = null,
    ): Result<Unit> = runCatching {
        val settings = settingsStore.settingsFlow.first()
        initializeConversation(conversationId)
        val compressionBase = getConversationFlow(conversationId).value.normalizeCompressionState()
        val expectedSummary = compressionBase.compressedSummary
        val expectedCompressedNodeIds = compressionBase.activeCompressedMessageNodeIds
        val model = settings.findModelById(settings.compressModelId)
            ?: settings.getChatModelForAssistant(compressionBase.assistantId)
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("Provider not found")
        val compressionProvider = provider.withCompressionApiOverride(settings.compressOpenAIConfig)
        val compressionModel = model.withCompressionModelOverride(settings.compressOpenAIConfig)

        val providerHandler = providerManager.getProviderByType(compressionProvider)

        val visibleNodes = compressionBase.visibleMessageNodes
        val allNodes = visibleNodes
        val allMessages = allNodes.map { it.currentMessage }
        if (allMessages.isEmpty()) {
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        }
        val effectiveKeepRecentMessages = effectiveCompressionKeepRecentMessages(keepRecentMessages)

        // Split messages into those to compress and those to keep
        val nodesToCompress: List<MessageNode>
        val messagesToCompress: List<UIMessage>

        if (allMessages.size > effectiveKeepRecentMessages) {
            nodesToCompress = allNodes.dropLast(effectiveKeepRecentMessages)
            // Plan B: tool-bearing nodes are kept verbatim in the tool-history ledger, so only
            // nodes that carry summarizable text feed the prose summary.
            messagesToCompress = nodesToCompress
                .map { node -> node.currentMessage }
                .filter { it.summaryAsText().isNotBlank() }
        } else {
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        }

        suspend fun generateCompressedSummary(contentToCompress: String, extraContext: String): String {
            val prompt = settings.compressPrompt.applyPlaceholders(
                "content" to contentToCompress,
                "target_tokens" to targetTokens.toString(),
                "additional_context" to extraContext,
                "locale" to Locale.getDefault().displayName
            )

            val result = providerHandler.generateText(
                providerSetting = compressionProvider,
                messages = listOf(UIMessage.user(prompt)),
                params = backgroundTextGenerationParams(compressionModel).copy(maxTokens = targetTokens),
            )

            return result.choices[0].message?.toText()?.trim()
                ?: throw IllegalStateException("Failed to generate compressed summary")
        }

        suspend fun mergeSummaries(summaries: List<String>, extraContext: String): String {
            val nonBlankSummaries = summaries.map { it.trim() }.filter { it.isNotBlank() }
            if (nonBlankSummaries.size <= 1) return nonBlankSummaries.singleOrNull().orEmpty()

            val mergedSummaries = splitTextsForCompression(nonBlankSummaries, targetTokens)
                .map { chunk ->
                    val contentToMerge = chunk.mapIndexed { index, summary ->
                        "Partial summary ${index + 1}:\n$summary"
                    }.joinToString("\n\n")
                    generateCompressedSummary(
                        contentToCompress = contentToMerge,
                        extraContext = extraContext
                    )
                }

            return if (mergedSummaries.size == nonBlankSummaries.size) {
                mergedSummaries.joinToString("\n\n")
            } else {
                mergeSummaries(mergedSummaries, extraContext)
            }
        }

        suspend fun compressMessages(messages: List<UIMessage>): String {
            val contentToCompress = messages.joinToString("\n\n") { it.summaryAsText() }
            val extraContext = buildString {
                append("Summarize only the new messages below.")
                if (additionalPrompt.isNotBlank()) {
                    appendLine()
                    append("Additional instructions from user: $additionalPrompt")
                }
            }
            return generateCompressedSummary(contentToCompress, extraContext)
        }

        // 压缩请求体大、耗时长，限制并发，避免网关同时掐断多个连接。
        val compressionSemaphore = Semaphore(2)
        val compressedSummaries = coroutineScope {
            splitMessagesForCompression(messagesToCompress, targetTokens)
                .map { chunk -> async { compressionSemaphore.withPermit { compressMessages(chunk) } } }
                .awaitAll()
        }

        val compressedSummary = mergeSummaries(
            summaries = compressedSummaries,
            extraContext = buildString {
                append("Merge these partial summaries into one coherent current conversation summary. ")
                append("Remove duplicate headings and duplicate facts, preserve important decisions, ")
                append("user preferences, constraints, open tasks, and current state.")
                if (additionalPrompt.isNotBlank()) {
                    appendLine()
                    append("Additional instructions from user: $additionalPrompt")
                }
            }
        )
        val previousSummary = expectedSummary?.takeIf { it.isNotBlank() }
        val summaryForPrompt = if (previousSummary == null) {
            compressedSummary.ifBlank { null }
        } else {
            val rollingSummaryInput = buildString {
                appendLine("Existing rolling summary:")
                appendLine(previousSummary)
                appendLine()
                appendLine("New summary to merge:")
                appendLine(compressedSummary)
            }
            generateCompressedSummary(
                contentToCompress = rollingSummaryInput,
                extraContext = buildString {
                    append("Merge the existing rolling summary and the new summary into one current conversation summary. ")
                    append("Remove duplicate facts, preserve important decisions, user preferences, constraints, open tasks, and current state. ")
                    append("Mark superseded or corrected information as outdated only when it matters.")
                    if (additionalPrompt.isNotBlank()) {
                        appendLine()
                        append("Additional instructions from user: $additionalPrompt")
                    }
                }
            ).ifBlank { null }
        }
        val selectedNodeIds = nodesToCompress.mapTo(mutableSetOf()) { it.id }
        val latestConversation = getConversationFlow(conversationId).value
        val newConversation = latestConversation
            .withCompressionResultIfBaseUnchanged(
                expectedSummary = expectedSummary,
                expectedCompressedNodeIds = expectedCompressedNodeIds,
                newSummary = summaryForPrompt,
                nodeIdsToCompress = selectedNodeIds,
                newAutoCompressConfig = autoCompressConfig?.copy(
                    keepRecentMessages = effectiveKeepRecentMessages
                ),
            )
            ?.copy(chatSuggestions = emptyList())
        if (newConversation == null) {
            // 压缩期间会话已被其他写入（新一轮压缩/摘要编辑）推进，按过期丢弃本次结果。
            Log.i(TAG, "compressConversationRolling: stale compression result dropped for $conversationId")
            return@runCatching
        }

        saveConversation(conversationId, newConversation)
    }

    private suspend fun autoCompressConversationIfNeeded(
        conversationId: Uuid,
        conversation: Conversation,
    ) {
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(conversation.assistantId)
            ?: settings.getCurrentAssistant()
        // 会话级自动压缩配置（jude 版核心）；AAA 未引入助手级 fallback。
        val config = conversation.autoCompressConfig ?: return
        if (!config.enabled) return

        val triggerMessageCount = assistant?.contextMessageSize ?: return
        if (triggerMessageCount <= 0) return

        val keepRecentMessages = config.keepRecentMessages.coerceAtLeast(1)
        if (conversation.visibleMessageNodes.size < keepRecentMessages + triggerMessageCount) return

        compressConversationRolling(
            conversationId = conversationId,
            additionalPrompt = config.additionalPrompt,
            targetTokens = config.targetTokens,
            keepRecentMessages = keepRecentMessages,
            autoCompressConfig = config.copy(keepRecentMessages = keepRecentMessages),
        ).onFailure {
            // 自动压缩是后台优化：失败时只记录日志、跳过本次压缩，不弹错误卡片打断聊天。
            Log.w(TAG, "autoCompressConversationIfNeeded: $it")
        }
    }

    private fun ProviderSetting.withCompressionApiOverride(
        config: CompressOpenAIConfig,
    ): ProviderSetting {
        if (!config.enabled) return this
        return ProviderSetting.OpenAI(
            apiKey = config.apiKey,
            baseUrl = config.baseUrl,
            chatCompletionsPath = config.chatCompletionsPath,
            useResponseApi = config.useResponseApi,
        )
    }

    private fun Model.withCompressionModelOverride(
        config: CompressOpenAIConfig,
    ): Model {
        if (!config.enabled || config.modelId.isBlank()) return this
        return copy(modelId = config.modelId.trim())
    }

    suspend fun updateCompressedSummary(conversationId: Uuid, summary: String?) {
        val updated = getConversationFlow(conversationId).value.copy(
            compressedSummary = summary?.takeIf { it.isNotBlank() },
        )
        saveConversation(conversationId, updated)
    }

    suspend fun saveConversationAutoCompressConfig(conversationId: Uuid, config: AutoCompressConfig?) {
        val updated = getConversationFlow(conversationId).value.copy(autoCompressConfig = config)
        saveConversation(conversationId, updated)
    }

    // ---- 压缩对话历史 ----

    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32
    ): Result<Unit> {
        return try {
        require(targetTokens in 100..32_000) { "Compression target must be between 100 and 32,000 tokens." }
        require(keepRecentMessages >= 0) { "Messages to keep cannot be negative." }

        val settings = settingsStore.settingsFlow.first()
        val configuredModel = settings.findModelById(settings.compressModelId)
        val configuredProvider = configuredModel?.findProvider(settings.providers)
        val conversationModel = settings.getChatModelForAssistant(conversation.assistantId)
        val conversationProvider = conversationModel?.findProvider(settings.providers)
        val binding = resolveCompressionModelBinding(
            configuredModel = configuredModel,
            configuredProvider = configuredProvider,
            configuredModelIsImplicitDefault = settings.compressModelId == DEFAULT_AUTO_MODEL_ID,
            conversationModel = conversationModel,
            conversationProvider = conversationProvider,
        )
        val model = binding.model
        val provider = binding.provider

        val providerHandler = providerManager.getProviderByType(provider)
        val allMessages = conversation.currentMessages

        // Split messages into those to compress and those to keep
        val retainedCount = keepRecentMessages.coerceAtMost(allMessages.size)
        val messagesToCompress = allMessages.dropLast(retainedCount)
        val messagesToKeep = allMessages.takeLast(retainedCount)
        if (messagesToCompress.isEmpty()) {
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        }

        suspend fun compressMessages(messages: List<UIMessage>): String {
            val contentToCompress = messages.joinToString("\n\n") { it.summaryAsText() }
            val prompt = settings.compressPrompt.applyPlaceholders(
                "content" to contentToCompress,
                "target_tokens" to targetTokens.toString(),
                "additional_context" to if (additionalPrompt.isNotBlank()) {
                    "Additional instructions from user: $additionalPrompt"
                } else "",
                "locale" to Locale.getDefault().displayName
            )

            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = backgroundTextGenerationParams(model).copy(maxTokens = targetTokens),
            )

            return result.choices.firstOrNull()?.message?.toText()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("Compression model returned no usable summary.")
        }

        // Do not fan out manual compression requests concurrently. Some OpenAI-compatible
        // gateways accept ordinary chat but reject parallel large summary requests with 400/429.
        val compressedSummaries = buildList {
            splitManualCompressionMessages(
                messages = messagesToCompress,
                contextWindowTokens = model.userContextWindowTokens,
                targetTokens = targetTokens,
            ).forEach { chunk ->
                add(compressMessages(chunk))
            }
        }

        // Create a stable manual-compression prefix followed by the exact requested tail.
        val newMessageNodes = buildManualCompressionMessages(
            compressedSummaries = compressedSummaries,
            messagesToKeep = messagesToKeep,
        ).map { it.toMessageNode() }
        val newConversation = conversation.copy(
            messageNodes = newMessageNodes,
            chatSuggestions = emptyList(),
        )

        saveConversation(conversationId, newConversation)
        Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    // ---- 通知 ----

    private fun sendGenerationDoneNotification(conversationId: Uuid, senderName: String) {
        // 先取�?Live Update 通知
        cancelLiveUpdateNotification(conversationId)

        val conversation = getConversationFlow(conversationId).value
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = 1
        ) {
            title = senderName
            content = conversation.currentMessages.lastOrNull()?.toText()?.take(50)?.trim() ?: ""
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = getPendingIntent(context, conversationId)
        }
    }

    private fun getLiveUpdateNotificationId(conversationId: Uuid): Int {
        return conversationId.hashCode() + 10000
    }

    private fun sendLiveUpdateNotification(
        conversationId: Uuid,
        messages: List<UIMessage>,
        senderName: String
    ) {
        val lastMessage = messages.lastOrNull() ?: return
        val parts = lastMessage.parts

        // 确定当前状�?
        val (chipText, statusText, contentText) = determineNotificationContent(parts)

        context.sendNotification(
            channelId = CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            notificationId = getLiveUpdateNotificationId(conversationId)
        ) {
            title = senderName
            content = contentText
            subText = statusText
            ongoing = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_PROGRESS
            useBigTextStyle = true
            contentIntent = getPendingIntent(context, conversationId)
            requestPromotedOngoing = true
            shortCriticalText = chipText
        }
    }

    private fun determineNotificationContent(parts: List<UIMessagePart>): Triple<String, String, String> {
        // 检查最近的 part 来确定状�?
        val lastReasoning = parts.filterIsInstance<UIMessagePart.Reasoning>().lastOrNull()
        val lastTool = parts.filterIsInstance<UIMessagePart.Tool>().lastOrNull()
        val lastText = parts.filterIsInstance<UIMessagePart.Text>().lastOrNull()

        return when {
            // 正在执行工具
            lastTool != null && !lastTool.isExecuted -> {
                // MCP tools are exposed as `mcp__<serverSlug>_<serverName>__<toolName>`; strip
                // both the prefix and the server segment so the notification shows the bare tool
                // name. Non-MCP tool names (no `mcp__` prefix) fall through unchanged via the
                // missingDelimiterValue, instead of being truncated at an embedded `__`.
                val toolName = lastTool.toolName
                    .removePrefix("mcp__")
                    .substringAfter("__", missingDelimiterValue = lastTool.toolName.removePrefix("mcp__"))
                Triple(
                    context.getString(R.string.notification_live_update_chip_tool),
                    context.getString(R.string.notification_live_update_tool, toolName),
                    lastTool.input.take(100)
                )
            }
            // 正在思考（Reasoning 未结束）
            lastReasoning != null && lastReasoning.finishedAt == null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_thinking),
                    context.getString(R.string.notification_live_update_thinking),
                    lastReasoning.reasoning.takeLast(200)
                )
            }
            // 正在写回�?
            lastText != null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_writing),
                    lastText.text.takeLast(200)
                )
            }
            // 默认状�?
            else -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_title),
                    ""
                )
            }
        }
    }

    private fun cancelLiveUpdateNotification(conversationId: Uuid) {
        context.cancelNotification(getLiveUpdateNotificationId(conversationId))
    }

    private fun getPendingIntent(context: Context, conversationId: Uuid): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // ---- 对话状态更�?----

    private fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        if (session.isHydrated) session.updateState { it.withRuntimeGraph(conversation) }
        else session.replaceState(conversation)
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        mergeConversationState(conversationId, update)
    }

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     *
     * 若该会话当前有活跃 session（正在查看或后台生成），先同步内存态再落库：
     * 否则仅改数据库 folder_id，而内存里那份 Conversation 仍是旧 folderId，
     * 后续任意 saveConversation(id, state.value) 会用整对象把 folder_id 覆盖回旧值，导致移动丢失。
     * 先改内存可确保这段窗口内的整对象保存也带上新 folderId。
     */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        if (sessions.containsKey(conversationId)) {
            updateConversationState(conversationId) { it.copy(folderId = folderId?.toString() ?: "") }
        }
        conversationRepo.updateConversationFolderId(conversationId, folderId)
    }

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessions.values.any { it.isGenerating && it.state.value.folderId == folderId.toString() }
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     *
     * 先把内存中归属该文件夹的活跃 session folderId 置空，再删库：
     * 否则 clearFolder 只改了数据库，而活跃 session 内存态仍指向该文件夹，
     * 后续整对象保存会写回一个已被删除的 folder_id，导致会话在列表中悬空。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        sessions.values
            .filter { it.state.value.folderId == folderId.toString() }
            .forEach { updateConversationState(it.id) { c -> c.copy(folderId = "") } }
        folderRepository.deleteFolder(folderId)
    }

    private fun mergeConversationState(
        conversationId: Uuid,
        update: (Conversation) -> Conversation,
    ): Conversation {
        // Projection is not a durable commit or proof of attachment ownership. Never delete
        // files here: a rollback or another conversation may still reference them.
        val session = getOrCreateSession(conversationId)
        return session.updateState { current ->
            val next = update(current)
            if (next.id != conversationId) current
            else {
                next
            }
        }
    }

    suspend fun saveConversation(
        conversationId: Uuid,
        conversation: Conversation,
        sourceInvalidationMode: ConversationSourceInvalidationMode =
            ConversationSourceInvalidationMode.APPLY,
        sourceInvalidationNowMs: Long = System.currentTimeMillis(),
    ) {
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时不保存
        }
        // Refuse to overwrite a non-empty stored row with an empty in-memory snapshot.
        // This is the silent-data-loss guard: handleToolApproval / stopGeneration / etc.
        // could be called against an unhydrated session (post-restart), build an empty
        // updatedConversation, and call saveConversation. Without this guard we'd wipe
        // the Pending tool the user was trying to approve.
        if (exists && conversation.messageNodes.isEmpty()) {
            val storedHasContent = runCatching {
                conversationRepo.getConversationById(conversation.id)?.messageNodes?.isNotEmpty() == true
            }.getOrDefault(false)
            if (storedHasContent) {
                Log.w(TAG, "saveConversation: refusing to overwrite non-empty $conversationId with empty snapshot �?likely an unhydrated session")
                return
            }
        }

        require(conversationId == conversation.id)
        val committed = if (!exists) {
            // Only an explicitly initialized draft may create. A missing durable row is not a draft.
            check(conversation.newConversation) { "Conversation disappeared before runtime save" }
            conversation.copy(newConversation = false).also { conversationRepo.insertConversation(it) }
        } else {
            conversationRepo.updateRuntimeConversation(conversation, sourceInvalidationMode, sourceInvalidationNowMs)
                ?: error("Conversation disappeared before runtime save")
        }
        updateConversation(conversationId, committed)
    }

    suspend fun mutateConversationMetadata(
        conversationId: Uuid,
        mutation: me.rerere.rikkahub.data.repository.ConversationMetadataMutation,
    ) {
        val session = getOrCreateSession(conversationId)
        session.metadataMutationMutex.withLock {
            ensureHydrated(conversationId)
            val committed = conversationRepo.mutateMetadata(conversationId, mutation)
            check(session.isHydrated) { "Conversation not initialized" }
            if (committed != null || session.state.value.newConversation) {
                session.updateState { mutation.apply(it) }
            } else {
                error("Conversation not found")
            }
        }
    }

    suspend fun selectMessageVersion(conversationId: Uuid, nodeId: Uuid, messageId: Uuid): SubmitResult {
        val tracked = submitCommandTracked(conversationId,
            me.rerere.rikkahub.service.chat.MutateMessageCommand(nodeId, messageId),
            CommandOrigin.APP_UI, null, null, emptyList())
        val outcome = tracked.outcome.await()
        return if (outcome == CommandOutcome.Completed) tracked.submission
            else SubmitResult.Rejected("Branch selection not applied: $outcome")
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                generationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    // Update translation field in real-time
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                // Save the conversation after translation is complete
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) {
                // Clear translation field on error
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = translationText)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    fun translateVoiceCallBubble(
        conversationId: Uuid,
        message: UIMessage,
        bubbleKey: String,
        sourceText: String,
        targetLanguage: Locale,
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()
                val loadingText = context.getString(R.string.translating)
                val currentMessage = getConversationFlow(conversationId).value.messageNodes
                    .asSequence()
                    .flatMap { it.messages.asSequence() }
                    .firstOrNull { it.id == message.id }
                val cachedTranslation = currentMessage?.voiceCallTranslations?.get(bubbleKey)
                if (cachedTranslation?.isNotBlank() == true && cachedTranslation != loadingText) {
                    return@launch
                }
                if (sourceText.isBlank()) return@launch

                updateVoiceCallTranslationField(conversationId, message.id, bubbleKey, loadingText)
                generationHandler.translateText(
                    settings = settings,
                    sourceText = sourceText,
                    targetLanguage = targetLanguage,
                ) { translatedText ->
                    updateVoiceCallTranslationField(
                        conversationId = conversationId,
                        messageId = message.id,
                        bubbleKey = bubbleKey,
                        translationText = translatedText.sanitizeVoiceCallTextForTranslation(),
                    )
                }.collect { }

                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) {
                updateVoiceCallTranslationField(
                    conversationId = conversationId,
                    messageId = message.id,
                    bubbleKey = bubbleKey,
                    translationText = null,
                )
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    fun translateChatVoiceSegment(
        conversationId: Uuid,
        message: UIMessage,
        segmentIndex: Int,
        sourceText: String,
        targetLanguage: Locale,
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()
                val loadingText = context.getString(R.string.translating)
                val currentMessage = getConversationFlow(conversationId).value.messageNodes
                    .asSequence()
                    .flatMap { it.messages.asSequence() }
                    .firstOrNull { it.id == message.id }
                val cachedTranslation = currentMessage
                    ?.chatVoiceReply()
                    ?.segments
                    ?.getOrNull(segmentIndex)
                    ?.translation
                if (cachedTranslation?.isNotBlank() == true && cachedTranslation != loadingText) {
                    return@launch
                }
                if (sourceText.isBlank()) return@launch

                updateChatVoiceSegmentTranslation(
                    conversationId = conversationId,
                    messageId = message.id,
                    segmentIndex = segmentIndex,
                    translationText = loadingText,
                )
                generationHandler.translateText(
                    settings = settings,
                    sourceText = sourceText,
                    targetLanguage = targetLanguage,
                ) { translatedText ->
                    updateChatVoiceSegmentTranslation(
                        conversationId = conversationId,
                        messageId = message.id,
                        segmentIndex = segmentIndex,
                        translationText = translatedText,
                    )
                }.collect { }
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) {
                updateChatVoiceSegmentTranslation(
                    conversationId = conversationId,
                    messageId = message.id,
                    segmentIndex = segmentIndex,
                    translationText = null,
                )
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    private fun updateVoiceCallTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        bubbleKey: String,
        translationText: String?,
        clearLegacyTranslation: Boolean = false,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                node.copy(
                    messages = node.messages.map { msg ->
                        if (msg.id == messageId) {
                            val translations = msg.voiceCallTranslations.toMutableMap().apply {
                                if (translationText.isNullOrBlank()) {
                                    remove(bubbleKey)
                                } else {
                                    put(bubbleKey, translationText)
                                }
                            }
                            msg.copy(
                                translation = if (clearLegacyTranslation) null else msg.translation,
                                voiceCallTranslations = translations,
                            )
                        } else {
                            msg
                        }
                    }
                )
            } else {
                node
            }
        }
        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    private fun updateChatVoiceSegmentTranslation(
        conversationId: Uuid,
        messageId: Uuid,
        segmentIndex: Int,
        translationText: String?,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.none { it.id == messageId }) {
                node
            } else {
                node.copy(
                    messages = node.messages.map { message ->
                        if (message.id == messageId) {
                            message.updateChatVoiceReplySegment(segmentIndex) { segment ->
                                segment.copy(translation = translationText?.takeIf { it.isNotBlank() })
                            }
                        } else {
                            message
                        }
                    }
                )
            }
        }
        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ): SubmitResult {
        if (parts.isEmptyInputMessage()) {
            return SubmitResult.Rejected("编辑内容为空")
        }

        // 未入库草稿会话读不到 Room 副本：直接给用户可见的拒绝，而不是静默 no-op。
        val current = conversationRepo.getConversationById(conversationId)
            ?: return SubmitResult.Rejected("会话还没保存，先发送一条消息后再编辑")
        val node = current.getMessageNodeByMessageId(messageId)
            ?: return SubmitResult.Rejected("找不到要编辑的消息")
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(current.assistantId) ?: settings.getCurrentAssistant()
        val processed = preprocessUserInputParts(parts, assistant)
        val tracked = submitCommandTracked(conversationId,
            me.rerere.rikkahub.service.chat.MutateMessageCommand(node.id, messageId, processed),
            CommandOrigin.APP_UI, null, null, emptyList())
        // ExTV 语义：编辑必须给用户一个确定的终局。无界 await 曾把 UI 永久挂死在
        // Paused 的 runtime 上，error() 则把任何拒绝变成崩溃线。
        val outcome = withTimeoutOrNull(EDIT_MESSAGE_OUTCOME_TIMEOUT) { tracked.outcome.await() }
        return messageEditSubmissionResult(tracked.submission, outcome)
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val copiedNodePairs = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { sourceNode ->
                sourceNode.id to sourceNode.copy(
                    id = Uuid.random(),
                    messages = sourceNode.messages.map { message ->
                        message.copy(
                            id = Uuid.random(),
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            }
                        )
                    }
                )
            }
        val copiedNodeIdsBySourceId = copiedNodePairs.associate { (sourceNodeId, copiedNode) ->
            sourceNodeId to copiedNode.id
        }
        val copiedNodes = copiedNodePairs.map { it.second }
        val targetNodeId = currentConversation.messageNodes[targetNodeIndex].id
        // 压缩元数据重映射：分支点在压缩范围内时不可复用滚动摘要（摘要可能包含分支点之后的内容）。
        val forkCompressionState = currentConversation.compressionStateForFork(
            targetNodeId = targetNodeId,
            copiedNodeIdsBySourceId = copiedNodeIdsBySourceId,
        )

        val forkConversation = Conversation(
            id = Uuid.random(),
            assistantId = currentConversation.assistantId,
            messageNodes = copiedNodes,
            customSystemPrompt = currentConversation.customSystemPrompt,
            modeInjectionIds = currentConversation.modeInjectionIds,
            lorebookIds = currentConversation.lorebookIds,
            compressedSummary = forkCompressionState.summary,
            compressedMessageNodeIds = forkCompressionState.compressedNodeIds,
            autoCompressConfig = currentConversation.autoCompressConfig,
        )

        conversationRepo.insertConversation(forkConversation)
        updateConversation(forkConversation.id, forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val current = conversationRepo.getConversationById(conversationId)
            ?: throw NotFoundException("Conversation not found")
        val node = current.messageNodes.find { it.id == nodeId }
            ?: throw NotFoundException("Message node not found")
        val target = node.messages.getOrNull(selectIndex) ?: throw BadRequestException("Invalid selectIndex")
        val tracked = submitCommandTracked(conversationId,
            me.rerere.rikkahub.service.chat.MutateMessageCommand(nodeId, target.id),
            CommandOrigin.WEB_API, null, null, emptyList())
        // ExTV 语义（同 editMessage）：分支选择必须给 web 调用方一个确定的终局。无界 await
        // 会把停在 Paused 的 runtime 上的请求永久吊死，check() 则把任何拒绝折叠成裸 500。
        val outcome = withTimeoutOrNull(EDIT_MESSAGE_OUTCOME_TIMEOUT) { tracked.outcome.await() }
        when (outcome) {
            CommandOutcome.Completed -> Unit
            null -> throw ConflictException("分支选择等待超时，请稍后重试")
            is CommandOutcome.Rejected ->
                throw ConflictException("分支选择被拒绝：${outcome.reason}")
            is CommandOutcome.Conflict ->
                throw ConflictException("分支选择冲突：${outcome.reason}")
            is CommandOutcome.Failed ->
                throw ConflictException("分支选择未能生效：${outcome.error.message ?: "未知错误"}")
            else -> throw ConflictException("分支选择未能生效：$outcome")
        }
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw NotFoundException("Message not found")
            }
            return
        }

        // 未持久化草稿（draft 守卫：未入库+标题空+节点空 → saveConversation 静默 return）
        // 会把删除后的内存态冻在原地：空 preset 节点删不掉、操作行一直残留。
        // 直接更新内存态绕开 draft 守卫；仅当会话确无持久化行且仍标记 newConversation 时走此路，
        // 第二道守卫（防未水合空快照覆盖已持久化历史）不受影响。
        if (!conversationRepo.existsConversationById(conversationId) &&
            updatedConversation.newConversation
        ) {
            updateConversationState(conversationId) { updatedConversation }
            return
        }

        saveConversation(conversationId, updatedConversation)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    suspend fun deleteVoiceCallRecord(
        conversationId: Uuid,
        callId: String,
    ) {
        val conversation = getConversationFlow(conversationId).value
        val recordMessages = conversation.messageNodes
            .flatMap { it.messages }
            .mapNotNull { message ->
                message.voiceCallRecord()
                    ?.takeIf { it.callId == callId }
                    ?.let { record -> message to record }
            }
        if (recordMessages.isEmpty()) return

        val linkedMessageIds = buildSet {
            recordMessages.forEach { (message, record) ->
                addAll(record.messageIds)
                if (!record.standalone) add(message.id.toString())
            }
        }
        val audioUris = recordMessages
            .flatMap { (_, record) ->
                record.audioSegments + record.audioSegmentsByMessageId.values.flatten()
            }
            .map { it.audioUri }
            .distinct()
            .map(String::toUri)

        val updatedNodes = conversation.messageNodes.mapNotNull { node ->
            val remainingMessages = node.messages.filterNot { message ->
                message.id.toString() in linkedMessageIds ||
                    message.voiceCallRecord()?.callId == callId
            }
            if (remainingMessages.isEmpty()) {
                null
            } else {
                node.copy(
                    messages = remainingMessages,
                    selectIndex = node.selectIndex.coerceAtMost(remainingMessages.lastIndex),
                )
            }
        }
        saveConversation(
            conversationId,
            conversation.copy(messageNodes = updatedNodes),
        )
        filesManager.deleteChatFiles(audioUris)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = null)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    fun clearVoiceCallTranslation(
        conversationId: Uuid,
        messageId: Uuid,
        bubbleKey: String,
        clearLegacyTranslation: Boolean,
    ) {
        appScope.launch(Dispatchers.IO) {
            updateVoiceCallTranslationField(
                conversationId = conversationId,
                messageId = messageId,
                bubbleKey = bubbleKey,
                translationText = null,
                clearLegacyTranslation = clearLegacyTranslation,
            )
            saveConversation(conversationId, getConversationFlow(conversationId).value)
        }
    }

    fun clearChatVoiceSegmentTranslation(
        conversationId: Uuid,
        messageId: Uuid,
        segmentIndex: Int,
    ) {
        appScope.launch(Dispatchers.IO) {
            updateChatVoiceSegmentTranslation(
                conversationId = conversationId,
                messageId = messageId,
                segmentIndex = segmentIndex,
                translationText = null,
            )
            saveConversation(conversationId, getConversationFlow(conversationId).value)
        }
    }

    /** Stop acknowledgement is not a join. Retain ephemeral state until both authorities settle. */
    internal suspend fun stopAndAwaitQuiescence(conversationId: Uuid, commandId: Uuid?, graceMs: Long): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(graceMs) {
            val runtime = runtimes[conversationId] ?: return@withTimeoutOrNull true
            val job = sessions[conversationId]?.getJob()
            val stop = CommandEnvelope(
                conversationId = conversationId, command = StopCommand(), origin = CommandOrigin.INTERNAL,
                sequence = commandSequences.getOrPut(conversationId) { AtomicLong() }.incrementAndGet(),
            )
            runtime.replaceEmergencyEnvelope(stop)
            if (commandId != null) cancelQueuedCommand(conversationId, commandId)
            if (stop.result.await() != CommandOutcome.Completed) return@withTimeoutOrNull false
            job?.join()
            while (runtime.hasRetainedWork) kotlinx.coroutines.delay(10)
            true
        } ?: false

    // 停止当前会话生成任务（不清理会话缓存�?
    suspend fun stopGeneration(conversationId: Uuid): SubmitResult =
        submitEmergency(conversationId, StopCommand(), CommandOrigin.APP_UI)

}

private fun SubmitResult.toOwnerRunSubmission(): me.rerere.rikkahub.owner.OwnerRunSubmission = when (this) {
    is SubmitResult.Accepted -> me.rerere.rikkahub.owner.OwnerRunSubmission(true, "RUN_CONTROL_ACCEPTED", commandId)
    is SubmitResult.QueueFull -> me.rerere.rikkahub.owner.OwnerRunSubmission(false, "RUN_QUEUE_FULL")
    is SubmitResult.RuntimeUnavailable -> me.rerere.rikkahub.owner.OwnerRunSubmission(false, "RUN_RUNTIME_UNAVAILABLE")
    is SubmitResult.Rejected -> me.rerere.rikkahub.owner.OwnerRunSubmission(false, "RUN_CONTROL_REJECTED")
}

private fun me.rerere.ai.ui.UIMessagePart.toLedgerText(): String = when (this) {
    is me.rerere.ai.ui.UIMessagePart.Text -> text
    is me.rerere.ai.ui.UIMessagePart.Tool -> "[tool call: $toolName]"
    else -> ""
}
