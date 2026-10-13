package me.rerere.rikkahub.data.ai.execution

import me.rerere.rikkahub.owner.OwnerToolFamily

/**
 * Application-owned model tools that are intentionally outside [CapabilityCatalog].
 * Keeping the names here prevents a trusted-but-uncatalogued surface (memory, skills, setup,
 * privileged management) from becoming an accidental unknown-tool bypass.
 */
object InternalToolSecurityCatalog {
    val READ_ONLY: Set<String> = setOf(
        "recent_chats",
        "conversation_search",
        "conversation_list_recent",
        "conversation_read_recent",
        "memory_query",
        "dream_view",
        "skill_get_content",
        "use_skill",
        "rikkahub_state_get",
        "setup_plan",
        "setup_verify",
        "display_session_list",
        "display_session_status",
        "execution_list",
        "execution_status",
        "execution_logs",
        "linux_profile_list",
        "linux_grant_list",
        "linux_session_inspect",
        "linux_session_list",
        // P2.1 progressive second-user directory helpers. They are deliberately explicit
        // internal entries so ToolRuntime never treats a new helper as an unknown bypass.
        "tool_catalog_search",
        "tool_catalog_list",
        "tool_catalog_open",
        // P1 pet diary reads are scoped to the selected second-user conversation. They
        // still go through ToolExecutionGate; this list merely prevents an accidental
        // unknown-tool rejection after the provider has deliberately exposed them.
        "pet_dialogue_current",
        "pet_diary_list",
        "pet_diary_read",
        // P2 vault access is intentionally metadata-only. The secret value has no model tool.
        "secret_vault_list",
        "secret_vault_test_binding",
        // Voice-call second-pass tagging (jude port). ChatService injects this tool directly
        // into a dedicated GenerationHandler.generateText(tools = …) run instead of LocalTools,
        // so the registry audit almost missed it: without this entry every tagging call was
        // rejected by ToolRuntime.assess as tool_security_descriptor_missing and the feature
        // silently fell back to plain text (4th occurrence of that defect class, found by the
        // 2026-10-13 registry audit). Read-only: it validates indices and returns a selection.
        me.rerere.rikkahub.data.voice.VOICE_CALL_AUDIO_TAG_SELECTION_TOOL_NAME,
    )

    val MUTATING: Set<String> = setOf(
        "conversation_send_message",
        "conversation_create",
        "conversation_update",
        "conversation_delete",
        "assistant_update",
        "assistant_toggle_tool",
        "assistant_update_skills",
        "assistant_update_mcp_servers",
        "lorebook_create",
        "lorebook_update",
        "lorebook_delete",
        "mode_injection_update",
        "app_settings_update",
        "setup_apply",
        "display_session_create",
        "display_session_close",
        "execution_stop",
        "linux_run",
        "linux_grant_request",
        "linux_grant_revoke",
        "linux_session_create",
        "linux_session_exec",
        "linux_session_close",
        "tool_experience_update",
        "tool_fast_lane_manage",
        "pet_diary_update_metadata",
        "pet_diary_soft_delete",
        "pet_diary_restore",
        "secret_vault_create_slot",
        "secret_vault_set_binding",
        // Heartbeat scheduling (jude port) writes the assistant-private wake plan. LocalTools
        // injects it whenever the heartbeat master switch is on; without this registration the
        // runtime rejected every call with tool_security_descriptor_missing (3rd occurrence of
        // that defect class, found by the 2026-10-13 registry audit). Approval stays
        // CALL_DEFINED — the tool itself declares needsApproval={false} by design.
        me.rerere.rikkahub.personal.heartbeat.HeartbeatScheduleTool.NAME,
    ) + OwnerToolFamily.entries.mapTo(linkedSetOf()) { it.toolName }

    val ARGUMENT_DEPENDENT: Set<String> = setOf("memory_tool")
    val ALL: Set<String> = READ_ONLY + MUTATING + ARGUMENT_DEPENDENT
}
