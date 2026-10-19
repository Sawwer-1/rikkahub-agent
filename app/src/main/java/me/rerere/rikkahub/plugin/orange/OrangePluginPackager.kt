package me.rerere.rikkahub.plugin.orange

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** 橘瓣包翻译后产出的本仓可直接安装的 zip。 */
data class PreparedOrangePackage(
    val archive: File,
    val pluginId: String,
    val toolCount: Int,
    val warnings: List<String>,
)

/**
 * 橘瓣包（`manifest.json` + `main.js`）→ 本仓插件包（`plugin.json` + `index.html`）的落盘器。
 *
 * 259 只做了翻译器，安装入口没接——界面上选到橘瓣包会被 `PluginPackageInstaller` 直接拒
 * （它只认 `plugin.json`）。本类把「识别 → 翻译 → 重新打包」这三步补齐，供设置页的
 * 「从文件安装」在安装前调用；输出仍然交给同一个安装器校验，安全边界不放松。
 *
 * 只用 `java.util.zip`，不依赖 Android，可单测。
 */
internal object OrangePluginPackager {
    private const val MANIFEST = "manifest.json"
    private const val MAIN_JS = "main.js"
    private const val PLUGIN_JSON = "plugin.json"

    /** 单个文本条目上限（安装器自己的上限是 4MB/条，这里先挡一道，避免读进 OOM）。 */
    private const val MAX_ENTRY_CHARS = 4 * 1024 * 1024

    /**
     * 是否橘瓣包：**没有** `plugin.json`，但 `manifest.json` 与 `main.js` 都在。
     * 有 `plugin.json` 的一律按本仓包处理，不抢安装器的活。
     */
    fun looksLikeOrangePlugin(archive: File): Boolean = runCatching {
        ZipFile(archive).use { zip ->
            zip.getEntry(PLUGIN_JSON) == null &&
                zip.getEntry(MANIFEST) != null &&
                zip.getEntry(MAIN_JS) != null
        }
    }.getOrDefault(false)

    /**
     * 读橘瓣包 → 翻译 → 写出可安装的 zip。
     *
     * @param outputDir 调用方的临时目录（一般是 `cacheDir/plugin-imports`）。
     * @throws IllegalArgumentException 翻译失败（例如清单里没有工具），消息即失败原因。
     */
    fun prepare(archive: File, outputDir: File): PreparedOrangePackage {
        val manifestJson: String
        val mainJs: String
        ZipFile(archive).use { zip ->
            manifestJson = readEntryText(zip, MANIFEST)
            mainJs = readEntryText(zip, MAIN_JS)
        }
        val translated = translateOrangePlugin(manifestJson, mainJs)
        outputDir.mkdirs()
        val output = File(outputDir, "orange-${translated.pluginId}.zip")
        ZipOutputStream(FileOutputStream(output)).use { zos ->
            zos.putNextEntry(ZipEntry(PLUGIN_JSON))
            zos.write(translated.pluginJson.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry(translated.entryHtmlName))
            zos.write(translated.entryHtml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            // 说明文件：安装后在插件目录里可见，便于用户知道这是翻译来的包、有哪些语义损失。
            if (translated.warnings.isNotEmpty()) {
                zos.putNextEntry(ZipEntry("ORANGE-COMPAT.txt"))
                zos.write(
                    buildString {
                        appendLine("Translated from an OrangeChat plugin package.")
                        appendLine("pluginId=${translated.pluginId}")
                        translated.warnings.forEach { appendLine("- $it") }
                    }.toByteArray(Charsets.UTF_8),
                )
                zos.closeEntry()
            }
        }
        return PreparedOrangePackage(
            archive = output,
            pluginId = translated.pluginId,
            toolCount = translated.toolCount,
            warnings = translated.warnings,
        )
    }

    private fun readEntryText(zip: ZipFile, name: String): String {
        val entry = zip.getEntry(name) ?: error("orange_entry_missing:$name")
        val bytes = zip.getInputStream(entry).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_ENTRY_CHARS) { "orange_entry_too_large:$name" }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
        return String(bytes, Charsets.UTF_8)
    }
}
