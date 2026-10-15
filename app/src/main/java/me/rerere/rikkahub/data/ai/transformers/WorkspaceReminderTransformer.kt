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
 * 助手绑定 workspace 时在系统提示词中追加一段引导：rootfs 就绪（READY）时给出完整的环境与
 * 工具清单；**未就绪时只给一小段状态说明**——此时工作区工具只剩 `workspace_list`（只读枚举），
 * 提示里必须提到它，否则模型会忘记这个工具或反复试不存在的工具（P1，2026-10-15）。
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
        val workspaceId = ctx.assistant.workspaceId?.toString() ?: return messages
        val workspace = workspaceRepository.getById(workspaceId) ?: return messages
        val prompt = buildList {
            if (workspace.shellStatus == WorkspaceShellStatus.READY.name) {
                add(buildWorkspacePrompt(workspace, ctx.workspaceCwd))
            } else {
                add(buildWorkspaceNotReadyPrompt(workspace))
            }
            rulesResolver.resolve(workspace.id, ctx.workspaceCwd)
                .toPrompt()
                .takeIf(String::isNotBlank)
                ?.let(::add)
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
    appendLine("<workspace>")
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
    append("</workspace>")
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
