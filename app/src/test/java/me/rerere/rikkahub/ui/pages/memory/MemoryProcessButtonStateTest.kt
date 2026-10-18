package me.rerere.rikkahub.ui.pages.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B10 / R29：「现在整理」与「重试失败」的可用性条件。
 *
 * 目标是与队列状态一致——有活可干、且**当前没有批次在跑**才可点。此前只看条数，
 * 批次跑着时按钮仍然亮着，点下去只是再排一次队，观感上像「点了没反应」。
 */
class MemoryProcessButtonStateTest {
    @Test
    fun `pending captures enable process now but not retry`() {
        val stats = MemoryCenterStats(pendingCaptures = 3)
        assertTrue(canProcessNow(stats))
        assertFalse(canRetryFailed(stats))
    }

    @Test
    fun `a running batch disables both buttons`() {
        val stats = MemoryCenterStats(
            pendingCaptures = 5,
            failedCaptures = 2,
            processingCaptures = 1,
        )
        assertFalse(canProcessNow(stats))
        assertFalse(canRetryFailed(stats))
    }

    @Test
    fun `failed captures enable retry only`() {
        val stats = MemoryCenterStats(failedCaptures = 2)
        assertFalse(canProcessNow(stats))
        assertTrue(canRetryFailed(stats))
    }

    @Test
    fun `paused captures enable both paths`() {
        val stats = MemoryCenterStats(pausedCaptures = 1)
        assertTrue(canProcessNow(stats))
        assertTrue(canRetryFailed(stats))
    }

    @Test
    fun `an empty queue disables everything`() {
        assertFalse(canProcessNow(MemoryCenterStats()))
        assertFalse(canRetryFailed(MemoryCenterStats()))
    }
}
