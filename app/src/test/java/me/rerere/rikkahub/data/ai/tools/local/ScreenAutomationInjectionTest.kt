package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Group-level injection test for the ScreenAutomation assembly block in `LocalTools.kt`
 * (device-side ticket §2.3: "do these UI-automation tools get injected, and where is that
 * written down?").
 *
 * What this file asserts: the **injection surface** — i.e. that `LocalTools.getTools(...)`
 * assembles exactly eleven accessibility tools under the `LocalToolOption.ScreenAutomation`
 * gate, and that each factory actually declares the documented LLM tool name.
 *
 * What it does NOT assert: runtime availability. Whether a call succeeds at runtime depends
 * on the accessibility service being connected; when it is not, every one of these tools
 * returns the shared handle envelope
 * `{"error":"AccessibilityService not active","recovery":"Enable RikkaHub in Settings → Accessibility → Installed Apps"}`
 * (see `local/AccessibilityServiceHandle.kt` `notActiveEnvelope()`), not a different tool list.
 *
 * Why static source scanning instead of instantiating the real factory: `LocalTools` needs an
 * Android `Context` plus ~50 Koin-wired collaborators (DataStore, repositories, controllers),
 * so it cannot be constructed from a plain JVM unit test — there is no existing test in this
 * module that constructs it. This mirrors the established `ToolSchemaArrayItemsTest` pattern:
 * read `LocalTools.kt` as text, brace-match the gate block, and pin its contents. The
 * `display_session_*` tools share the same toggle but are gated further by
 * `managedVirtualDisplayEnabled` + origin restrictions and are deliberately out of scope here.
 */
class ScreenAutomationInjectionTest {

    /** (factory function, exact LLM tool name) — the documented eleven-tool surface. */
    private val surface: List<Pair<String, String>> = listOf(
        "tapTool" to "tap",
        "longPressTool" to "long_press",
        "swipeTool" to "swipe",
        "readWindowTreeTool" to "read_window_tree",
        "findNodeTool" to "find_node",
        "clickNodeTool" to "click_node",
        "setTextTool" to "set_text",
        "scrollTool" to "scroll",
        "globalActionTool" to "global_action",
        "takeScreenshotTool" to "take_screenshot",
        "wakeScreenTool" to "wake_screen",
    )

    private val screenAutomationGate = "if (options.contains(LocalToolOption.ScreenAutomation))"

    @Test
    fun `screen automation gate injects exactly the eleven accessibility tool factories`() {
        val text = localToolsSource().readText()

        val gateStart = text.indexOf(screenAutomationGate)
        assertTrue("ScreenAutomation assembly gate not found in LocalTools.kt", gateStart >= 0)
        assertEquals(
            "the ScreenAutomation gate must appear exactly once in the assembly",
            -1,
            text.indexOf(screenAutomationGate, gateStart + 1),
        )

        val openBrace = text.indexOf('{', gateStart)
        assertTrue(openBrace > gateStart)
        val closeBrace = findMatchingCloseBrace(text, openBrace)
        assertTrue("unbalanced braces after the ScreenAutomation gate", closeBrace > openBrace)
        val block = text.substring(openBrace, closeBrace + 1)

        // The block is a plain call sequence (no string literals / braces in comments), so the
        // naive brace matcher above and this factory-call regex are exact.
        val factories = Regex("\\b(\\w+Tool)\\(").findAll(block).map { it.groupValues[1] }.toSet()
        assertEquals(
            "factories injected under the ScreenAutomation gate: ${factories.sorted().joinToString()}",
            surface.map { it.first }.toSet(),
            factories,
        )
    }

    @Test
    fun `every injected factory declares the documented LLM tool name`() {
        val localDir = File(moduleRoot(), "src/main/java/me/rerere/rikkahub/data/ai/tools/local")
        val files = localDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("no local tool sources found at ${localDir.path}", files.isNotEmpty())

        val missing = surface.filterNot { (factory, toolName) ->
            files.any { file -> file.readText().factoryBuildsToolName(factory, toolName) }
        }
        assertTrue(
            "factories whose first declared tool name does not match the injected surface: " +
                missing.joinToString { "${it.first} -> ${it.second}" },
            missing.isEmpty(),
        )
    }

    /** Starting at `fun <factory>(`, the first `name = "..."` must be the documented tool name. */
    private fun String.factoryBuildsToolName(factory: String, toolName: String): Boolean {
        val fnStart = indexOf("fun $factory(")
        if (fnStart < 0) return false
        val nameMatch = Regex("name = \"([a-z_]+)\"").find(this, fnStart) ?: return false
        return nameMatch.groupValues[1] == toolName
    }

    private fun findMatchingCloseBrace(text: String, openIdx: Int): Int {
        var depth = 0
        var i = openIdx
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    private fun localToolsSource(): File {
        val file = File(moduleRoot(), "src/main/java/me/rerere/rikkahub/data/ai/tools/LocalTools.kt")
        assertTrue("LocalTools.kt not found at ${file.path}", file.isFile)
        return file
    }

    private fun moduleRoot(): File {
        // JUnit's working dir is usually the module root. Fall back to walking up if the
        // tests get invoked from the repo root (same resolution as ToolSchemaArrayItemsTest).
        val cwdPath = System.getProperty("user.dir") ?: error("user.dir is not set")
        val cwd = File(cwdPath)
        if (File(cwd, "src/main").isDirectory) return cwd
        val maybeApp = File(cwd, "app")
        if (File(maybeApp, "src/main").isDirectory) return maybeApp
        error("Could not locate the :app module root from cwd=$cwd")
    }
}
