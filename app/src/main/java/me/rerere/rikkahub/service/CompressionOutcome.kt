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
    /**
     * 摘要生成失败（空摘要）导致**本次没有折叠**（工单 CB 2026-10-19 N1/O2）。
     * 与之相对的是「本来就没得折」（[foldedMessages] == 0 且本字段 false）。
     */
    val summaryEmpty: Boolean = false,
)
