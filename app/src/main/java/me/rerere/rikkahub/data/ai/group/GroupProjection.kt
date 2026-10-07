package me.rerere.rikkahub.data.ai.group

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/** Annotation accessor: which group member spoke this assistant message, if any. */
fun UIMessage.groupMemberOrNull(): UIMessageAnnotation.GroupMember? =
    annotations.filterIsInstance<UIMessageAnnotation.GroupMember>().firstOrNull()

/**
 * Request-side identity projection for group chats (handover §三.B): the provider-visible
 * history must keep the shape of a normal one-assistant chat, so messages from every OTHER
 * group member are rewritten into "[From X]: ..." USER turns while the speaking member's own
 * replies stay assistant messages. Consecutive user turns (real user + projected members)
 * are merged so providers never see two USER messages back to back.
 *
 * Pure function, unit tested: no repository or Android dependency.
 *
 * @param messages raw generation history (already through messagesForGeneration, so
 *   compression summary handling is unchanged upstream)
 * @param selfMemberId the member who is about to speak
 * @param memberNames display names by assistant id, for the "[From X]" prefix
 * @param fallbackAssistantName name used for assistant messages that carry no member
 *   annotation (history written before the conversation became a group, or host artifacts)
 */
fun projectMessagesForMember(
    messages: List<UIMessage>,
    selfMemberId: Uuid,
    memberNames: Map<Uuid, String>,
    fallbackAssistantName: String,
): List<UIMessage> {
    val projected = mutableListOf<UIMessage>()
    for (message in messages) {
        when (message.role) {
            MessageRole.ASSISTANT -> {
                val member = message.groupMemberOrNull()
                if (member != null && member.memberAssistantId == selfMemberId) {
                    projected.add(message)
                } else {
                    val name = member?.displayName
                        ?: fallbackAssistantName.ifBlank { "assistant" }
                    val text = message.parts.asProjectedText().ifBlank { continue }
                    projected.add(
                        UIMessage.user(prompt = "[From $name]: $text"),
                    )
                }
            }

            MessageRole.USER -> projected.add(message)

            else -> projected.add(message)
        }
    }
    // Merge consecutive USER messages into a single turn: two members may reply back to
    // back, and a projected turn can also directly follow the real user's message. This
    // mirrors the waifu request-side merge and keeps providers on the plain alternation.
    val merged = mutableListOf<UIMessage>()
    for (message in projected) {
        val last = merged.lastOrNull()
        if (message.role == MessageRole.USER && last != null && last.role == MessageRole.USER) {
            merged[merged.lastIndex] = last.copy(
                parts = last.parts + UIMessagePart.Text(
                    "\n\n" + message.parts.filterIsInstance<UIMessagePart.Text>()
                        .joinToString("\n") { it.text },
                ),
            )
        } else {
            merged.add(message)
        }
    }
    return merged
}

private fun List<UIMessagePart>.asProjectedText(): String =
    filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }.trim()
