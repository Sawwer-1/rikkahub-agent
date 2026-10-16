package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.memory.MemorySourceVersion
import me.rerere.rikkahub.service.chat.InterruptCleanupResult
import me.rerere.rikkahub.service.chat.InterruptRegenerateCommand
import me.rerere.rikkahub.service.chat.RegenerateCommand
import me.rerere.rikkahub.service.chat.RegeneratePolicy
import me.rerere.rikkahub.service.chat.RunOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class DurableRegenerationReconciliationTest {
    @Test
    fun `normal and interrupt commands expose one normalized content-bound baseline`() {
        val regeneration = regeneration(
            scopeId = " assistant-a ",
            messageIds = listOf(" message-b ", "message-a", "message-b", ""),
            sourceVersions = listOf(
                MemorySourceVersion("message-b", "B".repeat(64)),
                MemorySourceVersion(" message-a ", "a".repeat(64)),
                MemorySourceVersion("message-c", "invalid"),
            ),
        )
        val expected = DurableRegenerationBaseline(
            assistantScopeId = "assistant-a",
            selectedMessageIds = listOf("message-a", "message-b"),
            selectedSourceVersions = listOf(
                MemorySourceVersion("message-a", "a".repeat(64)),
                MemorySourceVersion("message-b", "b".repeat(64)),
            ),
        )

        assertEquals(expected, regeneration.durableRegenerationBaselineOrNull())
        assertEquals(
            expected,
            InterruptRegenerateCommand(regeneration).durableRegenerationBaselineOrNull(),
        )
        assertNull(
            regeneration.copy(
                baselineSelectedMessageIds = emptyList(),
                baselineSelectedSourceVersions = emptyList(),
            ).durableRegenerationBaselineOrNull(),
        )
    }

    @Test
    fun `legacy id-only baseline remains usable without inventing content versions`() {
        val baseline = regeneration(
            scopeId = "assistant-a",
            messageIds = listOf("message-a"),
        ).durableRegenerationBaselineOrNull()

        assertEquals(listOf("message-a"), baseline?.selectedMessageIds)
        assertTrue(baseline?.selectedSourceVersions.orEmpty().isEmpty())
    }

    @Test
    fun `failed regeneration restores transient graph while success keeps final graph`() = runBlocking {
        var failedRestoreCalled = false
        val failure = runRegenerationTransaction(
            restore = { failedRestoreCalled = true },
            operation = { RunOutcome.Failed(IllegalStateException("provider failed")) },
        )
        assertTrue(failure is RunOutcome.Failed)
        assertTrue(failedRestoreCalled)

        var successfulRestoreCalled = false
        val success = runRegenerationTransaction(
            restore = { successfulRestoreCalled = true },
            operation = { RunOutcome.Completed() },
        )
        assertTrue(success is RunOutcome.Completed)
        assertFalse(successfulRestoreCalled)
    }

    @Test
    fun `user stopped regeneration keeps the new partial graph instead of rolling back`() = runBlocking {
        // 真机验收 P0-1b（2026-10-17）：编辑成新版本 → 重发 → 中途中断。旧判据把 Interrupted/
        // Stopped 与 Failed 一起送进 restore，会话图被回滚到操作前的快照，于是新版本那半截回复
        // 消失、界面摆回上一个版本的中断记录（"B 生成的记录不保存，显示的是 A 版的中断记录"）。
        var stoppedRestoreCalled = false
        val stopped = runRegenerationTransaction(
            restore = { stoppedRestoreCalled = true },
            operation = { RunOutcome.Stopped(InterruptCleanupResult.Completed) },
        )
        assertTrue(stopped is RunOutcome.Stopped)
        assertFalse(stoppedRestoreCalled)

        var interruptedRestoreCalled = false
        val interrupted = runRegenerationTransaction(
            restore = { interruptedRestoreCalled = true },
            operation = { RunOutcome.Interrupted(InterruptCleanupResult.Completed) },
        )
        assertTrue(interrupted is RunOutcome.Interrupted)
        assertFalse(interruptedRestoreCalled)
    }

    @Test
    fun `cancelled regeneration keeps the partial graph while a real failure still restores`() = runBlocking {
        // 用户中断的真实路径：运行时 cancel 这个 run 的 job → operation() 以 CancellationException
        // 抛出。真机验收 P0-1b 就是被这条旧的无条件 restore 吃掉新版本的半截回复。
        var restoreCalledOnCancel = false
        val cancelled = runCatching {
            runRegenerationTransaction(
                restore = { restoreCalledOnCancel = true },
                operation = { throw CancellationException("Interrupted by user") },
            )
        }.exceptionOrNull()
        assertTrue(cancelled is CancellationException)
        assertFalse(restoreCalledOnCancel)

        // 真正的失败仍然要回滚（异常侧）。
        var restoreCalledOnFailure = false
        val failure = runCatching {
            runRegenerationTransaction(
                restore = { restoreCalledOnFailure = true },
                operation = { throw IllegalStateException("provider blew up") },
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(restoreCalledOnFailure)
    }

    private fun regeneration(
        scopeId: String,
        messageIds: List<String>,
        sourceVersions: List<MemorySourceVersion> = emptyList(),
    ) = RegenerateCommand(
        targetMessageId = Uuid.random(),
        expectedTargetVersion = 0L,
        expectedBranchHeadMessageId = Uuid.random(),
        policy = RegeneratePolicy.REJECT_IF_BUSY,
        baselineAssistantScopeId = scopeId,
        baselineSelectedMessageIds = messageIds,
        baselineSelectedSourceVersions = sourceVersions,
    )
}
