package me.rerere.rikkahub.data.ai.tools

import kotlin.time.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.JsonInstantPretty
import kotlin.uuid.Uuid

/**
 * 定时任务（TrustedWorkflow）专用的只读会话摘要工具（工单 T-10，方案 B）。
 *
 * 与第二用户读者（[createSecondUserConversationReaderTools]）的区别：后者要求特权会话
 * （`invocationContext.privilege`），TrustedWorkflow 没有这层身份，因此工单里
 * `conversation_search` 在后台任务中「未注册」。
 *
 * 本工具的安全边界（对应工单 B「只读指定会话、指定时间窗」）：
 * - **必须显式传 conversation_id**：不接受全局检索，不列其他会话；
 * - **窗口固定为当天本地 00:00 起**：不提供 since 参数，无法放大到历史全量；
 * - **只回片段**：每条消息截断到 [DAY_DIGEST_SNIPPET_CHARS] 字，只用于「今天有没有回话」
 *   这类判定，不返回完整正文；
 * - **调用自然留痕**：工具参数与返回的 metadata（conversation_id / since / count）随执行
 *   账本记录，后台任务不再出现「未产生调用记录」的黑洞。
 */
private const val DAY_DIGEST_DEFAULT_LIMIT = 50
private const val DAY_DIGEST_MAX_LIMIT = 100
private const val DAY_DIGEST_SNIPPET_CHARS = 200

fun createTrustedWorkflowConversationDigestTool(
    conversationRepo: ConversationRepository,
): Tool = Tool(
    name = "conversation_day_digest",
    description = """
        Read-only digest of ONE explicitly identified conversation, limited to TODAY (local midnight onward).
        Returns role, timestamp and a short snippet (max $DAY_DIGEST_SNIPPET_CHARS chars) for each message.
        Use it to check whether the user has spoken today; it cannot search other conversations or history,
        and it never returns full message bodies.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("conversation_id", buildJsonObject {
                    put("type", "string")
                    put("description", "UUID of the conversation to inspect (required; no global search).")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Maximum number of today's messages to return (default: $DAY_DIGEST_DEFAULT_LIMIT, max: $DAY_DIGEST_MAX_LIMIT)",
                    )
                })
            },
            required = listOf("conversation_id"),
        )
    },
    execute = { input ->
        val args = input.jsonObject
        val conversationId = runCatching {
            Uuid.parse(args["conversation_id"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }.getOrNull()
            ?: return@Tool dayDigestFailure("INVALID_CONVERSATION_ID", "conversation_id must be a UUID.")
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: DAY_DIGEST_DEFAULT_LIMIT)
            .coerceIn(1, DAY_DIGEST_MAX_LIMIT)
        val since = startOfTodayLocal()
        val conversation = conversationRepo.getConversationById(conversationId)
            ?: return@Tool dayDigestFailure("CONVERSATION_NOT_FOUND", "The conversation does not exist.")
        val messages = buildDayDigest(conversation.currentMessages, since, limit)
        val payload = buildJsonObject {
            put("conversation_id", conversationId.toString())
            put("title", conversation.title.ifBlank { "Untitled" })
            put("window", "today")
            put("since", since.toString())
            put("count", messages.size)
            put("snippet_chars", DAY_DIGEST_SNIPPET_CHARS)
            put("messages", buildJsonArray {
                messages.forEach { message ->
                    add(buildJsonObject {
                        put("message_id", message.id.toString())
                        put("role", message.role.name)
                        put("created_at", message.createdAt.toString())
                        put("text", message.toDigestText())
                    })
                }
            })
        }
        listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
    },
)

/** 当天本地 00:00。固定窗口，刻意不开放参数，避免后台任务自行放大到历史全量。 */
internal fun startOfTodayLocal(
    today: kotlinx.datetime.LocalDate = Clock.System.now()
        .toLocalDateTime(TimeZone.currentSystemDefault()).date,
): LocalDateTime = LocalDateTime.parse("${today}T00:00")

/**
 * 当日窗口过滤 + 只留最后 [limit] 条。纯函数，单测覆盖（不依赖 Android Context）。
 */
internal fun buildDayDigest(
    messages: List<UIMessage>,
    since: LocalDateTime,
    limit: Int,
): List<UIMessage> = messages
    .filter { it.createdAt >= since }
    .takeLast(limit)

private fun UIMessage.toDigestText(): String = parts
    .filterIsInstance<UIMessagePart.Text>()
    .joinToString("\n") { it.text }
    .take(DAY_DIGEST_SNIPPET_CHARS)

private fun dayDigestFailure(code: String, message: String): List<UIMessagePart> {
    val payload = buildJsonObject {
        put("ok", false)
        put("code", code)
        put("message", message)
    }
    return listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
}
