/*
 * 微信 Bot (iLink) 持久化配置 — 照 fork 既有 TelegramBotPreferences 模式：
 * 独立 DataStore（preferencesDataStore），token 存应用私有存储，
 * vaultSlotId 字段预留（见 WeixinBotService 头注的保险库取舍说明）。
 */
package me.rerere.rikkahub.data.weixin

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val Context.weixinDataStore by preferencesDataStore(name = "weixin_bot")

/** 每个微信号（from_user_id）最多缓存这么多最近 context_token，供主动消息复用。 */
private const val MAX_CONTEXT_TOKENS = 32

/** from_user_id -> conversationId 映射上限，防止长期使用后无限膨胀。 */
private const val MAX_CHAT_MAPPINGS = 64

/**
 * 微信 Bot (iLink 协议) 配置.
 *
 * 设计理念（承橘瓣）：微信 bot 不是新功能，而是给某个已有助手多开一条"微信消息通道"。
 * 只配 assistantId，AI/记忆/工具全部复用那个助手。
 *
 * 字段:
 *  - [enabled]: 总开关. 开启后由 RikkaHubApp.onCreate 自动拉起 WeixinBotService 长轮询.
 *  - [assistantId]: 关联助手的 UUID 字符串. null = 用当前助手 (settings.getCurrentAssistant()).
 *  - [botToken]: 扫码登录后从 iLink 服务器下发的 Bearer token. 机器取得（非用户手输），
 *    存应用私有 DataStore——与 fork Telegram 桥的 token 存储同一边界；保险库（SecondUserSecretVault）
 *    的写入路径要求 BIOMETRIC_STRONG 用户授权且构造器 internal，结构上不收机器 token，
 *    故 vaultSlotId 仅作预留位。
 *  - [baseUrl]: iLink 服务器地址, 扫码确认时服务器可能下发不同的 baseurl.
 *  - [botId]: 本机微信号对应的 ilink_bot_id (登录后下发), 仅用于显示/标识.
 *  - [pollIntervalSec]: 两次长轮询之间的间隔 (秒). 长轮询本身会 hold 最多 35s.
 *  - [chatMap]: from_user_id -> conversationId 映射（持久化，进程重启后不丢会话）.
 */
data class WeixinBotConfig(
    val enabled: Boolean = false,
    val assistantId: String? = null,
    val botToken: String = "",
    val vaultSlotId: String? = null,
    val baseUrl: String = WeixinBotClient.DEFAULT_BASE_URL,
    val botId: String = "",
    val pollIntervalSec: Int = 1,
    /**
     * 单条回复的最大字符数（B05 / R17）。iLink 的文本消息有长度上限，超长会被整条拒掉，
     * 长回复必须按这个阈值分段发送。默认 1800，可在设置页调整（200..4000）。
     */
    val replyChunkChars: Int = DEFAULT_WEIXIN_REPLY_CHUNK_CHARS,
    val chatMap: Map<String, String> = emptyMap(),
) {
    val hasCredential: Boolean get() = botToken.isNotBlank()
    val isUsable: Boolean get() = hasCredential && enabled
}

class WeixinBotPreferences(private val context: Context) {
    private val store = context.weixinDataStore

    private val K_ENABLED = booleanPreferencesKey("enabled")
    private val K_ASSISTANT_ID = stringPreferencesKey("assistant_id")
    private val K_BOT_TOKEN = stringPreferencesKey("bot_token")
    private val K_VAULT_SLOT_ID = stringPreferencesKey("vault_slot_id")
    private val K_BASE_URL = stringPreferencesKey("base_url")
    private val K_BOT_ID = stringPreferencesKey("bot_id")
    private val K_POLL_INTERVAL = intPreferencesKey("poll_interval_sec")
    private val K_REPLY_CHUNK_CHARS = intPreferencesKey("reply_chunk_chars")
    private val K_CHAT_MAP = stringPreferencesKey("chat_map")

    private val json = Json { ignoreUnknownKeys = true }

    val flow = store.data.map { p -> readConfig(p) }

    suspend fun current(): WeixinBotConfig = flow.first()

    suspend fun update(fn: (WeixinBotConfig) -> WeixinBotConfig) {
        store.edit { p ->
            val next = fn(readConfig(p))
            p[K_ENABLED] = next.enabled
            if (next.assistantId != null) p[K_ASSISTANT_ID] = next.assistantId
            else p.remove(K_ASSISTANT_ID)
            p[K_BOT_TOKEN] = next.botToken
            if (next.vaultSlotId != null) p[K_VAULT_SLOT_ID] = next.vaultSlotId
            else p.remove(K_VAULT_SLOT_ID)
            p[K_BASE_URL] = next.baseUrl
            p[K_BOT_ID] = next.botId
            p[K_POLL_INTERVAL] = next.pollIntervalSec.coerceIn(1, 120)
            p[K_REPLY_CHUNK_CHARS] = next.replyChunkChars
                .coerceIn(MIN_WEIXIN_REPLY_CHUNK_CHARS, MAX_WEIXIN_REPLY_CHUNK_CHARS)
            if (next.chatMap.isNotEmpty()) p[K_CHAT_MAP] = serializeChatMap(next.chatMap)
            else p.remove(K_CHAT_MAP)
        }
    }

    private fun readConfig(p: androidx.datastore.preferences.core.Preferences): WeixinBotConfig =
        WeixinBotConfig(
            enabled = p[K_ENABLED] == true,
            assistantId = p[K_ASSISTANT_ID],
            botToken = p[K_BOT_TOKEN].orEmpty(),
            vaultSlotId = p[K_VAULT_SLOT_ID],
            baseUrl = p[K_BASE_URL] ?: WeixinBotClient.DEFAULT_BASE_URL,
            botId = p[K_BOT_ID].orEmpty(),
            pollIntervalSec = p[K_POLL_INTERVAL] ?: 1,
            replyChunkChars = (p[K_REPLY_CHUNK_CHARS] ?: DEFAULT_WEIXIN_REPLY_CHUNK_CHARS)
                .coerceIn(MIN_WEIXIN_REPLY_CHUNK_CHARS, MAX_WEIXIN_REPLY_CHUNK_CHARS),
            chatMap = parseChatMap(p[K_CHAT_MAP].orEmpty()),
        )

    /** 读取 from_user_id 的会话映射，无则返回 null. */
    suspend fun conversationIdFor(fromUserId: String): kotlin.uuid.Uuid? {
        val raw = current().chatMap[fromUserId] ?: return null
        return runCatching { kotlin.uuid.Uuid.parse(raw) }.getOrNull()
    }

    /** 写入/更新 from_user_id 的会话映射；超限丢弃最早一条. */
    suspend fun rememberChatMapping(fromUserId: String, conversationId: kotlin.uuid.Uuid) {
        update { cfg ->
            val nextMap = LinkedHashMap(cfg.chatMap)
            nextMap.remove(fromUserId)
            while (nextMap.size >= MAX_CHAT_MAPPINGS) {
                nextMap.remove(nextMap.keys.first())
            }
            nextMap[fromUserId] = conversationId.toString()
            cfg.copy(chatMap = nextMap)
        }
    }

    // ---- context_token 缓存（主动消息出口用，进程内存态） ----
    // iLink 协议：BOT 主动发消息也必须携带一个 context_token（来自该用户最近一条入站消息）。
    // 服务在收到入站消息时调用 rememberContextToken 缓存；外部生成完成泵发主动消息时取用，
    // 无缓存则放弃推送（协议上无 token 无法关联对话）。
    private val contextTokens = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun rememberContextToken(fromUserId: String, contextToken: String) {
        if (fromUserId.isBlank() || contextToken.isBlank()) return
        contextTokens[fromUserId] = contextToken
        while (contextTokens.size > MAX_CONTEXT_TOKENS) {
            contextTokens.remove(contextTokens.keys.first())
        }
    }

    fun peekContextToken(fromUserId: String): String? = contextTokens[fromUserId]

    fun knownChatUserIds(): Set<String> = contextTokens.keys.toSet()

    private fun serializeChatMap(map: Map<String, String>): String {
        val obj = buildJsonObject {
            map.forEach { (k, v) -> put(k, v) }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    private fun parseChatMap(s: String): Map<String, String> {
        if (s.isBlank()) return emptyMap()
        return runCatching {
            val obj = json.parseToJsonElement(s).jsonObject
            obj.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
        }.getOrDefault(emptyMap())
    }
}
