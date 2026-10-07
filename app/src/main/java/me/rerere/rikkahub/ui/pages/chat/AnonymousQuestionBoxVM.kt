package me.rerere.rikkahub.ui.pages.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.interaction.QuestionBoxAutoReplyEngine
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.AnonymousQuestion
import me.rerere.rikkahub.data.repository.AnonymousQuestionAuthor
import me.rerere.rikkahub.data.repository.AnonymousQuestionEntry
import me.rerere.rikkahub.data.repository.AnonymousQuestionProfile
import me.rerere.rikkahub.data.repository.AnonymousQuestionRepository
import me.rerere.rikkahub.service.InteractionReplyScheduler
import kotlin.uuid.Uuid

/**
 * Thin UI shell around [QuestionBoxAutoReplyEngine]. The due-processing core moved to the
 * engine so the background InteractionReplyWorker can run the identical pipeline when the
 * user has not opened the overlay; this VM keeps the observable UI state and delegates.
 */
class AnonymousQuestionBoxVM(
    private val engine: QuestionBoxAutoReplyEngine,
    private val repository: AnonymousQuestionRepository,
    private val interactionReplyScheduler: InteractionReplyScheduler,
) : ViewModel() {
    private val _processing = MutableStateFlow(false)
    val processing: StateFlow<Boolean> = _processing.asStateFlow()

    fun observeQuestions(scopeId: Uuid): Flow<List<AnonymousQuestionEntry>> = repository.observeQuestions(scopeId)
    fun observeProfile(scopeId: Uuid): Flow<AnonymousQuestionProfile> = repository.observeProfile(scopeId)
    fun observeHasUnread(scopeId: Uuid): Flow<Boolean> = repository.observeHasUnread(scopeId)

    fun markViewed(scopeId: Uuid) {
        viewModelScope.launch { repository.markViewed(scopeId) }
    }

    fun postUserQuestion(scopeId: Uuid, content: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            repository.postUserQuestion(scopeId, content)
            // The assistant answers due user questions; schedule a check without requiring
            // the overlay to be opened. The due window is honored by the worker chain.
            interactionReplyScheduler.schedule(scopeId)
        }
    }

    fun addUserAnswer(question: AnonymousQuestion, content: String) {
        if (content.isBlank() || question.author != AnonymousQuestionAuthor.ASSISTANT) return
        viewModelScope.launch {
            repository.addUserAnswer(question.id, content)
            interactionReplyScheduler.schedule(question.scopeId)
        }
    }

    fun processDue(
        scopeId: Uuid,
        assistant: Assistant,
        conversation: Conversation,
        conversationSystemPrompt: String?,
    ) {
        viewModelScope.launch {
            if (_processing.value) return@launch
            _processing.value = true
            try {
                engine.processDue(
                    scopeId = scopeId,
                    assistant = assistant,
                    conversation = conversation,
                    conversationSystemPrompt = conversationSystemPrompt,
                )
            } finally {
                _processing.value = false
            }
        }
    }
}
