package me.rerere.rikkahub.ui.pages.setting.scheduledjobs

import android.content.Context
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One-line human summary of a job's schedule. Used in the list row's "When:" subtitle and at
 * the top of the detail screen. We intentionally keep this short — the detail screen renders
 * the full breakdown elsewhere.
 *
 * Examples (English resources):
 *   once at Wed 16:09          (one-shot, future)
 *   once at 7 May 16:09        (one-shot, > 24h away)
 *   every day at 09:00         (cron `0 9 * * *` or @daily)
 *   every 30 min               (cron `@every 30m`)
 *   every Mon, Wed, Fri 09:00  (cron "0 9 * * MON,WED,FRI")
 *   custom: <expr>              (cron we couldn't pretty-print)
 */
fun summariseSchedule(context: Context, job: ScheduledJobEntity): String = when (job.scheduleType) {
    "once" -> {
        val ms = job.atUnixMs ?: return context.getString(R.string.ui2_jobs_once_no_time)
        context.getString(R.string.ui2_jobs_once_at, formatAbsoluteTime(ms))
    }
    "cron" -> {
        val expr = job.cronExpression?.trim().orEmpty()
        prettyCron(context, expr) ?: context.getString(R.string.ui2_jobs_custom, expr)
    }
    else -> job.scheduleType
}

private fun formatAbsoluteTime(ms: Long): String {
    val now = System.currentTimeMillis()
    val ageMs = ms - now
    val sameDayCutoff = 24L * 60 * 60 * 1000
    return if (ageMs in 0..sameDayCutoff) {
        SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(ms))
    } else {
        SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(ms))
    }
}

/**
 * Best-effort pretty-printer for the supported cron forms. Returns null when the expression
 * is too complex to summarise cleanly — caller falls back to "custom: <expr>".
 *
 * Recognised:
 *  - @hourly, @daily, @weekly, @monthly, @yearly
 *  - @every Ns|Nm|Nh|Nd
 *  - "M H * * *"              -> "every day at HH:MM"
 *  - "M H * * MON,WED,FRI"    -> "every Mon, Wed, Fri at HH:MM"
 *  - "(slash)N * * * *"       -> "every N min"  (where (slash)N is `*` followed by `/N`)
 *  - "0 (slash)N * * *"       -> "every N hours"
 */
private fun prettyCron(context: Context, expr: String): String? {
    val e = expr.trim()
    if (e.isEmpty()) return null
    when (e.lowercase()) {
        "@hourly" -> return context.getString(R.string.ui2_jobs_every_hour)
        "@daily", "@midnight" -> return context.getString(R.string.ui2_jobs_every_day_at_midnight)
        "@weekly" -> return context.getString(R.string.ui2_jobs_every_sunday)
        "@monthly" -> return context.getString(R.string.ui2_jobs_first_of_month)
        "@yearly", "@annually" -> return context.getString(R.string.ui2_jobs_every_jan_1)
    }
    if (e.startsWith("@every", ignoreCase = true)) {
        val rest = e.substring("@every".length).trim()
        return context.getString(R.string.ui2_jobs_every_interval, rest)
    }
    val parts = e.split(Regex("\\s+"))
    if (parts.size != 5) return null
    val (minute, hour, dom, month, dow) = parts.let { listOf(it[0], it[1], it[2], it[3], it[4]) }

    // every N min — "*/N * * * *"
    if (hour == "*" && dom == "*" && month == "*" && dow == "*" && minute.matches(Regex("\\*/\\d+"))) {
        val n = minute.removePrefix("*/").toIntOrNull() ?: return null
        return if (n == 1) context.getString(R.string.ui2_jobs_every_minute)
        else context.getString(R.string.ui2_jobs_every_n_min, n)
    }
    // every N hours — "0 */N * * *"
    if (minute == "0" && dom == "*" && month == "*" && dow == "*" && hour.matches(Regex("\\*/\\d+"))) {
        val n = hour.removePrefix("*/").toIntOrNull() ?: return null
        return if (n == 1) context.getString(R.string.ui2_jobs_every_hour)
        else context.getString(R.string.ui2_jobs_every_n_hours, n)
    }
    // every day at HH:MM — "M H * * *"
    if (dom == "*" && month == "*" && minute.toIntOrNull() in 0..59 && hour.toIntOrNull() in 0..23) {
        val hh = hour.toInt()
        val mm = minute.toInt()
        val time = "%02d:%02d".format(hh, mm)
        if (dow == "*") return context.getString(R.string.ui2_jobs_every_day_at, time)
        // specific weekdays — "MON,WED,FRI" or "1,3,5"
        val days = parseDows(context, dow) ?: return null
        if (days.size in 1..6) {
            return context.getString(
                R.string.ui2_jobs_every_days_at,
                days.joinToString(context.getString(R.string.ui2_jobs_list_separator)),
                time,
            )
        }
    }
    return null
}

private val DOW_KEYS = mapOf(
    "0" to R.string.ui2_jobs_day_sun, "7" to R.string.ui2_jobs_day_sun,
    "1" to R.string.ui2_jobs_day_mon, "2" to R.string.ui2_jobs_day_tue,
    "3" to R.string.ui2_jobs_day_wed, "4" to R.string.ui2_jobs_day_thu,
    "5" to R.string.ui2_jobs_day_fri, "6" to R.string.ui2_jobs_day_sat,
    "SUN" to R.string.ui2_jobs_day_sun, "MON" to R.string.ui2_jobs_day_mon,
    "TUE" to R.string.ui2_jobs_day_tue, "WED" to R.string.ui2_jobs_day_wed,
    "THU" to R.string.ui2_jobs_day_thu, "FRI" to R.string.ui2_jobs_day_fri,
    "SAT" to R.string.ui2_jobs_day_sat,
)

private fun parseDows(context: Context, spec: String): List<String>? {
    if (spec.contains("/") || spec.contains("-")) return null
    val parts = spec.split(",").map { it.trim().uppercase() }
    val names = parts.map { key ->
        val nameRes = DOW_KEYS[key] ?: return null
        context.getString(nameRes)
    }
    return names
}

/**
 * Returns "llm" or "direct" + a short human label. Used to render the mode chip / row label.
 */
fun modeLabel(context: Context, job: ScheduledJobEntity): String = when (job.mode) {
    "llm" -> context.getString(R.string.ui2_jobs_mode_llm)
    "direct" -> context.getString(R.string.ui2_jobs_mode_direct)
    else -> job.mode
}

fun formatAbsoluteForDetail(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
