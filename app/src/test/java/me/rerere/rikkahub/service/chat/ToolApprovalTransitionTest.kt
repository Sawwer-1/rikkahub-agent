package me.rerere.rikkahub.service.chat

import me.rerere.ai.ui.ToolApprovalState
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolApprovalTransitionTest {
    @Test
    fun `pending approval applies first terminal decision`() {
        val requested = ToolApprovalState.Approved
        assertEquals(
            ToolApprovalTransition.Apply(requested),
            resolveToolApproval(ToolApprovalState.Pending, requested),
        )
    }

    @Test
    fun `repeating same approval is idempotent`() {
        assertEquals(
            ToolApprovalTransition.Idempotent,
            resolveToolApproval(ToolApprovalState.Approved, ToolApprovalState.Approved),
        )
        assertEquals(
            ToolApprovalTransition.Idempotent,
            resolveToolApproval(
                ToolApprovalState.Denied("no"),
                ToolApprovalState.Denied("no"),
            ),
        )
    }

    @Test
    fun `changing a terminal approval is a conflict`() {
        assertEquals(
            ToolApprovalTransition.Conflict,
            resolveToolApproval(ToolApprovalState.Approved, ToolApprovalState.Denied("no")),
        )
        assertEquals(
            ToolApprovalTransition.Conflict,
            resolveToolApproval(ToolApprovalState.Denied("no"), ToolApprovalState.Approved),
        )
    }

    @Test
    fun `auto approval is not a pending user decision`() {
        assertEquals(
            ToolApprovalTransition.NotPending,
            resolveToolApproval(ToolApprovalState.Auto, ToolApprovalState.Approved),
        )
    }

    @Test
    fun `only the command that applies a pending decision may resume`() {
        assertEquals(true, shouldResumeAfterApproval(appliedPendingDecision = true, hasPendingAfterUpdate = false))
        assertEquals(false, shouldResumeAfterApproval(appliedPendingDecision = false, hasPendingAfterUpdate = false))
        assertEquals(false, shouldResumeAfterApproval(appliedPendingDecision = true, hasPendingAfterUpdate = true))
    }

    @Test
    fun `positive approvals still require exact identity`() {
        // Regression guard for the ask_user answer fix (2026-10-12): the carve-out must stay
        // scoped to answers — plain approvals keep requiring the exact projection identity.
        assertEquals(
            false,
            mayUseLegacyApprovalPath(
                decision = ToolDecision.Approved,
                exactIdentityPresent = false,
                legacyAskUserAnswer = false,
            ),
        )
        assertEquals(
            true,
            mayUseLegacyApprovalPath(
                decision = ToolDecision.Approved,
                exactIdentityPresent = true,
                legacyAskUserAnswer = false,
            ),
        )
    }

    @Test
    fun `plain approval in an ordinary conversation may use the legacy path`() {
        // 2026-10-14（226 / 231 老账）：普通（LOCAL_ASSISTANT）会话两个 persistPendingBarrier*
        // 插入路径都要求 LOCAL_SECOND_USER，因此从不落 pending_tool_approvals 行 → 批准命令必然
        // 不带精确身份。此前一律拒绝，工具调用永远 Pending、整个会话卡死无法继续发送。
        assertEquals(
            true,
            mayUseLegacyApprovalPath(
                decision = ToolDecision.Approved,
                exactIdentityPresent = false,
                legacyAskUserAnswer = false,
                legacyOrdinaryApproval = true,
            ),
        )
        // 未携带该判据时保持 fail-closed（防放宽成"任何批准都放行"）。
        assertEquals(
            false,
            mayUseLegacyApprovalPath(
                decision = ToolDecision.Approved,
                exactIdentityPresent = false,
                legacyAskUserAnswer = false,
                legacyOrdinaryApproval = false,
            ),
        )
        // 明确：判断依据是调用方算出的"APP UI + 无身份 + 该调用仍挂起"，函数本身不看 denied。
        assertEquals(
            true,
            mayUseLegacyApprovalPath(
                decision = ToolDecision.Denied("no"),
                exactIdentityPresent = false,
                legacyAskUserAnswer = false,
            ),
        )
    }

    @Test
    fun `denials and ask_user answers may use the legacy path`() {
        assertEquals(
            true,
            mayUseLegacyApprovalPath(
                decision = ToolDecision.Denied("no"),
                exactIdentityPresent = false,
                legacyAskUserAnswer = false,
            ),
        )
        assertEquals(
            true,
            mayUseLegacyApprovalPath(
                decision = ToolDecision.Answered("""{"answers":{"q1":"ok"}}"""),
                exactIdentityPresent = false,
                legacyAskUserAnswer = true,
            ),
        )
        // An answer without the legacy ask_user context is still gated.
        assertEquals(
            false,
            mayUseLegacyApprovalPath(
                decision = ToolDecision.Answered("""{"answers":{"q1":"ok"}}"""),
                exactIdentityPresent = false,
                legacyAskUserAnswer = false,
            ),
        )
    }
}
