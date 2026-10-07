package me.rerere.rikkahub.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.ai.interaction.MomentsAutoReplyEngine
import me.rerere.rikkahub.data.ai.interaction.QuestionBoxAutoReplyEngine
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findAssistantById
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.AnonymousQuestionRepository
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MomentRepository
import kotlin.uuid.Uuid

/**
 * Background auto-reply check for Moments / anonymous question box interactions.
 *
 * Runs the exact same due-processing engines the overlays use (extracted into
 * [MomentsAutoReplyEngine] / [QuestionBoxAutoReplyEngine]), then chains itself to the next
 * pending due instant if any interaction still waits inside its randomized human-feel
 * window. Gated per assistant on the Moments / QuestionBox local-tool switches — if the
 * user turned the feature off for an assistant, no background generation happens.
 */
class InteractionReplyWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val settingsStore: SettingsStore by inject()
    private val conversationRepo: ConversationRepository by inject()
    private val momentRepository: MomentRepository by inject()
    private val questionRepository: AnonymousQuestionRepository by inject()
    private val momentsEngine: MomentsAutoReplyEngine by inject()
    private val questionBoxEngine: QuestionBoxAutoReplyEngine by inject()
    private val scheduler: InteractionReplyScheduler by inject()

    override suspend fun doWork(): Result {
        val assistantId = inputData.getString(KEY_ASSISTANT_ID)
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: return Result.success()
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.findAssistantById(assistantId) ?: return Result.success()

        val momentsEnabled = LocalToolOption.Moments in assistant.localTools
        val questionBoxEnabled = LocalToolOption.QuestionBox in assistant.localTools
        if (!momentsEnabled && !questionBoxEnabled) return Result.success()

        val conversation = resolveConversation(assistantId)
        val conversationSystemPrompt = conversation.customSystemPrompt?.takeIf { it.isNotBlank() }

        // Each engine is independent: a failure in one must not block the other, and a
        // failed check simply waits for the next user interaction to re-arm the chain.
        if (momentsEnabled) {
            runCatching {
                momentsEngine.processDue(
                    assistantId = assistantId,
                    assistant = assistant,
                    conversation = conversation,
                    conversationSystemPrompt = conversationSystemPrompt,
                    manual = false,
                )
            }
        }
        if (questionBoxEnabled) {
            runCatching {
                questionBoxEngine.processDue(
                    scopeId = assistantId,
                    assistant = assistant,
                    conversation = conversation,
                    conversationSystemPrompt = conversationSystemPrompt,
                )
            }
        }

        val nextDueAt = listOfNotNull(
            if (momentsEnabled) momentRepository.nextPendingDueAt(assistantId) else null,
            if (questionBoxEnabled) questionRepository.nextPendingDueAt(assistantId) else null,
        ).minOrNull()
        if (nextDueAt != null) {
            val delay = (nextDueAt - System.currentTimeMillis()).coerceAtLeast(MIN_CHAIN_DELAY_MS) + CHAIN_BUFFER_MS
            scheduler.scheduleChain(assistantId, delayMs = delay)
        }
        return Result.success()
    }

    /**
     * Generation context comes from the assistant's most recent conversation (memories,
     * system prompt, compression summary all live there). Fresh assistants without any
     * chat get a dedicated context conversation titled so the user can recognize it.
     */
    private suspend fun resolveConversation(assistantId: Uuid): Conversation {
        return conversationRepo.getRecentConversations(assistantId).firstOrNull()
            ?: Conversation.ofId(
                id = Uuid.random(),
                assistantId = assistantId,
                newConversation = true,
            ).copy(title = "[互动]").also { conversationRepo.insertConversation(it) }
    }

    companion object {
        const val KEY_ASSISTANT_ID = "assistant_id"
        private const val MIN_CHAIN_DELAY_MS = 30_000L
        private const val CHAIN_BUFFER_MS = 15_000L
    }
}
