package me.rerere.ai.provider.providers.openai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [prepareToolTurnReasoningForReplay] / [foldRepeatedShortSegments] 的纯函数单测。
 * 目标：短文本不变、超长保留尾部、连续重复折叠、中英混合不误伤、空串/全空白原样。
 */
class ToolTurnReasoningGuardTest {

    // ---- 空串 / 全空白 ----

    @Test
    fun `empty string is returned as is`() {
        assertEquals("", prepareToolTurnReasoningForReplay(""))
        assertEquals("", foldRepeatedShortSegments(""))
    }

    @Test
    fun `whitespace only text is returned as is`() {
        val raw = "   \n \t \n   "
        assertEquals(raw, prepareToolTurnReasoningForReplay(raw))
        assertEquals(raw, foldRepeatedShortSegments(raw))
    }

    @Test
    fun `blank lines are not treated as repeated segments`() {
        val raw = "甲。\n\n\n乙。"
        assertEquals(raw, foldRepeatedShortSegments(raw))
    }

    // ---- 短文本不变 / 不足 3 次不折叠 ----

    @Test
    fun `short text without repeats is unchanged`() {
        val raw = "好的，我先读取文件再决定。"
        assertEquals(raw, prepareToolTurnReasoningForReplay(raw))
        assertEquals(raw, foldRepeatedShortSegments(raw))
    }

    @Test
    fun `two consecutive repeats are not folded`() {
        assertEquals("执行。执行。", foldRepeatedShortSegments("执行。执行。"))
        assertEquals("执行。\n执行。", foldRepeatedShortSegments("执行。\n执行。"))
    }

    // ---- 连续重复折叠 ----

    @Test
    fun `three consecutive identical sentences are folded with a marker`() {
        val folded = foldRepeatedShortSegments("好。好。好。")

        assertEquals("好。" + REPEATED_SEGMENT_FOLD_MARKER, folded)
    }

    @Test
    fun `a longer run of repeats is folded to a single occurrence`() {
        val raw = "好。".repeat(7)

        assertEquals("好。" + REPEATED_SEGMENT_FOLD_MARKER, foldRepeatedShortSegments(raw))
    }

    @Test
    fun `three consecutive identical lines are folded`() {
        val raw = "执行。\n执行。\n执行。\n继续。"

        assertEquals(
            "执行。" + REPEATED_SEGMENT_FOLD_MARKER + "\n继续。",
            foldRepeatedShortSegments(raw),
        )
    }

    // ---- 中英混合 ----

    @Test
    fun `mixed chinese and english repeated segments are folded`() {
        val raw = "继续。继续。继续。OK. OK. OK. 收尾。"

        assertEquals(
            "继续。" + REPEATED_SEGMENT_FOLD_MARKER +
                "OK. " + REPEATED_SEGMENT_FOLD_MARKER + "收尾。",
            foldRepeatedShortSegments(raw),
        )
    }

    @Test
    fun `mixed chinese and english content without repeats is untouched`() {
        val raw = "让我先检查配置，读取 config.json。然后汇总。"

        assertEquals(raw, foldRepeatedShortSegments(raw))
        assertEquals(raw, prepareToolTurnReasoningForReplay(raw))
    }

    // ---- 长片段 / 超长截尾（保留尾部） ----

    @Test
    fun `repeated segments longer than 24 chars are not folded`() {
        val raw = ("长片段".repeat(9) + "。").repeat(3)

        assertEquals(raw, foldRepeatedShortSegments(raw))
    }

    @Test
    fun `oversize text keeps the tail within the default limit`() {
        val raw = "H".repeat(2500) + "TAIL-END"

        val result = prepareToolTurnReasoningForReplay(raw)

        assertEquals(MAX_TOOL_TURN_REASONING_CHARS, result.length)
        assertTrue(result.endsWith("TAIL-END"))
        assertEquals("H".repeat(1992) + "TAIL-END", result)
    }

    @Test
    fun `custom limit keeps the tail`() {
        val raw = "A".repeat(80) + "END"

        val result = prepareToolTurnReasoningForReplay(raw, maxChars = 50)

        assertEquals("A".repeat(47) + "END", result)
    }

    @Test
    fun `repeats are folded before truncation`() {
        // 4000 字符的退化文本：折叠后应只剩 1 次出现 + 标记，远小于默认上限。
        val raw = "好。".repeat(2000)

        assertEquals("好。" + REPEATED_SEGMENT_FOLD_MARKER, prepareToolTurnReasoningForReplay(raw))
    }

    @Test
    fun `tail truncation does not split a surrogate pair`() {
        // 前缀 2 字符 + 1500 个 emoji(2 code units) + 尾字符 1 个 = 3003 code units，
        // takeLast(2000) 的起点恰好落在某个 surrogate pair 的 low 位上，必须丢弃半个字符。
        val raw = "AB" + "🀄".repeat(1500) + "Z"

        val result = prepareToolTurnReasoningForReplay(raw)

        assertEquals(1999, result.length)
        assertTrue(result.first().isHighSurrogate())
        assertFalse(result.first().isLowSurrogate())
        assertEquals('Z', result.last())
    }

    // ---- 句子切分边界 ----

    @Test
    fun `sentence splitter keeps decimals and version numbers intact`() {
        assertEquals(
            listOf("3.14 是圆周率。", "v1.2 已发布。"),
            splitIntoSentenceSegments("3.14 是圆周率。v1.2 已发布。"),
        )
    }
}
