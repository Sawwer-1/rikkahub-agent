package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B10 / R8（A13 P1-2）：单轮时间预算到点后的**硬中断**语义。
 *
 * 预算实现早已在仓内（`turnBudgetMs` → `wallClockNeedsFinalization`），本用例锁的是它的后果：
 * 到点后必须 forceFinalization + **停止再发起可恢复工具**（skipResumableTools），
 * 否则「预算到了还在跑工具」，用户看到的就是一条永远结束不了的回复。
 */
class GenerationFinalizationStepTest {
    @Test
    fun `wall clock budget forces tool free finalization and skips resumable tools`() {
        val step = generationFinalizationStep(
            stepIndex = 1,
            maxSteps = 10,
            wallClockNeedsFinalization = true,
            loopGuardNeedsFinalization = false,
        )

        assertTrue(step.forceFinalization)
        // 关键：预算到点之后不能再起新的工具（这是「硬」的地方）。
        assertTrue(step.skipResumableTools)
    }

    @Test
    fun `loop guard trips behave the same way`() {
        val step = generationFinalizationStep(
            stepIndex = 0,
            maxSteps = 10,
            wallClockNeedsFinalization = false,
            loopGuardNeedsFinalization = true,
        )

        assertTrue(step.forceFinalization)
        assertTrue(step.skipResumableTools)
    }

    @Test
    fun `a normal step keeps tools resumable`() {
        val step = generationFinalizationStep(
            stepIndex = 0,
            maxSteps = 10,
            wallClockNeedsFinalization = false,
            loopGuardNeedsFinalization = false,
        )

        assertFalse(step.forceFinalization)
        assertFalse(step.skipResumableTools)
    }
}
