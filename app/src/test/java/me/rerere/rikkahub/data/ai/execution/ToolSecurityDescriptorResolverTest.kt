package me.rerere.rikkahub.data.ai.execution

import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.ai.tools.local.SPREADSHEET_TOOL_NAMES
import me.rerere.rikkahub.data.capability.CapabilityCatalog
import me.rerere.rikkahub.data.capability.ImplementationState
import me.rerere.rikkahub.data.voice.VOICE_CALL_AUDIO_TAG_SELECTION_TOOL_NAME
import me.rerere.rikkahub.privilege.PRIVILEGED_SHELL_TOOL_NAME
import me.rerere.rikkahub.owner.OwnerToolFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class ToolSecurityDescriptorResolverTest {
    private val resolver = DefaultToolSecurityDescriptorResolver()
    private val context = ToolExecutionContext(
        runId = Uuid.random(),
        conversationId = Uuid.random(),
        assistantId = "assistant",
        callOrigin = ToolCallOrigin.LocalChat,
    )

    @Test
    fun `all implemented capability tools have security descriptors`() {
        val missing = CapabilityCatalog.allCapabilities()
            .filter { it.implementationState == ImplementationState.Implemented }
            .flatMap { it.toolNames }
            .distinct()
            .filter { resolver.resolve(it, context) == null }

        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `mcp tools are per-call and cannot be permanently approved`() {
        val descriptor = checkNotNull(resolver.resolve("mcp__calendar__read", context))

        assertEquals(ToolDescriptorSource.MCP, descriptor.source)
        assertEquals(ToolDescriptorApproval.EVERY_CALL, descriptor.approval)
        assertFalse(descriptor.allowsPermanentApproval)
    }

    @Test
    fun `plugin tools are per-call and cannot be permanently approved`() {
        val pluginAware = DefaultToolSecurityDescriptorResolver(
            pluginToolKnown = { it == "plugin__0123456789ab__read_status" },
        )
        val descriptor = checkNotNull(
            pluginAware.resolve("plugin__0123456789ab__read_status", context)
        )

        assertEquals(ToolDescriptorSource.PLUGIN, descriptor.source)
        assertEquals(ToolDescriptorApproval.EVERY_CALL, descriptor.approval)
        assertFalse(descriptor.allowsPermanentApproval)
        assertNull(resolver.resolve("plugin__0123456789ab__read_status", context))
    }

    @Test
    fun `unknown tools fail descriptor resolution`() {
        assertNull(resolver.resolve("unregistered_runtime_tool", context))
    }

    @Test
    fun `every explicitly internal model tool has a descriptor`() {
        val missing = InternalToolSecurityCatalog.ALL.filter {
            resolver.resolve(it, context) == null
        }

        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `every spreadsheet manifest tool has a security descriptor and is catalogued under Files`() {
        // Regression: 2.5.4/215 injected excel_write/csv_parse/csv_write into the manifest but
        // never registered them here, so every call was rejected with
        // tool_security_descriptor_missing. LocalTools itself needs an Android Context and
        // cannot be built on the JVM, so both sides are pinned to the shared name constants.
        val missingDescriptor = SPREADSHEET_TOOL_NAMES.filter { resolver.resolve(it, context) == null }
        assertEquals(emptyList<String>(), missingDescriptor)

        val notCataloguedUnderFiles = SPREADSHEET_TOOL_NAMES.filter {
            CapabilityCatalog.byToolName(it)?.localToolOption != LocalToolOption.Files
        }
        assertEquals(emptyList<String>(), notCataloguedUnderFiles)
    }

    @Test
    fun `voice-call second-pass selection tool resolves descriptor and policy`() {
        // Regression (2026-10-13 registry audit): the tool is injected only by ChatService's
        // second-pass tagging run, not by LocalTools, so its registration drifted. Assert both
        // surfaces directly so a future re-check catches the drift without a device.
        assertNotNull(resolver.resolve(VOICE_CALL_AUDIO_TAG_SELECTION_TOOL_NAME, context))
        assertFalse(
            DefaultToolExecutionPolicyResolver().resolve(
                VOICE_CALL_AUDIO_TAG_SELECTION_TOOL_NAME,
                kotlinx.serialization.json.buildJsonObject {},
                context,
            ) == ToolExecutionPolicy.UNKNOWN,
        )
    }

    @Test
    fun `every compact owner tool has security and execution policy`() {
        val policy = DefaultToolExecutionPolicyResolver()
        val missing = OwnerToolFamily.entries.filter { family ->
            resolver.resolve(family.toolName, context) == null ||
                policy.resolve(
                    family.toolName,
                    kotlinx.serialization.json.buildJsonObject {},
                    context,
                ) == ToolExecutionPolicy.UNKNOWN
        }

        assertEquals(emptyList<OwnerToolFamily>(), missing)
    }

    @Test
    fun `dynamic privileged command is explicit and cannot be permanently approved`() {
        val descriptor = resolver.resolve(PRIVILEGED_SHELL_TOOL_NAME, context)

        assertNotNull(descriptor)
        assertEquals(ToolDescriptorApproval.EVERY_CALL, descriptor!!.approval)
        assertFalse(descriptor.allowsPermanentApproval)
    }
}
