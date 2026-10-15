package me.rerere.rikkahub.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CSV 导出的契约：表头固定、RFC 4180 转义、行序与输入一致。
 */
class SecurityAuditCsvTest {
    private fun entry(
        id: String = "id",
        timestamp: Long = 1_700_000_000_000L,
        category: String = "tool",
        action: String = "execute",
        target: String = "read_file",
        detail: String = "",
        status: String = "success",
        sensitive: Boolean = false,
    ) = SecurityAuditEntry(id, timestamp, category, action, target, detail, status, sensitive)

    @Test
    fun `empty list yields the header only`() {
        assertEquals(SECURITY_AUDIT_CSV_HEADER, securityAuditCsv(emptyList()))
    }

    @Test
    fun `header keeps the documented column order`() {
        assertEquals(
            "timestamp,category,action,target,detail,status,sensitive",
            SECURITY_AUDIT_CSV_HEADER,
        )
    }

    @Test
    fun `plain fields are not quoted`() {
        val row = securityAuditCsv(listOf(entry(target = "read_file"))).substringAfter('\n')

        assertTrue(row, ",read_file," in row)
        assertFalse(row, "\"read_file\"" in row)
    }

    @Test
    fun `comma inside a field is quoted`() {
        val csv = securityAuditCsv(listOf(entry(detail = "origin=LocalChat, run=abc")))

        assertTrue(csv, "\"origin=LocalChat, run=abc\"" in csv)
    }

    @Test
    fun `quote inside a field is doubled and wrapped`() {
        val csv = securityAuditCsv(listOf(entry(target = "say \"hi\"")))

        assertTrue(csv, "\"say \"\"hi\"\"\"" in csv)
    }

    @Test
    fun `newline inside a field keeps the record quoted`() {
        val csv = securityAuditCsv(listOf(entry(detail = "line1\nline2")))

        assertTrue(csv, "\"line1\nline2\"" in csv)
    }

    @Test
    fun `rows keep the input order, one row per entry`() {
        val csv = securityAuditCsv(
            listOf(
                entry(id = "a", target = "first", status = "success"),
                entry(id = "b", target = "second", status = "failure"),
            ),
        )

        val rows = csv.lines().drop(1)
        assertEquals(2, rows.size)
        assertTrue(rows[0], ",first," in rows[0])
        assertTrue(rows[1], ",second," in rows[1])
    }

    @Test
    fun `sensitive flag is exported as a boolean literal`() {
        val csv = securityAuditCsv(listOf(entry(sensitive = true)))

        assertTrue(csv, csv.trimEnd().endsWith(",true"))
    }

    @Test
    fun `file name follows the security_audit timestamp convention`() {
        val name = securityAuditCsvFileName(java.time.LocalDateTime.of(2026, 10, 15, 21, 4, 7))

        assertEquals("security_audit_20261015_210407.csv", name)
    }
}
