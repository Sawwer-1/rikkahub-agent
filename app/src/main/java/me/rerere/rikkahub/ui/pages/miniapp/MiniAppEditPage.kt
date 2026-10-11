package me.rerere.rikkahub.ui.pages.miniapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Rocket01
import me.rerere.hugeicons.stroke.WebDesign01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.MiniApp
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.MiniAppIcon
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * Add / edit one chat mini app. Persisted to the settings JSON store; the entry surfaces in
 * the chat input sheet and opens in the in-app WebView.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniAppEditPage(
    id: String?,
    settingsStore: SettingsStore = koinInject(),
    // Persistence goes on the app scope: a save that pops the page must not be cancelled
    // when this composition leaves the navigation back stack.
    appScope: AppScope = koinInject(),
) {
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val existing = remember(id, settings.miniApps) {
        id?.let { raw ->
            runCatching { Uuid.parse(raw) }.getOrNull()?.let { parsed ->
                settings.miniApps.find { it.id == parsed }
            }
        }
    }

    var name by remember(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var url by remember(existing?.id) { mutableStateOf(existing?.url ?: "") }
    var icon by remember(existing?.id) { mutableStateOf(existing?.icon ?: "") }
    var description by remember(existing?.id) { mutableStateOf(existing?.description ?: "") }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val isEditing = existing != null
    val canSave = name.isNotBlank() && url.isNotBlank()

    if (showDeleteDialog) {
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.mini_app_delete_title),
            confirmText = stringResource(R.string.delete),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                val target = existing
                showDeleteDialog = false
                if (target != null) {
                    appScope.launch {
                        settingsStore.update { current ->
                            current.copy(miniApps = current.miniApps.filterNot { it.id == target.id })
                        }
                    }
                    navController.popBackStack()
                }
            },
            onDismiss = { showDeleteDialog = false },
            text = {
                Text(stringResource(R.string.mini_app_delete_body, existing?.name.orEmpty()))
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(
                            if (isEditing) R.string.mini_app_edit_title else R.string.mini_app_add_title,
                        )
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (isEditing) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.delete),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (name.isNotBlank() && url.isNotBlank()) {
                MiniAppIcon(
                    app = MiniApp(name = name, url = url, icon = icon),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp),
                )
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.mini_app_name_label)) },
                placeholder = { Text(stringResource(R.string.mini_app_name_hint)) },
                singleLine = true,
                leadingIcon = { Icon(HugeIcons.Rocket01, contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.mini_app_url_label)) },
                placeholder = { Text("https://example.com") },
                singleLine = true,
                leadingIcon = { Icon(HugeIcons.WebDesign01, contentDescription = null) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
            )
            OutlinedTextField(
                value = icon,
                onValueChange = { icon = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.mini_app_icon_label)) },
                placeholder = { Text("https://example.com/icon.png") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.mini_app_desc_label)) },
                minLines = 2,
            )
            Button(
                onClick = {
                    if (!canSave) return@Button
                    val normalizedUrl = normalizeMiniAppUrl(url)
                    val normalizedIcon = icon.trim()
                    appScope.launch {
                        settingsStore.update { current ->
                            if (existing != null) {
                                current.copy(
                                    miniApps = current.miniApps.map { app ->
                                        if (app.id == existing.id) {
                                            app.copy(
                                                name = name.trim(),
                                                url = normalizedUrl,
                                                icon = normalizedIcon,
                                                description = description.trim(),
                                            )
                                        } else {
                                            app
                                        }
                                    },
                                )
                            } else {
                                current.copy(
                                    miniApps = current.miniApps + MiniApp(
                                        name = name.trim(),
                                        url = normalizedUrl,
                                        icon = normalizedIcon,
                                        description = description.trim(),
                                        order = current.miniApps.size,
                                    ),
                                )
                            }
                        }
                    }
                    navController.popBackStack()
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(
                        if (isEditing) R.string.mini_app_save else R.string.mini_app_add,
                    )
                )
            }
        }
    }
}

/** Blank-safe URL normalization: prepend https:// when no scheme is present. */
internal fun normalizeMiniAppUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return trimmed
    return if (trimmed.contains("://")) trimmed else "https://$trimmed"
}
