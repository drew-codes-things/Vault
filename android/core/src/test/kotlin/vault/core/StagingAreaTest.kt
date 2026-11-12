package vault.core

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StagingAreaTest {
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun cacheDir(): File = Files.createTempDirectory("cache").toFile().also { tempDirs += it }

    @Test
    fun plainCopiesLeftByAnAppThatDiedAreRemovedOnTheNextStart() {
        val cache = cacheDir()
        val before = StagingArea(File(cache, "staging"))
        before.newFile().writeBytes("private photo".toByteArray())
        before.newFile().writeBytes("private video".toByteArray())

        val afterRestart = StagingArea(File(cache, "staging"))
        assertTrue(afterRestart.clear())

        assertEquals(emptyList(), File(cache, "staging").listFiles().orEmpty().toList())
    }

    @Test
    fun clearingWhenNothingWasEverStagedSucceeds() {
        assertTrue(StagingArea(File(cacheDir(), "staging")).clear())
    }

    @Test
    fun newFilesLandInTheStagingFolder() {
        val staging = File(cacheDir(), "staging")
        val file = StagingArea(staging).newFile()
        assertEquals(staging.canonicalFile, file.parentFile.canonicalFile)
    }
}
