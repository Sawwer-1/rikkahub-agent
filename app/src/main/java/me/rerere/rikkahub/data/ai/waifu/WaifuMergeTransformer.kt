package me.rerere.rikkahub.data.ai.waifu

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.TransformerContext

/** Group annotation carried by every sentence bubble of one waifu-split turn. */
fun UIMessage.waifuGroupIdOrNull(): String? =
    annotations.filterIsInstance<UIMessageAnnotation.WaifuGroup>().firstOrNull()?.groupId

/**
 * Rebuilds this message so that its visible text content is exactly [text]: the first
 * Text part is replaced and any additional Text parts are dropped, while non-text parts
 * (reasoning, images, ...) are preserved in place. A message without any Text part gets
 * one appended.
 */
fun UIMessage.withWaifuText(text: String): UIMessage {
    var replaced = false
    val newParts = buildList {
        for (part in parts) {
            when {
                part is UIMessagePart.Text && !replaced -> {
                    add(UIMessagePart.Text(text))
                    replaced = true
                }

                part is UIMessagePart.Text -> {
                    // Additional text parts collapse into the replaced one.
                }

                else -> add(part)
            }
        }
        if (!replaced) add(UIMessagePart.Text(text))
    }
    return if (newParts == parts) this else copy(parts = newParts)
}

/**
 * Waifu typewriter request-side merge: reassembles adjacent assistant messages that
 * belong to the same waifu group into a single assistant message, so the history sent
 * to the model has exactly the same shape as a normal (unsplit) chat — no consecutive
 * assistant messages.
 *
 * Mounted unconditionally in the chat input transformer chain; messages without a
 * [UIMessageAnnotation.WaifuGroup] annotation are never touched, so plain chats and
 * historical data are unaffected.
 *
 * Rules (WAIFU_TASK design decisions, fixed):
 * - Merge runs of adjacent ASSISTANT messages sharing the same group id.
 * - Merged text is joined with "\n\n"; the first message donates id / modelId /
 *   createdAt / usage / annotations.
 * - A run is broken by any message that is not an assistant of the same group (user,
 *   tool, another group) — no cross-group merging.
 */
object WaifuMergeTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = mergeWaifuGroupMessages(messages)
}

/** Pure merge used by [WaifuMergeTransformer]; exposed for JVM unit tests. */
fun mergeWaifuGroupMessages(messages: List<UIMessage>): List<UIMessage> {
    if (messages.size < 2) return messages
    var mergedAny = false
    val result = mutableListOf<UIMessage>()
    var index = 0
    while (index < messages.size) {
        val message = messages[index]
        val groupId = message.waifuGroupIdOrNull()
        if (message.role != MessageRole.ASSISTANT || groupId == null) {
            result.add(message)
            index++
            continue
        }
        var end = index + 1
        while (end < messages.size) {
            val next = messages[end]
            if (next.role == MessageRole.ASSISTANT && next.waifuGroupIdOrNull() == groupId) {
                end++
            } else {
                break
            }
        }
        if (end == index + 1) {
            result.add(message)
            index++
            continue
        }
        mergedAny = true
        val run = messages.subList(index, end)
        val first = run.first()
        val last = run.last()
        val mergedText = run.joinToString(separator = "\n\n") { it.toText() }
        val nonTextParts = run.drop(1).flatMap { tail ->
            tail.parts.filter { part -> part !is UIMessagePart.Text }
        }
        result.add(
            first.copy(
                parts = first.withWaifuText(mergedText).parts + nonTextParts,
                // The turn outcome (state/terminal/finishedAt) lives on the last bubble;
                // identity (id/modelId/createdAt), usage and annotations stay on the first.
                state = last.state,
                finishedAt = last.finishedAt ?: first.finishedAt,
                terminal = last.terminal ?: first.terminal,
            ),
        )
        index = end
    }
    return if (mergedAny) result else messages
}
