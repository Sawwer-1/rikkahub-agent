package me.rerere.rikkahub.data.security

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * CSV 表头。列顺序即为导出顺序，[securityAuditCsv] 与替换测试都以此为准。
 */
internal const val SECURITY_AUDIT_CSV_HEADER = "timestamp,category,action,target,detail,status,sensitive"

/**
 * 安全审计的 CSV 生成（纯函数，便于单测；无 Android / IO 依赖）。
 *
 * 约定：
 * - `timestamp` 列导出为 **ISO-8601 本地时区**字符串（`yyyy-MM-dd'T'HH:mm:ssXXX`，例：`2026-10-15T21:04:07+08:00`）。
 *   内存里的 [SecurityAuditEntry.timestamp] 是 epoch 毫秒，直接导出人不可读、且跨时区易误读；转换只用
 *   `java.time`，不引入新依赖。（备选方案 epoch 毫秒被放弃：可读性差。）
 * - 字段转义遵循 RFC 4180：字段含 `,`、`"`、`\n`、`\r` 时用双引号包裹，内部的 `"` 双写成 `""`。
 * - 行分隔符用 `\n`（RFC 4180 允许 CRLF；Android 端既有导出以 `\n` 为主，且被引号包裹的换行不受影响）。
 */
internal fun securityAuditCsv(entries: List<SecurityAuditEntry>): String =
    (listOf(SECURITY_AUDIT_CSV_HEADER) + entries.map(::securityAuditCsvRow)).joinToString("\n")

/**
 * 导出文件名：`security_audit_<yyyyMMdd_HHmmss>.csv`。抽成纯函数以便单测且不依赖设备时钟。
 */
internal fun securityAuditCsvFileName(now: LocalDateTime = LocalDateTime.now()): String =
    "security_audit_${now.format(CSV_FILE_NAME_FORMAT)}.csv"

private fun securityAuditCsvRow(entry: SecurityAuditEntry): String = listOf(
    formatAuditTimestamp(entry.timestamp),
    entry.category,
    entry.action,
    entry.target,
    entry.detail,
    entry.status,
    entry.sensitive.toString(),
).joinToString(",") { csvField(it) }

/** RFC 4180 字段转义：仅当含分隔符 / 引号 / 换行时才包裹。 */
private fun csvField(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + value.replace("\"", "\"\"") + "\""
    } else {
        value
    }

private fun formatAuditTimestamp(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(CSV_TIMESTAMP_FORMAT)

private val CSV_TIMESTAMP_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

private val CSV_FILE_NAME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)
