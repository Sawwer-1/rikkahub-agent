package me.rerere.ai.provider.providers.openai

/**
 * 「工具轮强制回传」reasoning 副本的护栏工具（纯函数、无 Android 依赖，可直接 JVM 单测）。
 *
 * 背景（工单 CB 差分定位）：deepseek-v4 系模型在工具轮会把上一轮 reasoning 原样回传；
 * 长工具链里一旦模型产出「好。」「执行。」式重复退化，完整回传会自我强化重复片段并
 * 挤占输出预算。此处两个净化只作用于**请求副本**，不修改内存中保存的 UIMessagePart.Reasoning。
 *
 * 仅用于 requireToolReasoningContent && 用户未开启 includeHistoryReasoning 的强制路径；
 * includeHistoryReasoning=true 的用户显式全量回传保持原样（不经过本文件）。
 */

/** 折叠连续重复短片段后追加的标记。 */
internal const val REPEATED_SEGMENT_FOLD_MARKER = "…（重复已折叠）"

/** 参与折叠判定的片段长度上限（trim 后字符数）；更长的连续相同片段不折叠。 */
private const val MAX_REPEATED_SEGMENT_CHARS = 24

/** 连续出现至少这么多次才折叠。 */
private const val MIN_REPEATED_RUN = 3

/**
 * 生成工具轮强制回传用的 reasoning 副本：
 * 1. 折叠连续重复的短片段（行内句子级 + 行级，见 [foldRepeatedShortSegments]）；
 * 2. 折叠后仍超长时，只保留尾部 [maxChars] 个字符——尾部承载结论、下一步动作等决策信息。
 */
internal fun prepareToolTurnReasoningForReplay(
    raw: String,
    maxChars: Int = MAX_TOOL_TURN_REASONING_CHARS,
): String {
    if (raw.isEmpty()) return raw
    val folded = foldRepeatedShortSegments(raw)
    if (folded.length <= maxChars) return folded
    val tail = folded.takeLast(maxChars)
    // 避免截在 surrogate pair 中间产生半个字符（如 emoji 被切一半）。
    return if (tail.isNotEmpty() && tail[0].isLowSurrogate()) tail.drop(1) else tail
}

/**
 * 折叠「连续 ≥3 次出现的相同短片段」（trim 后非空且长度 ≤ 24 字符）：
 * - 先做行内句子级折叠（句末标点：。！？!?；英文 '.' 需后跟空白或行尾才算句末）；
 * - 再做行级折叠（整行 trim 后相同的连续行）。
 * 折叠后保留 1 次出现并追加 [REPEATED_SEGMENT_FOLD_MARKER]；空行/纯空白片段不参与折叠。
 */
internal fun foldRepeatedShortSegments(raw: String): String {
    if (raw.isBlank()) return raw
    val lines = raw.split('\n')
    val sentenceFoldedLines = lines.map { foldRepeatsInLine(it) }
    return foldRepeatedRuns(sentenceFoldedLines).joinToString("\n")
}

/** 把一行切成「句子 + 句末标点 + 尾随空白」片段，保持原字符顺序。 */
internal fun splitIntoSentenceSegments(line: String): List<String> {
    val segments = ArrayList<String>()
    if (line.isEmpty()) return segments
    val sb = StringBuilder()
    var i = 0
    while (i < line.length) {
        val ch = line[i]
        sb.append(ch)
        val next = line.getOrNull(i + 1)
        val isBoundary = when (ch) {
            '。', '！', '？', '!', '?' -> true
            // 英文句点仅在后跟空白或行尾时才算句末，避免切坏 3.14、v1.2 这类内容。
            '.' -> next == null || next.isWhitespace()
            else -> false
        }
        if (isBoundary) {
            // 尾随空白并入当前片段，折叠比较与输出都保持原顺序。
            while (i + 1 < line.length && line[i + 1].isWhitespace()) {
                i++
                sb.append(line[i])
            }
            segments.add(sb.toString())
            sb.clear()
        }
        i++
    }
    if (sb.isNotEmpty()) segments.add(sb.toString())
    return segments
}

private fun foldRepeatsInLine(line: String): String {
    if (line.isEmpty()) return line
    return foldRepeatedRuns(splitIntoSentenceSegments(line)).joinToString("")
}

/** 片段可作为折叠单位的 key（trim 后非空且不超过长度上限）；否则返回 null（不参与折叠）。 */
private fun foldableKeyOf(segment: String): String? {
    val key = segment.trim()
    return if (key.isEmpty() || key.length > MAX_REPEATED_SEGMENT_CHARS) null else key
}

/** 对相邻片段做 run 折叠：连续 ≥ [MIN_REPEATED_RUN] 个相同短片段 → 保留 1 个 + 标记。 */
private fun foldRepeatedRuns(segments: List<String>): List<String> {
    val out = ArrayList<String>(segments.size)
    var i = 0
    while (i < segments.size) {
        val key = foldableKeyOf(segments[i])
        if (key == null) {
            out.add(segments[i])
            i++
            continue
        }
        var j = i + 1
        while (j < segments.size && foldableKeyOf(segments[j]) == key) j++
        if (j - i >= MIN_REPEATED_RUN) {
            out.add(segments[i] + REPEATED_SEGMENT_FOLD_MARKER)
        } else {
            for (k in i until j) out.add(segments[k])
        }
        i = j
    }
    return out
}
