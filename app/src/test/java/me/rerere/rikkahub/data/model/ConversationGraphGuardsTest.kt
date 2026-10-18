package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * B03 / R2·R3 的两道防守。
 *
 * 验收点：「取消后库图节点数不减少」——R2 靠节点级写入由构造保证，R3 靠拒绝「更旧 + 丢节点」
 * 的整图写入兜底。这里只测纯函数，不碰 Room（CI 只跑 JVM 单测）。
 */
class ConversationGraphGuardsTest {
    @Test
    fun `node level mutation replaces stored nodes by id and never adds or removes`() {
        val first = node("first")
        val second = node("second")
        val third = node("third")
        val editedSecond = second.copy(selectIndex = 0)
        // 内存里有一份库里还没有的节点（未落库的新消息）：清理路径不该把它塞进库图。
        val unsaved = node("unsaved")

        val merged = applyNodeLevelMutation(
            stored = listOf(first, second, third),
            mutated = listOf(editedSecond, unsaved),
        )

        // 节点数与顺序都以库里的为准：取消清理只可能改内容，不可能让历史变短或变长。
        assertEquals(listOf(first.id, second.id, third.id), merged.map { it.id })
        assertEquals(editedSecond, merged[1])
        assertEquals(3, merged.size)
    }

    @Test
    fun `node level mutation keeps the stored order when the caller reorders`() {
        val first = node("first").copy(selectIndex = 0)
        val second = node("second").copy(selectIndex = 0)

        val merged = applyNodeLevelMutation(
            stored = listOf(first, second),
            mutated = listOf(second.copy(isFavorite = true), first.copy(isFavorite = true)),
        )

        assertEquals(listOf(first.id, second.id), merged.map { it.id })
        assertTrue(merged.all { it.isFavorite })
    }

    @Test
    fun `older write that drops stored nodes is rejected`() {
        val storedIds = listOf(Uuid.random(), Uuid.random(), Uuid.random())
        val now = Instant.parse("2026-10-18T02:00:00Z")

        assertTrue(
            isStaleGraphWrite(
                storedNodeIds = storedIds,
                storedUpdatedAt = now,
                // 取消时拿着「调用前」的快照回写：既更旧又短一条。
                incomingNodeIds = storedIds.dropLast(1),
                incomingUpdatedAt = now.minusSeconds(30),
            ),
        )
    }

    @Test
    fun `newer truncation stays allowed`() {
        val storedIds = listOf(Uuid.random(), Uuid.random(), Uuid.random())
        val now = Instant.parse("2026-10-18T02:00:00Z")

        // 用户删消息 / 重新生成截断：图变短但时间戳更新，属于合法收缩。
        assertFalse(
            isStaleGraphWrite(
                storedNodeIds = storedIds,
                storedUpdatedAt = now,
                incomingNodeIds = storedIds.dropLast(1),
                incomingUpdatedAt = now.plusSeconds(1),
            ),
        )
        // 时间戳相同（内存态不主动 bump）也不拦，避免误伤「同一时刻的删除」。
        assertFalse(
            isStaleGraphWrite(
                storedNodeIds = storedIds,
                storedUpdatedAt = now,
                incomingNodeIds = storedIds.dropLast(1),
                incomingUpdatedAt = now,
            ),
        )
    }

    @Test
    fun `older write that keeps every stored node stays allowed`() {
        val storedIds = listOf(Uuid.random(), Uuid.random())
        val now = Instant.parse("2026-10-18T02:00:00Z")

        // 只改内容、不丢节点：这是取消清理/流式收尾的正常形态，不能拦。
        assertFalse(
            isStaleGraphWrite(
                storedNodeIds = storedIds,
                storedUpdatedAt = now,
                incomingNodeIds = storedIds,
                incomingUpdatedAt = now.minusSeconds(30),
            ),
        )
    }

    @Test
    fun `force bypasses the stale guard`() {
        val storedIds = listOf(Uuid.random(), Uuid.random())
        val now = Instant.parse("2026-10-18T02:00:00Z")

        assertFalse(
            isStaleGraphWrite(
                storedNodeIds = storedIds,
                storedUpdatedAt = now,
                incomingNodeIds = storedIds.take(1),
                incomingUpdatedAt = now.minusSeconds(60),
                force = true,
            ),
        )
    }

    private fun node(label: String): MessageNode = MessageNode(
        messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text(label)),
            ),
        ),
    )
}
