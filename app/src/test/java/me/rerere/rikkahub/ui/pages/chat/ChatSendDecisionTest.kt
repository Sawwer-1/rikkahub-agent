package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.ai.SteeringState
import me.rerere.rikkahub.service.chat.RuntimeState
import me.rerere.rikkahub.service.chat.SteeringHistoryMode
import me.rerere.rikkahub.service.chat.SteeringUiEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ChatSendDecisionTest {
    @Test
    fun `running short send interrupts the run and restarts from the new message`() {
        // 2026-10-14 需求（对齐 EXTV）+ 2026-10-17 定版：生成中追加消息 = 停当前这一轮 +
        // 从这条消息之下续跑。**纯附件也走同一条**（不再弹"这条消息怎么处理"三选）。
        assertEquals(
            ChatSendAction.INTERRUPT,
            resolveShortSendAction(RuntimeState.Running, hasInput = true),
        )
        assertEquals(
            ChatSendAction.STOP,
            resolveShortSendAction(RuntimeState.Running, hasInput = false),
        )
    }

    @Test
    fun `approval waiting short send interrupts the barrier and restarts from the new message`() {
        // 2026-10-16 需求：审批等待中追加消息 = 取消审批屏障 + 从这条消息继续（与生成中短按
        // 发送同一套 INTERRUPT 语义）。仅当取消提交失败/对账失败时运行时才回退为
        // 暂停待恢复（ConversationRuntime 的 InterruptCommand 分支）。
        assertEquals(
            ChatSendAction.INTERRUPT,
            resolveShortSendAction(RuntimeState.WaitingApproval, hasInput = true),
        )
        // 空输入不构成消息，落回 QUEUE（no-op），不得误触打断。
        assertEquals(
            ChatSendAction.QUEUE,
            resolveShortSendAction(RuntimeState.WaitingApproval, hasInput = false),
        )
    }

    @Test
    fun `paused short send restarts from the new message instead of waiting for a manual resume`() {
        // 2026-10-17：暂停态（含"审批状态需对账"与失败 pin 的 Paused）下追加消息 = 改做这条。
        // 背景：运行时的状态收窄曾把"审批等待 + 发生过队列控制"写成 Idle，短按落到 QUEUE，
        // 用户看到的是"补了一条消息但什么都没发生"。判据与运行时状态一起在本版修正。
        assertEquals(
            ChatSendAction.INTERRUPT,
            resolveShortSendAction(RuntimeState.Paused, hasInput = true),
        )
        assertEquals(
            ChatSendAction.QUEUE,
            resolveShortSendAction(RuntimeState.Paused, hasInput = false),
        )
    }

    @Test
    fun `no app send path can produce a soft steer any more`() {
        // 用户反馈（2026-10-17）：生成中追加消息，当前轮没被打断，反而出现"我正在记着 /
        // 可切换是否保留"的引导卡片。定版：App 发送路径不再产生引导——枚举值已删除
        // （编译期保证），长按选单只剩"排队"与"立刻改做这条"两项。
        assertEquals(
            setOf(ChatSendAction.QUEUE, ChatSendAction.INTERRUPT),
            RunningSendChoice.entries.map { it.toSendAction() }.toSet(),
        )
        assertEquals(
            ChatSendAction.SHOW_RUNNING_CHOICES,
            resolveLongSendAction(RuntimeState.Running, hasInput = true),
        )
        assertEquals(
            ChatSendAction.STOP,
            resolveLongSendAction(RuntimeState.Running, hasInput = false),
        )
        assertEquals(
            ChatSendAction.QUEUE,
            resolveLongSendAction(RuntimeState.Idle, hasInput = true),
        )
    }

    @Test
    fun `idle keeps ordinary messages queued`() {
        assertEquals(
            ChatSendAction.QUEUE,
            resolveShortSendAction(RuntimeState.Idle, hasInput = true),
        )
    }

    @Test
    fun `visible guidance keeps editable cards and the latest fallback notice`() {
        val runId = Uuid.random()
        val appliedAfterRunFinished = steeringEntry(runId, SteeringState.APPLIED, editable = false)
        val pending = steeringEntry(runId, SteeringState.PENDING, editable = true)
        val oldFallback = steeringEntry(runId, SteeringState.FALLBACK_QUEUED, editable = false)
        val latestFallback = steeringEntry(runId, SteeringState.FALLBACK_QUEUED, editable = false)

        assertEquals(
            listOf(pending, latestFallback),
            selectVisibleSteeringEntries(
                listOf(appliedAfterRunFinished, pending, oldFallback, latestFallback)
            ),
        )
    }

    private fun steeringEntry(
        runId: Uuid,
        state: SteeringState,
        editable: Boolean,
    ) = SteeringUiEntry(
        commandId = Uuid.random(),
        runId = runId,
        text = state.name,
        state = state,
        historyMode = SteeringHistoryMode.TRANSIENT,
        editable = editable,
    )
}
