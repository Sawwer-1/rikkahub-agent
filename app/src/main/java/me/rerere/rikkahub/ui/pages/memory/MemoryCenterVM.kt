package me.rerere.rikkahub.ui.pages.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.dao.MemoryCaptureStatusCounts
import me.rerere.rikkahub.data.db.dao.MemoryV2Dao
import me.rerere.rikkahub.data.db.dao.PolicyNoteDao
import me.rerere.rikkahub.data.db.entity.PolicyNoteEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.DEFAULT_MEMORY_PROMPT_MAX_CHARS
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.memory.MemoryAutoSaveMode
import me.rerere.rikkahub.memory.MemoryCaptureOrigin
import me.rerere.rikkahub.memory.MemoryMutationCommand
import me.rerere.rikkahub.memory.MemoryMutationCoordinator
import me.rerere.rikkahub.memory.MemoryMutationResult
import me.rerere.rikkahub.memory.MemoryReviewCommand
import me.rerere.rikkahub.memory.MemoryReviewResult
import me.rerere.rikkahub.memory.MemoryRelationReviewCommand
import me.rerere.rikkahub.memory.MemoryRelationReviewResult
import me.rerere.rikkahub.memory.MemoryV2Coordinator
import me.rerere.rikkahub.memory.MemoryWorkRequest
import me.rerere.rikkahub.memory.MemoryWorkScheduler
import me.rerere.rikkahub.memory.MemoryApprovalSource
import me.rerere.rikkahub.memory.MemoryWriteInput
import me.rerere.rikkahub.data.db.entity.MemoryCandidateEntity
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.MemoryRelationCandidateEntity
import me.rerere.rikkahub.memory.MemoryCandidateAction
import me.rerere.rikkahub.memory.MemoryCandidatePolicy
import me.rerere.rikkahub.memory.MemoryKind
import me.rerere.rikkahub.memory.MemoryProposal
import me.rerere.rikkahub.memory.resolveMemoryExtractionModel
import me.rerere.rikkahub.memory.dreaming.diagnostics.DreamObserverDiagnostics
import me.rerere.rikkahub.memory.dreaming.diagnostics.DreamObserverScopeDiagnostic
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.review.DreamClaimMutationTarget
import me.rerere.rikkahub.memory.dreaming.review.DreamCorrectionDraft
import me.rerere.rikkahub.memory.dreaming.review.DreamCorrectionResult
import me.rerere.rikkahub.memory.dreaming.review.DreamEvidenceRevealResult
import me.rerere.rikkahub.memory.dreaming.review.DreamEvidenceSummary
import me.rerere.rikkahub.memory.dreaming.review.DreamReviewMutationResult
import me.rerere.rikkahub.memory.dreaming.review.DreamReviewProjection
import me.rerere.rikkahub.memory.dreaming.review.DreamReviewReadResult
import me.rerere.rikkahub.memory.dreaming.review.DreamReviewRepository
import me.rerere.rikkahub.memory.dreaming.review.DreamReviewFence
import android.content.Context
import me.rerere.rikkahub.data.db.dao.DreamDao
import me.rerere.rikkahub.memory.dreaming.runtime.DreamAppIdleDecision
import me.rerere.rikkahub.memory.dreaming.runtime.DreamAppIdleTracker
import me.rerere.rikkahub.memory.dreaming.runtime.DreamDailyUsageQuery
import me.rerere.rikkahub.memory.dreaming.runtime.DreamNetworkPolicy
import me.rerere.rikkahub.memory.dreaming.runtime.dreamUtcDayWindowOrNull
import me.rerere.rikkahub.memory.dreaming.runtime.DreamSynthesisCoordinator
import me.rerere.rikkahub.memory.dreaming.work.DreamSynthesisScanReason
import me.rerere.rikkahub.memory.dreaming.runtime.DreamSynthesisScheduleDeferral
import me.rerere.rikkahub.memory.dreaming.work.readDreamWorkerEnvironment
import me.rerere.rikkahub.memory.dreaming.runtime.DreamingCostPolicy
import me.rerere.rikkahub.memory.dreaming.runtime.DreamingScopePreferenceMutation
import me.rerere.rikkahub.memory.dreaming.runtime.DreamingScopePreferences
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryCenterVM(
    id: String,
    private val settingsStore: SettingsStore,
    private val memoryDao: MemoryDAO,
    private val memoryV2Dao: MemoryV2Dao,
    private val memoryRepository: MemoryRepository,
    private val mutationCoordinator: MemoryMutationCoordinator,
    private val coordinator: MemoryV2Coordinator,
    private val scheduler: MemoryWorkScheduler,
    private val conversationRepository: ConversationRepository,
    private val dreamObserverDiagnostics: DreamObserverDiagnostics,
    private val dreamReviewRepository: DreamReviewRepository,
    private val dreamSynthesisCoordinator: DreamSynthesisCoordinator,
    private val learnedPolicyDao: PolicyNoteDao,
    private val dreamDao: DreamDao,
    private val dreamUsageStore: me.rerere.rikkahub.memory.dreaming.runtime.DreamDailyUsageStore,
    private val dreamIdleTracker: DreamAppIdleTracker,
    private val appContext: Context,
) : ViewModel() {
    private val candidatePolicy = MemoryCandidatePolicy()
    private val assistantId = Uuid.parse(id)
    val assistant = settingsStore.settingsFlow.map { settings ->
        settings.getAssistantById(assistantId) ?: Assistant(id = assistantId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Assistant(id = assistantId))
    val assistants = settingsStore.settingsFlow.map { it.assistants }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            listOf(Assistant(id = assistantId)),
        )

    val viewGlobal = MutableStateFlow(
        settingsStore.settingsFlow.value.getAssistantById(assistantId)?.useGlobalMemory == true,
    )
    val libraryFilter = MutableStateFlow(MemoryLibraryFilter())
    private val scopeId = combine(assistant, viewGlobal) { assistant, global ->
        if (global) MemoryRepository.GLOBAL_MEMORY_ID else assistant.id.toString()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), assistantId.toString())

    val developerMode = settingsStore.settingsFlow
        .map { settings -> settings.developerMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val observerDiagnosticsRefresh = MutableStateFlow(0L)
    val observerDiagnostic = combine(
        scopeId,
        developerMode,
        observerDiagnosticsRefresh,
    ) { scope, enabled, _ -> scope to enabled }
        .flatMapLatest { (scope, enabled) ->
            flow<DreamObserverScopeDiagnostic?> {
                if (!enabled) {
                    emit(null)
                    return@flow
                }
                val typedScope = DreamScopeId.parseOrNull(scope)
                emit(typedScope?.let { dreamObserverDiagnostics.readScope(it) })
            }.catch { error ->
                if (error is CancellationException) throw error
                emit(null)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val pairDreamScopeId = DreamScopeId.pairScope(assistantId)

    val dreamProjection = dreamReviewRepository.observeScope(pairDreamScopeId)
        .map<DreamReviewProjection, DreamReviewProjection?> { it }
        .catch { error ->
            if (error is CancellationException) throw error
            emit(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val dreamingScopePreferences = settingsStore.settingsFlow
        .map { settings -> settings.dreamingPreferences.forScope(pairDreamScopeId) }
        .stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        DreamingScopePreferences(),
    )

    val dreamingCostPolicy = settingsStore.settingsFlow
        .map { settings -> settings.dreamingPreferences.failClosed().costPolicy }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            DreamingCostPolicy(),
        )

    val dreamDetailState = MutableStateFlow<MemoryDreamDetailState>(MemoryDreamDetailState.Closed)

    /** Command authorization must not wait for the asynchronous combine/stateIn propagation. */
    private fun currentCommandScopeId(): String = if (viewGlobal.value) {
        MemoryRepository.GLOBAL_MEMORY_ID
    } else {
        assistantId.toString()
    }

    val library = combine(scopeId, libraryFilter) { scope, filter -> scope to filter }
        .flatMapLatest { (scope, filter) ->
            Pager(PagingConfig(pageSize = 30, prefetchDistance = 10)) {
                memoryDao.pagingLibrary(
                    scopeId = scope,
                    includeArchived = filter.includeArchived,
                    nowMs = System.currentTimeMillis(),
                    query = filter.query.trim(),
                    kind = filter.kind?.name,
                    sourceType = filter.sourceType,
                    tag = filter.tag.trim(),
                    sort = filter.sort.name,
                )
            }.flow
        }.cachedIn(viewModelScope)

    val candidates = scopeId.flatMapLatest { scope ->
        Pager(PagingConfig(pageSize = 20, prefetchDistance = 5)) {
            memoryV2Dao.pagingPendingCandidates(scope)
        }.flow
    }.cachedIn(viewModelScope)

    val relationCandidates = scopeId
        .flatMapLatest { scope -> memoryV2Dao.observePendingRelationCandidates(scope) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val stats = scopeId.flatMapLatest { scope ->
        val memoryCounts = combine(
            memoryDao.observeActiveCount(scope, System.currentTimeMillis()),
            memoryDao.observeArchivedCount(scope),
        ) { active, archived -> active to archived }
        val queueCounts = combine(
            memoryV2Dao.observePendingCandidateCount(scope),
            memoryV2Dao.observePendingRelationCandidates(scope).map { it.size },
            memoryV2Dao.observeCaptureStatusCounts(scope),
        ) { memoryReview, relationReview, captures ->
            Triple(memoryReview, relationReview, captures)
        }
        combine(
            memoryCounts,
            queueCounts,
            memoryV2Dao.observeLastProcessedAt(scope),
        ) { memories, queues, last ->
            memoryCenterStats(
                active = memories.first,
                archived = memories.second,
                pendingReview = queues.first,
                captures = queues.third,
                lastProcessedAtMs = last,
                pendingRelationReview = queues.second,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoryCenterStats())

    val latestFailure = scopeId
        .flatMapLatest { scope -> memoryV2Dao.observeLatestFailure(scope) }
        .map { failure ->
            failure?.let { formatMemoryFailureDetail(it.errorCode, it.errorMessage) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val extractionModel = settingsStore.settingsFlow.map { settings ->
        val model = settings.resolveMemoryExtractionModel()
        val provider = model?.findProvider(settings.providers)
        MemoryExtractionModelUiState(
            modelName = model?.displayName?.ifBlank { model.modelId }.orEmpty(),
            providerName = provider?.name.orEmpty(),
            usingFastModel = settings.memoryExtractionModelId == null,
            available = model != null && provider != null && provider.enabled,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        MemoryExtractionModelUiState("", "", true),
    )

    val modelOptions = settingsStore.settingsFlow.map { settings ->
        settings.providers.filter { it.enabled }.flatMap { provider ->
            provider.models.map { model ->
                MemoryModelOption(
                    id = model.id,
                    name = model.displayName.ifBlank { model.modelId },
                    providerName = provider.name,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recallTestState = MutableStateFlow<MemoryRecallTestState>(MemoryRecallTestState.Idle)

    fun refreshObserverDiagnostics() {
        observerDiagnosticsRefresh.update { current ->
            if (current == Long.MAX_VALUE) 0L else current + 1L
        }
    }
    val lastActionMessage = MutableStateFlow<String?>(null)

    // 轻量学习（Part B）：待审查策略
    val pendingPolicies = learnedPolicyDao
        .observeByScopeAndStatus(assistantId.toString(), PolicyNoteEntity.STATUS_PENDING)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun reviewPolicy(policyId: String, confirmed: Boolean) {
        viewModelScope.launch {
            learnedPolicyDao.review(
                policyId = policyId,
                status = if (confirmed) {
                    PolicyNoteEntity.STATUS_CONFIRMED
                } else {
                    PolicyNoteEntity.STATUS_REJECTED
                },
                reviewedAt = System.currentTimeMillis(),
            )
        }
    }

    fun deletePolicy(policyId: String) {
        viewModelScope.launch { learnedPolicyDao.deleteById(policyId) }
    }

    init {
        viewModelScope.launch {
            val configured = settingsStore.settingsFlow.first { settings -> !settings.init }
                .getAssistantById(assistantId)
            if (configured?.useGlobalMemory == true) viewGlobal.value = true
        }
    }

    fun setViewGlobal(global: Boolean) {
        closeDreamClaim()
        viewGlobal.value = global
    }

    fun updateFilter(transform: (MemoryLibraryFilter) -> MemoryLibraryFilter) {
        libraryFilter.value = transform(libraryFilter.value)
    }

    fun updateAssistant(transform: (Assistant) -> Assistant) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map { current ->
                        if (current.id == assistantId) transform(current) else current
                    },
                )
            }
        }
    }

    fun setAutoSaveMode(mode: MemoryAutoSaveMode) {
        updateAssistant { it.copy(memoryAutoSaveMode = mode) }
        if (mode == MemoryAutoSaveMode.OFF) {
            val current = assistant.value
            val activeScope = if (current.useGlobalMemory) {
                MemoryRepository.GLOBAL_MEMORY_ID
            } else {
                current.id.toString()
            }
            viewModelScope.launch { scheduler.cancel(activeScope) }
        }
    }

    /**
     * Persists the user-facing batching controls together and immediately reapplies the schedule to
     * any already queued captures in the Assistant's active memory scope.
     */
    fun setScheduleTuning(
        idleMinutes: Int,
        immediateThreshold: Int,
        conversationContextTurns: Int,
    ) {
        val normalizedMinutes = idleMinutes.coerceIn(MIN_IDLE_MINUTES, MAX_IDLE_MINUTES)
        val normalizedThreshold = immediateThreshold.coerceIn(MIN_IMMEDIATE_THRESHOLD, MAX_IMMEDIATE_THRESHOLD)
        val normalizedContextTurns = conversationContextTurns.coerceIn(
            MIN_CONVERSATION_CONTEXT_TURNS,
            MAX_CONVERSATION_CONTEXT_TURNS,
        )
        updateAssistant {
            it.copy(
                memoryIdleDelayMinutes = normalizedMinutes,
                memoryImmediateCaptureThreshold = normalizedThreshold,
                memoryConversationContextTurns = normalizedContextTurns,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val currentAssistant = assistant.value
            val scope = if (currentAssistant.useGlobalMemory) {
                MemoryRepository.GLOBAL_MEMORY_ID
            } else {
                currentAssistant.id.toString()
            }
            val pending = memoryV2Dao.countPendingCaptures(scope)
            if (pending > 0) {
                val delayMs = if (pending >= normalizedThreshold) {
                    0L
                } else {
                    normalizedMinutes * 60_000L
                }
                scheduler.schedule(MemoryWorkRequest(scope, delayMs))
            }
        }
    }

    fun setNarrativeNames(userName: String, companionName: String) {
        updateAssistant {
            it.copy(
                memoryNarrativeUserName = userName.trim().take(MAX_NARRATIVE_NAME_CHARS),
                memoryNarrativeCompanionName = companionName.trim().take(MAX_NARRATIVE_NAME_CHARS),
            )
        }
    }

    fun setOrigin(origin: MemoryCaptureOrigin, enabled: Boolean) {
        if (origin !in USER_CONFIGURABLE_ORIGINS) return
        updateAssistant { assistant ->
            assistant.copy(
                memoryCaptureOrigins = if (enabled) {
                    assistant.memoryCaptureOrigins + origin
                } else {
                    assistant.memoryCaptureOrigins - origin
                },
            )
        }
    }

    fun setExtractionModel(modelId: Uuid?) {
        viewModelScope.launch {
            settingsStore.update { it.copy(memoryExtractionModelId = modelId) }
        }
    }

    /** 做梦模型（settings.dreamModelId）：null = 跟随记忆抽取模型回落链。 */
    val dreamModelId = settingsStore.settingsFlow.map { it.dreamModelId }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null,
    )

    fun setDreamModel(modelId: Uuid?) {
        viewModelScope.launch {
            settingsStore.update { it.copy(dreamModelId = modelId) }
        }
    }

    // ---- Dream 运行状态与手动触发（2026-10-11 A 系列：主公裁定暴露既有能力） ----

    /** 「最近运行」列表的一行：时间 · 结果 · token。 */
    data class DreamRunRow(
        val timeText: String,
        val outcome: String,
        val tokensText: String?,
    )

    /** 设置页「运行状态」卡的数据：今日用量 / 上次做梦 / 当前被挡原因 / 活动 run / 最近运行。 */
    data class DreamRuntimeStatus(
        val usageSummary: String,
        val lastRunSummary: String,
        val blockers: List<String>,
        /** 当前 PENDING/RUNNING run 的进度摘要；无活动 run 时为 null。 */
        val activeRunSummary: String? = null,
        /** 最近若干条 run 历史（含失败原因），最新的在前。 */
        val recentRuns: List<DreamRunRow> = emptyList(),
    )

    val dreamNowRunning = MutableStateFlow(false)
    val dreamNowMessage = MutableStateFlow<String?>(null)
    val dreamRuntimeStatus = MutableStateFlow<DreamRuntimeStatus?>(null)

    private var dreamStatusPollingJob: Job? = null

    init {
        refreshDreamRuntimeStatus()
    }

    /** 手动「立即做梦」：跳过空闲等待（用户在界面点了就说明此刻要做），其余成本/条件门照常。 */
    fun dreamNow() {
        if (dreamNowRunning.value) return
        viewModelScope.launch(Dispatchers.IO) {
            dreamNowRunning.value = true
            try {
                val result = dreamSynthesisCoordinator.scanDirtyScopes(
                    reason = DreamSynthesisScanReason.MANUAL,
                    bypassIdle = true,
                )
                dreamNowMessage.value = when {
                    result.scheduledScopes.isNotEmpty() ->
                        "已开始做梦：本次立即执行（联网即可，无需充电/空闲），进度见下方"
                    result.deferredScopes.values.any {
                        it == DreamSynthesisScheduleDeferral.DAILY_BUDGET_EXHAUSTED
                    } -> "今日额度已用尽（UTC 00:00 重置）"
                    result.deferredScopes.values.any {
                        it == DreamSynthesisScheduleDeferral.FEATURE_DISABLED
                    } -> "「生成 Dream 概览」未开启"
                    else -> "当前没有待整理的记忆积压"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                // 2026-10-11 主公报“频繁报错”：原文案吞掉了异常细节，无法定位。
                // 现在把异常类名与消息原样透出到页面，并提供日志行。
                android.util.Log.e("MemoryCenterVM", "dreamNow scan failed", e)
                dreamNowMessage.value = "触发失败：${e.javaClass.simpleName}: ${e.message ?: "无详细信息"}"
            } finally {
                refreshDreamRuntimeStatus()
                dreamNowRunning.value = false
                // 2026-10-11 主公令：触发后持续轮询，让「排队→做梦→完成」实时可见，
                // 不再出现"只显示正在处理"的悬挂体验。
                startDreamStatusPolling()
            }
        }
    }

    fun refreshDreamRuntimeStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            dreamRuntimeStatus.value = runCatching { buildDreamRuntimeStatus() }.getOrNull()
        }
    }

    private suspend fun buildDreamRuntimeStatus(): DreamRuntimeStatus {
        val scope = currentDreamScopeId()
        val prefs = settingsStore.settingsFlow.first().dreamingPreferences.failClosed()
        val policy = prefs.costPolicy.validatedOrNull() ?: prefs.costPolicy
        val flags = prefs.forScope(scope).toFeatureFlags(schemaReady = true)

        // 今日用量（UTC 日窗口，与 DreamBudgetGate 同一口径）
        var usageSummary = "今日尚未做梦"
        runCatching {
            val window = dreamUtcDayWindowOrNull(System.currentTimeMillis())
            if (window != null) {
                val usage = dreamUsageStore.readGlobalUtcUsage(
                    DreamDailyUsageQuery(window = window, excludingRunId = null),
                )
                usageSummary = buildString {
                    append("今日已做梦 ")
                    append(usage.startedRunCount)
                    append('/')
                    append(policy.dailyRunLimit)
                    append(" 次")
                    if (policy.dailyInputTokenLimit != null || policy.dailyOutputTokenLimit != null) {
                        append(" · token ")
                        if (policy.dailyInputTokenLimit != null) {
                            append("入 ${usage.knownInputTokens}/${policy.dailyInputTokenLimit}")
                        }
                        if (policy.dailyInputTokenLimit != null && policy.dailyOutputTokenLimit != null) {
                            append(" / ")
                        }
                        if (policy.dailyOutputTokenLimit != null) {
                            append("出 ${usage.knownOutputTokens}/${policy.dailyOutputTokenLimit}")
                        }
                    }
                }
            }
        }

        // 2026-10-11 主公令：活动 run 与终局 run 分开展示——此前"上次做梦"永远被最新
        // PENDING 行（排队中永不终局）遮蔽成"进行中"，后台跑了五次的失败明细完全不可见。
        val recent = dreamDao.listRecentRuns(scope.value, 10)
        val activeRun = recent.firstOrNull { it.status == "PENDING" || it.status == "RUNNING" }
        val lastTerminalRun = recent.firstOrNull { it.status != "PENDING" && it.status != "RUNNING" }

        // 失败不占配额（2026-10-12 主公令）：单独报数，避免"次数少了却不知道去哪了"。
        runCatching {
            val window = dreamUtcDayWindowOrNull(System.currentTimeMillis()) ?: return@runCatching
            val failedToday = recent.count { run ->
                run.status == "FAILED" &&
                    run.createdAtMs >= window.startInclusiveEpochMs &&
                    run.createdAtMs < window.endExclusiveEpochMs
            }
            if (failedToday > 0) {
                usageSummary = "$usageSummary · 失败 $failedToday 次（不计入）"
            }
        }

        var lastRunSummary = "从未做过梦（积压会在条件满足时自动整理）"
        if (lastTerminalRun != null) {
            val atMs = lastTerminalRun.finishedAtMs ?: lastTerminalRun.startedAtMs
                ?: lastTerminalRun.createdAtMs
            val ago = java.time.Duration.between(
                java.time.Instant.ofEpochMilli(atMs),
                java.time.Instant.now(),
            )
            val agoText = when {
                ago.toMinutes() < 1 -> "刚刚"
                ago.toHours() < 1 -> "${ago.toMinutes()} 分钟前"
                ago.toDays() < 1 -> "${ago.toHours()} 小时前"
                else -> "${ago.toDays()} 天前"
            }
            lastRunSummary = "上次做梦：$agoText（${dreamRunOutcomeText(lastTerminalRun)}）"
        }

        val activeRunSummary: String? = activeRun?.let { run ->
            val nowMs = System.currentTimeMillis()
            if (run.status == "RUNNING") {
                val mins = ((nowMs - (run.startedAtMs ?: run.createdAtMs)) / 60000L).coerceAtLeast(0L)
                "做梦进行中（已 $mins 分钟）…"
            } else {
                val waited = java.time.Duration.between(
                    java.time.Instant.ofEpochMilli(run.createdAtMs),
                    java.time.Instant.now(),
                )
                val waitedText = when {
                    waited.toMinutes() < 1 -> "刚进入队列"
                    waited.toHours() < 1 -> "已排队 ${waited.toMinutes()} 分钟"
                    else -> "已排队 ${waited.toHours()} 小时"
                }
                "$waitedText——等待条件满足后自动开跑（点「立即做梦」可跳过等待）"
            }
        }

        val timeFormatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        val recentRuns = recent.take(5).map { run ->
            val atMs = run.startedAtMs ?: run.createdAtMs
            DreamRunRow(
                timeText = timeFormatter.format(
                    java.time.Instant.ofEpochMilli(atMs).atZone(java.time.ZoneId.systemDefault()),
                ),
                outcome = dreamRunOutcomeText(run),
                tokensText = if (run.inputTokens != null || run.outputTokens != null) {
                    "token 入 ${run.inputTokens ?: 0} / 出 ${run.outputTokens ?: 0}"
                } else {
                    null
                },
            )
        }

        // 被挡原因（generate 关是第一位；其余为成本/设备条件）
        val blockers = mutableListOf<String>()
        if (!flags.generate) {
            blockers += "「生成 Dream 概览」未开启——打开后才会整理积压"
        }
        val env = readDreamWorkerEnvironment(appContext)
        if (policy.requireCharging && !env.charging) {
            blockers += "要求充电中才做梦，当前未充电"
        }
        if (policy.requireBatteryNotLow && !env.batteryNotLow) {
            blockers += "要求电量充足，当前电量偏低"
        }
        if (policy.networkPolicy == DreamNetworkPolicy.UNMETERED && env.networkMetered) {
            blockers += "要求非计量网络（如 WiFi），当前为计量网络"
        } else if (!env.networkConnected) {
            blockers += "当前无可用网络"
        }
        if (flags.generate) {
            when (val idle = dreamIdleTracker.decisionAt(
                System.currentTimeMillis(),
                policy.idleThresholdMinutes,
            )) {
                is DreamAppIdleDecision.Deferred -> when (idle.reason) {
                    me.rerere.rikkahub.memory.dreaming.runtime.DreamAppIdleDeferralReason.APP_FOREGROUND ->
                        blockers += "应用正在前台使用，退出后需再空闲 ${policy.idleThresholdMinutes} 分钟"
                    me.rerere.rikkahub.memory.dreaming.runtime.DreamAppIdleDeferralReason.THRESHOLD_NOT_REACHED ->
                        blockers += "空闲时间未满 ${policy.idleThresholdMinutes} 分钟"
                    else -> Unit
                }
                is DreamAppIdleDecision.Eligible -> Unit
            }
        }
        if (blockers.isEmpty()) {
            blockers += "条件已满足，有积压时会自动做梦；也可点「立即做梦」"
        }

        return DreamRuntimeStatus(
            usageSummary = usageSummary,
            lastRunSummary = lastRunSummary,
            blockers = blockers,
            activeRunSummary = activeRunSummary,
            recentRuns = recentRuns,
        )
    }

    /** run 的中文结果文案：成功带产出条数，失败码翻译成人话，不再裸抛英文枚举。 */
    private fun dreamRunOutcomeText(run: me.rerere.rikkahub.data.db.entity.DreamRunEntity): String =
        when (run.status) {
            "SUCCEEDED" -> "成功·产出 ${run.outputClaimCount ?: 0} 条画像"
            "PENDING" -> "排队中"
            "RUNNING" -> "进行中"
            else -> dreamFailureText(run.failureCode)
        }

    private fun dreamFailureText(code: String?): String = when (code) {
        null -> "失败"
        "MODEL_PERMANENT_FAILURE" -> "失败·模型永久错误"
        "MODEL_UNAVAILABLE", "MODEL_PROVIDER_UNAVAILABLE" -> "失败·模型或服务商不可用"
        "MODEL_TIMEOUT" -> "失败·模型超时"
        "MODEL_CANCELLED_BY_PROVIDER" -> "失败·被服务商取消"
        "MODEL_OUTPUT_LIMIT" -> "失败·输出超限"
        "MODEL_SAFETY_REJECTION" -> "失败·模型安全拒绝"
        "MODEL_INVALID_CONFIGURATION" -> "失败·模型配置无效"
        "MODEL_AUDIT_MISMATCH" -> "失败·用量审计不符"
        "MODEL_OUTPUT_PARSE_REJECTED" -> "失败·模型输出无法解析"
        "MODEL_OUTPUT_VALIDATION_REJECTED" -> "失败·模型输出校验被拒"
        "SNAPSHOT_COMPILATION_FAILED" -> "失败·快照编译失败"
        "FEATURE_DISABLED" -> "已取消·功能被关闭"
        // 2026-10-12: the remaining enum values used to fall through to the raw code
        // ("失败·LEASE_EXPIRED"), which the user could not read. Plain language first,
        // and what happens next.
        "LEASE_EXPIRED" -> "失败·没跑完就被系统收回了，稍后会自动重试"
        "MEMORY_EPOCH_CONFLICT" -> "失败·整理期间又有新内容进来，下次会重新整理"
        "OBSERVER_CHECKPOINT_CONFLICT" -> "失败·进度对不上，下次会重新整理"
        "JOURNAL_GAP" -> "失败·记录中间缺了一段，下次会重新整理"
        "OWNER_MISMATCH" -> "失败·被另一次整理接管了"
        "CANCELLED_BY_POLICY" -> "已取消"
        "INPUT_REJECTED" -> "失败·这次要整理的内容不可用"
        "STORE_FAILURE" -> "失败·写入没成功（已保留原始记录，可在诊断里查看）"
        "INTERRUPTED" -> "已取消·中途被打断"
        // 2026-10-13: commit-stage codes are no longer collapsed into STORE_FAILURE. Each one
        // says which check failed and, for the recoverable conflicts, that a retry is coming.
        "FENCE_CONFLICT" -> "失败·整理基准已被改动，下次会重新整理"
        "DREAM_REVISION_CONFLICT" -> "失败·画像刚被更新过，下次会重新整理"
        "ACTIVE_SNAPSHOT_CONFLICT" -> "失败·生效画像刚被替换，下次会重新整理"
        "CLAIM_REVISION_CONFLICT" -> "失败·画像条目刚被改动，下次会重新整理"
        "LEASE_MISSING" -> "失败·整理任务身份对不上，下次会重新整理"
        "RUN_NOT_RUNNING" -> "失败·任务状态已改变"
        "EVIDENCE_SCOPE_MISMATCH" -> "失败·证据来源不匹配，下次会重新整理"
        "EVIDENCE_REVISION_MISMATCH" -> "失败·证据内容已被改动，下次会重新整理"
        "EVIDENCE_FINGERPRINT_MISMATCH" -> "失败·证据指纹已变化，下次会重新整理"
        "EVIDENCE_SOURCE_MANIFEST_MISMATCH" -> "失败·证据清单已变化，下次会重新整理"
        "EVIDENCE_TOMBSTONED" -> "失败·证据已被删除，下次会重新整理"
        "STORE_CORRUPTION" -> "失败·写入校验未通过（已保留原始记录，可在诊断里查看）"
        else -> "失败·$code"
    }

    /**
     * 手动触发后轮询刷新状态卡（2026-10-11 主公令：进度可视化）。
     * 每 3 秒刷一次，直到无活动 run 或 90 秒上限——自动通道被充电等条件挡住时，
     * 页面会一直显示"排队中 + 等待原因"，不再静默悬挂。
     */
    private fun startDreamStatusPolling() {
        dreamStatusPollingJob?.cancel()
        dreamStatusPollingJob = viewModelScope.launch(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + 90_000L
            while (System.currentTimeMillis() < deadline) {
                delay(3_000L)
                val status = runCatching { buildDreamRuntimeStatus() }.getOrNull() ?: break
                dreamRuntimeStatus.value = status
                if (status.activeRunSummary == null) break
            }
        }
    }

    fun processNow(retryFailed: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val scope = currentCommandScopeId()
            if (retryFailed) memoryV2Dao.retryScope(scope, System.currentTimeMillis())
            scheduler.schedule(MemoryWorkRequest(scope, 0L))
            lastActionMessage.value = "scheduled"
        }
    }

    fun review(command: MemoryReviewCommand) {
        viewModelScope.launch(Dispatchers.IO) {
            lastActionMessage.value = when (coordinator.review(command)) {
                is MemoryReviewResult.Applied -> "accepted"
                MemoryReviewResult.Rejected -> "rejected"
                MemoryReviewResult.Conflict -> "conflict"
                MemoryReviewResult.AlreadyResolved -> "already_resolved"
                MemoryReviewResult.NotFound -> "not_found"
                is MemoryReviewResult.Failed -> "failed"
            }
        }
    }

    fun reviewRelation(command: MemoryRelationReviewCommand) {
        viewModelScope.launch(Dispatchers.IO) {
            lastActionMessage.value = when (coordinator.reviewRelation(command)) {
                is MemoryRelationReviewResult.Applied -> "accepted"
                MemoryRelationReviewResult.Rejected -> "rejected"
                MemoryRelationReviewResult.Conflict -> "conflict"
                MemoryRelationReviewResult.AlreadyResolved -> "already_resolved"
                MemoryRelationReviewResult.NotFound -> "not_found"
                is MemoryRelationReviewResult.Failed -> "failed"
            }
        }
    }

    fun acceptRelationCandidate(candidate: MemoryRelationCandidateEntity) {
        reviewRelation(candidate.reviewCommand(accept = true))
    }

    fun rejectRelationCandidate(candidate: MemoryRelationCandidateEntity) {
        reviewRelation(candidate.reviewCommand(accept = false))
    }

    fun acceptCandidate(
        candidate: MemoryCandidateEntity,
        editedTitle: String? = null,
        editedContent: String? = null,
    ) {
        val proposal = candidate.toProposal().let { original ->
            if (editedTitle == null && editedContent == null) original
            else original.copy(
                title = editedTitle ?: original.title,
                content = editedContent ?: original.content,
            )
        }
        review(
            MemoryReviewCommand.Accept(
                candidateId = candidate.id,
                expectedScopeId = candidate.scopeId,
                editedProposal = proposal,
            ),
        )
    }

    fun rejectCandidate(candidate: MemoryCandidateEntity) {
        review(
            MemoryReviewCommand.Reject(
                candidateId = candidate.id,
                expectedScopeId = candidate.scopeId,
            ),
        )
    }

    /**
     * Applies only candidates that still meet the same clean CREATE policy used by automatic
     * write-back. The database review path rechecks revisions and duplicate conflicts per row.
     */
    fun acceptSafeNewCandidates() {
        reviewPendingCandidates { candidate ->
            candidate.takeIf { it.status == "PENDING_REVIEW" }
                ?.takeIf { candidate.hasNoReviewFlags() }
                ?.takeIf { candidatePolicy.isSafeNewCreate(candidate.toProposal()) }
                ?.let {
                    MemoryReviewCommand.Accept(
                        candidateId = it.id,
                        expectedScopeId = it.scopeId,
                    )
                }
        }
    }

    /** Rejects pending candidates only; conflicts remain visible for an explicit user decision. */
    fun rejectAllPendingCandidates() {
        reviewPendingCandidates { candidate ->
            candidate.takeIf { it.status == "PENDING_REVIEW" }
                ?.let {
                    MemoryReviewCommand.Reject(
                        candidateId = it.id,
                        expectedScopeId = it.scopeId,
                    )
                }
        }
    }

    private fun reviewPendingCandidates(
        commandFor: (MemoryCandidateEntity) -> MemoryReviewCommand?,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            var changed = 0
            var conflict = false
            val pending = memoryV2Dao.observePendingCandidates(currentCommandScopeId()).first()
            pending.forEach { candidate ->
                val command = commandFor(candidate) ?: return@forEach
                when (coordinator.review(command)) {
                    is MemoryReviewResult.Applied,
                    MemoryReviewResult.Rejected,
                    -> changed++

                    MemoryReviewResult.Conflict -> conflict = true
                    MemoryReviewResult.AlreadyResolved,
                    MemoryReviewResult.NotFound,
                    is MemoryReviewResult.Failed,
                    -> Unit
                }
            }
            lastActionMessage.value = when {
                changed > 0 -> "accepted"
                conflict -> "conflict"
                else -> "already_resolved"
            }
        }
    }

    suspend fun loadMemories(
        candidate: MemoryCandidateEntity,
        ids: List<Int>,
    ): List<MemoryEntity> =
        if (ids.isEmpty()) emptyList() else memoryDao.getMemoriesByIds(ids, candidate.scopeId)

    suspend fun resolveSource(candidate: MemoryCandidateEntity): MemorySourceLocation? {
        val conversationId = runCatching { Uuid.parse(candidate.sourceConversationId) }.getOrNull()
            ?: return null
        val conversation = conversationRepository.getConversationById(conversationId)
            ?: return null
        val evidence = runCatching {
            JsonInstant.decodeFromString<List<String>>(candidate.evidenceMessageIdsJson)
        }.getOrDefault(emptyList())
        val nodeId = evidence.asSequence()
            .mapNotNull { raw -> runCatching { Uuid.parse(raw) }.getOrNull() }
            .mapNotNull { messageId -> conversation.getMessageNodeByMessageId(messageId)?.id }
            .firstOrNull()
        return MemorySourceLocation(conversationId, nodeId)
    }

    fun archive(memory: MemoryEntity) {
        mutate(
            MemoryMutationCommand.Archive(
                memoryId = memory.id,
                expectedScopeId = memory.assistantId,
                expectedRevision = memory.revision,
                approvalSource = MemoryApprovalSource.MANUAL_UI,
            ),
        )
    }

    /** 2026-10-11 主公裁定：记忆库补直接删除（硬删，含修订历史与关联）。 */
    fun delete(memory: MemoryEntity) {
        mutate(
            MemoryMutationCommand.Delete(
                memoryId = memory.id,
                expectedScopeId = memory.assistantId,
                expectedRevision = memory.revision,
                approvalSource = MemoryApprovalSource.MANUAL_UI,
            ),
        )
    }

    fun restore(memory: MemoryEntity) {
        mutate(
            MemoryMutationCommand.Restore(
                memoryId = memory.id,
                expectedScopeId = memory.assistantId,
                expectedRevision = memory.revision,
                approvalSource = MemoryApprovalSource.MANUAL_UI,
            ),
        )
    }

    fun updateMemory(memory: MemoryEntity, input: MemoryWriteInput) {
        mutate(
            MemoryMutationCommand.Update(
                memoryId = memory.id,
                expectedScopeId = memory.assistantId,
                expectedRevision = memory.revision,
                title = input.title,
                content = input.content,
                kind = input.kind,
                tags = input.tags,
                importance = input.importance,
                expiryUpdate = input.expiresAtMs?.let(
                    me.rerere.rikkahub.memory.MemoryExpiryUpdate::Set,
                ) ?: input.expiryUpdate,
                approvalSource = MemoryApprovalSource.MANUAL_UI,
            ),
        )
    }

    fun restoreRevision(memory: MemoryEntity, revision: Int) {
        mutate(
            MemoryMutationCommand.RestoreRevision(
                memoryId = memory.id,
                expectedScopeId = memory.assistantId,
                expectedCurrentRevision = memory.revision,
                revision = revision,
                approvalSource = MemoryApprovalSource.MANUAL_UI,
            ),
        )
    }

    fun revisions(memory: MemoryEntity) =
        memoryV2Dao.observeRevisions(memory.id, memory.assistantId)

    fun createMemory(input: MemoryWriteInput) {
        viewModelScope.launch(Dispatchers.IO) {
            val command = MemoryMutationCommand.Create(
                scopeId = currentCommandScopeId(),
                title = input.title,
                content = input.content,
                kind = input.kind ?: me.rerere.rikkahub.memory.MemoryKind.OTHER,
                tags = input.tags.orEmpty(),
                importance = input.importance ?: 0.5f,
                confidence = input.confidence ?: 1f,
                expiresAtMs = input.expiresAtMs,
                approvalSource = MemoryApprovalSource.MANUAL_UI,
                sourceType = "MANUAL_UI",
                originAssistantId = assistantId.toString(),
            )
            lastActionMessage.value = mutationCoordinator.mutate(command)
                .toMemoryMutationUiFeedback()
                .actionMessageCode
        }
    }

    fun mutate(command: MemoryMutationCommand) {
        viewModelScope.launch(Dispatchers.IO) {
            lastActionMessage.value = mutationCoordinator.mutate(command)
                .toMemoryMutationUiFeedback()
                .actionMessageCode
        }
    }

    fun runRecallTest(query: String) {
        val normalized = query.trim()
        if (normalized.isEmpty()) return
        recallTestState.value = MemoryRecallTestState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                memoryRepository.queryDetailed(
                    assistantId = assistantId,
                    query = normalized,
                    includeGlobal = viewGlobal.value,
                    limit = 8,
                )
            }.onSuccess { results ->
                recallTestState.value = MemoryRecallTestState.Ready(
                    query = normalized,
                    results = results,
                    usedCharacters = results.sumOf { it.content.length },
                    characterBudget = DEFAULT_MEMORY_PROMPT_MAX_CHARS,
                )
            }.onFailure { error ->
                recallTestState.value = MemoryRecallTestState.Failed(error.message.orEmpty())
            }
        }
    }

    fun openDreamClaim(target: DreamClaimMutationTarget) {
        if (target.fence.scopeId != currentDreamScopeId()) {
            dreamDetailState.value = MemoryDreamDetailState.Failed(target, "scope_changed")
            return
        }
        dreamDetailState.value = MemoryDreamDetailState.Loading(target)
        viewModelScope.launch(Dispatchers.IO) {
            val next = try {
                when (val result = dreamReviewRepository.readClaim(target)) {
                    is DreamReviewReadResult.Found -> MemoryDreamDetailState.Ready(result.value)
                    is DreamReviewReadResult.Conflict -> {
                        MemoryDreamDetailState.Failed(target, "conflict_${result.conflict.name.lowercase()}")
                    }
                    DreamReviewReadResult.NotFound -> MemoryDreamDetailState.Failed(target, "not_found")
                    DreamReviewReadResult.InvalidState -> MemoryDreamDetailState.Failed(target, "invalid_state")
                    DreamReviewReadResult.Corrupt -> MemoryDreamDetailState.Failed(target, "corrupt")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                MemoryDreamDetailState.Failed(target, "failed")
            }
            val current = dreamDetailState.value
            if (current is MemoryDreamDetailState.Loading &&
                current.target == target &&
                target.fence.scopeId == currentDreamScopeId()
            ) {
                dreamDetailState.value = next
            }
        }
    }

    fun closeDreamClaim() {
        dreamDetailState.value = MemoryDreamDetailState.Closed
    }

    /** Sensitive excerpts are deliberately ephemeral and must not survive backgrounding. */
    fun onDreamUiHidden() = closeDreamClaim()

    fun revealDreamEvidence(summary: DreamEvidenceSummary) {
        val ready = dreamDetailState.value as? MemoryDreamDetailState.Ready ?: return
        if (summary !in ready.detail.evidence ||
            summary.reference.scopeId != currentDreamScopeId() ||
            summary.reference in ready.revealingEvidence
        ) {
            return
        }
        dreamDetailState.value = ready.copy(
            revealingEvidence = ready.revealingEvidence + summary.reference,
        )
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                dreamReviewRepository.revealEvidence(summary.reference)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DreamEvidenceRevealResult.Corrupt
            }
            val latest = dreamDetailState.value as? MemoryDreamDetailState.Ready ?: return@launch
            if (latest.detail.target != ready.detail.target) return@launch
            dreamDetailState.value = when (result) {
                is DreamEvidenceRevealResult.Revealed -> latest.copy(
                    revealedEvidence = latest.revealedEvidence + (summary.reference to result.excerpt),
                    revealingEvidence = latest.revealingEvidence - summary.reference,
                )
                is DreamEvidenceRevealResult.Invalid,
                DreamEvidenceRevealResult.NotFound,
                DreamEvidenceRevealResult.Corrupt,
                -> latest.copy(revealingEvidence = latest.revealingEvidence - summary.reference)
            }
        }
    }

    fun rejectDreamClaim(target: DreamClaimMutationTarget) {
        if (target.fence.scopeId != currentDreamScopeId()) {
            lastActionMessage.value = "conflict"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                dreamReviewRepository.reject(target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DreamReviewMutationResult.Corrupt
            }
            lastActionMessage.value = result.toDreamActionMessage()
            if (result is DreamReviewMutationResult.Applied) closeDreamClaim()
        }
    }

    fun correctDreamClaim(draft: DreamCorrectionDraft) {
        if (draft.target.fence.scopeId != currentDreamScopeId()) {
            lastActionMessage.value = "conflict"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                dreamReviewRepository.correct(draft)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DreamCorrectionResult.Corrupt
            }
            lastActionMessage.value = result.toDreamActionMessage()
            if (result is DreamCorrectionResult.Applied ||
                result is DreamCorrectionResult.PairApplied ||
                result is DreamCorrectionResult.AuthorityAppliedRebuildPending
            ) {
                closeDreamClaim()
            }
        }
    }

    fun clearDreamDerived(fence: DreamReviewFence) {
        if (fence.scopeId != currentDreamScopeId()) {
            lastActionMessage.value = "conflict"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                dreamReviewRepository.clearDerived(fence)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DreamReviewMutationResult.Corrupt
            }
            lastActionMessage.value = result.toDreamActionMessage()
            if (result is DreamReviewMutationResult.Applied || result is DreamReviewMutationResult.AlreadyClear) {
                closeDreamClaim()
            }
        }
    }

    fun updateDreamingScopePreference(mutation: DreamingScopePreferenceMutation) {
        val renderedScope = currentDreamScopeId()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val change = settingsStore.updateDreamingScopePreferences(renderedScope, mutation)
                dreamSynthesisCoordinator.onSettingsChanged(
                    renderedScope,
                    change.previous,
                    change.current,
                )
                refreshDreamRuntimeStatus()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                lastActionMessage.value = "failed"
            }
        }
    }

    fun updateDreamingCostPolicy(policy: DreamingCostPolicy) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val change = settingsStore.updateDreamingCostPolicy(policy)
                dreamSynthesisCoordinator.onCostPolicyChanged(change.previous, change.current)
                refreshDreamRuntimeStatus()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                lastActionMessage.value = "failed"
            }
        }
    }

    private fun currentDreamScopeId(): DreamScopeId = pairDreamScopeId

    companion object {
        const val MIN_IDLE_MINUTES = 1
        const val MAX_IDLE_MINUTES = 1_440
        const val MIN_IMMEDIATE_THRESHOLD = 1
        const val MAX_IMMEDIATE_THRESHOLD = 50
        const val MIN_CONVERSATION_CONTEXT_TURNS = 3
        const val MAX_CONVERSATION_CONTEXT_TURNS = 30
        const val MAX_NARRATIVE_NAME_CHARS = 80

        val USER_CONFIGURABLE_ORIGINS = setOf(
            MemoryCaptureOrigin.APP_UI,
            MemoryCaptureOrigin.SYSTEM_ASSISTANT,
            MemoryCaptureOrigin.QUICK_CAPTURE,
            MemoryCaptureOrigin.TELEGRAM,
            MemoryCaptureOrigin.WEB_API,
        )
    }
}

private fun DreamReviewMutationResult.toDreamActionMessage(): String = when (this) {
    is DreamReviewMutationResult.Applied -> "accepted"
    is DreamReviewMutationResult.Conflict -> "conflict"
    DreamReviewMutationResult.AlreadyClear -> "already_resolved"
    DreamReviewMutationResult.NotFound -> "not_found"
    DreamReviewMutationResult.InvalidState,
    DreamReviewMutationResult.Corrupt,
    -> "failed"
}

private fun DreamCorrectionResult.toDreamActionMessage(): String = when (this) {
    is DreamCorrectionResult.Applied -> "accepted"
    is DreamCorrectionResult.PairApplied -> "accepted"
    is DreamCorrectionResult.AuthorityAppliedRebuildPending -> "dream_authority_applied_rebuild_pending"
    is DreamCorrectionResult.Conflict -> "conflict"
    DreamCorrectionResult.NotFound -> "not_found"
    DreamCorrectionResult.InvalidState,
    DreamCorrectionResult.Corrupt,
    is DreamCorrectionResult.AuthorityRejected,
    -> "failed"
}

internal fun memoryCenterStats(
    active: Int,
    archived: Int,
    pendingReview: Int,
    captures: MemoryCaptureStatusCounts,
    lastProcessedAtMs: Long?,
    pendingRelationReview: Int = 0,
) = MemoryCenterStats(
    active = active,
    archived = archived,
    pendingReview = pendingReview + pendingRelationReview,
    pendingRelationReview = pendingRelationReview,
    pendingCaptures = captures.pendingCaptures,
    processingCaptures = captures.processingCaptures,
    processedCaptures = captures.processedCaptures,
    noLongTermSignalCaptures = captures.noLongTermSignalCaptures,
    failedCaptures = captures.failedCaptures,
    pausedCaptures = captures.pausedCaptures,
    discardedCaptures = captures.discardedCaptures,
    lastProcessedAtMs = lastProcessedAtMs,
)

private fun MemoryCandidateEntity.toProposal() = MemoryProposal(
    action = runCatching { MemoryCandidateAction.valueOf(action) }
        .getOrDefault(MemoryCandidateAction.IGNORE),
    targetIds = runCatching { JsonInstant.decodeFromString<List<Int>>(targetMemoryIdsJson) }
        .getOrDefault(emptyList()),
    expectedRevisions = runCatching {
        JsonInstant.decodeFromString<List<Int>>(expectedRevisionsJson)
    }.getOrDefault(emptyList()),
    title = title,
    content = content,
    kind = runCatching { MemoryKind.valueOf(memoryKind) }.getOrDefault(MemoryKind.OTHER),
    tags = runCatching { JsonInstant.decodeFromString<List<String>>(tagsJson) }
        .getOrDefault(emptyList()),
    importance = importance,
    confidence = confidence,
    expiresAtMs = expiresAtMs,
    evidenceMessageIds = runCatching {
        JsonInstant.decodeFromString<List<String>>(evidenceMessageIdsJson)
    }.getOrDefault(emptyList()),
    reason = reason,
)

/** Unknown or malformed persisted flags fail closed so bulk acceptance cannot bypass review. */
private fun MemoryCandidateEntity.hasNoReviewFlags(): Boolean = runCatching {
    JsonInstant.decodeFromString<List<String>>(riskFlagsJson).isEmpty()
}.getOrDefault(false)
