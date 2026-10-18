package me.rerere.rikkahub.data.weixin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B05 / R17：微信桥长回复分段。
 *
 * 约束：每段都不得超过阈值（iLink 超长会整条被拒）；优先在段落/句子边界切开，
 * 不产生空段；短回复一条都不拆。
 */
class WeixinReplyChunkerTest {
    @Test
    fun `short reply stays a single message`() {
        val text = "好的，这件事我来处理。"
        assertEquals(listOf(text), splitWeixinReply(text, maxChars = 1000))
    }

    @Test
    fun `blank reply produces no chunk at all`() {
        assertTrue(splitWeixinReply("   \n\n  ", maxChars = 1000).isEmpty())
    }

    @Test
    fun `long reply splits at paragraph boundaries`() {
        val paragraph = "第一段：先核对日志与请求体。".repeat(6)
        val text = (1..5).joinToString("\n\n") { paragraph + it }
        val limit = 200

        val chunks = splitWeixinReply(text, limit)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= limit })
        // 空行边界被保留在段内，不会被拼成一条超长消息。
        assertTrue(chunks.all { it.isNotBlank() })
        assertEquals(text.replace("\n", "").length, chunks.joinToString("").replace("\n", "").length)
    }

    @Test
    fun `huge paragraph without punctuation is hard cut`() {
        val text = "a".repeat(5000)
        val chunks = splitWeixinReply(text, maxChars = 200)

        assertEquals(25, chunks.size)
        assertTrue(chunks.all { it.length == 200 })
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun `sentence boundaries are preferred over hard cuts`() {
        val text = (1..40).joinToString("") { "第${it}步，先确认再动手，避免返工。" }
        val chunks = splitWeixinReply(text, maxChars = 200)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 200 })
        // 除最后一段外，其余都以句末标点收尾（说明切在句子上，不是硬切）。
        assertTrue(chunks.dropLast(1).all { it.endsWith("。") })
    }

    @Test
    fun `limit is clamped into the supported range`() {
        val text = "x".repeat(300)
        // 传 10 会被夹到 200：至少保证一段不超过 200，而不是切成一堆 10 字的碎片。
        val chunks = splitWeixinReply(text, maxChars = 10)
        assertTrue(chunks.all { it.length <= MIN_WEIXIN_REPLY_CHUNK_CHARS })
        assertTrue(chunks.size >= 2)
    }
}
