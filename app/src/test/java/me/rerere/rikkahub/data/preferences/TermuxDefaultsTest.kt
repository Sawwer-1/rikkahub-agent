package me.rerere.rikkahub.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-Kotlin coverage for the Termux configurable-knob clamping helpers introduced for
 * GitHub issue #5. Mirrors [me.rerere.rikkahub.browser.BrowserTimeoutClampTest] in
 * structure — every clamp function gets floor / ceiling / in-range / default tests.
 */
class TermuxDefaultsTest {

    // --- clampCommandTimeoutMs ------------------------------------------------------------

    @Test
    fun commandTimeout_belowFloor_snapsToMin() {
        assertEquals(TermuxDefaults.MIN_COMMAND_TIMEOUT_MS,
            TermuxDefaults.clampCommandTimeoutMs(0L))
        assertEquals(TermuxDefaults.MIN_COMMAND_TIMEOUT_MS,
            TermuxDefaults.clampCommandTimeoutMs(-1L))
        assertEquals(TermuxDefaults.MIN_COMMAND_TIMEOUT_MS,
            TermuxDefaults.clampCommandTimeoutMs(TermuxDefaults.MIN_COMMAND_TIMEOUT_MS - 1L))
    }

    @Test
    fun commandTimeout_aboveCeiling_snapsToMax() {
        assertEquals(TermuxDefaults.MAX_COMMAND_TIMEOUT_MS,
            TermuxDefaults.clampCommandTimeoutMs(Long.MAX_VALUE))
        assertEquals(TermuxDefaults.MAX_COMMAND_TIMEOUT_MS,
            TermuxDefaults.clampCommandTimeoutMs(TermuxDefaults.MAX_COMMAND_TIMEOUT_MS + 1L))
    }

    @Test
    fun commandTimeout_inRange_passesThrough() {
        assertEquals(TermuxDefaults.DEFAULT_COMMAND_TIMEOUT_MS,
            TermuxDefaults.clampCommandTimeoutMs(TermuxDefaults.DEFAULT_COMMAND_TIMEOUT_MS))
        assertEquals(30_000L, TermuxDefaults.clampCommandTimeoutMs(30_000L))
        assertEquals(TermuxDefaults.MIN_COMMAND_TIMEOUT_MS,
            TermuxDefaults.clampCommandTimeoutMs(TermuxDefaults.MIN_COMMAND_TIMEOUT_MS))
        assertEquals(TermuxDefaults.MAX_COMMAND_TIMEOUT_MS,
            TermuxDefaults.clampCommandTimeoutMs(TermuxDefaults.MAX_COMMAND_TIMEOUT_MS))
    }

    // --- clampTurnBudgetMs ----------------------------------------------------------------

    @Test
    fun turnBudget_zeroOrNegative_readsAsUnlimited() {
        // 2026-10-12: 0 / negative means "no wall-clock cut" and clamps up to the ceiling.
        assertEquals(TermuxDefaults.MAX_TURN_BUDGET_MS,
            TermuxDefaults.clampTurnBudgetMs(0L))
        assertEquals(TermuxDefaults.MAX_TURN_BUDGET_MS,
            TermuxDefaults.clampTurnBudgetMs(-1L))
    }

    @Test
    fun turnBudget_belowOneMinuteButPositive_snapsToFloor() {
        assertEquals(TermuxDefaults.MIN_TURN_BUDGET_MS,
            TermuxDefaults.clampTurnBudgetMs(1L))
    }

    @Test
    fun turnBudget_aboveCeiling_snapsToMax() {
        assertEquals(TermuxDefaults.MAX_TURN_BUDGET_MS,
            TermuxDefaults.clampTurnBudgetMs(Long.MAX_VALUE))
        assertEquals(TermuxDefaults.MAX_TURN_BUDGET_MS,
            TermuxDefaults.clampTurnBudgetMs(TermuxDefaults.MAX_TURN_BUDGET_MS + 1L))
    }

    @Test
    fun turnBudget_inRange_passesThrough() {
        assertEquals(TermuxDefaults.DEFAULT_TURN_BUDGET_MS,
            TermuxDefaults.clampTurnBudgetMs(TermuxDefaults.DEFAULT_TURN_BUDGET_MS))
        assertEquals(5L * 60_000L, TermuxDefaults.clampTurnBudgetMs(5L * 60_000L))
    }

    // --- clampVerifyTimeoutMs -------------------------------------------------------------

    @Test
    fun verifyTimeout_belowFloor_snapsToMin() {
        assertEquals(TermuxDefaults.MIN_VERIFY_TIMEOUT_MS,
            TermuxDefaults.clampVerifyTimeoutMs(0L))
        assertEquals(TermuxDefaults.MIN_VERIFY_TIMEOUT_MS,
            TermuxDefaults.clampVerifyTimeoutMs(TermuxDefaults.MIN_VERIFY_TIMEOUT_MS - 1L))
    }

    @Test
    fun verifyTimeout_aboveCeiling_snapsToMax() {
        assertEquals(TermuxDefaults.MAX_VERIFY_TIMEOUT_MS,
            TermuxDefaults.clampVerifyTimeoutMs(Long.MAX_VALUE))
    }

    @Test
    fun verifyTimeout_inRange_passesThrough() {
        assertEquals(TermuxDefaults.DEFAULT_VERIFY_TIMEOUT_MS,
            TermuxDefaults.clampVerifyTimeoutMs(TermuxDefaults.DEFAULT_VERIFY_TIMEOUT_MS))
    }

    // --- clampMaxStdout / clampMaxStderr --------------------------------------------------

    @Test
    fun maxStdout_belowFloor_snapsToMin() {
        assertEquals(TermuxDefaults.MIN_MAX_STDOUT, TermuxDefaults.clampMaxStdout(0))
        assertEquals(TermuxDefaults.MIN_MAX_STDOUT,
            TermuxDefaults.clampMaxStdout(TermuxDefaults.MIN_MAX_STDOUT - 1))
    }

    @Test
    fun maxStdout_aboveCeiling_snapsToMax() {
        assertEquals(TermuxDefaults.MAX_MAX_STDOUT, TermuxDefaults.clampMaxStdout(Int.MAX_VALUE))
    }

    @Test
    fun maxStdout_inRange_passesThrough() {
        assertEquals(TermuxDefaults.DEFAULT_MAX_STDOUT,
            TermuxDefaults.clampMaxStdout(TermuxDefaults.DEFAULT_MAX_STDOUT))
    }

    @Test
    fun maxStderr_belowFloor_snapsToMin() {
        assertEquals(TermuxDefaults.MIN_MAX_STDERR, TermuxDefaults.clampMaxStderr(0))
        assertEquals(TermuxDefaults.MIN_MAX_STDERR,
            TermuxDefaults.clampMaxStderr(TermuxDefaults.MIN_MAX_STDERR - 1))
    }

    @Test
    fun maxStderr_aboveCeiling_snapsToMax() {
        assertEquals(TermuxDefaults.MAX_MAX_STDERR, TermuxDefaults.clampMaxStderr(Int.MAX_VALUE))
        assertEquals(TermuxDefaults.MAX_MAX_STDERR,
            TermuxDefaults.clampMaxStderr(TermuxDefaults.MAX_MAX_STDERR + 1))
    }

    @Test
    fun maxStderr_inRange_passesThrough() {
        assertEquals(TermuxDefaults.DEFAULT_MAX_STDERR,
            TermuxDefaults.clampMaxStderr(TermuxDefaults.DEFAULT_MAX_STDERR))
    }

    // --- clampWorkingDir ------------------------------------------------------------------

    @Test
    fun workingDir_blank_returnsDefault() {
        assertEquals(TermuxDefaults.DEFAULT_WORKING_DIR, TermuxDefaults.clampWorkingDir(""))
        assertEquals(TermuxDefaults.DEFAULT_WORKING_DIR, TermuxDefaults.clampWorkingDir("   "))
    }

    @Test
    fun workingDir_nonEmpty_passesThrough() {
        val custom = "/data/data/com.termux/files/home/myproject"
        assertEquals(custom, TermuxDefaults.clampWorkingDir(custom))
    }

    // --- Default values are within their own bounds (regression guard) --------------------

    @Test
    fun defaults_areWithinTheirOwnBounds() {
        assertEquals(TermuxDefaults.DEFAULT_COMMAND_TIMEOUT_MS,
            TermuxDefaults.clampCommandTimeoutMs(TermuxDefaults.DEFAULT_COMMAND_TIMEOUT_MS))
        assertEquals(TermuxDefaults.DEFAULT_TURN_BUDGET_MS,
            TermuxDefaults.clampTurnBudgetMs(TermuxDefaults.DEFAULT_TURN_BUDGET_MS))
        assertEquals(TermuxDefaults.DEFAULT_VERIFY_TIMEOUT_MS,
            TermuxDefaults.clampVerifyTimeoutMs(TermuxDefaults.DEFAULT_VERIFY_TIMEOUT_MS))
        assertEquals(TermuxDefaults.DEFAULT_MAX_STDOUT,
            TermuxDefaults.clampMaxStdout(TermuxDefaults.DEFAULT_MAX_STDOUT))
        assertEquals(TermuxDefaults.DEFAULT_MAX_STDERR,
            TermuxDefaults.clampMaxStderr(TermuxDefaults.DEFAULT_MAX_STDERR))
    }

    @Test
    fun turnBudget_defaultIsUnlimited() {
        // 2026-10-12 user override: the default is "no wall-clock cut" (the 24 h ceiling reads
        // as 不限) after a long turn was force-finalized and its running tool cancelled.
        assertEquals(TermuxDefaults.MAX_TURN_BUDGET_MS, TermuxDefaults.DEFAULT_TURN_BUDGET_MS)
        assertEquals(24L * 60L * 60L * 1_000L, TermuxDefaults.DEFAULT_TURN_BUDGET_MS)
    }

    @Test
    fun commandTimeout_maxSecondsAligns600() {
        // Ceiling raised from 300 to 600 per spec override.
        assertEquals(600, TermuxDefaults.MAX_COMMAND_TIMEOUT_SECONDS)
        assertEquals(TermuxDefaults.MAX_COMMAND_TIMEOUT_MS,
            TermuxDefaults.MAX_COMMAND_TIMEOUT_SECONDS.toLong() * 1_000L)
    }
}
