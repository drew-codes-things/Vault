package vault.core

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.net.URLConnection
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RELEASES_API = "https://api.github.com/repos/drew-codes-things/Vault/releases/latest"
private const val DAY_MS = 24L * 60 * 60 * 1000

private fun releaseAnswer(tag: String) = """{"tag_name":"$tag","html_url":"https://example.test/elsewhere","body":"notes"}"""

private class FakeHttpsConnection(url: URL, private val status: Int, private val body: ByteArray) : HttpsURLConnection(url) {
    var disconnected = false

    override fun getResponseCode(): Int = status
    override fun getInputStream(): InputStream = ByteArrayInputStream(body)
    override fun connect() = Unit
    override fun disconnect() {
        disconnected = true
    }
    override fun usingProxy() = false
    override fun getCipherSuite() = ""
    override fun getLocalCertificates(): Array<Certificate>? = null
    override fun getServerCertificates(): Array<Certificate> = emptyArray()
}

private class MemoryStore(override var checksEnabled: Boolean = true, override var dismissedVersion: String = "") : UpdateNoticeStore

private class Clock(var now: Long = 1_000L)

class UpdateCheckTest {
    @Test
    fun versionsCompareByNumberAndIgnoreATagPrefix() {
        assertTrue(isNewerVersion("v1.4.0", "1.3.3"))
        assertTrue(isNewerVersion("desktop-v1.10.0", "1.9.9"))
        assertTrue(isNewerVersion("2", "1.9"))
        assertFalse(isNewerVersion("1.4", "1.4.0"))
        assertFalse(isNewerVersion("1.3.2", "1.3.3"))
        assertFalse(isNewerVersion("latest", "1.3.3"))
        assertFalse(isNewerVersion("99999999999.0", "1.0"))
    }

    @Test
    fun theReleasedVersionComesFromTheTag() {
        assertEquals("1.4.0", releasedVersion(releaseAnswer("v1.4.0")))
        assertEquals("2.0.1", releasedVersion(releaseAnswer("desktop-v2.0.1")))
    }

    @Test
    fun anAnswerWithoutAUsableTagIsRefused() {
        for (answer in listOf("not json", "[]", """{"tag_name":5}""", """{"name":"v1.0"}""", """{"tag_name":"latest"}""")) {
            assertFailsWith<UpdateCheckException>(answer) { releasedVersion(answer) }
        }
    }

    @Test
    fun anAnswerOver256KbIsRefused() {
        val small = ByteArray(MAX_RELEASE_ANSWER_BYTES) { 'x'.code.toByte() }
        assertEquals(MAX_RELEASE_ANSWER_BYTES, readCapped(ByteArrayInputStream(small)).size)
        assertFailsWith<UpdateCheckException> { readCapped(ByteArrayInputStream(small + 'x'.code.toByte())) }
    }

    @Test
    fun theSourceAsksOnceOverHttpsWithShortTimeoutsAndOnlyAUserAgent() {
        val opened = mutableListOf<FakeHttpsConnection>()
        val source = LatestReleaseSource(RELEASES_API, "Vault/1.4.0") { url ->
            FakeHttpsConnection(url, 200, releaseAnswer("v1.5.0").toByteArray()).also { opened += it }
        }
        assertEquals("1.5.0", source.latestVersion())
        val connection = opened.single()
        assertEquals(RELEASES_API, connection.url.toString())
        assertEquals(5_000, connection.connectTimeout)
        assertEquals(5_000, connection.readTimeout)
        assertFalse(connection.useCaches)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals(setOf("User-Agent", "Accept"), connection.requestProperties.keys)
        assertEquals("Vault/1.4.0", connection.getRequestProperty("User-Agent"))
        assertTrue(connection.disconnected)
    }

    @Test
    fun aRefusedOrRateLimitedAnswerIsAFailure() {
        val source = LatestReleaseSource(RELEASES_API, "Vault/1.4.0") { url -> FakeHttpsConnection(url, 403, ByteArray(0)) }
        assertFailsWith<UpdateCheckException> { source.latestVersion() }
    }

    @Test
    fun plainHttpIsNeverUsed() {
        assertFailsWith<IllegalArgumentException> { LatestReleaseSource("http://api.github.com/repos/x/y/releases/latest", "Vault/1.4.0") }
        val source = LatestReleaseSource(RELEASES_API, "Vault/1.4.0") { url: URL -> object : URLConnection(url) {
            override fun connect() = Unit
        } }
        assertFailsWith<UpdateCheckException> { source.latestVersion() }
    }

    @Test
    fun aNewerReleaseIsOfferedAndAnOlderOrEqualOneIsNot() {
        assertEquals("1.5.0", UpdateNotice("1.4.0", MemoryStore(), { "1.5.0" }, {}).checkIfDue())
        assertNull(UpdateNotice("1.4.0", MemoryStore(), { "1.4.0" }, {}).checkIfDue())
        assertNull(UpdateNotice("1.4.0", MemoryStore(), { "1.3.3" }, {}).checkIfDue())
    }

    @Test
    fun githubIsAskedOnceAtStartThenAtMostOnceADay() {
        val clock = Clock()
        var asked = 0
        val notice = UpdateNotice("1.4.0", MemoryStore(), { asked++; "1.5.0" }, {}, { clock.now })
        notice.checkIfDue()
        clock.now += DAY_MS - 1
        assertEquals("1.5.0", notice.checkIfDue())
        assertEquals(1, asked)
        clock.now += 1
        notice.checkIfDue()
        assertEquals(2, asked)
    }

    @Test
    fun dismissingHidesOnlyThatVersion() {
        val store = MemoryStore()
        val clock = Clock()
        var latest = "1.5.0"
        val notice = UpdateNotice("1.4.0", store, { latest }, {}, { clock.now })
        notice.checkIfDue()
        notice.dismiss()
        assertEquals("1.5.0", store.dismissedVersion)
        assertNull(notice.availableVersion())
        latest = "1.6.0"
        clock.now += DAY_MS
        assertEquals("1.6.0", notice.checkIfDue())
    }

    @Test
    fun checksTurnedOffNeverAskAndShowNothing() {
        val store = MemoryStore(checksEnabled = false)
        var asked = 0
        val notice = UpdateNotice("1.4.0", store, { asked++; "1.5.0" }, {})
        assertNull(notice.checkIfDue())
        assertEquals(0, asked)
        notice.turnChecks(on = true)
        assertTrue(store.checksEnabled)
        assertEquals("1.5.0", notice.checkIfDue())
        notice.turnChecks(on = false)
        assertNull(notice.availableVersion())
    }

    @Test
    fun aFailedCheckShowsNothingAndLogsOneLine() {
        val logged = mutableListOf<String>()
        val notice = UpdateNotice("1.4.0", MemoryStore(), { throw IOException("offline") }, { logged += it })
        assertNull(notice.checkIfDue())
        assertEquals(listOf("Update check failed: offline"), logged)
    }
}
