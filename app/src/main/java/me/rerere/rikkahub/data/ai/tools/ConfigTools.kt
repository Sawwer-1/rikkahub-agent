package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

/** 工作区配置存储目录（工作区 Rootfs 内绝对路径）。 */
private const val CONFIG_DIR = "/workspace/config"

/** 键值配置文件的 Rootfs 路径。 */
private const val CONFIG_SETTINGS_PATH = "$CONFIG_DIR/settings.json"

/** 变更审计日志的 Rootfs 路径（每行一条 JSON）。 */
private const val CONFIG_AUDIT_PATH = "$CONFIG_DIR/audit.jsonl"

/**
 * 工作区作用域的配置工具：`config_get_all` / `config_set` / `config_revert`。
 *
 * 配置存于工作区 Rootfs 的配置目录内：[CONFIG_SETTINGS_PATH] 保存 `{ "<key>": <json> }`，
 * [CONFIG_AUDIT_PATH] 每行记录一次 set/revert 及其前后值，供 `config_revert` 回滚。
 * 这些工具随工作区一起装配，因此需要工作区绑定（由 [createWorkspaceTools] 注入）。
 */
internal fun createConfigTools(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    allowSharedStorage: Boolean,
): List<Tool> = listOf(
    createConfigGetAllTool(workspaceId, needsApproval, workspaceRepository, allowSharedStorage),
    createConfigSetTool(workspaceId, needsApproval, workspaceRepository, allowSharedStorage),
    createConfigRevertTool(workspaceId, needsApproval, workspaceRepository, allowSharedStorage),
)

// ── 纯函数（可单测，不依赖工作区/Rootfs） ────────────────────────────────────────────

/**
 * 纯函数：把 [valueJson] 解析成 JSON 值写入 [current] 的 [key]，返回 (新对象, 旧值)。
 * 键不存在时旧值为 null；[valueJson] 不是合法 JSON 时抛 [IllegalArgumentException]。
 */
/**
 * **严格口径**的"value 必须是 JSON 编码"校验。
 *
 * `Json.parseToJsonElement` 对裸标识符是宽松的（`dark`、`hello` 会被当成字符串解析而不报错），
 * 于是「字符串必须带引号」这条 UX 约定形同虚设——243 CI 实测：传 `dark` 时旧实现放行了。
 * 这里显式要求首字符像 JSON 起始（引号 / 括号 / 数字 / true|false|null），再交给解析器做完整校验。
 */
private fun requireJsonEncoded(valueJson: String): String {
    val trimmed = valueJson.trim()
    val head = trimmed.firstOrNull()
    val looksLikeJson = when {
        head == null -> false
        head == '"' || head == '{' || head == '[' || head == '-' || head == '+' -> true
        head.isDigit() -> true
        trimmed == "true" || trimmed == "false" || trimmed == "null" -> true
        else -> false
    }
    require(looksLikeJson) {
        "value must be valid JSON — a string needs quotes (for example \"dark\")"
    }
    return trimmed
}

internal fun configApplySet(
    current: JsonObject,
    key: String,
    valueJson: String,
): Pair<JsonObject, JsonElement?> {
    val parsed = try {
        Json.parseToJsonElement(requireJsonEncoded(valueJson))
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("value must be valid JSON: ${e.message}")
    }
    val previous = current[key]
    return JsonObject(current + (key to parsed)) to previous
}

/**
 * 纯函数：按审计日志把 [key] 回滚到最近一次 `set` 之前的值。
 *
 * 倒序扫描 [auditLines]，取最近一条 `action="set"` 且 `key` 匹配的记录：其 `previous` 存在
 * 则写回该值，为 null 则删除该键；没有任何历史则直接删除该键。
 * 返回 (新对象, 是否恢复到某个历史值)。
 */
internal fun configRevertValue(
    current: JsonObject,
    auditLines: List<String>,
    key: String,
): Pair<JsonObject, Boolean> {
    val match = auditLines.asReversed().asSequence()
        .mapNotNull { line -> runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() }
        .firstOrNull { entry ->
            entry["action"]?.jsonPrimitiveOrNull?.contentOrNull == "set" &&
                entry["key"]?.jsonPrimitiveOrNull?.contentOrNull == key
        }
        ?: return JsonObject(current - key) to false
    val previous = match["previous"]
    return if (previous == null || previous is JsonNull) {
        JsonObject(current - key) to true
    } else {
        JsonObject(current + (key to previous)) to true
    }
}

// ── 工具定义 ────────────────────────────────────────────────────────────────────────

private fun createConfigGetAllTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    allowSharedStorage: Boolean,
) = Tool(
    name = "config_get_all",
    description = """
        Read the whole workspace configuration store as a single JSON object. Returns {} before
        anything has been configured. Read-only; use config_set to change a value and config_revert
        to undo the most recent change to a key.
        The optional `workspace` parameter targets another workspace id (from workspace_list);
        it defaults to the assistant's bound workspace.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putWorkspaceProperty()
            }
        )
    },
    needsApproval = { needsApproval("config_get_all") },
    execute = {
        val params = it.jsonObject
        val targetWorkspaceId = resolveTargetWorkspaceId(workspaceRepository, params, workspaceId)
        readSettingsObject(workspaceRepository, targetWorkspaceId, allowSharedStorage).fold(
            onSuccess = { settings -> listOf(UIMessagePart.Text(settings.toString())) },
            onFailure = { configCorruptedParts() },
        )
    },
)

private fun createConfigSetTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    allowSharedStorage: Boolean,
) = Tool(
    name = "config_set",
    description = """
        Set one key in the workspace configuration store. `value` must be a JSON-encoded value
        (for example "text" with quotes, 42, true, or {"nested":1}); it is stored verbatim.
        Returns the previous value (if any) and records the change so it can be undone with
        config_revert.
        The optional `workspace` parameter targets another workspace id (from workspace_list);
        it defaults to the assistant's bound workspace.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("key", buildJsonObject {
                    put("type", "string")
                    put("description", "Configuration key to set.")
                })
                put("value", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "JSON-encoded value; strings need quotes, e.g. \"text\".",
                    )
                })
                put("note", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional short note recorded with the change.")
                })
                putWorkspaceProperty()
            },
            required = listOf("key", "value"),
        )
    },
    needsApproval = { needsApproval("config_set") },
    execute = {
        val params = it.jsonObject
        val key = params["key"]?.jsonPrimitiveOrNull?.contentOrNull
        val valueJson = params["value"]?.jsonPrimitiveOrNull?.contentOrNull
        val note = params["note"]?.jsonPrimitiveOrNull?.contentOrNull
        when {
            key.isNullOrBlank() -> ToolErrors.partsFor(
                ToolErrors.INVALID_ARGUMENT,
                "`key` is required and must be a non-empty string.",
            )

            valueJson == null -> ToolErrors.partsFor(
                ToolErrors.INVALID_ARGUMENT,
                "`value` is required.",
            )

            else -> applyConfigSet(
                workspaceRepository,
                resolveTargetWorkspaceId(workspaceRepository, params, workspaceId),
                allowSharedStorage,
                key,
                valueJson,
                note,
            )
        }
    },
)

private fun createConfigRevertTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    allowSharedStorage: Boolean,
) = Tool(
    name = "config_revert",
    description = """
        Undo the most recent config_set for one key in the workspace configuration store.
        Restores the value that was in place before that change; if the key did not exist before,
        or has no recorded change history, the key is removed. Records the revert so it is
        visible in the audit trail.
        The optional `workspace` parameter targets another workspace id (from workspace_list);
        it defaults to the assistant's bound workspace.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("key", buildJsonObject {
                    put("type", "string")
                    put("description", "Configuration key to revert to its most recent previous value.")
                })
                putWorkspaceProperty()
            },
            required = listOf("key"),
        )
    },
    needsApproval = { needsApproval("config_revert") },
    execute = {
        val params = it.jsonObject
        val key = params["key"]?.jsonPrimitiveOrNull?.contentOrNull
        if (key.isNullOrBlank()) {
            ToolErrors.partsFor(
                ToolErrors.INVALID_ARGUMENT,
                "`key` is required and must be a non-empty string.",
            )
        } else {
            applyConfigRevert(
                workspaceRepository,
                resolveTargetWorkspaceId(workspaceRepository, params, workspaceId),
                allowSharedStorage,
                key,
            )
        }
    },
)

// ── 存储读写 ────────────────────────────────────────────────────────────────────────

private fun configCorruptedParts(): List<UIMessagePart> = ToolErrors.partsFor(
    ToolErrors.INTERNAL,
    "The workspace configuration store is not a valid JSON object.",
    hint = "Fix or remove the corrupted configuration file before retrying.",
)

/** 读出当前配置；文件不存在或为空视为 `{}`，内容不是 JSON 对象时返回失败。 */
private suspend fun readSettingsObject(
    workspaceRepository: WorkspaceRepository,
    workspaceId: String,
    allowSharedStorage: Boolean,
): Result<JsonObject> {
    val raw = runCatching {
        workspaceRepository.readTextInRootfs(workspaceId, CONFIG_SETTINGS_PATH, allowSharedStorage)
    }.getOrNull()
    if (raw.isNullOrBlank()) return Result.success(JsonObject(emptyMap()))
    return runCatching { Json.parseToJsonElement(raw).jsonObject }
}

/** 读出审计日志的非空行；文件不存在视为空。 */
private suspend fun readAuditLines(
    workspaceRepository: WorkspaceRepository,
    workspaceId: String,
    allowSharedStorage: Boolean,
): List<String> {
    val raw = runCatching {
        workspaceRepository.readTextInRootfs(workspaceId, CONFIG_AUDIT_PATH, allowSharedStorage)
    }.getOrNull()
    return raw?.lineSequence()?.filter { it.isNotBlank() }?.toList().orEmpty()
}

/** 失败描述：优先异常 message，缺失时退化为异常类名；只描述底层错误，不回显调用方输入。 */
private fun failureDescription(error: Throwable?): String =
    error?.message?.takeIf { it.isNotBlank() } ?: error?.javaClass?.simpleName ?: "unknown error"

private suspend fun applyConfigSet(
    workspaceRepository: WorkspaceRepository,
    workspaceId: String,
    allowSharedStorage: Boolean,
    key: String,
    valueJson: String,
    note: String?,
): List<UIMessagePart> {
    val current = readSettingsObject(workspaceRepository, workspaceId, allowSharedStorage)
        .getOrElse { return configCorruptedParts() }

    val (updated, previous) = try {
        configApplySet(current, key, valueJson)
    } catch (e: IllegalArgumentException) {
        return ToolErrors.partsFor(
            ToolErrors.INVALID_ARGUMENT,
            "`value` must be a JSON-encoded value: ${e.message}",
            hint = "Strings need quotes, for example \"text\".",
        )
    }
    val next = Json.parseToJsonElement(valueJson)

    // 顺序敏感：先落审计行再写配置——审计里的 previous 是 config_revert 的回滚依据，
    // 审计失败时配置必须保持原样，不能出现“配置已生效但无据可回滚”的状态。
    val auditWritten = runCatching {
        val audit = readAuditLines(workspaceRepository, workspaceId, allowSharedStorage).toMutableList()
        audit.add(auditLine("set", key, previous, next, note))
        workspaceRepository.writeTextInRootfs(
            workspaceId,
            CONFIG_AUDIT_PATH,
            audit.joinToString(separator = "\n", postfix = "\n"),
            overwrite = true,
            allowSharedStorage = allowSharedStorage,
        )
    }
    if (auditWritten.isFailure) {
        return ToolErrors.partsFor(
            ToolErrors.INTERNAL,
            "Failed to record the audit entry; the configuration was not changed: " +
                failureDescription(auditWritten.exceptionOrNull()),
        )
    }
    val settingsWritten = runCatching {
        workspaceRepository.writeTextInRootfs(
            workspaceId,
            CONFIG_SETTINGS_PATH,
            updated.toString(),
            overwrite = true,
            allowSharedStorage = allowSharedStorage,
        )
    }
    if (settingsWritten.isFailure) {
        return ToolErrors.partsFor(
            ToolErrors.INTERNAL,
            "audit entry recorded but the config file was not updated: " +
                failureDescription(settingsWritten.exceptionOrNull()),
        )
    }
    return listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put("key", key)
                put("previous", previous ?: JsonNull)
                put("value", next)
            }.toString(),
        ),
    )
}

private suspend fun applyConfigRevert(
    workspaceRepository: WorkspaceRepository,
    workspaceId: String,
    allowSharedStorage: Boolean,
    key: String,
): List<UIMessagePart> {
    val current = readSettingsObject(workspaceRepository, workspaceId, allowSharedStorage)
        .getOrElse { return configCorruptedParts() }
    val auditLines = readAuditLines(workspaceRepository, workspaceId, allowSharedStorage)

    val (updated, restored) = configRevertValue(current, auditLines, key)
    val removedValue = current[key]
    val restoredValue = updated[key]

    // 顺序敏感：先落审计行再写配置——审计链是回滚对账的唯一依据，
    // 审计失败时配置保持原样，不产生“已改配置却无记录”的静默变更。
    val auditWritten = runCatching {
        val audit = auditLines.toMutableList()
        audit.add(auditLine("revert", key, removedValue, restoredValue, null))
        workspaceRepository.writeTextInRootfs(
            workspaceId,
            CONFIG_AUDIT_PATH,
            audit.joinToString(separator = "\n", postfix = "\n"),
            overwrite = true,
            allowSharedStorage = allowSharedStorage,
        )
    }
    if (auditWritten.isFailure) {
        return ToolErrors.partsFor(
            ToolErrors.INTERNAL,
            "Failed to record the audit entry; the configuration was not changed: " +
                failureDescription(auditWritten.exceptionOrNull()),
        )
    }
    val settingsWritten = runCatching {
        workspaceRepository.writeTextInRootfs(
            workspaceId,
            CONFIG_SETTINGS_PATH,
            updated.toString(),
            overwrite = true,
            allowSharedStorage = allowSharedStorage,
        )
    }
    if (settingsWritten.isFailure) {
        return ToolErrors.partsFor(
            ToolErrors.INTERNAL,
            "audit entry recorded but the config file was not updated: " +
                failureDescription(settingsWritten.exceptionOrNull()),
        )
    }
    return listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put("key", key)
                put("restored", restored)
                put("value", restoredValue ?: JsonNull)
            }.toString(),
        ),
    )
}

/** 构造一条审计日志行：`{"ts":<epochMs>,"action":...,"key":...,"previous":...,"next":...,"note"?}`。 */
private fun auditLine(
    action: String,
    key: String,
    previous: JsonElement?,
    next: JsonElement?,
    note: String?,
): String = buildJsonObject {
    put("ts", System.currentTimeMillis())
    put("action", action)
    put("key", key)
    put("previous", previous ?: JsonNull)
    put("next", next ?: JsonNull)
    if (!note.isNullOrBlank()) put("note", note)
}.toString()
