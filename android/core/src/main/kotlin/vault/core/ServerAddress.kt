package vault.core

import java.net.URI
import java.net.URISyntaxException

private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]", "::1")

/**
 * Turns what someone typed as their server address into the base URL the API uses.
 * A bare host gets https://. Plain http is refused except on this device (local testing),
 * because the passkey-derived login key travels in the request.
 */
fun normaliseServerUrl(input: String): String {
    val trimmed = input.trim()
    require(trimmed.isNotEmpty()) { "Enter the server address" }
    val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
    val uri = try {
        URI(withScheme)
    } catch (error: URISyntaxException) {
        throw IllegalArgumentException("That doesn't look like a web address")
    }
    val host = uri.host
    require(!host.isNullOrEmpty()) { "That doesn't look like a web address" }
    require(uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null) { "Use just the address, like https://vault.example.com" }
    when (uri.scheme?.lowercase()) {
        "https" -> Unit
        "http" -> require(host.lowercase() in LOOPBACK_HOSTS) { "The server must use https://" }
        else -> throw IllegalArgumentException("The server must use https://")
    }
    return URI(uri.scheme.lowercase(), null, host.lowercase(), uri.port, uri.rawPath.trimEnd('/').ifEmpty { null }, null, null).toString()
}
