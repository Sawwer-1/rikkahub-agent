package me.rerere.rikkahub.ui.pages.usage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.service.UsageReminderService
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import me.rerere.usagetracker.UsageTrackerPage
import org.koin.android.ext.android.inject

/**
 * Standalone host for UsageTrackerPage (ported from jude, batch 4).
 * Follows the HeartbeatSettingsActivity pattern: standalone ComponentActivity, no nav route.
 *
 * Note: the "allow assistant to read usage stats" toggle currently drives
 * [LocalToolOption.UsageStats] (AAA's existing usage-stats tool option). If the
 * dedicated usage stats tool option lands later, swap the option here.
 */
class UsageTrackerActivity : ComponentActivity() {
    private val settingsStore: SettingsStore by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RikkahubTheme {
                UsageTrackerPageHost(
                    settingsStore = settingsStore,
                    onBack = { finish() },
                )
            }
        }
    }
}

@Composable
private fun UsageTrackerPageHost(
    settingsStore: SettingsStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val assistant = settings.getCurrentAssistant()
    val scope = rememberCoroutineScope()
    UsageTrackerPage(
        onBack = onBack,
        usageStatsToolEnabled = assistant.localTools.contains(LocalToolOption.UsageStats),
        usageReminderConfig = settings.usageReminderConfig,
        usageReminderState = settings.usageReminderState,
        onUsageStatsToolEnabledChange = { enabled ->
            val localTools = if (enabled) {
                assistant.localTools + LocalToolOption.UsageStats
            } else {
                assistant.localTools - LocalToolOption.UsageStats
            }
            scope.launch {
                settingsStore.update { current ->
                    current.copy(
                        assistants = current.assistants.map {
                            if (it.id == assistant.id) {
                                it.copy(localTools = localTools.distinct())
                            } else {
                                it
                            }
                        }
                    )
                }
            }
        },
        onUsageReminderConfigChange = { config ->
            scope.launch {
                settingsStore.update { current ->
                    current.copy(usageReminderConfig = config)
                }
                UsageReminderService.sync(context, config)
            }
        },
        onUsageReminderStateChange = { state ->
            scope.launch {
                settingsStore.update { current ->
                    current.copy(usageReminderState = state)
                }
            }
        },
    )
}
