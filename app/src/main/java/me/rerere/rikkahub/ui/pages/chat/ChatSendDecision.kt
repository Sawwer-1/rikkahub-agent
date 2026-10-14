package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.ai.SteeringState
import me.rerere.rikkahub.service.chat.RuntimeState
import me.rerere.rikkahub.service.chat.SteeringUiEntry

/** User-facing send intents. UI copy stays conversational; these names remain internal. */
internal enum class ChatSendAction {
    SOFT_STEER,
    QUEUE,
    INTERRUPT,
    STOP,
    SHOW_RUNNING_CHOICES,
}

internal enum class RunningSendChoice {
    CONTINUE_WITH_GUIDANCE,
    HANDLE_AFTER_CURRENT_TASK,
    STOP_AND_REPLACE,
}

/**
 * 短按发送的分流。
 *
 * 生成中带文本 → [ChatSendAction.INTERRUPT]（需求 2026-10-14，对齐 EXTV）：当前这一轮立即
 * 停下、已生成内容保留，并把这条消息作为最新用户消息从它之下重新起一轮；不再是"当提醒注入
 * 当前上下文"。只想提醒不断当前轮的走长按选单（[RunningSendChoice.CONTINUE_WITH_GUIDANCE]）。
 *
 * 等待审批时仍走 [ChatSendAction.QUEUE]：运行时要先取消审批屏障并对账，才允许启动替代命令
 * （`APPROVAL_BARRIER_REQUIRES_RECONCILIATION`，见 ConversationRuntime 的 InterruptCommand
 * 分支），自动打断会把队列按暂停、消息判失败。先批准/拒绝（或取消）那张审批卡即可继续。
 */
internal fun resolveShortSendAction(
    runtimeState: RuntimeState,
    hasInput: Boolean,
    hasGuidanceText: Boolean,
): ChatSendAction = when {
    runtimeState == RuntimeState.Running && hasGuidanceText -> ChatSendAction.INTERRUPT
    runtimeState == RuntimeState.Running && !hasInput -> ChatSendAction.STOP
    runtimeState == RuntimeState.Running -> ChatSendAction.SHOW_RUNNING_CHOICES
    else -> ChatSendAction.QUEUE
}

internal fun resolveLongSendAction(
    runtimeState: RuntimeState,
    hasInput: Boolean,
): ChatSendAction = when {
    runtimeState == RuntimeState.Running && hasInput -> ChatSendAction.SHOW_RUNNING_CHOICES
    runtimeState == RuntimeState.Running -> ChatSendAction.STOP
    else -> ChatSendAction.QUEUE
}

internal fun RunningSendChoice.toSendAction(): ChatSendAction = when (this) {
    RunningSendChoice.CONTINUE_WITH_GUIDANCE -> ChatSendAction.SOFT_STEER
    RunningSendChoice.HANDLE_AFTER_CURRENT_TASK -> ChatSendAction.QUEUE
    RunningSendChoice.STOP_AND_REPLACE -> ChatSendAction.INTERRUPT
}

/**
 * Keep every card the user can still switch, plus the latest terminal notice that needs
 * acknowledgement. Successfully applied transient cards disappear when the task ends;
 * persistent ones are rendered from conversation history instead.
 */
internal fun selectVisibleSteeringEntries(
    entries: Collection<SteeringUiEntry>,
): List<SteeringUiEntry> {
    val editable = entries.filter { it.editable }
    val latestTerminal = entries.lastOrNull { entry ->
        !entry.editable && entry.state in setOf(
            SteeringState.FALLBACK_QUEUED,
            SteeringState.NOT_APPLIED_RUN_FINISHED,
            SteeringState.REJECTED_NOT_STEERABLE,
        )
    }
    return if (latestTerminal == null || editable.any { it.commandId == latestTerminal.commandId }) {
        editable
    } else {
        editable + latestTerminal
    }
}
