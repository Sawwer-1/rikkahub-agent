package me.rerere.rikkahub.memory.dreaming.synthesis

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Test

class DreamProviderOutputTokenBudgetTest {
    @Test
    fun `OpenCode DeepSeek V4 reasoning gets expanded Dream budget and timeout`() {
        val provider = ProviderSetting.OpenAI(baseUrl = "https://opencode.ai/zen/go/v1")
        val model = Model(
            modelId = "deepseek-v4-flash",
            abilities = listOf(ModelAbility.REASONING),
        )

        assertEquals(81_920, dreamProviderOutputTokenBudget(provider, model, 4_096))
        assertEquals(12L * 60_000L, dreamProviderTimeoutMs(provider, model))
    }

    @Test
    fun `dotted DeepSeek V4 spelling also gets the expanded Dream budget and timeout`() {
        // 2026-10-17 回归：判据此前写成 `"deepseek-v4-" in modelId.lowercase()`（只认连字符），
        // 于是 `deepseek-v4.1-flash` 这种点号写法漏判 → 拿不到扩容预算与超时，
        // 小预算被隐藏思考吃光、长任务提前超时。判据已统一到 isDeepSeekV4Family。
        val provider = ProviderSetting.OpenAI(baseUrl = "https://opencode.ai/zen/go/v1")
        val model = Model(
            modelId = "deepseek-v4.1-flash",
            abilities = listOf(ModelAbility.REASONING),
        )

        assertEquals(81_920, dreamProviderOutputTokenBudget(provider, model, 4_096))
        assertEquals(12L * 60_000L, dreamProviderTimeoutMs(provider, model))
    }

    @Test
    fun `other OpenCode reasoning models retain caller baseline`() {
        val provider = ProviderSetting.OpenAI(baseUrl = "https://opencode.ai/zen/go/v1")
        val model = Model(
            modelId = "other-reasoning-model",
            abilities = listOf(ModelAbility.REASONING),
        )

        assertEquals(4_096, dreamProviderOutputTokenBudget(provider, model, 4_096))
        assertEquals(2L * 60_000L, dreamProviderTimeoutMs(provider, model))
    }

    @Test
    fun `DeepSeek V4 on other providers retains caller baseline`() {
        val provider = ProviderSetting.OpenAI(baseUrl = "https://api.deepseek.com/v1")
        val model = Model(
            modelId = "deepseek-v4-flash",
            abilities = listOf(ModelAbility.REASONING),
        )

        assertEquals(4_096, dreamProviderOutputTokenBudget(provider, model, 4_096))
        assertEquals(2L * 60_000L, dreamProviderTimeoutMs(provider, model))
    }

    @Test
    fun `non reasoning DeepSeek V4 retains caller baseline`() {
        val provider = ProviderSetting.OpenAI(baseUrl = "https://opencode.ai/zen/go/v1")
        val model = Model(modelId = "deepseek-v4-flash")

        assertEquals(4_096, dreamProviderOutputTokenBudget(provider, model, 4_096))
        assertEquals(2L * 60_000L, dreamProviderTimeoutMs(provider, model))
    }
}
