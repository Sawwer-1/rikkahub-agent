package me.rerere.rikkahub.memory.dreaming.synthesis

/**
 * Dream 产出的**语言混排**守卫（2026-10-17 真机：`识之律者 consistently replies to 安桐's roleplay…`）。
 *
 * 设计约束（为什么不判「应该用哪种语言」）：
 * 1. 本机拿不到可靠的用户语言偏好，硬判"必须中文"会把英文用户的正常产出判死；
 * 2. 真机症状有明确形态：**英文句式骨架 + 中文专名/中文槽位**（英文连接词 + 中文人名）。
 *    这个形态不需要语言识别就能判——同一句里既有汉字、又由拉丁字母主导。
 *
 * 所以这里只判"混排"：句中出现汉字，且拉丁字母数量是汉字的两倍以上（且拉丁字母足够多，
 * 避免把「DeepSeek V4 的思考链」这种术语混用误判）。
 *
 * 命中后的处置**不在本类**：由 `DreamProposalValidator` 抛 `LANGUAGE_MISMATCH`，
 * 复用既有的「校验拒绝 → 一次 repair 重写 → 再拒则整批终局失败」链路，
 * 因此不达标的内容不会入库，也不会污染下一轮的 `existing_claims`。
 */
internal object DreamLanguageGuard {
    /** 拉丁字母达到这个数量才可能构成"英文句式骨架"，低于它的短词（术语混用）不判。 */
    private const val MIN_LATIN_FOR_FRAME = 20

    /** 拉丁字母 / 汉字 的倍数阈值：超过它就是英文主导，而句子里明明有汉字 ⇒ 混排。 */
    private const val LATIN_TO_HAN_RATIO = 2

    /**
     * 输入以中文为主时，单条产出允许保留的拉丁字符上限。
     * 40 够放几个专名/标识符（`DeepSeek`、`v4.1-flash`、文件路径片段），但装不下一句英文。
     */
    private const val MAX_LATIN_UNDER_CHINESE_INPUT = 40

    /** 输入 payload 至少要有这么多汉字，才谈得上"中文为主"（防小样本误判）。 */
    private const val MIN_HAN_FOR_CHINESE_INPUT = 200

    fun hanCount(text: String): Int = text.count { isHan(it) }

    fun latinCount(text: String): Int = text.count { it in 'a'..'z' || it in 'A'..'Z' }

    /**
     * 输入主语言判定（2026-10-17 用户拍板：中文会话必须全中文输出）。
     *
     * 只看 payload 里的字符构成，不做词形/语言库分析：汉字数 ≥ 200 且汉字数 × 3 ≥ 拉丁字母数
     * 即视为"中文为主"。英文会话汉字≈0，永远为 false，不会被这条规则误伤。
     */
    fun isChineseDominantInput(payload: String): Boolean {
        val han = hanCount(payload)
        if (han < MIN_HAN_FOR_CHINESE_INPUT) return false
        return han * 3 >= latinCount(payload)
    }

    /**
     * true = 这句话是「英文骨架 + 中文槽位」形态，应当判定为语言不达标。
     * 纯英文（无汉字）返回 false：那是英文用户的正常产出，不在本判据里。
     */
    fun isMixedLanguageOutput(text: String): Boolean {
        val han = hanCount(text)
        if (han == 0) return false
        val latin = latinCount(text)
        return latin >= MIN_LATIN_FOR_FRAME && latin > han * LATIN_TO_HAN_RATIO
    }

    /**
     * 最终判据（校验器只用这个）：
     * - 输入以中文为主 ⇒ **必须全中文**：拉丁字符超过 40 个就拒（纯英文摘要同样被拒）；
     * - 输入不是中文为主 ⇒ 退回"混排"判据，纯英文放行。
     */
    fun isDisallowedOutput(
        text: String,
        chineseDominantInput: Boolean,
    ): Boolean = if (chineseDominantInput) {
        latinCount(text) > MAX_LATIN_UNDER_CHINESE_INPUT
    } else {
        isMixedLanguageOutput(text)
    }

    private fun isHan(char: Char): Boolean =
        Character.UnicodeScript.of(char.code) == Character.UnicodeScript.HAN
}
