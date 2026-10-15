package me.rerere.rikkahub.ui.pages.security

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FileDownload
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.security.SecurityAuditEntry
import me.rerere.rikkahub.data.security.SecurityAuditRepository
import me.rerere.rikkahub.data.security.securityAuditCsv
import me.rerere.rikkahub.data.security.securityAuditCsvFileName
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

/** Category filter for the audit list. */
private enum class AuditCategoryFilter(val value: String?) {
    All(null),
    Tool("tool"),
    Approval("approval"),
    Automation("automation"),
}

/**
 * Status filter for the audit list.
 *
 * 数据里 `status` 取值有 success / failure / timeout / cancelled / rejected:<reason>，而
 * denied / blocked 实际落在 `action` 上（审批链的决策），所以这里同时按 status 与 action
 * 的前缀匹配，保证「拒绝 / 已阻止」筛得出来。
 */
private enum class AuditStatusFilter(val prefix: String?) {
    All(null),
    Success("success"),
    Failure("failure"),
    Denied("denied"),
    Blocked("blocked"),
}

/**
 * Security audit log viewer: dangerous tool executions, approval-chain decisions and
 * external automation triggers, backed by a rolling JSONL file (500 entries / 30 days).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityAuditPage(repo: SecurityAuditRepository = koinInject()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val entries by repo.entries.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var categoryFilter by remember { mutableStateOf(AuditCategoryFilter.All) }
    var statusFilter by remember { mutableStateOf(AuditStatusFilter.All) }
    var query by remember { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(repo) {
        repo.refresh()
    }

    // 分类（All 不过滤）/ 状态（按 status 或 action 前缀）/ 关键字（target、detail、status，
    // 忽略大小写、去首尾空格）三者取交集。
    val filtered = remember(entries, categoryFilter, statusFilter, query) {
        val category = categoryFilter.value
        val status = statusFilter.prefix
        val keyword = query.trim()
        entries.filter { entry ->
            (category == null || entry.category == category) &&
                (status == null || entry.status.startsWith(status) || entry.action.startsWith(status)) &&
                (
                    keyword.isEmpty() ||
                        entry.target.contains(keyword, ignoreCase = true) ||
                        entry.detail.contains(keyword, ignoreCase = true) ||
                        entry.status.contains(keyword, ignoreCase = true)
                    )
        }
    }

    // CSV 导出走 SAF（CreateDocument text/csv）写到用户选择的位置；写失败用 Toast 给出可见反馈。
    val exportFailureMessage = stringResource(R.string.security_audit_export_failed)
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) {
            val csv = securityAuditCsv(filtered)
            scope.launch {
                val written = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(csv.toByteArray())
                        } ?: error("no output stream for $uri")
                    }.isSuccess
                }
                if (!written) {
                    Toast.makeText(context, exportFailureMessage, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = {
                Text(
                    text = stringResource(R.string.security_audit_clear_confirm_title),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = { Text(stringResource(R.string.security_audit_clear_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    scope.launch { repo.clearAll() }
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.security_audit_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(
                        onClick = { exportLauncher.launch(securityAuditCsvFileName()) },
                        modifier = Modifier.testTag("security_audit_export_button"),
                    ) {
                        Icon(
                            imageVector = HugeIcons.FileDownload,
                            contentDescription = stringResource(R.string.security_audit_export),
                        )
                    }
                    IconButton(onClick = { showClearConfirm = true }) {
                        Icon(
                            imageVector = HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.security_audit_clear),
                        )
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.security_audit_search_hint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("security_audit_search_field"),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AuditCategoryFilter.entries.forEach { candidate ->
                    FilterChip(
                        selected = categoryFilter == candidate,
                        onClick = { categoryFilter = candidate },
                        label = {
                            Text(
                                text = when (candidate) {
                                    AuditCategoryFilter.All ->
                                        stringResource(R.string.security_audit_filter_all)
                                    AuditCategoryFilter.Tool ->
                                        stringResource(R.string.security_audit_filter_tool)
                                    AuditCategoryFilter.Approval ->
                                        stringResource(R.string.security_audit_filter_approval)
                                    AuditCategoryFilter.Automation ->
                                        stringResource(R.string.security_audit_filter_automation)
                                },
                            )
                        },
                    )
                }
            }
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AuditStatusFilter.entries.forEach { candidate ->
                    FilterChip(
                        selected = statusFilter == candidate,
                        onClick = { statusFilter = candidate },
                        label = {
                            Text(
                                text = when (candidate) {
                                    AuditStatusFilter.All ->
                                        stringResource(R.string.security_audit_status_all)
                                    AuditStatusFilter.Success ->
                                        stringResource(R.string.security_audit_status_success)
                                    AuditStatusFilter.Failure ->
                                        stringResource(R.string.security_audit_status_failure)
                                    AuditStatusFilter.Denied ->
                                        stringResource(R.string.security_audit_status_denied)
                                    AuditStatusFilter.Blocked ->
                                        stringResource(R.string.security_audit_status_blocked)
                                },
                            )
                        },
                        modifier = Modifier.testTag("security_audit_status_${candidate.name.lowercase()}"),
                    )
                }
            }
            if (filtered.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = innerPadding.calculateBottomPadding()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = HugeIcons.Shield01,
                        contentDescription = null,
                        modifier = Modifier.padding(bottom = 16.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        text = stringResource(R.string.security_audit_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = 8.dp,
                        bottom = innerPadding.calculateBottomPadding() + 16.dp,
                    ) + PaddingValues(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered, key = { it.id }) { entry ->
                        SecurityAuditCard(entry = entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun SecurityAuditCard(entry: SecurityAuditEntry) {
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
    val categoryColor = when (entry.category.lowercase()) {
        "tool" -> MaterialTheme.colorScheme.secondary
        "approval" -> MaterialTheme.colorScheme.tertiary
        "automation" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    val statusColor = when {
        entry.status.startsWith("success") -> MaterialTheme.colorScheme.primary
        entry.status.startsWith("failure") ||
            entry.status.startsWith("blocked") ||
            entry.status.startsWith("denied") ||
            entry.status.startsWith("rejected") -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = buildString {
                        append(entry.category.uppercase())
                        append("/")
                        append(entry.action.uppercase())
                        if (entry.sensitive) {
                            append(" · ")
                            append(stringResource(R.string.security_audit_sensitive).uppercase())
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = categoryColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = dateFormat.format(Date(entry.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.target.isNotBlank()) {
                Text(
                    text = entry.target,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (entry.detail.isNotBlank()) {
                Text(
                    text = entry.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (entry.status.isNotBlank()) {
                Text(
                    text = entry.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
