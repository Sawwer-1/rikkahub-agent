package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动滚动摘要的触发判据（工单 CB 2026-10-18）。
 *
 * 两轮结论都固化在这里：
 * 1. 旧判据两路默认全关（`Assistant.contextMessageSize` 默认 0、阈值默认留空）→ 开关打开也永不触发；
 * 2. 触发时机**交给用户**：会话配置的「保留 + 增量」优先，其次助手窗口，最后才由代码兜底。
 */
class AutoCompressionTriggerTest {
    @Test
    fun `configured extra messages win over the assistant window`() {
        // 用户在弹窗里选了「保留 +16」→ 门槛 48，助手窗口（20）不再参与。
        assertEquals(
            48,
            resolveAutoCompressionCountThreshold(
                keepRecentMessages = 32,
                configuredExtraMessages = 16,
                assistantContextMessageSize = 20,
            ),
        )
    }

    @Test
    fun `assistant window is used when the conversation has no explicit trigger`() {
        // 老语义保持：未显式配置时按助手的「上下文消息数」算（32 + 20）。
        assertEquals(
            52,
            resolveAutoCompressionCountThreshold(
                keepRecentMessages = 32,
                configuredExtraMessages = 0,
                assistantContextMessageSize = 20,
            ),
        )
    }

    @Test
    fun `both unset falls back to folding as much as we keep`() {
        // 兜底：折叠量 ≥ 保留量（32 → 64），否则默认配置下开关等于摆设。
        assertEquals(
            64,
            resolveAutoCompressionCountThreshold(
                keepRecentMessages = 32,
                configuredExtraMessages = 0,
                assistantContextMessageSize = 0,
            ),
        )
        // 保留 1 条的极限设置：门槛 2，仍要求「至少有一条可折」。
        assertEquals(
            2,
            resolveAutoCompressionCountThreshold(
                keepRecentMessages = 1,
                configuredExtraMessages = 0,
                assistantContextMessageSize = 0,
            ),
        )
    }

    @Test
    fun `either condition triggers a fold`() {
        assertFalse(
            shouldAutoCompress(visibleNodeCount = 63, countThreshold = 64, tokenBudgetTriggered = false),
        )
        assertTrue(
            shouldAutoCompress(visibleNodeCount = 64, countThreshold = 64, tokenBudgetTriggered = false),
        )
        // 上下文占用先到也算触发（OR 语义）。
        assertTrue(
            shouldAutoCompress(visibleNodeCount = 2, countThreshold = 64, tokenBudgetTriggered = true),
        )
    }

    @Test
    fun `conversation percent wins over the global threshold`() {
        assertEquals(70, resolveAutoCompressionPercent(configuredPercent = 70, globalPercent = 80))
        // 会话没选 → 跟全局设置走（全局留空 = 这一路不参与）。
        assertEquals(80, resolveAutoCompressionPercent(configuredPercent = null, globalPercent = 80))
        assertNull(resolveAutoCompressionPercent(configuredPercent = null, globalPercent = null))
        // 越界值收敛到 5..95，避免用户填出 0% / 200% 这种必折或永不折的口径。
        assertEquals(5, resolveAutoCompressionPercent(configuredPercent = 1, globalPercent = null))
        assertEquals(95, resolveAutoCompressionPercent(configuredPercent = 200, globalPercent = null))
    }
}
