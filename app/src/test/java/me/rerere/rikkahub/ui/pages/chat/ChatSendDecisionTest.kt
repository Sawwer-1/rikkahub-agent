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
    fun `approval waiting short send interrupts the barrier and restarts from the new message`() {
        // 2026-10-16 需求：审批等待中追加消息 = 取消审批屏障 + 从这条消息继续（与生成中短按
        // 发送同一套 INTERRUPT 语义）。仅当取消提交失败/对账失败时运行时才回退为
        // 暂停待恢复（ConversationRuntime 的 InterruptCommand 分支）。
        assertEquals(
            ChatSendAction.INTERRUPT,
            resolveShortSendAction(RuntimeState.WaitingApproval, hasInput = true, hasGuidanceText = true),
        )
        // 纯附件也按“立即换到这条消息”处理：审批等待没有“当前轮”可提醒，不存在引导选项。
        assertEquals(
            ChatSendAction.INTERRUPT,
            resolveShortSendAction(RuntimeState.WaitingApproval, hasInput = true, hasGuidanceText = false),
        )
        // 空输入不构成消息，落回 QUEUE（no-op），不得误触打断。
        assertEquals(
            ChatSendAction.QUEUE,
            resolveShortSendAction(RuntimeState.WaitingApproval, hasInput = false, hasGuidanceText = false),
        )
    }

    @Test
    fun `idle keeps ordinary messages queued`() {
        assertEquals(
            ChatSendAction.QUEUE,
            resolveShortSendAction(RuntimeState.Idle, hasInput = true, hasGuidanceText = true),
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
