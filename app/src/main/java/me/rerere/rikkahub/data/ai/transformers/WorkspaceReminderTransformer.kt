package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.OFFLOAD_HINT_BYTES
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus

/**
 * Workspace 系统提示注入转换器
 *
 * 按 workspace 状态向系统提示词追加引导：
 * - 已绑定且 rootfs 就绪（READY）：完整的环境与工具清单。
 * - 已绑定但未就绪：一小段状态说明（P1，2026-10-15）。
 * - 未绑定但存在可选 workspace：教用户绑定（P2，2026-10-16，迁移自 ExTV）。
 * - 一个 workspace 都没有：不注入。
 *
 * 后两档里工作区工具都不可用，只剩 `workspace_list`（只读枚举），提示里必须提到它，
 * 否则模型会忘记这个工具或反复试不存在的工具。
 */
class WorkspaceReminderTransformer(
    private val workspaceRepository: WorkspaceRepository,
    private val rulesResolver: WorkspaceRulesResolver =
        WorkspaceRulesResolver(workspaceRepository),
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val workspaceId = ctx.assistant.workspaceId?.toString()
        val workspace = workspaceId?.let { workspaceRepository.getById(it) }
        // 仅在未解析到绑定的 workspace 时才需要查询是否存在其它 workspace（短路，避免多余查询）
        val hasAnyWorkspace = workspace != null || workspaceRepository.getAll().isNotEmpty()

        val prompt = buildList {
            when {
                // 未绑定但存在可选 workspace：教用户绑定（P2，2026-10-16）
                workspace == null && hasAnyWorkspace -> add(buildWorkspaceUnboundPrompt())
                workspace != null -> {
                    if (workspace.shellStatus == WorkspaceShellStatus.READY.name) {
                        add(buildWorkspacePrompt(workspace, ctx.workspaceCwd))
                    } else {
                        add(buildWorkspaceNotReadyPrompt(workspace))
                    }
                    rulesResolver.resolve(workspace.id, ctx.workspaceCwd)
                        .toPrompt()
                        .takeIf(String::isNotBlank)
                        ?.let(::add)
                }
            }
        }.joinToString("\n\n")
        if (prompt.isBlank()) return messages

        // 追加到第一条 system 消息; 若不存在则插入一条
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex].appendText("\n\n$prompt")
            }
        } else {
            listOf(UIMessage.system(prompt)) + messages
        }
    }
}

private fun buildWorkspacePrompt(workspace: WorkspaceEntity, cwd: String? = null): String = buildString {
    appendLine("<workspace>")
    appendLine(
        "You have access to a persistent Linux workspace named " +
            "\"${escapeXmlText(workspace.name)}\", running in a sandboxed proot rootfs environment.",
    )
    appendLine("- The workspace files area is mounted at `/workspace`. Use it as your working directory; files written there persist across turns of this conversation.")
    appendLine("- All paths passed to workspace tools must be absolute and inside the Rootfs (for example `/workspace/notes.md`).")
    appendLine("- Available tools:")
    appendLine("  - `workspace_read_file`: read file contents.")
    appendLine("  - `workspace_list`: list the available workspaces and their ids. Most workspace tools accept an optional `workspace` parameter to act on one of those ids instead of the bound workspace.")
    appendLine("  - `workspace_write_file` / `workspace_edit_file`: create files, or make precise edits to existing files.")
    appendLine("  - `workspace_shell`: run shell commands (the files area is mounted at /workspace). It also accepts the optional `workspace` parameter to run in another workspace (cross-workspace execution).")
    appendLine("  - `workspace_offload`: persist a large block of text under `/workspace/offloads/` and get back only a short preview plus its path.")
    appendLine("  - `config_get_all` / `config_set` / `config_revert`: session-level key/value config kept in `/workspace/config/settings.json` (values are JSON-encoded; `config_revert` restores the previous value recorded in the audit log).")
    appendLine("- Prefer `workspace_shell` for tasks that standard Unix tools handle well, and prefer `workspace_edit_file` for targeted edits over rewriting whole files.")
    appendLine(
        "- Long outputs (logs, full file dumps, diffs, transcripts, generated code) larger than " +
            "$OFFLOAD_HINT_BYTES bytes must go through `workspace_offload` instead of being echoed into the conversation; " +
            "read them back in smaller passes with `workspace_read_file` when needed, and list the area with `workspace_read_folder /workspace/offloads`.",
    )
    appendLine("- The skills directory is mounted at `/skills`. Each skill is a subdirectory `/skills/<skill-name>/` containing a `SKILL.md` (with `name` and `description` frontmatter) plus any supporting files. Read a skill's `SKILL.md` before using it, and follow its instructions.")
    appendLine("- `/sdcard` is the Android shared-storage mount only when the current execution has an explicit shared-storage grant. Otherwise use direct phone file tools and do not treat Rootfs lookalike paths as phone storage.")
    appendLine("- When the mount is authorized, exchange files with RikkaHub or Termux through `/sdcard/RikkaHubExchange`.")
    if (!cwd.isNullOrBlank()) {
        appendLine(
            "- Current working directory: `${escapeXmlText(cwd)}`. " +
                "Use this as the default context for file operations and shell commands.",
        )
    }
    append("</workspace>")
}

/**
 * Rootfs 未就绪（INSTALLING / DISABLED / 失败等）时的最小提醒（P1，2026-10-15）。
 *
 * 此时工作区工具只剩 `workspace_list`（只读枚举、不依赖 rootfs），其余工具不会被注入；
 * 把这件事写清楚，模型才不会忘记这个工具、或反复尝试不存在的工具。
 */
private fun buildWorkspaceNotReadyPrompt(workspace: WorkspaceEntity): String = buildString {
    // tag 统一口径（2026-10-16）：READY 档用 <workspace>；"没准备好"的两档（未绑定 / 未就绪）
    // 统一用 <workspace-setup>，与 ExTV 的语义一致。
    appendLine("<workspace-setup>")
    appendLine(
        "A Linux workspace named \"${escapeXmlText(workspace.name)}\" is bound to this assistant, " +
            "but its rootfs is not ready yet (status=${escapeXmlText(workspace.shellStatus)}).",
    )
    appendLine("- `workspace_list` is available even now: it lists the available workspaces and their ids.")
    appendLine(
        "- The other workspace tools (workspace_read_file / workspace_write_file / workspace_edit_file / " +
            "workspace_create_folder / workspace_read_folder / workspace_shell / workspace_offload, and the " +
            "config_* tools) are not available until the rootfs is installed and ready — do not call them " +
            "in this state; if the task needs them, tell the user to finish the rootfs installation first.",
    )
    append("</workspace-setup>")
}

/**
 * 存在 workspace 但未绑定到当前助手时的引导（P2，2026-10-16，迁移自 ExTV WorkspaceReminderTransformer）。
 *
 * 此时助手未绑定任何 workspace，工作区工具全部不会注入，只有 `workspace_list`（只读枚举、不依赖绑定）
 * 可用。写清"怎样绑定"和"绑定后仍要就绪"，模型才不会谎称自己有工具、或反复调用不存在的工具。
 */
private fun buildWorkspaceUnboundPrompt(): String = buildString {
    appendLine("<workspace-setup>")
    appendLine(
        "The user has a workspace, but none is bound to this assistant, so the workspace tools " +
            "(workspace_read_file, workspace_write_file, workspace_edit_file, workspace_create_folder, " +
            "workspace_read_folder, workspace_shell, workspace_offload, and the config_* tools) are NOT " +
            "available right now.",
    )
    appendLine("- `workspace_list` is available even now: it lists the available workspaces and their ids.")
    appendLine(
        "If the user asks to save files or run shell / Linux commands in a workspace, explain in the " +
            "user's language how to enable it:",
    )
    appendLine("1. Tap the + button in the chat input bar and select a workspace to bind it to this assistant.")
    appendLine(
        "2. If that workspace's shell is not Ready yet, open Extensions > Workspace, open the workspace, " +
            "and install its rootfs until the shell status shows Ready.",
    )
    appendLine(
        "Do not claim to have workspace tools or attempt to call them until a workspace is bound " +
            "and its shell is Ready.",
    )
    append("</workspace-setup>")
}

private fun UIMessage.appendText(extra: String): UIMessage {
    val updatedParts = parts.toMutableList()
    val firstTextIndex = updatedParts.indexOfFirst { it is UIMessagePart.Text }
    if (firstTextIndex >= 0) {
        val text = updatedParts[firstTextIndex] as UIMessagePart.Text
        updatedParts[firstTextIndex] = text.copy(text = text.text + extra)
    } else {
        updatedParts.add(UIMessagePart.Text(extra))
    }
    return copy(parts = updatedParts)
}
