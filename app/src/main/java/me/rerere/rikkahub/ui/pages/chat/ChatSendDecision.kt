package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.ai.SteeringState
import me.rerere.rikkahub.service.chat.RuntimeState
import me.rerere.rikkahub.service.chat.SteeringUiEntry

/** User-facing send intents. UI copy stays conversational; these names remain internal. */
internal enum class ChatSendAction {
    QUEUE,
    INTERRUPT,
    /** 软引导：不打断当前这一轮，把文字作为 steering 注入（只在设置开启时出现）。 */
    SOFT_STEER,
    STOP,
    SHOW_RUNNING_CHOICES,
}

internal enum class RunningSendChoice {
    HANDLE_AFTER_CURRENT_TASK,
    STOP_AND_REPLACE,
}

/**
 * 短按发送的分流（2026-10-17 定版：**有活干时的追加消息一律"打断 + 从新消息重开"**）。
 *
 * 生成中带输入 → [ChatSendAction.INTERRUPT]（需求 2026-10-14，对齐 EXTV）：当前这一轮立即
 * 停下、已生成内容保留（246 起改为不再删除被打断的那条消息，只把未完成工具就地放弃），
 * 并把这条消息作为最新用户消息从它之下重新起一轮。
 *
 * 等待审批时同样走 [ChatSendAction.INTERRUPT]（2026-10-16 工单）：审批等待是"run 槽已空、
 * 只剩审批屏障站着"的挂起态，短按发送会取消屏障（走既有 cancelWaitingApprovalBarrier 语义、
 * 留审计 interrupted:new_message）并对账后把这新条消息作为新一轮生成起点；历史与已排队
 * 消息不动。仅当取消无法提交/对账失败时，运行时才回退为暂停待恢复（见 ConversationRuntime
 * 的 InterruptCommand 分支）。
 *
 * 已暂停（含"审批状态需对账"与失败 pin 的 Paused）也走 [ChatSendAction.INTERRUPT]：
 * 用户此时发新消息的意图就是"改做这条"，把自己卡在暂停里等他先去点恢复是错的。
 *
 * **2026-10-17 定版**：App 短按发送默认**不再产生软引导**（用户反馈生成中追加消息看到的是
 * "我正在记着 / 可切换是否保留"的卡片、当前轮没停）。steering 内核（SteerCommand / 卡片 /
 * web / Telegram `/steer`）保留不动。
 *
 * **B02 / R1 起**给了用户一个显式开关（`Settings.steerOnAppendEnabled`，默认关＝硬打断）。
 * 打开后，生成中带文字追加 → [ChatSendAction.SOFT_STEER]（不打断，文字注入当前轮）；
 * 纯附件承载不了引导（SteerCommand 只带文本），仍走 [ChatSendAction.INTERRUPT]。
 */
internal fun resolveShortSendAction(
    runtimeState: RuntimeState,
    hasInput: Boolean,
    hasGuidanceText: Boolean = false,
    softSteerEnabled: Boolean = false,
): ChatSendAction = when {
    runtimeState == RuntimeState.Running && hasInput && softSteerEnabled && hasGuidanceText ->
        ChatSendAction.SOFT_STEER
    runtimeState == RuntimeState.Running && hasInput -> ChatSendAction.INTERRUPT
    runtimeState == RuntimeState.Running -> ChatSendAction.STOP
    runtimeState == RuntimeState.WaitingApproval && hasInput -> ChatSendAction.INTERRUPT
    runtimeState == RuntimeState.Paused && hasInput -> ChatSendAction.INTERRUPT
    else -> ChatSendAction.QUEUE
}

/**
 * 长按发送的分流：生成中给一次显式选择（排队 / 立刻改做这条），不再是三选。
 * 引导选项已随 2026-10-17 定版移除（见 [resolveShortSendAction]）。
 */
internal fun resolveLongSendAction(
    runtimeState: RuntimeState,
    hasInput: Boolean,
): ChatSendAction = when {
    runtimeState == RuntimeState.Running && hasInput -> ChatSendAction.SHOW_RUNNING_CHOICES
    runtimeState == RuntimeState.Running -> ChatSendAction.STOP
    else -> ChatSendAction.QUEUE
}

internal fun RunningSendChoice.toSendAction(): ChatSendAction = when (this) {
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
