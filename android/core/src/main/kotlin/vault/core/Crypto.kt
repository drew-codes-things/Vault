package vault.core

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

const val KEY_BYTES = 32
const val GCM_TAG_BYTES = 16
const val GCM_NONCE_BYTES = 12
const val PLAIN_CHUNK_BYTES = 1 shl 20
const val SEALED_CHUNK_BYTES = PLAIN_CHUNK_BYTES + GCM_TAG_BYTES

private const val GCM_TAG_BITS = GCM_TAG_BYTES * 8
private const val DEFAULT_KDF_ITERATIONS = 3
private const val DEFAULT_KDF_MEMORY_KIB = 65536
private const val MIN_KDF_SALT_BYTES = 16
private const val MAX_KDF_ITERATIONS = 64
private const val MAX_KDF_MEMORY_KIB = 1 shl 20
private const val MAX_KDF_PARALLELISM = 16
private val random = SecureRandom()

class DecryptionException(message: String, cause: Throwable? = null) : GeneralSecurityException(message, cause)

data class KdfParams(
    val salt: ByteArray,
    val iterations: Int,
    val memoryKiB: Int,
    val parallelism: Int,
)

class DerivedKeys(val masterKey: ByteArray, val authKey: ByteArray)

class UntrustedServerException(message: String) : GeneralSecurityException(message)

data class KdfFloor(val iterations: Int, val memoryKiB: Int)

val STANDARD_KDF_FLOOR = KdfFloor(DEFAULT_KDF_ITERATIONS, DEFAULT_KDF_MEMORY_KIB)

fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

fun newKdfParams(iterations: Int = DEFAULT_KDF_ITERATIONS, memoryKiB: Int = DEFAULT_KDF_MEMORY_KIB, parallelism: Int = 1): KdfParams =
    KdfParams(randomBytes(MIN_KDF_SALT_BYTES), iterations, memoryKiB, parallelism)

fun requireTrustworthyKdf(params: KdfParams, floor: KdfFloor = STANDARD_KDF_FLOOR) {
    val isTrustworthy = params.salt.size >= MIN_KDF_SALT_BYTES &&
        params.iterations in floor.iterations..MAX_KDF_ITERATIONS &&
        params.memoryKiB in floor.memoryKiB..MAX_KDF_MEMORY_KIB &&
        params.parallelism in 1..MAX_KDF_PARALLELISM
    if (!isTrustworthy) throw UntrustedServerException("The server asked for passkey protection this app does not accept")
}

fun deriveKeys(passphrase: CharArray, params: KdfParams): DerivedKeys {
    val normalized = Normalizer.normalize(String(passphrase), Normalizer.Form.NFKC)
    val argonParameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
        .withVersion(Argon2Parameters.ARGON2_VERSION_13)
        .withSalt(params.salt)
        .withIterations(params.iterations)
        .withMemoryAsKB(params.memoryKiB)
        .withParallelism(params.parallelism)
        .build()
    val output = ByteArray(KEY_BYTES * 2)
    val generator = Argon2BytesGenerator()
    generator.init(argonParameters)
    generator.generateBytes(normalized.toByteArray(Charsets.UTF_8), output)
    return DerivedKeys(output.copyOfRange(0, KEY_BYTES), output.copyOfRange(KEY_BYTES, KEY_BYTES * 2))
}

private fun gcmCipher(mode: Int, key: ByteArray, nonce: ByteArray, associatedData: ByteArray): Cipher {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
    cipher.updateAAD(associatedData)
    return cipher
}

fun sealBlob(key: ByteArray, label: String, plaintext: ByteArray): ByteArray {
    val nonce = randomBytes(GCM_NONCE_BYTES)
    val cipher = gcmCipher(Cipher.ENCRYPT_MODE, key, nonce, label.toByteArray(Charsets.UTF_8))
    return nonce + cipher.doFinal(plaintext)
}

fun openBlob(key: ByteArray, label: String, sealed: ByteArray): ByteArray {
    if (sealed.size < GCM_NONCE_BYTES + GCM_TAG_BYTES) throw DecryptionException("blob too short")
    val nonce = sealed.copyOfRange(0, GCM_NONCE_BYTES)
    return try {
        gcmCipher(Cipher.DECRYPT_MODE, key, nonce, label.toByteArray(Charsets.UTF_8))
            .doFinal(sealed, GCM_NONCE_BYTES, sealed.size - GCM_NONCE_BYTES)
    } catch (error: GeneralSecurityException) {
        throw DecryptionException("blob failed authentication", error)
    }
}

fun chunkCount(plainSize: Long): Long = if (plainSize == 0L) 1 else (plainSize + PLAIN_CHUNK_BYTES - 1) / PLAIN_CHUNK_BYTES

fun sealedSize(plainSize: Long): Long = plainSize + chunkCount(plainSize) * GCM_TAG_BYTES

private fun chunkNonce(index: Long): ByteArray {
    val nonce = ByteArray(GCM_NONCE_BYTES)
    for (position in 0 until 8) {
        nonce[GCM_NONCE_BYTES - 1 - position] = (index ushr (8 * position)).toByte()
    }
    return nonce
}

private fun chunkAssociatedData(index: Long, isFinal: Boolean): ByteArray {
    val data = ByteArray(9)
    for (position in 0 until 8) {
        data[7 - position] = (index ushr (8 * position)).toByte()
    }
    data[8] = if (isFinal) 1 else 0
    return data
}

fun sealChunk(fileKey: ByteArray, index: Long, isFinal: Boolean, plaintext: ByteArray, length: Int = plaintext.size): ByteArray {
    val cipher = gcmCipher(Cipher.ENCRYPT_MODE, fileKey, chunkNonce(index), chunkAssociatedData(index, isFinal))
    return cipher.doFinal(plaintext, 0, length)
}

fun openChunk(fileKey: ByteArray, index: Long, isFinal: Boolean, sealed: ByteArray): ByteArray {
    return try {
        gcmCipher(Cipher.DECRYPT_MODE, fileKey, chunkNonce(index), chunkAssociatedData(index, isFinal)).doFinal(sealed)
    } catch (error: GeneralSecurityException) {
        throw DecryptionException("chunk $index failed authentication", error)
    }
}
