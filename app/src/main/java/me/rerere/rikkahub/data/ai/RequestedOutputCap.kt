package me.rerere.rikkahub.data.ai

/**
 * 决定「请求参数 `max_tokens` 到底带不带」的纯函数（2026-10-17 B01 / R11）。
 *
 * 背景（246 修的 P1）：`contextPreparation.effectiveMaxOutputTokens` 是上下文闸门的**保守预留**，
 * 调用方没给上限时它按 `DEFAULT_REQUESTED_OUTPUT_TOKENS(4096)` 兜底。把它直接发成 `max_tokens`，
 * 等于替用户隐式钉死 4096：
 *   1) 模型未声明输出上限的网关（`max_output_tokens: null`）会直接 400；
 *   2) 思考链与正文共用该上限，长思考链被截断（表现为「思考链降智」）。
 *
 * 规则：**只有用户真的配了上限（> 0）才携带**，且携带的是闸门夹取后的值（窗口吃紧时仍会下调）；
 * 未配置（null / 0 / 负数）一律留空，交由上游默认。
 *
 * 抽成纯函数是为了让这条分支能被单测钉住——它此前连续三版都记着「无单测覆盖」。
 */
internal fun resolveWireMaxTokens(
    requestedMaxTokens: Int?,
    gatedEffectiveMaxTokens: Int?,
): Int? = requestedMaxTokens
    ?.takeIf { it > 0 }
    ?.let { gatedEffectiveMaxTokens }
