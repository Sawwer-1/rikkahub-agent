package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.abandonUnresolvedToolCalls
import me.rerere.ai.ui.canResumeToolExecution

/**
 * 对一个消息节点做「未完成工具就地放弃」（2026-10-17 B01 / R25）。
 *
 * 从 `ChatService.checkInvalidMessages` 抽出来，原因有二：
 * 1. **可测**：节点可能有多版本（用户编辑过 = 消息下方那个 2/2），必须只动**当前激活版本**，
 *    其它版本一字不改。这段逻辑此前内联在 `ChatService` 里，没有单测覆盖「当前激活版本非原始版本」
 *    这条路径（246 的 5 例只测了单版本）。
 * 2. **单一事实来源**：判据（有没有未执行工具 / 能不能 resume）只在这里写一次。
 *
 * 语义：
 * - 没有未执行工具 → 返回**同一实例**（调用方据此跳过整图回写）；
 * - 存在可 resume 的工具（Approved / Answered 等，等用户审批或可续跑）→ 原样返回，不碰；
 * - 否则放弃当前激活版本的未完成工具（写取消回执 + Denied），**同一节点的其它版本不动**。
 */
internal fun MessageNode.abandonUnresolvedToolsIfNotResumable(): MessageNode {
    val message = currentMessage
    val unresolvedTools = message.getTools().filter { !it.isExecuted }
    if (unresolvedTools.isEmpty()) {
        return this
    }
    // Keep messages that are ready to resume, such as approved/denied/answered tools.
    if (unresolvedTools.any { it.approvalState.canResumeToolExecution() }) {
        return this
    }
    val abandoned = message.abandonUnresolvedToolCalls()
    if (abandoned == message) {
        return this
    }
    return copy(
        messages = messages.map { branch ->
            if (branch.id == message.id) abandoned else branch
        },
    )
}
