package me.rerere.rikkahub.ui.pages.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import me.rerere.rikkahub.data.db.entity.LearnedPolicyEntity

/**
 * 轻量学习（Part B）的策略审查段：列出提炼产生的 PENDING 策略，人工确认或拒绝。
 * 确认过的策略才会进入生成提示（[From role: 策略] 段）。
 */
@Composable
fun MemoryPolicyReviewSection(
    pendingPolicies: List<LearnedPolicyEntity>,
    onReview: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pendingPolicies.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = "策略审查",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "从近期经历提炼的候选策略，确认后才会注入对话提示",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        pendingPolicies.forEach { policy ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
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
