package me.rerere.rikkahub.workflow.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.RelativeTimeStrings
import me.rerere.rikkahub.utils.formatRelativeAgo
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.model.WorkflowRunStatus
import me.rerere.rikkahub.workflow.repository.WorkflowRepository.Loaded
import org.koin.androidx.compose.koinViewModel

@Composable
fun WorkflowsScreen(vm: WorkflowsViewModel = koinViewModel()) {
    val nav = LocalNavController.current
    val workflows by vm.workflows.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showHowItWorks by remember { mutableStateOf(false) }

    if (showHowItWorks) {
        AlertDialog(
            onDismissRequest = { showHowItWorks = false },
            title = { Text(stringResource(R.string.setting_page_workflows_how_it_works_dialog_title)) },
            text = { Text(stringResource(R.string.setting_page_workflows_how_it_works_dialog_body)) },
            confirmButton = {
                TextButton(onClick = { showHowItWorks = false }) {
                    Text(stringResource(R.string.setting_page_workflows_how_it_works_dialog_dismiss))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_workflows)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (workflows.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.setting_page_workflows_empty),
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(workflows, key = { it.entity.id }) { loaded ->
                    WorkflowRow(
                        loaded = loaded,
                        onToggle = { enabled -> vm.setEnabled(loaded.entity.id, enabled) },
                        onTap = { nav.navigate(Screen.WorkflowDetail(loaded.entity.id)) },
                    )
                }
                item {
                    TextButton(
                        onClick = { showHowItWorks = true },
                        modifier = Modifier.padding(8.dp),
                    ) {
                        Text(stringResource(R.string.setting_page_workflows_how_it_works))
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkflowRow(
    loaded: Loaded,
    onToggle: (Boolean) -> Unit,
    onTap: () -> Unit,
) {
    val rel = relativeStrings()
    val context = LocalContext.current
    val nowMs by rememberTickingNowMs()
    val triggerSummary = remember(loaded.definition, context) {
        oneLineTriggerSummary(loaded.definition, context)
    }
    val statusLine: String = when {
        loaded.entity.lastRunAtMs == null -> stringResource(R.string.setting_page_workflows_subtitle_never_run)
        else -> {
            val ago = formatRelativeAgo(loaded.entity.lastRunAtMs, nowMs, rel)
            when (loaded.entity.lastRunStatus) {
                WorkflowRunStatus.SUCCESS.name ->
                    stringResource(R.string.setting_page_workflows_subtitle_ran_success, ago)
                WorkflowRunStatus.FAILED.name ->
                    stringResource(R.string.setting_page_workflows_subtitle_ran_failed, ago)
                else ->
                    stringResource(R.string.setting_page_workflows_subtitle_ran_skipped, ago)
            }
        }
    }

    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTap() }
            .padding(horizontal = 8.dp),
        headlineContent = { Text(loaded.entity.name) },
        supportingContent = {
            Text(
                text = "${stringResource(R.string.setting_page_workflows_subtitle_when, triggerSummary)}\n$statusLine",
                maxLines = 3,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingContent = {
            Switch(checked = loaded.entity.enabled, onCheckedChange = onToggle)
        },
    )
    HorizontalDivider()
}

internal fun oneLineTriggerSummary(def: WorkflowDefinition, context: Context): String = when (val t = def.trigger) {
    is TriggerSpec.TimeCron ->
        if (!t.timeOfDay.isNullOrBlank()) context.getString(R.string.wf_trigger_every_time, t.timeOfDay)
        else context.getString(R.string.wf_trigger_schedule)
    is TriggerSpec.WifiConnected ->
        if (t.ssid.isNullOrBlank()) context.getString(R.string.wf_trigger_wifi_connects)
        else context.getString(R.string.wf_trigger_wifi_connects_to, t.ssid)
    is TriggerSpec.WifiDisconnected ->
        if (t.ssid.isNullOrBlank()) context.getString(R.string.wf_trigger_wifi_disconnects)
        else context.getString(R.string.wf_trigger_wifi_disconnects_from, t.ssid)
    is TriggerSpec.BluetoothDeviceConnected -> context.getString(R.string.wf_trigger_bluetooth_connects)
    is TriggerSpec.BluetoothDeviceDisconnected -> context.getString(R.string.wf_trigger_bluetooth_disconnects)
    is TriggerSpec.HeadphonesPlugged -> context.getString(R.string.wf_trigger_headphones_plugged)
    is TriggerSpec.HeadphonesUnplugged -> context.getString(R.string.wf_trigger_headphones_unplugged)
    is TriggerSpec.PowerConnected -> context.getString(R.string.wf_trigger_power_connected)
    is TriggerSpec.PowerDisconnected -> context.getString(R.string.wf_trigger_power_disconnected)
    is TriggerSpec.BatteryBelow -> context.getString(R.string.wf_trigger_battery_below, t.thresholdPercent)
    is TriggerSpec.BatteryAbove -> context.getString(R.string.wf_trigger_battery_above, t.thresholdPercent)
    is TriggerSpec.GeofenceEnter ->
        if (t.label.isNullOrBlank()) context.getString(R.string.wf_trigger_geofence_enter_place)
        else context.getString(R.string.wf_trigger_geofence_enter_label, t.label)
    is TriggerSpec.GeofenceExit ->
        if (t.label.isNullOrBlank()) context.getString(R.string.wf_trigger_geofence_exit_place)
        else context.getString(R.string.wf_trigger_geofence_exit_label, t.label)
    is TriggerSpec.AppLaunched -> context.getString(R.string.wf_trigger_app_launched, t.packageName)
    is TriggerSpec.AppClosed -> context.getString(R.string.wf_trigger_app_closed, t.packageName)
    is TriggerSpec.NotificationReceived ->
        if (t.packageName.isNullOrBlank()) context.getString(R.string.wf_trigger_notification)
        else context.getString(R.string.wf_trigger_notification_from, t.packageName)
    is TriggerSpec.BootCompleted -> context.getString(R.string.wf_trigger_boot_completed)
    is TriggerSpec.ScreenOn -> context.getString(R.string.wf_trigger_screen_on)
    is TriggerSpec.ScreenOff -> context.getString(R.string.wf_trigger_screen_off)
    is TriggerSpec.Manual -> context.getString(R.string.wf_trigger_manual)
}

@Composable
internal fun relativeStrings(): RelativeTimeStrings = RelativeTimeStrings(
    justNow = stringResource(R.string.relative_time_just_now),
    secondsAgo = stringResource(R.string.relative_time_seconds_ago),
    minutesAgo = stringResource(R.string.relative_time_minutes_ago),
    hoursAgo = stringResource(R.string.relative_time_hours_ago),
    daysAgo = stringResource(R.string.relative_time_days_ago),
)

/**
 * A [State<Long>] of the current wall-clock millis, refreshed every 30s while the calling
 * Composable is in the composition. Used to keep "ran 2m ago" subtitles fresh without
 * fully re-deriving every recomposition. The audit found the old `remember { now }` pattern
 * silently froze the relative-time at the moment the row first composed.
 */
@Composable
internal fun rememberTickingNowMs(): State<Long> {
    val state = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            state.longValue = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    return state
}
