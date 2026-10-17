package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [resolveWireMaxTokens] 的单测（2026-10-17 B01 / R11）。
 *
 * 这条分支是 246 的 P1 修复核心：闸门的保守预留 4096 **不得**被当成用户意图发出去，
 * 否则模型未声明输出上限的网关会 400、长思考链会被截断。此前连续三版都记着「无单测覆盖」。
 */
class RequestedOutputCapTest {

    @Test
    fun `unset cap never reaches the wire`() {
        // 用户留空（null）——最常见的配置，必须不带 max_tokens。
        assertNull(resolveWireMaxTokens(requestedMaxTokens = null, gatedEffectiveMaxTokens = 4_096))
        // 0 / 负数同样视为未配置（历史数据里出现过 0）。
        assertNull(resolveWireMaxTokens(requestedMaxTokens = 0, gatedEffectiveMaxTokens = 4_096))
        assertNull(resolveWireMaxTokens(requestedMaxTokens = -1, gatedEffectiveMaxTokens = 4_096))
    }

    @Test
    fun `explicit cap is carried but still clamped by the gate`() {
        // 用户配了 8000，闸门因窗口吃紧夹到 4096 → 带夹取后的值（不能越过窗口）。
        assertEquals(
            4_096,
            resolveWireMaxTokens(requestedMaxTokens = 8_000, gatedEffectiveMaxTokens = 4_096),
        )
        // 窗口宽裕时带用户配置值。
        assertEquals(
            8_000,
            resolveWireMaxTokens(requestedMaxTokens = 8_000, gatedEffectiveMaxTokens = 8_000),
        )
    }

    @Test
    fun `explicit cap with unavailable gate value degrades to unset`() {
        // 闸门没算出可用值（null）时宁可不带，也不要凭空造一个上限。
        assertNull(resolveWireMaxTokens(requestedMaxTokens = 8_000, gatedEffectiveMaxTokens = null))
    }
}
