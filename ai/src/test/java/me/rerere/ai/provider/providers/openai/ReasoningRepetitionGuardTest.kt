package me.rerere.ai.provider.providers.openai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B04 / R6：思考通道重复退化的检测判据。
 *
 * 这些用例锁的是「宁可漏判、不可误杀」：正常长列表、正常重复句式都不能触发；
 * 只有同一短句连续刷屏或同一整行连续刷屏才收敛。
 */
class ReasoningRepetitionGuardTest {
    private val prefix = buildString {
        append("先看日志，确认是解析层还是校验层拒的；再看请求体里 max_tokens 与工具 schema 的大小。")
        append("定位到行之后再改，改完要有单测，单测要能区分正确与错误。")
        append("这一段是正常内容，长度足够越过护栏的最小判定长度，不参与重复计数。")
    }

    @Test
    fun `consecutive identical short sentences converge the turn`() {
        val raw = prefix + "好。".repeat(REPETITION_MIN_RUN)

        val verdict = detectReasoningRepetition(raw)
        assertNotNull(verdict)

        assertEquals(REPETITION_MIN_RUN, verdict!!.repeatedRun)
        assertTrue(verdict.reason.startsWith("segment:"))
        // 切点落在重复段的第一次出现处：之前的内容不含任何一段「好。」。
        assertTrue(verdict.cutIndex <= raw.length)
        assertTrue(raw.substring(0, verdict.cutIndex).count { it == '好' } == 0)
    }

    @Test
    fun `one repetition below the threshold does not fire`() {
        val raw = prefix + "好。".repeat(REPETITION_MIN_RUN - 1)
        assertNull(detectReasoningRepetition(raw))
    }

    @Test
    fun `short reasoning is never judged`() {
        // 长度不足 400：哪怕全在重复，也不参与（短回答里出现重复措辞是正常的）。
        val raw = "好。".repeat(REPETITION_MIN_RUN + 5)
        assertTrue(raw.length < REPETITION_MIN_TEXT_CHARS)
        assertNull(detectReasoningRepetition(raw))
    }

    @Test
    fun `legit distinct sentences never fire`() {
        val raw = prefix + (1..80).joinToString("") { "第${it}步：核对第${it}项的输入与输出。\n" }
        assertNull(detectReasoningRepetition(raw))
    }

    @Test
    fun `consecutive identical long lines converge the turn`() {
        // 单行超过句级上限（24 字），句级规则不参与；行级规则兜住这种无标点刷屏。
        val line = "这一行是很长的重复说明文字，超过二十四个字符的限制。"
        assertTrue(line.length > REPETITION_SEGMENT_MAX_CHARS)
        val raw = prefix + (1..REPETITION_MIN_LINE_RUN).joinToString("\n") { line }

        val verdict = detectReasoningRepetition(raw)

        assertNotNull(verdict)
        assertTrue(verdict!!.reason.startsWith("line:"))
        assertEquals(REPETITION_MIN_LINE_RUN, verdict.repeatedRun)
    }

    @Test
    fun `repetition in the middle of a long tail is not fatal when the tail recovered`() {
        // 尾部已经恢复正常（最后一段不重复）→ 不触发：护栏只看尾部，历史里的重复交给回传折叠处理。
        val raw = prefix + "好。".repeat(REPETITION_MIN_RUN) + "\n现在给出结论：先改 A，再改 B。"
        assertNull(detectReasoningRepetition(raw))
    }
}
