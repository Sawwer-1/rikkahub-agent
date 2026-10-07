package me.rerere.rikkahub.data.ai.group

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.interaction.anonymousQuestionVisibleText
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.GroupChatConfig
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.utils.JsonInstant
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.uuid.Uuid

/**
 * Group chat pipeline (handover §三.B): a planner round picks which member assistants speak
 * this turn (JSON speech plan, MomentsVM-style extractJsonObject parsing), then each planned
 * member answers as themselves over a request-side projected history (see
 * [projectMessagesForMember]). Runs inside the runtime's run job, so StopCommand cancels it
 * through plain coroutine cancellation.
 */
class GroupChatEngine(
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
) {
    data class MemberSpeechPlan(
        val memberAssistantId: Uuid,
        val hint: String?,
    )

    /**
     * Planner round: given the group roster and the recent transcript, return an ordered
     * list of members who should reply to the latest user message. Never throws: any
     * planner failure returns an empty plan, and the caller falls back to the first member.
     */
    suspend fun planSpeakers(
        config: GroupChatConfig,
        conversation: Conversation,
        members: List<Assistant>,
        hostAssistant: Assistant,
        maxSpeakers: Int,
    ): List<MemberSpeechPlan> {
        val settings = settingsStore.settingsFlow.value
        val plannerModelId = config.plannerModelId ?: hostAssistant.chatModelId
        val model = settings.findModelById(plannerModelId, settings.chatModelId) ?: return emptyList()
        val memberNames = members.associate { it.id to it.name }
        val transcript = conversation.currentMessages
            .takeLast(TRANSCRIPT_WINDOW)
            .joinToString("\n") { message ->
                val speaker = when (message.role) {
                    MessageRole.USER -> "用户"
                    MessageRole.ASSISTANT ->
                        message.groupMemberOrNull()?.displayName
                            ?: hostAssistant.name.ifBlank { "主持" }
                    else -> null
                } ?: return@joinToString ""
                "$speaker: ${message.parts.asReadableText().take(200)}"
            }
            .trim()
        val latestUserText = conversation.currentMessages
            .lastOrNull { it.role == MessageRole.USER }
            ?.parts?.asReadableText().orEmpty()
        val roster = members.joinToString("\n") { member ->
            "- ${member.name} (assistant_id=${member.id}): ${member.systemPrompt.take(120).ifBlank { "(无人设)" }}"
        }
        val prompt = """
            你是群聊编排器。以下是群成员名单和最近的群聊记录，用户刚刚发来了新消息。
            决定哪些成员应该回复这条消息（按回复顺序）。通常 1 到 $maxSpeakers 人；只有当消息明确
            指向某人时才多选。不要选择与消息无关的成员。
            只输出 JSON：{"speaks":[{"assistant_id":"成员id","hint":"给该成员的一句话提示（可空）"}]}。

            成员名单：
            $roster

            最近群聊记录：
            ${transcript.ifBlank { "(暂无)" }}

            用户最新消息：
            ${latestUserText.ifBlank { "(非文本)" }}
        """.trimIndent()
        val text = generateText(
            settings = settings,
            assistant = hostAssistant.copy(streamOutput = false),
            model = model,
            conversationId = conversation.id,
            messages = listOf(UIMessage.user(prompt)),
            conversationSystemPrompt = null,
            conversationContextSummary = conversation.compressedSummary,
        )
        if (text.isBlank()) return emptyList()
        return parseSpeechPlan(text, members).take(maxSpeakers)
    }

    /**
     * One member's reply: generated over the projected history with the member's own
     * persona (assistant.systemPrompt applies through GenerationHandler; the conversation's
     * custom prompt is intentionally NOT passed — it belongs to the group host context).
     * Returns blank on failure; the caller skips blank replies.
     */
    suspend fun generateMemberReply(
        conversation: Conversation,
        member: Assistant,
        memberNames: Map<Uuid, String>,
        hostAssistantName: String,
        hint: String?,
    ): String {
        val settings = settingsStore.settingsFlow.value
        val model = settings.findModelById(member.chatModelId, settings.chatModelId)
            ?: return ""
        val memories = memoryRepository.getMemoriesOfAssistant(member.id.toString())
        val others = memberNames.filterKeys { it != member.id }.values
            .filter { it.isNotBlank() }
            .joinToString("、")
        val directive = buildString {
            append("\n\n（群聊指令：你是 ${member.name.ifBlank { "成员" }}，正在和其他成员一起群聊")
            if (others.isNotBlank()) append("，其他成员：$others")
            append("。请以自己的身份、性格和口吻自然回复最新这条用户消息，不要复述他人，不要署名。")
            if (!hint.isNullOrBlank()) append("本轮提示：$hint")
            append("）")
        }
        val projected = projectMessagesForMember(
            messages = conversation.currentMessages,
            selfMemberId = member.id,
            memberNames = memberNames + (member.id to member.name),
            fallbackAssistantName = hostAssistantName,
        )
        if (projected.isEmpty()) return ""
        val lastIndex = projected.lastIndex
        val directed = projected[lastIndex].let { last ->
            last.copy(parts = last.parts + UIMessagePart.Text(directive))
        }
        val messages = projected.dropLast(1) + directed
        return generateText(
            settings = settings,
            assistant = member.copy(streamOutput = false),
            model = model,
            conversationId = conversation.id,
            memories = memories,
            messages = messages,
            conversationSystemPrompt = null,
            conversationContextSummary = conversation.compressedSummary,
        ).trim()
    }

    private fun parseSpeechPlan(text: String, members: List<Assistant>): List<MemberSpeechPlan> {
        val jsonText = text.extractJsonObject() ?: return emptyList()
        return runCatching {
            val obj = JsonInstant.parseToJsonElement(jsonText).jsonObject
            val speaks = obj["speaks"]?.jsonArray ?: return emptyList()
            speaks.mapNotNull { element ->
                val entry = element.jsonObject
                val idText = entry["assistant_id"]?.jsonPrimitive?.contentOrNull
                    ?: entry["member_id"]?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null
                val id = runCatching { Uuid.parse(idText.trim()) }.getOrNull() ?: return@mapNotNull null
                if (members.none { it.id == id }) return@mapNotNull null
                MemberSpeechPlan(
                    memberAssistantId = id,
                    hint = entry["hint"]?.jsonPrimitive?.contentOrNull?.take(200),
                )
            }.distinctBy { it.memberAssistantId }
        }.getOrElse { emptyList() }
    }

    private suspend fun generateText(
        settings: Settings,
        assistant: Assistant,
        model: me.rerere.ai.provider.Model,
        conversationId: Uuid,
        memories: List<me.rerere.rikkahub.data.model.AssistantMemory> = emptyList(),
        messages: List<UIMessage>,
        conversationSystemPrompt: String?,
        conversationContextSummary: String?,
    ): String {
        var output = ""
        generationHandler.generateText(
            settings = settings,
            model = model,
            conversationId = conversationId,
            messages = messages,
            assistant = assistant,
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
        return output
    }

    private fun List<UIMessagePart>.asReadableText(): String =
        filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }.trim()

    private fun String.extractJsonObject(): String? {
        val cleaned = replace("```json", "", ignoreCase = true)
            .replace("```", "")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        return if (start >= 0 && end > start) cleaned.substring(start, end + 1) else null
    }

    private companion object {
        const val TRANSCRIPT_WINDOW = 20
    }
}
