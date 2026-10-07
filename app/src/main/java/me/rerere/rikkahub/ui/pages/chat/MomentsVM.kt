package me.rerere.rikkahub.ui.pages.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.interaction.MomentsAutoReplyEngine
import me.rerere.rikkahub.data.ai.interaction.MomentsRefreshReport
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.MomentEntry
import me.rerere.rikkahub.data.repository.MomentProfile
import me.rerere.rikkahub.data.repository.MomentRepository
import me.rerere.rikkahub.service.InteractionReplyScheduler
import kotlin.uuid.Uuid

/**
 * Thin UI shell around [MomentsAutoReplyEngine]. The due-processing core moved to the engine
 * so the background InteractionReplyWorker can run the identical pipeline when the user has
 * not opened the overlay; this VM keeps the observable UI state and delegates.
 */
class MomentsVM(
    private val engine: MomentsAutoReplyEngine,
    private val momentRepository: MomentRepository,
    private val interactionReplyScheduler: InteractionReplyScheduler,
    val filesManager: FilesManager,
) : ViewModel() {
    private val _processing = MutableStateFlow(false)
    val processing: StateFlow<Boolean> = _processing.asStateFlow()
    private val _visionModelSetupRequired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val visionModelSetupRequired: SharedFlow<Unit> = _visionModelSetupRequired.asSharedFlow()
    private val _refreshReports = MutableSharedFlow<MomentsRefreshReport>(extraBufferCapacity = 1)
    val refreshReports: SharedFlow<MomentsRefreshReport> = _refreshReports.asSharedFlow()

    init {
        viewModelScope.launch {
            engine.visionModelSetupRequired.collect {
                _visionModelSetupRequired.emit(Unit)
            }
        }
    }

    fun observeTimeline(assistantId: Uuid): Flow<List<MomentEntry>> = momentRepository.observeTimeline(assistantId)

    fun observeProfile(assistantId: Uuid): Flow<MomentProfile> = momentRepository.observeProfile(assistantId)

    fun observeHasUnread(assistantId: Uuid): Flow<Boolean> = momentRepository.observeHasUnread(assistantId)

    fun markViewed(assistantId: Uuid) {
        viewModelScope.launch {
            momentRepository.markViewed(assistantId)
        }
    }

    fun updateCover(assistantId: Uuid, coverUri: String) {
        viewModelScope.launch {
            momentRepository.updateCover(assistantId, coverUri)
        }
    }

    fun postUserMoment(assistantId: Uuid, content: String, imageUris: List<String>) {
        if (content.isBlank() && imageUris.isEmpty()) return
        viewModelScope.launch {
            momentRepository.postUserMoment(assistantId, content, imageUris)
            // The assistant may react to the user's new moment; the due window is honored
            // inside the worker chain, this only guarantees a check happens without UI.
            interactionReplyScheduler.schedule(assistantId)
        }
    }

    fun toggleUserLike(momentId: Uuid) {
        viewModelScope.launch {
            momentRepository.toggleUserLike(momentId)
        }
    }

    fun addComment(momentId: Uuid, content: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            momentRepository.addUserComment(momentId, content)
            // Scope the check to the moment's owning assistant (the comment write itself
            // carries no assistant id; one indexed read is cheaper than widening the UI API).
            val moment = momentRepository.getMoment(momentId)
            if (moment != null) {
                interactionReplyScheduler.schedule(moment.assistantId)
            }
        }
    }

    fun processDue(
        assistantId: Uuid,
        assistant: me.rerere.rikkahub.data.model.Assistant,
        conversation: me.rerere.rikkahub.data.model.Conversation,
        conversationSystemPrompt: String?,
        manual: Boolean = false,
    ) {
        viewModelScope.launch {
            if (_processing.value) {
                if (manual) {
                    _refreshReports.emit(MomentsRefreshReport(processingSkipped = true))
                }
                return@launch
            }
            _processing.value = true
            try {
                val report = engine.processDue(
                    assistantId = assistantId,
                    assistant = assistant,
                    conversation = conversation,
                    conversationSystemPrompt = conversationSystemPrompt,
                    manual = manual,
                )
                if (manual) {
                    _refreshReports.emit(report)
                }
            } finally {
                _processing.value = false
            }
        }
    }
}
