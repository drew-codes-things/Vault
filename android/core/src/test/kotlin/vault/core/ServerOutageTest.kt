package vault.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val BAD_GATEWAY = 502
private val GATEWAY_ERROR_PAGE = FakeAnswer(BAD_GATEWAY, "<html><body><h1>502 Bad Gateway</h1></body></html>")

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
}
