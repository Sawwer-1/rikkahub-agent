package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.ImageFormatDetector
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.generateUnifiedDiff
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.workspace.WorkspaceTreeResult
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024

val WORKSPACE_TOOL_NAMES: Set<String> = setOf(
    "workspace_read_file",
    "workspace_write_file",
    "workspace_edit_file",
    "workspace_create_folder",
    "workspace_read_folder",
    "workspace_shell",
    // 工作区枚举（jude-port）：只读列出所有工作区及其 id，供其它工作区工具的 `workspace` 参数取值。
    "workspace_list",
    // 大输出落盘（2026-10-14 吸收自 xiwangone/rikkahub-agents）：本集合被
    // CapabilityCatalog 的 WorkspaceTools 能力直接引用，因此登记在这里即完成能力面登记。
    "workspace_offload",
    // 工作区配置存储（jude-port）：工作区作用域的键值配置读取/写入/回滚。
    // 本集合被 CapabilityCatalog 的 WorkspaceTools 能力直接引用，登记在此即完成能力面登记。
    "config_get_all",
    "config_set",
    "config_revert",
)

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read_file" to false,
    "workspace_write_file" to false,
    "workspace_edit_file" to false,
    "workspace_create_folder" to false,
    "workspace_read_folder" to false,
    "workspace_shell" to true,
    "workspace_list" to false,
    "workspace_offload" to false,
    "config_get_all" to false,
    "config_set" to false,
    "config_revert" to false,
)

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false

suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
    allowSharedStorage: Boolean = false,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val approvalOverrides = workspaceRepository.getById(workspaceId)?.toolApprovalOverrides().orEmpty()
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)

    val shellCwd = cwd?.removePrefix("/workspace/")?.removePrefix("/workspace")

    return listOf(
        createListTool(workspaceRepository, approvalOverrides),
        createReadFileTool(workspaceId, ::needsApproval, workspaceRepository, allowSharedStorage),
        createWriteFileTool(workspaceId, ::needsApproval, workspaceRepository, allowSharedStorage),
        createEditFileTool(workspaceId, ::needsApproval, workspaceRepository, allowSharedStorage),
        createCreateFolderTool(workspaceId, ::needsApproval, workspaceRepository, allowSharedStorage),
        createReadFolderTool(workspaceId, ::needsApproval, workspaceRepository, allowSharedStorage),
        createShellTool(
            workspaceId,
            ::needsApproval,
            workspaceRepository,
            shellCwd,
            allowSharedStorage,
        ),
        createOffloadTool(workspaceId, ::needsApproval, workspaceRepository, allowSharedStorage),
        // config_* 由独立工厂返回 List<Tool>，必须整体拼接：塞进 listOf 会被推断成 List<Any>
        // 并撞上 `:app:compileDebugKotlin` 的 Return type mismatch（243 CI 首轮实测）。
    ) + createConfigTools(workspaceId, ::needsApproval, workspaceRepository, allowSharedStorage)
}

/**
 * 枚举所有工作区（jude-port）。只读、无参数；默认不需审批，但可在工作区详情页逐工具设定，
 * 口径与其它工作区工具一致（[WorkspaceToolDefaultApprovals] 默认 false）。
 *
 * 返回 `{"workspaces":[{"id","name","shellStatus","lastAccessAt"?}...]}`；模型可用其中的 id
 * 作为其它工作区工具的可选 `workspace` 参数，省略则该工具作用于助手绑定的工作区。
 */
internal fun createListTool(
    workspaceRepository: WorkspaceRepository,
    approvalOverrides: Map<String, Boolean> = emptyMap(),
): Tool = Tool(
    name = "workspace_list",
    description = buildString {
        append("List the available workspaces with their id, name and shell status. ")
        append("Use one of these ids as the optional `workspace` parameter of the other workspace ")
        append("tools (for example workspace_read_file / workspace_write_file) to act on that ")
        append("workspace; omitting the parameter acts on the assistant's bound workspace.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {},
            required = emptyList(),
        )
    },
    // 只读枚举：默认不审批，但工作区详情页可逐工具设为必批（P2 口径修正，2026-10-15）。
    needsApproval = { resolveWorkspaceToolApproval("workspace_list", approvalOverrides) },
    execute = {
        val workspaces = workspaceRepository.getAll()
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("workspaces", buildJsonArray {
                        workspaces.forEach { w ->
                            add(
                                buildJsonObject {
                                    put("id", w.id)
                                    put("name", w.name)
                                    put("shellStatus", w.shellStatus)
                                    w.lastAccessAt?.let { put("lastAccessAt", it) }
                                }
                            )
                        }
                    })
                }.toString()
            )
        )
    },
)

private fun createReadFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    allowSharedStorage: Boolean,
) = Tool(
    name = "workspace_read_file",
    description = """
        Read a file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area. This is not the Android phone storage root;
        use direct phone file tools for Android shared-storage files.
        Supports UTF-8 text and header-verified PNG, JPEG, WebP, GIF, BMP, safe SVG,
        HEIC, HEIF, AVIF, and ICO files. Unsupported images are returned as ordinary files.
        The optional `workspace` parameter targets another workspace id (from workspace_list);
        it defaults to the assistant's bound workspace.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                putWorkspaceProperty()
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_read_file") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val targetWorkspaceId = resolveTargetWorkspaceId(workspaceRepository, params, workspaceId)
        workspaceRepository.readFileInRootfs(targetWorkspaceId, path, allowSharedStorage)
    },
)

private fun createWriteFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    allowSharedStorage: Boolean,
) = Tool(
    name = "workspace_write_file",
    description = """
        Write a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area. For a phone-visible file, use a direct phone
        file tool instead.
        The optional `workspace` parameter targets another workspace id (from workspace_list);
        it defaults to the assistant's bound workspace.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content to write")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
                putWorkspaceProperty()
            },
            required = listOf("path", "text"),
        )
    },
    needsApproval = { needsApproval("workspace_write_file") || it.pathOutsideWorkspace("path") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val text = params.string("text") ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val targetWorkspaceId = resolveTargetWorkspaceId(workspaceRepository, params, workspaceId)
        val entry = workspaceRepository.writeTextInRootfs(
            targetWorkspaceId,
            path,
            text,
            overwrite,
            allowSharedStorage,
        )
        listOf(UIMessagePart.Text(entry.toJson().toString()))
    },
)

private fun createEditFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    allowSharedStorage: Boolean,
) = Tool(
    name = "workspace_edit_file",
    description = """
        Edit a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area. For a phone-visible file, use a direct phone
        file tool instead.
        Provide old_text and new_text. By default old_text must occur exactly once; set replace_all=true to replace every occurrence.
        If no exact match is found, whitespace-tolerant line matching is attempted automatically.
        The optional `workspace` parameter targets another workspace id (from workspace_list);
        it defaults to the assistant's bound workspace.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact text to replace")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to replace every occurrence. Defaults to false.")
                })
                putWorkspaceProperty()
            },
            required = listOf("path", "old_text", "new_text"),
        )
    },
    needsApproval = { needsApproval("workspace_edit_file") || it.pathOutsideWorkspace("path") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val oldText = params.string("old_text") ?: error("old_text is required")
        val newText = params.string("new_text") ?: error("new_text is required")
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        require(oldText.isNotEmpty()) { "old_text must not be empty" }

        val targetWorkspaceId = resolveTargetWorkspaceId(workspaceRepository, params, workspaceId)
        val original = workspaceRepository.readTextInRootfs(targetWorkspaceId, path, allowSharedStorage)
        // 逐级尝试 exact -> line_trimmed -> block_anchor 替换器, 见 TextReplacers.kt
        val result = try {
            replaceText(original, oldText, newText, replaceAll)
        } catch (e: IllegalArgumentException) {
            error("${e.message} (path: $path)")
        }
        val entry = workspaceRepository.writeTextInRootfs(
            targetWorkspaceId,
            path,
            result.updated,
            overwrite = true,
            allowSharedStorage = allowSharedStorage,
        )
        val diff = generateUnifiedDiff(original, result.updated, entry.path)
        listOf(
            UIMessagePart.Text(
                text = buildJsonObject {
                    put("path", entry.path)
                    put("replacements", result.replacements)
                    if (result.strategy != ExactReplacer.name) put("matchStrategy", result.strategy)
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                }.toString(),
                // diff 存入 metadata 供 UI 渲染 diff view, 不会随工具结果发送给 API
                metadata = diff?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)

private fun createCreateFolderTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    allowSharedStorage: Boolean,
) = Tool(
    name = "workspace_create_folder",
    description = """
        Create a directory (and any missing parent directories) using the assistant's bound workspace Rootfs.
        Paths must be absolute inside Rootfs. Use /workspace for the workspace files area.
        Does nothing (succeeds) if the directory already exists.
        The optional `workspace` parameter targets another workspace id (from workspace_list);
        it defaults to the assistant's bound workspace.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                putWorkspaceProperty()
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_create_folder") || it.pathOutsideWorkspace("path") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val targetWorkspaceId = resolveTargetWorkspaceId(workspaceRepository, params, workspaceId)
        val entry = workspaceRepository.createFolderInRootfs(targetWorkspaceId, path, allowSharedStorage)
        listOf(UIMessagePart.Text(entry.toJson().toString()))
    },
)

private fun createReadFolderTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    allowSharedStorage: Boolean,
) = Tool(
    name = "workspace_read_folder",
    description = """
        Recursively list a directory using the assistant's bound workspace Rootfs, as an indented tree.
        Paths must be absolute inside Rootfs. Use /workspace for the workspace files area.
        The listing is capped in entry count and depth; a truncation notice is included when a cap is hit.
        The optional `workspace` parameter targets another workspace id (from workspace_list);
        it defaults to the assistant's bound workspace.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                putWorkspaceProperty()
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_read_folder") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val targetWorkspaceId = resolveTargetWorkspaceId(workspaceRepository, params, workspaceId)
        val result = workspaceRepository.readFolderTree(targetWorkspaceId, path, allowSharedStorage)
        listOf(UIMessagePart.Text(formatWorkspaceTree(path, result)))
    },
)

private fun createShellTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
    allowSharedStorage: Boolean = false,
) = Tool(
    name = "workspace_shell",
    description = buildString {
        append("Run a shell command in the assistant's bound workspace Rootfs. The workspace files area is mounted at /workspace. ")
        if (allowSharedStorage) {
            append("This invocation has an authorized Android shared-storage mount at /sdcard. ")
            append("Use /sdcard/Download, /sdcard/Music, or /sdcard/RikkaHubExchange for phone-visible files. ")
        } else {
            append("Android shared storage is not mounted for this invocation; use direct phone file tools instead. ")
        }
        append("Use cwd for a path relative to the workspace files root. ")
        if (!defaultCwd.isNullOrBlank()) {
            append("Defaults to '$defaultCwd'. ")
        }
        append("The optional `workspace` parameter targets another workspace id (from workspace_list); ")
        append("it defaults to the assistant's bound workspace. ")
        append("Requires Rootfs to be installed and ready.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        if (!defaultCwd.isNullOrBlank()) {
                            "Working directory relative to the workspace files root. Defaults to '$defaultCwd'."
                        } else {
                            "Working directory relative to the workspace files root. Defaults to root."
                        }
                    )
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Command timeout in seconds. Defaults to 30, max $SHELL_TIMEOUT_MAX_SECONDS."
                    )
                })
                putWorkspaceProperty()
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval("workspace_shell") },
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        val targetWorkspaceId = resolveTargetWorkspaceId(workspaceRepository, params, workspaceId)
        // cwd 相对目标工作区文件根; 跨区调用时会话捕获的 shellCwd 对别的区没有意义, 忽略之(等价于不传 cwd)
        val cwd = (params.string("cwd") ?: if (targetWorkspaceId == workspaceId) defaultCwd.orEmpty() else "")
            .removePrefix("/workspace/").removePrefix("/workspace")
        val timeoutMillis = params.string("timeout")?.toLongOrNull()
            ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)
            ?.times(1_000L)
            ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
        val result = workspaceRepository.executeCommand(
            id = targetWorkspaceId,
            command = command,
            cwd = cwd,
            timeoutMillis = timeoutMillis,
            allowSharedStorage = allowSharedStorage,
        )
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("exitCode", result.exitCode)
                    put("stdout", result.stdout)
                    put("stderr", result.stderr)
                    put("timedOut", result.timedOut)
                    if (result.truncated) put("truncated", true)
                }.toString()
            )
        )
    },
)

/** 落盘区目录（工作区 Rootfs 内的绝对路径）。 */
private const val OFFLOAD_DIR = "/workspace/offloads"

/** 回显给模型的 preview 上限（字符）。 */
private const val OFFLOAD_PREVIEW_CHARS = 400

/** 建议模型"超过此长度先落盘"的字节阈值，供系统提示引用。 */
const val OFFLOAD_HINT_BYTES = 12 * 1024

/**
 * 大输出落盘（2026-10-14 吸收自 xiwangone/rikkahub-agents）。
 *
 * 长日志 / 完整文件 dump / diff / 转录 / 生成代码写进工作区 `offloads/`，只回显一小段
 * preview，完整内容留盘，后续可用 `workspace_read_file` 取回、用 `workspace_read_folder`
 * 列举 —— 避免工具输出一次性撑爆上下文。
 *
 * 与 `GenerationHandler` 的自动兜底（超 32 KB 且本回合有 shell 访问时自动落盘到
 * filesDir）互补：那条是自动兜底且不可见，这条是模型显式可控、落在工作区（人可见、可引用）。
 */
private fun createOffloadTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    allowSharedStorage: Boolean,
) = Tool(
    name = "workspace_offload",
    description = """
        Persist a potentially large block of text (logs, full file dumps, diffs, transcripts,
        generated code) into the workspace offload area and return a reference instead of echoing
        it back into the conversation. Only the first $OFFLOAD_PREVIEW_CHARS characters come back
        as preview; the full content is written to $OFFLOAD_DIR/<name>.md and can be read later
        with workspace_read_file (list the area with workspace_read_folder on $OFFLOAD_DIR).
        Prefer this over dumping outputs larger than $OFFLOAD_HINT_BYTES bytes into the conversation.
        The optional `workspace` parameter targets another workspace id (from workspace_list);
        it defaults to the assistant's bound workspace.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "File name base, letters/digits/-/_ only, e.g. crash_log. Written as <name>.md",
                    )
                })
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "Full UTF-8 content to persist. May be arbitrarily large.")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
                putWorkspaceProperty()
            },
            required = listOf("name", "text"),
        )
    },
    needsApproval = { needsApproval("workspace_offload") },
    execute = {
        val params = it.jsonObject
        val name = sanitizeOffloadName(params.string("name"))
        val text = params.string("text") ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val targetWorkspaceId = resolveTargetWorkspaceId(workspaceRepository, params, workspaceId)
        val path = "$OFFLOAD_DIR/$name.md"
        val entry = workspaceRepository.writeTextInRootfs(
            targetWorkspaceId,
            path,
            text,
            overwrite,
            allowSharedStorage,
        )
        val preview = if (text.length <= OFFLOAD_PREVIEW_CHARS) {
            text
        } else {
            text.take(OFFLOAD_PREVIEW_CHARS) +
                "\n…[+${text.length - OFFLOAD_PREVIEW_CHARS} chars; full at $path]"
        }
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("path", entry.path)
                    put("sizeBytes", entry.sizeBytes)
                    put("preview", preview)
                }.toString()
            )
        )
    },
)

private fun sanitizeOffloadName(raw: String?): String {
    val cleaned = (raw ?: "offload").replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)
    return cleaned.ifBlank { "offload" }
}

private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private suspend fun WorkspaceRepository.readFileInRootfs(
    workspaceId: String,
    path: String,
    allowSharedStorage: Boolean,
): List<UIMessagePart> {
    val bytes = readRootfsBytes(workspaceId, path, allowSharedStorage)
    val originalName = path.substringAfterLast('/').ifBlank { "file" }
    val detected = ImageFormatDetector.detect(bytes, originalName)
    val imageExtensionClaimed = originalName.substringAfterLast('.', "").lowercase() in
        ImageFormatDetector.knownExtensions
    if (detected == null && !imageExtensionClaimed) {
        return listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("path", path)
                    put("text", bytes.toString(Charsets.UTF_8))
                }.toString()
            )
        )
    }
    val filesManager = getKoin().get<FilesManager>()
    val displayName = detected?.normalizedDisplayName(originalName) ?: originalName
    val mimeType = detected?.mimeType ?: "application/octet-stream"
    val saved = filesManager.createChatFileByBytes(
        bytes = bytes,
        displayName = displayName,
        mimeType = mimeType,
        expectImage = detected != null,
    )
    if (detected != null && saved.imageRenderable) {
        return listOf(
            UIMessagePart.Image(url = saved.uri.toString()),
            UIMessagePart.Text(
                buildJsonObject {
                    put("path", path)
                    put("file_name", saved.displayName)
                    put("mime_type", saved.mimeType)
                    put("description", "Image file read successfully")
                }.toString()
            ),
        )
    }
    return listOf(
        UIMessagePart.Document(
            url = saved.uri.toString(),
            fileName = saved.displayName,
            mime = saved.mimeType,
        ),
        UIMessagePart.Text(
            buildJsonObject {
                put("path", path)
                put("file_name", saved.displayName)
                put("mime_type", saved.mimeType)
                put(
                    "description",
                    if (detected == null) {
                        "The file claims to be an image, but its header is unsupported or unsafe; returned as an ordinary file."
                    } else {
                        "This Android runtime cannot decode the detected image format; returned as an ordinary file."
                    },
                )
            }.toString()
        )
    )
}

internal suspend fun WorkspaceRepository.readTextInRootfs(
    workspaceId: String,
    path: String,
    allowSharedStorage: Boolean,
): String = readRootfsBytes(workspaceId, path, allowSharedStorage).toString(Charsets.UTF_8)

private suspend fun WorkspaceRepository.readRootfsBytes(
    workspaceId: String,
    path: String,
    allowSharedStorage: Boolean,
): ByteArray {
    val size = rootfsFileSize(workspaceId, path, allowSharedStorage)
    require(size <= MAX_READ_FILE_BYTES) {
        "File is too large to read: $path (${size / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Use shell commands like head, tail, or grep to read parts of it."
    }
    val buffer = ByteArrayOutputStream(size.toInt())
    exportRootfsFile(workspaceId, path, buffer, allowSharedStorage)
    return buffer.toByteArray()
}

internal suspend fun WorkspaceRepository.writeTextInRootfs(
    workspaceId: String,
    path: String,
    text: String,
    overwrite: Boolean,
    allowSharedStorage: Boolean,
): WorkspaceFileEntry {
    val pathArg = path.shellQuote()
    val result = runRootfsCommand(
        workspaceId = workspaceId,
        action = "Write file",
        command = """
            if [ -e $pathArg ] && [ ${(!overwrite).shellFlag()} = 1 ]; then
              printf '%s\n' ${"File already exists: $path".shellQuote()} >&2
              exit 1
            fi
            if [ -e $pathArg ] && [ ! -f $pathArg ]; then
              printf '%s\n' ${"Path is not a file: $path".shellQuote()} >&2
              exit 1
            fi
            parent=${'$'}(dirname -- $pathArg) || exit 1
            mkdir -p -- "${'$'}parent" || exit 1
            cat > $pathArg || exit 1
            ${statEntryCommand(path)}
        """.trimIndent(),
        stdin = text.toByteArray(Charsets.UTF_8),
        allowSharedStorage = allowSharedStorage,
    )
    return result.stdout.parseRootfsEntry()
}

internal suspend fun WorkspaceRepository.createFolderInRootfs(
    workspaceId: String,
    path: String,
    allowSharedStorage: Boolean = false,
): WorkspaceFileEntry {
    val pathArg = path.shellQuote()
    val result = runRootfsCommand(
        workspaceId = workspaceId,
        action = "Create folder",
        command = """
            if [ -e $pathArg ] && [ ! -d $pathArg ]; then
              printf '%s\n' ${"Path already exists and is not a directory: $path".shellQuote()} >&2
              exit 1
            fi
            mkdir -p -- $pathArg || exit 1
            ${statEntryCommand(path)}
        """.trimIndent(),
        allowSharedStorage = allowSharedStorage,
    )
    return result.stdout.parseRootfsEntry()
}

private suspend fun WorkspaceRepository.runRootfsCommand(
    workspaceId: String,
    action: String,
    command: String,
    stdin: ByteArray? = null,
    allowSharedStorage: Boolean = false,
): WorkspaceCommandResult {
    val result = executeCommand(
        id = workspaceId,
        command = command,
        timeoutMillis = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin = stdin,
        allowSharedStorage = allowSharedStorage,
    )
    if (result.timedOut) {
        error("$action timed out")
    }
    if (result.exitCode != 0) {
        val message = result.stderr.ifBlank { result.stdout }.trim()
        error(if (message.isBlank()) "$action failed with exit code ${result.exitCode}" else message)
    }
    if (result.truncated) {
        error("$action output is too large")
    }
    return result
}

private fun statEntryCommand(path: String): String {
    val pathArg = path.shellQuote()
    return """
        if [ -d $pathArg ]; then entry_type=d; else entry_type=f; fi
        entry_size=${'$'}(stat -c '%s' -- $pathArg) || exit 1
        entry_mtime=${'$'}(stat -c '%Y' -- $pathArg) || exit 1
        printf '%s\0%s\0%s\0%s\0' "${'$'}entry_type" "${'$'}entry_size" "${'$'}entry_mtime" $pathArg
    """.trimIndent()
}

private fun String.parseRootfsEntry(): WorkspaceFileEntry =
    parseRootfsEntries().singleOrNull() ?: error("Invalid file metadata output")

private fun String.parseRootfsEntries(): List<WorkspaceFileEntry> {
    val fields = split('\u0000').dropLastWhile { it.isEmpty() }
    require(fields.size % 4 == 0) { "Invalid file metadata output" }
    return fields.chunked(4).map { chunk ->
        val type = chunk[0]
        val size = chunk[1].toLongOrNull() ?: error("Invalid file size: ${chunk[1]}")
        val updatedAt = (chunk[2].toLongOrNull() ?: error("Invalid file mtime: ${chunk[2]}")) * 1_000L
        val path = chunk[3]
        WorkspaceFileEntry(
            path = path,
            name = path.rootfsName(),
            isDirectory = type == "d",
            sizeBytes = size,
            updatedAt = updatedAt,
        )
    }
}

private fun kotlinx.serialization.json.JsonObject.absolutePath(name: String): String {
    val path = string(name)?.replace('\\', '/')?.trim() ?: error("$name is required")
    require(path.isNotBlank()) { "$name is required" }
    require(path.startsWith("/")) { "$name must be an absolute path inside Rootfs" }
    require(!path.contains('\u0000')) { "$name contains invalid character" }
    return path
}

private fun kotlinx.serialization.json.JsonElement.pathOutsideWorkspace(name: String): Boolean =
    runCatching {
        jsonObject.absolutePath(name).isOutsideWorkspace()
    }.getOrDefault(true)

private fun String.isOutsideWorkspace(): Boolean {
    val normalized = trimEnd('/').ifBlank { "/" }
    return normalized != "/workspace" && !normalized.startsWith("/workspace/")
}

/**
 * 纯函数: 把递归目录树格式化为紧凑的缩进文本 (面向模型), 命中截断上限时显式提示。
 */
internal fun formatWorkspaceTree(rootPath: String, result: WorkspaceTreeResult): String = buildString {
    append(rootPath.trimEnd('/').ifEmpty { "/" }).append('/')
    if (result.entries.isEmpty()) {
        append(if (result.truncated) " (empty, truncated)" else " (empty)")
        return@buildString
    }
    result.entries.forEach { entry ->
        append('\n')
        append("  ".repeat(entry.depth))
        append(entry.name)
        if (entry.isDirectory) {
            append('/')
        } else {
            append(" (").append(entry.sizeBytes).append(" bytes)")
        }
    }
    if (result.truncated) {
        append('\n')
        append("... (truncated: showing ${result.entries.size} entries; narrow the path for a complete listing)")
    }
}

private fun String.rootfsName(): String =
    trimEnd('/').substringAfterLast('/').ifBlank { "/" }

private fun String.shellQuote(): String =
    "'" + replace("'", "'\"'\"'") + "'"

private fun Boolean.shellFlag(): Int = if (this) 1 else 0

private fun JsonObjectBuilder.putPathProperty(required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) {
                "Absolute path inside Rootfs. Use /workspace for the workspace files area."
            } else {
                "Optional absolute path inside Rootfs. Use /workspace for the workspace files area."
            }
        )
    })
}

private fun WorkspaceFileEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}
