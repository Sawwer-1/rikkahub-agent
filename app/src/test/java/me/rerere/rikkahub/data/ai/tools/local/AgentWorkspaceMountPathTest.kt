package me.rerere.rikkahub.data.ai.tools.local

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 2026-10-13 device evidence: host-side file tools resolved `~/` to `${filesDir}/workspace` while
 * the shell's `/workspace` bind mount is `${filesDir}/workspaces/<root>/files`
 * (see `me.rerere.workspace.WorkspaceMountResolver`). As a result `zip_files(["~/…"])`,
 * `list_zip_contents("~/x.zip")` and `csv_write("/workspace/…")` all failed on paths the
 * workspace_* tool group had just written. These tests pin the unified resolution.
 *
 * Only the `/workspace` mount branch is exercised here: the `~` branch needs an Android Context
 * via [AgentWorkspace.init] and is covered by the instrumented suite.
 */
class AgentWorkspaceMountPathTest {
    private val mount = File("/data/user/0/me.rerere.rikkahub/files/workspaces/abc/files")

    @After
    fun tearDown() {
        AgentWorkspace.registerMountedWorkspaceFilesDir(null)
    }

    @Test
    fun `mounted workspace prefix resolves to the registered host directory`() {
        AgentWorkspace.registerMountedWorkspaceFilesDir(mount)

        assertEquals(mount.path, AgentWorkspace.expand("/workspace"))
        assertEquals(File(mount, "tmp/a.csv").path, AgentWorkspace.expand("/workspace/tmp/a.csv"))
    }

    @Test
    fun `unregistered mount stays literal and other roots are untouched`() {
        AgentWorkspace.registerMountedWorkspaceFilesDir(null)

        assertEquals("/workspace/tmp/a.csv", AgentWorkspace.expand("/workspace/tmp/a.csv"))
        assertEquals("/sdcard/Documents/a.csv", AgentWorkspace.expand("/sdcard/Documents/a.csv"))
        assertEquals("file:///sdcard/a.zip", AgentWorkspace.expand("file:///sdcard/a.zip"))
    }

    @Test
    fun `a path that merely starts with the word workspace is not remapped`() {
        AgentWorkspace.registerMountedWorkspaceFilesDir(mount)

        assertEquals("/workspaceX/a.csv", AgentWorkspace.expand("/workspaceX/a.csv"))
    }
}
