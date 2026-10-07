package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.chat.CommandOutcome
import me.rerere.rikkahub.service.chat.SubmitResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * 暂停/编辑炉（ExTV 照抄方案）的纯函数契约：
 * - editMessage 的终局映射必须有界且不崩（超时 → 用户可见拒绝）。
 * - 空 preset 条目在出生点过滤，非空 preset 完整保留。
 */
class MessageEditAndPresetContractTest {

    @Test
    fun `edit outcome await is bounded at fifteen seconds`() {
        assertEquals(15.seconds, EDIT_MESSAGE_OUTCOME_TIMEOUT)
    }

    @Test
    fun `edit outcome timeout folds into a visible rejection instead of crashing`() {
        val accepted = SubmitResult.Accepted(Uuid.random())

        // 超时（outcome 为 null）：拒绝而非 error() 崩溃。
        val timeout = messageEditSubmissionResult(accepted, null)
        assertTrue(timeout is SubmitResult.Rejected)

        // 非 Completed 终局：折叠为用户可见拒绝。
        val conflicted = messageEditSubmissionResult(accepted, CommandOutcome.Conflict("Message target missing"))
        assertTrue(conflicted is SubmitResult.Rejected)

        val failed = messageEditSubmissionResult(accepted, CommandOutcome.Failed(IllegalStateException("boom")))
        assertTrue(failed is SubmitResult.Rejected)

        // Completed：透传原始 submission。
        assertEquals(accepted, messageEditSubmissionResult(accepted, CommandOutcome.Completed))
    }

    @Test
    fun `empty preset entries are filtered while non-empty presets are preserved`() {
        val emptyText = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("")))
        val blankText = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("   \n\t")))
        val keepUser = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("你好")))
        val keepAssistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("欢迎回来，主公")),
        )

        // 空文本条目（手写空行）不入会话，非空条目顺序与内容原样保留。
        assertEquals(
            listOf(keepUser, keepAssistant),
            effectivePresetMessages(listOf(emptyText, keepUser, blankText, keepAssistant)),
        )

        // 全空 preset 过滤后为空列表（新会话不再出现只剩操作行的空消息）。
        assertEquals(emptyList<UIMessage>(), effectivePresetMessages(listOf(emptyText, blankText)))

        // 全非空 preset 完整保留。
        assertEquals(
            listOf(keepUser, keepAssistant),
            effectivePresetMessages(listOf(keepUser, keepAssistant)),
        )
    }
}
