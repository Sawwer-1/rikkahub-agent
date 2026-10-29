package me.rerere.rikkahub.data.ai.tools

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 工单 T-10 方案 B 的契约：后台任务只能拿到「指定会话 + 当天 + 片段」的摘要。
 *
 * [buildDayDigest] 是纯函数，负责把窗口收在本地当天 00:00 之后，并只保留最后 N 条；
 * 它不依赖 Android Context，因此可在此直接覆盖边界。
 */
class TrustedWorkflowConversationDigestTest {

    private fun message(at: LocalDateTime, text: String, role: MessageRole = MessageRole.USER) =
        UIMessage(
            id = Uuid.random(),
            role = role,
            parts = listOf(UIMessagePart.Text(text)),
            createdAt = at,
        )

    @Test
    fun `messages before local midnight are excluded and today's are kept`() {
        val since = LocalDateTime.parse("2026-10-29T00:00")
        val yesterday = message(LocalDateTime.parse("2026-10-28T23:59"), "昨晚的回复")
        val todayEarly = message(LocalDateTime.parse("2026-10-29T00:01"), "今天凌晨")
        val todayNoon = message(LocalDateTime.parse("2026-10-29T12:00"), "今天中午")

        val digest = buildDayDigest(listOf(yesterday, todayEarly, todayNoon), since, limit = 50)

        assertEquals(listOf("今天凌晨", "今天中午"), digest.map { (it.parts.single() as UIMessagePart.Text).text })
    }

    @Test
    fun `only the last limit entries are returned`() {
        val since = LocalDateTime.parse("2026-10-29T00:00")
        val all = (0 until 10).map { index ->
            message(LocalDateTime.parse("2026-10-29T08:0${index}"), "m$index")
        }

        val digest = buildDayDigest(all, since, limit = 3)

        assertEquals(listOf("m7", "m8", "m9"), digest.map { (it.parts.single() as UIMessagePart.Text).text })
    }

    @Test
    fun `empty window yields no digest`() {
        val since = LocalDateTime.parse("2026-10-29T00:00")
        val yesterday = message(LocalDateTime.parse("2026-10-28T23:59"), "昨晚")

        assertEquals(emptyList<UIMessage>(), buildDayDigest(listOf(yesterday), since, limit = 50))
    }

    @Test
    fun `start of today is local midnight`() {
        val today = LocalDate.parse("2026-10-29")

        assertEquals(LocalDateTime.parse("2026-10-29T00:00"), startOfTodayLocal(today))
    }
}
