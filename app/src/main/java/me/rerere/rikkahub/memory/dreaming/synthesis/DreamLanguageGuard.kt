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

    fun hanCount(text: String): Int = text.count { isHan(it) }

    fun latinCount(text: String): Int = text.count { it in 'a'..'z' || it in 'A'..'Z' }

    /**
     * true = 这句话是「英文骨架 + 中文槽位」形态，应当判定为语言不达标。
     * 纯英文（无汉字）返回 false：那是英文用户的正常产出，不在本守卫的判据里。
     */
    fun isMixedLanguageOutput(text: String): Boolean {
        val han = hanCount(text)
        if (han == 0) return false
        val latin = latinCount(text)
        return latin >= MIN_LATIN_FOR_FRAME && latin > han * LATIN_TO_HAN_RATIO
    }

    private fun isHan(char: Char): Boolean =
        Character.UnicodeScript.of(char.code) == Character.UnicodeScript.HAN
}
