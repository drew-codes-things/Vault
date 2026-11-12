package vault.core

import java.io.File

private const val STAGED_PREFIX = "add-"
private const val STAGED_SUFFIX = ".bin"

class StagingArea(private val dir: File) {
    fun newFile(): File = File.createTempFile(STAGED_PREFIX, STAGED_SUFFIX, dir.apply { mkdirs() })

    fun clear(): Boolean = dir.listFiles().orEmpty().map { it.deleteRecursively() }.all { it }
}
