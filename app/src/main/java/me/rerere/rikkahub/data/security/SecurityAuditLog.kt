package me.rerere.rikkahub.data.security

import android.content.Context
import android.util.Log
import java.io.File
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.ai.execution.RedactedToolLifecycleEvent
import me.rerere.rikkahub.data.ai.execution.ToolLifecycleObserver
import me.rerere.rikkahub.data.ai.isSensitivePrivilegedTool

/**
 * One security-audit record: a dangerous tool execution, an approval-chain decision, or an
 * external automation trigger. Modeled after the OrangeChat SecurityAuditEntity, but stored
 * as a rolling JSONL file instead of Room — the fork's DB schema is frozen for parallel
 * feature work, so audit storage must not add tables.
 */
@Serializable
data class SecurityAuditEntry(
    val id: String = Uuid.random().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    /** tool / approval / automation / system */
    val category: String,
    /** execute / approval_requested / approved / denied / blocked / run_task */
    val action: String,
    /** Tool name, caller package, or other target identifier. */
    val target: String = "",
    /** Non-sensitive context: origin, reason code, request id. Never tool arguments. */
    val detail: String = "",
    /** started / success / failure / timeout / cancelled / rejected:<reason> … */
    val status: String = "",
    /** True when the target tool is in the sensitive/privileged surface. */
    val sensitive: Boolean = false,
)

/**
 * Rolling-file security audit log. Append-only JSONL under filesDir/audit/, kept to the most
 * recent [MAX_ENTRIES] entries and [RETAIN_DAYS] days, matching the OrangeChat retention
 * policy (500 / 30d) without a database.
 *
 * [record] never throws and never blocks the caller's critical path beyond a small file
 * append on Dispatchers.IO: audit is a sidecar and must not alter tool or approval behavior.
 */
class SecurityAuditRepository(private val context: Context) {
    companion object {
        private const val TAG = "SecurityAudit"
        private const val MAX_ENTRIES = 500
        private const val RETAIN_DAYS = 30L
        private val RETAIN_MS = RETAIN_DAYS * 24 * 60 * 60 * 1000L
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val _entries = MutableStateFlow<List<SecurityAuditEntry>>(emptyList())

    /** Newest first. Backed by the in-memory mirror of the JSONL file. */
    val entries: StateFlow<List<SecurityAuditEntry>> = _entries.asStateFlow()

    @Volatile
    private var loaded = false

    private fun auditDir(): File = File(context.filesDir, "audit").apply { mkdirs() }

    private fun auditFile(): File = File(auditDir(), "security_audit.jsonl")

    /** Loads (and lazily compacts) the file into memory. Callers hold [mutex]. */
    private fun loadLocked() {
        val file = auditFile()
        val cutoff = System.currentTimeMillis() - RETAIN_MS
        val parsed = if (file.exists()) {
            runCatching {
                file.readLines().mapNotNull { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) {
                        null
                    } else {
                        runCatching { json.decodeFromString<SecurityAuditEntry>(trimmed) }.getOrNull()
                    }
                }
            }.onFailure { Log.w(TAG, "security audit file unreadable, starting fresh", it) }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val kept = parsed.filter { it.timestamp >= cutoff }.takeLast(MAX_ENTRIES)
        if (kept.size != parsed.size) {
            runCatching { rewriteLocked(kept) }
                .onFailure { Log.w(TAG, "security audit compaction failed", it) }
        }
        _entries.value = kept.asReversed()
        loaded = true
    }

    suspend fun refresh() {
        mutex.withLock {
            withContext(Dispatchers.IO) { loadLocked() }
        }
    }

    suspend fun record(
        category: String,
        action: String,
        target: String = "",
        detail: String = "",
        status: String = "",
        sensitive: Boolean = false,
    ) {
        runCatching {
            mutex.withLock {
                withContext(Dispatchers.IO) {
                    if (!loaded) loadLocked()
                    val entry = SecurityAuditEntry(
                        category = category,
                        action = action,
                        target = target.take(200),
                        detail = detail.take(500),
                        status = status.take(120),
                        sensitive = sensitive,
                    )
                    auditFile().appendText(json.encodeToString(entry) + "\n")
                    val updated = listOf(entry) + _entries.value
                    _entries.value = if (updated.size > MAX_ENTRIES) {
                        updated.take(MAX_ENTRIES)
                    } else {
                        updated
                    }
                    if (updated.size >= MAX_ENTRIES) compactLocked()
                }
            }
        }.onFailure { Log.w(TAG, "security audit write failed", it) }
    }

    suspend fun clearAll() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching { auditFile().delete() }
                    .onFailure { Log.w(TAG, "security audit clear failed", it) }
                _entries.value = emptyList()
            }
        }
    }

    /** Rewrite the file keeping only the newest [MAX_ENTRIES] in-retention entries. */
    private fun rewriteLocked(entries: List<SecurityAuditEntry>) {
        val tmp = File(auditDir(), "security_audit.jsonl.tmp")
        tmp.bufferedWriter().use { writer ->
            entries.forEach { entry ->
                writer.write(json.encodeToString(entry))
                writer.newLine()
            }
        }
        val dst = auditFile()
        if (dst.exists()) dst.delete()
        if (!tmp.renameTo(dst)) {
            Log.w(TAG, "security audit compaction rename failed")
            tmp.delete()
        }
    }

    private fun compactLocked() {
        val cutoff = System.currentTimeMillis() - RETAIN_MS
        val kept = _entries.value.filter { it.timestamp >= cutoff }.takeLast(MAX_ENTRIES)
        runCatching { rewriteLocked(kept) }
            .onFailure { Log.w(TAG, "security audit compaction failed", it) }
        _entries.value = kept.asReversed()
    }
}

/**
 * Tool-execution audit sink wired into [me.rerere.rikkahub.data.ai.execution.DefaultToolRuntime]'s
 * observer list. Records ONE entry per execution attempt at its terminal phase — the runtime
 * emits FAILED for gate/interceptor rejections too, so "blocked" calls are captured without
 * touching the execution path itself. Exceptions are swallowed by [SecurityAuditRepository.record].
 */
class SecurityAuditToolObserver(
    private val repository: SecurityAuditRepository,
) : ToolLifecycleObserver {
    override suspend fun onEvent(event: RedactedToolLifecycleEvent) {
        val ctx = event.context
        val sensitive = isSensitivePrivilegedTool(ctx.toolName) || isSensitiveToolName(ctx.toolName)
        val baseDetail = "origin=${ctx.origin.name} run=${ctx.runId}"
        when (event.phase) {
            RedactedToolLifecycleEvent.Phase.COMPLETED -> repository.record(
                category = CATEGORY_TOOL,
                action = "execute",
                target = ctx.toolName,
                detail = baseDetail,
                status = "success",
                sensitive = sensitive,
            )
            RedactedToolLifecycleEvent.Phase.FAILED -> repository.record(
                category = CATEGORY_TOOL,
                action = "execute",
                target = ctx.toolName,
                detail = listOfNotNull(
                    baseDetail,
                    event.detail?.let { "code=$it" },
                ).joinToString(" "),
                status = "failure",
                sensitive = sensitive,
            )
            RedactedToolLifecycleEvent.Phase.TIMED_OUT -> repository.record(
                category = CATEGORY_TOOL,
                action = "execute",
                target = ctx.toolName,
                detail = baseDetail,
                status = "timeout",
                sensitive = sensitive,
            )
            RedactedToolLifecycleEvent.Phase.CANCELLED -> repository.record(
                category = CATEGORY_TOOL,
                action = "execute",
                target = ctx.toolName,
                detail = baseDetail,
                status = "cancelled",
                sensitive = sensitive,
            )
            else -> Unit
        }
    }

    companion object {
        const val CATEGORY_TOOL = "tool"
        const val CATEGORY_APPROVAL = "approval"
        const val CATEGORY_AUTOMATION = "automation"
    }
}

/** Keyword heuristic over tool names for the audit "sensitive" flag (shell/sms/a11y/…). */
private val SENSITIVE_NAME_KEYWORDS = listOf(
    "termux", "ssh", "shell", "exec", "delete", "remove", "write",
    "sms", "phone", "notification", "accessibility", "browser", "chrome",
    "screenshot", "install", "uninstall", "click", "input_", "gesture",
    "clipboard", "contact", "location", "camera", "microphone", "keystore",
)

internal fun isSensitiveToolName(toolName: String): Boolean {
    val lowered = toolName.lowercase()
    return SENSITIVE_NAME_KEYWORDS.any { lowered.contains(it) }
}
