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
 *
 * 注意：[prefix] 必须真的长过 [REPETITION_MIN_TEXT_CHARS]，否则会先被「短思考不判」挡掉，
 * 让正样本用例失去意义（第一版就踩了这个：前缀只有 90 字，两个正样本全挂在 assertNotNull）。
 */
class ReasoningRepetitionGuardTest {
    private val prefix = listOf(
        "先看日志，确认这次输出是被解析层拒掉的，还是被校验层拒掉的。",
        "再看请求体里 max_tokens 的取值与工具 schema 的实际体积是否异常。",
        "定位到具体行之后再动手改，改完必须补一条能区分正确与错误的单测。",
        "单测要能证明修复前后的行为差异，而不是只断言调用不抛异常。",
        "短回答里出现重复措辞是正常的，护栏不能把这种正常现象判成退化。",
        "真正危险的是同一短句连续刷屏，把输出预算全部烧在重复片段上。",
        "护栏只看尾部窗口，历史里出现过的重复交给回传侧的折叠逻辑处理。",
        "这段前缀故意写长，用来越过护栏的最小判定长度，本身不含任何重复。",
        "如果尾部已经恢复正常表述，就不应该再判退化，避免把抖动判成故障。",
        "判定阈值宁可保守一些，漏判的代价远小于误杀正常长文本的代价。",
        "会话越长，越需要一道能自己收尾的护栏，而不是等预算耗尽才结束。",
        "所有判据都要能用纯函数复现，不依赖网络、不依赖设备时间。",
    ).joinToString("")

    @Test
    fun `consecutive identical short sentences converge the turn`() {
        val raw = prefix + "好。".repeat(REPETITION_MIN_RUN)
        assertTrue(raw.length > REPETITION_MIN_TEXT_CHARS)

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
        // 前面先补一个换行，保证「整行相同」的那组真的凑满 REPETITION_MIN_LINE_RUN 行
        // （否则第一行会和前缀粘在一起，实际只有 N-1 行相同）。
        val raw = prefix + "\n" + (1..REPETITION_MIN_LINE_RUN).joinToString("\n") { line }
        assertTrue(raw.length > REPETITION_MIN_TEXT_CHARS)

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
