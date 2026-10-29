package me.rerere.rikkahub.data.sync.backup

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * 备份/恢复链路的「自有路径」判定（工单 CB 2026-10-29）。
 *
 * Android 上 `Context.filesDir` / `cacheDir` / `noBackupFilesDir` 位于
 * `/data/user/<user>/<pkg>/…`，而 `/data/user/0` 是指向 `/data/data` 的符号链接。
 * 因此 `path.toFile().canonicalFile.toPath() == path` **永远不成立**——旧判据让本地、
 * WebDAV、S3 导出在第一步就报 `FILE_TARGET_UNSAFE`，整条恢复链队同理（备份从未成功过一次）。
 *
 * 正确语义：path 本身不得是符号链接（不许把目录入口换成链接逃逸），且能解析出物理路径；
 * 系统挂载链（`/data/user/0` → `/data/data`）由 canonical 解析消化，不属于逃逸。
 * 需要真实路径参与比较或遍历时，使用返回的 canonical 路径，不要再用调用方传入的原文。
 */
internal fun resolveSafeOwnedPath(path: Path): Path? {
    if (Files.isSymbolicLink(path)) return null
    return runCatching { path.toFile().canonicalFile.toPath().normalize() }.getOrNull()
}

internal fun isSafeOwnedDirectory(path: Path): Boolean =
    resolveSafeOwnedPath(path)?.let { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) } == true
