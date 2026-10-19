package me.rerere.rikkahub.plugin.orange

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B07 / S1：橘瓣清单 → 本仓插件格式的翻译。
 *
 * 用调研文档里的两个真实样例（官方 Hello World、天气插件）做基线：
 * 翻译产物要能被本仓校验器接受（翻译函数内部已 `validate`），并且工具名/参数/必填项不丢。
 */
class OrangePluginTranslatorTest {
    private val helloManifest = """
        {
          "name": "Hello 插件",
          "version": "1.0.0",
          "entry": "main.js",
          "tools": [
            {
              "name": "say_hello",
              "description": "向用户打招呼",
              "parameters": [
                {"name": "name", "type": "string", "description": "用户名字", "required": false}
              ]
            }
          ]
        }
    """.trimIndent()

    private val helloJs = """
        exports.say_hello = function (params) {
          var name = params.name || "陌生人";
          return { success: true, greeting: "你好，" + name + "！" };
        };
    """.trimIndent()

    @Test
    fun `official hello world sample translates into an installable plugin`() {
        val translated = translateOrangePlugin(helloManifest, helloJs)

        // 中文名没有 id：从 name 归一，且必须满足本仓 [a-z0-9][a-z0-9_-]{1,62}。
        assertEquals("hello", translated.pluginId)
        assertEquals("index.html", translated.entryHtmlName)
        assertEquals(1, translated.toolCount)

        val manifest = Json.parseToJsonElement(translated.pluginJson).jsonObject
        assertEquals("1", manifest["schemaVersion"]!!.jsonPrimitive.content)
        assertEquals("index.html", manifest["entry"]!!.jsonPrimitive.content)
        val tool = manifest["tools"]!!.jsonArray.single().jsonObject
        assertEquals("say_hello", tool["slug"]!!.jsonPrimitive.content)
        assertEquals("say_hello", tool["handler"]!!.jsonPrimitive.content)
        val schema = tool["inputSchema"]!!.jsonObject
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        assertEquals(
            "string",
            schema["properties"]!!.jsonObject["name"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
        // 非必填：schema 里不应出现 required。
        assertFalse(schema.containsKey("required"))

        // 入口 HTML 必须真的把工具挂到 window 上，并转发到橘瓣的导出名。
        assertTrue(translated.entryHtml.contains("window.say_hello"))
        assertTrue(translated.entryHtml.contains("__orange_call('say_hello'"))
        assertTrue(translated.entryHtml.contains("exports.say_hello"))
    }

    @Test
    fun `weather sample keeps parameter types and the required list`() {
        val manifest = """
            {
              "id": "weather-helper",
              "name": "天气",
              "version": "0.2.1",
              "tools": [
                {
                  "name": "get_weather",
                  "description": "查询城市天气",
                  "parameters": [
                    {"name": "city", "type": "string", "description": "城市名", "required": true},
                    {"name": "days", "type": "integer", "description": "预报天数"},
                    {"name": "include_hourly", "type": "boolean"},
                    {"name": "extra", "type": "weird_type"}
                  ]
                }
              ]
            }
        """.trimIndent()

        val translated = translateOrangePlugin(manifest, "exports.get_weather = function(){return {};};")
        val schema = Json.parseToJsonElement(translated.pluginJson).jsonObject["tools"]!!
            .jsonArray.single().jsonObject["inputSchema"]!!.jsonObject

        assertEquals(listOf("city"), schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        val properties = schema["properties"]!!.jsonObject
        assertEquals("string", properties["city"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("integer", properties["days"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("boolean", properties["include_hourly"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        // 未知类型按 string 收，别让整包因为一个类型名装不上。
        assertEquals("string", properties["extra"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `invalid ids and tool names are normalized instead of rejected`() {
        val manifest = """
            {
              "name": "123 My Plugin!!",
              "version": "1.0.0",
              "tools": [
                {"name": "Do-Thing", "description": "x"},
                {"name": "do_thing", "description": "y"}
              ]
            }
        """.trimIndent()

        val translated = translateOrangePlugin(manifest, "")

        // 数字开头要加前缀，大小写/符号都要归一。
        assertTrue(translated.pluginId.startsWith("orange-"))
        val slugs = Json.parseToJsonElement(translated.pluginJson).jsonObject["tools"]!!
            .jsonArray.map { it.jsonObject["slug"]!!.jsonPrimitive.content }
        // "Do-Thing" 与 "do_thing" 归一后撞名：第二个必须加后缀，不能重复。
        assertEquals(listOf("do_thing", "do_thing_2"), slugs)
    }

    @Test
    fun `plugin without tools is rejected with a stable reason`() {
        val error = runCatching {
            translateOrangePlugin("""{"name":"ui only","version":"1.0.0"}""", "")
        }.exceptionOrNull()

        assertEquals("orange_manifest_has_no_tools", error?.message)
    }

    @Test
    fun `network hosts are prefilled lowercase deduped and counted`() {
        val manifest = """
            {"name":"net","version":"1.0.0","tools":[{"name":"ping","description":"x"}]}
        """.trimIndent()

        val translated = translateOrangePlugin(
            manifest,
            "",
            prefillNetworkHosts = listOf("API.Example.com", "api.example.com", "not a host", ""),
        )

        val hosts = Json.parseToJsonElement(translated.pluginJson).jsonObject["permissions"]!!
            .jsonObject["networkHosts"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("api.example.com"), hosts)
        assertTrue(translated.warnings.any { "被丢弃" in it })
    }

    @Test
    fun `orange host capabilities are reported as warnings`() {
        val manifest = """
            {
              "name":"chatty","version":"1.0.0",
              "permissions":["ai_chat","disable_native_selection"],
              "tools":[{"name":"ask","description":"问模型"}]
            }
        """.trimIndent()

        val translated = translateOrangePlugin(manifest, "")

        assertTrue(translated.warnings.any { "ai_chat" in it })
        assertTrue(translated.warnings.any { "disable_native_selection" in it })
    }

    @Test
    fun `script tags inside main js cannot break out of the entry html`() {
        val html = buildEntryHtml(
            mainJs = "var s = '</script><img src=x onerror=alert(1)>';",
            bindings = listOf("ping" to "ping"),
        )

        // 注入的那处必须被转义；全文只应剩我们自己的收尾 </script>。
        assertTrue(html.contains("<\\/script"))
        assertEquals(1, Regex("</script").findAll(html).count())
    }

    @Test
    fun `tool count above the host limit is truncated with a warning`() {
        val tools = (1..18).joinToString(",") { """{"name":"t$it","description":"d"}""" }
        val translated = translateOrangePlugin(
            """{"name":"many","version":"1.0.0","tools":[$tools]}""",
            "",
        )

        assertEquals(16, translated.toolCount)
        assertTrue(translated.warnings.any { "16" in it })
    }
}
