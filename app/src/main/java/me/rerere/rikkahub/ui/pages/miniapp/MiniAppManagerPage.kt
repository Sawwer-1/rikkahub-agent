package me.rerere.rikkahub.ui.pages.miniapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.Rocket01
import me.rerere.hugeicons.stroke.WebDesign01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.MiniApp
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.MiniAppIcon
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

/**
 * Mini App manager: user-added web pages opened in the in-app WebView from the chat input
 * sheet. Telegram-style "add any URL as a chat mini app", stored in the settings JSON store.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniAppManagerPage(
    settingsStore: SettingsStore = koinInject(),
    // Persistence rides the app scope so deletes/reorders survive leaving this screen.
    appScope: AppScope = koinInject(),
) {
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val apps = remember(settings.miniApps) { settings.miniApps.sortedBy { it.order } }
    var pendingDelete by remember { mutableStateOf<MiniApp?>(null) }

    if (pendingDelete != null) {
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.mini_app_delete_title),
            confirmText = stringResource(R.string.delete),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                val target = pendingDelete
                pendingDelete = null
                if (target != null) {
                    appScope.launch {
                        settingsStore.update { current ->
                            current.copy(miniApps = current.miniApps.filterNot { it.id == target.id })
                        }
                    }
                }
            },
            onDismiss = { pendingDelete = null },
            text = {
                Text(stringResource(R.string.mini_app_delete_body, pendingDelete?.name.orEmpty()))
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.mini_app_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { navController.navigate(Screen.MiniAppEdit()) }) {
                Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.mini_app_add))
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (apps.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = HugeIcons.WebDesign01,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.mini_app_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    text = stringResource(R.string.mini_app_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(apps, key = { it.id }) { app ->
                    MiniAppCard(
                        app = app,
                        canMoveUp = app.id != apps.first().id,
                        canMoveDown = app.id != apps.last().id,
                        onOpen = { navController.navigate(Screen.WebView(url = app.url)) },
                        onEdit = { navController.navigate(Screen.MiniAppEdit(id = app.id.toString())) },
                        onMoveUp = {
                            appScope.launch { reorderMiniApps(settingsStore, app.id, -1) }
                        },
                        onMoveDown = {
                            appScope.launch { reorderMiniApps(settingsStore, app.id, +1) }
                        },
                        onDelete = { pendingDelete = app },
                    )
                }
            }
        }
    }
}

private suspend fun reorderMiniApps(settingsStore: SettingsStore, id: kotlin.uuid.Uuid, offset: Int) {
    settingsStore.update { current ->
        val sorted = current.miniApps.sortedBy { it.order }
        val index = sorted.indexOfFirst { it.id == id }
        val target = index + offset
        if (index < 0 || target < 0 || target >= sorted.size) return@update current
        val mutable = sorted.toMutableList()
        val item = mutable.removeAt(index)
        mutable.add(target, item)
        current.copy(miniApps = mutable.mapIndexed { i, app -> app.copy(order = i) })
    }
}

@Composable
private fun MiniAppCard(
    app: MiniApp,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MiniAppIcon(
                app = app,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = app.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (app.description.isNotBlank()) {
                    Text(
                        text = app.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onOpen) {
                Icon(
                    imageVector = HugeIcons.Rocket01,
                    contentDescription = stringResource(R.string.mini_app_open),
                )
            }
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = HugeIcons.Edit01,
                    contentDescription = stringResource(R.string.mini_app_edit),
                )
            }
            Column {
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(
                        imageVector = HugeIcons.ArrowUp01,
                        contentDescription = stringResource(R.string.mini_app_move_up),
                    )
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(
                        imageVector = HugeIcons.ArrowDown01,
                        contentDescription = stringResource(R.string.mini_app_move_down),
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = HugeIcons.Delete01,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
