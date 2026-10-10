package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Per-conversation multi-member ("group chat") configuration, stored in Settings/DataStore.
 *
 * Keyed by the group conversation's id — no Room schema change: the conversation keeps its
 * normal assistantId (its assistant becomes the group host and stops generating directly;
 * a planner picks member speakers and each member answers as themselves). Zero-migration
 * convention follows [Settings.subAgents]: the Settings field MUST default to an empty list
 * so installs predating this field decode cleanly.
 */
@Serializable
data class GroupChatConfig(
    val conversationId: Uuid,
    /** Members who may speak in the group, in roster order. Host assistant is implicit. */
    val memberAssistantIds: List<Uuid> = emptyList(),
    /** When false the user message is answered by the first member directly, no planner round. */
    val plannerEnabled: Boolean = true,
    /** Overrides the planner's model; null falls back to the host assistant's chat model. */
    val plannerModelId: Uuid? = null,
    /** Max member replies per planning round, safety cap on token amplification. */
    val maxSpeakersPerTurn: Int = 3,
    /**
     * Max planning rounds per user turn. 1 = single round (legacy behaviour); >=2 lets the
     * planner schedule later rounds where members respond to each other's replies.
     * Hard-capped at 4 by the engine; exposed in the group-chat config dialog.
     */
    val maxRoundsPerTurn: Int = 2,
)
