package me.rerere.rikkahub.plugin.orange

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.plugin.PluginHookManifest
import me.rerere.rikkahub.plugin.PluginManifestV1
import me.rerere.rikkahub.plugin.PluginManifestValidator
import me.rerere.rikkahub.plugin.PluginPermissions
import me.rerere.rikkahub.plugin.PluginToolManifest

/**
 * 橘瓣（OrangeChat）插件 → 本仓插件格式的翻译器（B07 / S1 + S4）。
 *
 * 两边的差别（调研见桌面《橘瓣插件系统调研与插件格式-20261017.md》）：
 *
 * | 橘瓣 | 本仓 |
 * |---|---|
 * | `manifest.json` + `main.js`（`exports.xxx` 导出同步函数） | `plugin.json` + `index.html`（运行时从 `window[handler]` 取函数） |
 * | 工具参数是平铺数组 `parameters[{name,type,description,required}]` | 要求标准 JSON Schema `{type:object, properties, required}` |
 * | `permissions` 只声明 `ai_chat` / `disable_native_selection` | `stateRead/storageRead/storageWrite/networkHosts` |
 *
 * 因此第 1 步只做两件事：
 * 1. 把清单翻译成 `plugin.json`（工具名归一、参数数组 → JSON Schema、域名预填）；
 * 2. 生成一个 `index.html`：内嵌原 `main.js`，为每个工具挂 `window.<slug>` 转发到
 *    `exports.<原名>`（橘瓣的导出名可能与 slug 不同）。
 *
 * 明确不做：`config[]` 表单（S5）、`hooks[]`（S6）、`ai_chat` 桥（需要宿主能力，见 warnings）。
 */
private val ORANGE_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private const val MAX_TRANSLATED_TOOLS = 16
private const val MAX_NETWORK_HOSTS = 16
private val NETWORK_HOST = Regex(
    "(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
)

@Serializable
internal data class OrangeManifest(
    val id: String? = null,
    val name: String,
    val version: String,
    val entry: String = "main.js",
    val description: String? = null,
    val tools: List<OrangeTool> = emptyList(),
    val permissions: List<String> = emptyList(),
)

@Serializable
internal data class OrangeTool(
    val name: String,
    val description: String = "",
    val parameters: List<OrangeParameter> = emptyList(),
)

@Serializable
internal data class OrangeParameter(
    val name: String,
    val type: String = "string",
    val description: String = "",
    val required: Boolean = false,
)

/** 翻译结果：`pluginJson` 与 [entryHtml] 一起写进插件包即可被本仓安装。 */
data class TranslatedOrangePlugin(
    val pluginId: String,
    val pluginJson: String,
    val entryHtmlName: String,
    val entryHtml: String,
    val toolCount: Int,
    /** 语义损失与需要人工确认的点（例如 `ai_chat` 无对应宿主能力）。 */
    val warnings: List<String>,
)

/**
 * @param prefillNetworkHosts 域名预填（S4）：橘瓣清单不声明域名，这里由调用方给默认值
 *   （例如用户上次授权过的常用域名），会做小写化/去重/上限截断。
 */
internal fun translateOrangePlugin(
    manifestJson: String,
    mainJs: String,
    prefillNetworkHosts: List<String> = emptyList(),
): TranslatedOrangePlugin {
    val orange = ORANGE_JSON.decodeFromString(OrangeManifest.serializer(), manifestJson)
    require(orange.tools.isNotEmpty()) { "orange_manifest_has_no_tools" }

    val warnings = ArrayList<String>()
    val pluginId = normalizePluginId(orange.id ?: orange.name)

    val usedSlugs = LinkedHashSet<String>()
    val bindings = ArrayList<Pair<String, String>>() // slug -> 橘瓣导出名
    val tools = ArrayList<PluginToolManifest>()
    for (raw in orange.tools.take(MAX_TRANSLATED_TOOLS)) {
        val slug = uniqueSlug(normalizeSlug(raw.name), usedSlugs)
        val exportName = raw.name.trim().ifBlank { slug }
        bindings.add(slug to exportName)
        tools.add(
            PluginToolManifest(
                slug = slug,
                description = raw.description.trim().ifBlank { raw.name.trim() }.take(500),
                handler = slug,
                inputSchema = buildInputSchema(raw.parameters),
            ),
        )
    }
    if (orange.tools.size > MAX_TRANSLATED_TOOLS) {
        warnings.add("工具超过 $MAX_TRANSLATED_TOOLS 个，只翻译前 $MAX_TRANSLATED_TOOLS 个")
    }
    orange.permissions.forEach { permission ->
        // 这两种是橘瓣宿主能力，本仓第一步没有等价物：装得上、但工具内调用会失败，必须说清楚。
        if (permission == "ai_chat" || permission == "disable_native_selection") {
            warnings.add("橘瓣权限 `$permission` 暂无等价实现（第 1 步只支持纯工具）")
        }
    }

    val hosts = prefillNetworkHosts
        .map { it.trim().lowercase() }
        .filter { NETWORK_HOST.matches(it) }
        .distinct()
        .take(MAX_NETWORK_HOSTS)
    if (hosts.size < prefillNetworkHosts.size) {
        warnings.add("域名预填里有 ${prefillNetworkHosts.size - hosts.size} 条被丢弃（不是合法 FQDN）")
    }

    val manifest = PluginManifestV1(
        schemaVersion = 1,
        id = pluginId,
        name = orange.name.trim().take(80),
        version = orange.version.trim().take(64),
        entry = ENTRY_HTML,
        permissions = PluginPermissions(networkHosts = hosts),
        tools = tools,
        hooks = PluginHookManifest(),
    )
    // 翻译产物必须能通过本仓校验，否则宁可在这里失败（不要生成一个装不上的包）。
    PluginManifestValidator.validate(manifest)

    val pluginJson = Json {
        prettyPrint = true
        encodeDefaults = true
    }.encodeToString(PluginManifestV1.serializer(), manifest)

    return TranslatedOrangePlugin(
        pluginId = pluginId,
        pluginJson = pluginJson,
        entryHtmlName = ENTRY_HTML,
        entryHtml = buildEntryHtml(mainJs, bindings),
        toolCount = tools.size,
        warnings = warnings,
    )
}

private const val ENTRY_HTML = "index.html"

/** 插件 id 归一：小写、非 `[a-z0-9_-]` 变 `-`；数字开头或过短时加前缀；≤62。 */
internal fun normalizePluginId(raw: String): String {
    val base = raw.trim().lowercase()
        .map { ch -> if (ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_') ch else '-' }
        .joinToString("")
        .replace(Regex("-{2,}"), "-")
        .trim('-', '_')
    val prefixed = when {
        base.isEmpty() -> "orange-plugin"
        base[0] in '0'..'9' -> "orange-$base"
        base.length < 2 -> "orange-$base"
        else -> base
    }
    return prefixed.take(62).trim('-', '_').ifEmpty { "orange-plugin" }
}

/** 工具 slug 归一：必须以小写字母开头，其余非法字符折成 `_`。 */
internal fun normalizeSlug(raw: String): String {
    val mapped = raw.trim().lowercase()
        .map { ch -> if (ch in 'a'..'z' || ch in '0'..'9') ch else '_' }
        .joinToString("")
        .replace(Regex("_{2,}"), "_")
        .trim('_')
    val withLetter = mapped.ifEmpty { "tool" }
    val start = if (withLetter[0] in 'a'..'z') withLetter else "t$withLetter"
    return start.take(63)
}

private fun uniqueSlug(base: String, used: MutableSet<String>): String {
    var candidate = base
    var suffix = 2
    while (!used.add(candidate)) {
        val tail = "_$suffix"
        candidate = base.take(63 - tail.length) + tail
        suffix++
    }
    return candidate
}

/** 橘瓣的平铺参数数组 → JSON Schema（本仓要求 `type:object`）。 */
private fun buildInputSchema(parameters: List<OrangeParameter>): JsonObject {
    val properties = buildJsonObject {
        parameters.forEach { parameter ->
            val name = parameter.name.trim().ifBlank { return@forEach }
            put(
                name,
                buildJsonObject {
                    put("type", schemaType(parameter.type))
                    if (parameter.description.isNotBlank()) {
                        put("description", parameter.description.trim().take(300))
                    }
                },
            )
        }
    }
    val required = parameters
        .filter { it.required && it.name.isNotBlank() }
        .map { it.name.trim() }
        .distinct()
    return buildJsonObject {
        put("type", "object")
        put("properties", properties)
        if (required.isNotEmpty()) {
            put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        }
    }
}

/** 橘瓣声明 string/number/integer/boolean/object/array；未知类型按 string 处理（附描述已够用）。 */
internal fun schemaType(orangeType: String): String = when (orangeType.trim().lowercase()) {
    "number" -> "number"
    "integer" -> "integer"
    "boolean" -> "boolean"
    "object" -> "object"
    "array" -> "array"
    else -> "string"
}

/**
 * 生成入口 HTML：内嵌原 `main.js`，按 slug 挂 `window.<slug>` 转发到 `exports.<原名>`。
 *
 * 用 IIFE 包住 `main.js`：橘瓣脚本是同步 ES5、顶层 `exports.xxx = function(){}`，
 * 包一层既避免污染全局，也让 `exports` 成为我们提供的对象。
 */
internal fun buildEntryHtml(mainJs: String, bindings: List<Pair<String, String>>): String {
    val safeJs = mainJs.replace("</script", "<\\/script")
    val forwarders = buildString {
        bindings.forEach { (slug, exportName) ->
            append("window.").append(slug).append(" = function (params) {\n")
            append("  return __orange_call(").append(quote(exportName)).append(", params);\n")
            append("};\n")
        }
    }
    return buildString {
        append("<!DOCTYPE html>\n<html>\n<head><meta charset=\"utf-8\"></head>\n<body>\n<script>\n")
        append("var exports = {};\n")
        append("(function () {\n")
        append(safeJs)
        append("\n})();\n")
        append("function __orange_call(name, params) {\n")
        append("  var fn = exports[name];\n")
        append("  if (typeof fn !== 'function') throw new Error('orange_export_missing:' + name);\n")
        append("  return fn(params || {});\n")
        append("}\n")
        append(forwarders)
        append("</script>\n</body>\n</html>\n")
    }
}

private fun quote(value: String): String =
    "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
