package me.rerere.rikkahub.data.model

import java.time.Instant
import kotlin.uuid.Uuid

/**
 * 会话图写入的两道防守（B03 / R2·R3）。
 *
 * 背景（思维链工单 问题二-3）：246/247 只做到「取消不删消息、不回滚整图」，但取消路径仍然
 * 拿内存快照整图回写，只是加了一条 W 日志。`updateRuntimeConversation` 的语义是「以调用方的
 * 图为准」，调用方递进来一份更短的图，库侧节点就静默消失——日志只能事后取证，不能拦住。
 *
 * 两个方向各一道：
 * - **R2**：[applyNodeLevelMutation] 让清理类写入只能按 id 替换库里已有的节点，节点数由构造
 *   保证不减少（增删都不被采纳）。
 * - **R3**：[isStaleGraphWrite] 拦截「既更旧又丢节点」的整图写入；`force` 是合法收缩的逃生门。
 */

/**
 * 节点级写入的骨架合并（R2）。
 *
 * 规则：**库里的节点序列是唯一骨架**，同名 id 用 [mutated] 里的版本覆盖，其余一字不改；
 * [mutated] 里多出来的节点（内存里有、库里没有）**一律丢弃**——清理路径不该产生新节点，
 * 未落库的消息交给正常保存路径，不能借取消清理偷偷插进库图。
 *
 * 因此「取消后库图节点数不减少」是构造保证，不依赖调用方是否 hydrate 成功。
 */
internal fun applyNodeLevelMutation(
    stored: List<MessageNode>,
    mutated: List<MessageNode>,
): List<MessageNode> {
    if (stored.isEmpty() || mutated.isEmpty()) return stored
    val mutatedById = mutated.associateBy { it.id }
    return stored.map { node -> mutatedById[node.id] ?: node }
}

/**
 * 是否该拒绝这次图写入（R3）。
 *
 * 只有同时满足三条才拒：不是 force、**入参比库里更旧**、并且**丢掉了库里已有的节点**。
 *
 * 判据刻意收紧：
 * - 只认严格更旧（`incomingUpdatedAt < storedUpdatedAt`），所以「同一时刻的删除消息」这类
 *   合法收缩不会被误拦（内存态不会主动 bump updateAt）。
 * - 只拒「又旧又短」：新图再短也不算问题——那是用户删消息/重新生成截断；旧图再短才是危险信号
 *   （取消时拿着调用前的快照回写）。
 */
internal fun isStaleGraphWrite(
    storedNodeIds: Collection<Uuid>,
    storedUpdatedAt: Instant,
    incomingNodeIds: Collection<Uuid>,
    incomingUpdatedAt: Instant,
    force: Boolean = false,
): Boolean {
    if (force) return false
    if (incomingUpdatedAt >= storedUpdatedAt) return false
    return !incomingNodeIds.toSet().containsAll(storedNodeIds)
}
