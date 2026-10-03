package me.rerere.rikkahub.ui.pages.setting.scheduledjobs

import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for [describeSchedule]/[describeCron] - the schedule pretty-printer
 * shared by the scheduled-jobs list row and the detail screen header. Recognition is
 * Context-free (resource ids + plain args); rendering is a thin on-device wrapper.
 *
 * Time-relative formatting (`formatAbsoluteTime` / `formatAbsoluteForDetail`) is locale- and
 * clock-dependent and intentionally not asserted here; only the deterministic cron and mode
 * branches are covered.
 */
class ScheduledJobSummariesTest {

    private fun job(
        scheduleType: String,
        cronExpression: String? = null,
        atUnixMs: Long? = null,
        mode: String = "llm",
    ) = ScheduledJobEntity(
        id = "id",
        name = "name",
        assistantId = "assistant",
        scheduleType = scheduleType,
        cronExpression = cronExpression,
        atUnixMs = atUnixMs,
        createdAtMs = 0L,
        mode = mode,
    )

    @Test fun `once with no time set`() {
        assertEquals(
            ScheduleSummary.OnceNoTime,
            describeSchedule(job("once", atUnixMs = null)),
        )
    }

    @Test fun `once with time carries the timestamp`() {
        assertEquals(
            ScheduleSummary.OnceAt(12345L),
            describeSchedule(job("once", atUnixMs = 12345L)),
        )
    }

    @Test fun `cron macro shortcuts`() {
        assertEquals(
            ScheduleSummary.CronRes(R.string.ui2_jobs_every_hour),
            describeCron("@hourly"),
        )
        assertEquals(
            ScheduleSummary.CronRes(R.string.ui2_jobs_every_day_at_midnight),
            describeCron("@daily"),
        )
        assertEquals(
            ScheduleSummary.CronRes(R.string.ui2_jobs_every_day_at_midnight),
            describeCron("@midnight"),
        )
        assertEquals(
            ScheduleSummary.CronRes(R.string.ui2_jobs_every_sunday),
            describeCron("@weekly"),
        )
        assertEquals(
            ScheduleSummary.CronRes(R.string.ui2_jobs_first_of_month),
            describeCron("@monthly"),
        )
        assertEquals(
            ScheduleSummary.CronRes(R.string.ui2_jobs_every_jan_1),
            describeCron("@yearly"),
        )
        assertEquals(
            ScheduleSummary.CronRes(R.string.ui2_jobs_every_jan_1),
            describeCron("@annually"),
        )
    }

    @Test fun `cron at-every passthrough keeps the raw spec`() {
        assertEquals(
            ScheduleSummary.CronEveryInterval("30m"),
            describeCron("@every 30m"),
        )
        assertEquals(
            ScheduleSummary.CronEveryInterval("2h"),
            describeCron("@every 2h"),
        )
    }

    @Test fun `cron every N minutes`() {
        assertEquals(
            ScheduleSummary.CronRes(R.string.ui2_jobs_every_minute),
            describeCron("*/1 * * * *"),
        )
        assertEquals(
            ScheduleSummary.CronRes(R.string.ui2_jobs_every_n_min, listOf(15)),
            describeCron("*/15 * * * *"),
        )
    }

    @Test fun `cron every N hours`() {
        assertEquals(
            ScheduleSummary.CronRes(R.string.ui2_jobs_every_hour),
            describeCron("0 */1 * * *"),
        )
        assertEquals(
            ScheduleSummary.CronRes(R.string.ui2_jobs_every_n_hours, listOf(6)),
            describeCron("0 */6 * * *"),
        )
    }

    @Test fun `cron daily at HH MM`() {
        assertEquals(
            ScheduleSummary.CronRes(R.string.ui2_jobs_every_day_at, listOf("09:00")),
            describeCron("0 9 * * *"),
        )
    }

    @Test fun `cron specific weekdays render day keys then time`() {
        val summary = describeCron("0 9 * * MON,WED,FRI")
        assertTrue(summary is ScheduleSummary.CronRes)
        summary as ScheduleSummary.CronRes
        assertEquals(R.string.ui2_jobs_every_days_at, summary.id)
        assertEquals(
            listOf(
                R.string.ui2_jobs_day_mon,
                R.string.ui2_jobs_day_wed,
                R.string.ui2_jobs_day_fri,
                "09:00",
            ),
            summary.args,
        )
    }

    @Test fun `unrecognised cron falls back to custom`() {
        assertEquals(
            ScheduleSummary.Custom("0 9 31 2 *"),
            describeSchedule(job("cron", "0 9 31 2 *")),
        )
    }

    @Test fun `unknown schedule type falls back to custom with the raw type`() {
        assertEquals(
            ScheduleSummary.Custom("interval"),
            describeSchedule(job("interval")),
        )
    }

    @Test fun `empty cron expression falls back to custom`() {
        assertEquals(
            ScheduleSummary.Custom(""),
            describeSchedule(job("cron", "")),
        )
    }
}
