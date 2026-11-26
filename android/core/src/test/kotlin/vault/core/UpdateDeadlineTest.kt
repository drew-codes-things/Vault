package vault.core

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val DEADLINE_TEST_API = "https://api.example.test/releases/latest"
private const val DRIP_BODY_BYTES = 12
private const val DRIP_STEP_MS = 1_000L
private const val DEADLINE_WITH_SLACK_MS = 7_500L

private class LocalConnection(url: URL, private val real: HttpURLConnection) : HttpsURLConnection(url) {
    override fun setConnectTimeout(timeout: Int) = real.setConnectTimeout(timeout)
    override fun setReadTimeout(timeout: Int) = real.setReadTimeout(timeout)
    override fun getResponseCode(): Int = real.responseCode
    override fun getInputStream(): InputStream = real.inputStream
    override fun connect() = real.connect()
    override fun disconnect() = real.disconnect()
    override fun usingProxy() = false
    override fun getCipherSuite() = ""
    override fun getLocalCertificates(): Array<Certificate>? = null
    override fun getServerCertificates(): Array<Certificate> = emptyArray()
}

class UpdateDeadlineTest {
    @Test
    fun aSlowDripStopsAtTheTotalDeadline() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            try {
                exchange.responseBody.use { out ->
                    repeat(DRIP_BODY_BYTES) {
                        out.write('{'.code)
                        out.flush()
                        Thread.sleep(DRIP_STEP_MS)
                    }
                }
            } catch (ignored: IOException) {
                return@createContext
            }
        }
        server.start()
        try {
            val localUrl = URI("http://127.0.0.1:${server.address.port}/").toURL()
            val source = LatestReleaseSource(DEADLINE_TEST_API, "Test/1.0") { LocalConnection(it, localUrl.openConnection() as HttpURLConnection) }
            val tookMillis = measureTimeMillis { assertFailsWith<IOException> { source.latestVersion() } }
            assertTrue(tookMillis < DEADLINE_WITH_SLACK_MS, "the check ran $tookMillis ms")
        } finally {
            server.stop(0)
        }
    }
}
