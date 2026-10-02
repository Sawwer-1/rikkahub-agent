package me.rerere.rikkahub.ui.pages.setting.doctor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.AccessibilityServiceHandle
import me.rerere.rikkahub.data.ai.tools.local.NotificationListenerHandle
import me.rerere.rikkahub.data.ai.tools.local.PermissionHelper
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRunRepository
import me.rerere.rikkahub.data.telegram.TelegramBotPreferences
import me.rerere.rikkahub.diagnostics.RuntimeDiagnosticStatus
import me.rerere.rikkahub.diagnostics.RuntimeDiagnosticsProvider
import me.rerere.rikkahub.service.TelegramBotService
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import me.rerere.rikkahub.browser.BrowserPreferences
import me.rerere.rikkahub.browser.BrowserToolDefaults
import java.net.InetAddress
import java.io.File

/**
 * Each row that depends on a system capability (a permission, an OS-level service binding,
 * Termux being installed) is "tool-aware": if no enabled tool needs the capability, the
 * row drops to INFO with a "not required" subtitle so the screen doesn't drown the user
 * in WARN noise about features they don't use.
 *
 * The map below records which [LocalToolOption] groups depend on which capability. The
 * answer comes from the tool registration code in `LocalTools.kt` — when a new tool is
 * added that needs a capability, also add its option here.
 */
private object Capability {
    val Notifications: Set<LocalToolOption> = setOf(
        LocalToolOption.Notification,        // post_notification tool
        LocalToolOption.TelegramBot,         // FGS notification
        LocalToolOption.CronJobs,            // CronJobWorker FGS notification
        LocalToolOption.Workflows,           // WorkflowTimeCronWorker FGS notification
    )
    val FineLocation: Set<LocalToolOption> = setOf(
        LocalToolOption.WifiInfo,            // SSID/BSSID on Android 10+
        LocalToolOption.Workflows,           // geofence_enter / geofence_exit triggers
    )
    val ForegroundLocation: Set<LocalToolOption> = setOf(
        LocalToolOption.Location,
    )
    val NotificationListener: Set<LocalToolOption> = setOf(
        LocalToolOption.NotificationListener,
        LocalToolOption.Workflows,           // notification_received trigger
    )
    val Accessibility: Set<LocalToolOption> = setOf(
        LocalToolOption.ScreenAutomation,    // take_screenshot, swipe, click_at, scroll, gesture
    )
    val Termux: Set<LocalToolOption> = setOf(
        LocalToolOption.Termux,
        LocalToolOption.SpeechToText,        // transcribe_audio_file uses Termux + whisper.cpp
        LocalToolOption.Ssh,                 // ssh_exec calls into termux ssh
    )
    val BatteryWhitelist: Set<LocalToolOption> = setOf(
        LocalToolOption.TelegramBot,         // long-poll loop
        LocalToolOption.CronJobs,            // worker fires
        LocalToolOption.Workflows,           // trigger receivers + cron worker
    )
    val AllFiles: Set<LocalToolOption> = setOf(
        LocalToolOption.Files,               // file_read / file_write to arbitrary paths
    )
    val Browser: Set<LocalToolOption> = setOf(
        LocalToolOption.Browser,             // 17 browser tools (in-app WebView)
    )
    // Phase 25 — Phase 3 second cut.
    val SendSms: Set<LocalToolOption> = setOf(
        LocalToolOption.SmsSend,
    )
    val Nfc: Set<LocalToolOption> = setOf(
        LocalToolOption.Nfc,
    )
    // Permissions that previously had no Doctor check at all. Each is gated on the tool that
    // actually needs it, so a denied perm only WARNs when its feature is enabled (opt-in) and
    // stays INFO otherwise. Closes the "Doctor reported all-clear while overlay etc. were denied"
    // gap.
    val Overlay: Set<LocalToolOption> = setOf(
        LocalToolOption.ScreenAutomation,    // "agent is working" overlay during automation
    )
    val WriteSettings: Set<LocalToolOption> = setOf(
        LocalToolOption.Brightness,          // set_brightness writes Settings.System
    )
    val BluetoothConnect: Set<LocalToolOption> = setOf(
        LocalToolOption.Workflows,           // workflow Bluetooth triggers read paired-device state
    )
    val NearbyWifi: Set<LocalToolOption> = setOf(
        LocalToolOption.WifiInfo,            // WiFi scan/info on Android 13+
    )
    val BackgroundLocation: Set<LocalToolOption> = setOf(
        LocalToolOption.Workflows,           // geofence triggers fire while the app is closed
    )
}

/** Friendly name for the row's "needed by:" subtitle. */
private fun LocalToolOption.shortName(context: Context): String = when (this) {
    LocalToolOption.Location -> context.getString(R.string.doctor_tool_location)
    LocalToolOption.WifiInfo -> context.getString(R.string.doctor_tool_wifi_info)
    LocalToolOption.NotificationListener -> context.getString(R.string.doctor_tool_notification_listener)
    LocalToolOption.ScreenAutomation -> context.getString(R.string.doctor_tool_screen_automation)
    LocalToolOption.Termux -> context.getString(R.string.doctor_tool_termux)
    LocalToolOption.SpeechToText -> context.getString(R.string.doctor_tool_speech_to_text)
    LocalToolOption.Ssh -> context.getString(R.string.doctor_tool_ssh)
    LocalToolOption.TelegramBot -> context.getString(R.string.doctor_tool_telegram_bot)
    LocalToolOption.CronJobs -> context.getString(R.string.doctor_tool_cron_jobs)
    LocalToolOption.Workflows -> context.getString(R.string.doctor_tool_workflows)
    LocalToolOption.Notification -> context.getString(R.string.doctor_tool_notification)
    LocalToolOption.Files -> context.getString(R.string.doctor_tool_files)
    LocalToolOption.Browser -> context.getString(R.string.doctor_tool_browser)
    LocalToolOption.SmsSend -> context.getString(R.string.doctor_tool_sms_send)
    LocalToolOption.Wallpaper -> context.getString(R.string.doctor_tool_wallpaper)
    LocalToolOption.Keystore -> context.getString(R.string.doctor_tool_keystore)
    LocalToolOption.Nfc -> context.getString(R.string.doctor_tool_nfc)
    LocalToolOption.ExternalStorage -> context.getString(R.string.doctor_tool_external_storage)
    LocalToolOption.Archive -> context.getString(R.string.doctor_tool_archive_zip)
    else -> this::class.simpleName ?: "?"
}

/** Localised section title for a Doctor category; [DoctorCategory] itself carries no Context. */
val DoctorCategory.displayNameRes: Int
    get() = when (this) {
        DoctorCategory.Permissions -> R.string.doctor_category_permissions
        DoctorCategory.Services -> R.string.doctor_category_services
        DoctorCategory.AssistantInfo -> R.string.doctor_category_assistant
        DoctorCategory.Database -> R.string.doctor_category_database
        DoctorCategory.Network -> R.string.doctor_category_network
        DoctorCategory.Termux -> R.string.doctor_category_termux
        DoctorCategory.Maintenance -> R.string.doctor_category_maintenance
        DoctorCategory.Diagnostics -> R.string.doctor_category_diagnostics
    }

/**
 * Run every diagnostic check. Returns the flat list — the Doctor screen groups by
 * [DoctorCheck.category].
 *
 * Most checks are cheap (Settings.Secure reads, package manager queries, in-memory state)
 * but a few do I/O (DB integrity PRAGMA, DNS resolve). Run on Dispatchers.IO at the call
 * site; the function itself is suspending so individual probes can withTimeoutOrNull.
 *
 * Adding a new check: append to the appropriate `runXxxChecks` block. Each helper function
 * returns either a single check or a list. Keep checks short — one concern per row.
 */
class DoctorChecks(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val telegramPrefs: TelegramBotPreferences,
    private val workflowRepository: WorkflowRepository,
    private val scheduledJobRepository: ScheduledJobRepository,
    private val scheduledJobRunRepository: ScheduledJobRunRepository,
    private val conversationRepository: ConversationRepository,
    private val database: AppDatabase,
    // Pass 3: per-tool browser toggle store. Used by the browser write-tools-enabled INFO
    // row so the user can spot-check which side-effecting tools are currently switched on.
    // Optional + nullable so callers that don't construct this DoctorChecks via the DI
    // graph (a few legacy tests) keep compiling — the row is silently skipped when null.
    private val browserPreferences: BrowserPreferences? = null,
    // Phase 25 — SAF tree-grant store, backs the "granted directories" Doctor row.
    // Nullable + defaulted so legacy test paths that don't build the full DI graph compile.
    private val storageVolumeGrantStore: me.rerere.rikkahub.data.storage.StorageVolumeGrantStore? = null,
    // Surface the persisted LiteRT accelerator decision so the user can see whether their
    // local models actually engaged GPU/NPU or silently fell back to CPU.
    // Nullable + defaulted same as the others above for legacy test path compatibility.
    private val localRuntimePreferences: me.rerere.locallm.LocalRuntimePreferences? = null,
    private val runtimeDiagnosticsProvider: RuntimeDiagnosticsProvider? = null,
    private val workspaceRepository: me.rerere.rikkahub.data.repository.WorkspaceRepository? = null,
    private val capabilityGrantRepository: me.rerere.rikkahub.data.capability.CapabilityGrantRepository? = null,
    private val executionConsistencyDoctor: me.rerere.rikkahub.diagnostics.ExecutionConsistencyDoctor? = null,
    private val petDiagnostics: me.rerere.rikkahub.pet.PetDiagnostics? = null,
) {
    suspend fun runAll(): List<DoctorCheck> = withContext(Dispatchers.IO) {
        // Aggregate enabled tools across every assistant. A tool is "in use" if at least
        // one assistant has its LocalToolOption switched on. The Doctor uses this to
        // decide whether a missing capability is actually a problem worth flagging.
        val enabled: Set<LocalToolOption> = runCatching {
            settingsStore.settingsFlow.first().assistants.flatMap { it.localTools }.toSet()
        }.getOrDefault(emptySet())

        buildList {
            addAll(permissionChecks(enabled))
            addAll(serviceChecks(enabled))
            addAll(assistantChecks())
            addAll(databaseChecks(enabled))
            addAll(networkChecks())
            addAll(termuxChecks(enabled))
            addAll(browserChecks(enabled))
            addAll(maintenanceChecks())
            addAll(diagnosticsChecks(enabled))
            addAll(runtimeDiagnosticsChecks())
            addAll(linuxRuntimeStateChecks())
            addAll(executionConsistencyChecks())
            addAll(petChecks())
        }
    }

    private suspend fun petChecks(): List<DoctorCheck> {
        val diagnostics = petDiagnostics ?: return emptyList()
        val snapshot = runCatching { diagnostics.inspect() }.getOrElse {
            return listOf(
                DoctorCheck(
                    id = "pet.diagnostics.unavailable",
                    category = DoctorCategory.Diagnostics,
                    label = context.getString(R.string.doctor_pet_diagnostics_label),
                    detail = context.getString(R.string.doctor_pet_diagnostics_detail_unavailable),
                    severity = Severity.WARN,
                ),
            )
        }
        val inconsistent = snapshot.overCapacitySessions + snapshot.expiredPendingHandoffs
        return listOf(
            DoctorCheck(
                id = "pet.session_integrity",
                category = DoctorCategory.Database,
                label = context.getString(R.string.doctor_pet_session_integrity_label),
                detail = context.getString(
                    R.string.doctor_pet_session_integrity_detail,
                    snapshot.overCapacitySessions,
                    snapshot.expiredPendingHandoffs,
                ),
                severity = if (inconsistent == 0) Severity.OK else Severity.FAIL,
                fix = if (inconsistent > 0 || snapshot.pendingSummaries > 0) FixAction.AutoFix(
                    label = context.getString(R.string.doctor_pet_repair_action),
                    run = {
                        AutoFixResult(
                            true,
                            context.getString(
                                R.string.doctor_pet_repair_message,
                                diagnostics.repair(),
                            ),
                        )
                    },
                ) else null,
            ),
            DoctorCheck(
                id = "pet.assets",
                category = DoctorCategory.AssistantInfo,
                label = context.getString(R.string.doctor_pet_assets_label),
                detail = context.getString(
                    R.string.doctor_pet_assets_detail,
                    if (snapshot.globalSelectionConfigured) {
                        context.getString(R.string.doctor_pet_assets_global_configured)
                    } else {
                        context.getString(R.string.doctor_pet_assets_global_needs_selection)
                    },
                    snapshot.runtimeDiagnostics.profileId
                        ?: context.getString(R.string.doctor_value_not_loaded),
                    snapshot.runtimeDiagnostics.rendererType
                        ?: context.getString(R.string.doctor_value_not_loaded),
                    snapshot.runtimeDiagnostics.supportedActionCount,
                    snapshot.runtimeDiagnostics.displayedActionId
                        ?: context.getString(R.string.doctor_value_none),
                    snapshot.runtimeDiagnostics.activeOneShotActionId
                        ?: context.getString(R.string.doctor_value_none),
                    snapshot.runtimeDiagnostics.resourceValid
                        ?: context.getString(R.string.doctor_value_unknown),
                    snapshot.missingPackages.size,
                    snapshot.truncatedPersonas.size,
                    snapshot.pendingSummaries,
                    snapshot.rejectedActionCount,
                    snapshot.actionTraceCount,
                ),
                severity = if (snapshot.globalSelectionConfigured && snapshot.missingPackages.isEmpty() && snapshot.runtimeDiagnostics.resourceValid != false) Severity.OK else Severity.WARN,
            ),
        )
    }

    private suspend fun executionConsistencyChecks(): List<DoctorCheck> {
        val doctor = executionConsistencyDoctor ?: return emptyList()
        val snapshot = runCatching { doctor.inspect() }.getOrElse { error ->
            return listOf(DoctorCheck(
                id = "execution.consistency.unavailable",
                category = DoctorCategory.Diagnostics,
                label = context.getString(R.string.doctor_execution_diagnostics_label),
                detail = context.getString(
                    R.string.doctor_execution_diagnostics_detail,
                    error::class.simpleName,
                ),
                severity = Severity.FAIL,
            ))
        }
        return listOf(
            DoctorCheck(
                id = "execution.inflight_contract",
                category = DoctorCategory.Database,
                label = context.getString(R.string.doctor_execution_inflight_label),
                detail = context.getString(
                    R.string.doctor_execution_inflight_detail,
                    snapshot.terminalReturnedAsInFlightCount,
                ),
                severity = if (snapshot.terminalReturnedAsInFlightCount == 0) Severity.OK else Severity.FAIL,
            ),
            DoctorCheck(
                id = "execution.approval_projection",
                category = DoctorCategory.Database,
                label = context.getString(R.string.doctor_execution_approval_label),
                detail = context.getString(
                    R.string.doctor_execution_approval_detail,
                    snapshot.approvalProjectionMismatchCount,
                ),
                severity = if (snapshot.approvalProjectionMismatchCount == 0) Severity.OK else Severity.FAIL,
                fix = if (snapshot.approvalProjectionMismatchCount > 0) FixAction.AutoFix(
                    label = context.getString(R.string.doctor_execution_approval_action),
                    run = {
                        val result = doctor.rebuildApprovalProjection()
                        AutoFixResult(
                            ok = true,
                            message = context.getString(
                                R.string.doctor_execution_approval_message,
                                result.restored,
                                result.invalidated,
                                result.retained,
                            ),
                        )
                    },
                ) else null,
            ),
            DoctorCheck(
                id = "execution.runtime_handles",
                category = DoctorCategory.Services,
                label = context.getString(R.string.doctor_execution_runtime_handles_label),
                detail = context.getString(
                    R.string.doctor_execution_runtime_handles_detail,
                    snapshot.missingRuntimeHandleCount,
                    snapshot.workspaceManagerState.name.lowercase(),
                ),
                severity = when {
                    snapshot.missingRuntimeHandleCount > 0 -> Severity.FAIL
                    snapshot.workspaceManagerNotReadyCount > 0 -> Severity.WARN
                    else -> Severity.OK
                },
                fix = if (snapshot.missingRuntimeHandleCount > 0 ||
                    snapshot.workspaceManagerNotReadyCount > 0
                ) FixAction.AutoFix(
                    label = context.getString(R.string.doctor_execution_probe_action),
                    run = {
                        val updates = doctor.reprobe()
                        AutoFixResult(
                            ok = updates.none { it.conflict },
                            message = context.getString(
                                R.string.doctor_execution_probe_message,
                                updates.size,
                            ),
                        )
                    },
                ) else null,
            ),
            DoctorCheck(
                id = "execution.probe_freshness",
                category = DoctorCategory.Services,
                label = context.getString(R.string.doctor_execution_probe_freshness_label),
                detail = context.getString(
                    R.string.doctor_execution_probe_freshness_detail,
                    snapshot.staleProbeCount,
                    snapshot.casConflictCount,
                    snapshot.staleProbeDiscardCount,
                ),
                severity = if (snapshot.staleProbeCount == 0) Severity.OK else Severity.WARN,
                fix = if (snapshot.staleProbeCount > 0) FixAction.AutoFix(
                    label = context.getString(R.string.doctor_execution_probe_again_action),
                    run = {
                        val updates = doctor.reprobe()
                        AutoFixResult(
                            ok = updates.none { it.conflict },
                            message = context.getString(
                                R.string.doctor_execution_probe_again_message,
                                updates.size,
                            ),
                        )
                    },
                ) else null,
            ),
            DoctorCheck(
                id = "execution.tracking_health",
                category = DoctorCategory.Diagnostics,
                label = context.getString(R.string.doctor_execution_tracking_label),
                detail = if (snapshot.trackingDegraded) {
                    context.getString(
                        R.string.doctor_execution_tracking_detail_degraded,
                        snapshot.trackingReasonCode ?: "unknown_reason",
                    )
                } else {
                    context.getString(R.string.doctor_execution_tracking_detail_healthy)
                },
                severity = if (snapshot.trackingDegraded) Severity.FAIL else Severity.OK,
            ),
            DoctorCheck(
                id = "execution.parent_child",
                category = DoctorCategory.Database,
                label = context.getString(R.string.doctor_execution_parent_child_label),
                detail = context.getString(
                    R.string.doctor_execution_parent_child_detail,
                    snapshot.activeChildUnderTerminalParentCount,
                    snapshot.allowedDetachedChildCount,
                ),
                severity = if (snapshot.activeChildUnderTerminalParentCount == 0) Severity.OK else Severity.FAIL,
            ),
            DoctorCheck(
                id = "execution.presentation_redaction",
                category = DoctorCategory.Diagnostics,
                label = context.getString(R.string.doctor_execution_redaction_label),
                detail = context.getString(
                    R.string.doctor_execution_redaction_detail,
                    snapshot.redactionViolationCount,
                ),
                severity = if (snapshot.redactionViolationCount == 0) Severity.OK else Severity.FAIL,
            ),
            DoctorCheck(
                id = "execution.retention",
                category = DoctorCategory.Maintenance,
                label = context.getString(R.string.doctor_execution_retention_label),
                detail = context.getString(R.string.doctor_execution_retention_detail),
                severity = Severity.INFO,
                fix = FixAction.AutoFix(
                    label = context.getString(R.string.doctor_execution_retention_action),
                    run = {
                        doctor.runRetentionCleanup()
                        AutoFixResult(true, context.getString(R.string.doctor_execution_retention_message))
                    },
                ),
            ),
        )
    }

    private suspend fun linuxRuntimeStateChecks(): List<DoctorCheck> = buildList {
        val exchange = me.rerere.rikkahub.data.files.SharedExchangeDirectory.ensure(context)
        add(DoctorCheck(
            id = "linux.shared_storage",
            category = DoctorCategory.Permissions,
            label = context.getString(R.string.doctor_linux_storage_label),
            detail = when (exchange) {
                is me.rerere.rikkahub.data.files.SharedExchangeDirectory.Status.Ready ->
                    context.getString(
                        R.string.doctor_linux_storage_detail_ready,
                        exchange.directory.absolutePath,
                    )
                is me.rerere.rikkahub.data.files.SharedExchangeDirectory.Status.PermissionRequired ->
                    context.getString(
                        R.string.doctor_linux_storage_detail_permission_required,
                        exchange.directory.absolutePath,
                    )
                is me.rerere.rikkahub.data.files.SharedExchangeDirectory.Status.Unavailable ->
                    context.getString(
                        R.string.doctor_linux_storage_detail_unavailable,
                        exchange.directory.absolutePath,
                    )
            },
            severity = when (exchange) {
                is me.rerere.rikkahub.data.files.SharedExchangeDirectory.Status.Ready -> Severity.OK
                is me.rerere.rikkahub.data.files.SharedExchangeDirectory.Status.PermissionRequired -> Severity.WARN
                is me.rerere.rikkahub.data.files.SharedExchangeDirectory.Status.Unavailable -> Severity.FAIL
            },
        ))
        workspaceRepository?.let { repository ->
            val workspaces = runCatching { repository.getAll() }.getOrDefault(emptyList())
            val shared = workspaces.count { it.storageMode == me.rerere.workspace.WorkspaceStorageMode.SHARED.name }
            add(DoctorCheck(
                id = "linux.workspace_profiles",
                category = DoctorCategory.Services,
                label = context.getString(R.string.doctor_linux_workspace_profiles_label),
                detail = context.getString(
                    R.string.doctor_linux_workspace_profiles_detail,
                    workspaces.size,
                    shared,
                    workspaces.size - shared,
                ),
                severity = if (workspaces.isEmpty()) Severity.INFO else Severity.OK,
            ))
        }
        capabilityGrantRepository?.let { repository ->
            val grants = repository.current().count {
                it.subjectType == me.rerere.rikkahub.data.capability.SubjectType.LOCAL_SECOND_USER &&
                    (it.capability.value.startsWith("linux.") || it.capability.value.startsWith("phone.shared."))
            }
            add(DoctorCheck(
                id = "linux.second_user_grants",
                category = DoctorCategory.AssistantInfo,
                label = context.getString(R.string.doctor_linux_grants_label),
                detail = context.getString(R.string.doctor_linux_grants_detail, grants),
                severity = if (grants == 0) Severity.INFO else Severity.OK,
            ))
        }
        val artifactRoot = java.io.File(
            context.filesDir,
            me.rerere.rikkahub.data.files.FileFolders.TOOL_OUTPUTS,
        )
        val bytes = artifactRoot.walkTopDown().filter(java.io.File::isFile).sumOf(java.io.File::length)
        add(DoctorCheck(
            id = "linux.artifacts",
            category = DoctorCategory.Maintenance,
            label = context.getString(R.string.doctor_linux_artifacts_label),
            detail = context.getString(
                R.string.doctor_linux_artifacts_detail,
                bytes / (1024 * 1024),
            ),
            severity = if (bytes > 512L * 1024 * 1024) Severity.WARN else Severity.OK,
        ))
    }

    private suspend fun runtimeDiagnosticsChecks(): List<DoctorCheck> {
        val provider = runtimeDiagnosticsProvider ?: return emptyList()
        val snapshot = runCatching { provider.refresh(conversationId = null) }.getOrElse { error ->
            return listOf(
                DoctorCheck(
                    id = "runtime.provider",
                    category = DoctorCategory.Services,
                    label = context.getString(R.string.doctor_runtime_provider_label),
                    detail = error.message ?: error.javaClass.simpleName,
                    severity = Severity.FAIL,
                    fix = FixAction.OpenAppRoute(
                        context.getString(R.string.doctor_action_open_runtime_diagnostics),
                        AppRouteKey.SettingDiagnostics,
                    ),
                )
            )
        }
        val included = setOf(
            "shizuku_bridge",
            "user_service",
            "workspace_processes",
            "workspace_wake_lock",
            "agent_keyboard",
            "termux",
            "emergency_stop",
            "oem_background",
        )
        return snapshot.items.filter { it.id in included }.map { item ->
            DoctorCheck(
                id = "runtime.${item.id}",
                category = DoctorCategory.Services,
                label = item.title,
                detail = item.detail,
                severity = when (item.status) {
                    RuntimeDiagnosticStatus.READY -> Severity.OK
                    RuntimeDiagnosticStatus.SERVICE_OFFLINE -> Severity.FAIL
                    RuntimeDiagnosticStatus.IMPLEMENTED_BUT_NOT_AUTHORIZED -> Severity.WARN
                    RuntimeDiagnosticStatus.OEM_RESTRICTED -> Severity.WARN
                    RuntimeDiagnosticStatus.NOT_SUPPORTED -> Severity.INFO
                },
                fix = if (item.status == RuntimeDiagnosticStatus.READY) null else {
                    FixAction.OpenAppRoute(
                        context.getString(R.string.doctor_action_open_runtime_diagnostics),
                        AppRouteKey.SettingDiagnostics,
                    )
                },
            )
        }
    }

    /**
     * Render the "needed by:" subtitle for a tool-aware row. If the requirement is currently
     * unsatisfied, list the enabled tools that demand it so the user knows why they should
     * care. Returns null when no enabled tool needs the capability — callers down-grade
     * severity to INFO in that case.
     */
    private fun requirersOf(cap: Set<LocalToolOption>, enabled: Set<LocalToolOption>): List<LocalToolOption> =
        cap.filter { it in enabled }

    // ----- Permissions ----------------------------------------------------------------

    private fun locationPermissionChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> {
        val fineGranted = PermissionHelper.hasRuntime(
            context,
            listOf(Manifest.permission.ACCESS_FINE_LOCATION),
        )
        val coarseGranted = PermissionHelper.hasRuntime(
            context,
            listOf(Manifest.permission.ACCESS_COARSE_LOCATION),
        )
        val manager = context.getSystemService(LocationManager::class.java)
        val servicesProbe = runCatching {
            manager != null && LocationManagerCompat.isLocationEnabled(manager)
        }
        val providerExistsProbe = runCatching {
            manager != null && LocationManagerCompat.hasProvider(manager, LocationManager.GPS_PROVIDER)
        }
        val providerEnabledProbe = runCatching {
            manager != null && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }
        val states = resolveLocationDiagnosticStates(
            LocationDiagnosticSnapshot(
                fineGranted = fineGranted,
                coarseGranted = coarseGranted,
                locationServicesEnabled = servicesProbe.getOrDefault(false),
                locationServicesProbeRestricted = servicesProbe.isFailure,
                gpsProviderExists = providerExistsProbe.getOrDefault(false),
                gpsProviderEnabled = providerEnabledProbe.getOrDefault(false),
                providerProbeRestricted = providerExistsProbe.isFailure || providerEnabledProbe.isFailure,
            )
        )
        val required = requirersOf(Capability.ForegroundLocation, enabled).isNotEmpty()
        val permissionFix = FixAction.OpenAppRoute(
            context.getString(R.string.doctor_location_action_open_permissions),
            AppRouteKey.SettingPermissions,
        )
        val locationSettingsFix = FixAction.OpenIntent(
            context.getString(R.string.doctor_location_action_open_settings),
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
        )
        return listOf(
            locationDiagnosticRow(
                id = "location.permission",
                label = context.getString(R.string.doctor_location_permission_title),
                state = states.permission,
                required = required,
                fix = permissionFix,
            ),
            locationDiagnosticRow(
                id = "location.precise",
                label = context.getString(R.string.doctor_location_precise_title),
                state = states.preciseLocation,
                required = required,
                fix = permissionFix,
            ),
            locationDiagnosticRow(
                id = "location.services",
                label = context.getString(R.string.doctor_location_services_title),
                state = states.locationServices,
                required = required,
                fix = locationSettingsFix,
            ),
            locationDiagnosticRow(
                id = "location.gnss_provider",
                label = context.getString(R.string.doctor_location_gnss_provider_title),
                state = states.gnssProvider,
                required = required,
                fix = locationSettingsFix,
            ),
        )
    }

    private fun locationDiagnosticRow(
        id: String,
        label: String,
        state: LocationDiagnosticState,
        required: Boolean,
        fix: FixAction,
    ): DoctorCheck = DoctorCheck(
        id = id,
        category = DoctorCategory.Permissions,
        label = label,
        detail = locationDiagnosticDetail(state),
        severity = if (!required) {
            Severity.INFO
        } else {
            when (state) {
                LocationDiagnosticState.READY -> Severity.OK
                LocationDiagnosticState.APPROXIMATE_ONLY,
                LocationDiagnosticState.GPS_PROVIDER_DISABLED,
                LocationDiagnosticState.OEM_RESTRICTED,
                -> Severity.WARN
                LocationDiagnosticState.PERMISSION_MISSING,
                LocationDiagnosticState.LOCATION_DISABLED,
                LocationDiagnosticState.PROVIDER_UNAVAILABLE,
                -> Severity.FAIL
            }
        },
        fix = fix.takeUnless { state == LocationDiagnosticState.READY },
    )

    private fun locationDiagnosticDetail(state: LocationDiagnosticState): String = when (state) {
        LocationDiagnosticState.READY -> context.getString(R.string.doctor_location_detail_ready)
        LocationDiagnosticState.APPROXIMATE_ONLY ->
            context.getString(R.string.doctor_location_detail_approximate_only)
        LocationDiagnosticState.PERMISSION_MISSING ->
            context.getString(R.string.doctor_location_detail_permission_missing)
        LocationDiagnosticState.LOCATION_DISABLED ->
            context.getString(R.string.doctor_location_detail_location_disabled)
        LocationDiagnosticState.GPS_PROVIDER_DISABLED ->
            context.getString(R.string.doctor_location_detail_gps_provider_disabled)
        LocationDiagnosticState.PROVIDER_UNAVAILABLE ->
            context.getString(R.string.doctor_location_detail_provider_unavailable)
        LocationDiagnosticState.OEM_RESTRICTED ->
            context.getString(R.string.doctor_location_detail_oem_restricted)
    }

    private fun permissionChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> = buildList {
        addAll(locationPermissionChecks(enabled))
        add(
            capabilityRow(
                id = "perm.notifications",
                category = DoctorCategory.Permissions,
                label = context.getString(R.string.doctor_perm_notifications_label),
                cap = Capability.Notifications,
                enabled = enabled,
                granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    PermissionHelper.hasRuntime(context, listOf(Manifest.permission.POST_NOTIFICATIONS)),
                grantedDetail = context.getString(R.string.doctor_detail_granted),
                missingDetail = context.getString(R.string.doctor_perm_notifications_detail_missing),
                fix = FixAction.OpenAppRoute(
                    context.getString(R.string.doctor_action_open_app_permissions),
                    AppRouteKey.SettingPermissions,
                ),
            )
        )
        add(
            capabilityRow(
                id = "perm.location.precise_dependencies",
                category = DoctorCategory.Permissions,
                label = context.getString(R.string.doctor_perm_fine_location_label),
                cap = Capability.FineLocation,
                enabled = enabled,
                granted = PermissionHelper.hasRuntime(context, listOf(Manifest.permission.ACCESS_FINE_LOCATION)),
                grantedDetail = context.getString(R.string.doctor_perm_fine_location_detail_granted),
                missingDetail = context.getString(R.string.doctor_perm_fine_location_detail_missing),
                fix = FixAction.OpenAppRoute(
                    context.getString(R.string.doctor_action_open_app_permissions),
                    AppRouteKey.SettingPermissions,
                ),
            )
        )
        add(
            capabilityRow(
                id = "perm.battery_opt",
                category = DoctorCategory.Permissions,
                label = context.getString(R.string.doctor_perm_battery_label),
                cap = Capability.BatteryWhitelist,
                enabled = enabled,
                granted = PermissionHelper.ignoresBatteryOptimizations(context),
                grantedDetail = context.getString(R.string.doctor_perm_battery_detail_granted),
                missingDetail = context.getString(R.string.doctor_perm_battery_detail_missing),
                fix = FixAction.OpenIntent(
                    label = context.getString(R.string.doctor_action_request_whitelist),
                    intent = PermissionHelper.requestIgnoreBatteryOptimizationsIntent(context),
                ),
            )
        )
        add(
            capabilityRow(
                id = "perm.notification_listener",
                category = DoctorCategory.Permissions,
                label = context.getString(R.string.doctor_perm_notification_listener_label),
                cap = Capability.NotificationListener,
                enabled = enabled,
                granted = PermissionHelper.hasNotificationListener(context),
                grantedDetail = context.getString(R.string.doctor_perm_notification_listener_detail_granted),
                    missingDetail = context.getString(R.string.doctor_perm_notification_listener_detail_missing),
                fix = FixAction.OpenIntent(
                    label = context.getString(R.string.doctor_action_open_settings),
                    intent = PermissionHelper.notificationListenerSettingsIntent(),
                ),
            )
        )
        add(
            capabilityRow(
                id = "perm.accessibility",
                category = DoctorCategory.Permissions,
                label = context.getString(R.string.doctor_perm_accessibility_label),
                cap = Capability.Accessibility,
                enabled = enabled,
                granted = PermissionHelper.hasAccessibilityService(context),
                grantedDetail = context.getString(R.string.doctor_perm_accessibility_detail_granted),
                    missingDetail = context.getString(R.string.doctor_perm_accessibility_detail_missing),
                fix = FixAction.OpenIntent(
                    label = context.getString(R.string.doctor_action_open_settings),
                    intent = PermissionHelper.accessibilitySettingsIntent(),
                ),
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(
                capabilityRow(
                    id = "perm.all_files",
                    category = DoctorCategory.Permissions,
                    label = context.getString(R.string.doctor_perm_all_files_label),
                    cap = Capability.AllFiles,
                    enabled = enabled,
                    granted = PermissionHelper.hasAllFilesAccess(context),
                    grantedDetail = context.getString(R.string.doctor_perm_all_files_detail_granted),
                    missingDetail = context.getString(R.string.doctor_perm_all_files_detail_missing),
                    fix = FixAction.OpenIntent(
                        label = context.getString(R.string.doctor_action_open_settings),
                        intent = PermissionHelper.allFilesAccessIntent(context),
                    ),
                )
            )
        }
        // Phase 25 — SEND_SMS runtime permission row for the send_sms tool.
        add(
            capabilityRow(
                id = "perm.send_sms",
                category = DoctorCategory.Permissions,
                label = context.getString(R.string.doctor_perm_send_sms_label),
                cap = Capability.SendSms,
                enabled = enabled,
                granted = PermissionHelper.hasRuntime(context, listOf(Manifest.permission.SEND_SMS)),
                grantedDetail = context.getString(R.string.doctor_detail_granted),
                missingDetail = context.getString(R.string.doctor_perm_send_sms_detail_missing),
                fix = FixAction.OpenAppRoute(
                    context.getString(R.string.doctor_action_open_app_permissions),
                    AppRouteKey.SettingPermissions,
                ),
            )
        )
        // Previously-unchecked permissions, now covered. Each is tool-aware: it only WARNs when
        // the feature that needs it is enabled, so the opt-in philosophy holds (a denied perm for
        // a disabled tool stays INFO). This is what fixes the "Doctor said all-clear while
        // Display-over-other-apps etc. were ungranted" report.
        add(
            capabilityRow(
                id = "perm.overlay",
                category = DoctorCategory.Permissions,
                label = context.getString(R.string.doctor_perm_overlay_label),
                cap = Capability.Overlay,
                enabled = enabled,
                granted = android.provider.Settings.canDrawOverlays(context),
                grantedDetail = context.getString(R.string.doctor_detail_granted),
                missingDetail = context.getString(R.string.doctor_perm_overlay_detail_missing),
                fix = FixAction.OpenAppRoute(
                    context.getString(R.string.doctor_action_open_app_permissions),
                    AppRouteKey.SettingPermissions,
                ),
            )
        )
        add(
            capabilityRow(
                id = "perm.write_settings",
                category = DoctorCategory.Permissions,
                label = context.getString(R.string.doctor_perm_write_settings_label),
                cap = Capability.WriteSettings,
                enabled = enabled,
                granted = PermissionHelper.hasWriteSettings(context),
                grantedDetail = context.getString(R.string.doctor_detail_granted),
                missingDetail = context.getString(R.string.doctor_perm_write_settings_detail_missing),
                fix = FixAction.OpenAppRoute(
                    context.getString(R.string.doctor_action_open_app_permissions),
                    AppRouteKey.SettingPermissions,
                ),
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(
                capabilityRow(
                    id = "perm.bluetooth_connect",
                    category = DoctorCategory.Permissions,
                    label = context.getString(R.string.doctor_perm_bluetooth_connect_label),
                    cap = Capability.BluetoothConnect,
                    enabled = enabled,
                    granted = PermissionHelper.hasRuntime(context, listOf(Manifest.permission.BLUETOOTH_CONNECT)),
                    grantedDetail = context.getString(R.string.doctor_detail_granted),
                    missingDetail = context.getString(R.string.doctor_perm_bluetooth_connect_detail_missing),
                    fix = FixAction.OpenAppRoute(
                    context.getString(R.string.doctor_action_open_app_permissions),
                    AppRouteKey.SettingPermissions,
                ),
                )
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(
                capabilityRow(
                    id = "perm.nearby_wifi",
                    category = DoctorCategory.Permissions,
                    label = context.getString(R.string.doctor_perm_nearby_wifi_label),
                    cap = Capability.NearbyWifi,
                    enabled = enabled,
                    granted = PermissionHelper.hasRuntime(context, listOf(Manifest.permission.NEARBY_WIFI_DEVICES)),
                    grantedDetail = context.getString(R.string.doctor_detail_granted),
                    missingDetail = context.getString(R.string.doctor_perm_nearby_wifi_detail_missing),
                    fix = FixAction.OpenAppRoute(
                    context.getString(R.string.doctor_action_open_app_permissions),
                    AppRouteKey.SettingPermissions,
                ),
                )
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(
                capabilityRow(
                    id = "perm.background_location",
                    category = DoctorCategory.Permissions,
                    label = context.getString(R.string.doctor_perm_background_location_label),
                    cap = Capability.BackgroundLocation,
                    enabled = enabled,
                    granted = PermissionHelper.hasRuntime(context, listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)),
                    grantedDetail = context.getString(R.string.doctor_detail_granted),
                    missingDetail = context.getString(R.string.doctor_perm_background_location_detail_missing),
                    fix = FixAction.OpenAppRoute(
                    context.getString(R.string.doctor_action_open_app_permissions),
                    AppRouteKey.SettingPermissions,
                ),
                )
            )
        }
        // Phase 25 — NFC combined hardware + system-toggle row. Tri-state: no hardware
        // (INFO, no fix), hardware present but disabled (WARN, open NFC settings), on (OK).
        run {
            val adapter = android.nfc.NfcAdapter.getDefaultAdapter(context)
            val nfcNeeders = requirersOf(Capability.Nfc, enabled)
            when {
                adapter == null -> add(
                    DoctorCheck(
                        id = "perm.nfc_enabled",
                        category = DoctorCategory.Permissions,
                        label = context.getString(R.string.doctor_perm_nfc_label),
                        detail = context.getString(R.string.doctor_perm_nfc_detail_no_hardware),
                        severity = Severity.INFO,
                    )
                )
                !adapter.isEnabled -> add(
                    DoctorCheck(
                        id = "perm.nfc_enabled",
                        category = DoctorCategory.Permissions,
                        label = context.getString(R.string.doctor_perm_nfc_label),
                        detail = if (nfcNeeders.isEmpty())
                            context.getString(R.string.doctor_perm_nfc_detail_disabled_not_required)
                        else
                            context.getString(
                                R.string.doctor_perm_nfc_detail_disabled_needed_by,
                                nfcNeeders.joinToString(", ") { it.shortName(context) },
                            ),
                        severity = if (nfcNeeders.isEmpty()) Severity.INFO else Severity.WARN,
                        fix = if (nfcNeeders.isEmpty()) null else FixAction.OpenIntent(
                            label = context.getString(R.string.doctor_action_open_nfc_settings),
                            intent = android.content.Intent(android.provider.Settings.ACTION_NFC_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        ),
                    )
                )
                else -> add(
                    DoctorCheck(
                        id = "perm.nfc_enabled",
                        category = DoctorCategory.Permissions,
                        label = context.getString(R.string.doctor_perm_nfc_label),
                        detail = context.getString(R.string.doctor_perm_nfc_detail_ok),
                        severity = Severity.OK,
                    )
                )
            }
        }
    }

    /**
     * Build a capability-aware Doctor row.
     *   granted = true                                  -> Severity.OK
     *   granted = false AND no enabled tool needs cap   -> Severity.INFO ("not required")
     *   granted = false AND some enabled tool needs cap -> Severity.WARN ("needed by: …")
     *
     * The Fix button is offered only when granted=false AND at least one tool needs the
     * capability — we don't push the user to grant a permission they don't currently use.
     */
    private fun capabilityRow(
        id: String,
        category: DoctorCategory,
        label: String,
        cap: Set<LocalToolOption>,
        enabled: Set<LocalToolOption>,
        granted: Boolean,
        grantedDetail: String,
        missingDetail: String,
        fix: FixAction,
    ): DoctorCheck {
        val needers = requirersOf(cap, enabled)
        val severity = when {
            granted -> Severity.OK
            needers.isEmpty() -> Severity.INFO
            else -> Severity.WARN
        }
        val detail = when {
            granted -> grantedDetail
            needers.isEmpty() -> context.getString(R.string.doctor_detail_not_required)
            else -> context.getString(
                R.string.doctor_detail_needed_by,
                missingDetail,
                needers.joinToString(", ") { it.shortName(context) },
            )
        }
        return DoctorCheck(
            id = id,
            category = category,
            label = label,
            detail = detail,
            severity = severity,
            fix = if (!granted && needers.isNotEmpty()) fix else null,
        )
    }

    // ----- Background services ---------------------------------------------------------

    private suspend fun serviceChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> = buildList {
        val tg = telegramPrefs.current()
        // Telegram bot: token, enabled flag, FGS state should agree.
        if (tg.enabled) {
            add(
                DoctorCheck(
                    id = "service.telegram_token",
                    category = DoctorCategory.Services,
                    label = context.getString(R.string.doctor_service_telegram_token_label),
                    // Don't render any portion of the token — Telegram bot tokens are
                    // formatted "<bot_id>:<secret>" and even the first 6 chars reveal the
                    // bot id, which an attacker could use to enumerate bot endpoints.
                    detail = if (tg.hasCredential) {
                        if (!tg.vaultSlotId.isNullOrBlank()) context.getString(R.string.doctor_service_telegram_token_detail_vault)
                        else context.getString(
                            R.string.doctor_service_telegram_token_detail_configured,
                            tg.token.length,
                        )
                    }
                    else context.getString(R.string.doctor_service_telegram_token_detail_missing),
                    severity = if (tg.hasCredential) Severity.OK else Severity.FAIL,
                    fix = if (!tg.hasCredential)
                        FixAction.OpenAppRoute(context.getString(R.string.doctor_action_open_telegram_settings), AppRouteKey.SettingTelegram)
                    else null,
                )
            )
            add(
                DoctorCheck(
                    id = "service.telegram_running",
                    category = DoctorCategory.Services,
                    label = context.getString(R.string.doctor_service_telegram_running_label),
                    detail = if (TelegramBotService.isRunning) context.getString(R.string.doctor_service_telegram_running_detail_ok)
                    else context.getString(R.string.doctor_service_telegram_running_detail_stopped),
                    severity = when {
                        TelegramBotService.isRunning -> Severity.OK
                        !tg.hasCredential -> Severity.INFO  // credential issue covers this
                        else -> Severity.FAIL
                    },
                )
            )
        } else {
            add(
                DoctorCheck(
                    id = "service.telegram_off",
                    category = DoctorCategory.Services,
                    label = context.getString(R.string.doctor_service_telegram_off_label),
                    detail = context.getString(R.string.doctor_service_telegram_off_detail),
                    severity = Severity.INFO,
                )
            )
        }
        // AccessibilityService binding — only flagged if a tool that needs it is enabled.
        val accNeeders = requirersOf(Capability.Accessibility, enabled)
        if (accNeeders.isNotEmpty()) {
            add(
                DoctorCheck(
                    id = "service.accessibility_bound",
                    category = DoctorCategory.Services,
                    label = context.getString(R.string.doctor_service_accessibility_label),
                    detail = if (AccessibilityServiceHandle.isRunning())
                        context.getString(
                            R.string.doctor_service_accessibility_detail_alive,
                            accNeeders.joinToString(", ") { it.shortName(context) },
                        )
                    else if (PermissionHelper.hasAccessibilityService(context))
                        context.getString(R.string.doctor_service_accessibility_detail_not_bound)
                    else
                        context.getString(
                            R.string.doctor_service_accessibility_detail_missing,
                            accNeeders.joinToString(", ") { it.shortName(context) },
                        ),
                    severity = when {
                        AccessibilityServiceHandle.isRunning() -> Severity.OK
                        else -> Severity.WARN
                    },
                    fix = if (!AccessibilityServiceHandle.isRunning()) FixAction.OpenIntent(
                        label = context.getString(R.string.doctor_action_open_settings),
                        intent = PermissionHelper.accessibilitySettingsIntent(),
                    ) else null,
                )
            )
        }
        // NotificationListener binding — same logic.
        val nlNeeders = requirersOf(Capability.NotificationListener, enabled)
        if (nlNeeders.isNotEmpty()) {
            add(
                DoctorCheck(
                    id = "service.notification_listener_bound",
                    category = DoctorCategory.Services,
                    label = context.getString(R.string.doctor_service_notification_listener_label),
                    detail = if (NotificationListenerHandle.isBound())
                        context.getString(
                            R.string.doctor_service_notification_listener_detail_bound,
                            nlNeeders.joinToString(", ") { it.shortName(context) },
                        )
                    else if (PermissionHelper.hasNotificationListener(context))
                        context.getString(R.string.doctor_service_notification_listener_detail_not_bound)
                    else
                        context.getString(
                            R.string.doctor_service_notification_listener_detail_missing,
                            nlNeeders.joinToString(", ") { it.shortName(context) },
                        ),
                    severity = when {
                        NotificationListenerHandle.isBound() -> Severity.OK
                        else -> Severity.WARN
                    },
                    fix = if (!NotificationListenerHandle.isBound()) FixAction.OpenIntent(
                        label = context.getString(R.string.doctor_action_open_settings),
                        intent = PermissionHelper.notificationListenerSettingsIntent(),
                    ) else null,
                )
            )
        }
    }

    // ----- Active assistant ------------------------------------------------------------

    /**
     * Informational section. All rows are [Severity.INFO] — these are status rows, not
     * problem rows. The single "default assistant" row surfaces the assistant that:
     *   - New Telegram conversations use (when no explicit assistantId is configured).
     *   - Cron jobs run as (their assistantId is locked at job creation time, but new jobs
     *     inherit from the Settings default).
     *   - New in-app chats default to.
     *
     * A WARN row fires when the global assistant list is empty — that's a sign the settings
     * store was corrupted or a migration wiped the assistants list.
     *
     * A separate row shows the Telegram-bot-configured override if one is set.
     */
    private suspend fun assistantChecks(): List<DoctorCheck> = buildList {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val assistants = settings.assistants
            val defaultAssistant = settings.getCurrentAssistant()

            // Row 1: default assistant name + id
            add(
                DoctorCheck(
                    id = "assistant.default",
                    category = DoctorCategory.AssistantInfo,
                    label = context.getString(R.string.doctor_assistant_default_label),
                    detail = if (assistants.isEmpty())
                        context.getString(R.string.doctor_assistant_default_detail_missing)
                    else
                        context.getString(
                            R.string.doctor_assistant_default_detail,
                            defaultAssistant.name.ifBlank {
                                context.getString(R.string.doctor_value_unnamed)
                            },
                            defaultAssistant.id.toString().take(8),
                        ),
                    severity = if (assistants.isEmpty()) Severity.WARN else Severity.INFO,
                    fix = FixAction.OpenAppRoute(context.getString(R.string.doctor_action_open_assistants), AppRouteKey.Assistant),
                )
            )

            // Row 2: total assistant count
            add(
                DoctorCheck(
                    id = "assistant.count",
                    category = DoctorCategory.AssistantInfo,
                    label = context.getString(R.string.doctor_assistant_count_label),
                    detail = context.getString(R.string.doctor_assistant_count_detail, assistants.size),
                    severity = Severity.INFO,
                    fix = FixAction.OpenAppRoute(context.getString(R.string.doctor_action_open_assistants), AppRouteKey.Assistant),
                )
            )

            // Row 3: Telegram-bot assistant override (if set)
            val tg = telegramPrefs.current()
            if (tg.enabled && tg.assistantId != null) {
                val tgAssistant = tg.assistantId.let { id ->
                    runCatching {
                        val uuid = kotlin.uuid.Uuid.parse(id)
                        assistants.find { it.id == uuid }
                    }.getOrNull()
                }
                add(
                    DoctorCheck(
                        id = "assistant.telegram_override",
                        category = DoctorCategory.AssistantInfo,
                        label = context.getString(R.string.doctor_assistant_telegram_override_label),
                        detail = when {
                            tgAssistant != null ->
                                context.getString(
                                    R.string.doctor_assistant_telegram_override_detail_set,
                                    tgAssistant.name.ifBlank {
                                        context.getString(R.string.doctor_value_unnamed)
                                    },
                                    tgAssistant.id.toString().take(8),
                                )
                            else ->
                                context.getString(
                                    R.string.doctor_assistant_telegram_override_detail_missing,
                                    tg.assistantId.take(8),
                                )
                        },
                        severity = if (tgAssistant != null) Severity.INFO else Severity.WARN,
                        fix = if (tgAssistant == null)
                            FixAction.OpenAppRoute(context.getString(R.string.doctor_action_open_telegram_settings), AppRouteKey.SettingTelegram)
                        else null,
                    )
                )
            }
        }
    }

    // ----- Database --------------------------------------------------------------------

    private suspend fun databaseChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> = buildList {
        // Migration version
        val version = runCatching { database.openHelper.readableDatabase.version }.getOrDefault(-1)
        add(
            DoctorCheck(
                id = "db.version",
                category = DoctorCategory.Database,
                label = context.getString(R.string.doctor_db_version_label),
                // Room refuses to open the DB unless the stored version matches the compiled schema;
                // if we got here, version is the live schema version (migrations ran successfully).
                detail = if (version > 0)
                    context.getString(R.string.doctor_db_version_detail_ok, version)
                else
                    context.getString(R.string.doctor_db_version_detail_failed),
                severity = if (version > 0) Severity.OK else Severity.WARN,
            )
        )
        // Integrity check
        val integrity = runCatching {
            withTimeoutOrNull(5_000L) {
                database.openHelper.readableDatabase
                    .query("PRAGMA integrity_check;")
                    .use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }
        }.getOrNull()
        // Offer an AutoFix only when the corruption mentions message_fts — that's the one
        // we know how to repair (DROP + recreate + reindex from the messages table). For
        // any other integrity failure, surface the message and let the user decide; we
        // don't blanket-rebuild things we don't know are safe.
        val mentionsFts = integrity != null && integrity != "ok" && integrity.contains("message_fts", ignoreCase = true)
        add(
            DoctorCheck(
                id = "db.integrity",
                category = DoctorCategory.Database,
                label = context.getString(R.string.doctor_db_integrity_label),
                detail = when (integrity) {
                    null -> context.getString(R.string.doctor_db_integrity_detail_timeout)
                    "ok" -> context.getString(R.string.doctor_db_integrity_detail_ok)
                    else -> context.getString(R.string.doctor_db_integrity_detail_returned, integrity)
                },
                severity = if (integrity == "ok") Severity.OK else Severity.FAIL,
                fix = if (mentionsFts) FixAction.AutoFix(
                    label = context.getString(R.string.doctor_db_integrity_action),
                    run = {
                        runCatching {
                            val n = conversationRepository.repairAndRebuildIndexes()
                            AutoFixResult(
                                ok = true,
                                message = context.getString(
                                    R.string.doctor_db_integrity_message_rebuilt,
                                    n,
                                ),
                            )
                        }.getOrElse {
                            AutoFixResult(
                                ok = false,
                                message = context.getString(
                                    R.string.doctor_db_integrity_message_failed,
                                    it::class.simpleName,
                                    it.message ?: "?",
                                ),
                            )
                        }
                    },
                ) else null,
            )
        )
        // Workflows summary
        runCatching {
            val all = workflowRepository.observeAll().first()
            val enabled = all.count { it.entity.enabled }
            add(
                DoctorCheck(
                    id = "db.workflows",
                    category = DoctorCategory.Database,
                    label = context.getString(R.string.doctor_db_workflows_label),
                    detail = context.getString(R.string.doctor_db_workflows_detail, all.size, enabled),
                    severity = Severity.INFO,
                    fix = if (all.isNotEmpty())
                        FixAction.OpenAppRoute(context.getString(R.string.doctor_action_open_workflows), AppRouteKey.SettingWorkflows)
                    else null,
                )
            )
        }
        // Scheduled jobs summary
        runCatching {
            val all = scheduledJobRepository.getAll()
            val enabled = all.count { it.enabled }
            add(
                DoctorCheck(
                    id = "db.scheduled_jobs",
                    category = DoctorCategory.Database,
                    label = context.getString(R.string.doctor_db_scheduled_jobs_label),
                    detail = context.getString(R.string.doctor_db_scheduled_jobs_detail, all.size, enabled),
                    severity = Severity.INFO,
                    fix = if (all.isNotEmpty())
                        FixAction.OpenAppRoute(context.getString(R.string.doctor_action_open_scheduled_jobs), AppRouteKey.SettingScheduledJobs)
                    else null,
                )
            )
        }
        // Stranded run rows (started but never finished — process killed mid-run)
        runCatching {
            val stranded = scheduledJobRunRepository.getStranded(System.currentTimeMillis() - 30 * 60_000L)
            add(
                DoctorCheck(
                    id = "db.stranded_runs",
                    category = DoctorCategory.Database,
                    label = context.getString(R.string.doctor_db_stranded_label),
                    detail = if (stranded.isEmpty())
                        context.getString(R.string.doctor_db_stranded_detail_none)
                    else
                        context.getString(
                            R.string.doctor_db_stranded_detail_stranded,
                            stranded.size,
                        ),
                    severity = if (stranded.isEmpty()) Severity.OK else Severity.WARN,
                )
            )
        }
        // Phase 25 — SAF granted-directories live count for the ExternalStorage tool.
        // Reconciles against the OS persisted-permission list so revoked grants drop off.
        val store = storageVolumeGrantStore
        if (store != null) {
            runCatching {
                val externalStorageEnabled = enabled.contains(LocalToolOption.ExternalStorage)
                val grants = store.reconcile()
                add(
                    DoctorCheck(
                        id = "storage.granted_directories",
                        category = DoctorCategory.Database,
                        label = context.getString(R.string.doctor_db_granted_directories_label),
                        detail = if (!externalStorageEnabled && grants.isEmpty()) {
                            context.getString(R.string.doctor_db_granted_directories_detail_disabled)
                        } else if (grants.isEmpty()) {
                            context.getString(R.string.doctor_db_granted_directories_detail_none)
                        } else {
                            context.getString(
                                R.string.doctor_db_granted_directories_detail_granted,
                                grants.size,
                                grants.joinToString(", ") { it.displayName }
                            )
                        },
                        severity = if (externalStorageEnabled && grants.isNotEmpty())
                            Severity.OK else Severity.INFO,
                    )
                )
            }
        }
    }

    // ----- Network & providers ---------------------------------------------------------

    private suspend fun networkChecks(): List<DoctorCheck> = buildList {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val provs = settings.providers
            val configured = provs.count { p ->
                when (p) {
                    is me.rerere.ai.provider.ProviderSetting.OpenAI -> p.apiKey.isNotBlank()
                    is me.rerere.ai.provider.ProviderSetting.Google -> p.apiKey.isNotBlank()
                    is me.rerere.ai.provider.ProviderSetting.Claude -> p.apiKey.isNotBlank()
                    is me.rerere.ai.provider.ProviderSetting.AICore -> p.enabled  // on-device, no API key
                    // Local provider (LiteRT): usable when enabled AND at least one model has
                    // been loaded/downloaded. A disabled provider with no models is the factory
                    // default — don't count it.
                    is me.rerere.ai.provider.ProviderSetting.LiteRtLocal -> p.enabled && p.models.isNotEmpty()
                    is me.rerere.ai.provider.ProviderSetting.Codex -> p.enabled  // OAuth, no API key
                }
            }
            add(
                DoctorCheck(
                    id = "net.providers",
                    category = DoctorCategory.Network,
                    label = context.getString(R.string.doctor_net_providers_label),
                    detail = context.getString(
                        R.string.doctor_net_providers_detail,
                        configured,
                        provs.size,
                    ),
                    severity = if (configured > 0) Severity.OK else Severity.WARN,
                    fix = FixAction.OpenAppRoute(context.getString(R.string.doctor_action_open_providers), AppRouteKey.SettingProvider),
                )
            )
        }
        // LiteRT accelerator status. The runtime's GPU -> CPU fallback is silent today:
        // if the device's OpenCL/OpenGL delegate fails to init (e.g. MLDrift's
        // "CreateSharedMemoryManager is not implemented" on some Adreno drivers), the
        // model loads on CPU and the user has no UI indication. LiteRtProvider now
        // persists the actually-chosen accelerator after every load; surface that here
        // so the user can confirm GPU is engaged.
        runCatching {
            val prefs = localRuntimePreferences
            if (prefs != null) {
                val accel = prefs.acceleratorFlow(me.rerere.locallm.LocalRuntime.LiteRT).first()
                val forceCpu = prefs.forceCpu(me.rerere.locallm.LocalRuntime.LiteRT)
                val detail = when {
                    accel == null -> context.getString(R.string.doctor_litert_accel_detail_not_probed)
                    forceCpu && accel == "CPU" ->
                        context.getString(R.string.doctor_litert_accel_detail_cpu_forced)
                    accel == "CPU" ->
                        context.getString(R.string.doctor_litert_accel_detail_cpu_fallback)
                    accel == "GPU" -> context.getString(R.string.doctor_litert_accel_detail_gpu)
                    accel == "QNN" || accel == "NPU" ->
                        context.getString(R.string.doctor_litert_accel_detail_npu)
                    accel == "NNAPI" -> context.getString(R.string.doctor_litert_accel_detail_nnapi)
                    else -> context.getString(R.string.doctor_litert_accel_detail_backend, accel)
                }
                val severity = when {
                    accel == null -> Severity.INFO
                    accel == "CPU" && !forceCpu -> Severity.WARN  // unexpected fallback
                    else -> Severity.OK
                }
                add(
                    DoctorCheck(
                        id = "net.litert_accel",
                        category = DoctorCategory.Network,
                        label = context.getString(R.string.doctor_litert_accel_label),
                        detail = detail,
                        severity = severity,
                        fix = FixAction.OpenAppRoute(
                            context.getString(R.string.doctor_action_open_local_litert),
                            AppRouteKey.SettingProvider,
                        ),
                    )
                )
                // Performance telemetry — surface the last-known prefill/decode tok/s for
                // each model so the user (and the support team triaging a slow report)
                // can see at a glance whether the runtime is hitting expected rates. We
                // INFO when present; WARN never (the model could legitimately be slow on a
                // weak device — the user knows their hardware better than we do).
                val perfMap = prefs.perfTelemetryFlow(me.rerere.locallm.LocalRuntime.LiteRT).first()
                if (perfMap.isNotEmpty()) {
                    val rows = perfMap.values.sortedByDescending { it.sampledAtMs }
                    val mtpOn = context.getString(R.string.doctor_litert_perf_mtp_on)
                    val detail = rows.joinToString("\n") { s ->
                        val spec = if (s.specDecodingEngaged) mtpOn else ""
                        context.getString(
                            R.string.doctor_litert_perf_row,
                            s.modelId,
                            "%.1f".format(s.prefillTps),
                            "%.1f".format(s.decodeTps),
                            spec,
                        )
                    }
                    add(
                        DoctorCheck(
                            id = "net.litert_perf",
                            category = DoctorCategory.Network,
                            label = context.getString(R.string.doctor_litert_perf_label),
                            detail = context.getString(R.string.doctor_litert_perf_detail, detail),
                            severity = Severity.INFO,
                            fix = FixAction.OpenAppRoute(
                                context.getString(R.string.doctor_action_open_local_litert),
                                AppRouteKey.SettingProvider,
                            ),
                        )
                    )
                }
                // Vision-encoder availability — surface any models the runtime had to drop
                // to text-only on this device's GPU. The provider's vision-CPU fallback
                // means a multimodal model still works for chat, but the user has lost
                // image input on this chip. Most common cause: Adreno 7xx + restrictive
                // OEM linker namespace (One UI / OriginOS) hitting upstream LiteRT-LM
                // issue #2292 (gpu_backend_opengl.cc:CreateSharedMemoryManager UNIMPLEMENTED).
                val visionUnavailable = prefs
                    .visionUnavailableFlow(me.rerere.locallm.LocalRuntime.LiteRT).first()
                if (visionUnavailable.isNotEmpty()) {
                    add(
                        DoctorCheck(
                            id = "net.litert_vision",
                            category = DoctorCategory.Network,
                            label = context.getString(R.string.doctor_litert_vision_label),
                            detail = context.getString(
                                R.string.doctor_litert_vision_detail,
                                visionUnavailable.joinToString(", "),
                            ),
                            severity = Severity.WARN,
                            fix = FixAction.OpenAppRoute(
                                context.getString(R.string.doctor_action_open_local_litert),
                                AppRouteKey.SettingProvider,
                            ),
                        )
                    )
                }
            }
        }
        // DNS sanity — confirms the OkHttp clients aren't stuck on a stale resolver.
        val dnsOk = withTimeoutOrNull(2_500L) {
            runCatching { InetAddress.getByName("dns.google") != null }.getOrDefault(false)
        } == true
        add(
            DoctorCheck(
                id = "net.dns",
                category = DoctorCategory.Network,
                label = context.getString(R.string.doctor_net_dns_label),
                detail = if (dnsOk) context.getString(R.string.doctor_net_dns_detail_ok)
                else context.getString(R.string.doctor_net_dns_detail_failed),
                severity = if (dnsOk) Severity.OK else Severity.WARN,
            )
        )
    }

    // ----- Termux ----------------------------------------------------------------------

    private fun termuxChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> = buildList {
        val needers = requirersOf(Capability.Termux, enabled)
        // Skip the entire category when no Termux-using tool is enabled — keeps the
        // Doctor screen focused on what the user actually configured.
        if (needers.isEmpty()) return@buildList

        val pm = context.packageManager
        val termuxInstalled = runCatching { pm.getPackageInfo("com.termux", 0); true }.getOrDefault(false)
        add(
            DoctorCheck(
                id = "termux.installed",
                category = DoctorCategory.Termux,
                label = context.getString(R.string.doctor_termux_installed_label),
                detail = if (termuxInstalled) context.getString(R.string.doctor_termux_installed_detail_ok)
                else context.getString(
                    R.string.doctor_termux_installed_detail_missing,
                    needers.joinToString(", ") { it.shortName(context) },
                ),
                severity = if (termuxInstalled) Severity.OK else Severity.WARN,
            )
        )
        if (termuxInstalled) {
            val runCommandPerm = runCatching {
                val perm = "com.termux.permission.RUN_COMMAND"
                context.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            add(
                DoctorCheck(
                    id = "termux.run_command",
                    category = DoctorCategory.Termux,
                    label = context.getString(R.string.doctor_termux_run_command_label),
                    detail = if (runCommandPerm) context.getString(R.string.doctor_termux_run_command_detail_granted)
                    else context.getString(R.string.doctor_termux_run_command_detail_missing),
                    severity = if (runCommandPerm) Severity.OK else Severity.WARN,
                )
            )
        }
    }

    // ----- Browser (Pass 3) ------------------------------------------------------------

    /**
     * Pass 3: Doctor rows for the in-app browser feature.
     *  - `browser.profile_dir_writable` — the WebView profile lives at
     *    `${filesDir}/browser-profile/`. The directory MUST exist + be writable for cookies
     *    to persist across app restarts. AutoFix re-creates it on demand.
     *  - `browser.write_tools_status` — informational live count of which write-tools the
     *    user has switched on. Lets a user spot-check at a glance whether `browser_type`
     *    is unintentionally enabled. INFO severity, no fix action.
     *
     * The category is [DoctorCategory.Permissions] per the spec ("Permissions / Services").
     * Both rows are emitted regardless of master Browser-toggle state, but their severity
     * downgrades to INFO when no assistant has [LocalToolOption.Browser] enabled (matches
     * the existing capability-aware pattern used throughout the file).
     */
    private fun browserChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> = buildList {
        val needers = requirersOf(Capability.Browser, enabled)
        val browserNeeded = needers.isNotEmpty()

        // Row 1: profile dir writable (with AutoFix to mkdirs).
        val profileDir = File(context.filesDir, "browser-profile")
        val exists = runCatching { profileDir.exists() && profileDir.isDirectory }.getOrDefault(false)
        val writable = exists && runCatching { profileDir.canWrite() }.getOrDefault(false)
        val ok = exists && writable
        add(
            DoctorCheck(
                id = "browser.profile_dir_writable",
                category = DoctorCategory.Permissions,
                label = context.getString(R.string.doctor_browser_profile_label),
                detail = when {
                    ok && browserNeeded ->
                        context.getString(
                            R.string.doctor_browser_profile_detail_ok_needed,
                            profileDir.absolutePath,
                        )
                    ok ->
                        context.getString(
                            R.string.doctor_browser_profile_detail_ok,
                            profileDir.absolutePath,
                        )
                    !exists && browserNeeded ->
                        context.getString(R.string.doctor_browser_profile_detail_missing_needed)
                    !exists ->
                        context.getString(R.string.doctor_browser_profile_detail_missing_not_required)
                    !writable && browserNeeded ->
                        context.getString(R.string.doctor_browser_profile_detail_readonly_needed)
                    else -> context.getString(R.string.doctor_browser_profile_detail_readonly)
                },
                severity = when {
                    ok -> Severity.OK
                    browserNeeded -> Severity.WARN
                    else -> Severity.INFO
                },
                fix = if (!ok && browserNeeded) FixAction.AutoFix(
                    label = context.getString(R.string.doctor_browser_profile_action),
                    run = {
                        val created = runCatching { profileDir.mkdirs() }.getOrDefault(false)
                        val nowOk = profileDir.exists() && profileDir.canWrite()
                        AutoFixResult(
                            ok = nowOk,
                            message = if (nowOk) context.getString(
                                R.string.doctor_browser_profile_message_created,
                                profileDir.absolutePath,
                            )
                            else if (created) context.getString(R.string.doctor_browser_profile_message_created_not_writable)
                            else context.getString(R.string.doctor_browser_profile_message_mkdirs_failed),
                        )
                    },
                ) else null,
            )
        )

        // Row 2: write-tools live count (INFO only). Skipped silently if BrowserPreferences
        // wasn't injected — the row is purely informational and the test harness paths
        // that don't construct prefs shouldn't fail.
        val prefs = browserPreferences
        if (prefs != null) {
            val snapshot = runCatching { prefs.snapshotBlocking() }.getOrDefault(BrowserToolDefaults.DEFAULT_ENABLED)
            val onWriteTools = BrowserToolDefaults.WRITE_TOOLS.filter { snapshot[it] == true }
            val detail = if (onWriteTools.isEmpty())
                context.getString(R.string.doctor_browser_write_tools_detail_none)
            else
                context.getString(
                    R.string.doctor_browser_write_tools_detail_count,
                    onWriteTools.size,
                    onWriteTools.joinToString(", ") { it.removePrefix("browser_") },
                )
            add(
                DoctorCheck(
                    id = "browser.write_tools_status",
                    category = DoctorCategory.Permissions,
                    label = context.getString(R.string.doctor_browser_write_tools_label),
                    detail = detail,
                    severity = Severity.INFO,
                )
            )
        }
    }

    // ----- Maintenance -----------------------------------------------------------------

    private fun maintenanceChecks(): List<DoctorCheck> = buildList {
        // Cache size on disk
        val cacheBytes = directorySize(context.cacheDir)
        add(
            DoctorCheck(
                id = "maint.cache_size",
                category = DoctorCategory.Maintenance,
                label = context.getString(R.string.doctor_maintenance_cache_label),
                detail = context.getString(
                    R.string.doctor_maintenance_cache_detail,
                    humanBytes(cacheBytes),
                    if (cacheBytes > 200L * 1024 * 1024) {
                        context.getString(R.string.doctor_maintenance_cache_detail_over_200mb)
                    } else {
                        context.getString(R.string.doctor_maintenance_cache_detail_normal)
                    },
                ),
                severity = if (cacheBytes > 500L * 1024 * 1024) Severity.WARN else Severity.OK,
                fix = FixAction.AutoFix(
                    label = context.getString(R.string.doctor_maintenance_cache_action),
                    run = {
                        val freed = clearDirectoryContents(context.cacheDir)
                        AutoFixResult(
                            ok = true,
                            message = context.getString(
                                R.string.doctor_maintenance_cache_message_freed,
                                humanBytes(freed),
                            ),
                        )
                    },
                ),
            )
        )
    }

    // ----- Diagnostics summary ---------------------------------------------------------

    private fun diagnosticsChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> = listOf(
        DoctorCheck(
            id = "diag.app",
            category = DoctorCategory.Diagnostics,
            label = context.getString(R.string.doctor_diag_app_label),
            detail = context.getString(
                R.string.doctor_diag_app_detail,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
                BuildConfig.DEBUG,
            ),
            severity = Severity.INFO,
        ),
        DoctorCheck(
            id = "diag.android",
            category = DoctorCategory.Diagnostics,
            label = context.getString(R.string.doctor_diag_android_label),
            detail = context.getString(
                R.string.doctor_diag_android_detail,
                Build.VERSION.SDK_INT,
                Build.VERSION.RELEASE,
                Build.MANUFACTURER,
                Build.MODEL,
            ),
            severity = Severity.INFO,
        ),
        DoctorCheck(
            id = "diag.runtime",
            category = DoctorCategory.Diagnostics,
            label = context.getString(R.string.doctor_diag_runtime_label),
            detail = run {
                val rt = Runtime.getRuntime()
                val freeMb = rt.freeMemory() / (1024 * 1024)
                val totalMb = rt.totalMemory() / (1024 * 1024)
                val maxMb = rt.maxMemory() / (1024 * 1024)
                context.getString(R.string.doctor_diag_runtime_detail, freeMb, totalMb, maxMb)
            },
            severity = Severity.INFO,
        ),
        DoctorCheck(
            id = "diag.enabled_tools",
            category = DoctorCategory.Diagnostics,
            label = context.getString(R.string.doctor_diag_tools_label),
            detail = if (enabled.isEmpty())
                context.getString(R.string.doctor_diag_tools_detail_none)
            else
                context.getString(R.string.doctor_diag_tools_detail_count, enabled.size),
            severity = if (enabled.isEmpty()) Severity.WARN else Severity.INFO,
        ),
    )

    private fun directorySize(dir: File): Long = runCatching {
        if (!dir.exists()) return@runCatching 0L
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    private fun clearDirectoryContents(dir: File): Long {
        var freed = 0L
        runCatching {
            dir.listFiles()?.forEach { f ->
                freed += directorySize(f)
                f.deleteRecursively()
            }
        }
        return freed
    }

    private fun humanBytes(bytes: Long): String {
        val mb = 1024.0 * 1024
        val gb = mb * 1024
        return when {
            bytes < mb -> "%.0f KB".format(bytes / 1024.0)
            bytes < gb -> "%.1f MB".format(bytes / mb)
            else -> "%.2f GB".format(bytes / gb)
        }
    }
}
