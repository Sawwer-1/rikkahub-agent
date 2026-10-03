package me.rerere.rikkahub.data.permissions

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.PermissionHelper
import me.rerere.rikkahub.data.capability.CapabilityCatalog
import me.rerere.rikkahub.data.capability.CapabilityRequirement
import me.rerere.rikkahub.data.capability.BridgeType
import me.rerere.rikkahub.data.capability.ImplementationState
import me.rerere.rikkahub.service.RikkaAccessibilityService
import me.rerere.rikkahub.service.RikkaNotificationListenerService

/**
 * Auto-discovered inventory of every permission this app requires, grouped by how the
 * user grants it. Reads <uses-permission> entries at runtime via PackageManager so
 * future-added perms appear automatically — only the friendly-label lookup is hand-curated;
 * any unmapped perm falls back to humanizing the constant name.
 *
 * On top of <uses-permission>, two virtual rows surface service bindings the user must enable
 * via dedicated Android UIs (AccessibilityService, NotificationListenerService) — these are
 * not real permissions but behave the same from the user's standpoint.
 */
object PermissionInventory {

    enum class Group { ServicesAndIntegrations, SpecialAccess, Runtime, AutoGranted }

    enum class Status { GRANTED, DENIED, AUTO_GRANTED }

    sealed class GrantAction {
        /** No action required — install-time / signature-level / always granted. */
        object None : GrantAction()
        /** Request via ActivityResultContracts.RequestPermission. */
        data class Runtime(val permission: String) : GrantAction()
        /** Open this Intent, user toggles in system Settings. */
        data class SystemSettings(val intent: Intent) : GrantAction()
    }

    data class Row(
        val id: String,
        val label: String,
        val description: String,
        val status: Status,
        val group: Group,
        val grant: GrantAction,
        /** Optional display override for states outside the legacy granted/denied model. */
        val statusLabel: String? = null,
    )

    fun build(context: Context): List<Row> {
        val rows = mutableListOf<Row>()
        rows += accessibilityServiceRow(context)
        rows += notificationListenerRow(context)
        rows += deviceAdminRow(context)
        rows += vpnServiceRow(context)
        rows += mediaProjectionConsentRow(context)
        rows += shizukuBridgeRow(context)
        rows += adbBridgeRow(context)

        val declared = readDeclaredPermissions(context)
        for (perm in declared) {
            rows += classify(context, perm) ?: continue
        }
        return rows.sortedWith(
            compareBy({ it.group.ordinal }, { if (it.status == Status.DENIED) 0 else 1 }, { it.label })
        )
    }

    /**
     * Build a list of [Row] entries from [CapabilityCatalog] showing the status of each
     * registered capability. This lets the user see what capabilities exist, their
     * implementation state, and whether their requirements are satisfied.
     *
     * Use this alongside [build] for a complete picture: [build] shows individual
     * permissions, while [capabilityStatusRows] groups them by capability.
     */
    fun capabilityStatusRows(context: Context): List<Row> {
        return CapabilityCatalog.allCapabilities().mapNotNull { cap ->
            val ok = cap.requirements.all { req -> checkRequirement(context, req) }
            val missingCount = cap.requirements.count { req -> !checkRequirement(context, req) }
            val desc = when (cap.implementationState) {
                ImplementationState.Reserved -> context.getString(R.string.perm_capability_desc_reserved)
                ImplementationState.SystemRestricted -> context.getString(R.string.perm_capability_desc_system_restricted)
                ImplementationState.ExternalBridgeRequired -> context.getString(R.string.perm_capability_desc_external_bridge)
                ImplementationState.ManualOnly -> context.getString(R.string.perm_capability_desc_manual_only)
                ImplementationState.Implemented -> {
                    if (missingCount > 0) {
                        context.getString(R.string.perm_capability_desc_missing, missingCount)
                    } else {
                        context.getString(R.string.perm_capability_desc_all_satisfied)
                    }
                }
            }
            Row(
                id = "capability:${cap.id.name}",
                label = cap.id.name.humanizeCapabilityId(),
                description = desc,
                status = when {
                    cap.implementationState == ImplementationState.Reserved -> Status.AUTO_GRANTED
                    ok -> Status.GRANTED
                    else -> Status.DENIED
                },
                group = Group.Runtime,
                grant = GrantAction.None,
                statusLabel = when (cap.implementationState) {
                    ImplementationState.Reserved -> context.getString(R.string.perm_status_reserved)
                    ImplementationState.SystemRestricted -> context.getString(R.string.perm_status_system_restricted)
                    ImplementationState.ExternalBridgeRequired -> context.getString(R.string.perm_status_external_bridge)
                    ImplementationState.ManualOnly -> context.getString(R.string.perm_status_manual_only)
                    ImplementationState.Implemented -> null
                },
            )
        }
    }

    private fun checkRequirement(context: Context, req: CapabilityRequirement): Boolean {
        return when (req) {
            is CapabilityRequirement.ManifestPermission -> {
                ContextCompat.checkSelfPermission(context, req.permission) ==
                    PackageManager.PERMISSION_GRANTED
            }
            is CapabilityRequirement.RuntimePermission -> {
                !req.appliesToSdk(Build.VERSION.SDK_INT) ||
                    ContextCompat.checkSelfPermission(context, req.permission) ==
                        PackageManager.PERMISSION_GRANTED
            }
            is CapabilityRequirement.SpecialAccess -> false // too complex, skip in quick check
            is CapabilityRequirement.EnabledService -> {
                val flatName = req.component.flattenToString()
                when {
                    req.component.className.contains("RikkaAccessibilityService") -> {
                        (Settings.Secure.getString(
                            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                        ) ?: "").split(":").any { it.equals(flatName, ignoreCase = true) }
                    }
                    req.component.className.contains("RikkaNotificationListenerService") -> {
                        (Settings.Secure.getString(
                            context.contentResolver, "enabled_notification_listeners"
                        ) ?: "").split(":").any { it.equals(flatName, ignoreCase = true) }
                    }
                    else -> false
                }
            }
            is CapabilityRequirement.ExternalBridge -> when (req.type) {
                BridgeType.Shizuku -> runCatching {
                    rikka.shizuku.Shizuku.pingBinder() &&
                        rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                }.getOrDefault(false)
                else -> false
            }
            is CapabilityRequirement.Role -> false
            is CapabilityRequirement.MediaProjectionConsent -> false
            is CapabilityRequirement.VpnConsent -> false
        }
    }

    private fun String.humanizeCapabilityId(): String {
        // Convert "ExportConversation" → "Export Conversation"
        return this.replace(Regex("([a-z])([A-Z])")) { "${it.groupValues[1]} ${it.groupValues[2]}" }
            .replace(Regex("([A-Z])([A-Z][a-z])")) { "${it.groupValues[1]} ${it.groupValues[2]}" }
    }

    private fun readDeclaredPermissions(context: Context): List<String> {
        val pm = context.packageManager
        val info: PackageInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return emptyList()
        }
        return info.requestedPermissions?.toList() ?: emptyList()
    }

    private fun classify(context: Context, perm: String): Row? {
        val pm = context.packageManager
        val pkgUri: Uri = ("package:" + context.packageName).toUri()

        API_RANGES[perm]?.let { range ->
            if (Build.VERSION.SDK_INT < range.first || Build.VERSION.SDK_INT > range.last) {
                val requirement = when {
                    range.last < Int.MAX_VALUE -> context.getString(
                        R.string.perm_api_range_only,
                        range.first,
                        range.last,
                    )
                    else -> context.getString(R.string.perm_api_range_or_newer, range.first)
                }
                return Row(
                    id = perm,
                    label = labelOrHumanize(context, perm),
                    description = "$requirement ${descriptionOrDefault(context, perm)}",
                    status = Status.AUTO_GRANTED,
                    group = Group.AutoGranted,
                    grant = GrantAction.None,
                    statusLabel = context.getString(R.string.perm_status_not_applicable),
                )
            }
        }

        // Special-access permissions — each has its own canWrite / canDrawOverlays / etc check
        // and a deep-link Intent.
        when (perm) {
            Manifest.permission.SYSTEM_ALERT_WINDOW -> {
                val granted = Settings.canDrawOverlays(context)
                return Row(
                    id = perm,
                    label = context.getString(R.string.perm_display_over_other_apps_label),
                    description = context.getString(R.string.perm_display_over_other_apps_desc),
                    status = if (granted) Status.GRANTED else Status.DENIED,
                    group = Group.SpecialAccess,
                    grant = GrantAction.SystemSettings(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)
                    ),
                )
            }
            Manifest.permission.WRITE_SETTINGS -> {
                val granted = Settings.System.canWrite(context)
                return Row(
                    id = perm,
                    label = context.getString(R.string.perm_modify_system_settings_label),
                    description = context.getString(R.string.perm_modify_system_settings_desc),
                    status = if (granted) Status.GRANTED else Status.DENIED,
                    group = Group.SpecialAccess,
                    grant = GrantAction.SystemSettings(
                        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri)
                    ),
                )
            }
            Manifest.permission.ACCESS_NOTIFICATION_POLICY -> {
                val nm = context.getSystemService(NotificationManager::class.java)
                val granted = nm?.isNotificationPolicyAccessGranted == true
                return Row(
                    id = perm,
                    label = context.getString(R.string.perm_dnd_access_label),
                    description = context.getString(R.string.perm_dnd_access_desc),
                    status = if (granted) Status.GRANTED else Status.DENIED,
                    group = Group.SpecialAccess,
                    grant = GrantAction.SystemSettings(
                        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    ),
                )
            }
            Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS -> {
                val pwm = context.getSystemService(PowerManager::class.java)
                val granted = pwm?.isIgnoringBatteryOptimizations(context.packageName) == true
                return Row(
                    id = perm,
                    label = context.getString(R.string.perm_ignore_battery_optimizations_label),
                    description = context.getString(R.string.perm_ignore_battery_optimizations_desc),
                    status = if (granted) Status.GRANTED else Status.DENIED,
                    group = Group.SpecialAccess,
                    // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS pops a system dialog asking
                    // for the exemption directly — better UX than the long settings list.
                    grant = GrantAction.SystemSettings(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)
                    ),
                )
            }
            Manifest.permission.POST_NOTIFICATIONS -> {
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val granted = ContextCompat.checkSelfPermission(context, perm) ==
                        PackageManager.PERMISSION_GRANTED
                    Row(
                        id = perm,
                        label = context.getString(R.string.perm_post_notifications_label),
                        description = context.getString(R.string.perm_post_notifications_desc),
                        status = if (granted) Status.GRANTED else Status.DENIED,
                        group = Group.Runtime,
                        grant = GrantAction.Runtime(perm),
                    )
                } else {
                    autoRow(context, perm, context.getString(R.string.perm_post_notifications_label))
                }
            }
            Manifest.permission.PACKAGE_USAGE_STATS -> {
                val granted = PermissionHelper.hasUsageStatsAccess(context)
                return Row(
                    id = perm,
                    label = context.getString(R.string.perm_usage_access_label),
                    description = context.getString(R.string.perm_usage_access_desc),
                    status = if (granted) Status.GRANTED else Status.DENIED,
                    group = Group.SpecialAccess,
                    grant = GrantAction.SystemSettings(PermissionHelper.usageAccessIntent()),
                )
            }
            Manifest.permission.MANAGE_EXTERNAL_STORAGE -> {
                val granted = PermissionHelper.hasAllFilesAccess(context)
                return Row(
                    id = perm,
                    label = context.getString(R.string.perm_all_files_access_label),
                    description = context.getString(R.string.perm_all_files_access_desc),
                    status = if (granted) Status.GRANTED else Status.DENIED,
                    group = Group.SpecialAccess,
                    grant = GrantAction.SystemSettings(PermissionHelper.allFilesAccessIntent(context)),
                )
            }
            Manifest.permission.SCHEDULE_EXACT_ALARM -> {
                val granted = PermissionHelper.hasExactAlarmAccess(context)
                return Row(
                    id = perm,
                    label = context.getString(R.string.perm_exact_alarms_label),
                    description = context.getString(R.string.perm_exact_alarms_desc),
                    status = if (granted) Status.GRANTED else Status.DENIED,
                    group = Group.SpecialAccess,
                    grant = GrantAction.SystemSettings(PermissionHelper.exactAlarmIntent(context)),
                )
            }
            Manifest.permission.REQUEST_INSTALL_PACKAGES -> {
                val granted = PermissionHelper.canRequestPackageInstalls(context)
                return Row(
                    id = perm,
                    label = context.getString(R.string.perm_install_unknown_apps_label),
                    description = context.getString(R.string.perm_install_unknown_apps_desc),
                    status = if (granted) Status.GRANTED else Status.DENIED,
                    group = Group.SpecialAccess,
                    grant = GrantAction.SystemSettings(PermissionHelper.unknownAppSourcesIntent(context)),
                )
            }
            Manifest.permission.USE_FULL_SCREEN_INTENT -> {
                val granted = PermissionHelper.canUseFullScreenIntent(context)
                return Row(
                    id = perm,
                    label = context.getString(R.string.perm_full_screen_intent_access_label),
                    description = context.getString(R.string.perm_full_screen_intent_access_desc),
                    status = if (granted) Status.GRANTED else Status.DENIED,
                    group = Group.SpecialAccess,
                    grant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        GrantAction.SystemSettings(PermissionHelper.fullScreenIntentSettingsIntent(context))
                    } else {
                        GrantAction.None
                    },
                )
            }
        }

        // Generic classification: ask PackageManager about the protection level. Dangerous =>
        // runtime grant. Anything else (normal, signature, signatureOrSystem) is auto-granted
        // at install time and only listed for transparency.
        val info: PermissionInfo? = try {
            pm.getPermissionInfo(perm, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

        if (info == null) {
            // Unknown to this device — typically a custom perm declared by an app that isn't
            // installed (e.g. com.termux.permission.RUN_COMMAND when Termux isn't installed).
            // Best we can do is check checkSelfPermission and offer no grant flow.
            val granted = ContextCompat.checkSelfPermission(context, perm) ==
                PackageManager.PERMISSION_GRANTED
            return Row(
                id = perm,
                label = humanize(perm),
                description = context.getString(R.string.perm_custom_permission_desc),
                status = if (granted) Status.GRANTED else Status.DENIED,
                group = Group.Runtime,
                grant = GrantAction.Runtime(perm),
            )
        }

        val protectionBase = info.protection
        val isDangerous = protectionBase == PermissionInfo.PROTECTION_DANGEROUS
        val granted = ContextCompat.checkSelfPermission(context, perm) ==
            PackageManager.PERMISSION_GRANTED

        return if (isDangerous) {
            Row(
                id = perm,
                label = labelOrHumanize(context, perm),
                description = describeRuntime(context, perm),
                status = if (granted) Status.GRANTED else Status.DENIED,
                group = Group.Runtime,
                grant = GrantAction.Runtime(perm),
            )
        } else {
            autoRow(context, perm, labelOrHumanize(context, perm), descriptionOrDefault(context, perm))
        }
    }

    private fun autoRow(
        context: Context,
        perm: String,
        label: String,
        description: String = context.getString(R.string.perm_auto_granted_desc),
    ) = Row(
        id = perm,
        label = label,
        description = description,
        status = Status.AUTO_GRANTED,
        group = Group.AutoGranted,
        grant = GrantAction.None,
    )

    private fun accessibilityServiceRow(context: Context): Row {
        val component = ComponentName(context, RikkaAccessibilityService::class.java)
            .flattenToString()
        val enabled = (Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: "").split(":").any { it.equals(component, ignoreCase = true) }
        return Row(
            id = "rikkahub.SERVICE_ACCESSIBILITY",
            label = context.getString(R.string.perm_accessibility_label),
            description = context.getString(R.string.perm_accessibility_desc),
            status = if (enabled) Status.GRANTED else Status.DENIED,
            group = Group.ServicesAndIntegrations,
            grant = GrantAction.SystemSettings(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ),
        )
    }

    private fun notificationListenerRow(context: Context): Row {
        val component = ComponentName(context, RikkaNotificationListenerService::class.java)
            .flattenToString()
        val enabled = (Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: "").split(":").any { it.equals(component, ignoreCase = true) }
        return Row(
            id = "rikkahub.SERVICE_NOTIFICATION_LISTENER",
            label = context.getString(R.string.perm_notification_access_label),
            description = context.getString(R.string.perm_notification_access_desc),
            status = if (enabled) Status.GRANTED else Status.DENIED,
            group = Group.ServicesAndIntegrations,
            grant = GrantAction.SystemSettings(
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ),
        )
    }

    private fun deviceAdminRow(context: Context) = reservedIntegrationRow(
        context = context,
        id = "rikkahub.SERVICE_DEVICE_ADMIN",
        label = context.getString(R.string.perm_device_admin_label),
        description = context.getString(R.string.perm_device_admin_desc),
    )

    private fun vpnServiceRow(context: Context) = reservedIntegrationRow(
        context = context,
        id = "rikkahub.SERVICE_VPN",
        label = context.getString(R.string.perm_vpn_service_label),
        description = context.getString(R.string.perm_vpn_service_desc),
        grant = GrantAction.SystemSettings(PermissionHelper.vpnSettingsIntent()),
    )

    private fun mediaProjectionConsentRow(context: Context): Row {
        val supported = PermissionHelper.hasMediaProjectionCapability(context)
        return Row(
            id = "rikkahub.CONSENT_MEDIA_PROJECTION",
            label = context.getString(R.string.perm_screen_capture_consent_label),
            description = if (supported) {
                context.getString(R.string.perm_screen_capture_consent_desc_supported)
            } else {
                context.getString(R.string.perm_screen_capture_consent_desc_unavailable)
            },
            status = Status.AUTO_GRANTED,
            group = Group.ServicesAndIntegrations,
            grant = GrantAction.None,
            statusLabel = if (supported) context.getString(R.string.perm_status_per_use_consent) else context.getString(R.string.perm_status_not_applicable),
        )
    }

    private fun shizukuBridgeRow(context: Context): Row {
        val packageManager = context.packageManager
        val installed = listOf("moe.shizuku.privileged.api", "rikka.sui").any { packageName ->
            runCatching { packageManager.getApplicationInfo(packageName, 0) }.isSuccess
        }
        val binderAvailable = runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false)
        val authorized = binderAvailable && runCatching {
            rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val (status, statusLabel, description) = when {
            !installed -> Triple(Status.DENIED, context.getString(R.string.perm_status_not_installed), context.getString(R.string.perm_shizuku_desc_not_installed))
            !binderAvailable -> Triple(Status.DENIED, context.getString(R.string.perm_status_not_running), context.getString(R.string.perm_shizuku_desc_not_running))
            !authorized -> Triple(Status.DENIED, context.getString(R.string.perm_status_authorization_required), context.getString(R.string.perm_shizuku_desc_needs_authorization))
            else -> Triple(Status.GRANTED, context.getString(R.string.perm_status_authorized), context.getString(R.string.perm_shizuku_desc_authorized))
        }
        return Row(
            id = "rikkahub.EXTERNAL_BRIDGE_SHIZUKU",
            label = context.getString(R.string.perm_shizuku_bridge_label),
            description = description,
            status = status,
            group = Group.ServicesAndIntegrations,
            grant = GrantAction.None,
            statusLabel = statusLabel,
        )
    }

    private fun adbBridgeRow(context: Context) = reservedIntegrationRow(
        context = context,
        id = "rikkahub.EXTERNAL_BRIDGE_ADB",
        label = context.getString(R.string.perm_adb_bridge_label),
        description = context.getString(R.string.perm_adb_bridge_desc),
    )

    private fun reservedIntegrationRow(
        context: Context,
        id: String,
        label: String,
        description: String,
        grant: GrantAction = GrantAction.None,
    ) = Row(
        id = id,
        label = label,
        description = description,
        status = Status.AUTO_GRANTED,
        group = Group.ServicesAndIntegrations,
        grant = grant,
        statusLabel = context.getString(R.string.perm_status_reserved),
    )

    // -- Friendly labels for every dangerous permission we currently request ------------------

    private val LABELS = mapOf(
        Manifest.permission.CAMERA to R.string.perm_camera_label,
        Manifest.permission.RECORD_AUDIO to R.string.perm_microphone_label,
        Manifest.permission.READ_PHONE_STATE to R.string.perm_phone_state_label,
        Manifest.permission.ACCESS_FINE_LOCATION to R.string.perm_precise_location_label,
        Manifest.permission.ACCESS_COARSE_LOCATION to R.string.perm_approximate_location_label,
        Manifest.permission.READ_CONTACTS to R.string.perm_contacts_label,
        Manifest.permission.READ_CALL_LOG to R.string.perm_call_log_label,
        Manifest.permission.READ_SMS to R.string.perm_sms_label,
        Manifest.permission.SEND_SMS to R.string.perm_send_sms_label,
        Manifest.permission.POST_NOTIFICATIONS to R.string.perm_post_notifications_label,
        Manifest.permission.READ_MEDIA_IMAGES to R.string.perm_media_images_label,
        Manifest.permission.READ_MEDIA_VIDEO to R.string.perm_media_video_label,
        Manifest.permission.READ_MEDIA_AUDIO to R.string.perm_media_audio_label,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED to R.string.perm_media_selected_label,
        Manifest.permission.READ_EXTERNAL_STORAGE to R.string.perm_shared_storage_read_label,
        Manifest.permission.WRITE_EXTERNAL_STORAGE to R.string.perm_shared_storage_write_label,
        Manifest.permission.CALL_PHONE to R.string.perm_call_phone_label,
        Manifest.permission.BLUETOOTH_SCAN to R.string.perm_bluetooth_scan_label,
        Manifest.permission.BLUETOOTH_ADVERTISE to R.string.perm_bluetooth_advertise_label,
        Manifest.permission.NEARBY_WIFI_DEVICES to R.string.perm_nearby_wifi_devices_label,
        Manifest.permission.CHANGE_WIFI_STATE to R.string.perm_change_wifi_state_label,
        Manifest.permission.BLUETOOTH to R.string.perm_bluetooth_legacy_label,
        Manifest.permission.BLUETOOTH_ADMIN to R.string.perm_bluetooth_admin_legacy_label,
        Manifest.permission.ACTIVITY_RECOGNITION to R.string.perm_activity_recognition_label,
        Manifest.permission.BODY_SENSORS to R.string.perm_body_sensors_label,
        Manifest.permission.BODY_SENSORS_BACKGROUND to R.string.perm_body_sensors_background_label,
        Manifest.permission.HIGH_SAMPLING_RATE_SENSORS to R.string.perm_high_sampling_rate_sensors_label,
        Manifest.permission.REQUEST_INSTALL_PACKAGES to R.string.perm_install_unknown_apps_label,
        Manifest.permission.REQUEST_DELETE_PACKAGES to R.string.perm_request_app_uninstall_label,
        Manifest.permission.EXPAND_STATUS_BAR to R.string.perm_expand_status_bar_label,
        Manifest.permission.DISABLE_KEYGUARD to R.string.perm_dismiss_insecure_keyguard_label,
        Manifest.permission.SET_ALARM to R.string.perm_set_alarms_label,
        Manifest.permission.USE_FULL_SCREEN_INTENT to R.string.perm_full_screen_intent_label,
        "com.termux.permission.RUN_COMMAND" to R.string.perm_termux_run_command_label,
    )

    private val DESCRIPTIONS = mapOf(
        Manifest.permission.CAMERA to R.string.perm_camera_desc,
        Manifest.permission.RECORD_AUDIO to R.string.perm_microphone_desc,
        Manifest.permission.READ_PHONE_STATE to R.string.perm_phone_state_desc,
        Manifest.permission.ACCESS_FINE_LOCATION to R.string.perm_precise_location_desc,
        Manifest.permission.ACCESS_COARSE_LOCATION to R.string.perm_approximate_location_desc,
        Manifest.permission.READ_CONTACTS to R.string.perm_contacts_desc,
        Manifest.permission.READ_CALL_LOG to R.string.perm_call_log_desc,
        Manifest.permission.READ_SMS to R.string.perm_sms_inbox_desc,
        Manifest.permission.SEND_SMS to R.string.perm_send_sms_desc,
        Manifest.permission.READ_MEDIA_IMAGES to R.string.perm_media_images_desc,
        Manifest.permission.READ_MEDIA_VIDEO to R.string.perm_media_video_desc,
        Manifest.permission.READ_MEDIA_AUDIO to R.string.perm_media_audio_desc,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED to R.string.perm_media_selected_desc,
        Manifest.permission.READ_EXTERNAL_STORAGE to R.string.perm_shared_storage_read_desc,
        Manifest.permission.WRITE_EXTERNAL_STORAGE to R.string.perm_shared_storage_write_desc,
        Manifest.permission.CALL_PHONE to R.string.perm_call_phone_desc,
        Manifest.permission.BLUETOOTH_SCAN to R.string.perm_bluetooth_scan_desc,
        Manifest.permission.BLUETOOTH_ADVERTISE to R.string.perm_bluetooth_advertise_desc,
        Manifest.permission.NEARBY_WIFI_DEVICES to R.string.perm_nearby_wifi_devices_desc,
        Manifest.permission.CHANGE_WIFI_STATE to R.string.perm_change_wifi_state_desc,
        Manifest.permission.BLUETOOTH to R.string.perm_bluetooth_legacy_desc,
        Manifest.permission.BLUETOOTH_ADMIN to R.string.perm_bluetooth_admin_legacy_desc,
        Manifest.permission.ACTIVITY_RECOGNITION to R.string.perm_activity_recognition_desc,
        Manifest.permission.BODY_SENSORS to R.string.perm_body_sensors_desc,
        Manifest.permission.BODY_SENSORS_BACKGROUND to R.string.perm_body_sensors_background_desc,
        Manifest.permission.HIGH_SAMPLING_RATE_SENSORS to R.string.perm_high_sampling_rate_sensors_desc,
        Manifest.permission.REQUEST_INSTALL_PACKAGES to R.string.perm_request_install_packages_desc,
        Manifest.permission.REQUEST_DELETE_PACKAGES to R.string.perm_request_delete_packages_desc,
        Manifest.permission.EXPAND_STATUS_BAR to R.string.perm_expand_status_bar_desc,
        Manifest.permission.DISABLE_KEYGUARD to R.string.perm_disable_keyguard_desc,
        Manifest.permission.SET_ALARM to R.string.perm_set_alarm_desc,
        Manifest.permission.USE_FULL_SCREEN_INTENT to R.string.perm_use_full_screen_intent_desc,
        "com.termux.permission.RUN_COMMAND" to R.string.perm_termux_run_command_desc,
    )

    /** API interval where each versioned permission exists and can be meaningfully granted. */
    private val API_RANGES = mapOf(
        Manifest.permission.ACCESS_BACKGROUND_LOCATION to (Build.VERSION_CODES.Q..Int.MAX_VALUE),
        Manifest.permission.ACCESS_LOCAL_NETWORK to (37..Int.MAX_VALUE),
        Manifest.permission.POST_PROMOTED_NOTIFICATIONS to (36..Int.MAX_VALUE),
        Manifest.permission.POST_NOTIFICATIONS to (Build.VERSION_CODES.TIRAMISU..Int.MAX_VALUE),
        Manifest.permission.READ_MEDIA_IMAGES to (Build.VERSION_CODES.TIRAMISU..Int.MAX_VALUE),
        Manifest.permission.READ_MEDIA_VIDEO to (Build.VERSION_CODES.TIRAMISU..Int.MAX_VALUE),
        Manifest.permission.READ_MEDIA_AUDIO to (Build.VERSION_CODES.TIRAMISU..Int.MAX_VALUE),
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED to
            (Build.VERSION_CODES.UPSIDE_DOWN_CAKE..Int.MAX_VALUE),
        Manifest.permission.READ_EXTERNAL_STORAGE to (1..Build.VERSION_CODES.S_V2),
        Manifest.permission.WRITE_EXTERNAL_STORAGE to (1..Build.VERSION_CODES.P),
        Manifest.permission.BLUETOOTH_SCAN to (Build.VERSION_CODES.S..Int.MAX_VALUE),
        Manifest.permission.BLUETOOTH_ADVERTISE to (Build.VERSION_CODES.S..Int.MAX_VALUE),
        Manifest.permission.BLUETOOTH to (1..Build.VERSION_CODES.R),
        Manifest.permission.BLUETOOTH_ADMIN to (1..Build.VERSION_CODES.R),
        Manifest.permission.NEARBY_WIFI_DEVICES to (Build.VERSION_CODES.TIRAMISU..Int.MAX_VALUE),
        Manifest.permission.ACTIVITY_RECOGNITION to (Build.VERSION_CODES.Q..Int.MAX_VALUE),
        Manifest.permission.BODY_SENSORS_BACKGROUND to
            (Build.VERSION_CODES.TIRAMISU..Int.MAX_VALUE),
        Manifest.permission.HIGH_SAMPLING_RATE_SENSORS to
            (Build.VERSION_CODES.S..Int.MAX_VALUE),
        Manifest.permission.REQUEST_INSTALL_PACKAGES to (Build.VERSION_CODES.O..Int.MAX_VALUE),
        Manifest.permission.REQUEST_DELETE_PACKAGES to (Build.VERSION_CODES.O..Int.MAX_VALUE),
        Manifest.permission.SCHEDULE_EXACT_ALARM to (Build.VERSION_CODES.S..Int.MAX_VALUE),
        Manifest.permission.MANAGE_EXTERNAL_STORAGE to (Build.VERSION_CODES.R..Int.MAX_VALUE),
        Manifest.permission.USE_FULL_SCREEN_INTENT to (Build.VERSION_CODES.Q..Int.MAX_VALUE),
        Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE to
            (Build.VERSION_CODES.UPSIDE_DOWN_CAKE..Int.MAX_VALUE),
        Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK to
            (Build.VERSION_CODES.UPSIDE_DOWN_CAKE..Int.MAX_VALUE),
        Manifest.permission.FOREGROUND_SERVICE_CAMERA to
            (Build.VERSION_CODES.UPSIDE_DOWN_CAKE..Int.MAX_VALUE),
        Manifest.permission.FOREGROUND_SERVICE_MICROPHONE to
            (Build.VERSION_CODES.UPSIDE_DOWN_CAKE..Int.MAX_VALUE),
        Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC to
            (Build.VERSION_CODES.UPSIDE_DOWN_CAKE..Int.MAX_VALUE),
        Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE to
            (Build.VERSION_CODES.UPSIDE_DOWN_CAKE..Int.MAX_VALUE),
    )

    private fun labelOrHumanize(context: Context, perm: String): String =
        LABELS[perm]?.let { context.getString(it) } ?: humanize(perm)
    private fun describeRuntime(context: Context, perm: String) =
        DESCRIPTIONS[perm]?.let { context.getString(it) }
            ?: context.getString(R.string.perm_runtime_required_desc)
    private fun descriptionOrDefault(context: Context, perm: String) =
        DESCRIPTIONS[perm]?.let { context.getString(it) }
            ?: context.getString(R.string.perm_auto_granted_desc)

    private fun humanize(perm: String): String {
        val tail = perm.substringAfterLast('.')
        return tail.lowercase().split('_').joinToString(" ") {
            it.replaceFirstChar { c -> c.uppercase() }
        }
    }
}

private fun String.toUri(): Uri = Uri.parse(this)
