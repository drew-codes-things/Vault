package vault.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class FakeAnswer(val status: Int, val body: String)

class FakeVaultServer(private val answers: Map<String, FakeAnswer>, private val otherwise: FakeAnswer) : AutoCloseable {
    val requestedPaths = CopyOnWriteArrayList<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requestedPaths += path
            exchange.requestBody.readBytes()
            val answer = answers[path] ?: otherwise
            val bytes = answer.body.toByteArray()
            exchange.sendResponseHeaders(answer.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    override fun close() {
        server.stop(0)
    }
}
