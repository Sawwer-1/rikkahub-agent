package me.rerere.rikkahub.data.repository

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 工单 CB 2026-10-18：滚动摘要「算了但没落库」。
 *
 * `saveConversation` 走 `updateRuntimeConversation` → `withRuntimeGraph`，它此前只把
 * messageNodes / chatSuggestions / updateAt 换成调用方的，**压缩状态取库里的旧值**——
 * 于是「手动确认」弹「已压缩 N 条」、库里却一条没折，自动路径每轮白算。
 */
class RuntimeGraphCompressionStateTest {
    @Test
    fun `runtime graph write carries the compression state from the incoming conversation`() {
        val foldedNode = node("old question")
        val stored = conversation(
            title = "库里的标题",
            nodes = listOf(foldedNode, node("recent")),
            summary = null,
            compressedIds = emptySet(),
        )
        val compressed = stored.copy(
            compressedSummary = "滚动摘要正文",
            compressedMessageNodeIds = setOf(foldedNode.id),
        )

        val merged = stored.withRuntimeGraph(compressed)

        // 压缩状态必须跟着图走，否则这次压缩等于没做。
        assertEquals("滚动摘要正文", merged.compressedSummary)
        assertEquals(setOf(foldedNode.id), merged.compressedMessageNodeIds)
        assertEquals(setOf(foldedNode.id), merged.activeCompressedMessageNodeIds)
        assertEquals(1, merged.visibleMessageNodes.size)
    }

    @Test
    fun `clearing the summary and folded ids is persisted too`() {
        val foldedNode = node("old question")
        val stored = conversation(
            title = "标题",
            nodes = listOf(foldedNode, node("recent")),
            summary = "旧摘要",
            compressedIds = setOf(foldedNode.id),
        )
        val cleared = stored.copy(
            compressedSummary = null,
            compressedMessageNodeIds = emptySet(),
        )

        val merged = stored.withRuntimeGraph(cleared)

        // 「撤销折叠」也要能落库：清空后两条消息都回到请求里，不能只清摘要、留着隐藏 id。
        assertNull(merged.compressedSummary)
        assertEquals(emptySet<Uuid>(), merged.compressedMessageNodeIds)
        assertEquals(2, merged.visibleMessageNodes.size)
    }

    @Test
    fun `non graph metadata still comes from the stored row`() {
        val incoming = conversation(
            title = "调用方的标题",
            nodes = listOf(node("a")),
            summary = "摘要",
            compressedIds = emptySet(),
        ).copy(customSystemPrompt = "调用方的提示词", folderId = "incoming-folder")

        val stored = conversation(
            title = "库里的标题",
            nodes = listOf(node("a"), node("b")),
            summary = null,
            compressedIds = emptySet(),
        ).copy(customSystemPrompt = "库里的提示词", folderId = "stored-folder")

        val merged = stored.withRuntimeGraph(incoming)

        assertEquals("库里的标题", merged.title)
        assertEquals("库里的提示词", merged.customSystemPrompt)
        assertEquals("stored-folder", merged.folderId)
        assertEquals("摘要", merged.compressedSummary)
    }

    private fun conversation(
        title: String,
        nodes: List<MessageNode>,
        summary: String?,
        compressedIds: Set<Uuid>,
    ) = Conversation(
        id = CONVERSATION_ID,
        assistantId = ASSISTANT_ID,
        title = title,
        messageNodes = nodes,
        compressedSummary = summary,
        compressedMessageNodeIds = compressedIds,
    )

    private fun node(label: String) = MessageNode(
        messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(label))),
        ),
    )

    private companion object {
        val CONVERSATION_ID: Uuid = Uuid.random()
        val ASSISTANT_ID: Uuid = Uuid.random()
    }
}
