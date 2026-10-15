package me.rerere.rikkahub.data.capability

import me.rerere.rikkahub.data.ai.InvocationSurfacePolicy
import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceAndKeyboardCapabilityCatalogTest {

    @Test
    fun `workspace tools are catalogued with their exact invocation surfaces`() {
        val expectedSurfaces = mapOf(
            "workspace_read_file" to ToolInvocationSurface.Background,
            // 工作区枚举（jude-port）：只读列出工作区，与 workspace_read_file 同面。
            "workspace_list" to ToolInvocationSurface.Background,
            "workspace_write_file" to ToolInvocationSurface.FileMutation,
            "workspace_edit_file" to ToolInvocationSurface.FileMutation,
            "workspace_create_folder" to ToolInvocationSurface.FileMutation,
            "workspace_read_folder" to ToolInvocationSurface.Background,
            "workspace_shell" to ToolInvocationSurface.UnboundedExecution,
            // 2026-10-14：大输出落盘工具，写入工作区，与 workspace_write_file 同面。
            "workspace_offload" to ToolInvocationSurface.FileMutation,
            // 工作区配置存储（jude-port）：get_all 只读，与 workspace_read_file 同面；
            // set/revert 写工作区配置，与 workspace_write_file 同面。
            "config_get_all" to ToolInvocationSurface.Background,
            "config_set" to ToolInvocationSurface.FileMutation,
            "config_revert" to ToolInvocationSurface.FileMutation,
        )

        val capability = CapabilityCatalog.capabilityOf(CapabilityId.WorkspaceTools)

        assertEquals(ImplementationState.Implemented, capability?.implementationState)
        assertEquals(expectedSurfaces.keys, capability?.toolNames)
        assertEquals(InvocationSurfacePolicy.LOCAL_UNLOCKED, capability?.allowedOrigins)
        expectedSurfaces.forEach { (toolName, expectedSurface) ->
            assertEquals(CapabilityId.WorkspaceTools, CapabilityCatalog.byToolName(toolName)?.id)
            assertEquals(expectedSurface, CapabilityCatalog.toolInvocationSurface(toolName))
        }
    }

    @Test
    fun `keyboard input alias keeps keyboard type catalog and approval policy`() {
        val canonical = CapabilityCatalog.byToolName("keyboard_type")
        val alias = CapabilityCatalog.byToolName("keyboard_input")

        assertEquals(CapabilityId.KeyboardControl, alias?.id)
        assertEquals(canonical?.id, alias?.id)
        assertEquals(
            ToolInvocationSurface.UnboundedExecution,
            CapabilityCatalog.toolInvocationSurface("keyboard_input"),
        )
        assertEquals(
            ToolApprovalDefaults.requiresApproval("keyboard_type"),
            ToolApprovalDefaults.requiresApproval("keyboard_input"),
        )
    }
}
