package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.data.ai.AgentSafetySettings
import me.rerere.rikkahub.data.ai.EmergencyStopCoordinator
import me.rerere.rikkahub.ui.components.nav.BackButton
import org.koin.compose.koinInject

@Composable
fun EmergencyStopPage() {
    val safety: AgentSafetySettings = koinInject()
    val emergencyStopCoordinator: EmergencyStopCoordinator = koinInject()
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val emergencyStop by safety.emergencyStopFlow.collectAsState(initial = false)
    val highRiskEnabled by safety.highRiskToolsEnabledFlow.collectAsState(initial = false)
    val remoteCallsEnabled by safety.remoteToolCallsEnabledFlow.collectAsState(initial = false)
    val backgroundAutomation by safety.backgroundAutomationEnabledFlow.collectAsState(initial = false)
    val allowLocked by safety.allowWhileDeviceLockedFlow.collectAsState(initial = false)
    val privilegedBridge by safety.privilegedBridgeEnabledFlow.collectAsState(initial = false)

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.ui3_emergency_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Emergency Stop Card ────────────────────────────────────────────────
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (emergencyStop)
                            MaterialTheme.colorScheme.errorContainer
                        else
                            MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = if (emergencyStop) HugeIcons.Alert01 else HugeIcons.Shield01,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = if (emergencyStop)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = if (emergencyStop) stringResource(R.string.ui3_emergency_active) else stringResource(R.string.ui3_emergency_running),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (emergencyStop)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (emergencyStop)
                                stringResource(R.string.ui3_emergency_active_desc)
                            else
                                stringResource(R.string.ui3_emergency_running_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    emergencyStopCoordinator.setStopped(!emergencyStop)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (emergencyStop)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.error,
                            ),
                            modifier = Modifier.fillMaxWidth(0.7f),
                        ) {
                            Text(
                                text = if (emergencyStop) stringResource(R.string.ui3_emergency_resume) else stringResource(R.string.ui3_emergency_stop_all),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            // ── Safety Toggles ────────────────────────────────────────────────────
            item {
                Text(
                    text = stringResource(R.string.ui3_emergency_safety_controls),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            item {
                SafetyToggle(
                    title = stringResource(R.string.ui3_emergency_high_risk_title),
                    description = stringResource(R.string.ui3_emergency_high_risk_desc),
                    checked = highRiskEnabled,
                    onCheckedChange = { scope.launch { safety.setHighRiskToolsEnabled(it) } },
                    enabled = !emergencyStop,
                )
            }

            item {
                SafetyToggle(
                    title = stringResource(R.string.ui3_emergency_remote_title),
                    description = stringResource(R.string.ui3_emergency_remote_desc),
                    checked = remoteCallsEnabled,
                    onCheckedChange = { scope.launch { safety.setRemoteToolCallsEnabled(it) } },
                    enabled = !emergencyStop,
                )
            }

            item {
                SafetyToggle(
                    title = stringResource(R.string.ui3_emergency_background_title),
                    description = stringResource(R.string.ui3_emergency_background_desc),
                    checked = backgroundAutomation,
                    onCheckedChange = { scope.launch { safety.setBackgroundAutomationEnabled(it) } },
                    enabled = !emergencyStop,
                )
            }

            item {
                SafetyToggle(
                    title = stringResource(R.string.ui3_emergency_locked_title),
                    description = stringResource(R.string.ui3_emergency_locked_desc),
                    checked = allowLocked,
                    onCheckedChange = { scope.launch { safety.setAllowWhileDeviceLocked(it) } },
                    enabled = !emergencyStop,
                )
            }

            item {
                SafetyToggle(
                    title = stringResource(R.string.ui3_emergency_bridge_title),
                    description = stringResource(R.string.ui3_emergency_bridge_desc),
                    checked = privilegedBridge,
                    onCheckedChange = { scope.launch { safety.setPrivilegedBridgeEnabled(it) } },
                    enabled = !emergencyStop,
                )
            }

            // ── Reset ──────────────────────────────────────────────────────────────
            item {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { scope.launch { safety.resetToDefaults() } },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !emergencyStop,
                ) {
                    Text(stringResource(R.string.ui3_emergency_reset))
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun SafetyToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        }
    }
}
