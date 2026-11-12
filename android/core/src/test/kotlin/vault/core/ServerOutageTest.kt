package vault.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val BAD_GATEWAY = 502
private const val TOO_MANY_ATTEMPTS = 429
private val GATEWAY_ERROR_PAGE = FakeAnswer(BAD_GATEWAY, "<html><body><h1>502 Bad Gateway</h1></body></html>")
private val LOGIN_PAUSED = FakeAnswer(TOO_MANY_ATTEMPTS, """{"error":"too many failed attempts"}""")
private val REFUSALS_FROM_THE_VAULT = listOf(400, 401, 403, 404, 409)

private fun standardKdfAnswer(): FakeAnswer {
    val salt = Base64.getEncoder().encodeToString(ByteArray(16) { 5 })
    return FakeAnswer(200, """{"salt":"$salt","iterations":${STANDARD_KDF_FLOOR.iterations},"memoryKiB":${STANDARD_KDF_FLOOR.memoryKiB},"parallelism":1}""")
}

class ServerOutageTest {
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun store(): LocalStore = LocalStore(Files.createTempDirectory("device").toFile().also { tempDirs += it })

    @Test
    fun aGatewayErrorPageCountsAsTheServerBeingUnavailable() {
        FakeVaultServer(emptyMap(), GATEWAY_ERROR_PAGE).use { gateway ->
            val error = assertFailsWith<ApiException> { VaultSession.unlockOnline(VaultApi(gateway.url), store(), "correct horse battery".toCharArray()) }
            assertEquals(BAD_GATEWAY, error.status)
            assertEquals("HTTP $BAD_GATEWAY", error.message)
            assertTrue(serverIsUnavailable(error))
        }
    }

    @Test
    fun aBrokenConnectionCountsAsTheServerBeingUnavailable() {
        assertTrue(serverIsUnavailable(IOException("connection reset")))
    }

    @Test
    fun anAnswerFromTheVaultServerDoesNotCountAsItBeingUnavailable() {
        listOf(401, 403, 404, 409, 429).forEach { status ->
            assertFalse(serverIsUnavailable(ApiException(status, "refused")), "status $status")
        }
    }

    @Test
    fun aPausedLoginCountsAsATemporaryProblemSoThePasskeyCanOpenTheOfflineCopy() {
        FakeVaultServer(mapOf("/v1/kdf" to standardKdfAnswer(), "/v1/login" to LOGIN_PAUSED), GATEWAY_ERROR_PAGE).use { server ->
            val error = assertFailsWith<ApiException> { VaultSession.unlockOnline(VaultApi(server.url), store(), "correct horse battery".toCharArray()) }
            assertEquals(TOO_MANY_ATTEMPTS, error.status)
            assertTrue(isTemporaryServerProblem(error))
        }
    }

    @Test
    fun outagesAreTemporaryProblems() {
        assertTrue(isTemporaryServerProblem(IOException("connection reset")))
        assertTrue(isTemporaryServerProblem(ApiException(BAD_GATEWAY, "HTTP $BAD_GATEWAY")))
    }

    @Test
    fun aRefusalFromTheVaultIsNotATemporaryProblem() {
        REFUSALS_FROM_THE_VAULT.forEach { status ->
            assertFalse(isTemporaryServerProblem(ApiException(status, "refused")), "status $status")
        }
    }
}
