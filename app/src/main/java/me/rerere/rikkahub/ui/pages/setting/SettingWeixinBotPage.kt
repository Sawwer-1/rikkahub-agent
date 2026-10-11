/*
 * 微信 Bot 设置页 — 移植自橘瓣 OrangeChat (sue1231513/orangechat, AGPL v3) 的
 * SettingWeixinBotPage，按本 fork 既有设施适配：
 *  - 二维码渲染：fork 已有 ZXing 依赖与 QRCode 组件（ui/components/ui/QRCode.kt），
 *    直接复用组件（替代橘瓣自带的 renderQrCode 手绘实现），另保留"浏览器打开二维码
 *    链接"兜底（compose 组件在部分深色主题/低版本上扫描成功率不佳）。
 *  - 页面骨架照 SettingTelegramPage：Scaffold + LargeFlexibleTopAppBar + CustomColors +
 *    CardGroup + LocalToaster；配置走独立 WeixinBotPreferences（照 TelegramBotPreferences），
 *    助手列表走既有 SettingVM（koinViewModel）。
 *  - 开关：开启即拉起 WeixinBotService（需已登录，否则 toast 提示）；关闭即停服。
 *    enabled=true 时进程重启由 RikkaHubApp.onCreate 自动恢复。
 */
package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Message02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.weixin.WeixinBotClient
import me.rerere.rikkahub.data.weixin.WeixinBotConfig
import me.rerere.rikkahub.data.weixin.WeixinBotPreferences
import me.rerere.rikkahub.service.WeixinBotService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.QRCode
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingWeixinBotPage(
    vm: SettingVM = koinViewModel(),
    prefs: WeixinBotPreferences = koinInject(),
    client: WeixinBotClient = koinInject(),
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val cfg by prefs.flow.collectAsStateWithLifecycle(initialValue = WeixinBotConfig())
    val noTokenToast = stringResource(R.string.setting_page_weixin_no_token_toast)
    // 协程（scope.launch）里不能用 @Composable 的 stringResource，先在组合期取好
    val statusFetchingQr = stringResource(R.string.setting_page_weixin_fetching_qr)
    val statusScanHint = stringResource(R.string.setting_page_weixin_scan_hint)
    val statusLoginSuccess = stringResource(R.string.setting_page_weixin_login_success)
    val statusScaned = stringResource(R.string.setting_page_weixin_scaned)
    val statusQrExpiredGiveup = stringResource(R.string.setting_page_weixin_qr_expired_giveup)
    val statusQrExpiredRefresh = stringResource(R.string.setting_page_weixin_qr_expired_refresh)
    val statusScanWait = stringResource(R.string.setting_page_weixin_scan_wait)
    val statusLoginTimeout = stringResource(R.string.setting_page_weixin_login_timeout)
    val statusLoggedOut = stringResource(R.string.setting_page_weixin_logged_out)

    // 扫码登录状态
    var qrContent by remember { mutableStateOf<String?>(null) }
    var loginStatus by remember { mutableStateOf("") }
    var isLoggingIn by remember { mutableStateOf(false) }
    // 服务运行状态轮询（照 Telegram 页的 isRunning 展示）
    var serviceRunning by remember { mutableStateOf(WeixinBotService.isRunning) }
    LaunchedEffect(Unit) {
        while (true) {
            serviceRunning = WeixinBotService.isRunning
            delay(2000)
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun updateConfig(fn: (WeixinBotConfig) -> WeixinBotConfig) {
        scope.launch { prefs.update(fn) }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_weixin)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 说明
            item {
                CardGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                ) {
                    item(
                        leadingContent = { Icon(imageVector = HugeIcons.Message02, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.setting_page_weixin_what_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_weixin_what_desc)) },
                    )
                }
            }

            // 扫码登录
            item {
                CardGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_weixin_login_status)) },
                        supportingContent = {
                            Text(
                                if (cfg.botToken.isNotBlank()) {
                                    stringResource(
                                        R.string.setting_page_weixin_logged_in,
                                        cfg.botId.ifBlank { "?" },
                                    )
                                } else {
                                    stringResource(R.string.setting_page_weixin_not_logged_in)
                                }
                            )
                        },
                        trailingContent = {
                            FilledTonalButton(
                                enabled = !isLoggingIn,
                                onClick = {
                                    scope.launch {
                                        isLoggingIn = true
                                        try {
                                            loginStatus = statusFetchingQr
                                            val qr = client.getQrcode(cfg.baseUrl)
                                            qrContent = qr.qrcodeImgContent
                                            loginStatus = statusScanHint
                                            // 轮询扫码状态，最多 5 分钟；二维码过期自动刷新（≤3 次）
                                            val deadline = System.currentTimeMillis() + 5 * 60_000
                                            var currentQrcode = qr.qrcode
                                            var refreshCount = 0
                                            var confirmed = false
                                            while (System.currentTimeMillis() < deadline && !confirmed) {
                                                val st = client.getQrcodeStatus(currentQrcode, cfg.baseUrl)
                                                when (st.status) {
                                                    "confirmed" -> {
                                                        prefs.update {
                                                            it.copy(
                                                                botToken = st.botToken ?: "",
                                                                baseUrl = st.baseUrl ?: cfg.baseUrl,
                                                                botId = st.botId ?: "",
                                                            )
                                                        }
                                                        loginStatus = statusLoginSuccess
                                                        confirmed = true
                                                    }
                                                    "scaned" ->
                                                        loginStatus = statusScaned
                                                    "expired" -> {
                                                        refreshCount++
                                                        if (refreshCount > 3) {
                                                            loginStatus = statusQrExpiredGiveup
                                                            break
                                                        }
                                                        loginStatus = statusQrExpiredRefresh
                                                        val newQr = client.getQrcode(cfg.baseUrl)
                                                        currentQrcode = newQr.qrcode
                                                        qrContent = newQr.qrcodeImgContent
                                                    }
                                                    else ->
                                                        loginStatus = statusScanWait
                                                }
                                                delay(1000)
                                            }
                                            if (!confirmed && loginStatus == statusScanWait) {
                                                loginStatus = statusLoginTimeout
                                            }
                                            if (confirmed) qrContent = null
                                        } catch (e: Exception) {
                                            loginStatus = context.getString(
                                                R.string.setting_page_weixin_login_failed,
                                                e.message ?: e::class.simpleName ?: "error",
                                            )
                                        } finally {
                                            isLoggingIn = false
                                        }
                                    }
                                },
                            ) {
                                Text(
                                    when {
                                        isLoggingIn -> stringResource(R.string.setting_page_weixin_logging_in)
                                        cfg.botToken.isNotBlank() -> stringResource(R.string.setting_page_weixin_re_login)
                                        else -> stringResource(R.string.setting_page_weixin_scan_login)
                                    }
                                )
                            }
                        },
                    )
                    if (loginStatus.isNotBlank()) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_page_weixin_status)) },
                            supportingContent = { Text(loginStatus) },
                        )
                    }
                    if (cfg.botToken.isNotBlank()) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_page_weixin_logout)) },
                            trailingContent = {
                                FilledTonalButton(onClick = {
                                    WeixinBotService.stop(context)
                                    updateConfig { it.copy(botToken = "", botId = "") }
                                    loginStatus = statusLoggedOut
                                }) { Text(stringResource(R.string.setting_page_weixin_logout)) }
                            },
                        )
                    }
                }
            }

            // 二维码展示区（独立顶层 item，状态变化必可见）
            if (qrContent != null) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = loginStatus,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // fork 自带 QRCode 组件（ZXing）；白底黑码提高扫码成功率
                        QRCode(
                            value = qrContent.orEmpty(),
                            size = 480,
                            color = Color.Black,
                            backgroundColor = Color.White,
                            modifier = Modifier
                                .size(240.dp)
                                .background(Color.White, MaterialTheme.shapes.small)
                                .padding(8.dp),
                        )
                        val url = qrContent.orEmpty()
                        Text(
                            text = stringResource(R.string.setting_page_weixin_qr_fallback_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        FilledTonalButton(onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }
                        }) { Text(stringResource(R.string.setting_page_weixin_qr_browser_open)) }
                    }
                }
            }

            // 总开关
            item {
                CardGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_weixin_enable)) },
                        supportingContent = {
                            Text(stringResource(R.string.setting_page_weixin_enable_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = cfg.enabled,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        if (!cfg.hasCredential) {
                                            toaster.show(noTokenToast)
                                        } else {
                                            updateConfig { it.copy(enabled = true) }
                                            WeixinBotService.start(context)
                                        }
                                    } else {
                                        updateConfig { it.copy(enabled = false) }
                                        WeixinBotService.stop(context)
                                    }
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_weixin_service_status)) },
                        supportingContent = {
                            Text(
                                text = if (cfg.enabled && serviceRunning)
                                    stringResource(R.string.setting_page_weixin_status_running)
                                else if (cfg.enabled)
                                    stringResource(R.string.setting_page_weixin_status_starting)
                                else
                                    stringResource(R.string.setting_page_weixin_status_stopped),
                            )
                        },
                    )
                    if (cfg.enabled && !cfg.hasCredential) {
                        item(
                            headlineContent = {
                                Text(
                                    stringResource(R.string.setting_page_weixin_not_logged_in),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            supportingContent = {
                                Text(stringResource(R.string.setting_page_weixin_need_login_desc))
                            },
                        )
                    }
                }
            }

            // 关联助手
            item {
                AssistantDropdownCard(
                    settings = settings,
                    selectedAssistantId = cfg.assistantId,
                    onSelect = { assistantIdStr -> updateConfig { it.copy(assistantId = assistantIdStr) } },
                )
            }

            // 轮询间隔
            item {
                CardGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_weixin_poll_interval)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_weixin_poll_interval_desc)) },
                        trailingContent = {
                            me.rerere.rikkahub.ui.components.ui.OutlinedNumberInput(
                                value = cfg.pollIntervalSec,
                                onValueChange = { v -> updateConfig { it.copy(pollIntervalSec = v.coerceIn(1, 120)) } },
                                label = "s",
                                modifier = Modifier.size(width = 120.dp, height = 56.dp),
                            )
                        },
                    )
                }
            }
        }
    }
}

/**
 * 关联助手下拉（fork 多助手架构：AI/记忆/工具复用所选助手；留空跟随当前助手）.
 * 照 TTSProviderConfigure 的 ExposedDropdownMenuBox 写法（fork 的 material3 用
 * ExposedDropdownMenuAnchorType 枚举版 menuAnchor）。
 */
@Composable
private fun AssistantDropdownCard(
    settings: Settings,
    selectedAssistantId: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = if (selectedAssistantId == null) {
        stringResource(R.string.setting_page_weixin_assistant_follow_current)
    } else {
        settings.assistants.firstOrNull { it.id.toString() == selectedAssistantId }?.name
            ?: stringResource(R.string.setting_page_weixin_assistant_missing)
    }

    CardGroup(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = stringResource(R.string.setting_page_weixin_assistant),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.setting_page_weixin_assistant_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    OutlinedTextField(
                        value = selectedName,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.setting_page_weixin_assistant_follow_current)) },
                            onClick = {
                                onSelect(null)
                                expanded = false
                            },
                        )
                        settings.assistants.forEach { assistant ->
                            DropdownMenuItem(
                                text = { Text(assistant.name.ifBlank { assistant.id.toString() }) },
                                onClick = {
                                    onSelect(assistant.id.toString())
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
