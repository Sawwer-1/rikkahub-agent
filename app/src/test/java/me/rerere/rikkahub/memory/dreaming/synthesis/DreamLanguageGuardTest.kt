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
}
