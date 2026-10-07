package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Brain01
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.GroupChatConfig

/**
 * Group chat (multi-member) config entry, rendered next to the conversation system prompt
 * button above the input area. The button opens a dialog to toggle group mode for THIS
 * conversation and pick member assistants; the config itself lives in Settings/DataStore
 * keyed by conversation id (zero Room migration, handover §三.B).
 */
@Composable
fun GroupChatConfigButton(
    conversationId: kotlin.uuid.Uuid,
    conversationAssistantId: kotlin.uuid.Uuid,
    assistants: List<Assistant>,
    config: GroupChatConfig?,
    onConfigChange: (GroupChatConfig?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showEditor by remember { mutableStateOf(false) }
    TextButton(
        onClick = { showEditor = true },
        modifier = modifier,
    ) {
        Icon(
            HugeIcons.Brain01,
            contentDescription = null,
        )
        Text(
            text = if (config != null) {
                "群聊 · ${config.memberAssistantIds.size} 名成员"
            } else {
                "开启群聊"
            },
            style = MaterialTheme.typography.labelLarge,
        )
    }
    if (showEditor) {
        val candidates = assistants.filter { it.id != conversationAssistantId }
        val selected = remember(config) {
            config?.memberAssistantIds?.toSet().orEmpty()
        }
        var enabled by remember(config) { mutableStateOf(config != null) }
        var pendingSelection by remember(config) { mutableStateOf(selected) }
        AlertDialog(
            onDismissRequest = { showEditor = false },
            title = { Text("群聊模式") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "开启后，本会话由编排器决定哪些成员回复；" +
                            "每位成员以各自的人设独立作答。当前助手作为主持不再直接生成。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("在本会话启用群聊")
                        Switch(
                            checked = enabled,
                            onCheckedChange = { enabled = it },
                        )
                    }
                    if (enabled) {
                        if (candidates.isEmpty()) {
                            Text(
                                "没有其他助手可邀请，先在助手页创建成员。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text("选择成员：", style = MaterialTheme.typography.labelLarge)
                            candidates.forEach { candidate ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = candidate.id in pendingSelection,
                                        onCheckedChange = { checked ->
                                            pendingSelection = if (checked) {
                                                pendingSelection + candidate.id
                                            } else {
                                                pendingSelection - candidate.id
                                            }
                                        },
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(candidate.name.ifBlank { "未命名助手" })
                                        Text(
                                            text = candidate.systemPrompt.take(60),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onConfigChange(
                            if (enabled && pendingSelection.isNotEmpty()) {
                                GroupChatConfig(
                                    conversationId = conversationId,
                                    memberAssistantIds = candidates
                                        .map { it.id }
                                        .filter { it in pendingSelection },
                                )
                            } else {
                                null
                            },
                        )
                        showEditor = false
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showEditor = false }) { Text("取消") }
            },
        )
    }
}
