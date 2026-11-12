package vault.core

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val UNUSED_SERVER = "http://127.0.0.1:1"
private const val FROZEN_CLOCK_MILLIS = 1_700_000_000_000L

class VaultSessionTest {
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun offlineSession(): VaultSession {
        val dir = Files.createTempDirectory("session").toFile().also { tempDirs += it }
        return VaultSession.withVaultKey(VaultApi(UNUSED_SERVER), LocalStore(dir), randomBytes(KEY_BYTES))
    }

    @Test
    fun foldersMadeInTheSameMillisecondKeepTheOrderTheyWereMadeIn() {
        val session = offlineSession()
        session.clock = { FROZEN_CLOCK_MILLIS }
        val names = listOf("Photos", "Videos", "Documents", "Family", "Work", "Holidays")
        names.forEach { session.createFolder(it) }
        assertEquals(names, session.folders())
    }
}
