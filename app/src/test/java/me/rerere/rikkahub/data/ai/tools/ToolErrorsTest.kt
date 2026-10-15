package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolErrorsTest {

    @Test
    fun `envelope keeps the flat error and detail keys the host understands`() {
        val json = ToolErrors.envelope(
            error = ToolErrors.INVALID_ARGUMENT,
            detail = "path is required",
        )

        assertEquals(ToolErrors.INVALID_ARGUMENT, json["error"]?.jsonPrimitive?.content)
        assertEquals("path is required", json["detail"]?.jsonPrimitive?.content)
    }

    @Test
    fun `recovery defaults are derived from the error code`() {
        assertEquals(
            ToolErrors.RECOVERY_FIX_PARAM,
            ToolErrors.envelope(ToolErrors.INVALID_ARGUMENT, "bad arg")["recovery"]?.jsonPrimitive?.content,
        )
        assertEquals(
            ToolErrors.RECOVERY_RETRY,
            ToolErrors.envelope(ToolErrors.INTERNAL, "boom")["recovery"]?.jsonPrimitive?.content,
        )
        assertEquals(
            ToolErrors.RECOVERY_ABORT,
            ToolErrors.envelope(ToolErrors.PERMISSION_DENIED, "nope")["recovery"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `explicit recovery and hint win, and the parts list carries the same json`() {
        val text = ToolErrors.textFor(
            error = ToolErrors.NOT_FOUND,
            detail = "no such file",
            recovery = ToolErrors.RECOVERY_RETRY,
            hint = "call workspace_read_folder first",
        )

        val json = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
        assertEquals(ToolErrors.RECOVERY_RETRY, json["recovery"]?.jsonPrimitive?.content)
        assertEquals("call workspace_read_folder first", json["hint"]?.jsonPrimitive?.content)
        assertEquals(1, ToolErrors.partsFor(ToolErrors.NOT_FOUND, "no such file").size)
    }

    @Test
    fun `detail and hint are truncated so a runaway tool cannot flood the context`() {
        val json = ToolErrors.envelope(
            error = ToolErrors.INTERNAL,
            detail = "x".repeat(2_000),
            hint = "y".repeat(2_000),
        )

        assertTrue((json["detail"]?.jsonPrimitive?.content?.length ?: 0) <= 500)
        assertTrue((json["hint"]?.jsonPrimitive?.content?.length ?: 0) <= 300)
    }
}
