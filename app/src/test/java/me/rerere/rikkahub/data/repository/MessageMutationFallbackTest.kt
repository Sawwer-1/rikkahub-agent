package me.rerere.rikkahub.data.repository

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.service.chat.MutateMessageCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 2026-10-28（工单 CB 编辑消息冲突）的契约：
 *
 * 发送命令的 admission 与执行端会为**同一条用户消息**各生成一个随机锚点节点（库一份、
 * 内存一份），编辑命令携带的 node id 可能来自另一份图。消息 id 两侧同源，因此
 * [applyMessageMutation] 在按 nodeId 找不到时必须回退按 messageId 定位，否则会以
 * Conflict("Message target missing") 丢弃用户的编辑。
 */
class MessageMutationFallbackTest {

    private val messageId = Uuid.random()

    private fun conversationWithUserNode(nodeId: Uuid): Conversation {
        val userMessage = UIMessage(
            id = messageId,
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("旧内容")),
        )
        return Conversation.ofId(
            id = Uuid.random(),
            messages = listOf(MessageNode(id = nodeId, messages = listOf(userMessage))),
        )
    }

    @Test
    fun `node id from the other graph falls back to the shared message id`() {
        val nodeIdInMemory = Uuid.random()
        val nodeIdInDb = Uuid.random()
        val conversation = conversationWithUserNode(nodeIdInMemory)
        val newMessageId = Uuid.random()

        val updated = applyMessageMutation(
            conversation,
            MutateMessageCommand(
                nodeId = nodeIdInDb,
                messageId = messageId,
                replacementParts = listOf(UIMessagePart.Text("新内容")),
                newMessageId = newMessageId,
            ),
        )

        assertNotNull(updated)
        val node = updated!!.messageNodes.single()
        // 新版本追加在真实存在的那个节点上，选中它，旧版本保留。
        assertEquals(listOf(messageId, newMessageId), node.messages.map { it.id })
        assertEquals(1, node.selectIndex)
        assertEquals(
            "新内容",
            (node.messages.last().parts.single() as UIMessagePart.Text).text,
        )
    }

    @Test
    fun `unknown message id is still a conflict`() {
        val conversation = conversationWithUserNode(Uuid.random())

        val updated = applyMessageMutation(
            conversation,
            MutateMessageCommand(
                nodeId = Uuid.random(),
                messageId = Uuid.random(),
                replacementParts = listOf(UIMessagePart.Text("新内容")),
            ),
        )

        assertNull(updated)
    }
}
