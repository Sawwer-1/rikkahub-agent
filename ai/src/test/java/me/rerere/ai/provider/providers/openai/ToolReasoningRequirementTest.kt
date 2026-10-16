package me.rerere.ai.provider.providers.openai

import me.rerere.ai.provider.isDeepSeekV4Family
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
    fun `family predicate is the single source of truth for every consumer`() {
        // 2026-10-17：工具轮 reasoning 判据与 Dream 预算/超时判据统一到 isDeepSeekV4Family，
        // 这里钉住被判据本身，避免以后又有人写第二套只认连字符的写法。
        assertTrue(isDeepSeekV4Family("deepseek-v4.1-flash"))
        assertTrue(isDeepSeekV4Family("Pro/deepseek-ai/DeepSeek-V4-Pro"))
        assertTrue(isDeepSeekV4Family("DeepSeek_V4_Flash"))
        assertFalse(isDeepSeekV4Family("deepseek-v3.2"))
        assertFalse(isDeepSeekV4Family("gpt-4o"))
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
