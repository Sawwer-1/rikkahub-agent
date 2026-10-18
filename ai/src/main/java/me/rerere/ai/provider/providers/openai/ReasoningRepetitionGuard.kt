package me.rerere.ai.provider.providers.openai

/**
 * 思考通道**重复退化**检测（B04 / R6：重复检测 → 提前收敛护栏）。
 *
 * 背景（思维链工单 A-3 / D3，2026-10-17 起悬空）：思考通道会出现「好。」「我们继续。」这类
 * 短句连续刷屏，模型把输出预算全烧在重复片段上，正文迟迟不落地。243 只在**回传副本**上做了
 * 折叠与尾截断（见 `ToolTurnReasoningGuard`），治的是「把退化喂回模型、自我强化」；
 * 但**生成本身**没有任何护栏——只要上游不停，就一路烧到 `max_tokens`。
 *
 * 这里补的是后半段：流式过程中检测到退化就**提前收尾**（停流 + 给一个正常终止标记），
 * 让这一轮用已生成的内容结束，而不是等预算耗尽。
 *
 * 判据刻意保守（宁可漏判，不可误杀正常长列表/重复句式）：
 * - 只有累计思考 ≥ [REPETITION_MIN_TEXT_CHARS] 才判（短思考不参与）；
 * - 只看尾部 [REPETITION_TAIL_WINDOW_CHARS] 窗口，避免每来一个 chunk 就扫全文；
 * - 句级：**同一短句**（trim 后 ≤ [REPETITION_SEGMENT_MAX_CHARS]，句末标点切分）
 *   连续出现 ≥ [REPETITION_MIN_RUN] 次；
 * - 行级：**同一整行**连续出现 ≥ [REPETITION_MIN_LINE_RUN] 次（长句/无标点的刷屏走这条）。
 */
internal const val REPETITION_MIN_TEXT_CHARS = 400
internal const val REPETITION_SEGMENT_MAX_CHARS = 24
internal const val REPETITION_MIN_RUN = 30
internal const val REPETITION_MIN_LINE_RUN = 8
internal const val REPETITION_TAIL_WINDOW_CHARS = 8_000

/** 退化判定结果；[cutIndex] 指向重复段**第一次出现**的位置（保留 `[0, cutIndex)` 的内容）。 */
internal data class RepetitionVerdict(
    val cutIndex: Int,
    val repeatedRun: Int,
    /** 供日志与单测断言的退化片段描述（截断到 24 字，避免日志爆掉）。 */
    val reason: String,
)

/**
 * 检测累计思考文本尾部的重复退化；无退化返回 null。
 *
 * 纯函数、无状态：调用方每次把「截至目前的完整思考」传进来即可（内部只扫尾部窗口）。
 */
internal fun detectReasoningRepetition(raw: String): RepetitionVerdict? {
    if (raw.length < REPETITION_MIN_TEXT_CHARS) return null
    val windowStart = (raw.length - REPETITION_TAIL_WINDOW_CHARS).coerceAtLeast(0)
    val tail = raw.substring(windowStart)
    if (tail.isBlank()) return null

    val lines = tail.split('\n')

    // 1) 行级：同一整行连续刷屏（长句、无句末标点也能抓）。
    detectLineRun(lines, windowStart)?.let { return it }

    // 2) 句级：同一短句连续刷屏（「好。」「继续。」这类）。
    return detectSegmentRun(lines, windowStart)
}

private fun detectLineRun(lines: List<String>, windowStart: Int): RepetitionVerdict? {
    if (lines.size < REPETITION_MIN_LINE_RUN) return null
    val last = lines.last().trim()
    if (last.isEmpty()) return null
    var run = 1
    var index = lines.lastIndex - 1
    while (index >= 0 && lines[index].trim() == last) {
        run++
        index--
    }
    if (run < REPETITION_MIN_LINE_RUN) return null
    return RepetitionVerdict(
        cutIndex = windowStart + startOffsetOfLine(lines, index + 1),
        repeatedRun = run,
        reason = "line:" + last.take(REPETITION_SEGMENT_MAX_CHARS),
    )
}

private fun detectSegmentRun(lines: List<String>, windowStart: Int): RepetitionVerdict? {
    // 把每一行切成「句子 + 句末标点 + 尾随空白」，同时记下每段在窗口内的绝对起点。
    val segments = ArrayList<String>(lines.size * 2)
    val offsets = ArrayList<Int>(lines.size * 2)
    var offset = 0
    for (line in lines) {
        for (segment in splitIntoSentenceSegments(line)) {
            segments.add(segment.trim())
            offsets.add(offset)
            offset += segment.length
        }
        // 换行符本身占一个字符（split 已把它吃掉）。
        offset += 1
    }
    if (segments.isEmpty()) return null

    val last = segments.last()
    if (last.isEmpty() || last.length > REPETITION_SEGMENT_MAX_CHARS) return null
    var run = 1
    var index = segments.lastIndex - 1
    while (index >= 0 && segments[index] == last) {
        run++
        index--
    }
    if (run < REPETITION_MIN_RUN) return null
    return RepetitionVerdict(
        cutIndex = windowStart + offsets[index + 1],
        repeatedRun = run,
        reason = "segment:" + last.take(REPETITION_SEGMENT_MAX_CHARS),
    )
}

/** 第 [lineIndex] 行在窗口内的起始偏移（按原始字符计，含换行符）。 */
private fun startOffsetOfLine(lines: List<String>, lineIndex: Int): Int {
    var offset = 0
    for (i in 0 until lineIndex) {
        offset += lines[i].length + 1
    }
    return offset
}
