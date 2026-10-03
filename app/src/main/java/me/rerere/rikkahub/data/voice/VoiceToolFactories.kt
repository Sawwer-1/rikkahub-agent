package me.rerere.rikkahub.data.voice

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * 语音通话工具的导出工厂（自 jude 快照 LocalTools.kt 抽出，供主刀在 LocalTools 挂接）。
 *
 * 适配说明：本仓 ai 模块的 Tool.needsApproval 是 (JsonElement) -> Boolean，
 * jude 原版是 Boolean 常量；requestVoiceCallTool 中以 lambda 常量形式传入，语义不变。
 */

/** jude LocalTools.kt ttsTool（lazy）原文：chat_voice_reply / 协议锁工具 */
fun chatVoiceReplyTool(): Tool = Tool(
    name = CHAT_VOICE_REPLY_TOOL_NAME,
    description = """
        Switch the current reply into voice-message composition mode.
        Call this tool exactly once when all or part of your reply would feel more natural as one or more voice messages.
        After calling it, you will receive a hard protocol lock for a complete mixed text-and-voice reply. Your next assistant message is invalid unless it contains at least one 【语音条】 segment.
        Do not call it merely because the user mentioned audio, and do not call it again for additional voice segments in the same reply.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { }
        )
    },
    execute = {
        listOf(UIMessagePart.Text(CHAT_VOICE_REPLY_TOOL_RESULT_PROMPT.trimIndent()))
    }
)

/** jude LocalTools.kt requestVoiceCallTool(voiceCallConfigured) 原文（RequestVoiceCall / request_voice_call） */
fun requestVoiceCallTool(voiceCallConfigured: Boolean): Tool = Tool(
    name = REQUEST_VOICE_CALL_TOOL_NAME,
    description = """
        Invite the user to start a voice call in the current chat.
        Use this sparingly, only when a real-time spoken conversation would feel more natural or helpful than text.
        Provide one short, natural reason that can be shown on the incoming-call screen.
        The user may answer, decline, or miss the call, and the result will be returned to you.
        当前对话确实更适合实时语音交流时，才主动邀请用户通话；不要频繁发起。
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("reason", buildJsonObject {
                    put("type", "string")
                    put("description", "A short natural reason for calling, suitable for the incoming-call screen.")
                })
            },
            required = listOf("reason")
        )
    },
    needsApproval = { voiceCallConfigured },
    execute = { params ->
        if (!voiceCallConfigured) {
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("success", false)
                        put("error", VOICE_CALL_UNAVAILABLE_MESSAGE)
                    }.toString()
                )
            )
        } else {
            val reason = params.jsonObject["reason"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("success", true)
                        put("status", "answered")
                        put("reason", reason)
                    }.toString()
                )
            )
        }
    }
)
