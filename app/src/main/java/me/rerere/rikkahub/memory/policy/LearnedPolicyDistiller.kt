package me.rerere.rikkahub.memory.policy

import android.util.Log
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.rikkahub.data.db.dao.LearnedPolicyDao
import me.rerere.rikkahub.data.db.entity.LearnedPolicyEntity
import me.rerere.rikkahub.data.db.entity.DreamExperienceEntity
import me.rerere.rikkahub.data.model.Settings
import java.util.UUID

/**
 * Lightweight policy distiller (Part B): recent dream experiences in, candidate rules out,
 * all rows land as PENDING and stay inert until a human confirms them in MemoryCenter.
 *
 * Promotion rule: a candidate needs at least [MIN_SUPPORT] distinct supporting experiences,
 * and identical content in the same scope is silently skipped.
 */
class LearnedPolicyDistiller(
    private val policyDao: LearnedPolicyDao,
    private val providerManager: me.rerere.rikkahub.data.ai.provider.ProviderManager,
) {
    suspend fun distill(
        settings: Settings,
        assistantId: String,
        scopeId: String,
        experiences: List<DreamExperienceEntity>,
    ): LearnedPolicyDistillResult {
        if (experiences.size < MIN_SUPPORT) {
            return LearnedPolicyDistillResult.Skipped.NotEnoughInput
        }
        val model = settings.dreamModelId?.let(settings.providers::findModelById)
            ?: return LearnedPolicyDistillResult.Skipped.NoModel
        val providerSetting = model.findProvider(settings.providers)
        if (!providerSetting.enabled) {
            return LearnedPolicyDistillResult.Skipped.NoModel
        }
        val provider = providerManager.getProviderByType(providerSetting)

        val experienceBlock = experiences.joinToString("\n") { exp ->
            "- [${exp.experienceId.take(8)}] (${exp.experienceKind}) ${exp.summary.take(400)}"
        }
        val systemContract = """
            你是策略提炼器。从给定的经历记录中提炼出「可长期遵循的行为策略」。
            规则：
            1. 每条策略必须是一条清晰、可执行、面向未来的指令（不要复述过去发生的事）。
            2. 每条策略必须至少能对应两条不同的经历记录作为支撑。
            3. 只提炼跨经历反复出现的模式，单次事件不要提炼。
            4. 输出 JSON 数组，每项：{"content": "策略正文", "support": ["经历id前缀", ...]}。
            5. 最多 8 条；没有值得提炼的内容就输出 []。
            只输出 JSON，不要任何解释或代码块标记。
        """.trimIndent()
        val userPayload = "经历记录：\n$experienceBlock"

        val messages = listOf(
            me.rerere.ai.ui.UIMessage.system(systemContract),
            me.rerere.ai.ui.UIMessage.user(userPayload),
        )
        val response = try {
            provider.generateText(
                providerSetting = providerSetting,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.2f,
                    maxTokens = POLICY_OUTPUT_BUDGET,
                    tools = emptyList(),
                ),
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "policy distill provider call failed", e)
            return LearnedPolicyDistillResult.Failure
        }
        val text = response.firstOrNull()?.text?.trim().orEmpty()
        if (text.isEmpty()) return LearnedPolicyDistillResult.Failure

        return parseAndStore(text, assistantId, scopeId, experiences)
    }

    private suspend fun parseAndStore(
        text: String,
        assistantId: String,
        scopeId: String,
        experiences: List<DreamExperienceEntity>,
    ): LearnedPolicyDistillResult {
        val cleaned = text
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        val candidates = try {
            val root = Json.parseToJsonElement(cleaned)
            (root as? JsonArray ?: (root as? JsonObject)?.get("policies")?.jsonArray)
                ?.mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    val content = obj["content"]?.jsonPrimitive?.contentOrNull?.take(500) ?: return@mapNotNull null
                    val support = obj["support"]?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        .orEmpty()
                    content to support
                }
                .orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "policy distill JSON parse failed", e)
            return LearnedPolicyDistillResult.Failure
        }

        val now = System.currentTimeMillis()
        val experienceById = experiences.associateBy { it.experienceId.take(8) }
        val rows = mutableListOf<LearnedPolicyEntity>()
        for ((content, supportPrefixes) in candidates) {
            if (rows.size >= MAX_CANDIDATES_PER_RUN) break
            val normalized = content.trim()
            if (normalized.isEmpty()) continue
            if (policyDao.existsByContent(scopeId, normalized)) continue
            // 经历id在提示里被截断为 8 位前缀；回连到完整实体以核验支撑数
            val supportIds = supportPrefixes.mapNotNull { prefix ->
                experienceById[prefix.take(8)]?.experienceId
            }.distinct()
            if (supportIds.size < MIN_SUPPORT) continue
            rows += LearnedPolicyEntity(
                id = UUID.randomUUID().toString(),
                scopeId = scopeId,
                content = normalized,
                status = LearnedPolicyEntity.STATUS_PENDING,
                supportExperienceIds = Json.encodeToString(ListSerializer(String.serializer()), supportIds),
                supportCount = supportIds.size,
                createdAt = now,
                reviewedAt = null,
            )
        }
        if (rows.isEmpty()) return LearnedPolicyDistillResult.Skipped.NoCandidates
        policyDao.insertAll(rows)
        Log.i(TAG, "policy distill stored ${rows.size} candidates for assistant $assistantId")
        return LearnedPolicyDistillResult.Stored(rows.size)
    }

    companion object {
        private const val TAG = "LearnedPolicy"
        const val POLICY_OUTPUT_BUDGET = 8192
        const val MIN_SUPPORT = 2
        const val MAX_CANDIDATES_PER_RUN = 8
    }
}

sealed interface LearnedPolicyDistillResult {
    data class Stored(val count: Int) : LearnedPolicyDistillResult
    data object Failure : LearnedPolicyDistillResult
    data object NoCandidates : LearnedPolicyDistillResult
    sealed interface Skipped : LearnedPolicyDistillResult {
        data object NotEnoughInput : Skipped
        data object NoModel : Skipped
    }
}
