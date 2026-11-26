package vault.core

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLConnection
import javax.net.ssl.HttpsURLConnection

const val UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
const val UPDATE_TIMEOUT_MS = 5_000
const val MAX_RELEASE_NESTING = 32
const val MAX_RELEASE_ANSWER_BYTES = 256 * 1024
private const val READ_CHUNK_BYTES = 8 * 1024
private const val SECURE_SCHEME = "https"
private const val LENIENT_ONLY_CHARACTERS = "'/#"
private const val JSON_WHITESPACE = " \t\n\r"
private const val STRING_STARTERS = "{[,:"
private val VERSION_NUMBER = Regex("""\d+(?:\.\d+)*""")

class UpdateCheckException(message: String) : IOException(message)

fun versionNumbers(version: String): List<Int>? {
    val found = VERSION_NUMBER.find(version) ?: return null
    return found.value.split('.').map { it.toIntOrNull() ?: return null }
}

fun isNewerVersion(latest: String, current: String): Boolean {
    val latestParts = versionNumbers(latest) ?: return false
    val currentParts = versionNumbers(current) ?: return false
    for (index in 0 until maxOf(latestParts.size, currentParts.size)) {
        val difference = latestParts.getOrElse(index) { 0 } - currentParts.getOrElse(index) { 0 }
        if (difference != 0) return difference > 0
    }
    return false
}

fun isPlainJsonWithinNesting(text: String, limit: Int = MAX_RELEASE_NESTING): Boolean {
    var depth = 0
    var inString = false
    var escaped = false
    var stringMayStart = true
    for (character in text) {
        if (escaped) {
            escaped = false
        } else if (inString) {
            when (character) {
                '\\' -> escaped = true
                '"' -> {
                    inString = false
                    stringMayStart = false
                }
            }
        } else if (character == '"') {
            if (!stringMayStart) return false
            inString = true
        } else if (character in LENIENT_ONLY_CHARACTERS) {
            return false
        } else if (character == '[' || character == '{') {
            if (++depth > limit) return false
            stringMayStart = true
        } else if (character == ']' || character == '}') {
            if (--depth < 0) return false
            stringMayStart = false
        } else if (character in STRING_STARTERS) {
            stringMayStart = true
        } else if (character !in JSON_WHITESPACE) {
            stringMayStart = false
        }
    }
    return true
}

fun releasedVersion(answer: String): String {
    if (!isPlainJsonWithinNesting(answer)) throw UpdateCheckException("the release answer is not plain JSON nested at most $MAX_RELEASE_NESTING deep")
    val tag = try {
        JSONObject(answer).opt("tag_name") as? String
    } catch (error: JSONException) {
        throw UpdateCheckException("the release answer is not a JSON object")
    }
    return tag?.let { VERSION_NUMBER.find(it)?.value } ?: throw UpdateCheckException("the latest release has no version in its tag")
}

fun readCapped(stream: InputStream, maxBytes: Int = MAX_RELEASE_ANSWER_BYTES): ByteArray {
    val output = ByteArrayOutputStream()
    val chunk = ByteArray(READ_CHUNK_BYTES)
    while (true) {
        val count = stream.read(chunk)
        if (count < 0) return output.toByteArray()
        if (output.size() + count > maxBytes) throw UpdateCheckException("the release answer is larger than $maxBytes bytes")
        output.write(chunk, 0, count)
    }
}

class LatestReleaseSource(
    private val apiUrl: String,
    private val userAgent: String,
    private val open: (URL) -> URLConnection = { it.openConnection() },
) {
    init {
        require(URI(apiUrl).scheme == SECURE_SCHEME) { "The update check only uses https" }
    }

    fun latestVersion(): String {
        val connection = open(URI(apiUrl).toURL()) as? HttpsURLConnection ?: throw UpdateCheckException("the update check only uses https")
        connection.connectTimeout = UPDATE_TIMEOUT_MS
        connection.readTimeout = UPDATE_TIMEOUT_MS
        connection.useCaches = false
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw UpdateCheckException("GitHub answered $status")
            val answer = connection.inputStream.use { readCapped(it) }
            return releasedVersion(answer.toString(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }
}

interface UpdateNoticeStore {
    var checksEnabled: Boolean
    var dismissedVersion: String
}

class UpdateNotice(
    private val currentVersion: String,
    private val store: UpdateNoticeStore,
    private val fetchLatestVersion: () -> String,
    private val log: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var lastCheckAt: Long? = null
    private var latestVersion: String? = null

    val checksEnabled: Boolean get() = store.checksEnabled

    @Synchronized
    fun availableVersion(): String? = latestVersion?.takeIf {
        store.checksEnabled && it != store.dismissedVersion && isNewerVersion(it, currentVersion)
    }

    @Synchronized
    fun checkIfDue(): String? {
        if (isCheckDue()) {
            lastCheckAt = now()
            try {
                latestVersion = fetchLatestVersion()
            } catch (error: IOException) {
                log("Update check failed: ${error.message}")
            } catch (error: Exception) {
                log("Update check failed: ${error.javaClass.simpleName}")
            } catch (error: StackOverflowError) {
                log("Update check failed: ${error.javaClass.simpleName}")
            }
        }
        return availableVersion()
    }

    @Synchronized
    fun dismiss() {
        latestVersion?.let { store.dismissedVersion = it }
    }

    @Synchronized
    fun turnChecks(on: Boolean) {
        store.checksEnabled = on
    }

    private fun isCheckDue(): Boolean {
        if (!store.checksEnabled) return false
        val last = lastCheckAt ?: return true
        val elapsed = now() - last
        return elapsed < 0 || elapsed >= UPDATE_CHECK_INTERVAL_MS
    }
}
