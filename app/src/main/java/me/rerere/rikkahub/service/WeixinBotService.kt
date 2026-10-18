/*
 * 微信 Bot 前台长轮询服务 — 移植自橘瓣 OrangeChat (sue1231513/orangechat, AGPL v3) 的
 * WeixinBotService，并按本 fork 的既有设施重写 AI 调用面：
 *  - 会话创建/复用：照 TelegramBotService.lookupOrCreateConversation（Conversation.ofId 绑定
 *    配置的 assistantId；fork 多助手架构下 ChatService 准入按持久化会话的 assistantId 校验）。
 *  - 入队：chatService.submitUserMessage（返回 SubmitResult，比橘瓣的 fire-and-forget
 *    sendMessage 多一层队列/运行时拒绝反馈），origin=EXTERNAL_AUTOMATION。
 *    不用 CommandOrigin.TELEGRAM——新增 WEIXIN 枚举会波及 ToolCallOrigin/SubjectType 等
 *    安全核心的多处穷尽 when，v1 走既有 EXTERNAL_AUTOMATION 通道。
 *  - 工具审批：微信通道没有审批 UI，HeadlessConversations.mark()（完全 headless：工具自动
 *    批准），与 cron/子代理通道同策略；橘瓣原版没有处理这一点，待审批工具会卡死整轮。
 *  - 等待生成：awaitGenerationTerminal（fork 的 GenerationAwait 助手）。
 *  - 生命周期军规照 TelegramBotService：onCreate 不抢 startForeground（Android 12+ 无
 *    前景票会炸），onStartCommand 提升；START_NOT_STICKY（复活路径靠 RikkaHubApp.onCreate
 *    的 startWeixinBotIfEnabled，不裸复活）。
 *  - 主动消息出口：外部生成完成泵（generationDoneFlow 监听，照 TelegramBotService.listen
 *    ForExternalGenerationCompletions）——心跳/cron/子代理在微信映射会话里完成的生成，
 *    把助手文本推回微信。iLink 协议发消息必须带 context_token（来自该用户最近一条入站
 *    消息），故仅在缓存可得时推送。
 *
 * 保险库取舍：botToken 是扫码登录时服务器下发的机器凭证，SecondUserSecretVault.storeForUser
 * 要求 BIOMETRIC_STRONG 用户授权（SecretVaultUserAuthorization 构造器 internal），结构上
 * 只收用户手输的密钥；token 存应用私有 DataStore（与 Telegram 桥 token 同边界），
 * WeixinBotConfig.vaultSlotId 预留。
 */
package me.rerere.rikkahub.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.ConversationSystemAddendum
import me.rerere.rikkahub.data.ai.tools.HeadlessConversations
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.weixin.WeixinBotClient
import me.rerere.rikkahub.data.weixin.WeixinBotPreferences
import me.rerere.rikkahub.data.weixin.WeixinMessageType
import me.rerere.rikkahub.data.weixin.extractInboundText
import me.rerere.rikkahub.service.chat.CommandOrigin
import me.rerere.rikkahub.service.chat.SubmitResult
import org.koin.android.ext.android.inject
import kotlin.uuid.Uuid

class WeixinBotService : Service() {

    private val prefs: WeixinBotPreferences by inject()
    private val client: WeixinBotClient by inject()
    private val chatService: ChatService by inject()
    private val conversationRepo: ConversationRepository by inject()
    private val settingsStore: SettingsStore by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var externalGenPumpJob: Job? = null

    /**
     * 每个微信用户的串行锁：同一用户的两次 AI 回合不得交错（照 TelegramBotService 的
     * chatMutexes）。入站轮询即时返回，每条消息在自己的协程里处理。
     */
    private val userMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private fun mutexFor(userId: String): Mutex =
        userMutexes.getOrPut(userId) { Mutex() }

    /** handleInbound 正在驱动（或刚驱动完 5s 内）的会话——外部泵跳过，避免重复推送。 */
    private val activeHandleIncomingConvs =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Uuid, Boolean>())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 军规照 TelegramBotService：onCreate 不调 startForeground——Android 12+ 在自动复活
        // （START_STICKY/进程重启）时会抛 ForegroundServiceStartNotAllowedException；
        // 只在用户显式 startForegroundService 的 onStartCommand 里提升。
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startInForeground()
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "startForeground failed; service will not run", e)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (pollJob?.isActive != true) {
            pollJob = scope.launch { pollLoop() }
        }
        // 外部生成完成泵（主动消息出口）——guarded，onStartCommand 会被多次调用。
        if (externalGenPumpJob?.isActive != true) {
            externalGenPumpJob = scope.launch { listenForExternalGenerationCompletions() }
        }
        isRunning = true
        // START_NOT_STICKY：裸复活没有前景票，必炸；重启路径由 RikkaHubApp.onCreate 覆盖。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }

    // ==================== 长轮询主循环 ====================

    private suspend fun pollLoop() {
        var getUpdatesBuf = ""
        android.util.Log.i(TAG, "poll loop started")
        while (true) {
            try {
                val cfg = prefs.current()
                if (!cfg.isUsable) {
                    android.util.Log.w(TAG, "disabled or no token, stopping service")
                    stopSelf()
                    return
                }

                val result = client.getUpdates(
                    token = cfg.botToken,
                    baseUrl = cfg.baseUrl,
                    getUpdatesBuf = getUpdatesBuf,
                )
                // 更新游标（服务器下发，下次带上，防重复收消息）
                getUpdatesBuf = result.getUpdatesBuf

                for (msg in result.msgs) {
                    // 只处理用户发给 bot 的消息（message_type=1）
                    val msgType = msg["message_type"]?.jsonPrimitive?.content?.toIntOrNull()
                    if (msgType != WeixinMessageType.INBOUND) continue

                    val fromUserId = msg["from_user_id"]?.jsonPrimitive?.content
                    val contextToken = msg["context_token"]?.jsonPrimitive?.content
                    if (fromUserId.isNullOrBlank()) {
                        android.util.Log.w(TAG, "inbound msg missing from_user_id, skip")
                        continue
                    }
                    if (!contextToken.isNullOrBlank()) {
                        prefs.rememberContextToken(fromUserId, contextToken)
                    }

                    val text = extractInboundText(msg.jsonObject)
                    android.util.Log.i(TAG, "inbound from=$fromUserId text=${text.take(80)}")

                    val token = cfg.botToken
                    val baseUrl = cfg.baseUrl
                    val ctx = contextToken
                    scope.launch {
                        mutexFor(fromUserId).withLock {
                            handleInbound(fromUserId, text, token, baseUrl, ctx)
                        }
                    }
                }

                // 长轮询本身 hold 最多 35s；pollIntervalSec 是两轮之间的额外间隔
                delay(cfg.pollIntervalSec.coerceAtLeast(1) * 1000L)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                android.util.Log.e(TAG, "poll error: $msg", e)
                // 会话过期：停服 + 提醒重扫（对应 bridge 的 session timeout / -14 / 401）
                if (msg.contains("session timeout", ignoreCase = true) ||
                    msg.contains("-14") || msg.contains("401")
                ) {
                    android.util.Log.w(TAG, "token expired/invalid, stopping service — user must re-login")
                    onTokenInvalid()
                    stopSelf()
                    return
                }
                // 其它错误（网络/临时）：静默等待后重连
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    // ==================== 入站消息 → AI → 回复 ====================

    private suspend fun handleInbound(
        fromUserId: String,
        text: String,
        token: String,
        baseUrl: String,
        contextToken: String?,
    ) {
        val (convId, wasCreated) = lookupOrCreateConversation(fromUserId)
        android.util.Log.i(
            TAG,
            "handleInbound: routing to conv=$convId wasCreated=$wasCreated textChars=${text.length}",
        )
        if (contextToken.isNullOrBlank()) {
            sendTextOrLog(token, baseUrl, fromUserId, getString(R.string.weixin_bot_no_context_hint))
            return
        }

        // 微信通道没有审批 UI：完全 headless（工具自动批准），与 cron/子代理同策略。
        HeadlessConversations.mark(convId)
        ConversationSystemAddendum.set(convId, WECHAT_ADDENDUM)
        chatService.addConversationReference(convId)
        try {
            // 进程重启后 session map 可能是空的：先从 DB 水合，否则 baseline=0 且
            // saveConversation 会用空会话覆盖持久化历史（静默丢数据）。
            // 不用 initializeConversation——它会把全局"当前助手"切到本会话助手。
            chatService.ensureHydrated(convId)
            val baseline = selectedMessagesOf(convId).size
            when (val submission = chatService.submitUserMessage(
                conversationId = convId,
                content = listOf(UIMessagePart.Text(text)),
                origin = CommandOrigin.EXTERNAL_AUTOMATION,
            )) {
                is SubmitResult.QueueFull ->
                    { sendTextOrLog(token, baseUrl, fromUserId, getString(R.string.weixin_bot_queue_full, submission.limit)); return }
                is SubmitResult.Rejected ->
                    { sendTextOrLog(token, baseUrl, fromUserId, getString(R.string.weixin_bot_rejected, submission.reason)); return }
                is SubmitResult.RuntimeUnavailable ->
                    { sendTextOrLog(token, baseUrl, fromUserId, getString(R.string.weixin_bot_runtime_unavailable, submission.reason)); return }
                is SubmitResult.Accepted -> Unit
            }

            val finished = awaitGenerationTerminal(
                generationJobs = chatService.getGenerationJobStateFlow(convId),
                timeoutMs = REPLY_TIMEOUT_MS,
            )
            val reply = if (finished) harvestReplyAfter(convId, baseline)
            else getString(R.string.weixin_bot_timeout)
            sendTextOrLog(token, baseUrl, fromUserId, reply)
            android.util.Log.i(TAG, "replied to=$fromUserId len=${reply.length}")
        } finally {
            chatService.removeConversationReference(convId)
            HeadlessConversations.unmark(convId)
            // 外部泵的 collect 协程可能晚于本回合收尾才处理 generationDoneFlow 事件，
            // 延迟 5s 再摘牌，避免把本回合回复重复推一遍（简化版 Telegram linger）。
            scope.launch {
                delay(ACTIVE_INCOMING_LINGER_MS)
                activeHandleIncomingConvs.remove(convId)
            }
        }
    }

    /**
     * 找到/创建微信用户对应的会话. 优先复用映射里已有且仍存在的会话；
     * 新建时绑定配置的 assistantId（留空用当前助手）——fork 多助手架构下
     * ChatService 准入按持久化会话的 assistantId 校验与解析助手.
     */
    private suspend fun lookupOrCreateConversation(fromUserId: String): Pair<Uuid, Boolean> {
        val existing = prefs.conversationIdFor(fromUserId)
        if (existing != null && conversationRepo.existsConversationById(existing)) {
            activeHandleIncomingConvs.add(existing)
            return existing to false
        }
        val cfg = prefs.current()
        val assistantUuid = cfg.assistantId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: settingsStore.settingsFlow.first().getCurrentAssistant().id
        val convId = Uuid.random()
        val conv = Conversation.ofId(
            id = convId,
            assistantId = assistantUuid,
            newConversation = true,
        ).copy(title = "[WeChat $fromUserId]")
        conversationRepo.insertConversation(conv)
        prefs.rememberChatMapping(fromUserId, convId)
        activeHandleIncomingConvs.add(convId)
        return convId to true
    }

    /** 会话当前选中消息流（照外部泵的 messageNodes+selectIndex 取法，fork 验证过的收割路径）. */
    private fun selectedMessagesOf(convId: Uuid) =
        chatService.getConversationFlow(convId).value.messageNodes
            .mapNotNull { it.messages.getOrNull(it.selectIndex) }

    /** 取 baseline 之后最后一条助手消息的文本；空则给占位符. */
    private fun harvestReplyAfter(convId: Uuid, baseline: Int): String {
        val after = runCatching { selectedMessagesOf(convId).drop(baseline) }
            .getOrElse { selectedMessagesOf(convId) }
        val text = after.lastOrNull { it.role.name.equals("assistant", ignoreCase = true) }
            ?.parts
            ?.filterIsInstance<UIMessagePart.Text>()
            ?.joinToString("\n") { it.text }
            ?.trim()
            .orEmpty()
        return text.ifBlank { getString(R.string.weixin_bot_empty_reply) }
    }

    private suspend fun sendTextOrLog(token: String, baseUrl: String, toUserId: String, text: String) {
        val ctx = prefs.peekContextToken(toUserId)
        if (ctx == null) {
            android.util.Log.w(TAG, "no cached context_token for $toUserId, cannot send")
            return
        }
        // B05 / R17：iLink 文本消息有长度上限，超长会整条被拒（用户侧表现为「发出去了但对方没收到」）。
        // 按配置阈值分段顺序发送，任一段失败即停止（后面的段再发也没有上下文意义）。
        val limit = runCatching { prefs.current().replyChunkChars }
            .getOrDefault(me.rerere.rikkahub.data.weixin.DEFAULT_WEIXIN_REPLY_CHUNK_CHARS)
        val chunks = me.rerere.rikkahub.data.weixin.splitWeixinReply(text, limit)
        if (chunks.size > 1) {
            android.util.Log.i(TAG, "sendTextOrLog: splitting ${text.length} chars into ${chunks.size} chunks (limit=$limit)")
        }
        var sent = 0
        for (chunk in chunks) {
            val ok = runCatching {
                client.sendTextMessage(
                    token = token,
                    baseUrl = baseUrl,
                    toUserId = toUserId,
                    text = chunk,
                    contextToken = ctx,
                )
            }.onFailure {
                android.util.Log.w(TAG, "sendTextMessage failed for $toUserId (chunk ${sent + 1}/${chunks.size})", it)
            }.isSuccess
            if (!ok) break
            sent++
            if (sent < chunks.size) kotlinx.coroutines.delay(150)
        }
        if (sent < chunks.size) {
            android.util.Log.w(TAG, "sendTextOrLog: only $sent/${chunks.size} chunks delivered to $toUserId")
        }
    }

    // ==================== 主动消息出口（外部生成完成泵） ====================

    /**
     * 心跳/cron/子代理等外部路径在微信映射会话里完成的生成，把助手文本推回微信.
     * iLink 发消息必须有 context_token（缓存自最近入站消息），无缓存则跳过.
     */
    private suspend fun listenForExternalGenerationCompletions() {
        chatService.generationDoneFlow.collect { convId ->
            if (convId in activeHandleIncomingConvs) return@collect
            val cfg = runCatching { prefs.current() }.getOrNull() ?: return@collect
            // 反查 conv -> 微信用户
            val fromUserId = cfg.chatMap.entries
                .firstOrNull { runCatching { Uuid.parse(it.value) }.getOrNull() == convId }
                ?.key ?: return@collect

            val text = runCatching {
                selectedMessagesOf(convId)
                    .lastOrNull { it.role.name.equals("assistant", ignoreCase = true) }
                    ?.parts
                    ?.filterIsInstance<UIMessagePart.Text>()
                    ?.joinToString("\n") { it.text }
                    ?.trim()
                    .orEmpty()
            }.getOrDefault("")
            if (text.isBlank()) {
                android.util.Log.i(TAG, "external-gen-pump: convId=$convId empty assistant text, skipping")
                return@collect
            }
            if (prefs.peekContextToken(fromUserId) == null) {
                android.util.Log.i(TAG, "external-gen-pump: no context_token for $fromUserId, skipping proactive push")
                return@collect
            }
            runCatching {
                sendTextOrLog(cfg.botToken, cfg.baseUrl, fromUserId, text)
                android.util.Log.i(TAG, "external-gen-pump: pushed ${text.length} chars to wechat user $fromUserId")
            }.onFailure {
                android.util.Log.w(TAG, "external-gen-pump: send failed for convId=$convId", it)
            }
        }
    }

    // ==================== 前台通知 ====================

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID,
                getString(R.string.weixin_bot_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ))
        }
        val notif = buildForegroundNotification(getString(R.string.weixin_bot_running))
        // Android 14+ 的 SPECIAL_USE 无 6 小时/日配额（DATA_SYNC 有），与 TelegramBotService 同选择
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildForegroundNotification(body: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.weixin_bot_notification_title))
            .setContentText(body)
            .setSmallIcon(R.drawable.small_icon)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    /** token 失效：翻转开关 + 高优通知（独立通道，静音通道之外必须让人看见）. */
    private suspend fun onTokenInvalid() {
        runCatching { prefs.update { it.copy(enabled = false) } }
        try {
            val nm = applicationContext.getSystemService(NotificationManager::class.java)
            val channelId = "rikkahub_weixin_token_invalid"
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(NotificationChannel(
                    channelId,
                    getString(R.string.weixin_bot_error_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ))
            }
            nm.notify(
                TOKEN_INVALID_NOTIF_ID,
                NotificationCompat.Builder(this, channelId)
                    .setContentTitle(getString(R.string.weixin_bot_disconnected_title))
                    .setContentText(getString(R.string.weixin_bot_disconnected_body))
                    .setSmallIcon(R.drawable.small_icon)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build(),
            )
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "WeixinBotService"
        private const val CHANNEL_ID = "weixin_bot"
        private const val NOTIF_ID = 20011
        private const val TOKEN_INVALID_NOTIF_ID = 20012
        private const val REPLY_TIMEOUT_MS = 180_000L
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val ACTIVE_INCOMING_LINGER_MS = 5_000L

        /** 注入到每轮微信会话的系统附言：告知模型回复会被原样发回微信，约束格式. */
        private const val WECHAT_ADDENDUM =
            "[Channel] This conversation is bridged to WeChat (iLink bot). The user is texting " +
                "from WeChat; your reply text is sent back verbatim as a WeChat text message. " +
                "Avoid markdown tables, images and other rich formatting — plain short text works best."

        /** Set whenever the service is alive AND its long-poll loop is running. */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, WeixinBotService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WeixinBotService::class.java))
        }
    }
}
