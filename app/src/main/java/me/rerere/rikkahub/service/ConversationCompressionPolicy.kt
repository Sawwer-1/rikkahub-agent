package me.rerere.rikkahub.service

import me.rerere.ai.context.ApproximateContextTokenEstimator
import me.rerere.ai.context.ContextTokenEstimator
import me.rerere.ai.provider.DEFAULT_USER_CONTEXT_WINDOW_TOKENS
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation

private const val COMPRESSION_PROMPT_OVERHEAD_TOKENS = 4_096

/**
 * 手动压缩**未显式传参**时的保留条数兜底。
 *
 * 2026-10-16 由 8 收敛为 32：会话级 `AutoCompressConfig` 的默认 `keepRecentMessages` 就是 32，
 * 弹窗的保留条数选项也是 1/16/32/64——旧的 8 既与它们不一致、也不在选项集合里。有传参时一律
 * 用传参（`ChatVM.handleCompressContext` 显式传递）。
 */
internal const val DEFAULT_MANUAL_COMPRESSION_KEEP_RECENT_MESSAGES = 32

/** Builds the new history while recording the explicit user-owned compression boundary. */
internal fun buildManualCompressionMessages(
    compressedSummaries: List<String>,
    messagesToKeep: List<UIMessage>,
): List<UIMessage> {
    val summaryCount = compressedSummaries.size
    return compressedSummaries.mapIndexed { index, summary ->
        UIMessage.user(summary).copy(
            annotations = listOf(
                UIMessageAnnotation.ManualCompressionSummary(
                    batchIndex = index,
                    batchCount = summaryCount,
                ),
            ),
        )
    } + messagesToKeep
}

/**
 * Splits a *manual* compression request only when it would exceed the context window selected by
 * the user for the compression model. This deliberately does not impose a 100K/128K policy cap.
 * A single oversize message stays intact so the provider can return its real error instead of
 * silently truncating user content.
 */
internal fun splitManualCompressionMessages(
    messages: List<UIMessage>,
    contextWindowTokens: Int,
    targetTokens: Int,
    tokenEstimator: ContextTokenEstimator = ApproximateContextTokenEstimator,
): List<List<UIMessage>> {
    if (messages.isEmpty()) return emptyList()

    val effectiveContextWindow = contextWindowTokens
        .takeIf { it > 0 }
        ?: DEFAULT_USER_CONTEXT_WINDOW_TOKENS
    val outputReserve = targetTokens
        .coerceAtLeast(1_024)
        .coerceAtMost((effectiveContextWindow - 1).coerceAtLeast(1))
    val inputBudget = (effectiveContextWindow - outputReserve - COMPRESSION_PROMPT_OVERHEAD_TOKENS)
        .coerceAtLeast(1)

    val chunks = mutableListOf<List<UIMessage>>()
    val currentChunk = mutableListOf<UIMessage>()
    var currentTokens = 0

    messages.forEach { message ->
        val messageTokens = tokenEstimator.estimate(message).coerceAtLeast(1)
        if (currentChunk.isNotEmpty() && currentTokens + messageTokens > inputBudget) {
            chunks += currentChunk.toList()
            currentChunk.clear()
            currentTokens = 0
        }
        currentChunk += message
        currentTokens += messageTokens
    }
    if (currentChunk.isNotEmpty()) chunks += currentChunk.toList()
    return chunks
}

internal data class CompressionModelBinding(
    val model: Model,
    val provider: ProviderSetting,
)

/**
 * An unconfigured/stale default compression model must not strand an otherwise working chat on a
 * disabled built-in Auto provider. In that one case, compression follows the assistant bound to
 * the conversation. An explicitly selected disabled compression provider remains an error.
 */
internal fun resolveCompressionModelBinding(
    configuredModel: Model?,
    configuredProvider: ProviderSetting?,
    configuredModelIsImplicitDefault: Boolean,
    conversationModel: Model?,
    conversationProvider: ProviderSetting?,
): CompressionModelBinding {
    if (configuredModel != null && configuredProvider?.enabled == true) {
        return CompressionModelBinding(configuredModel, configuredProvider)
    }

    val mayUseConversationModel = configuredModelIsImplicitDefault || configuredModel == null
    if (mayUseConversationModel && conversationModel != null && conversationProvider?.enabled == true) {
        return CompressionModelBinding(conversationModel, conversationProvider)
    }

    val configuredName = configuredModel?.displayName?.ifBlank { configuredModel.modelId }
    when {
        configuredModel != null && configuredProvider == null -> {
            throw IllegalStateException(
                "Compression model '${configuredName ?: "selected model"}' has no matching provider. " +
                    "Choose a valid compression model in Settings > Default models.",
            )
        }

        configuredModel != null && configuredProvider?.enabled == false -> {
            throw IllegalStateException(
                "Compression provider '${configuredProvider.name}' is disabled. " +
                    "Re-enable it or choose a different compression model in Settings > Default models.",
            )
        }

        conversationModel == null -> {
            throw IllegalStateException(
                "No chat model is available for this conversation. Choose one before compressing.",
            )
        }

        conversationProvider == null -> {
            throw IllegalStateException(
                "This conversation's chat model has no matching provider.",
            )
        }

        else -> {
            throw IllegalStateException(
                "This conversation's chat provider '${conversationProvider.name}' is disabled.",
            )
        }
    }
}
