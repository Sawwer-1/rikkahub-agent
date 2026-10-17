package me.rerere.rikkahub.service.chat

/**
 * 引导（steering）提交来源审计（B02 / R1）。
 *
 * 为什么要单独记来源：App 短按发送、队列升格、Web API、Telegram `/steer` 四条路最终都落到
 * 同一个 [SteerCommand]，只靠运行时的 origin 分不清「用户在输入框追加」与「把队列里的消息
 * 升格成引导」——246/247 排查「生成中追加消息没打断」时就卡在这一步。这里把来源抽成纯函数，
 * 审计条目里只落来源标签与元数据，**绝不落引导正文**（审计要可导出）。
 */
internal const val STEERING_AUDIT_CATEGORY = "steering"

internal const val STEERING_AUDIT_ACTION_SUBMITTED = "submitted"

/** 队列里的消息被升格成引导：来源是队列面板，不是 /steer 也不是输入框。 */
internal const val STEERING_SOURCE_QUEUE_PROMOTION = "queue_promotion"

/**
 * 提交来源标签。优先判「队列升格」（它借用 APP_UI 作为 origin，单看 origin 会误判成输入框）。
 */
internal fun steeringAuditTarget(
    origin: CommandOrigin,
    promotedFromQueue: Boolean,
): String = when {
    promotedFromQueue -> STEERING_SOURCE_QUEUE_PROMOTION
    origin == CommandOrigin.APP_UI -> "app_ui"
    origin == CommandOrigin.WEB_API -> "web_api"
    origin == CommandOrigin.TELEGRAM -> "telegram"
    else -> origin.name.lowercase()
}

/**
 * 审计 detail：只放元数据（作用域 / 是否留存 / 字数），不放正文。
 *
 * [textLength] 为 null 表示提交点看不到正文（队列升格时正文还在运行时里），此时不写字数，
 * 免得审计里出现一个"0 字"的假数据。
 */
internal fun steeringAuditDetail(
    scope: SteeringScope,
    historyMode: SteeringHistoryMode,
    textLength: Int?,
): String = buildString {
    append("scope=").append(scope.name)
    append(" history=").append(historyMode.name)
    textLength?.let { append(" chars=").append(it.coerceAtLeast(0)) }
}
