package me.rerere.rikkahub.data.model

import kotlin.uuid.Uuid

@JvmInline
value class MemoryScope private constructor(val ownerId: String) {
    companion object {
        val Global = MemoryScope("__global__")

        fun assistant(assistantId: Uuid): MemoryScope = MemoryScope(assistantId.toString())

        /** Conversation keys are prefixed so they can never collide with legacy assistant UUID keys. */
        fun conversation(conversationId: Uuid): MemoryScope =
            MemoryScope("conversation:$conversationId")

        /** Key prefix of the conversation-level scope; see [conversation]. */
        const val CONVERSATION_PREFIX = "conversation:"

        /**
         * Returns the scope for a persisted `conversation:<uuid>` key, or null when [key] is not a
         * well-formed conversation scope key. Used by the storage/worker layers to recognise and
         * exclude the short-lived conversation isolation layer.
         */
        fun conversationKeyOrNull(key: String): MemoryScope? {
            if (!key.startsWith(CONVERSATION_PREFIX)) return null
            val raw = key.substring(CONVERSATION_PREFIX.length)
            val parsed = runCatching { Uuid.parse(raw) }.getOrNull() ?: return null
            return if (parsed.toString() == raw) {
                MemoryScope(key)
            } else {
                null
            }
        }
    }
}

val Assistant.memoryScope: MemoryScope
    get() = memoryScope(conversationId = null)

fun Assistant.memoryScope(conversationId: Uuid?): MemoryScope = when {
    useConversationMemory && conversationId != null -> MemoryScope.conversation(conversationId)
    useGlobalMemory -> MemoryScope.Global
    else -> MemoryScope.assistant(id)
}
