package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart

/**
 * Provider-facing projection of the persisted conversation.
 *
 * Steering messages remain in the stored/UI history for auditability, but they are control
 * messages rather than unanswered user turns. Persistent notes are therefore lifted into one
 * system addendum while transient notes are omitted from later provider calls.
 */
data class PersistentSteeringContext(
    val messages: List<UIMessage>,
    val systemAddendum: String?,
)

fun preparePersistentSteeringContext(messages: List<UIMessage>): PersistentSteeringContext {
    val seenCommandIds = mutableSetOf<String>()
    val persistentGuidance = mutableListOf<String>()
    val providerMessages = buildList {
        messages.forEach { message ->
            val steering = message.annotations.filterIsInstance<UIMessageAnnotation.Steering>()
            if (steering.isEmpty()) {
                add(message)
                return@forEach
            }

            steering.forEach { annotation ->
                if (annotation.persistent && seenCommandIds.add(annotation.commandId)) {
                    message.textParts()
                        .takeIf(String::isNotBlank)
                        ?.let(persistentGuidance::add)
                }
            }
        }
    }

    val addendum = persistentGuidance
        .takeIf { it.isNotEmpty() }
        ?.joinToString(
            separator = "\n- ",
            prefix = "Persistent user guidance from earlier in this conversation:\n- ",
        )
    return PersistentSteeringContext(providerMessages, addendum)
}

/** Builds the ephemeral trailing user turn used at a live steering checkpoint. */
fun buildSteeringUserGuidanceMessage(deliveries: List<SteeringDelivery>): UIMessage? {
    if (deliveries.isEmpty()) return null
    val guidance = deliveries.joinToString("\n") { "- ${it.note.text.trim()}" }
    return UIMessage(
        role = MessageRole.USER,
        parts = listOf(
            UIMessagePart.Text(
                """
                [User guidance received while this run is active]
                $guidance

                Apply this guidance now. Stop any old-plan steps that have not started yet
                （停止尚未开始的旧计划步骤）, preserve already completed work, and continue from
                the current verified state.
                """.trimIndent(),
            )
        ),
    )
}

/**
 * A resumable tool batch belongs to the provider's previous turn. It must finish before a
 * NEXT_MODEL_CALL steering note is consumed, otherwise the note could disappear without ever
 * reaching a model call.
 */
fun takeSteeringForProviderCheckpoint(
    runControl: GenerationRunControl,
    modelCallIndex: Int,
    hasResumableTools: Boolean,
): List<SteeringDelivery> = if (hasResumableTools) {
    emptyList()
} else {
    runControl.takeSteeringForCheckpoint(modelCallIndex)
}

fun buildSteeringSystemAddendum(deliveries: List<SteeringDelivery>): String? = deliveries
    .takeIf { it.isNotEmpty() }
    ?.joinToString("\n") { "User guidance for this run: ${it.note.text.trim()}" }

/** Converts an abandoned old-plan tool into one stable, provider-readable result. */
fun UIMessagePart.Tool.skippedDueToGuidance(): UIMessagePart.Tool {
    if (isExecuted) return this
    return copy(
        output = listOf(
            // The `code` stays machine-stable; `message` is the plain-language explanation a
            // user (and the model) can read: the plan was replaced by the new message, so this
            // step never ran. 2026-10-12: was an English-only sentence that showed up as a
            // silent, unexplained failure in the tool card.
            UIMessagePart.Text(
                """{"ok":false,"code":"skipped_due_to_guidance","message":"你在我执行过程中发了新消息，这轮计划已被替换，这一步没有执行。需要的话我可以按新要求重来。"}"""
            )
        )
    )
}

private fun UIMessage.textParts(): String = parts
    .filterIsInstance<UIMessagePart.Text>()
    .joinToString("\n") { it.text.trim() }
    .trim()
