package me.rerere.rikkahub.data.security

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 审计滚动日志的保留策略契约（2026-10-15 修 §26 引出的两处缺陷）：
 * 1. 满了之后**不能**每次记录都全量重写文件（写慢 → 观察者被超时跳过 → 丢记录）；
 * 2. 顺序必须自洽——内存「新在前」、文件「旧→新」，且选择保留集要按 timestamp 而不是行序
 *    （旧实现把「新在前」直接写盘，文件被写成倒序，重装时按行序取尾会丢最新记录）。
 */
class SecurityAuditLogRetentionTest {
    private fun entry(at: Long) = SecurityAuditEntry(
        id = "e$at",
        timestamp = at,
        category = "tool",
        action = "execute",
        target = "demo",
        status = "success",
    )

    @Test
    fun `retention keeps the newest entries and returns them newest first`() {
        val parsed = (1L..10L).map(::entry)
        val kept = selectRetainedAuditEntriesNewestFirst(
            parsed = parsed,
            nowMs = 10L,
            retentionMs = 1_000L,
            maxEntries = 3,
        )
        assertEquals(listOf(10L, 9L, 8L), kept.map { it.timestamp })
    }

    @Test
    fun `entries older than the retention window are dropped`() {
        // 截止线 = nowMs - retentionMs = 100 - 60 = 40：只淘汰 1，保留 50 与 100。
        val parsed = listOf(entry(1L), entry(50L), entry(100L))
        val kept = selectRetainedAuditEntriesNewestFirst(
            parsed = parsed,
            nowMs = 100L,
            retentionMs = 60L,
            maxEntries = 10,
        )
        assertEquals(listOf(100L, 50L), kept.map { it.timestamp })
    }

    @Test
    fun `file order helper writes oldest first so appends stay chronological`() {
        val newestFirst = listOf(entry(3L), entry(2L), entry(1L))
        assertEquals(
            listOf(1L, 2L, 3L),
            auditEntriesInFileOrder(newestFirst).map { it.timestamp },
        )
    }

    @Test
    fun `out of order file rows are re-sorted instead of trusting line order`() {
        // 历史版本 compaction 后文件是倒序的：按行序取尾会丢掉 9（最新那条）。
        val parsed = listOf(entry(5L), entry(1L), entry(9L), entry(3L))
        val kept = selectRetainedAuditEntriesNewestFirst(
            parsed = parsed,
            nowMs = 9L,
            retentionMs = 1_000L,
            maxEntries = 2,
        )
        assertEquals(listOf(9L, 5L), kept.map { it.timestamp })
    }
}
