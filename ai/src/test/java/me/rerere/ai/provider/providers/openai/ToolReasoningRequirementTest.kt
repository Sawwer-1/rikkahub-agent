package me.rerere.ai.provider.providers.openai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [requiresToolReasoningContent] 的单测（工单 CB · 思考链降智 · 待确认项 4）。
 *
 * 判据必须只看「deepseek + v4」这个家族特征：大小写与分隔符（`-` / `.` / `_` / 空格）不该改变行为，
 * 否则同一上游仅因模型名拼写不同（`DeepSeek-V4-Flash` vs `deepseek-v4.1-flash`）就会让
 * reasoning_effort 写法与工具轮 reasoning 回传口径时开时关。
 */
class ToolReasoningRequirementTest {

    @Test
    fun `deepseek v4 family is matched regardless of separators and case`() {
        assertTrue(requiresToolReasoningContent("deepseek-v4-flash"))
        assertTrue(requiresToolReasoningContent("DeepSeek-V4-Flash"))
        assertTrue(requiresToolReasoningContent("deepseek-v4.1-flash"))
        assertTrue(requiresToolReasoningContent("deepseek_v4_1_flash"))
        assertTrue(requiresToolReasoningContent("deepseek v4 flash"))
        assertTrue(requiresToolReasoningContent("deepseek-ai/DeepSeek-V4-Flash"))
    }

    @Test
    fun `other models are not matched`() {
        assertFalse(requiresToolReasoningContent("deepseek-chat"))
        assertFalse(requiresToolReasoningContent("deepseek-v3.2"))
        assertFalse(requiresToolReasoningContent("deepseek-flash"))
        assertFalse(requiresToolReasoningContent("gpt-4o"))
        assertFalse(requiresToolReasoningContent(""))
    }
}
