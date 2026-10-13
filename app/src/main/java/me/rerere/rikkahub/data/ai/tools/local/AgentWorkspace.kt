package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import java.io.File

/**
 * Termux-style sandbox for the on-device agent.
 *
 * The agent's "home" lives in `${context.filesDir}/workspace/`. Tools that take a path
 * argument expand `~` and `~/foo` to that directory before validation, so the LLM can
 * write to a stable, OS-blessed location without knowing the absolute path:
 *
 *   write_text_file(path = "~/learnings/ERRORS.md", content = "...")
 *   list_files(path = "~/skill-cache/")
 *
 * Why this directory:
 *  - `/data/data/<own-package>/files/` has stayed open for app-private writes since
 *    Android 1; immune to scoped-storage tightening across releases.
 *  - PathSafetyGuard's OWN_APP_PREFIXES already allows it; no policy change needed.
 *  - Excluded from cloud backup via the existing `exclude domain="file" path=""` (no, we
 *    DON'T want that — workspace state should restore on device migration, not be wiped).
 *    Backup default is "include this dir"; explicit exclude only for skill secrets.
 *
 * This is the agent equivalent of Termux's `$HOME = /data/data/com.termux/files/home/`:
 * private to the agent, no shared-storage permissions required, doesn't pollute
 * `/sdcard/Documents/` with internal scratch files. Files the user should *see* (saved
 * screenshots, exported reports) still belong on `/sdcard/...`.
 */
object AgentWorkspace {
    private const val DIR_NAME = "workspace"
    private const val MOUNTED_WORKSPACE_PREFIX = "/workspace"

    @Volatile
    private var workspaceDir: File? = null

    /**
     * Host-side directory the Linux workspace binds to `/workspace`
     * (see `me.rerere.workspace.WorkspaceMountResolver`). Null until the app has resolved a
     * workspace; `/workspace/...` then stays a literal path, i.e. exactly the old behaviour.
     */
    @Volatile
    private var mountedWorkspaceFilesDir: String? = null

    /** Wire up at app start (RikkaHubApp.onCreate) — idempotent. */
    fun init(context: Context) {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        workspaceDir = dir
    }

    /**
     * Register the host directory exposed as `/workspace` inside the shell.
     *
     * 2026-10-13 device evidence: the two tool groups disagreed on the root.
     * `workspace_write_file("/workspace/tmp/x")` landed in the PRoot bind mount, while
     * `read_file("~/tmp/x")`, `zip_files(["~/tmp/x"])` and `csv_write("/workspace/...")` looked
     * under `${filesDir}/workspace` and failed with not_found / source_unreadable /
     * destination_unwritable. Accepting the mount prefix here puts both groups into one namespace.
     * The expanded path still lives under `filesDir`, so PathSafetyGuard keeps accepting it.
     */
    fun registerMountedWorkspaceFilesDir(dir: File?) {
        mountedWorkspaceFilesDir = dir?.absolutePath
    }

    /** Absolute path of `~`. Throws if [init] hasn't been called yet. */
    fun rootPath(): String = workspaceDir?.absolutePath
        ?: error("AgentWorkspace not initialised — call AgentWorkspace.init(context) first")

    /**
     * Expand a tilde-prefixed path to the workspace dir, and the Linux mount prefix to its host
     * directory. No-op for everything else.
     *   "~"            → "/data/data/<pkg>/files/workspace"
     *   "~/learnings/" → "/data/data/<pkg>/files/workspace/learnings/"
     *   "/workspace/x" → "/data/data/<pkg>/files/workspaces/<root>/files/x"  (once registered)
     *   "/sdcard/x"    → "/sdcard/x"   (unchanged)
     */
    fun expand(raw: String): String {
        if (raw.isEmpty()) return raw
        if (raw == "~") return rootPath()
        if (raw.startsWith("~/")) return rootPath() + raw.removePrefix("~")
        val mounted = mountedWorkspaceFilesDir
        if (mounted != null) {
            if (raw == MOUNTED_WORKSPACE_PREFIX) return mounted
            if (raw.startsWith("$MOUNTED_WORKSPACE_PREFIX/")) {
                return mounted + raw.removePrefix(MOUNTED_WORKSPACE_PREFIX)
            }
        }
        return raw
    }
}
