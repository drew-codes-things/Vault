package vault.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServerAddressTest {
    @Test
    fun bareHostGetsHttps() = assertEquals("https://vault.example.com", normaliseServerUrl("  Vault.Example.com/ "))

    @Test
    fun keepsPortAndPath() = assertEquals("https://example.com:8443/vault", normaliseServerUrl("https://example.com:8443/vault/"))

    @Test
    fun allowsHttpOnlyOnThisDevice() {
        assertEquals("http://127.0.0.1:18080", normaliseServerUrl("http://127.0.0.1:18080"))
        assertEquals("http://localhost:8092", normaliseServerUrl("http://localhost:8092"))
        assertFailsWith<IllegalArgumentException> { normaliseServerUrl("http://vault.example.com") }
        assertFailsWith<IllegalArgumentException> { normaliseServerUrl("http://192.168.1.10:8092") }
    }

    @Test
    fun rejectsJunk() {
        listOf("", "   ", "ftp://example.com", "https://", "https://example.com/?x=1", "https://user:pw@example.com", "not a url").forEach { input ->
            assertFailsWith<IllegalArgumentException>(input) { normaliseServerUrl(input) }
        }
    }
}
