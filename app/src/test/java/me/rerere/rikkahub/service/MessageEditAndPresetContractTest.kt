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
 * - 空 preset 条目在出生点过滤，非空 preset 内容保留但消息 id 必须换新。
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
    fun `empty preset entries are filtered while surviving presets get fresh message ids`() {
        val emptyText = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("")))
        val blankText = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("   \n\t")))
        val keepUser = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("你好")))
        val keepAssistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("欢迎回来，主公")),
        )

        val materialized = effectivePresetMessages(listOf(emptyText, keepUser, blankText, keepAssistant))

        // 空文本条目（手写空行）不入会话；非空条目顺序、角色与内容原样保留。
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), materialized.map { it.role })
        assertEquals(listOf(keepUser.parts, keepAssistant.parts), materialized.map { it.parts })

        // preset 的 id 会随助手设置长期保留（JsonInstant 是 encodeDefaults = true），因此物化
        // 进新会话必须换新 id：否则同一助手 scope 下 (scope, message_id) 主键会让新会话命中
        // 旧会话（含已删除会话的残行）并崩进程（2026-10-13 识之律者闪退）。
        val presetIds = setOf(keepUser.id, keepAssistant.id)
        assertTrue(materialized.none { it.id in presetIds })
        assertEquals(materialized.size, materialized.map { it.id }.toSet().size)

        // 同一份 preset 连续物化两次（同一助手的两个会话）不得共用任何 id。
        val second = effectivePresetMessages(listOf(keepUser, keepAssistant))
        assertTrue(materialized.map { it.id }.none { id -> second.any { it.id == id } })

        // 全空 preset 过滤后为空列表（新会话不再出现只剩操作行的空消息）。
        assertEquals(emptyList<UIMessage>(), effectivePresetMessages(listOf(emptyText, blankText)))
    }
}
