package me.rerere.rikkahub.memory.dreaming.store

import android.util.Log
import androidx.room.withTransaction
import kotlin.math.roundToInt
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.DreamDao
import me.rerere.rikkahub.data.db.dao.DreamExperienceDao
import me.rerere.rikkahub.data.db.dao.DreamSynthesisDao
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.dao.MemoryV2Dao
import me.rerere.rikkahub.data.db.entity.DreamClaimEntity
import me.rerere.rikkahub.data.db.entity.DreamClaimExperienceSourceEntity
import me.rerere.rikkahub.data.db.entity.DreamClaimVersionEntity
import me.rerere.rikkahub.data.db.entity.DreamClaimVersionSourceEntity
import me.rerere.rikkahub.data.db.entity.DreamRunEntity
import me.rerere.rikkahub.data.db.entity.DreamSnapshotEntity
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.MemoryRevisionEntity
import me.rerere.rikkahub.memory.MemoryApprovalSource
import me.rerere.rikkahub.memory.MemoryAttribution
import me.rerere.rikkahub.memory.MemoryKind
import me.rerere.rikkahub.memory.MemoryLifecycleStatus
import me.rerere.rikkahub.memory.MemorySourceIdentity
import me.rerere.rikkahub.memory.MemoryTruthStatus
import me.rerere.rikkahub.memory.dreaming.input.DreamDeterministicInvalidation
import me.rerere.rikkahub.memory.dreaming.input.DreamDeterministicInvalidationReason
import me.rerere.rikkahub.memory.dreaming.input.DreamInputBudget
import me.rerere.rikkahub.memory.dreaming.input.DreamInputBuildRequest
import me.rerere.rikkahub.memory.dreaming.input.DreamInputCandidate
import me.rerere.rikkahub.memory.dreaming.input.DreamInputCandidateOrigin
import me.rerere.rikkahub.memory.dreaming.experience.toDreamInputCandidate
import me.rerere.rikkahub.memory.dreaming.model.AuthorityChangeReason
import me.rerere.rikkahub.memory.dreaming.model.AuthorityEntityKind
import me.rerere.rikkahub.memory.dreaming.model.DreamAuthorityFingerprintV1
import me.rerere.rikkahub.memory.dreaming.model.DreamAuthorityMemory
import me.rerere.rikkahub.memory.dreaming.model.DreamAuthorityPin
import me.rerere.rikkahub.memory.dreaming.model.DreamAuthoritySource
import me.rerere.rikkahub.memory.dreaming.model.DreamClaimHead
import me.rerere.rikkahub.memory.dreaming.model.DreamClaimSourcePin
import me.rerere.rikkahub.memory.dreaming.model.DreamClaimState
import me.rerere.rikkahub.memory.dreaming.model.DreamClaimVersionCanonicalV1
import me.rerere.rikkahub.memory.dreaming.model.DreamEpistemicType
import me.rerere.rikkahub.memory.dreaming.model.DreamRunFailureCode
import me.rerere.rikkahub.memory.dreaming.model.DreamRunMode
import me.rerere.rikkahub.memory.dreaming.model.DreamRunStatus
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.model.DreamPairScopeId
import me.rerere.rikkahub.memory.dreaming.model.DreamSha256
import me.rerere.rikkahub.memory.dreaming.model.DreamStorageClass
import me.rerere.rikkahub.memory.dreaming.model.DreamSupportType
import me.rerere.rikkahub.memory.dreaming.model.DreamSynthesisFence
import me.rerere.rikkahub.memory.dreaming.model.DreamSynthesisMode
import me.rerere.rikkahub.memory.dreaming.model.DreamValidatedClaimVersion
import me.rerere.rikkahub.memory.dreaming.runtime.DreamingFeatureFlagSource
import me.rerere.rikkahub.memory.dreaming.runtime.allowsSynthesisGeneration
import me.rerere.rikkahub.memory.dreaming.source.DreamSourceLocator
import me.rerere.rikkahub.memory.dreaming.diagnostics.DreamSynthesisForensicsEvent
import me.rerere.rikkahub.memory.dreaming.diagnostics.DreamSynthesisForensicsStore
import me.rerere.rikkahub.memory.dreaming.temporal.TemporalState

/** Room implementation with no provider dependency; every public method is one short transaction. */
class RoomDreamSynthesisStore(
    private val database: AppDatabase,
    private val dreamDao: DreamDao,
    private val experienceDao: DreamExperienceDao,
    private val synthesisDao: DreamSynthesisDao,
    private val memoryDao: MemoryDAO,
    private val memoryV2Dao: MemoryV2Dao,
    private val observerStore: DreamObserverStore,
    private val featureFlags: DreamingFeatureFlagSource,
    private val json: Json,
    private val idGenerator: () -> String = { Uuid.random().toString() },
    /** Optional durable forensics sidecar; null keeps legacy (test/contract) construction sites unchanged. */
    private val forensics: DreamSynthesisForensicsStore? = null,
) : DreamSynthesisStore {
    override suspend fun begin(request: BeginDreamSynthesisRequest): BeginDreamSynthesisResult {
        if (!generationEnabled(request.scopeId)) {
            return BeginDreamSynthesisResult.Rejected(DreamSynthesisStoreRejection.FEATURE_DISABLED)
        }
        if (DreamPairScopeId.parseOrNull(request.scopeId.value) != null) {
            return beginPair(request)
        }
        return database.withTransaction {
            val initial = observerStore.ensureScopeState(request.scopeId, request.attemptNowEpochMs)
            val stateBefore = dreamDao.getScopeState(request.scopeId.value)
                ?: return@withTransaction BeginDreamSynthesisResult.Rejected(
                    DreamSynthesisStoreRejection.STORE_CORRUPTION,
                )
            val pairStateBefore = DreamPairScopeId.parseOrNull(request.scopeId.value)?.let {
                experienceDao.getState(request.scopeId.value)
                    ?: return@withTransaction BeginDreamSynthesisResult.Rejected(
                        DreamSynthesisStoreRejection.STORE_CORRUPTION,
                    )
            }
            if (initial.memoryEpoch != stateBefore.memoryEpoch ||
                (pairStateBefore != null && (
                    stateBefore.memoryEpoch != pairStateBefore.experienceEpoch ||
                        stateBefore.observerCheckpointEpoch != pairStateBefore.observerCheckpointEpoch ||
                        stateBefore.lastAppliedMemoryEpoch != pairStateBefore.appliedExperienceEpoch ||
                        stateBefore.dreamStateRevision != pairStateBefore.profileRevision ||
                        stateBefore.activeSnapshotId != pairStateBefore.activeSnapshotId
                    ))
            ) {
                return@withTransaction BeginDreamSynthesisResult.Rejected(
                    DreamSynthesisStoreRejection.STORE_CORRUPTION,
                )
            }
            val appliedEpoch = pairStateBefore?.appliedExperienceEpoch ?: stateBefore.lastAppliedMemoryEpoch
            val effectiveMode = if (appliedEpoch == 0L) {
                DreamSynthesisMode.FULL
            } else {
                request.mode
            }
            val started = observerStore.startRun(
                StartDreamRunRequest(
                    runId = request.runId,
                    scopeId = request.scopeId,
                    mode = effectiveMode.toRunMode(),
                    leaseOwner = request.leaseOwner,
                    nowMs = request.attemptNowEpochMs,
                    leaseDurationMs = DREAM_SYNTHESIS_INITIAL_LEASE_MS,
                    sourceTimezoneId = request.sourceTimezoneId,
                ),
            )
            when (started) {
                is StartDreamRunResult.Terminal -> BeginDreamSynthesisResult.Terminal(
                    succeeded = started.run.status == DreamRunStatus.SUCCEEDED,
                )

                is StartDreamRunResult.Rejected -> BeginDreamSynthesisResult.Rejected(
                    started.reason.toSynthesisRejection(),
                )

                is StartDreamRunResult.Started,
                is StartDreamRunResult.Resumed,
                -> {
                    val run = dreamDao.getRunById(request.runId)
                        ?: return@withTransaction BeginDreamSynthesisResult.Rejected(
                            DreamSynthesisStoreRejection.RUN_NOT_FOUND,
                        )
                    val state = dreamDao.getScopeState(request.scopeId.value)
                        ?: return@withTransaction BeginDreamSynthesisResult.Rejected(
                            DreamSynthesisStoreRejection.STORE_CORRUPTION,
                        )
                    if (!run.isOwnedBy(request.scopeId, request.leaseOwner) ||
                        state.activeRunId != request.runId || run.status != DreamRunStatus.RUNNING.name ||
                        run.sourceTimezoneId != request.sourceTimezoneId || run.startedAtMs == null ||
                        run.startedAtMs > request.attemptNowEpochMs
                    ) {
                        return@withTransaction BeginDreamSynthesisResult.Rejected(
                            DreamSynthesisStoreRejection.FENCE_CONFLICT,
                        )
                    }
                    BeginDreamSynthesisResult.Ready(
                        DreamSynthesisFence(
                            scopeId = request.scopeId,
                            runId = request.runId,
                            leaseOwner = request.leaseOwner,
                            baseMemoryEpoch = pairStateBefore?.experienceEpoch ?: run.baseMemoryEpoch,
                            baseLastAppliedMemoryEpoch = pairStateBefore?.appliedExperienceEpoch
                                ?: state.lastAppliedMemoryEpoch,
                            baseDreamRevision = pairStateBefore?.profileRevision ?: run.baseDreamRevision,
                            expectedActiveSnapshotId = pairStateBefore?.activeSnapshotId
                                ?: state.activeSnapshotId,
                            frozenNowEpochMs = run.startedAtMs,
                            sourceTimezoneId = run.sourceTimezoneId,
                            mode = effectiveMode,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun beginPair(request: BeginDreamSynthesisRequest): BeginDreamSynthesisResult =
        database.withTransaction {
            val state = experienceDao.getState(request.scopeId.value)
                ?: return@withTransaction BeginDreamSynthesisResult.Rejected(
                    DreamSynthesisStoreRejection.STORE_CORRUPTION,
                )
            val run = dreamDao.getRunById(request.runId)
                ?: return@withTransaction BeginDreamSynthesisResult.Rejected(
                    DreamSynthesisStoreRejection.RUN_NOT_FOUND,
                )
            if (run.scopeId != request.scopeId.value) {
                return@withTransaction BeginDreamSynthesisResult.Rejected(
                    DreamSynthesisStoreRejection.SCOPE_MISMATCH,
                )
            }
            val status = runCatching { DreamRunStatus.valueOf(run.status) }.getOrNull()
                ?: return@withTransaction BeginDreamSynthesisResult.Rejected(
                    DreamSynthesisStoreRejection.STORE_CORRUPTION,
                )
            if (status.isTerminal) {
                return@withTransaction BeginDreamSynthesisResult.Terminal(
                    succeeded = status == DreamRunStatus.SUCCEEDED,
                )
            }
            val effectiveMode = if (state.appliedExperienceEpoch == 0L) {
                DreamSynthesisMode.FULL
            } else {
                request.mode
            }
            if (run.mode != effectiveMode.toRunMode().name) {
                return@withTransaction BeginDreamSynthesisResult.Rejected(
                    DreamSynthesisStoreRejection.FENCE_CONFLICT,
                )
            }
            val leaseUntilMs = runCatching {
                Math.addExact(request.attemptNowEpochMs, DREAM_SYNTHESIS_INITIAL_LEASE_MS)
            }.getOrNull() ?: return@withTransaction BeginDreamSynthesisResult.Rejected(
                DreamSynthesisStoreRejection.STORE_CORRUPTION,
            )
            when (status) {
                DreamRunStatus.PENDING -> {
                    if (experienceDao.acquireLease(
                            pairScopeId = request.scopeId.value,
                            runId = request.runId,
                            leaseUntilMs = leaseUntilMs,
                            nowMs = request.attemptNowEpochMs,
                            reasonCode = AuthorityChangeReason.RUN_CLAIMED.name,
                        ) != 1
                    ) {
                        return@withTransaction BeginDreamSynthesisResult.Rejected(
                            DreamSynthesisStoreRejection.FENCE_CONFLICT,
                        )
                    }
                    if (experienceDao.startRunMirror(
                            runId = request.runId,
                            pairScopeId = request.scopeId.value,
                            baseExperienceEpoch = state.experienceEpoch,
                            baseObserverCheckpointEpoch = state.observerCheckpointEpoch,
                            baseProfileRevision = state.profileRevision,
                            leaseOwner = request.leaseOwner,
                            leaseUntilMs = leaseUntilMs,
                            nowMs = request.attemptNowEpochMs,
                            sourceTimezoneId = request.sourceTimezoneId,
                        ) != 1
                    ) {
                        experienceDao.releaseLease(
                            pairScopeId = request.scopeId.value,
                            runId = request.runId,
                            nowMs = request.attemptNowEpochMs,
                            reasonCode = AuthorityChangeReason.RUN_FINISHED.name,
                        )
                        return@withTransaction BeginDreamSynthesisResult.Rejected(
                            DreamSynthesisStoreRejection.FENCE_CONFLICT,
                        )
                    }
                }

                DreamRunStatus.RUNNING -> {
                    if (run.leaseOwner != request.leaseOwner || run.leaseUntilMs == null ||
                        run.leaseUntilMs <= request.attemptNowEpochMs ||
                        state.activeRunId != run.runId || state.activeRunLeaseUntilMs != run.leaseUntilMs
                    ) {
                        return@withTransaction BeginDreamSynthesisResult.Rejected(
                            DreamSynthesisStoreRejection.FENCE_CONFLICT,
                        )
                    }
                }

                else -> error("terminal Dream status handled above")
            }
            val startedRun = dreamDao.getRunById(request.runId)
                ?: return@withTransaction BeginDreamSynthesisResult.Rejected(
                    DreamSynthesisStoreRejection.RUN_NOT_FOUND,
                )
            val startedState = experienceDao.getState(request.scopeId.value)
                ?: return@withTransaction BeginDreamSynthesisResult.Rejected(
                    DreamSynthesisStoreRejection.STORE_CORRUPTION,
                )
            if (startedRun.status != DreamRunStatus.RUNNING.name ||
                startedRun.leaseOwner != request.leaseOwner || startedRun.startedAtMs == null ||
                startedRun.sourceTimezoneId != request.sourceTimezoneId ||
                startedState.activeRunId != request.runId ||
                startedState.activeRunLeaseUntilMs != startedRun.leaseUntilMs
            ) {
                return@withTransaction BeginDreamSynthesisResult.Rejected(
                    DreamSynthesisStoreRejection.FENCE_CONFLICT,
                )
            }
            BeginDreamSynthesisResult.Ready(
                DreamSynthesisFence(
                    scopeId = request.scopeId,
                    runId = request.runId,
                    leaseOwner = request.leaseOwner,
                    baseMemoryEpoch = startedRun.baseMemoryEpoch,
                    baseLastAppliedMemoryEpoch = state.appliedExperienceEpoch,
                    baseDreamRevision = startedRun.baseDreamRevision,
                    expectedActiveSnapshotId = state.activeSnapshotId,
                    frozenNowEpochMs = startedRun.startedAtMs,
                    sourceTimezoneId = startedRun.sourceTimezoneId,
                    mode = effectiveMode,
                ),
            )
        }

    override suspend fun readInputSeed(
        fence: DreamSynthesisFence,
        attemptNowEpochMs: Long,
    ): ReadDreamInputSeedResult {
        require(attemptNowEpochMs >= fence.frozenNowEpochMs)
        if (!generationEnabled(fence.scopeId)) {
            return ReadDreamInputSeedResult.Rejected(DreamSynthesisStoreRejection.FEATURE_DISABLED)
        }
        return database.withTransaction {
            val owned = when (val context = ownedContext(fence, attemptNowEpochMs)) {
                is OwnedContextResult.Ready -> context.run
                is OwnedContextResult.Rejected -> return@withTransaction ReadDreamInputSeedResult.Rejected(
                    context.storeReason,
                )
            }
            if (DreamPairScopeId.parseOrNull(fence.scopeId.value) != null) {
                val allClaims = loadClaims(fence.scopeId, fence.runId)
                    ?: return@withTransaction ReadDreamInputSeedResult.Rejected(
                        DreamSynthesisStoreRejection.STORE_CORRUPTION,
                    )
                val fromExclusive = if (fence.mode == DreamSynthesisMode.FULL) {
                    0L
                } else {
                    fence.baseAppliedExperienceEpoch
                }
                val experiences = experienceDao.listSynthesisExperiences(
                    pairScopeId = fence.scopeId.value,
                    afterExclusiveEpoch = fromExclusive,
                    throughInclusiveEpoch = fence.baseExperienceEpoch,
                    limit = MAX_PAIR_DREAM_INPUT_CANDIDATE_SCAN,
                )
                val candidates = experiences.mapNotNull { experience ->
                    experience.toDreamInputCandidate(
                        scopeId = fence.scopeId,
                        origin = if (fence.mode == DreamSynthesisMode.FULL) {
                            DreamInputCandidateOrigin.FULL_REBUILD
                        } else {
                            DreamInputCandidateOrigin.AUTHORITY_CHANGE
                        },
                        json = json,
                    )
                }.take(DREAM_SYNTHESIS_INPUT_BUDGET.maxMemories)
                val invalidations = deterministicInvalidations(allClaims, fence)
                    ?: return@withTransaction ReadDreamInputSeedResult.Rejected(
                        DreamSynthesisStoreRejection.STORE_CORRUPTION,
                    )
                if (owned.checkpointEpoch != fence.baseExperienceEpoch &&
                    experienceDao.advanceRunCheckpoint(
                        runId = fence.runId,
                        pairScopeId = fence.scopeId.value,
                        leaseOwner = fence.leaseOwner,
                        expectedCheckpointEpoch = owned.checkpointEpoch,
                        targetCheckpointEpoch = fence.baseExperienceEpoch,
                        nowMs = attemptNowEpochMs,
                    ) != 1
                ) {
                    return@withTransaction ReadDreamInputSeedResult.Rejected(
                        DreamSynthesisStoreRejection.FENCE_CONFLICT,
                    )
                }
                return@withTransaction ReadDreamInputSeedResult.Ready(
                    DreamInputBuildRequest(
                        fence = fence,
                        candidates = candidates,
                        currentClaims = allClaims,
                        deterministicInvalidations = invalidations,
                        budget = DREAM_SYNTHESIS_INPUT_BUDGET,
                    ),
                )
            }
            // A first FULL rebuild is the upgrade/bootstrap boundary. Its authority comes from the
            // current scoped tables, not from a v45 journal that may legitimately have been pruned
            // before synthesis existed. Only incremental replay requires an unbroken epoch window.
            val changes = if (fence.mode == DreamSynthesisMode.INCREMENTAL) {
                dreamDao.listChanges(
                    scopeId = fence.scopeId.value,
                    afterExclusiveEpoch = fence.baseLastAppliedMemoryEpoch,
                    throughInclusiveEpoch = fence.baseMemoryEpoch,
                ).also { journal ->
                    if (!hasCompleteEpochs(
                            journal.map { it.memoryEpoch },
                            fence.baseLastAppliedMemoryEpoch,
                            fence.baseMemoryEpoch,
                        )
                    ) {
                        return@withTransaction rejectSeedCorruption(
                            fence.runId,
                            fence.scopeId,
                            "SEED_JOURNAL_GAP",
                            "${fence.baseLastAppliedMemoryEpoch}:${fence.baseMemoryEpoch}:${journal.size}",
                        )
                    }
                }
            } else {
                emptyList()
            }
            val allClaims = loadClaims(fence.scopeId, fence.runId)
                ?: return@withTransaction ReadDreamInputSeedResult.Rejected(
                    DreamSynthesisStoreRejection.STORE_CORRUPTION,
                )
            val memories = when (fence.mode) {
                DreamSynthesisMode.FULL -> memoryDao.getActiveConfirmedMemoriesForDream(
                    scopeId = fence.scopeId.value,
                    nowMs = fence.frozenNowEpochMs,
                    limit = MAX_DREAM_INPUT_CANDIDATES + 1,
                )

                DreamSynthesisMode.INCREMENTAL -> {
                    val ids = changes.asSequence()
                        .filter { it.entityKind == AuthorityEntityKind.MEMORY.name }
                        .mapNotNull { it.entityId.toIntOrNull() }
                        .distinct()
                        .sorted()
                        .toList()
                    if (ids.size > MAX_DREAM_INPUT_CANDIDATES) {
                        return@withTransaction rejectSeedCorruption(
                            fence.runId,
                            fence.scopeId,
                            "SEED_CHANGE_ID_OVERFLOW",
                            ids.size.toString(),
                        )
                    }
                    val loaded = mutableListOf<MemoryEntity>()
                    for (chunk in ids.chunked(DREAM_MEMORY_ID_QUERY_CHUNK)) {
                        loaded += memoryDao.getMemoriesByIds(chunk, fence.scopeId.value)
                    }
                    loaded.filter { it.isActiveAt(fence.frozenNowEpochMs) }
                }
            }.sortedBy(MemoryEntity::id)
            if (memories.size > MAX_DREAM_INPUT_CANDIDATES) {
                return@withTransaction rejectSeedCorruption(
                    fence.runId,
                    fence.scopeId,
                    "SEED_MEMORY_OVERFLOW",
                    memories.size.toString(),
                )
            }
            val candidates = memories.map { memory ->
                val authority = memory.toDreamAuthority(fence.scopeId)
                    ?: return@withTransaction rejectSeedCorruption(
                        fence.runId,
                        fence.scopeId,
                        "SEED_MEMORY_AUTHORITY",
                        memory.id.toString(),
                    )
                val pin = authority.toPin()
                DreamInputCandidate(
                    origin = if (fence.mode == DreamSynthesisMode.FULL) {
                        DreamInputCandidateOrigin.FULL_REBUILD
                    } else {
                        DreamInputCandidateOrigin.AUTHORITY_CHANGE
                    },
                    memory = authority,
                    pin = pin,
                    sourceLocators = authority.sources.map { source -> source.toLocator(fence.scopeId) },
                    requireSourceReread = authority.sources.isNotEmpty(),
                )
            }
            val invalidations = deterministicInvalidations(allClaims, fence)
                ?: return@withTransaction ReadDreamInputSeedResult.Rejected(
                    DreamSynthesisStoreRejection.STORE_CORRUPTION,
                )
            if (owned.checkpointEpoch != fence.baseMemoryEpoch) {
                if (dreamDao.advanceRunCheckpoint(
                        runId = fence.runId,
                        scopeId = fence.scopeId.value,
                        leaseOwner = fence.leaseOwner,
                        expectedCheckpointEpoch = owned.checkpointEpoch,
                        targetCheckpointEpoch = fence.baseMemoryEpoch,
                        nowMs = attemptNowEpochMs,
                    ) != 1
                ) {
                    return@withTransaction ReadDreamInputSeedResult.Rejected(
                        DreamSynthesisStoreRejection.FENCE_CONFLICT,
                    )
                }
            }
            ReadDreamInputSeedResult.Ready(
                DreamInputBuildRequest(
                    fence = fence,
                    candidates = candidates,
                    currentClaims = allClaims,
                    deterministicInvalidations = invalidations,
                    budget = DREAM_SYNTHESIS_INPUT_BUDGET,
                ),
            )
        }
    }

    override suspend fun heartbeat(
        fence: DreamSynthesisFence,
        nowMs: Long,
        leaseDurationMs: Long,
    ): DreamSynthesisStoreResult {
        if (!generationEnabled(fence.scopeId)) {
            return DreamSynthesisStoreResult.Rejected(DreamSynthesisStoreRejection.FEATURE_DISABLED)
        }
        if (DreamPairScopeId.parseOrNull(fence.scopeId.value) != null) {
            return heartbeatPair(fence, nowMs, leaseDurationMs)
        }
        return when (val result = observerStore.heartbeat(
            DreamRunLeaseRequest(
                runId = fence.runId,
                scopeId = fence.scopeId,
                leaseOwner = fence.leaseOwner,
                nowMs = nowMs,
                leaseDurationMs = leaseDurationMs,
            ),
        )) {
            is HeartbeatDreamRunResult.Extended -> DreamSynthesisStoreResult.Accepted
            is HeartbeatDreamRunResult.Rejected -> DreamSynthesisStoreResult.Rejected(
                result.reason.toSynthesisRejection(),
            )
        }
    }

    private suspend fun heartbeatPair(
        fence: DreamSynthesisFence,
        nowMs: Long,
        leaseDurationMs: Long,
    ): DreamSynthesisStoreResult = try {
        database.withTransaction {
            when (val owned = ownedContext(fence, nowMs)) {
                is OwnedContextResult.Rejected ->
                    return@withTransaction DreamSynthesisStoreResult.Rejected(owned.storeReason)
                is OwnedContextResult.Ready -> Unit
            }
            val leaseUntilMs = runCatching { Math.addExact(nowMs, leaseDurationMs) }
                .getOrElse { throw PairLeaseAbort() }
            if (experienceDao.heartbeatLease(
                    pairScopeId = fence.scopeId.value,
                    runId = fence.runId,
                    leaseUntilMs = leaseUntilMs,
                    nowMs = nowMs,
                    reasonCode = AuthorityChangeReason.RUN_HEARTBEAT.name,
                ) != 1
            ) throw PairLeaseAbort()
            if (experienceDao.heartbeatRunMirror(
                    runId = fence.runId,
                    pairScopeId = fence.scopeId.value,
                    leaseOwner = fence.leaseOwner,
                    leaseUntilMs = leaseUntilMs,
                    nowMs = nowMs,
                ) != 1
            ) throw PairLeaseAbort()
            DreamSynthesisStoreResult.Accepted
        }
    } catch (_: PairLeaseAbort) {
        DreamSynthesisStoreResult.Rejected(DreamSynthesisStoreRejection.FENCE_CONFLICT)
    }

    override suspend fun markProviderDispatch(
        request: DreamProviderDispatchRequest,
    ): DreamSynthesisStoreResult {
        val fence = request.fence
        if (!generationEnabled(fence.scopeId)) {
            return DreamSynthesisStoreResult.Rejected(DreamSynthesisStoreRejection.FEATURE_DISABLED)
        }
        return database.withTransaction {
            val marked = synthesisDao.markRunProviderDispatch(
                runId = fence.runId,
                scopeId = fence.scopeId.value,
                leaseOwner = fence.leaseOwner,
                promptContractVersion = request.promptContractVersion,
                validatorVersion = request.validatorVersion,
                inputMemoryCount = request.inputMemoryCount,
                inputManifestHash = request.inputManifestHash.value,
                nowMs = request.markedAtEpochMs,
            )
            if (marked == 1) {
                DreamSynthesisStoreResult.Accepted
            } else {
                when (val owned = ownedContext(fence, request.markedAtEpochMs)) {
                    is OwnedContextResult.Rejected -> DreamSynthesisStoreResult.Rejected(owned.storeReason)
                    is OwnedContextResult.Ready -> {
                        val run = owned.run
                        if (run.promptContractVersion == request.promptContractVersion &&
                            run.validatorVersion == request.validatorVersion &&
                            run.inputMemoryCount == request.inputMemoryCount &&
                            run.inputManifestHash == request.inputManifestHash.value
                        ) {
                            DreamSynthesisStoreResult.Accepted
                        } else {
                            // Names the exact mismatch so a replay divergence stops surfacing as a
                            // bare STORE_FAILURE on the run row (2026-10-13).
                            val mismatch = buildString {
                                if (run.promptContractVersion != request.promptContractVersion) append("prompt ")
                                if (run.validatorVersion != request.validatorVersion) append("validator ")
                                if (run.inputMemoryCount != request.inputMemoryCount) append("memory_count ")
                                if (run.inputManifestHash != request.inputManifestHash.value) append("manifest ")
                            }.trim().ifEmpty { "unknown" }
                            recordSeedForensics(
                                fence.runId,
                                fence.scopeId,
                                "DISPATCH_REPLAY_MISMATCH",
                                mismatch,
                            )
                            DreamSynthesisStoreResult.Rejected(DreamSynthesisStoreRejection.STORE_CORRUPTION)
                        }
                    }
                }
            }
        }
    }

    override suspend fun commit(request: DreamSynthesisCommitRequest): DreamSynthesisCommitResult {
        if (!generationEnabled(request.fence.scopeId)) {
            return DreamSynthesisCommitResult.Rejected(DreamSynthesisCommitRejection.LEASE_MISSING)
        }
        return try {
            database.withTransaction { commitOrAbort(request) }
        } catch (abort: CommitAbort) {
            // 2026-10-13: the stage names the exact check that aborted, so the sidecar can tell
            // apart aborts that share a rejection code (and the seven identical device failures
            // stop being one opaque STORE_FAILURE).
            recordCommitForensics(
                request,
                stage = "COMMIT_REJECTED:" + abort.stage,
                reason = abort.reason,
            )
            DreamSynthesisCommitResult.Rejected(abort.reason)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Forensics (2026-10-12 device evidence: repeated identical failures whose original
            // exception was swallowed, leaving only the failure code). Enum names and the run id
            // only — no model or user text is ever logged here.
            Log.w(
                DREAM_SYNTHESIS_STORE_LOG_TAG,
                "Dream synthesis commit failed for run ${request.fence.runId} " +
                    "scope=${request.fence.scopeId.value}",
                error,
            )
            recordCommitForensics(request, stage = "COMMIT_EXCEPTION", error = error)
            DreamSynthesisCommitResult.Rejected(DreamSynthesisCommitRejection.STORE_CORRUPTION)
        }
    }

    /**
     * Durable forensics sidecar for commit failures. Enum names, counts, opaque ids and
     * exception summaries only — no model or user text. Never throws; a forensics failure
     * must not change the commit outcome.
     */
    private fun recordCommitForensics(
        request: DreamSynthesisCommitRequest,
        stage: String,
        reason: DreamSynthesisCommitRejection? = null,
        error: Exception? = null,
    ) {
        val sidecar = forensics ?: return
        val fence = request.fence
        sidecar.record(
            DreamSynthesisForensicsEvent(
                recordedAtEpochMs = maxOf(request.committedAtEpochMs, System.currentTimeMillis()),
                runId = fence.runId,
                pairScope = DreamPairScopeId.parseOrNull(fence.scopeId.value) != null,
                stage = stage,
                reason = reason?.name,
                failureCode = null,
                exceptionType = error?.let { it::class.java.name },
                exceptionMessage = error?.message?.take(300),
                exceptionFrames = error?.stackTrace
                    ?.take(4)
                    ?.joinToString(" | ") { frame ->
                        "${frame.className}.${frame.methodName}(${frame.fileName}:${frame.lineNumber})"
                    }
                    ?.take(400),
                mode = fence.mode.name,
                planUpsertCount = request.plan.upserts.size,
                planTransitionCount = request.plan.transitions.size,
                planClaimCount = request.plan.resultingClaims.size,
                baseExperienceEpoch = fence.baseExperienceEpoch,
                baseAppliedExperienceEpoch = fence.baseAppliedExperienceEpoch,
                baseDreamRevision = fence.baseDreamRevision,
                inputMemoryCount = request.inputMemoryCount,
            ),
        )
    }

    override suspend fun terminalizeConflict(
        fence: DreamSynthesisFence,
        reason: DreamSynthesisCommitRejection,
        nowMs: Long,
    ): DreamSynthesisStoreResult {
        // 2026-10-13: no more collapse. Every rejection now names its own run failure code
        // (see DreamSynthesisCommitRejection.toRunFailureCode), so the device run list — which is
        // the only field the user can actually read — distinguishes a fence conflict from a
        // missing lease, a superseded snapshot, a rejected evidence pin or real corruption.
        // A lost terminalize race can still get the row relabelled LEASE_EXPIRED by
        // failExpiredRunMirrors; that path stays as the recovery backstop.
        val failureCode = reason.toRunFailureCode()
        // Keep the same facts in the log/sidecar as before: enum names and run id only — no model
        // or user text. (This store only runs under instrumented tests; Log is safe here.)
        Log.w(
            DREAM_SYNTHESIS_STORE_LOG_TAG,
            "Dream run ${fence.runId} terminalized: reason=$reason failureCode=$failureCode",
        )
        forensics?.record(
            DreamSynthesisForensicsEvent(
                recordedAtEpochMs = maxOf(nowMs, System.currentTimeMillis()),
                runId = fence.runId,
                pairScope = DreamPairScopeId.parseOrNull(fence.scopeId.value) != null,
                stage = "TERMINALIZED",
                reason = reason.name,
                failureCode = failureCode.name,
                mode = fence.mode.name,
                baseExperienceEpoch = fence.baseExperienceEpoch,
                baseAppliedExperienceEpoch = fence.baseAppliedExperienceEpoch,
                baseDreamRevision = fence.baseDreamRevision,
            ),
        )
        return failOwnedRun(
            fence = fence,
            failureCode = failureCode,
            nowMs = nowMs,
        )
    }

    override suspend fun fail(
        fence: DreamSynthesisFence,
        failure: DreamSynthesisFailure,
        nowMs: Long,
    ): DreamSynthesisStoreResult {
        val failureCode = if (!generationEnabled(fence.scopeId)) {
            DreamRunFailureCode.FEATURE_DISABLED
        } else {
            failure.toRunFailureCode()
        }
        // Terminal runs written through this path (orchestrator failOrRetry, interrupted-run
        // terminalization) used to leave no forensics at all — on device that is exactly where
        // the opaque STORE_FAILURE rows came from. One record per terminal transition; enum
        // names and opaque ids only, never model or user text.
        forensics?.record(
            DreamSynthesisForensicsEvent(
                recordedAtEpochMs = maxOf(nowMs, System.currentTimeMillis()),
                runId = fence.runId,
                pairScope = DreamPairScopeId.parseOrNull(fence.scopeId.value) != null,
                stage = "RUN_FAILED",
                reason = failure.name,
                failureCode = failureCode.name,
                mode = fence.mode.name,
                baseExperienceEpoch = fence.baseExperienceEpoch,
                baseAppliedExperienceEpoch = fence.baseAppliedExperienceEpoch,
                baseDreamRevision = fence.baseDreamRevision,
            ),
        )
        return failOwnedRun(
            fence = fence,
            failureCode = failureCode,
            nowMs = nowMs,
        )
    }

    private suspend fun commitOrAbort(
        request: DreamSynthesisCommitRequest,
    ): DreamSynthesisCommitResult.Committed {
        val fence = request.fence
        val run = when (val context = ownedContext(fence, request.committedAtEpochMs)) {
            is OwnedContextResult.Ready -> context.run
            is OwnedContextResult.Rejected -> abort(context.commitReason)
        }
        if (run.checkpointEpoch != fence.baseMemoryEpoch) {
            abort(DreamSynthesisCommitRejection.STORE_CORRUPTION, stage = "RUN_CHECKPOINT")
        }
        request.liveAuthorityPins.forEach { pin -> validatePin(pin, request.committedAtEpochMs) }
        request.historicalTransitionPins.forEach { pin -> validateHistoricalPin(pin, fence.scopeId) }
        val versions = (request.plan.upserts + request.plan.transitions.map { it.nextVersion })
            .sortedWith(compareBy({ it.claimId }, { it.nextRevision }))
        val versionedClaimIds = versions.mapTo(hashSetOf()) { it.claimId }
        request.plan.resultingClaims
            .filter { claim ->
                claim.state == DreamClaimState.ACTIVE_CONTEXTUAL && claim.claimId !in versionedClaimIds
            }
            .forEach { claim -> touchUnchangedActiveClaim(claim, request) }
        versions.forEach { version -> persistVersion(version, request) }

        val nextDreamRevision = fence.baseDreamRevision + 1L
        if (nextDreamRevision <= 0L) abort(DreamSynthesisCommitRejection.DREAM_REVISION_CONFLICT)
        val snapshotId = idGenerator()
        try {
            me.rerere.rikkahub.memory.dreaming.model.requireDreamStableId(snapshotId)
        } catch (_: Exception) {
            abort(DreamSynthesisCommitRejection.STORE_CORRUPTION, stage = "SNAPSHOT_ID")
        }
        synthesisDao.insertSnapshot(
            DreamSnapshotEntity(
                snapshotId = snapshotId,
                scopeId = fence.scopeId.value,
                snapshotRevision = nextDreamRevision,
                sourceMemoryEpoch = fence.baseMemoryEpoch,
                committedDreamRevision = nextDreamRevision,
                status = "ACTIVE",
                canonicalPayloadJson = request.snapshot.payloadJson,
                payloadSha256 = request.snapshot.payloadHash.value,
                compilerRevision = request.snapshot.compilerRevision,
                estimatedTokens = request.snapshot.estimatedTokens,
                claimCount = request.snapshot.claimCount,
                createdByRunId = fence.runId,
                createdAtMs = request.committedAtEpochMs,
                supersedesSnapshotId = fence.expectedActiveSnapshotId,
                reasonCode = SYNTHESIS_COMMIT_REASON,
            ),
        )
        fence.expectedActiveSnapshotId?.let { oldId ->
            if (synthesisDao.supersedeActiveSnapshot(oldId, fence.scopeId.value) != 1) {
                abort(DreamSynthesisCommitRejection.ACTIVE_SNAPSHOT_CONFLICT, stage = "SUPERSEDE")
            }
        }
        if (synthesisDao.recordRunSynthesisAudit(
                runId = fence.runId,
                scopeId = fence.scopeId.value,
                leaseOwner = fence.leaseOwner,
                modelIdentityDigest = request.modelAudit.modelIdentityDigest.value,
                providerKind = request.modelAudit.providerKind,
                promptContractVersion = request.modelAudit.promptContractVersion,
                validatorVersion = request.modelAudit.validatorVersion,
                inputMemoryCount = request.inputMemoryCount,
                inputTokens = request.modelAudit.inputTokens?.toLong(),
                outputClaimCount = request.outputOperationCount,
                outputTokens = request.modelAudit.outputTokens?.toLong(),
                inputManifestHash = request.inputManifestHash.value,
                outputManifestHash = request.outputManifestHash.value,
                nowMs = request.committedAtEpochMs,
            ) != 1
        ) {
            abort(DreamSynthesisCommitRejection.LEASE_MISSING, stage = "AUDIT_INSERT")
        }
        if (DreamPairScopeId.parseOrNull(fence.scopeId.value) != null) {
            if (experienceDao.advanceApplied(
                pairScopeId = fence.scopeId.value,
                expectedExperienceEpoch = fence.baseExperienceEpoch,
                expectedAppliedEpoch = fence.baseAppliedExperienceEpoch,
                targetEpoch = fence.baseExperienceEpoch,
                expectedProfileRevision = fence.baseDreamRevision,
                nextProfileRevision = nextDreamRevision,
                activeSnapshotId = snapshotId,
                remainingDebt = 0.0,
                nowMs = request.committedAtEpochMs,
                ) != 1
                ) {
                abort(classifyCommitCas(fence, request.committedAtEpochMs), stage = "APPLIED_CAS")
                }
                } else {
            if (synthesisDao.commitActiveSnapshotCas(
                    scopeId = fence.scopeId.value,
                    runId = fence.runId,
                    leaseOwner = fence.leaseOwner,
                    baseMemoryEpoch = fence.baseMemoryEpoch,
                    baseDreamRevision = fence.baseDreamRevision,
                    expectedLastAppliedMemoryEpoch = fence.baseLastAppliedMemoryEpoch,
                    expectedActiveSnapshotId = fence.expectedActiveSnapshotId,
                    newSnapshotId = snapshotId,
                    fullRebuildAtMs = request.committedAtEpochMs.takeIf {
                        fence.mode == DreamSynthesisMode.FULL
                    },
                    reasonCode = SYNTHESIS_COMMIT_REASON,
                    nowMs = request.committedAtEpochMs,
                ) != 1
            ) {
                abort(classifyCommitCas(fence, request.committedAtEpochMs), stage = "SNAPSHOT_CAS")
            }
        }
        finishCommittedSynthesisRun(fence, request.committedAtEpochMs)
        return DreamSynthesisCommitResult.Committed(snapshotId, nextDreamRevision)
    }

    /**
     * A first FULL rebuild validates the bounded authority tables rather than an old journal that
     * may legitimately be absent after upgrade. Only this post-snapshot path may close such a run
     * without replaying that journal; every write remains inside the enclosing commit transaction.
     */
    private suspend fun finishCommittedSynthesisRun(fence: DreamSynthesisFence, nowMs: Long) {
        if (DreamPairScopeId.parseOrNull(fence.scopeId.value) != null) {
            val run = dreamDao.getRunById(fence.runId)
                ?: abort(DreamSynthesisCommitRejection.STORE_CORRUPTION, stage = "FINISH_RUN_LOOKUP")
            if (run.scopeId != fence.scopeId.value || run.mode != fence.mode.toRunMode().name ||
                run.baseMemoryEpoch != fence.baseExperienceEpoch ||
                run.baseDreamRevision != fence.baseDreamRevision ||
                run.checkpointEpoch != fence.baseExperienceEpoch
            ) {
                abort(DreamSynthesisCommitRejection.STORE_CORRUPTION, stage = "FINISH_RUN_STATE")
            }
            if (experienceDao.finishRunMirror(
                    runId = fence.runId,
                    pairScopeId = fence.scopeId.value,
                    leaseOwner = fence.leaseOwner,
                    terminalStatus = DreamRunStatus.SUCCEEDED.name,
                    failureCode = null,
                    nowMs = nowMs,
                ) != 1
            ) {
                abort(DreamSynthesisCommitRejection.STORE_CORRUPTION)
            }
            if (experienceDao.releaseLease(
                    pairScopeId = fence.scopeId.value,
                    runId = fence.runId,
                    nowMs = nowMs,
                    reasonCode = AuthorityChangeReason.RUN_FINISHED.name,
                ) != 1
            ) {
                abort(DreamSynthesisCommitRejection.STORE_CORRUPTION, stage = "FINISH_LEASE")
            }
            return
        }
        if (fence.mode != DreamSynthesisMode.FULL) {
            val finished = observerStore.finish(
                FinishDreamRunRequest(
                    runId = fence.runId,
                    scopeId = fence.scopeId,
                    leaseOwner = fence.leaseOwner,
                    outcome = DreamRunFinishOutcome.SUCCEEDED,
                    nowMs = nowMs,
                ),
            )
            if (finished !is FinishDreamRunResult.Finished ||
                finished.run.status != DreamRunStatus.SUCCEEDED
            ) {
                abort(DreamSynthesisCommitRejection.STORE_CORRUPTION, stage = "FINISH_OBSERVER")
            }
            return
        }

        val run = dreamDao.getRunById(fence.runId)
            ?: abort(DreamSynthesisCommitRejection.STORE_CORRUPTION)
        if (run.scopeId != fence.scopeId.value || run.mode != DreamRunMode.FULL.name ||
            run.baseMemoryEpoch != fence.baseMemoryEpoch ||
            run.baseDreamRevision != fence.baseDreamRevision ||
            run.checkpointEpoch != fence.baseMemoryEpoch
        ) {
            abort(DreamSynthesisCommitRejection.STORE_CORRUPTION, stage = "FULL_RUN_STATE")
        }
        if (dreamDao.finishRunMirror(
                runId = fence.runId,
                scopeId = fence.scopeId.value,
                leaseOwner = fence.leaseOwner,
                terminalStatus = DreamRunStatus.SUCCEEDED.name,
                failureCode = null,
                nowMs = nowMs,
            ) != 1
        ) {
            abort(DreamSynthesisCommitRejection.STORE_CORRUPTION, stage = "FULL_RUN_MIRROR")
        }
        if (dreamDao.advanceObserverCheckpoint(
                scopeId = fence.scopeId.value,
                runId = fence.runId,
                expectedMemoryEpoch = fence.baseMemoryEpoch,
                expectedCheckpointEpoch = run.baseObserverCheckpointEpoch,
                targetCheckpointEpoch = fence.baseMemoryEpoch,
                reasonCode = AuthorityChangeReason.OBSERVER_CHECKPOINT_ADVANCED.name,
                nowMs = nowMs,
            ) != 1
        ) {
            abort(DreamSynthesisCommitRejection.STORE_CORRUPTION, stage = "OBSERVER_CHECKPOINT")
        }
        if (dreamDao.releaseScopeLease(
                scopeId = fence.scopeId.value,
                runId = fence.runId,
                reasonCode = AuthorityChangeReason.OBSERVER_CHECKPOINT_ADVANCED.name,
                nowMs = nowMs,
            ) != 1
        ) {
            abort(DreamSynthesisCommitRejection.STORE_CORRUPTION, stage = "FULL_LEASE")
        }
    }

    private suspend fun touchUnchangedActiveClaim(
        claim: DreamClaimHead,
        request: DreamSynthesisCommitRequest,
    ) {
        val current = synthesisDao.getClaim(claim.claimId, request.fence.scopeId.value)
            ?: abort(DreamSynthesisCommitRejection.CLAIM_REVISION_CONFLICT)
        val persisted = current.toModel(request.fence.scopeId)
            ?: abort(DreamSynthesisCommitRejection.STORE_CORRUPTION, stage = "CLAIM_TOUCH")
        // An unchanged head is immutable at this revision, including its complete provenance.
        // Rechecking only the head hash would permit a caller to substitute a different live pin
        // without writing a new ClaimVersion/source history row.
        if (persisted != claim ||
            current.state != DreamClaimState.ACTIVE_CONTEXTUAL.name ||
            current.lastValidatedMemoryEpoch > request.fence.baseMemoryEpoch
        ) {
            abort(DreamSynthesisCommitRejection.CLAIM_REVISION_CONFLICT)
        }
        if (current.lastValidatedMemoryEpoch == request.fence.baseMemoryEpoch) return
        if (synthesisDao.touchClaimValidationEpochCas(
                scopeId = request.fence.scopeId.value,
                claimId = claim.claimId,
                expectedRevision = claim.revision,
                targetEpoch = request.fence.baseMemoryEpoch,
                nowMs = request.committedAtEpochMs,
            ) != 1
        ) {
            abort(DreamSynthesisCommitRejection.CLAIM_REVISION_CONFLICT)
        }
    }

    private suspend fun persistVersion(
        version: DreamValidatedClaimVersion,
        request: DreamSynthesisCommitRequest,
    ) {
        val fence = request.fence
        val canonical = DreamClaimVersionCanonicalV1.encode(version)
        val resulting = request.plan.resultingClaims.singleOrNull {
            it.claimId == version.claimId && it.revision == version.nextRevision
        } ?: abort(DreamSynthesisCommitRejection.STORE_CORRUPTION)
        if (resulting.versionHash != canonical.contentHash ||
            resulting.state != version.nextState || resulting.sources != version.sources
        ) {
            abort(DreamSynthesisCommitRejection.STORE_CORRUPTION)
        }
        val current = synthesisDao.getClaim(version.claimId, fence.scopeId.value)
        if (version.expectedPreviousRevision == null) {
            if (current != null || synthesisDao.getClaimByKey(fence.scopeId.value, version.claimKey) != null) {
                abort(DreamSynthesisCommitRejection.CLAIM_REVISION_CONFLICT)
            }
            synthesisDao.insertClaim(version.toEntity(request, learnedAtMs = request.committedAtEpochMs))
        } else {
            if (current == null || current.claimRevision != version.expectedPreviousRevision) {
                abort(DreamSynthesisCommitRejection.CLAIM_REVISION_CONFLICT)
            }
            if (synthesisDao.updateClaimHeadCas(
                    claimId = version.claimId,
                    scopeId = fence.scopeId.value,
                    expectedClaimRevision = version.expectedPreviousRevision,
                    nextClaimRevision = version.nextRevision,
                    claimKey = version.claimKey,
                    storageClass = version.storageClass.name,
                    epistemicType = version.epistemicType.name,
                    subjectKind = version.subjectKind.name,
                    profileSection = version.profileSection,
                    epistemicOrigin = version.epistemicOrigin.name,
                    contentType = version.contentType.name,
                    title = version.title,
                    statement = version.statement,
                    state = version.nextState.name,
                    confidence = version.confidencePermille / 1_000.0,
                    temporalState = version.temporalState.name,
                    validFromMs = version.validFromEpochMs,
                    validToMs = version.validToEpochMs,
                    learnedAtMs = current.learnedAtMs,
                    sourceTimezone = fence.sourceTimezoneId,
                    claimHash = canonical.contentHash.value,
                    createdByRunId = fence.runId,
                    lastValidatedMemoryEpoch = fence.baseMemoryEpoch,
                    invalidatedAtMs = request.committedAtEpochMs.takeIf {
                        version.nextState !in setOf(
                            DreamClaimState.ACTIVE_CONTEXTUAL,
                            DreamClaimState.PENDING_REVIEW,
                        )
                    },
                    invalidationReason = version.reason.name.takeIf {
                        version.nextState !in setOf(
                            DreamClaimState.ACTIVE_CONTEXTUAL,
                            DreamClaimState.PENDING_REVIEW,
                        )
                    },
                    nowMs = request.committedAtEpochMs,
                ) != 1
            ) {
                abort(DreamSynthesisCommitRejection.CLAIM_REVISION_CONFLICT)
            }
        }
        synthesisDao.insertClaimVersion(
            DreamClaimVersionEntity(
                claimId = version.claimId,
                claimRevision = version.nextRevision,
                canonicalClaimJson = canonical.canonicalClaimJson,
                contentHash = canonical.contentHash.value,
                sourceManifestHash = canonical.sourceManifestHash.value,
                reasonCode = version.reason.name,
                createdByRunId = fence.runId,
                createdAtMs = request.committedAtEpochMs,
            ),
        )
        if (DreamPairScopeId.parseOrNull(fence.scopeId.value) != null) {
            synthesisDao.insertClaimExperienceSources(
                version.sources.map { source ->
                    val experience = experienceDao.getExperience(
                        source.authority.memoryId,
                        fence.scopeId.value,
                    ) ?: abort(DreamSynthesisCommitRejection.EVIDENCE_TOMBSTONED)
                    if (experience.experienceEpoch != source.authority.expectedRevision) {
                        abort(DreamSynthesisCommitRejection.EVIDENCE_REVISION_MISMATCH)
                    }
                    DreamClaimExperienceSourceEntity(
                        claimId = version.claimId,
                        claimRevision = version.nextRevision,
                        experienceId = experience.experienceId,
                        experienceEpoch = experience.experienceEpoch,
                        contentDigest = experience.contentDigest,
                        sourceManifestHash = source.authority.expectedSourceManifestHash.value,
                        supportType = source.supportType.name,
                        createdAtMs = request.committedAtEpochMs,
                    )
                },
            )
        } else {
            synthesisDao.insertClaimVersionSources(
                version.sources.map { source ->
                    DreamClaimVersionSourceEntity(
                        claimId = version.claimId,
                        claimRevision = version.nextRevision,
                        memoryId = source.authority.memoryId.toIntOrNull()
                            ?: abort(DreamSynthesisCommitRejection.STORE_CORRUPTION),
                        memoryRevision = source.authority.expectedRevision.toIntExactOrAbort(),
                        memorySemanticHash = source.authority.expectedAuthorityFingerprint.value,
                        memoryEvidenceId = null,
                        supportType = source.supportType.name,
                        createdAtMs = request.committedAtEpochMs,
                    )
                },
            )
        }
    }

    private fun DreamValidatedClaimVersion.toEntity(
        request: DreamSynthesisCommitRequest,
        learnedAtMs: Long,
    ): DreamClaimEntity {
        val canonical = DreamClaimVersionCanonicalV1.encode(this)
        return DreamClaimEntity(
            claimId = claimId,
            scopeId = request.fence.scopeId.value,
            claimRevision = nextRevision,
            claimKey = claimKey,
            storageClass = storageClass.name,
            epistemicType = epistemicType.name,
            subjectKind = subjectKind.name,
            profileSection = profileSection,
            epistemicOrigin = epistemicOrigin.name,
            contentType = contentType.name,
            title = title,
            statement = statement,
            state = nextState.name,
            confidence = confidencePermille / 1_000.0,
            temporalState = temporalState.name,
            validFromMs = validFromEpochMs,
            validToMs = validToEpochMs,
            learnedAtMs = learnedAtMs,
            sourceTimezone = request.fence.sourceTimezoneId,
            claimHash = canonical.contentHash.value,
            createdByRunId = request.fence.runId,
            lastValidatedMemoryEpoch = request.fence.baseMemoryEpoch,
            invalidatedAtMs = request.committedAtEpochMs.takeIf {
                nextState !in setOf(DreamClaimState.ACTIVE_CONTEXTUAL, DreamClaimState.PENDING_REVIEW)
            },
            invalidationReason = reason.name.takeIf {
                nextState !in setOf(DreamClaimState.ACTIVE_CONTEXTUAL, DreamClaimState.PENDING_REVIEW)
            },
            createdAtMs = request.committedAtEpochMs,
            updatedAtMs = request.committedAtEpochMs,
        )
    }

    private suspend fun validatePin(pin: DreamAuthorityPin, nowMs: Long) {
        if (DreamPairScopeId.parseOrNull(pin.scopeId.value) != null) {
            val experience = experienceDao.getExperience(pin.memoryId, pin.scopeId.value)
                ?: abort(DreamSynthesisCommitRejection.EVIDENCE_TOMBSTONED)
            if (experience.status == "DISCARDED") {
                abort(DreamSynthesisCommitRejection.EVIDENCE_TOMBSTONED)
            }
            if (experience.experienceEpoch != pin.expectedRevision) {
                abort(DreamSynthesisCommitRejection.EVIDENCE_REVISION_MISMATCH)
            }
            val projected = experience.toDreamInputCandidate(
                scopeId = pin.scopeId,
                origin = DreamInputCandidateOrigin.AUTHORITY_CHANGE,
                json = json,
            ) ?: abort(DreamSynthesisCommitRejection.STORE_CORRUPTION)
            if (projected.pin.expectedAuthorityFingerprint != pin.expectedAuthorityFingerprint) {
                abort(DreamSynthesisCommitRejection.EVIDENCE_FINGERPRINT_MISMATCH)
            }
            if (projected.pin.expectedSourceManifestHash != pin.expectedSourceManifestHash) {
                abort(DreamSynthesisCommitRejection.EVIDENCE_SOURCE_MANIFEST_MISMATCH)
            }
            return
        }
        val memoryId = pin.memoryId.toIntOrNull()
            ?: abort(DreamSynthesisCommitRejection.EVIDENCE_REVISION_MISMATCH)
        val memory = memoryDao.getMemoryById(memoryId, pin.scopeId.value)
            ?: abort(DreamSynthesisCommitRejection.EVIDENCE_TOMBSTONED)
        if (pin.scopeId != DreamScopeId.requireCanonical(memory.assistantId)) {
            abort(DreamSynthesisCommitRejection.EVIDENCE_SCOPE_MISMATCH)
        }
        if (memory.revision.toLong() != pin.expectedRevision ||
            pin.expectedRevision > Int.MAX_VALUE
        ) {
            abort(DreamSynthesisCommitRejection.EVIDENCE_REVISION_MISMATCH)
        }
        if (!memory.isActiveAt(nowMs)) abort(DreamSynthesisCommitRejection.EVIDENCE_TOMBSTONED)
        if (memoryV2Dao.findRevision(memoryId, memory.revision, pin.scopeId.value) == null) {
            abort(DreamSynthesisCommitRejection.EVIDENCE_REVISION_MISMATCH)
        }
        val authority = memory.toDreamAuthority(pin.scopeId)
            ?: abort(DreamSynthesisCommitRejection.STORE_CORRUPTION)
        if (DreamAuthorityFingerprintV1.compute(authority) != pin.expectedAuthorityFingerprint) {
            abort(DreamSynthesisCommitRejection.EVIDENCE_FINGERPRINT_MISMATCH)
        }
        if (DreamAuthorityFingerprintV1.sourceManifestHash(authority.sources) !=
            pin.expectedSourceManifestHash
        ) {
            abort(DreamSynthesisCommitRejection.EVIDENCE_SOURCE_MANIFEST_MISMATCH)
        }
    }

    /** Historical invalidation provenance requires only scoped referential existence. */
    private suspend fun validateHistoricalPin(pin: DreamAuthorityPin, scopeId: DreamScopeId) {
        if (pin.scopeId != scopeId) {
            abort(DreamSynthesisCommitRejection.EVIDENCE_SCOPE_MISMATCH)
        }
        if (DreamPairScopeId.parseOrNull(scopeId.value) != null) {
            val experience = experienceDao.getExperience(pin.memoryId, scopeId.value)
                ?: abort(DreamSynthesisCommitRejection.EVIDENCE_REVISION_MISMATCH)
            if (experience.experienceEpoch != pin.expectedRevision) {
                abort(DreamSynthesisCommitRejection.EVIDENCE_REVISION_MISMATCH)
            }
            return
        }
        val memoryId = pin.memoryId.toIntOrNull()
            ?: abort(DreamSynthesisCommitRejection.EVIDENCE_REVISION_MISMATCH)
        val revision = pin.expectedRevision
        if (revision !in 1L..Int.MAX_VALUE.toLong() ||
            memoryV2Dao.findRevision(memoryId, revision.toInt(), scopeId.value) == null
        ) {
            abort(DreamSynthesisCommitRejection.EVIDENCE_REVISION_MISMATCH)
        }
    }

    private suspend fun loadClaims(scopeId: DreamScopeId, forensicsRunId: String? = null): List<DreamClaimHead>? {
        val entities = synthesisDao.listClaims(scopeId.value, MAX_DREAM_CLAIMS + 1)
        if (entities.size > MAX_DREAM_CLAIMS) {
            recordSeedForensics(forensicsRunId, scopeId, "SEED_CLAIM_COUNT_OVERFLOW", entities.size.toString())
            return null
        }
        val claims = ArrayList<DreamClaimHead>(entities.size)
        for (entity in entities) {
            val model = entity.toModel(scopeId, lenientSources = true, forensicsRunId = forensicsRunId)
            if (model == null) {
                // Still fatal (genuine corruption), but now the next device failure names the claim.
                recordSeedForensics(forensicsRunId, scopeId, "SEED_CLAIM_UNPROJECTABLE", entity.claimId)
                return null
            }
            claims += model
        }
        return claims.sortedWith(compareBy({ it.claimKey }, { it.claimId }, { it.revision }))
    }

    private suspend fun DreamClaimEntity.toModel(
        scopeId: DreamScopeId,
        lenientSources: Boolean = false,
        forensicsRunId: String? = null,
    ): DreamClaimHead? {
        val state = enumOrNull<DreamClaimState>(state) ?: return null
        val sources = if (state == DreamClaimState.TOMBSTONED) {
            emptyList()
        } else if (DreamPairScopeId.parseOrNull(scopeId.value) != null) {
            synthesisDao.listClaimExperienceSources(claimId, claimRevision).map { source ->
                projectPairClaimSource(scopeId, claimId, source, lenientSources, forensicsRunId) ?: return null
            }
        } else {
            synthesisDao.listClaimVersionSources(claimId, claimRevision).map { source ->
                val revision = memoryV2Dao.findRevision(source.memoryId, source.memoryRevision, scopeId.value)
                    ?: return null
                val manifest = revision.toAuthoritySources() ?: return null
                DreamClaimSourcePin(
                    authority = DreamAuthorityPin(
                        scopeId = scopeId,
                        memoryId = source.memoryId.toString(),
                        expectedRevision = source.memoryRevision.toLong(),
                        expectedAuthorityFingerprint = try {
                            DreamSha256(source.memorySemanticHash)
                        } catch (_: Exception) {
                            return null
                        },
                        expectedSourceManifestHash = DreamAuthorityFingerprintV1.sourceManifestHash(manifest),
                    ),
                    supportType = enumOrNull<DreamSupportType>(source.supportType) ?: return null,
                    directAuthority = true,
                )
            }
        }
        val confidencePermille = (confidence * 1_000.0).roundToInt()
        if (confidencePermille !in 0..1_000) return null
        return try {
            DreamClaimHead(
                claimId = claimId,
                scopeId = scopeId,
                revision = claimRevision,
                claimKey = claimKey,
                storageClass = enumValueOf<DreamStorageClass>(storageClass),
                epistemicType = enumValueOf<DreamEpistemicType>(epistemicType),
                subjectKind = enumValueOf(subjectKind),
                profileSection = profileSection,
                epistemicOrigin = enumValueOf(epistemicOrigin),
                contentType = enumValueOf(contentType),
                state = state,
                title = title,
                statement = statement,
                confidencePermille = confidencePermille,
                temporalState = enumValueOf<TemporalState>(temporalState),
                validFromEpochMs = validFromMs,
                validToEpochMs = validToMs,
                versionHash = DreamSha256(claimHash),
                sources = sources,
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Projects one persisted pair-claim provenance row back into a source pin.
     *
     * Strict mode (default; the commit path) keeps the historical contract: any drift in the
     * referenced Experience fails the projection. Lenient mode (the seed/loadClaims path, since
     * 2026-10-13) rebuilds the pin from the recorded provenance row instead, so
     * `deterministicInvalidations()` can classify the drift (SOURCE_MISSING / SOURCE_TOMBSTONED /
     * SOURCE_REVISION_CHANGED / SOURCE_HASH_CHANGED) and synthesis can repair the claim.
     *
     * Why: device evidence (2.5.11-2.5.13) showed every later run ending in a bare STORE_FAILURE.
     * One drifted source made `loadClaims` return null, turning the whole seed into
     * STORE_CORRUPTION before the invalidation path could ever run — a deterministic deadlock no
     * retry could clear (lag only grew, 570 → 597). Genuine corruption (bad enum, malformed
     * digest, non-positive epoch) stays fatal in either mode.
     */
    private suspend fun projectPairClaimSource(
        scopeId: DreamScopeId,
        claimId: String,
        source: me.rerere.rikkahub.data.db.entity.DreamClaimExperienceSourceEntity,
        lenient: Boolean,
        forensicsRunId: String?,
    ): DreamClaimSourcePin? {
        val supportType = enumOrNull<DreamSupportType>(source.supportType) ?: return null
        val experience = experienceDao.getExperience(source.experienceId, scopeId.value)
        if (experience != null &&
            experience.status != "DISCARDED" &&
            experience.experienceEpoch == source.experienceEpoch &&
            experience.contentDigest == source.contentDigest
        ) {
            val candidate = experience.toDreamInputCandidate(
                scopeId = scopeId,
                origin = DreamInputCandidateOrigin.AUTHORITY_CHANGE,
                json = json,
            )
            if (candidate != null && candidate.pin.expectedSourceManifestHash.value == source.sourceManifestHash) {
                return DreamClaimSourcePin(
                    authority = candidate.pin,
                    supportType = supportType,
                    directAuthority = true,
                )
            }
        }
        if (!lenient) return null
        val rebuilt = rebuildRecordedSourcePin(scopeId, source) ?: return null
        recordSeedForensics(
            runId = forensicsRunId,
            scopeId = scopeId,
            stage = "SEED_SOURCE_DEGRADED",
            detail = "$claimId:${source.experienceId}",
        )
        return DreamClaimSourcePin(authority = rebuilt, supportType = supportType, directAuthority = true)
    }

    /** Error-path-only forensics; enum names, opaque ids and counters only, never user text. */
    private fun recordSeedForensics(
        runId: String?,
        scopeId: DreamScopeId,
        stage: String,
        detail: String? = null,
    ) {
        val sidecar = forensics ?: return
        sidecar.record(
            DreamSynthesisForensicsEvent(
                recordedAtEpochMs = System.currentTimeMillis(),
                runId = runId ?: "unattributed",
                pairScope = DreamPairScopeId.parseOrNull(scopeId.value) != null,
                stage = stage,
                reason = detail,
            ),
        )
    }

    /** Seed rejection that names its exact check, so the next device failure is self-locating. */
    private fun rejectSeedCorruption(
        runId: String?,
        scopeId: DreamScopeId,
        stage: String,
        detail: String? = null,
    ): ReadDreamInputSeedResult.Rejected {
        recordSeedForensics(runId, scopeId, stage, detail)
        return ReadDreamInputSeedResult.Rejected(DreamSynthesisStoreRejection.STORE_CORRUPTION)
    }

    private suspend fun deterministicInvalidations(
        claims: List<DreamClaimHead>,
        fence: DreamSynthesisFence,
    ): List<DreamDeterministicInvalidation>? {
        val invalidations = mutableListOf<DreamDeterministicInvalidation>()
        for (claim in claims) {
            if (claim.state in setOf(
                    DreamClaimState.TOMBSTONED,
                    DreamClaimState.INVALID,
                    DreamClaimState.SUPERSEDED,
                )
            ) {
                continue
            }
            var reason: DreamDeterministicInvalidationReason? = null
            for (source in claim.sources) {
                reason = sourceInvalidationReason(source, fence)
                if (reason != null) break
            }
            if (reason != null) {
                invalidations += DreamDeterministicInvalidation(claim.claimId, claim.revision, reason)
            }
        }
        return invalidations.sortedBy(DreamDeterministicInvalidation::claimId)
    }

    private suspend fun sourceInvalidationReason(
        source: DreamClaimSourcePin,
        fence: DreamSynthesisFence,
    ): DreamDeterministicInvalidationReason? {
        if (DreamPairScopeId.parseOrNull(fence.scopeId.value) != null) {
            val experience = experienceDao.getExperience(source.authority.memoryId, fence.scopeId.value)
                ?: return DreamDeterministicInvalidationReason.SOURCE_MISSING
            if (experience.status == "DISCARDED") {
                return DreamDeterministicInvalidationReason.SOURCE_TOMBSTONED
            }
            if (experience.experienceEpoch != source.authority.expectedRevision) {
                return DreamDeterministicInvalidationReason.SOURCE_REVISION_CHANGED
            }
            val projected = experience.toDreamInputCandidate(
                scopeId = fence.scopeId,
                origin = DreamInputCandidateOrigin.AUTHORITY_CHANGE,
                json = json,
            ) ?: return DreamDeterministicInvalidationReason.SOURCE_HASH_CHANGED
            if (projected.pin.expectedAuthorityFingerprint != source.authority.expectedAuthorityFingerprint ||
                projected.pin.expectedSourceManifestHash != source.authority.expectedSourceManifestHash
            ) {
                return DreamDeterministicInvalidationReason.SOURCE_HASH_CHANGED
            }
            return null
        }
        val memoryId = source.authority.memoryId.toIntOrNull()
            ?: return DreamDeterministicInvalidationReason.SOURCE_MISSING
        val memory = memoryDao.getMemoryById(memoryId, fence.scopeId.value)
            ?: return DreamDeterministicInvalidationReason.SOURCE_MISSING
        if (memory.lifecycleStatus != MemoryLifecycleStatus.ACTIVE.name) {
            return DreamDeterministicInvalidationReason.SOURCE_TOMBSTONED
        }
        if (memory.expiresAtMs != null && memory.expiresAtMs <= fence.frozenNowEpochMs) {
            return DreamDeterministicInvalidationReason.SOURCE_EXPIRED
        }
        if (memory.revision.toLong() != source.authority.expectedRevision) {
            return DreamDeterministicInvalidationReason.SOURCE_REVISION_CHANGED
        }
        val authority = memory.toDreamAuthority(fence.scopeId)
            ?: return DreamDeterministicInvalidationReason.SOURCE_HASH_CHANGED
        if (DreamAuthorityFingerprintV1.compute(authority) != source.authority.expectedAuthorityFingerprint ||
            DreamAuthorityFingerprintV1.sourceManifestHash(authority.sources) !=
            source.authority.expectedSourceManifestHash
        ) {
            return DreamDeterministicInvalidationReason.SOURCE_HASH_CHANGED
        }
        return null
    }

    private fun MemoryEntity.toDreamAuthority(scopeId: DreamScopeId): DreamAuthorityMemory? {
        val projectedUpdatedAtMs = dreamUpdatedAtEpochMsOrNull() ?: return null
        return try {
            DreamAuthorityMemory(
                scopeId = scopeId,
                memoryId = id.toString(),
                revision = revision.toLong(),
                title = title,
                content = content,
                kind = enumValueOf<MemoryKind>(memoryKind),
                attribution = enumValueOf<MemoryAttribution>(attribution),
                truthStatus = enumValueOf<MemoryTruthStatus>(truthStatus),
                lifecycleStatus = enumValueOf<MemoryLifecycleStatus>(lifecycleStatus),
                approvalSource = enumValueOf<MemoryApprovalSource>(approvalSource),
                tags = json.decodeStringList(tagsJson),
                createdAtEpochMs = createdAtMs,
                updatedAtEpochMs = projectedUpdatedAtMs,
                occurredAtEpochMs = occurredAtMs,
                expiresAtEpochMs = expiresAtMs,
                originAssistantId = originAssistantId,
                participants = json.decodeStringList(participantsJson),
                outcome = outcome,
                sources = json.decodeSourceIdentities(sourceIdentitiesJson).map { it.toAuthoritySource() },
                // Hard-deleted authority has no row. Non-active rows retain their explicit lifecycle
                // and are rejected by the active-authority projection before reaching this mapping.
                tombstoned = false,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun DreamAuthorityMemory.toPin() = DreamAuthorityPin(
        scopeId = scopeId,
        memoryId = memoryId,
        expectedRevision = revision,
        expectedAuthorityFingerprint = DreamAuthorityFingerprintV1.compute(this),
        expectedSourceManifestHash = DreamAuthorityFingerprintV1.sourceManifestHash(sources),
    )

    private fun MemoryRevisionEntity.toAuthoritySources(): List<DreamAuthoritySource>? = try {
        json.decodeSourceIdentities(sourceIdentitiesJson).map { it.toAuthoritySource() }
    } catch (_: Exception) {
        null
    }

    private fun MemorySourceIdentity.toAuthoritySource() = DreamAuthoritySource(
        conversationId = conversationId,
        messageId = messageId,
        role = role,
        sourceKind = sourceKind,
        consumedTextDigest = DreamSha256(consumedTextDigest),
        evidenceGroupId = evidenceGroupId,
    )

    private fun DreamAuthoritySource.toLocator(scopeId: DreamScopeId) = DreamSourceLocator(
        scopeId = scopeId,
        conversationId = conversationId,
        messageId = messageId,
        role = role,
        sourceKind = sourceKind,
        expectedConsumedTextDigest = consumedTextDigest,
        evidenceGroupId = evidenceGroupId,
    )

    private fun Json.decodeSourceIdentities(raw: String): List<MemorySourceIdentity> =
        decodeFromString<List<MemorySourceIdentity>>(raw)

    private fun Json.decodeStringList(raw: String): List<String> = decodeFromString<List<String>>(raw)

    private fun MemoryEntity.isActiveAt(nowMs: Long): Boolean =
        lifecycleStatus == MemoryLifecycleStatus.ACTIVE.name &&
            truthStatus == MemoryTruthStatus.CONFIRMED.name &&
            (expiresAtMs == null || expiresAtMs > nowMs)

    private suspend fun ownedContext(fence: DreamSynthesisFence, nowMs: Long): OwnedContextResult {
        val pairState = DreamPairScopeId.parseOrNull(fence.scopeId.value)?.let {
            experienceDao.getState(fence.scopeId.value)
                ?: return ownedRejected(
                    DreamSynthesisStoreRejection.RUN_NOT_FOUND,
                    DreamSynthesisCommitRejection.LEASE_MISSING,
                )
        }
        val activeRunId: String?
        val activeRunLeaseUntilMs: Long?
        if (pairState != null) {
            if (pairState.experienceEpoch != fence.baseExperienceEpoch) return ownedRejected(
                DreamSynthesisStoreRejection.FENCE_CONFLICT,
                DreamSynthesisCommitRejection.MEMORY_EPOCH_CONFLICT,
            )
            if (pairState.appliedExperienceEpoch != fence.baseAppliedExperienceEpoch ||
                pairState.profileRevision != fence.baseDreamRevision
            ) return ownedRejected(
                DreamSynthesisStoreRejection.FENCE_CONFLICT,
                DreamSynthesisCommitRejection.DREAM_REVISION_CONFLICT,
            )
            if (pairState.activeSnapshotId != fence.expectedActiveSnapshotId) return ownedRejected(
                DreamSynthesisStoreRejection.FENCE_CONFLICT,
                DreamSynthesisCommitRejection.ACTIVE_SNAPSHOT_CONFLICT,
            )
            activeRunId = pairState.activeRunId
            activeRunLeaseUntilMs = pairState.activeRunLeaseUntilMs
        } else {
            val state = dreamDao.getScopeState(fence.scopeId.value)
                ?: return ownedRejected(
                    DreamSynthesisStoreRejection.RUN_NOT_FOUND,
                    DreamSynthesisCommitRejection.LEASE_MISSING,
                )
            if (state.memoryEpoch != fence.baseMemoryEpoch) return ownedRejected(
                DreamSynthesisStoreRejection.FENCE_CONFLICT,
                DreamSynthesisCommitRejection.MEMORY_EPOCH_CONFLICT,
            )
            if (state.lastAppliedMemoryEpoch != fence.baseLastAppliedMemoryEpoch ||
                state.dreamStateRevision != fence.baseDreamRevision
            ) return ownedRejected(
                DreamSynthesisStoreRejection.FENCE_CONFLICT,
                DreamSynthesisCommitRejection.DREAM_REVISION_CONFLICT,
            )
            if (state.activeSnapshotId != fence.expectedActiveSnapshotId) return ownedRejected(
                DreamSynthesisStoreRejection.FENCE_CONFLICT,
                DreamSynthesisCommitRejection.ACTIVE_SNAPSHOT_CONFLICT,
            )
            activeRunId = state.activeRunId
            activeRunLeaseUntilMs = state.activeRunLeaseUntilMs
        }
        if (activeRunId != fence.runId || activeRunLeaseUntilMs == null) return ownedRejected(
            DreamSynthesisStoreRejection.FENCE_CONFLICT,
            DreamSynthesisCommitRejection.LEASE_MISSING,
        )
        if (activeRunLeaseUntilMs <= nowMs) return ownedRejected(
            DreamSynthesisStoreRejection.LEASE_EXPIRED,
            DreamSynthesisCommitRejection.LEASE_EXPIRED,
        )
        val run = dreamDao.getRunById(fence.runId) ?: return ownedRejected(
            DreamSynthesisStoreRejection.RUN_NOT_FOUND,
            DreamSynthesisCommitRejection.RUN_NOT_RUNNING,
        )
        if (run.scopeId != fence.scopeId.value) return ownedRejected(
            DreamSynthesisStoreRejection.SCOPE_MISMATCH,
            DreamSynthesisCommitRejection.FENCE_CONFLICT,
        )
        if (run.status != DreamRunStatus.RUNNING.name) return ownedRejected(
            DreamSynthesisStoreRejection.RUN_NOT_RUNNING,
            DreamSynthesisCommitRejection.RUN_NOT_RUNNING,
        )
        if (run.leaseOwner != fence.leaseOwner) return ownedRejected(
            DreamSynthesisStoreRejection.OWNER_MISMATCH,
            DreamSynthesisCommitRejection.LEASE_OWNER_MISMATCH,
        )
        if (run.leaseUntilMs != activeRunLeaseUntilMs || run.leaseUntilMs <= nowMs) return ownedRejected(
            DreamSynthesisStoreRejection.LEASE_EXPIRED,
            DreamSynthesisCommitRejection.LEASE_EXPIRED,
        )
        if (run.baseMemoryEpoch != fence.baseMemoryEpoch || run.baseDreamRevision != fence.baseDreamRevision) {
            return ownedRejected(
                DreamSynthesisStoreRejection.FENCE_CONFLICT,
                DreamSynthesisCommitRejection.FENCE_CONFLICT,
            )
        }
        if (nowMs < run.updatedAtMs) return ownedRejected(
            DreamSynthesisStoreRejection.FENCE_CONFLICT,
            DreamSynthesisCommitRejection.FENCE_CONFLICT,
        )
        return OwnedContextResult.Ready(run)
    }

    private suspend fun classifyCommitCas(
        fence: DreamSynthesisFence,
        nowMs: Long,
    ): DreamSynthesisCommitRejection {
        return when (val context = ownedContext(fence, nowMs)) {
            is OwnedContextResult.Ready -> DreamSynthesisCommitRejection.STORE_CORRUPTION
            is OwnedContextResult.Rejected -> context.commitReason
        }
    }

    private suspend fun failOwnedRun(
        fence: DreamSynthesisFence,
        failureCode: DreamRunFailureCode,
        nowMs: Long,
    ): DreamSynthesisStoreResult {
        if (DreamPairScopeId.parseOrNull(fence.scopeId.value) != null) {
            return try {
                database.withTransaction {
                    when (val owned = ownedContext(fence, nowMs)) {
                        is OwnedContextResult.Rejected ->
                            return@withTransaction DreamSynthesisStoreResult.Rejected(owned.storeReason)
                        is OwnedContextResult.Ready -> Unit
                    }
                    if (experienceDao.finishRunMirror(
                            runId = fence.runId,
                            pairScopeId = fence.scopeId.value,
                            leaseOwner = fence.leaseOwner,
                            terminalStatus = DreamRunStatus.FAILED.name,
                            failureCode = failureCode.name,
                            nowMs = nowMs,
                        ) != 1
                    ) throw PairLeaseAbort()
                    if (experienceDao.releaseLease(
                            pairScopeId = fence.scopeId.value,
                            runId = fence.runId,
                            nowMs = nowMs,
                            reasonCode = AuthorityChangeReason.RUN_FINISHED.name,
                        ) != 1
                    ) throw PairLeaseAbort()
                    DreamSynthesisStoreResult.Accepted
                }
            } catch (_: PairLeaseAbort) {
                DreamSynthesisStoreResult.Rejected(DreamSynthesisStoreRejection.FENCE_CONFLICT)
            }
        }
        return when (val result = observerStore.fail(
            FailDreamRunRequest(
                runId = fence.runId,
                scopeId = fence.scopeId,
                leaseOwner = fence.leaseOwner,
                failureCode = failureCode,
                nowMs = nowMs,
            ),
        )) {
            is FinishDreamRunResult.Finished -> DreamSynthesisStoreResult.Accepted
            is FinishDreamRunResult.Rejected -> DreamSynthesisStoreResult.Rejected(
                result.reason.toSynthesisRejection(),
            )
        }
    }

    private suspend fun generationEnabled(scopeId: DreamScopeId): Boolean {
        val flags = featureFlags.flagsFor(scopeId)
        return flags.allowsSynthesisGeneration()
    }

}

/**
 * Pre-V2 legacy rows used `0` as the persisted `updated_at_ms` sentinel even after a real
 * `created_at_ms` had been assigned. Preserve the authoritative row unchanged and normalize only
 * this exact historical shape at the Dream projection boundary. Modern timestamp inversions still
 * fail closed instead of being silently repaired.
 */
internal fun MemoryEntity.dreamUpdatedAtEpochMsOrNull(): Long? = when {
    createdAtMs < 0L -> null
    updatedAtMs >= createdAtMs -> updatedAtMs
    sourceType == "LEGACY" && updatedAtMs == 0L && createdAtMs > 0L -> createdAtMs
    else -> null
}

private sealed interface OwnedContextResult {
    data class Ready(val run: DreamRunEntity) : OwnedContextResult

    data class Rejected(
        val storeReason: DreamSynthesisStoreRejection,
        val commitReason: DreamSynthesisCommitRejection,
    ) : OwnedContextResult
}

private fun ownedRejected(
    storeReason: DreamSynthesisStoreRejection,
    commitReason: DreamSynthesisCommitRejection,
) = OwnedContextResult.Rejected(storeReason, commitReason)

private fun DreamSynthesisMode.toRunMode(): DreamRunMode = when (this) {
    DreamSynthesisMode.INCREMENTAL -> DreamRunMode.INCREMENTAL
    DreamSynthesisMode.FULL -> DreamRunMode.FULL
}

private fun DreamRunEntity.isOwnedBy(scopeId: DreamScopeId, owner: String): Boolean =
    this.scopeId == scopeId.value && leaseOwner == owner

private fun DreamStoreRejection.toSynthesisRejection(): DreamSynthesisStoreRejection = when (this) {
    DreamStoreRejection.NOT_FOUND -> DreamSynthesisStoreRejection.RUN_NOT_FOUND
    DreamStoreRejection.SCOPE_MISMATCH -> DreamSynthesisStoreRejection.SCOPE_MISMATCH
    DreamStoreRejection.OWNER_MISMATCH -> DreamSynthesisStoreRejection.OWNER_MISMATCH
    DreamStoreRejection.LEASE_EXPIRED,
    DreamStoreRejection.CLOCK_ROLLBACK,
    -> DreamSynthesisStoreRejection.LEASE_EXPIRED
    DreamStoreRejection.STATUS_MISMATCH -> DreamSynthesisStoreRejection.RUN_NOT_RUNNING
    else -> DreamSynthesisStoreRejection.FENCE_CONFLICT
}

private fun hasCompleteEpochs(values: List<Long>, fromExclusive: Long, throughInclusive: Long): Boolean {
    val epochs = values.distinct()
    var expected = fromExclusive
    for (epoch in epochs) {
        if (expected == Long.MAX_VALUE || epoch != expected + 1L) return false
        expected = epoch
    }
    return expected == throughInclusive
}

private inline fun <reified T : Enum<T>> enumOrNull(raw: String): T? =
    enumValues<T>().firstOrNull { it.name == raw }

/**
 * Rebuilds the authority pin recorded for one pair-claim provenance row. Used by the lenient
 * seed path so a drifted Experience degrades into a deterministic invalidation instead of
 * failing the whole seed; returns null only when the row itself is malformed (bad digest format,
 * non-positive epoch).
 */
internal fun rebuildRecordedSourcePin(
    scopeId: DreamScopeId,
    source: me.rerere.rikkahub.data.db.entity.DreamClaimExperienceSourceEntity,
): DreamAuthorityPin? = try {
    DreamAuthorityPin(
        scopeId = scopeId,
        memoryId = source.experienceId,
        expectedRevision = source.experienceEpoch,
        expectedAuthorityFingerprint = DreamSha256(source.contentDigest),
        expectedSourceManifestHash = DreamSha256(source.sourceManifestHash),
    )
} catch (_: Exception) {
    null
}

private fun Long.toIntExactOrAbort(): Int {
    if (this !in 1L..Int.MAX_VALUE.toLong()) abort(DreamSynthesisCommitRejection.STORE_CORRUPTION)
    return toInt()
}

private class CommitAbort(
    val reason: DreamSynthesisCommitRejection,
    /** Allow-listed check name; distinguishes two aborts that share one rejection code. */
    val stage: String,
) : RuntimeException()

private class PairLeaseAbort : RuntimeException()

/**
 * Abort the enclosing commit transaction with a durable, allow-listed reason. [stage] names the
 * exact check so the forensics sidecar can tell apart aborts that share a rejection code — for
 * example a checkpoint precondition and a claim-version conflict both surface as
 * STORE_CORRUPTION on the run row. Never carries model or user text.
 */
private fun abort(
    reason: DreamSynthesisCommitRejection,
    stage: String = "COMMIT",
): Nothing = throw CommitAbort(reason, stage)

private const val DREAM_SYNTHESIS_INITIAL_LEASE_MS = 15L * 60_000L
private const val DREAM_SYNTHESIS_STORE_LOG_TAG = "DreamSynthesisStore"
private const val MAX_DREAM_INPUT_CANDIDATES = 8_192
private const val MAX_PAIR_DREAM_INPUT_CANDIDATE_SCAN = 512
private const val MAX_DREAM_CLAIMS = 10_000
private const val DREAM_MEMORY_ID_QUERY_CHUNK = 500
private const val SYNTHESIS_COMMIT_REASON = "DREAM_SYNTHESIS_COMMIT"
private val DREAM_SYNTHESIS_INPUT_BUDGET = DreamInputBudget(
    maxMemories = 128,
    maxClaims = 128,
    maxInputUtf8Bytes = 96_000,
    maxSourceUtf8Bytes = 32_000,
)
