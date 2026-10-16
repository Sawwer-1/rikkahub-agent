package me.rerere.workspace

import java.io.File

data class ManagedWorkspaceProcessContext(
    val root: String,
    val command: String,
    val cwd: String,
    val filesDir: File,
    val linuxDir: File,
    val tempDir: File,
    val allowSharedStorage: Boolean = false,
    // 兼容模式: 见 ProotShellRunner, enabled 时注入 PROOT_NO_SECCOMP=1
    val shellCompatibilityMode: Boolean = false,
)

interface ManagedWorkspaceProcessLauncher {
    fun startManagedProcess(context: ManagedWorkspaceProcessContext): Process
}
