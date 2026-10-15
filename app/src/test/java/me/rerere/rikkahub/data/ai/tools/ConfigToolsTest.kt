package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 纯函数级测试：不依赖工作区 / Rootfs，只验证合并与回滚逻辑（configApplySet / configRevertValue）。
 */
class ConfigToolsTest {

    private fun settings(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
    }

    // ── configApplySet ──────────────────────────────────────────────────────────

    @Test
    fun `apply set adds a new key and reports a null previous`() {
        val (updated, previous) = configApplySet(JsonObject(emptyMap()), "theme", "\"dark\"")

        assertNull(previous)
        assertEquals(JsonPrimitive("dark"), updated["theme"])
    }

    @Test
    fun `apply set overwrites an existing key and reports the old value`() {
        val (updated, previous) = configApplySet(settings("theme" to "light"), "theme", "\"dark\"")

        assertEquals(JsonPrimitive("light"), previous)
        assertEquals(JsonPrimitive("dark"), updated["theme"])
    }

    @Test
    fun `apply set preserves other keys`() {
        val (updated, _) = configApplySet(settings("a" to "1", "b" to "2"), "c", "3")

        assertEquals(setOf("a", "b", "c"), updated.keys)
        // value 是 JSON 编码：`3` 解析成 JSON 数字，而不是字符串 "3"（243 CI 实测校正）
        assertEquals(JsonPrimitive(3), updated["c"])
    }

    @Test
    fun `apply set parses non string json values`() {
        val (updated, _) = configApplySet(JsonObject(emptyMap()), "flag", "true")

        assertEquals(JsonPrimitive(true), updated["flag"])
    }

    @Test
    fun `apply set rejects invalid json`() {
        try {
            configApplySet(JsonObject(emptyMap()), "theme", "dark") // unquoted -> invalid JSON
            fail("expected IllegalArgumentException for invalid JSON")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    // ── configRevertValue ───────────────────────────────────────────────────────

    @Test
    fun `revert restores the previous value from the latest set`() {
        val current = settings("theme" to "dark")
        val audit = listOf(
            """{"ts":1,"action":"set","key":"theme","previous":null,"next":"light"}""",
            """{"ts":2,"action":"set","key":"theme","previous":"light","next":"dark"}""",
        )

        val (updated, restored) = configRevertValue(current, audit, "theme")

        assertTrue(restored)
        assertEquals(JsonPrimitive("light"), updated["theme"])
    }

    @Test
    fun `revert deletes the key when the latest set previous is null`() {
        val current = settings("theme" to "dark")
        val audit = listOf(
            """{"ts":1,"action":"set","key":"theme","previous":null,"next":"dark"}""",
        )

        val (updated, restored) = configRevertValue(current, audit, "theme")

        assertTrue(restored)
        assertFalse(updated.containsKey("theme"))
    }

    @Test
    fun `revert deletes the key when there is no set history`() {
        val current = settings("theme" to "dark")

        val (updated, restored) = configRevertValue(current, emptyList(), "theme")

        assertFalse(restored)
        assertFalse(updated.containsKey("theme"))
    }

    @Test
    fun `revert ignores other keys and non set actions`() {
        val current = settings("theme" to "dark", "size" to "12")
        val audit = listOf(
            """{"ts":1,"action":"revert","key":"theme","previous":null,"next":null}""",
            """{"ts":2,"action":"set","key":"size","previous":"10","next":"12"}""",
        )

        val (updated, restored) = configRevertValue(current, audit, "theme")

        assertFalse(restored)
        assertFalse(updated.containsKey("theme"))
        assertEquals(JsonPrimitive("12"), updated["size"])
    }

    @Test
    fun `revert skips malformed audit lines`() {
        val current = settings("theme" to "dark")
        val audit = listOf(
            "not json",
            """{"ts":2,"action":"set","key":"theme","previous":"light","next":"dark"}""",
        )

        val (updated, restored) = configRevertValue(current, audit, "theme")

        assertTrue(restored)
        assertEquals(JsonPrimitive("light"), updated["theme"])
    }
}
