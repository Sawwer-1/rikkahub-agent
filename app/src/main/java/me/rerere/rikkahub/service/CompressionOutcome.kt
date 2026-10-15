package me.rerere.rikkahub.service

/**
 * What one compression pass actually did.
 *
 * Reported back to the caller so the UI can say "f N messages folded, M kept" instead of a bare
 * success, and so it can distinguish "nothing to fold" ([foldedMessages] == 0, e.g. the pass was
 * dropped because a newer write won the race) from "the conversation was compressed".
 */
data class CompressionOutcome(
    val foldedMessages: Int,
    val keptMessages: Int,
)
