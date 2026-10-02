package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.model.AutoCompressConfig
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid

/** Decodes a persisted AutoCompressConfig JSON blob; empty/legacy values decode to null. */
private fun String.toAutoCompressConfigOrNull(): AutoCompressConfig? =
    ifEmpty { null }?.let { JsonInstant.decodeFromString<AutoCompressConfig>(it) }

fun conversationToConversationEntity(conversation: Conversation): ConversationEntity {
    require(conversation.messageNodes.none { it.messages.any { message -> message.hasBase64Part() } })
    return ConversationEntity(
        id = conversation.id.toString(),
        title = conversation.title,
        nodes = "[]",  // nodes 现在存储在单独的表中
        createAt = conversation.createAt.toEpochMilli(),
        updateAt = conversation.updateAt.toEpochMilli(),
        assistantId = conversation.assistantId.toString(),
        chatSuggestions = JsonInstant.encodeToString(conversation.chatSuggestions),
        isPinned = conversation.isPinned,
        customSystemPrompt = conversation.customSystemPrompt ?: "",
        modeInjectionIds = JsonInstant.encodeToString(conversation.modeInjectionIds),
        lorebookIds = JsonInstant.encodeToString(conversation.lorebookIds),
        workspaceCwd = conversation.workspaceCwd ?: "",
        folderId = conversation.folderId,
        compressedSummary = conversation.compressedSummary ?: "",
        compressedMessageNodeIds = JsonInstant.encodeToString(conversation.compressedMessageNodeIds),
        autoCompressConfig = conversation.autoCompressConfig
            ?.let { JsonInstant.encodeToString(it) }
            ?: "",
    )
}

fun conversationEntityToConversation(
    conversationEntity: ConversationEntity,
    messageNodes: List<MessageNode>
): Conversation {
    return Conversation(
        id = Uuid.parse(conversationEntity.id),
        title = conversationEntity.title,
        messageNodes = messageNodes.filter { it.messages.isNotEmpty() },
        createAt = Instant.ofEpochMilli(conversationEntity.createAt),
        updateAt = Instant.ofEpochMilli(conversationEntity.updateAt),
        assistantId = Uuid.parse(conversationEntity.assistantId),
        chatSuggestions = JsonInstant.decodeFromString(conversationEntity.chatSuggestions),
        isPinned = conversationEntity.isPinned,
        customSystemPrompt = conversationEntity.customSystemPrompt.ifEmpty { null },
        modeInjectionIds = JsonInstant.decodeFromString(conversationEntity.modeInjectionIds),
        lorebookIds = JsonInstant.decodeFromString(conversationEntity.lorebookIds),
        workspaceCwd = conversationEntity.workspaceCwd.ifEmpty { null },
        folderId = conversationEntity.folderId,
        compressedSummary = conversationEntity.compressedSummary.ifEmpty { null },
        compressedMessageNodeIds = JsonInstant.decodeFromString(
            conversationEntity.compressedMessageNodeIds,
        ),
        autoCompressConfig = conversationEntity.autoCompressConfig.toAutoCompressConfigOrNull(),
    )
}
