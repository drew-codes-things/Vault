package vault.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

private const val META_VERSION = 2
private const val LEGACY_META_VERSION = 1
private const val META_LABEL = "meta-v1"
private const val THUMB_LABEL = "thumb-v1"
private const val VAULT_KEY_LABEL = "vault-key-v1"

enum class MediaKind { IMAGE, VIDEO, OTHER }

fun mediaKindOf(mimeType: String): MediaKind = when {
    mimeType.startsWith("image/") -> MediaKind.IMAGE
    mimeType.startsWith("video/") -> MediaKind.VIDEO
    else -> MediaKind.OTHER
}

fun FileMeta.inFolder(target: String): FileMeta = FileMeta(name, mimeType, plainSize, createdAtMillis, fileKey, target)

class FileMeta(
    val name: String,
    val mimeType: String,
    val plainSize: Long,
    val createdAtMillis: Long,
    val fileKey: ByteArray,
    val folder: String = "",
) {
    val kind: MediaKind get() = mediaKindOf(mimeType)
}

fun sealMeta(vaultKey: ByteArray, meta: FileMeta): ByteArray {
    val buffer = ByteArrayOutputStream()
    DataOutputStream(buffer).use { out ->
        out.writeByte(META_VERSION)
        out.writeUTF(meta.name)
        out.writeUTF(meta.mimeType)
        out.writeLong(meta.plainSize)
        out.writeLong(meta.createdAtMillis)
        out.writeInt(meta.fileKey.size)
        out.write(meta.fileKey)
        out.writeUTF(meta.folder)
    }
    return sealBlob(vaultKey, META_LABEL, buffer.toByteArray())
}

fun openMeta(vaultKey: ByteArray, sealed: ByteArray): FileMeta {
    val plain = openBlob(vaultKey, META_LABEL, sealed)
    DataInputStream(ByteArrayInputStream(plain)).use { input ->
        val version = input.readUnsignedByte()
        if (version != META_VERSION && version != LEGACY_META_VERSION) throw DecryptionException("unsupported metadata version $version")
        val name = input.readUTF()
        val mimeType = input.readUTF()
        val plainSize = input.readLong()
        val createdAt = input.readLong()
        val keySize = input.readInt()
        if (keySize != KEY_BYTES) throw DecryptionException("bad file key size")
        val fileKey = ByteArray(keySize)
        input.readFully(fileKey)
        val folder = if (version >= META_VERSION) input.readUTF() else ""
        return FileMeta(name, mimeType, plainSize, createdAt, fileKey, folder)
    }
}

fun sealThumb(fileKey: ByteArray, thumbnail: ByteArray): ByteArray = sealBlob(fileKey, THUMB_LABEL, thumbnail)

fun openThumb(fileKey: ByteArray, sealed: ByteArray): ByteArray = openBlob(fileKey, THUMB_LABEL, sealed)

fun wrapVaultKey(masterKey: ByteArray, vaultKey: ByteArray): ByteArray = sealBlob(masterKey, VAULT_KEY_LABEL, vaultKey)

fun unwrapVaultKey(masterKey: ByteArray, wrapped: ByteArray): ByteArray = openBlob(masterKey, VAULT_KEY_LABEL, wrapped)
