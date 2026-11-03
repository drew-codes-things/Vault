package vault.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.util.Base64

class ApiException(val status: Int, message: String, val retryAfterSeconds: Int = 0) : IOException(message)

class RemoteFile(val id: String, val sealedMeta: ByteArray, val sealedSize: Long, val hasThumb: Boolean, val createdAtIso: String)

class LoginResult(val token: String, val wrappedVaultKey: ByteArray)

private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 60_000

private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)

class VaultApi(baseUrl: String, var token: String? = null) {
    private val base = baseUrl.trimEnd('/')

    private fun open(method: String, path: String, authenticated: Boolean = true): HttpURLConnection {
        val connection = java.net.URI(base + path).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.useCaches = false
        if (authenticated) {
            val current = token ?: throw ApiException(401, "not signed in")
            connection.setRequestProperty("Authorization", "Bearer $current")
        }
        return connection
    }

    private fun readAll(stream: InputStream?): ByteArray {
        if (stream == null) return ByteArray(0)
        val output = ByteArrayOutputStream()
        stream.use { it.copyTo(output) }
        return output.toByteArray()
    }

    private fun failure(connection: HttpURLConnection): ApiException {
        val body = readAll(connection.errorStream).toString(Charsets.UTF_8)
        val message = runCatching { JSONObject(body).optString("error", body) }.getOrDefault(body)
        val retry = connection.getHeaderField("Retry-After")?.toIntOrNull() ?: 0
        return ApiException(connection.responseCode, message.ifEmpty { "HTTP ${connection.responseCode}" }, retry)
    }

    private fun sendJson(connection: HttpURLConnection, body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(bytes) }
    }

    private fun jsonResult(connection: HttpURLConnection, expected: Int): String {
        if (connection.responseCode != expected) throw failure(connection)
        return readAll(connection.inputStream).toString(Charsets.UTF_8)
    }

    fun getKdf(): KdfParams {
        val connection = open("GET", "/v1/kdf", authenticated = false)
        val json = JSONObject(jsonResult(connection, 200))
        return KdfParams(decode(json.getString("salt")), json.getInt("iterations"), json.getInt("memoryKiB"), json.getInt("parallelism"))
    }

    fun createAccount(setupToken: String, params: KdfParams, authKey: ByteArray, wrappedVaultKey: ByteArray) {
        val connection = open("POST", "/v1/account", authenticated = false)
        connection.setRequestProperty("X-Setup-Token", setupToken)
        val kdf = JSONObject()
            .put("salt", encode(params.salt))
            .put("iterations", params.iterations)
            .put("memoryKiB", params.memoryKiB)
            .put("parallelism", params.parallelism)
        sendJson(connection, JSONObject().put("kdf", kdf).put("authKey", encode(authKey)).put("wrappedVaultKey", encode(wrappedVaultKey)))
        jsonResult(connection, 201)
    }

    fun login(authKey: ByteArray): LoginResult {
        val connection = open("POST", "/v1/login", authenticated = false)
        sendJson(connection, JSONObject().put("authKey", encode(authKey)))
        val json = JSONObject(jsonResult(connection, 200))
        val result = LoginResult(json.getString("token"), decode(json.getString("wrappedVaultKey")))
        token = result.token
        return result
    }

    fun logout() {
        val connection = open("POST", "/v1/logout")
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(0)
        connection.outputStream.close()
        if (connection.responseCode != 204) throw failure(connection)
        token = null
    }

    fun listFiles(): List<RemoteFile> {
        val array = JSONArray(jsonResult(open("GET", "/v1/files"), 200))
        return (0 until array.length()).map { position ->
            val item = array.getJSONObject(position)
            RemoteFile(item.getString("id"), decode(item.getString("meta")), item.getLong("size"), item.getBoolean("hasThumb"), item.getString("createdAt"))
        }
    }

    fun createFile(sealedMeta: ByteArray): String {
        val connection = open("POST", "/v1/files")
        sendJson(connection, JSONObject().put("meta", encode(sealedMeta)))
        return JSONObject(jsonResult(connection, 201)).getString("id")
    }

    fun storedSize(id: String): Long {
        val connection = open("HEAD", "/v1/files/$id/content")
        if (connection.responseCode != 200) throw failure(connection)
        return connection.getHeaderFieldLong("Content-Length", 0)
    }

    fun updateMeta(id: String, sealedMeta: ByteArray) {
        val connection = open("PUT", "/v1/files/$id/meta")
        sendJson(connection, JSONObject().put("meta", encode(sealedMeta)))
        if (connection.responseCode != 204) throw failure(connection)
    }

    fun appendContent(id: String, offset: Long, bytes: ByteArray) {
        val connection = open("PUT", "/v1/files/$id/content?offset=$offset")
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        connection.outputStream.use { it.write(bytes) }
        if (connection.responseCode != 200) throw failure(connection)
        readAll(connection.inputStream)
    }

    fun putThumb(id: String, sealedThumb: ByteArray) {
        val connection = open("PUT", "/v1/files/$id/thumb")
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(sealedThumb.size)
        connection.outputStream.use { it.write(sealedThumb) }
        if (connection.responseCode != 204) throw failure(connection)
    }

    fun finalize(id: String, sealedSize: Long) {
        val connection = open("POST", "/v1/files/$id/finalize")
        sendJson(connection, JSONObject().put("size", sealedSize))
        if (connection.responseCode != 204) throw failure(connection)
    }

    fun getThumb(id: String): ByteArray {
        val connection = open("GET", "/v1/files/$id/thumb")
        if (connection.responseCode != 200) throw failure(connection)
        return readAll(connection.inputStream)
    }

    fun getRange(id: String, startInclusive: Long, endInclusive: Long): ByteArray {
        val connection = open("GET", "/v1/files/$id/content")
        connection.setRequestProperty("Range", "bytes=$startInclusive-$endInclusive")
        if (connection.responseCode != 206 && connection.responseCode != 200) throw failure(connection)
        return readAll(connection.inputStream)
    }

    fun delete(id: String) {
        val connection = open("DELETE", "/v1/files/$id")
        if (connection.responseCode != 204) throw failure(connection)
    }
}
