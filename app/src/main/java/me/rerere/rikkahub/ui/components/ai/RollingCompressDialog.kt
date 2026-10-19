package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.AutoCompressConfig
import me.rerere.rikkahub.ui.components.ui.RabbitLoadingIndicator

/**
 * Rolling-summary compression dialog (ported from jude's CompressContextDialog). Configures the
 * per-conversation AutoCompressConfig (toggle + target tokens + keep-recent + additional
 * instructions) and can trigger an immediate compression run.
 */
@Composable
fun RollingCompressDialog(
    autoCompressConfig: AutoCompressConfig?,
    /**
     * 已折叠的历史条数与摘要字数（工单 CB 2026-10-18）：滚动摘要在 UI 上是「无痕」的——
     * 原始消息保留、只是不再发给模型，所以用户没有任何可断言的观察点。这两个数字直接来自
     * 会话的压缩状态，是「到底折过没有」的唯一可见证据。
     */
    compressedNodeCount: Int = 0,
    compressedSummaryChars: Int = 0,
    onDismiss: () -> Unit,
    onSaveConfig: (
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int,
        autoCompress: Boolean,
        triggerExtraMessages: Int,
        triggerContextPercent: Int?
    ) -> Unit,
    onConfirm: (
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int,
        autoCompress: Boolean,
        triggerExtraMessages: Int,
        triggerContextPercent: Int?
    ) -> Job
) {
    var additionalPrompt by remember { mutableStateOf(autoCompressConfig?.additionalPrompt.orEmpty()) }
    var selectedTokens by remember { mutableIntStateOf(autoCompressConfig?.targetTokens ?: 2000) }
    var keepRecentMessages by remember {
        mutableIntStateOf(autoCompressConfig?.keepRecentMessages?.coerceAtLeast(1) ?: 32)
    }
    var autoCompress by remember { mutableStateOf(autoCompressConfig?.enabled ?: false) }
    // 折叠时机（2026-10-18 用户要求）：条数增量与上下文占用百分比都由用户自己定。
    var triggerExtraMessages by remember {
        mutableIntStateOf(autoCompressConfig?.triggerExtraMessages ?: 0)
    }
    var triggerContextPercent by remember {
        mutableStateOf(autoCompressConfig?.triggerContextPercent)
    }
    val tokenOptions = listOf(500, 1000, 2000, 4000)
    val keepRecentOptions = listOf(1, 16, 32, 64)
    val triggerExtraOptions = listOf(0, 16, 32, 64)
    val triggerPercentOptions = listOf<Int?>(null, 50, 70, 85)
    var currentJob by remember { mutableStateOf<Job?>(null) }
    val isLoading = currentJob?.isActive == true
    val currentAutoCompressConfig = AutoCompressConfig(
        enabled = autoCompress,
        additionalPrompt = additionalPrompt,
        targetTokens = selectedTokens,
        keepRecentMessages = keepRecentMessages.coerceAtLeast(1),
        triggerExtraMessages = triggerExtraMessages,
        triggerContextPercent = triggerContextPercent,
    )
    // 只在「有未保存修改」时显示「保存」（工单 CB 2026-10-19 P3）。
    // 旧判据含 `autoCompress ||`：开关一开就永远显示 → 取消/保存/确认三键并存，
    // 用户点「保存」后按钮位置变化，第二次点击落到别的键上（P2「点击不稳定」）。
    val showSaveConfigButton = currentAutoCompressConfig != (autoCompressConfig ?: AutoCompressConfig())

    // Monitor job completion
    LaunchedEffect(currentJob) {
        currentJob?.join()
        if (currentJob?.isCompleted == true && currentJob?.isCancelled == false) {
            onDismiss()
        }
        currentJob = null
    }

    AlertDialog(
        // 2026-10-15 自检 §三：四个 `compress_dialog_*` 在无障碍树里读不到，而同一 App 主窗口的
        // `chat_input` / `chat_send_button` 能读到 —— 因为 `testTagsAsResourceId = true` 只挂在
        // RouteActivity 的根（RouteActivity.kt:429），而 AlertDialog 渲染在**独立窗口**里，其
        // ComposeView 根上没有这个 flag。这里在弹窗自己的根上再挂一次（并在每个控件上各挂一次，
        // 兼容"只读节点自身 config"的实现），tag 才会落成无障碍 resource-id。
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        onDismissRequest = {
            if (!isLoading) {
                onDismiss()
            }
        },
        title = {
            Text(stringResource(R.string.chat_page_compress_context_title))
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (isLoading) {
                    // Loading state
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RabbitLoadingIndicator(
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.chat_page_compressing))
                    }
                } else {
                    Text(stringResource(R.string.chat_page_compress_context_desc))

                    // 压缩状态（工单 CB 2026-10-18）：UI 无痕 ≠ 没生效，但此前连一个可看的数字
                    // 都没有，用户只能靠请求侧 token 猜。这里直接报「折了几条 / 摘要多长」。
                    if (compressedNodeCount > 0) {
                        Text(
                            text = stringResource(
                                R.string.compress_dialog_status_folded,
                                compressedNodeCount,
                                compressedSummaryChars,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .testTag("compress_dialog_status")
                                .semantics { testTagsAsResourceId = true },
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.compress_dialog_status_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .testTag("compress_dialog_status")
                                .semantics { testTagsAsResourceId = true },
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "自动滚动摘要",
                            style = MaterialTheme.typography.labelMedium
                        )
                        // testTag 会经 RouteActivity 的 testTagsAsResourceId 变成无障碍 resource-id，
                        // 让 read_window_tree / find_node 能定位（自检 2026-10-15 §12.4-1 反馈）。
                        Switch(
                            checked = autoCompress,
                            onCheckedChange = { autoCompress = it },
                            modifier = Modifier
                                .testTag("compress_dialog_auto_summary_switch")
                                .semantics { testTagsAsResourceId = true },
                        )
                    }

                    // Token size selector
                    Text(
                        text = stringResource(R.string.chat_page_compress_target_tokens),
                        style = MaterialTheme.typography.labelMedium
                    )
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        tokenOptions.forEachIndexed { index, tokens ->
                            SegmentedButton(
                                selected = selectedTokens == tokens,
                                onClick = { selectedTokens = tokens },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = tokenOptions.size
                                )
                            ) {
                                Text("$tokens")
                            }
                        }
                    }

                    // Keep recent messages selector
                    Text(
                        text = stringResource(R.string.chat_page_compress_keep_recent),
                        style = MaterialTheme.typography.labelMedium
                    )
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        keepRecentOptions.forEachIndexed { index, count ->
                            SegmentedButton(
                                selected = keepRecentMessages == count,
                                onClick = { keepRecentMessages = count },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = keepRecentOptions.size
                                )
                            ) {
                                Text("$count")
                            }
                        }
                    }

                    // 折叠时机（条数）——用户自主设定：保留几 + 再多几条就折
                    Text(
                        text = stringResource(R.string.compress_dialog_trigger_count),
                        style = MaterialTheme.typography.labelMedium
                    )
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        triggerExtraOptions.forEachIndexed { index, extra ->
                            SegmentedButton(
                                selected = triggerExtraMessages == extra,
                                onClick = { triggerExtraMessages = extra },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = triggerExtraOptions.size
                                )
                            ) {
                                Text(
                                    if (extra == 0) {
                                        stringResource(R.string.compress_dialog_trigger_count_auto)
                                    } else {
                                        stringResource(R.string.compress_dialog_trigger_count_extra, extra)
                                    }
                                )
                            }
                        }
                    }

                    // 折叠时机（上下文占用）
                    Text(
                        text = stringResource(R.string.compress_dialog_trigger_context),
                        style = MaterialTheme.typography.labelMedium
                    )
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        triggerPercentOptions.forEachIndexed { index, percent ->
                            SegmentedButton(
                                selected = triggerContextPercent == percent,
                                onClick = { triggerContextPercent = percent },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = triggerPercentOptions.size
                                )
                            ) {
                                Text(
                                    if (percent == null) {
                                        stringResource(R.string.compress_dialog_trigger_context_off)
                                    } else {
                                        "$percent%"
                                    }
                                )
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.compress_dialog_trigger_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Additional context input
                    OutlinedTextField(
                        value = additionalPrompt,
                        onValueChange = { additionalPrompt = it },
                        label = {
                            Text(stringResource(R.string.chat_page_compress_additional_prompt))
                        },
                        placeholder = {
                            Text(stringResource(R.string.chat_page_compress_additional_prompt_hint))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                    )

                    // Warning text
                    Text(
                        text = stringResource(R.string.chat_page_compress_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            if (isLoading) {
                TextButton(onClick = {
                    currentJob?.cancel()
                    currentJob = null
                }) {
                    Text(stringResource(R.string.cancel))
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showSaveConfigButton) {
                        TextButton(
                            onClick = {
                                onSaveConfig(
                                    additionalPrompt,
                                    selectedTokens,
                                    keepRecentMessages,
                                    autoCompress,
                                    triggerExtraMessages,
                                    triggerContextPercent,
                                )
                            },
                            // 该按钮此前不在无障碍树里（自检 §12.4-1 实测 find_node no-match），补 tag。
                            modifier = Modifier
                                .testTag("compress_dialog_save_button")
                                .semantics { testTagsAsResourceId = true },
                        ) {
                            Text(stringResource(R.string.chat_page_save))
                        }
                    }
                    TextButton(
                        onClick = {
                            currentJob = onConfirm(
                                additionalPrompt,
                                selectedTokens,
                                keepRecentMessages,
                                autoCompress,
                                triggerExtraMessages,
                                triggerContextPercent,
                            )
                        },
                        modifier = Modifier
                            .testTag("compress_dialog_confirm_button")
                            .semantics { testTagsAsResourceId = true },
                    ) {
                        Text(stringResource(R.string.confirm))
                    }
                }
            }
        },
        dismissButton = {
            if (!isLoading) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .testTag("compress_dialog_cancel_button")
                        .semantics { testTagsAsResourceId = true },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )
}
