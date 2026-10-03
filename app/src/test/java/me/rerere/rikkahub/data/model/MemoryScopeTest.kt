package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class MemoryScopeTest {
    @Test
    fun assistantMemoryIsIsolatedByDefault() {
        val assistantId = Uuid.random()
        val assistant = Assistant(
            id = assistantId,
            useGlobalMemory = false,
        )

        assertEquals(assistantId.toString(), assistant.memoryScope.ownerId)
    }

    @Test
    fun globalMemoryIsUsedOnlyWhenExplicitlyEnabled() {
        val assistant = Assistant(useGlobalMemory = true)

        assertEquals(MemoryScope.Global, assistant.memoryScope)
        assertEquals("__global__", assistant.memoryScope.ownerId)
    }

    // Port additions below: the two tests above are verbatim from jude's MemoryScopeTest; the
    // conversation-level scope resolution is exercised here as well because this repo resolves
    // the chat-time scope in ChatService (main-engineer wiring).

    @Test
    fun conversationMemoryRequiresConversationId() {
        val assistantId = Uuid.random()
        val conversationId = Uuid.random()
        val assistant = Assistant(
            id = assistantId,
            useGlobalMemory = false,
            useConversationMemory = true,
        )

        assertEquals(
            "conversation:$conversationId",
            assistant.memoryScope(conversationId).ownerId,
        )
        // Without a conversation context (assistant-level pages) the scope falls back to the
        // assistant isolation instead of silently sharing a conversation bucket.
        assertEquals(assistantId.toString(), assistant.memoryScope(null).ownerId)
    }

    @Test
    fun conversationMemoryTakesPrecedenceOverGlobalMemory() {
        val conversationId = Uuid.random()
        val assistant = Assistant(
            useGlobalMemory = true,
            useConversationMemory = true,
        )

        assertEquals(
            "conversation:$conversationId",
            assistant.memoryScope(conversationId).ownerId,
        )
        assertEquals(
            MemoryScope.Global,
            assistant.memoryScope(null),
        )
    }

    @Test
    fun conversationScopeKeysRoundTripOnlyInCanonicalForm() {
        val conversationId = Uuid.random()
        val key = "conversation:$conversationId"

        // Jude serialization parity: conversation:<uuid> with the canonical UUID text.
        assertEquals(key, MemoryScope.conversation(conversationId).ownerId)
        assertEquals(key, MemoryScope.conversationKeyOrNull(key)?.ownerId)
        assertNull(MemoryScope.conversationKeyOrNull(conversationId.toString()))
        assertNull(MemoryScope.conversationKeyOrNull("conversation:not-a-uuid"))
        assertNull(MemoryScope.conversationKeyOrNull("CONVERSATION:$conversationId"))
        assertNull(MemoryScope.conversationKeyOrNull("conversation:"))
    }
}
