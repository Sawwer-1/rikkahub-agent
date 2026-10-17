package me.rerere.rikkahub.service.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteeringSubmissionAuditTest {
    @Test
    fun `queue promotion outranks the borrowed app origin`() {
        // 队列升格沿用入队时的 origin（多为 APP_UI）：只看 origin 会把它误记成"输入框追加"，
        // 而这两条链路在 246/247 的排查里正是要区分开的。
        assertEquals(
            STEERING_SOURCE_QUEUE_PROMOTION,
            steeringAuditTarget(CommandOrigin.APP_UI, promotedFromQueue = true),
        )
        assertEquals(
            STEERING_SOURCE_QUEUE_PROMOTION,
            steeringAuditTarget(CommandOrigin.WEB_API, promotedFromQueue = true),
        )
    }

    @Test
    fun `direct submissions keep their own source label`() {
        assertEquals("app_ui", steeringAuditTarget(CommandOrigin.APP_UI, promotedFromQueue = false))
        assertEquals("web_api", steeringAuditTarget(CommandOrigin.WEB_API, promotedFromQueue = false))
        assertEquals("telegram", steeringAuditTarget(CommandOrigin.TELEGRAM, promotedFromQueue = false))
        // 其余来源统一走枚举名小写，保证审计里不会出现空标签。
        assertEquals("cron", steeringAuditTarget(CommandOrigin.CRON, promotedFromQueue = false))
        assertEquals(
            "system_assistant_keyguard",
            steeringAuditTarget(CommandOrigin.SYSTEM_ASSISTANT_KEYGUARD, promotedFromQueue = false),
        )
    }

    @Test
    fun `detail carries metadata only and omits unknown length`() {
        val detail = steeringAuditDetail(
            scope = SteeringScope.REMAINDER_OF_RUN,
            historyMode = SteeringHistoryMode.TRANSIENT,
            textLength = 42,
        )
        assertEquals("scope=REMAINDER_OF_RUN history=TRANSIENT chars=42", detail)

        // 队列升格在提交点看不到正文：不写字数，也不要写"0 字"这种假数据。
        val promoted = steeringAuditDetail(
            scope = SteeringScope.NEXT_MODEL_CALL,
            historyMode = SteeringHistoryMode.PERSISTENT,
            textLength = null,
        )
        assertEquals("scope=NEXT_MODEL_CALL history=PERSISTENT", promoted)
        assertFalse(promoted.contains("chars="))

        // 负数字数只会来自调用方错误，审计里按 0 收敛，不抛异常。
        assertTrue(
            steeringAuditDetail(SteeringScope.REMAINDER_OF_RUN, SteeringHistoryMode.TRANSIENT, -5)
                .endsWith("chars=0"),
        )
    }
}
