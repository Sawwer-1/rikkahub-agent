package me.rerere.rikkahub.data.ai.waifu

/**
 * Waifu typewriter (sentence-split bubbles): pure-text sentence splitter.
 *
 * Splits a growing assistant reply into finished sentences plus an unfinished tail.
 * The splitter is a pure JVM function (no Android dependencies) so the streaming
 * mount point in ChatService can re-run it on every chunk against the not-yet-split
 * remainder, and unit tests can cover it without a device.
 *
 * Rules (WAIFU_TASK design decisions, fixed — do not improvise):
 * - Sentence-ending characters: [SENTENCE_ENDERS] (。！？!?…\n).
 * - Closing quotes/brackets immediately following the boundary ([CLOSERS]) belong to
 *   the same sentence.
 * - A run of ellipsis characters (…) belongs entirely to one sentence (no split
 *   inside "……").
 * - A sentence that accumulates more than [maxSentenceChars] is force-cut at that
 *   limit.
 * - A cut candidate shorter than [minSentenceChars] is merged into the NEXT sentence:
 *   it is not emitted as a finished sentence, it stays in the tail until the next
 *   boundary (or the end-of-stream flush) produces a unit that reaches the minimum.
 * - The segment touching the end of the input is NEVER emitted as a finished sentence:
 *   closing quotes may still arrive in a later chunk, and the last sentence of a reply
 *   is materialized by the end-of-stream flush instead. This keeps the splitter
 *   prefix-stable: appending text never changes already-emitted sentences.
 */
class WaifuSentenceSplitter(
    minSentenceChars: Int,
    maxSentenceChars: Int,
) {
    private val minSentenceChars = minSentenceChars.coerceAtLeast(1)
    private val effectiveMaxChars = maxSentenceChars.coerceAtLeast(this.minSentenceChars)

    data class SplitResult(
        /** Finished sentences; safe to materialize as independent bubbles. */
        val sentences: List<String>,
        /** Text that is not final yet (held for merge-into-next / waiting for more input). */
        val tail: String,
    )

    fun split(text: String): SplitResult {
        if (text.isEmpty()) return SplitResult(emptyList(), "")
        val sentences = mutableListOf<String>()
        val carry = StringBuilder()
        var index = 0
        while (index < text.length) {
            val char = text[index]
            carry.append(char)
            index++
            if (char in SENTENCE_ENDERS) {
                // Consume the trailing closer group: closing quotes/brackets and any
                // continuation of an ellipsis run belong to this sentence.
                while (index < text.length &&
                    (text[index] in CLOSERS || (char == ELLIPSIS && text[index] == ELLIPSIS))
                ) {
                    carry.append(text[index])
                    index++
                }
                // Boundary strictly before the end of the input -> the segment is final
                // (subject to the minimum-length merge). A boundary AT the end is held in
                // the tail so late-arriving closers can still join the sentence.
                if (index < text.length) {
                    if (carry.length >= minSentenceChars && carry.isNotBlank()) {
                        sentences.add(carry.toString())
                        carry.clear()
                    }
                    // else: keep carrying; a too-short (or blank) segment merges into the
                    // next sentence instead of becoming its own bubble.
                }
            } else if (carry.length >= effectiveMaxChars && index < text.length &&
                carry.isNotBlank()
            ) {
                // Force cut for an over-long sentence. Held at the end of the input for
                // the same prefix-stability reason as natural boundaries.
                sentences.add(carry.toString())
                carry.clear()
            }
        }
        return SplitResult(sentences, carry.toString())
    }

    companion object {
        /** Characters that terminate a sentence. */
        const val SENTENCE_ENDERS = "。！？!?…\n"

        /** Closing quotes/brackets that stick to the preceding sentence boundary. */
        const val CLOSERS = "」』\"'）】"

        private const val ELLIPSIS = '…'
    }
}
