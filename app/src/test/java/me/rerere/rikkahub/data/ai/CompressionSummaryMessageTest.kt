package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5（263）：折叠历史用**摘要消息替代**，不再把摘要追加到尾部系统段。
 *
 * 判据两条：摘要消息位于消息流最前（历史最老的一端）、角色是 SYSTEM；
 * 没有摘要时消息列表必须原样返回，不能凭空多一条。
 */
class CompressionSummaryMessageTest {
    @Test
    fun `summary is prepended as a system message`() {
        val history = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("最近一条用户消息"))),
        )

        val result = withCompressionSummary(history, "前序对话摘要：讨论过压缩与折叠。")

        assertEquals(2, result.size)
        assertEquals(MessageRole.SYSTEM, result.first().role)
        val text = result.first().parts.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(text.contains("replaced by the summary"))
        assertTrue(text.contains("前序对话摘要：讨论过压缩与折叠。"))
        // 原历史必须原样跟在摘要后面（顺序不变）。
        assertSame(history.single(), result.last())
    }

    @Test
    fun `blank summary keeps the message list untouched`() {
        val history = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi"))),
        )

        assertSame(history, withCompressionSummary(history, null))
        assertSame(history, withCompressionSummary(history, "   "))
    }
}
