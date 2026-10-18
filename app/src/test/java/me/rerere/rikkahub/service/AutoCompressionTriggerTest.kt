package me.rerere.rikkahub.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工单 CB 2026-10-18：自动滚动摘要「开了开关但从不触发」。
 *
 * 旧判据两路都默认关：`Assistant.contextMessageSize` 默认 0（不限条数）、
 * `autoCompactionThreshold*` 默认留空（不启用）→ 开关打开也永不压缩。
 * 现在助手不限条数时用「折叠量 ≥ 保留量」兜底，仅在用户主动打开开关时生效。
 */
class AutoCompressionTriggerTest {
    @Test
    fun `unlimited assistant window still triggers once folding is worth it`() {
        // 保留 32、可见 64：折掉的量与留下的量相当，值得跑一次摘要。
        assertTrue(
            shouldAutoCompress(
                visibleNodeCount = 64,
                keepRecentMessages = 32,
                assistantContextMessageSize = 0,
                tokenBudgetTriggered = false,
            ),
        )
        // 63 条还差一条：不触发，避免每轮都压。
        assertFalse(
            shouldAutoCompress(
                visibleNodeCount = 63,
                keepRecentMessages = 32,
                assistantContextMessageSize = 0,
                tokenBudgetTriggered = false,
            ),
        )
    }

    @Test
    fun `explicit assistant window keeps the original count semantics`() {
        // 旧语义不变：可见节点 ≥ 保留 + 助手窗口（32 + 20）。
        assertTrue(
            shouldAutoCompress(
                visibleNodeCount = 52,
                keepRecentMessages = 32,
                assistantContextMessageSize = 20,
                tokenBudgetTriggered = false,
            ),
        )
        assertFalse(
            shouldAutoCompress(
                visibleNodeCount = 51,
                keepRecentMessages = 32,
                assistantContextMessageSize = 20,
                tokenBudgetTriggered = false,
            ),
        )
    }

    @Test
    fun `token budget trigger wins regardless of node counts`() {
        assertTrue(
            shouldAutoCompress(
                visibleNodeCount = 2,
                keepRecentMessages = 32,
                assistantContextMessageSize = 0,
                tokenBudgetTriggered = true,
            ),
        )
    }

    @Test
    fun `keep window edge cases never trigger on a short conversation`() {
        // 保留条数为 1 的极限设置：至少要两条才谈得上折叠。
        assertFalse(
            shouldAutoCompress(
                visibleNodeCount = 1,
                keepRecentMessages = 1,
                assistantContextMessageSize = 0,
                tokenBudgetTriggered = false,
            ),
        )
        assertTrue(
            shouldAutoCompress(
                visibleNodeCount = 2,
                keepRecentMessages = 1,
                assistantContextMessageSize = 0,
                tokenBudgetTriggered = false,
            ),
        )
    }
}
