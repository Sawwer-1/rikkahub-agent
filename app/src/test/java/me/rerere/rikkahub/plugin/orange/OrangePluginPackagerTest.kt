package me.rerere.rikkahub.plugin.orange

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 262（259 遗留）：橘瓣包在导入时被翻译成本仓可安装的包。
 *
 * 259 只落翻译器，没有任何调用点——界面上选到橘瓣包会被安装器按「缺 plugin.json」直接拒。
 * 这里锁住「识别 → 翻译 → 重新打包」的落盘结果。
 */
class OrangePluginPackagerTest {
    @Test
    fun `orange package is detected and translated into an installable zip`() {
        val dir = Files.createTempDirectory("orange-pack").toFile()
        val source = File(dir, "orange.zip")
        writeZip(
            source,
            mapOf(
                "manifest.json" to """
                    {
                      "name": "Hello 插件",
                      "version": "1.0.0",
                      "entry": "main.js",
                      "tools": [
                        {"name": "say_hello", "description": "向用户打招呼",
                         "parameters": [{"name":"who","type":"string","required":true}]}
                      ],
                      "permissions": ["ai_chat"]
                    }
                """.trimIndent(),
                "main.js" to "exports.say_hello = function (p) { return {ok: true}; };",
            ),
        )

        assertTrue(OrangePluginPackager.looksLikeOrangePlugin(source))

        val prepared = OrangePluginPackager.prepare(source, File(dir, "out"))

        assertEquals("hello", prepared.pluginId)
        assertEquals(1, prepared.toolCount)
        assertTrue(prepared.archive.isFile)
        // 语义损失要留痕（ai_chat 本仓没有等价能力）。
        assertTrue(prepared.warnings.any { "ai_chat" in it })

        ZipFile(prepared.archive).use { zip ->
            assertTrue(zip.getEntry("plugin.json") != null)
            assertTrue(zip.getEntry("index.html") != null)
            val manifest = Json.parseToJsonElement(
                zip.getInputStream(zip.getEntry("plugin.json")).use { it.readBytes().decodeToString() },
            ).jsonObject
            assertEquals("index.html", manifest["entry"]!!.jsonPrimitive.content)
            val slug = manifest["tools"]!!.jsonArray.single().jsonObject["slug"]!!.jsonPrimitive.content
            assertEquals("say_hello", slug)
        }
    }

    @Test
    fun `strict plugin package is not treated as orange`() {
        val dir = Files.createTempDirectory("strict-pack").toFile()
        val source = File(dir, "strict.zip")
        writeZip(
            source,
            mapOf(
                "plugin.json" to """{"schemaVersion":1}""",
                "index.html" to "<html></html>",
            ),
        )

        assertFalse(OrangePluginPackager.looksLikeOrangePlugin(source))
    }

    @Test
    fun `orange package without tools fails with a stable reason`() {
        val dir = Files.createTempDirectory("empty-pack").toFile()
        val source = File(dir, "empty.zip")
        writeZip(
            source,
            mapOf(
                "manifest.json" to """{"name":"ui only","version":"1.0.0"}""",
                "main.js" to "",
            ),
        )

        val error = runCatching { OrangePluginPackager.prepare(source, File(dir, "out")) }
            .exceptionOrNull()

        assertEquals("orange_manifest_has_no_tools", error?.message)
    }

    private fun writeZip(file: File, entries: Map<String, String>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
