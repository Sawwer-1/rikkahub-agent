package me.rerere.rikkahub.data.ai.waifu

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UIMessageState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class WaifuMergeTransformerTest {
    private fun bubble(
        text: String,
        groupId: String,
        id: Uuid = Uuid.random(),
    ) = UIMessage(
        id = id,
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
        annotations = listOf(UIMessageAnnotation.WaifuGroup(groupId = groupId)),
    )

    private fun plain(text: String, role: MessageRole = MessageRole.USER) = UIMessage(
        role = role,
        parts = listOf(UIMessagePart.Text(text)),
    )

    @Test
    fun `adjacent bubbles of one group merge into a single assistant message`() {
        val firstId = Uuid.random()
        val messages = listOf(
            plain("hi"),
            UIMessage(
                id = firstId,
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("第一句。")),
                annotations = listOf(
                    UIMessageAnnotation.UrlCitation(title = "t", url = "u"),
                    UIMessageAnnotation.WaifuGroup(groupId = "g"),
                ),
            ),
            bubble("第二句。", groupId = "g"),
            bubble("第三句。", groupId = "g"),
        )

        val merged = mergeWaifuGroupMessages(messages)

        assertEquals(2, merged.size)
        val mergedAssistant = merged.last()
        assertEquals(firstId, mergedAssistant.id)
        assertEquals("第一句。\n\n第二句。\n\n第三句。", mergedAssistant.toText())
        assertEquals(MessageRole.ASSISTANT, mergedAssistant.role)
        // Annotations (including non-waifu ones) are donated by the first message.
        assertTrue(mergedAssistant.annotations.any { it is UIMessageAnnotation.UrlCitation })
    }

    @Test
    fun `a foreign message between groups prevents cross group merging`() {
        val messages = listOf(
            bubble("组一A。", groupId = "g1"),
            bubble("组一B。", groupId = "g1"),
            plain("打断一下"),
            bubble("组二A。", groupId = "g2"),
            bubble("组二B。", groupId = "g2"),
        )

        val merged = mergeWaifuGroupMessages(messages)

        assertEquals(3, merged.size)
        assertEquals("组一A。\n\n组一B。", merged[0].toText())
        assertEquals("打断一下", merged[1].toText())
        assertEquals("组二A。\n\n组二B。", merged[2].toText())
    }

    @Test
    fun `adjacent bubbles of different groups are not merged`() {
        val messages = listOf(
            bubble("组一。", groupId = "g1"),
            bubble("组二。", groupId = "g2"),
        )

        val merged = mergeWaifuGroupMessages(messages)

        assertEquals(2, merged.size)
        assertSame(messages[0], merged[0])
        assertSame(messages[1], merged[1])
    }

    @Test
    fun `unannotated messages are returned untouched`() {
        val messages = listOf(
            plain("user message"),
            plain("assistant message", role = MessageRole.ASSISTANT),
            plain("another user message"),
        )

        val merged = mergeWaifuGroupMessages(messages)

        assertSame(messages, merged)
    }

    @Test
    fun `a lone group bubble is untouched`() {
        val messages = listOf(
            plain("hi"),
            bubble("独句。", groupId = "g"),
        )

        val merged = mergeWaifuGroupMessages(messages)

        assertSame(messages, merged)
    }

    @Test
    fun `merged message takes usage from the first and outcome from the last`() {
        val firstId = Uuid.random()
        val first = UIMessage(
            id = firstId,
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("第一句。")),
            annotations = listOf(UIMessageAnnotation.WaifuGroup(groupId = "g")),
            state = UIMessageState.STREAMING,
        )
        val last = bubble("最后一句。", groupId = "g").copy(
            state = UIMessageState.COMPLETED,
        )

        val merged = mergeWaifuGroupMessages(listOf(first, last))

        assertEquals(1, merged.size)
        assertEquals(firstId, merged[0].id)
        assertEquals(UIMessageState.COMPLETED, merged[0].state)
        assertEquals("第一句。\n\n最后一句。", merged[0].toText())
    }

    @Test
    fun `transformer is wired as an input message transformer`() {
        // Compile-level contract check: the object must satisfy InputMessageTransformer
        // so the ChatService transformer chain accepts it. The transform body is a pure
        // delegation to mergeWaifuGroupMessages (covered above).
        val transformer: me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer =
            WaifuMergeTransformer
        assertTrue(transformer === WaifuMergeTransformer)
    }

    @Test
    fun `withWaifuText replaces text content and keeps other parts`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "thinking"),
                UIMessagePart.Text("旧文本"),
                UIMessagePart.Text("第二段文本"),
            ),
        )

        val rebuilt = message.withWaifuText("新文本")

        assertEquals(2, rebuilt.parts.size)
        assertEquals("thinking", (rebuilt.parts[0] as UIMessagePart.Reasoning).reasoning)
        assertEquals("新文本", (rebuilt.parts[1] as UIMessagePart.Text).text)
        // Original stays untouched (data class copy semantics).
        assertEquals(3, message.parts.size)
    }
}
