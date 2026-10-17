package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MessageNode.abandonUnresolvedToolsIfNotResumable] 的单测（2026-10-17 B01 / R25）。
 *
 * 246 的 5 例只覆盖「单版本消息」；真机验收 P0-1b 暴露的是**多版本**场景（用户编辑过 →
 * 消息下方出现 2/2）。必须只动**当前激活版本**，否则会把用户旧版本的记录一起改掉。
 */
class MessageNodeAbandonTest {

    private fun tool(
        callId: String,
        state: ToolApprovalState = ToolApprovalState.Pending,
        output: List<UIMessagePart> = emptyList(),
    ) = UIMessagePart.Tool(
        toolCallId = callId,
        toolName = "shell",
        input = """{"command":"ls"}""",
        output = output,
        approvalState = state,
    )

    @Test
    fun `only the active version is abandoned when the node has multiple versions`() {
        val oldVersion = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("旧版本正文"), tool("call-old")),
        )
        val newVersion = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("新版本正文"), tool("call-new")),
        )
        // 用户编辑成新版本 → 消息下方 2/2，selectIndex 指向第 2 个
        val node = MessageNode(messages = listOf(oldVersion, newVersion), selectIndex = 1)

        val updated = node.abandonUnresolvedToolsIfNotResumable()

        assertNotSame(node, updated)
        // 旧版本一字不改（连它那个未执行工具都不动）
        assertEquals(oldVersion, updated.messages[0])
        // 新版本（当前激活版本）的未完成工具被就地放弃，正文保留
        val abandonedTool = updated.messages[1].getTools().single()
        assertTrue("active version tool must look executed", abandonedTool.isExecuted)
        assertTrue(
            "active version must use the abandoned receipt",
            (abandonedTool.approvalState is ToolApprovalState.Denied),
        )
        assertEquals("新版本正文", (updated.messages[1].parts.first() as UIMessagePart.Text).text)
        // 选中项不变
        assertEquals(1, updated.selectIndex)
    }

    @Test
    fun `resumable tool keeps the node untouched`() {
        // Approved = 可续跑（等用户/等待恢复），不能当成"被放弃"。
        val node = MessageNode(
            messages = listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(tool("call-approved", ToolApprovalState.Approved)),
                ),
            ),
            selectIndex = 0,
        )

        assertSame(node, node.abandonUnresolvedToolsIfNotResumable())
    }

    @Test
    fun `node without unresolved tool returns the same instance`() {
        val done = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                tool(
                    callId = "call-done",
                    state = ToolApprovalState.Approved,
                    output = listOf(UIMessagePart.Text("""{"status":"ok"}""")),
                ),
            ),
        )
        val plain = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi")))

        // 同一实例才能让调用方跳过整图回写
        val node = MessageNode(messages = listOf(done), selectIndex = 0)
        assertSame(node, node.abandonUnresolvedToolsIfNotResumable())
        val plainNode = MessageNode(messages = listOf(plain), selectIndex = 0)
        assertSame(plainNode, plainNode.abandonUnresolvedToolsIfNotResumable())
    }
}
