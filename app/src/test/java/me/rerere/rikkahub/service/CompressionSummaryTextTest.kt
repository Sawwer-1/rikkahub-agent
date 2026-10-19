package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工单 CB 2026-10-19 N1 / O2：折叠内容喂给摘要模型时，不能只喂文本。
 *
 * 旧行为：`summaryAsText()` 只取文本部分，纯工具轮变成 `"[ASSISTANT]: "` 空壳 →
 * 模型返回空摘要 → 折叠照做 → 「已折叠 9 条 · 摘要 0 字」，那批历史对模型等于凭空消失。
 */
class CompressionSummaryTextTest {
    @Test
    fun `tool only message still yields summarizable text`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "workspace_shell",
                    input = """{"command":"ls -la"}""",
                    output = listOf(UIMessagePart.Text("total 0\ndrwxr-xr-x  app")),
                ),
            ),
        )

        val text = compressionSummaryText(message)

        assertTrue(text.startsWith("[ASSISTANT]: "))
        assertTrue(text.contains("[tool] workspace_shell"))
        assertTrue(text.contains("input="))
        assertTrue(text.contains("output="))
        assertTrue(text.contains("total 0"))
    }

    @Test
    fun `message without text or tools is filtered out`() {
        val message = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("   ")),
        )

        assertEquals("", compressionSummaryText(message))
    }

    @Test
    fun `text and tool output are both kept and bounded`() {
        val longOutput = "x".repeat(2_000)
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("先跑一下测试。"),
                UIMessagePart.Tool(
                    toolCallId = "call-2",
                    toolName = "workspace_shell",
                    input = """{"command":"./gradlew test"}""",
                    output = listOf(UIMessagePart.Text(longOutput)),
                ),
            ),
        )

        val text = compressionSummaryText(message)

        assertTrue(text.contains("先跑一下测试。"))
        // 出参必须被截断，否则一个日志节点就能把摘要请求撑爆。
        assertTrue(text.length < 1_200)
    }
}
