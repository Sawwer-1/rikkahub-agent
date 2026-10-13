package me.rerere.rikkahub.ui.pages.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.db.entity.PolicyNoteEntity

/**
 * 轻量学习（Part B）的策略审查段：列出提炼产生的 PENDING 策略，人工确认或拒绝。
 * 确认过的策略才会进入生成提示（[From role: 策略] 段）。
 *
 * 2026-10-13（报告 H / I）：本区块位于页面**非滚动**的外层 Column 中、pager 之上。此前它随
 * 待审策略条数无限增高：条数一多，整页超出一屏，区块自身没有任何滚动宿主（划不动），
 * 且把 pager——包括记忆列表及其编辑入口——挤压到实际不可达，用户表现为「进不去记忆编辑」。
 * 现改为：区块限高 + 列表自身可滚动，剩余高度全部留给 pager。
 */
@Composable
fun MemoryPolicyReviewSection(
    pendingPolicies: List<PolicyNoteEntity>,
    onReview: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pendingPolicies.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = "策略审查（${pendingPolicies.size} 条待确认）",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "从近期经历提炼的候选策略，确认后才会注入对话提示",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = POLICY_REVIEW_MAX_HEIGHT)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            pendingPolicies.forEach { policy ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(
                            text = policy.content,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${policy.supportCount} 条经历支撑",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { onReview(policy.id, false) }) {
                                Text("拒绝")
                            }
                            TextButton(onClick = { onReview(policy.id, true) }) {
                                Text("确认")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Cap chosen so the block never crowds out the tab pager on a phone viewport. */
private val POLICY_REVIEW_MAX_HEIGHT = 208.dp
