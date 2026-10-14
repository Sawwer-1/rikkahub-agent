package me.rerere.rikkahub.service.chat

import me.rerere.ai.ui.ToolApprovalState

/**
 * Pure, idempotent approval transition used before mutating a conversation.
 * A terminal decision is immutable: repeating it is success, changing it is a conflict.
 */
sealed interface ToolApprovalTransition {
    data class Apply(val state: ToolApprovalState) : ToolApprovalTransition
    data object Idempotent : ToolApprovalTransition
    data object Conflict : ToolApprovalTransition
    data object NotPending : ToolApprovalTransition
}

fun shouldResumeAfterApproval(
    appliedPendingDecision: Boolean,
    hasPendingAfterUpdate: Boolean,
): Boolean = appliedPendingDecision && !hasPendingAfterUpdate

fun resolveToolApproval(
    current: ToolApprovalState,
    requested: ToolApprovalState,
): ToolApprovalTransition = when (current) {
    ToolApprovalState.Pending -> ToolApprovalTransition.Apply(requested)
    ToolApprovalState.Approved ->
        if (requested == ToolApprovalState.Approved) ToolApprovalTransition.Idempotent
        else ToolApprovalTransition.Conflict
    is ToolApprovalState.Denied ->
        if (requested is ToolApprovalState.Denied && requested.reason == current.reason) {
            ToolApprovalTransition.Idempotent
        } else ToolApprovalTransition.Conflict
    is ToolApprovalState.Answered ->
        if (requested == current) ToolApprovalTransition.Idempotent
        else ToolApprovalTransition.Conflict
    ToolApprovalState.Auto -> ToolApprovalTransition.NotPending
}

/**
 * Exact-identity gate for approval commands: positive decisions must carry the exact
 * projection identity created with the pending barrier, so a replayed payload can never
 * approve a newer execution. Two carve-outs may use the conservative legacy path:
 *
 *  - Denials: they grant nothing and can only mark a decision.
 *  - ask_user answers ([me.rerere.rikkahub.service.chat.ToolDecision.Answered] for an
 *    ask_user tool): an answer is user input, not a permission grant, and the inline
 *    executor re-verifies the toolCallId is still Pending before mutating anything.
 *    Device evidence 2026-10-12: normal (LOCAL_ASSISTANT) conversations never persist an
 *    approval projection, so without this carve-out every ask_user confirmation was
 *    silently rejected as `approval_exact_identity_required` and the question card stayed
 *    pending forever.
 *  - [legacyOrdinaryApproval]: a plain tool approval tapped in the app's own UI
 *    (origin = APP_UI) in a **non-second-user** conversation. Those conversations never
 *    persist an approval projection either —— both `persistPendingBarrier*` insert paths
 *    `require(subjectType == LOCAL_SECOND_USER)` —— so a positive decision can never carry
 *    the exact identity. Device evidence 2026-10-13/14: the approval card appeared but
 *    every 确认 was rejected as `Approval exact identity is required`, and because the
 *    rejected command left the tool call Pending, the whole conversation deadlocked
 *    (could not send anything further) whenever global auto-approve was off. The real gate
 *    stays the message-graph transition in the inline executor, which only ever applies to
 *    a still-Pending part of the very same toolCallId.
 */
fun mayUseLegacyApprovalPath(
    decision: ToolDecision,
    exactIdentityPresent: Boolean,
    legacyAskUserAnswer: Boolean,
    legacyOrdinaryApproval: Boolean = false,
): Boolean = decision is ToolDecision.Denied ||
    exactIdentityPresent ||
    legacyAskUserAnswer ||
    legacyOrdinaryApproval
