package me.rerere.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class WorkspaceFileSystemImportBytesTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `importBytes rejects a stream larger than the cap and deletes the partial file`() {
        val root = temp.newFolder("workspace")
        val fileSystem = WorkspaceFileSystem(WorkspaceConfig(maxImportBytes = 10))
        val oversized = ByteArrayInputStream(ByteArray(11) { 'a'.code.toByte() })

        assertThrows(IllegalArgumentException::class.java) {
            fileSystem.importBytes(root, "upload.bin", oversized)
        }
        assertFalse(File(root, "upload.bin").exists())
    }
}
