package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

sealed interface ConversationMetadataMutation {
    data class Injections(val modes: Set<Uuid>, val lorebooks: Set<Uuid>) : ConversationMetadataMutation
    data class GeneratedTitle(val value: String, val force: Boolean) : ConversationMetadataMutation
    data class Title(val value: String) : ConversationMetadataMutation
    data class SystemPrompt(val value: String?) : ConversationMetadataMutation
    data class Workspace(val value: String?) : ConversationMetadataMutation
    data class Mode(val id: Uuid, val enabled: Boolean) : ConversationMetadataMutation
    data class Lorebook(val id: Uuid, val enabled: Boolean) : ConversationMetadataMutation

    /**
     * 会话级「自动滚动摘要」配置（2026-10-15 新增）。
     *
     * 必须走元数据写入而不是 [Conversation] 整快照：`saveConversation` 走的是
     * `updateRuntimeConversation` → `withRuntimeGraph`，后者**只取** messageNodes / chatSuggestions /
     * updateAt，其余字段一律以库里为准 —— `autoCompressConfig` 会在那条路上被静默丢弃。
     * 236 实测症状：压缩弹窗点「保存」弹「未能保存，请重试」、重开开关回落关闭。
     */
    data class AutoCompress(val value: me.rerere.rikkahub.data.model.AutoCompressConfig?) :
        ConversationMetadataMutation

    fun apply(current: Conversation): Conversation = when (this) {
        is Injections -> current.copy(modeInjectionIds = modes, lorebookIds = lorebooks)
        is GeneratedTitle -> if (force || current.title.isBlank()) current.copy(title = value) else current
        is Title -> current.copy(title = value)
        is SystemPrompt -> current.copy(customSystemPrompt = value)
        is Workspace -> current.copy(workspaceCwd = value)
        is Mode -> current.copy(modeInjectionIds = if (enabled) current.modeInjectionIds + id else current.modeInjectionIds - id)
        is Lorebook -> current.copy(lorebookIds = if (enabled) current.lorebookIds + id else current.lorebookIds - id)
        is AutoCompress -> current.copy(autoCompressConfig = value)
    }
}

internal fun Conversation.withRuntimeGraph(incoming: Conversation): Conversation {
    require(id == incoming.id)
    return copy(messageNodes = incoming.messageNodes, chatSuggestions = incoming.chatSuggestions,
        updateAt = incoming.updateAt, newConversation = false)
}

internal fun applyMessageMutation(
    current: Conversation,
    command: me.rerere.rikkahub.service.chat.MutateMessageCommand,
): Conversation? {
    val node = current.messageNodes.find { it.id == command.nodeId } ?: return null
    val index = node.messages.indexOfFirst { it.id == command.messageId }
    if (index < 0) return null
    val next = if (command.replacementParts == null) node.copy(selectIndex = index)
    else node.copy(messages = node.messages + me.rerere.ai.ui.UIMessage(
        id = command.newMessageId, role = node.role, parts = command.replacementParts),
        selectIndex = node.messages.size)
    return current.copy(messageNodes = current.messageNodes.map { if (it.id == node.id) next else it })
}
