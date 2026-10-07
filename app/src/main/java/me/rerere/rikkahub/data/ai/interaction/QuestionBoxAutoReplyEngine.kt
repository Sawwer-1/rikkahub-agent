package me.rerere.rikkahub.data.ai.interaction

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.AnonymousQuestion
import me.rerere.rikkahub.data.repository.AnonymousQuestionReply
import me.rerere.rikkahub.data.repository.AnonymousQuestionReplyStatus
import me.rerere.rikkahub.data.repository.AnonymousQuestionRepository
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

internal fun Assistant.forAnonymousQuestionGeneration(): Assistant {
    return copy(streamOutput = false, reasoningLevel = ReasoningLevel.OFF)
}

internal fun List<UIMessagePart>.anonymousQuestionVisibleText(): String {
    return filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .trim()
}

/**
 * Anonymous question box auto-reply engine. Extracted verbatim from AnonymousQuestionBoxVM
 * so the same due-processing pipeline can run from the overlay UI and from the background
 * InteractionReplyWorker. Pure move: prompts, guards and status transitions are unchanged.
 */
class QuestionBoxAutoReplyEngine(
    private val settingsStore: SettingsStore,
    private val repository: AnonymousQuestionRepository,
    private val conversationRepository: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
) {
    private val processingIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val processingMutex = Mutex()

    suspend fun processDue(
        scopeId: Uuid,
        assistant: Assistant,
        conversation: Conversation,
        conversationSystemPrompt: String?,
    ) {
        processingMutex.withLock {
            val generationConversation = conversationRepository.getConversationById(conversation.id) ?: conversation
            val now = System.currentTimeMillis()
            repository.getDueQuestions(scopeId, now, AnonymousQuestionRepository.MAX_PROCESSING_ITEMS)
                .forEach { processQuestion(it, assistant, generationConversation, conversationSystemPrompt) }
            repository.getDueAnswers(scopeId, now, AnonymousQuestionRepository.MAX_PROCESSING_ITEMS)
                .forEach { processAnswer(it, assistant, generationConversation, conversationSystemPrompt) }
        }
    }

    private suspend fun processQuestion(
        question: AnonymousQuestion,
        assistant: Assistant,
        conversation: Conversation,
        conversationSystemPrompt: String?,
    ) {
        val key = "question:${question.id}"
        if (!processingIds.add(key)) return
        try {
            val answer = generateAnonymousAnswer(question.content, assistant, conversation, conversationSystemPrompt) ?: return
            repository.addAssistantAnswer(question.id, answer)
            repository.updateQuestion(question.copy(replyStatus = AnonymousQuestionReplyStatus.DONE))
        } finally {
            processingIds.remove(key)
        }
    }

    private suspend fun processAnswer(
        reply: AnonymousQuestionReply,
        assistant: Assistant,
        conversation: Conversation,
        conversationSystemPrompt: String?,
    ) {
        val key = "answer:${reply.id}"
        if (!processingIds.add(key)) return
        try {
            val question = repository.getQuestion(reply.questionId) ?: return
            val comment = generateAnonymousComment(question.content, reply.content, assistant, conversation, conversationSystemPrompt) ?: return
            repository.addAssistantComment(question.id, comment)
            repository.updateReply(reply.copy(replyStatus = AnonymousQuestionReplyStatus.DONE))
        } finally {
            processingIds.remove(key)
        }
    }

    private suspend fun generateAnonymousAnswer(
        question: String,
        assistant: Assistant,
        conversation: Conversation,
        conversationSystemPrompt: String?,
    ): String? {
        return generateText(
            assistant = assistant,
            conversationId = conversation.id,
            conversationSystemPrompt = conversationSystemPrompt,
            conversationContextSummary = conversation.compressedSummary,
            messages = listOf(UIMessage.user(
            prompt = """
                Answer an anonymous question in the assistant's anonymous question box.
                You do not know who asked it. Do not infer, identify, name, or expose the asker.
                Return only a natural, concise answer with no markdown and no identity claims.
                Keep the complete answer within 200 characters and finish it naturally before reaching the limit.

                Anonymous question:
                $question
            """.trimIndent(),
            )),
        )
    }

    private suspend fun generateAnonymousComment(
        question: String,
        answer: String,
        assistant: Assistant,
        conversation: Conversation,
        conversationSystemPrompt: String?,
    ): String? {
        return generateText(
            assistant = assistant,
            conversationId = conversation.id,
            conversationSystemPrompt = conversationSystemPrompt,
            conversationContextSummary = conversation.compressedSummary,
            messages = listOf(UIMessage.user(
            prompt = """
                Write a short natural comment on an anonymous question-box answer.
                The question and answer are anonymous. Do not infer who wrote either one.
                Do not reveal that the assistant authored the question or mention any identity.
                Return only the comment text, with no markdown.
                Keep the complete comment within 200 characters and finish it naturally before reaching the limit.

                Anonymous question:
                $question

                Anonymous answer:
                $answer
            """.trimIndent(),
            )),
        )
    }

    private suspend fun generateText(
        assistant: Assistant,
        conversationId: Uuid,
        conversationSystemPrompt: String?,
        conversationContextSummary: String?,
        messages: List<UIMessage>,
    ): String? {
        val settings = settingsStore.settingsFlow.value
        val model = settings.findModelById(assistant.chatModelId, settings.chatModelId) ?: return null
        val memories = memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
        var output = ""
        generationHandler.generateText(
            settings = settings,
            model = model,
            messages = messages,
            conversationId = conversationId,
            // The 200-character rule is expressed in the prompt; AAA's generateText has no
            // per-call maxTokens override, so no provider token budget is forced here.
            assistant = assistant.forAnonymousQuestionGeneration(),
            conversationSystemPrompt = conversationSystemPrompt,
            conversationContextSummary = conversationContextSummary,
            memories = memories,
            tools = emptyList(),
            maxSteps = 1,
        ).collect { chunk ->
            if (chunk is GenerationChunk.Messages) {
                output = chunk.messages.lastOrNull()?.parts?.anonymousQuestionVisibleText().orEmpty()
            }
        }
        return output.takeIf { it.isNotBlank() }
    }
}
