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
        // 2026-10-14 需求（对齐 EXTV）：生成中追加消息 = 停当前这一轮 + 从这条消息之下续跑，
        // 不再是"当提醒塞进当前上下文"。想只提醒的走长按选单（CONTINUE_WITH_GUIDANCE）。
        assertEquals(
            ChatSendAction.INTERRUPT,
            resolveShortSendAction(RuntimeState.Running, hasInput = true, hasGuidanceText = true),
        )
        assertEquals(
            ChatSendAction.STOP,
            resolveShortSendAction(RuntimeState.Running, hasInput = false, hasGuidanceText = false),
        )
        assertEquals(
            ChatSendAction.SHOW_RUNNING_CHOICES,
            resolveShortSendAction(RuntimeState.Running, hasInput = true, hasGuidanceText = false),
        )
        // 长按选单里的"继续引导"仍是提醒语义，两条路都留着。
        assertEquals(
            ChatSendAction.SOFT_STEER,
            RunningSendChoice.CONTINUE_WITH_GUIDANCE.toSendAction(),
        )
    }

    @Test
    fun `idle and approval states keep ordinary messages queued`() {
        assertEquals(
            ChatSendAction.QUEUE,
            resolveShortSendAction(RuntimeState.Idle, hasInput = true, hasGuidanceText = true),
        )
        // WaitingApproval 不自动打断：运行时取消审批屏障后要求先对账再启动替代
        // （APPROVAL_BARRIER_REQUIRES_RECONCILIATION，ConversationRuntime 约 :1268），
        // 自动打断会把队列按暂停、消息被判失败。先处理或取消那张审批卡即可继续。
        assertEquals(
            ChatSendAction.QUEUE,
            resolveShortSendAction(RuntimeState.WaitingApproval, hasInput = true, hasGuidanceText = true),
        )
    }

    @Test
    fun `running long press exposes the three conversational choices`() {
        assertEquals(
            ChatSendAction.SHOW_RUNNING_CHOICES,
            resolveLongSendAction(RuntimeState.Running, hasInput = true),
        )
        assertEquals(
            ChatSendAction.SOFT_STEER,
            RunningSendChoice.CONTINUE_WITH_GUIDANCE.toSendAction(),
        )
        assertEquals(
            ChatSendAction.QUEUE,
            RunningSendChoice.HANDLE_AFTER_CURRENT_TASK.toSendAction(),
        )
        assertEquals(
            ChatSendAction.INTERRUPT,
            RunningSendChoice.STOP_AND_REPLACE.toSendAction(),
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
