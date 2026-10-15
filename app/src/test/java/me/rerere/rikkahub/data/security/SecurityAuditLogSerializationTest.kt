package me.rerere.rikkahub.data.security

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审计行的序列化契约。
 *
 * 2026-10-15 自检 238 §1 实测：文件满 500 条后，新写入的行**整条 JSON 完整但 `timestamp` 字段失踪**
 * （501 行起 13 条无 TS，`status`/`target` 等非默认值字段照常写出）。根因是 `Json` 默认
 * `encodeDefaults = false`，而 `SecurityAuditEntry.id` / `timestamp` 只由构造器默认值填充 ——
 * 于是「等于默认值」的字段被省略。修复见 [AUDIT_JSON]（`encodeDefaults = true`）与 `record()` 的
 * 显式传参，本测试把"每行都必须带 id 与 timestamp"钉死，避免同类字段级丢失再复发。
 */
class SecurityAuditLogSerializationTest {
    private fun defaultedEntry() = SecurityAuditEntry(
        category = "tool",
        action = "execute",
        target = "read_file",
        detail = "origin=LocalChat run=abc",
        status = "success",
    )

    @Test
    fun `every encoded row carries id and timestamp even when they came from constructor defaults`() {
        val line = AUDIT_JSON.encodeToString(defaultedEntry())

        assertTrue("row must contain id: $line", "\"id\":" in line)
        assertTrue("row must contain timestamp: $line", "\"timestamp\":" in line)
    }

    @Test
    fun `explicit id and timestamp survive the round trip unchanged`() {
        val original = SecurityAuditEntry(
            id = "fixed-id",
            timestamp = 1_700_000_000_000L,
            category = "approval",
            action = "approval_requested",
            target = "ask_user",
            detail = "origin=LocalChat conversation=x",
            status = "pending",
            sensitive = true,
        )

        val decoded = AUDIT_JSON.decodeFromString<SecurityAuditEntry>(
            AUDIT_JSON.encodeToString(original),
        )

        assertEquals(original, decoded)
    }

    @Test
    fun `legacy rows written without a timestamp still decode`() {
        // 238 及更早写出的行可能整字段缺 timestamp；解码必须容忍，否则装载会把这些行当坏行丢掉
        // （mapNotNull + runCatching 的既有容错路径）。
        val legacy =
            """{"id":"legacy","category":"tool","action":"execute","target":"read_file","status":"success"}"""

        val decoded = AUDIT_JSON.decodeFromString<SecurityAuditEntry>(legacy)

        assertEquals("legacy", decoded.id)
        assertEquals("read_file", decoded.target)
        assertEquals("success", decoded.status)
    }
}
