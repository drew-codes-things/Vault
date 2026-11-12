package vault.core

import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

private const val LOGIN_PATH = "/v1/login"
private const val HUGE_MEMORY_KIB = Int.MAX_VALUE
private val LOGIN_REFUSED = FakeAnswer(401, """{"error":"invalid credentials"}""")

class UntrustedServerTest {
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun store(): LocalStore = LocalStore(Files.createTempDirectory("device").toFile().also { tempDirs += it })

    private fun kdfAnswer(iterations: Int, memoryKiB: Int, saltBytes: Int = 16): FakeAnswer {
        val salt = Base64.getEncoder().encodeToString(ByteArray(saltBytes) { 5 })
        return FakeAnswer(200, """{"salt":"$salt","iterations":$iterations,"memoryKiB":$memoryKiB,"parallelism":1}""")
    }

    private fun assertLoginKeyIsNeverSent(kdf: FakeAnswer) {
        FakeVaultServer(mapOf("/v1/kdf" to kdf), LOGIN_REFUSED).use { server ->
            assertFailsWith<UntrustedServerException> { VaultSession.unlockOnline(VaultApi(server.url), store(), "correct horse battery".toCharArray()) }
            assertFalse(LOGIN_PATH in server.requestedPaths)
        }
    }

    @Test
    fun aServerAskingForAWeakKeyDerivationNeverReceivesTheLoginKey() {
        assertLoginKeyIsNeverSent(kdfAnswer(iterations = 1, memoryKiB = 8))
    }

    @Test
    fun aServerAskingForAShortSaltNeverReceivesTheLoginKey() {
        assertLoginKeyIsNeverSent(kdfAnswer(iterations = 3, memoryKiB = 65536, saltBytes = 4))
    }

    @Test
    fun aServerAskingForImpossibleMemoryIsRefusedBeforeDerivingAnything() {
        assertLoginKeyIsNeverSent(kdfAnswer(iterations = 3, memoryKiB = HUGE_MEMORY_KIB))
    }

    @Test
    fun theAppsOwnKeyDerivationSettingsAreAccepted() {
        requireTrustworthyKdf(newKdfParams())
    }
}
