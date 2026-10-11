package me.rerere.rikkahub.memory.dreaming.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.model.requireCanonicalDreamRunId
import me.rerere.rikkahub.memory.dreaming.runtime.DreamNetworkPolicy
import me.rerere.rikkahub.memory.dreaming.runtime.DreamingCostPolicy

enum class DreamSynthesisScanReason {
    AUTHORITY_COMMIT,
    STARTUP,
    PERIODIC,
    SETTINGS_CHANGED,
    COST_POLICY_CHANGED,
    UTC_BUDGET_ROLLOVER,
    APP_IDLE_RECHECK,
    FOLLOW_UP,
    /** User-invoked "dream now" from the memory settings page; bypasses the idle gate. */
    MANUAL,
}

/** Best-effort scheduling; durable dirty epochs and pending run rows remain the authority. */
interface DreamSynthesisWorkScheduler {
    fun enqueueScope(
        scopeId: DreamScopeId,
        runId: String,
        policy: DreamingCostPolicy,
        replaceExisting: Boolean = false,
        bypassIdle: Boolean = false,
    )

    /**
     * The runtime already waited until the exact persisted idle deadline before requesting this
     * scan. Reapplying the full idle threshold here would double every configured wait.
     */
    fun enqueueScopeAfterIdleRecheck(
        scopeId: DreamScopeId,
        runId: String,
        policy: DreamingCostPolicy,
        replaceExisting: Boolean = false,
    ) = enqueueScope(scopeId, runId, policy, replaceExisting)

    fun cancelScope(scopeId: DreamScopeId)

    fun enqueueDirtyScan(
        reason: DreamSynthesisScanReason,
        earliestAtEpochMs: Long? = null,
    )

    fun armRecoveryScans()

    /** Cancels synthesis-only scan work when no scope permits generation. */
    fun disarmRecoveryScans()
}

class AndroidDreamSynthesisWorkScheduler(
    context: Context,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) : DreamSynthesisWorkScheduler {
    private val workManager = WorkManager.getInstance(context)

    override fun enqueueScope(
        scopeId: DreamScopeId,
        runId: String,
        policy: DreamingCostPolicy,
        replaceExisting: Boolean,
        bypassIdle: Boolean,
    ) = enqueueScopeInternal(
        scopeId = scopeId,
        runId = runId,
        policy = policy,
        replaceExisting = replaceExisting,
        initialDelayMsOverride = null,
        bypassIdle = bypassIdle,
    )

    override fun enqueueScopeAfterIdleRecheck(
        scopeId: DreamScopeId,
        runId: String,
        policy: DreamingCostPolicy,
        replaceExisting: Boolean,
    ) = enqueueScopeInternal(
        scopeId = scopeId,
        runId = runId,
        policy = policy,
        replaceExisting = replaceExisting,
        initialDelayMsOverride = 0L,
    )

    private fun enqueueScopeInternal(
        scopeId: DreamScopeId,
        runId: String,
        policy: DreamingCostPolicy,
        replaceExisting: Boolean,
        initialDelayMsOverride: Long?,
        bypassIdle: Boolean = false,
    ) {
        val validatedPolicy = requireNotNull(policy.validatedOrNull()) {
            "Invalid Dreaming cost policy"
        }
        val plan = dreamSynthesisScopeWorkPlan(scopeId, runId, replaceExisting)
        // 2026-10-11 主公令：手动「立即做梦」必须真立即。此前 bypassIdle 只写进 payload
        // （运行时跳 idle 门），WorkManager 层却仍带 idleThresholdMinutes 初始延迟与
        // 充电/非计量网络硬约束——未插电时排队中的 Work 永不执行，按钮点了毫无反应。
        // 手动通道：零延迟 + 仅保留联网约束（无网调不了模型），充电/电池/计量门交由
        // 运行时按 bypassEnvironment 裁决，额度门原样保留。
        val constraints = if (bypassIdle) {
            dreamSynthesisManualConstraintPlan(validatedPolicy)
        } else {
            dreamSynthesisConstraintPlan(validatedPolicy)
        }
        dreamSynthesisLegacyScopeWorkNames(scopeId).forEach { legacyName ->
            workManager.cancelUniqueWork(legacyName)
        }
        val request = OneTimeWorkRequestBuilder<DreamSynthesisWorker>()
            .addTag(DREAM_SYNTHESIS_SCOPE_WORK_TAG)
            .setConstraints(constraints.toWorkConstraints())
            .setInitialDelay(
                initialDelayMsOverride
                    ?: TimeUnit.MINUTES.toMillis(
                        if (bypassIdle) 0L else constraints.initialDelayMinutes.toLong(),
                    ),
                TimeUnit.MILLISECONDS,
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                DREAM_SYNTHESIS_RETRY_BACKOFF_SECONDS,
                TimeUnit.SECONDS,
            )
            .setInputData(
                Data.Builder()
                    // Identity only. Policy, scope state, model, and Memory stay out of WorkManager.
                    .putString(DreamSynthesisWorker.KEY_SCOPE_ID, plan.scopeId.value)
                    .putString(DreamSynthesisWorker.KEY_RUN_ID, plan.runId)
                    .putBoolean(DreamSynthesisWorker.KEY_BYPASS_IDLE, bypassIdle)
                    .putBoolean(DreamSynthesisWorker.KEY_BYPASS_ENVIRONMENT, bypassIdle)
                    .build(),
            )
            .build()
        workManager.enqueueUniqueWork(plan.uniqueWorkName, plan.policy, request)
    }

    override fun cancelScope(scopeId: DreamScopeId) {
        dreamSynthesisAllScopeWorkNames(scopeId).forEach { workName ->
            workManager.cancelUniqueWork(workName)
        }
    }

    override fun enqueueDirtyScan(
        reason: DreamSynthesisScanReason,
        earliestAtEpochMs: Long?,
    ) {
        val now = nowEpochMs()
        val delayMs = when {
            earliestAtEpochMs == null -> DREAM_SYNTHESIS_SCAN_KICK_DELAY_MS
            now < 0L -> return
            earliestAtEpochMs <= now -> 0L
            else -> try {
                Math.subtractExact(earliestAtEpochMs, now)
            } catch (_: ArithmeticException) {
                return
            }
        }
        workManager.enqueueUniqueWork(
            when (reason) {
                DreamSynthesisScanReason.UTC_BUDGET_ROLLOVER -> UTC_ROLLOVER_SCAN_WORK_NAME
                else -> DIRTY_SCAN_KICK_WORK_NAME
            },
            dreamSynthesisScanExistingPolicy(reason),
            dirtyScanRequest(reason, delayMs),
        )
    }

    override fun armRecoveryScans() {
        workManager.enqueueUniqueWork(
            STARTUP_SCAN_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            dirtyScanRequest(DreamSynthesisScanReason.STARTUP, 0L),
        )
        val periodic = PeriodicWorkRequestBuilder<DreamSynthesisSweepWorker>(
            PERIODIC_SCAN_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setInitialDelay(PERIODIC_SCAN_INTERVAL_HOURS, TimeUnit.HOURS)
            .setInputData(
                Data.Builder()
                    .putString(
                        DreamSynthesisSweepWorker.KEY_SCAN_REASON,
                        DreamSynthesisScanReason.PERIODIC.name,
                    )
                    .build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                DREAM_SYNTHESIS_RETRY_BACKOFF_SECONDS,
                TimeUnit.SECONDS,
            )
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_SCAN_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic,
        )
    }

    override fun disarmRecoveryScans() {
        dreamSynthesisRecoveryWorkNames().forEach(workManager::cancelUniqueWork)
        workManager.cancelAllWorkByTag(DREAM_SYNTHESIS_SCOPE_WORK_TAG)
    }

    private fun dirtyScanRequest(reason: DreamSynthesisScanReason, delayMs: Long) =
        OneTimeWorkRequestBuilder<DreamSynthesisSweepWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                DREAM_SYNTHESIS_RETRY_BACKOFF_SECONDS,
                TimeUnit.SECONDS,
            )
            .setInputData(
                Data.Builder()
                    .putString(DreamSynthesisSweepWorker.KEY_SCAN_REASON, reason.name)
                    .build(),
            )
            .build()
}

internal data class DreamSynthesisScopeWorkPlan(
    val uniqueWorkName: String,
    val policy: ExistingWorkPolicy,
    val scopeId: DreamScopeId,
    val runId: String,
)

internal data class DreamSynthesisConstraintPlan(
    val networkPolicy: DreamNetworkPolicy,
    val requireBatteryNotLow: Boolean,
    val requireCharging: Boolean,
    val initialDelayMinutes: Int,
)

internal fun dreamSynthesisConstraintPlan(policy: DreamingCostPolicy): DreamSynthesisConstraintPlan {
    val validated = requireNotNull(policy.validatedOrNull())
    return DreamSynthesisConstraintPlan(
        networkPolicy = validated.networkPolicy,
        requireBatteryNotLow = validated.requireBatteryNotLow,
        requireCharging = validated.requireCharging,
        initialDelayMinutes = validated.idleThresholdMinutes,
    )
}

/** Manual "dream now" plan: run now, only require network (charging/battery/metered stay out). */
internal fun dreamSynthesisManualConstraintPlan(
    @Suppress("UNUSED_PARAMETER") policy: DreamingCostPolicy,
): DreamSynthesisConstraintPlan = DreamSynthesisConstraintPlan(
    networkPolicy = DreamNetworkPolicy.CONNECTED,
    requireBatteryNotLow = false,
    requireCharging = false,
    initialDelayMinutes = 0,
)

private fun DreamSynthesisConstraintPlan.toWorkConstraints(): Constraints =
    Constraints.Builder()
        .setRequiredNetworkType(
            when (networkPolicy) {
                DreamNetworkPolicy.CONNECTED -> NetworkType.CONNECTED
                DreamNetworkPolicy.UNMETERED -> NetworkType.UNMETERED
            },
        )
        .setRequiresBatteryNotLow(requireBatteryNotLow)
        .setRequiresCharging(requireCharging)
        .build()

internal fun dreamSynthesisScopeWorkPlan(
    scopeId: DreamScopeId,
    runId: String,
    replaceExisting: Boolean = false,
): DreamSynthesisScopeWorkPlan {
    requireCanonicalDreamRunId(runId)
    return DreamSynthesisScopeWorkPlan(
        uniqueWorkName = dreamSynthesisScopeWorkName(scopeId),
        policy = if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
        scopeId = scopeId,
        runId = runId,
    )
}

internal fun dreamSynthesisScanExistingPolicy(
    reason: DreamSynthesisScanReason,
): ExistingWorkPolicy = if (reason == DreamSynthesisScanReason.COST_POLICY_CHANGED) {
    ExistingWorkPolicy.REPLACE
} else if (
    reason == DreamSynthesisScanReason.FOLLOW_UP ||
    reason == DreamSynthesisScanReason.APP_IDLE_RECHECK
) {
    ExistingWorkPolicy.APPEND_OR_REPLACE
} else {
    ExistingWorkPolicy.KEEP
}

internal fun dreamSynthesisScopeWorkName(scopeId: DreamScopeId): String =
    "dream_synthesis_scope_v1_${dreamSynthesisScopeWorkKey(scopeId)}"

/** M4 builds used the same full digest before the stable v1 namespace was introduced. */
internal fun dreamSynthesisLegacyScopeWorkNames(scopeId: DreamScopeId): List<String> =
    listOf("dream_synthesis_scope_${dreamSynthesisScopeWorkKey(scopeId)}")

internal fun dreamSynthesisAllScopeWorkNames(scopeId: DreamScopeId): List<String> =
    listOf(dreamSynthesisScopeWorkName(scopeId)) + dreamSynthesisLegacyScopeWorkNames(scopeId)

internal fun dreamSynthesisRecoveryWorkNames(): List<String> = listOf(
    STARTUP_SCAN_WORK_NAME,
    DIRTY_SCAN_KICK_WORK_NAME,
    PERIODIC_SCAN_WORK_NAME,
    UTC_ROLLOVER_SCAN_WORK_NAME,
)

internal fun dreamSynthesisScopeWorkTags(): Set<String> = setOf(DREAM_SYNTHESIS_SCOPE_WORK_TAG)

/** Full SHA-256; unlike String.hashCode, unrelated scopes cannot alias normal work names. */
internal fun dreamSynthesisScopeWorkKey(scopeId: DreamScopeId): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(scopeId.value.toByteArray(Charsets.UTF_8))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

private const val STARTUP_SCAN_WORK_NAME = "dream_synthesis_scan_startup_v1"
private const val DIRTY_SCAN_KICK_WORK_NAME = "dream_synthesis_scan_kick_v1"
private const val PERIODIC_SCAN_WORK_NAME = "dream_synthesis_scan_periodic_v1"
private const val UTC_ROLLOVER_SCAN_WORK_NAME = "dream_synthesis_scan_utc_rollover_v1"
private const val PERIODIC_SCAN_INTERVAL_HOURS = 6L
private const val DREAM_SYNTHESIS_SCAN_KICK_DELAY_MS = 1_000L
private const val DREAM_SYNTHESIS_RETRY_BACKOFF_SECONDS = 30L
private const val DREAM_SYNTHESIS_SCOPE_WORK_TAG = "dream_synthesis_scope_work_v1"
