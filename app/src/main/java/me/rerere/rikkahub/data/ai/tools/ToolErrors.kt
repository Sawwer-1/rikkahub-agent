package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart

/**
 * Shared error envelope for internal tools.
 *
 * Shape: `{"error": <machine code>, "detail": <plain sentence>, "recovery"?: "retry"|"fix_param"|"abort",
 * "hint"?: string}`.
 *
 * It deliberately keeps the flat `error` / `detail` keys the host already understands —
 * `LocalTools.appendHumanErrorToToolResult` derives its `human_error` field from `error`, and the
 * tool-approval / audit path reads the same envelope — while adding the machine-readable recovery
 * guidance that lets the model decide whether to retry, fix arguments, or stop.
 *
 * New tools should always return `ToolErrors.partsFor(...)` on failure instead of building an
 * ad-hoc `{"error": ...}` object; existing tools can migrate as their code is touched.
 */
object ToolErrors {

    // ---- Machine-stable error codes (snake_case, no user-visible wording) ----
    const val INVALID_ARGUMENT = "invalid_argument"
    const val NOT_FOUND = "not_found"
    const val ALREADY_EXISTS = "already_exists"
    const val PERMISSION_DENIED = "permission_denied"
    const val UNSUPPORTED = "unsupported"
    const val INTERNAL = "internal_error"
    const val UNKNOWN = "unknown"

    // ---- Recovery guidance ----
    /** The same call may succeed if it is sent again (transient failure). */
    const val RECOVERY_RETRY = "retry"
    /** The call must change its arguments before it can succeed. */
    const val RECOVERY_FIX_PARAM = "fix_param"
    /** Stop: this path cannot succeed and repeating it makes things worse. */
    const val RECOVERY_ABORT = "abort"

    /** Default recovery advice per error code, used when the caller does not pass one. */
    fun recoveryFor(error: String): String = when (error) {
        INTERNAL, UNKNOWN -> RECOVERY_RETRY
        PERMISSION_DENIED, UNSUPPORTED -> RECOVERY_ABORT
        else -> RECOVERY_FIX_PARAM
    }

    fun envelope(
        error: String,
        detail: String,
        recovery: String? = null,
        hint: String? = null,
    ): JsonObject = buildJsonObject {
        put("error", error)
        put("detail", detail.take(500))
        (recovery ?: recoveryFor(error)).let { put("recovery", it) }
        if (!hint.isNullOrBlank()) put("hint", hint.take(300))
    }

    fun textFor(
        error: String,
        detail: String,
        recovery: String? = null,
        hint: String? = null,
    ): String = envelope(error, detail, recovery, hint).toString()

    fun partsFor(
        error: String,
        detail: String,
        recovery: String? = null,
        hint: String? = null,
    ): List<UIMessagePart> = listOf(UIMessagePart.Text(textFor(error, detail, recovery, hint)))
}
