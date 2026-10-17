package me.rerere.rikkahub.memory.dreaming.synthesis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语言守卫单测（2026-10-17 真机 Dream 中英混排）。
 *
 * 判据只有一条：**同一句里既有汉字、又由拉丁字母主导**（英文句框 + 中文槽位）。
 * 纯英文必须放行——那是英文用户的正常产出，不能被判死。
 */
class DreamLanguageGuardTest {
    @Test
    fun `real device samples are rejected`() {
        val claim = "识之律者 consistently replies to 安桐's roleplay prompts with immersive " +
            "first-person narration, sensory detail, and in-character dialogue."
        val experience = "After 识之律者's in-character remark that it would choose to leave if it " +
            "ever became unwilling, 安桐 voiced the fear that he still cannot keep 识之律者."

        assertTrue(DreamLanguageGuard.isMixedLanguageOutput(claim))
        assertTrue(DreamLanguageGuard.isMixedLanguageOutput(experience))
    }

    @Test
    fun `pure Chinese and pure English both pass`() {
        assertFalse(
            DreamLanguageGuard.isMixedLanguageOutput(
                "识之律者会用第一人称叙述回应角色的提问，语气不变，并把界限说清楚。",
            ),
        )
        assertFalse(
            DreamLanguageGuard.isMixedLanguageOutput(
                "The assistant consistently replies with immersive first-person narration and sensory detail.",
            ),
        )
        assertFalse(DreamLanguageGuard.isMixedLanguageOutput(""))
    }

    @Test
    fun `Chinese sentence with short technical terms is not a mixed frame`() {
        assertFalse(
            DreamLanguageGuard.isMixedLanguageOutput(
                "思考链在 DeepSeek V4 Flash 上偏长，max_tokens 留空时不再被钉死到 4096。",
            ),
        )
    }

    @Test
    fun `Chinese dominant input demands fully Chinese output even without any Han in the claim`() {
        // 用户拍板（2026-10-17）：中文会话必须全中文输出 —— 纯英文摘要也要拦。
        val chinesePayload = "体验内容：".repeat(80) + "用户与助手讨论了一个长期项目。" .repeat(20)
        assertTrue(DreamLanguageGuard.isChineseDominantInput(chinesePayload))

        val pureEnglishClaim = "The assistant consistently provides immersive narration and keeps " +
            "the negotiated boundaries intact across the whole conversation."
        assertTrue(DreamLanguageGuard.isDisallowedOutput(pureEnglishClaim, chineseDominantInput = true))
        // 同一条产出，在非中文输入下按"混排"判据 → 纯英文放行。
        assertFalse(DreamLanguageGuard.isDisallowedOutput(pureEnglishClaim, chineseDominantInput = false))
        // 全中文产出永远放行。
        assertFalse(
            DreamLanguageGuard.isDisallowedOutput(
                "助手一直用第一人称叙述，并始终守住双方约定好的边界。",
                chineseDominantInput = true,
            ),
        )
        // 中文句里几个专名/标识符（拉丁 ≤ 40 字符）仍放行。
        assertFalse(
            DreamLanguageGuard.isDisallowedOutput(
                "思考链在 DeepSeek V4 Flash 上偏长，max_tokens 留空即可。",
                chineseDominantInput = true,
            ),
        )
    }

    @Test
    fun `English input is never judged Chinese dominant`() {
        assertFalse(DreamLanguageGuard.isChineseDominantInput("The user and assistant discussed a project."))
    }
}
