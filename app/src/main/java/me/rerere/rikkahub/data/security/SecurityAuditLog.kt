package me.rerere.rikkahub.data.security

import android.content.Context
import android.util.Log
import java.io.File
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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

        /**
         * 超过容量这么多条才重写整份文件（2026-10-15 修）。原实现是「文件一到 500 条，之后每条记录都
         * 全量重写」——单次审计写要重排 500 行，而观察者链对每个观察者套了 `withTimeoutOrNull`，
         * 慢下来就会被静默跳过、记录直接丢（自检 §26「一批调用只落了 web_fetch 一条」即此）。
         * 现在每条记录只做一次 append，攒够 MAX+SLACK 才 compact 一次。
         */
        private const val COMPACT_SLACK = 40
        private const val RETAIN_DAYS = 30L
        private val RETAIN_MS = RETAIN_DAYS * 24 * 60 * 60 * 1000L
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val _entries = MutableStateFlow<List<SecurityAuditEntry>>(emptyList())

    /**
     * 文件里的大致行数。内存镜像 [entries] 被裁到 MAX_ENTRIES，用它的 size 判断不了文件是否该压缩。
     * 只在持锁时读写。
     */
    private var fileLineCount = 0

    /** Newest first. Backed by the in-memory mirror of the JSONL file. */
    val entries: StateFlow<List<SecurityAuditEntry>> = _entries.asStateFlow()

    @Volatile
    private var loaded = false

    private fun auditDir(): File = File(context.filesDir, "audit").apply { mkdirs() }

    private fun auditFile(): File = File(auditDir(), "security_audit.jsonl")

    /** Loads (and lazily compacts) the file into memory. Callers hold [mutex]. */
    private fun loadLocked() {
        val file = auditFile()
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
        // 按 timestamp 选保留集（不信任行序：历史版本的 compaction 会把文件写成倒序）。
        val kept = selectRetainedAuditEntriesNewestFirst(
            parsed = parsed,
            nowMs = System.currentTimeMillis(),
            retentionMs = RETAIN_MS,
            maxEntries = MAX_ENTRIES,
        )
        fileLineCount = parsed.size
        if (kept.size != parsed.size) {
            runCatching { rewriteLocked(auditEntriesInFileOrder(kept)) }
                .onFailure { Log.w(TAG, "security audit compaction failed", it) }
            fileLineCount = kept.size
        }
        _entries.value = kept
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
                // 观察者链对每个观察者套了 withTimeoutOrNull(observerTimeoutMs = 500ms)：超时即取消，
                // 而 append 被取消会留下**半行**（自检 2026-10-15 §6 实测到「无 timestamp 的头部条目」），
                // 那半行下次装载解析失败即被丢弃 —— 表现为「调用发生了但审计里没有」。
                // 这里让写入不可取消：它是落盘的最后一步，慢一点也要写完。
                withContext(NonCancellable + Dispatchers.IO) {
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
                    fileLineCount++
                    _entries.value = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
                    if (fileLineCount >= MAX_ENTRIES + COMPACT_SLACK) compactLocked()
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
        // 内存镜像已是「新在前」，按时间淘汰后仍保持「新在前」；写盘时反向成旧→新。
        val kept = selectRetainedAuditEntriesNewestFirst(
            parsed = _entries.value.asReversed(),
            nowMs = System.currentTimeMillis(),
            retentionMs = RETAIN_MS,
            maxEntries = MAX_ENTRIES,
        )
        runCatching { rewriteLocked(auditEntriesInFileOrder(kept)) }
            .onFailure { Log.w(TAG, "security audit compaction failed", it) }
        _entries.value = kept
        fileLineCount = kept.size
    }
}

/**
 * 保留策略（纯函数，便于单测）：按时间窗淘汰 + 只留最近 [maxEntries] 条，返回「新在前」。
 *
 * [parsed] 传**文件行序**（旧→新）；本函数按 `timestamp` 自行排序，不信任行序——历史版本的
 * compaction 会把文件写成倒序（新→旧），若按行序取尾就会丢新记录（2026-10-15 修）。
 */
internal fun selectRetainedAuditEntriesNewestFirst(
    parsed: List<SecurityAuditEntry>,
    nowMs: Long,
    retentionMs: Long,
    maxEntries: Int,
): List<SecurityAuditEntry> = parsed
    .filter { it.timestamp >= nowMs - retentionMs }
    .sortedBy { it.timestamp }
    .takeLast(maxEntries)
    .asReversed()

/** 写盘顺序：旧 → 新，保证 append-only 文件按时间递增，外部用 tail 也能看到最新记录。 */
internal fun auditEntriesInFileOrder(newestFirst: List<SecurityAuditEntry>): List<SecurityAuditEntry> =
    newestFirst.asReversed()

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
