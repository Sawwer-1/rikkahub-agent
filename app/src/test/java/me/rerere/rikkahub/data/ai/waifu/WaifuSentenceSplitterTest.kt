package me.rerere.rikkahub.data.ai.waifu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaifuSentenceSplitterTest {
    private fun splitter(
        minSentenceChars: Int = 2,
        maxSentenceChars: Int = 120,
    ) = WaifuSentenceSplitter(
        minSentenceChars = minSentenceChars,
        maxSentenceChars = maxSentenceChars,
    )

    private fun WaifuSentenceSplitter.SplitResult.joined(text: String) {
        assertEquals(
            "sentences + tail must reproduce the input verbatim",
            text,
            sentences.joinToString(separator = "") + tail,
        )
    }

    @Test
    fun `empty text splits to nothing`() {
        val result = splitter().split("")
        assertTrue(result.sentences.isEmpty())
        assertEquals("", result.tail)
    }

    @Test
    fun `chinese punctuation ends sentences`() {
        val text = "你好。今天天气不错！可以出门吗？"
        val result = splitter().split(text)
        result.joined(text)
        // The last segment touches the end of the input and is held in the tail so a
        // late-arriving closing character can still join it.
        assertEquals(listOf("你好。", "今天天气不错！"), result.sentences)
        assertEquals("可以出门吗？", result.tail)
    }

    @Test
    fun `closing quotes and brackets stick to the sentence`() {
        val text = "他说：「你好吗？」我很好。"
        val result = splitter().split(text)
        result.joined(text)
        assertEquals(listOf("他说：「你好吗？」"), result.sentences)
        assertEquals("我很好。", result.tail)
    }

    @Test
    fun `ellipsis group is never split`() {
        val text = "等一下……然后呢。"
        val result = splitter().split(text)
        result.joined(text)
        assertEquals(listOf("等一下……"), result.sentences)
        assertEquals("然后呢。", result.tail)
    }

    @Test
    fun `newline is a sentence boundary`() {
        val text = "第一行\n第二行还在写"
        val result = splitter().split(text)
        result.joined(text)
        assertEquals(listOf("第一行\n"), result.sentences)
        assertEquals("第二行还在写", result.tail)
    }

    @Test
    fun `overlong sentence is force cut at the limit`() {
        val text = "abcdefghijkl"
        val result = splitter(maxSentenceChars = 5).split(text)
        result.joined(text)
        assertEquals(listOf("abcde", "fghij"), result.sentences)
        assertEquals("kl", result.tail)
        assertTrue(result.sentences.all { it.length <= 5 })
    }

    @Test
    fun `short segment merges into the next sentence`() {
        val text = "好。好的。好吗。"
        val result = splitter(minSentenceChars = 3).split(text)
        result.joined(text)
        // "好。" is below the minimum, so it merges forward into the next sentence
        // instead of becoming its own bubble.
        assertEquals(listOf("好。好的。"), result.sentences)
        assertEquals("好吗。", result.tail)
    }

    @Test
    fun `blank-only segments never become bubbles`() {
        val text = "你好。\n\n第二段。"
        val result = splitter().split(text)
        result.joined(text)
        assertTrue(result.sentences.none { it.isBlank() })
        assertEquals(listOf("你好。"), result.sentences)
    }

    @Test
    fun `half width punctuation is supported`() {
        val text = "Hi! How are you?"
        val result = splitter().split(text)
        result.joined(text)
        assertEquals(listOf("Hi!"), result.sentences)
        assertEquals(" How are you?", result.tail)
    }

    @Test
    fun `splitting is prefix stable while the tail keeps growing`() {
        val splitter = splitter()
        val streamed = listOf("你", "你好。今", "你好。今天天", "你好。今天天气不错。改")
        var previousCommitted = ""
        streamed.forEachIndexed { index, text ->
            val result = splitter.split(text)
            result.joined(text)
            val committed = result.sentences.joinToString(separator = "") { it }
            if (index > 0) {
                // Sentences never un-commit or change once emitted while text grows.
                assertTrue(committed.startsWith(previousCommitted))
            }
            previousCommitted = committed
        }
    }
}
