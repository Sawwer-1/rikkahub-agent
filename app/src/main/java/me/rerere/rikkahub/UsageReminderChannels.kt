package me.rerere.rikkahub

/**
 * Notification channel IDs and intent extra used by the usage reminder/lock stack
 * (ported from jude). Channels themselves are created in UsageReminderService.onCreate
 * so no other app-level initialization file needs to change.
 */
const val USAGE_REMINDER_MONITOR_CHANNEL_ID = "usage_reminder_monitor"
const val USAGE_LIMIT_REMINDER_CHANNEL_ID = "usage_limit_reminder"
const val EXTRA_OPEN_USAGE_TRACKER = "extra_open_usage_tracker"
