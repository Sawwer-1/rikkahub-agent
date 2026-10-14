package me.rerere.rikkahub.ui.components.ai

/**
 * 压缩弹窗的输入校验策略。
 *
 * 可见的压缩弹窗是 [RollingCompressDialog]（jude 移植：带「自动滚动摘要」开关 + 保存 / 确认），
 * 本文件原有的 `CompressContextDialog` 已被它完全取代且没有任何调用点，2026-10-14 删除
 * （自检《全链路》§7 工单收尾：避免后续再有人去修一个不生效的弹窗）。
 *
 * 下面这条解析策略仍被单测 `CompressContextInputPolicyTest` 覆盖，故保留。
 */
internal data class CompressContextInput(
    val targetTokens: Int,
    val keepRecentMessages: Int,
)

internal fun parseCompressContextInput(
    targetTokens: String,
    keepRecentMessages: String,
): CompressContextInput? {
    val parsedTargetTokens = targetTokens.trim().toIntOrNull()
        ?.takeIf { it in 100..32_000 }
        ?: return null
    val parsedKeepRecentMessages = keepRecentMessages.trim().toIntOrNull()
        ?.takeIf { it >= 0 }
        ?: return null
    return CompressContextInput(
        targetTokens = parsedTargetTokens,
        keepRecentMessages = parsedKeepRecentMessages,
    )
}
