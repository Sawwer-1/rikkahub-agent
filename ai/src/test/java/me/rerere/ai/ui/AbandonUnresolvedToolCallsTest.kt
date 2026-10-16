package me.rerere.ai.ui

import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UIMessage.abandonUnresolvedToolCalls] 的单测。
 *
 * 目的（工单 CB 问题三：取消生成丢思考链 / 历史）：停止生成后残留的未完成工具必须**就地放弃**，
 * 而不是让调用方把整条消息删掉——消息里的思考链与正文要原样留下。
 * 边界：已执行、已终止（Denied/Answered）的工具不得被改写；无可放弃工具时必须返回**同一实例**，
 * 好让调用方据此跳过整图回写。
 */
class AbandonUnresolvedToolCallsTest {

    private fun abortableTool(
        approvalState: ToolApprovalState = ToolApprovalState.Pending,
        executionStartedAt: Long? = null,
        output: List<UIMessagePart> = emptyList(),
    ) = UIMessagePart.Tool(
        toolCallId = "call-1",
        toolName = "shell",
        input = """{"command":"ls"}""",
        output = output,
        approvalState = approvalState,
        executionStartedAt = executionStartedAt,
    )

    @Test
    fun `pending tool is abandoned in place and keeps reasoning and text`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "先看一下目录"),
                UIMessagePart.Text("我来执行命令"),
                abortableTool(),
            ),
        )

        val abandoned = message.abandonUnresolvedToolCalls()

        assertNotSame(message, abandoned)
        // 思考链与正文原样保留（这正是取消后不能再丢的东西）。
        assertEquals(message.parts[0], abandoned.parts[0])
        assertEquals(message.parts[1], abandoned.parts[1])
        val tool = abandoned.getTools().single()
        assertTrue("abandoned tool must look executed", tool.isExecuted)
        assertFalse("abandoned tool must not stay resumable", tool.canResumeExecution)
        assertTrue(tool.approvalState is ToolApprovalState.Denied)
        assertEquals(
            ABANDONED_TOOL_CALL_OUTPUT,
            (tool.output.single() as UIMessagePart.Text).text,
        )
    }

    @Test
    fun `auto and interrupted-approved tools are abandoned too`() {
        val auto = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(abortableTool(approvalState = ToolApprovalState.Auto)),
        )
        val interruptedApproved = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                abortableTool(
                    approvalState = ToolApprovalState.Approved,
                    executionStartedAt = 1_760_000_000_000L,
                ),
            ),
        )

        assertTrue(auto.abandonUnresolvedToolCalls().getTools().single().isExecuted)
        assertTrue(interruptedApproved.abandonUnresolvedToolCalls().getTools().single().isExecuted)
    }

    @Test
    fun `already executed tool is left untouched`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                abortableTool(
                    approvalState = ToolApprovalState.Approved,
                    output = listOf(UIMessagePart.Text("""{"status":"ok"}""")),
                ),
            ),
        )

        assertSame(message, message.abandonUnresolvedToolCalls())
    }

    @Test
    fun `terminated tool states keep their own reason`() {
        val hardline = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(abortableTool(approvalState = ToolApprovalState.Denied("hardline blocked"))),
        )
        val answered = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(abortableTool(approvalState = ToolApprovalState.Answered("""{"a":"b"}"""))),
        )

        assertSame(hardline, hardline.abandonUnresolvedToolCalls())
        assertSame(answered, answered.abandonUnresolvedToolCalls())
        assertEquals(
            "hardline blocked",
            (hardline.getTools().single().approvalState as ToolApprovalState.Denied).reason,
        )
    }

    @Test
    fun `message without abandonable tool returns the same instance`() {
        val plain = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi")))

        assertSame(plain, plain.abandonUnresolvedToolCalls())
    }
}
