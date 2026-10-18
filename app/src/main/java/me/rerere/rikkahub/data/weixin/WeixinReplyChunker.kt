package me.rerere.rikkahub.data.weixin

/**
 * 微信桥的**回复分段**（B05 / R17）。
 *
 * 背景：iLink / 公众号客服消息的文本长度有上限（2048 字节量级），超长回复会被整条拒掉，
 * 用户侧表现为「消息发出去但对方没收到」。此前 `sendTextOrLog` 直接把整段文本丢给
 * `sendTextMessage`，长回复（尤其是带长代码块/长列表的）必踩。
 *
 * 规则（按优先级）：
 * 1. 不超限就原样一条；
 * 2. 优先在**空行**（`\n\n`）处切，保持段落完整；
 * 3. 段落太长就退到**单换行**；
 * 4. 再长就按**句末标点**（。！？!?；;）切；
 * 5. 仍超限（长代码块/无标点长句）才**硬切**，保证每段都不超过上限。
 *
 * 纯函数、不依赖 Android，便于单测；阈值由 `WeixinBotConfig.replyChunkChars` 提供。
 */
internal const val DEFAULT_WEIXIN_REPLY_CHUNK_CHARS = 1800
internal const val MIN_WEIXIN_REPLY_CHUNK_CHARS = 200
internal const val MAX_WEIXIN_REPLY_CHUNK_CHARS = 4000

internal fun splitWeixinReply(
    text: String,
    maxChars: Int = DEFAULT_WEIXIN_REPLY_CHUNK_CHARS,
): List<String> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return emptyList()
    val limit = maxChars.coerceIn(MIN_WEIXIN_REPLY_CHUNK_CHARS, MAX_WEIXIN_REPLY_CHUNK_CHARS)
    if (trimmed.length <= limit) return listOf(trimmed)

    val chunks = ArrayList<String>()
    var rest = trimmed
    while (rest.length > limit) {
        val window = rest.substring(0, limit)
        // 依次尝试段落 / 行 / 句子边界；都找不到才硬切。
        val cut = lastBoundary(window, "\n\n")
            ?: lastBoundary(window, "\n")
            ?: lastBoundary(window, "。")
            ?: lastBoundary(window, "！")
            ?: lastBoundary(window, "？")
            ?: lastBoundary(window, "；")
            ?: lastBoundary(window, ";")
            ?: limit
        val piece = rest.substring(0, cut).trimEnd()
        if (piece.isNotBlank()) chunks.add(piece)
        rest = rest.substring(cut).trimStart()
    }
    if (rest.isNotBlank()) chunks.add(rest)
    return chunks
}

/** 返回 `window` 内最后一个 [marker] **之后**的位置；太靠前（< 上限 30%）就放弃，避免碎成一堆短段。 */
private fun lastBoundary(window: String, marker: String): Int? {
    val index = window.lastIndexOf(marker)
    if (index < 0) return null
    val cut = index + marker.length
    return if (cut >= window.length * 3 / 10) cut else null
}
