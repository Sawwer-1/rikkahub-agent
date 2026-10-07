package me.rerere.rikkahub.data.ai.group

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupProjectionTest {

    private val alice = Uuid.random()
    private val bob = Uuid.random()
    private val names = mapOf(alice to "Alice", bob to "Bob")

    private fun memberMessage(memberId: Uuid, name: String, text: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(me.rerere.ai.ui.UIMessagePart.Text(text)),
        annotations = listOf(
            UIMessageAnnotation.GroupMember(memberAssistantId = memberId, displayName = name),
        ),
    )

    @Test
    fun `own member replies stay assistant turns`() {
        val messages = listOf(
            UIMessage.user("hello"),
            memberMessage(alice, "Alice", "hi there"),
        )
        val projected = projectMessagesForMember(messages, alice, names, "Host")
        assertEquals(MessageRole.USER, projected[0].role)
        assertEquals(MessageRole.ASSISTANT, projected[1].role)
        assertEquals("hi there", projected[1].toText())
    }

    @Test
    fun `other member replies become from-prefixed user turns`() {
        val messages = listOf(
            UIMessage.user("hello"),
            memberMessage(bob, "Bob", "hey!"),
        )
        val projected = projectMessagesForMember(messages, alice, names, "Host")
        // The live user message and the projected member turn are consecutive USER turns,
        // so by design they merge into one alternation-clean user turn.
        assertEquals(1, projected.size)
        assertEquals(MessageRole.USER, projected[0].role)
        assertTrue(projected[0].toText().contains("hello"))
        assertTrue(projected[0].toText().contains("[From Bob]: hey!"))
    }

    @Test
    fun `unannotated assistant messages use the fallback name`() {
        val messages = listOf(
            UIMessage.assistant("legacy host reply"),
        )
        val projected = projectMessagesForMember(messages, alice, names, "Host")
        assertEquals(MessageRole.USER, projected[0].role)
        assertEquals("[From Host]: legacy host reply", projected[0].toText())
    }

    @Test
    fun `consecutive user turns merge into one`() {
        val messages = listOf(
            UIMessage.user("first"),
            memberMessage(bob, "Bob", "reply one"),
            memberMessage(bob, "Bob", "reply two"),
            memberMessage(alice, "Alice", "own reply"),
        )
        val projected = projectMessagesForMember(messages, alice, names, "Host")
        // user(first) + both [From Bob] turns are all consecutive USER turns and merge
        // into one; the own assistant reply stays a separate assistant turn.
        assertEquals(2, projected.size)
        val merged = projected[0]
        assertEquals(MessageRole.USER, merged.role)
        assertTrue(merged.toText().contains("first"))
        assertTrue(merged.toText().contains("[From Bob]: reply one"))
        assertTrue(merged.toText().contains("reply two"))
        assertEquals(MessageRole.ASSISTANT, projected[1].role)
    }

    @Test
    fun `blank other-member messages are dropped`() {
        val blank = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(me.rerere.ai.ui.UIMessagePart.Text("   ")),
            annotations = listOf(
                UIMessageAnnotation.GroupMember(memberAssistantId = bob, displayName = "Bob"),
            ),
        )
        val projected = projectMessagesForMember(listOf(UIMessage.user("hi"), blank), alice, names, "Host")
        assertEquals(1, projected.size)
    }
}
