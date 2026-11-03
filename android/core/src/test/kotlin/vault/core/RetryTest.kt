package vault.core

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RetryTest {
    @Test
    fun transientFailuresAreRetriedWithGrowingDelay() {
        val delays = mutableListOf<Long>()
        var calls = 0
        val result = retryIo(3, 100, { delays += it }) {
            calls++
            if (calls < 3) throw IOException("unexpected end of stream")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(listOf(100L, 200L), delays)
    }

    @Test
    fun givesUpAfterTheLastAttempt() {
        var calls = 0
        assertFailsWith<IOException> { retryIo(3, 1, {}) { calls++; throw IOException("down") } }
        assertEquals(3, calls)
    }

    @Test
    fun clientErrorsAreNotRetried() {
        var calls = 0
        assertFailsWith<ApiException> { retryIo(3, 1, {}) { calls++; throw ApiException(401, "unauthorized") } }
        assertEquals(1, calls)
    }

    @Test
    fun decryptionFailuresAreNotRetried() {
        var calls = 0
        assertFailsWith<DecryptionException> { retryIo(3, 1, {}) { calls++; throw DecryptionException("tampered") } }
        assertEquals(1, calls)
    }
}
