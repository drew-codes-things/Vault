package vault.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

private const val RETRY_BASE_DELAY_MS = 1_000L
private const val READ_ATTEMPTS = 3
private const val CREDENTIALS_VERSION = 1

const val FOLDER_MIME = "application/vnd.vault.folder"
const val MAX_FOLDER_NAME_LENGTH = 40

class VaultItem(val id: String, val meta: FileMeta, val record: LocalRecord) {
    val hasThumb: Boolean get() = record.hasThumb
    val pendingUpload: Boolean get() = record.state == SyncState.PENDING_UPLOAD
    val isFolder: Boolean get() = meta.mimeType == FOLDER_MIME
}

class OfflineCredentials(val kdf: KdfParams, val wrappedVaultKey: ByteArray) {
    fun encode(): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeByte(CREDENTIALS_VERSION)
            out.writeInt(kdf.salt.size)
            out.write(kdf.salt)
            out.writeInt(kdf.iterations)
            out.writeInt(kdf.memoryKiB)
            out.writeInt(kdf.parallelism)
            out.writeInt(wrappedVaultKey.size)
            out.write(wrappedVaultKey)
        }
        return buffer.toByteArray()
    }

    companion object {
        fun decode(bytes: ByteArray): OfflineCredentials {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readUnsignedByte() != CREDENTIALS_VERSION) throw IOException("unsupported credentials version")
                val salt = ByteArray(input.readInt()).also { input.readFully(it) }
                val kdf = KdfParams(salt, input.readInt(), input.readInt(), input.readInt())
                val wrapped = ByteArray(input.readInt()).also { input.readFully(it) }
                return OfflineCredentials(kdf, wrapped)
            }
        }
    }
}

class UnlockResult(val session: VaultSession, val authKey: ByteArray, val credentials: OfflineCredentials)

class VaultSession private constructor(val api: VaultApi, private val store: LocalStore, vaultKey: ByteArray) {
    private val vaultKey: ByteArray = vaultKey.copyOf()

    @Volatile
    var isLocked: Boolean = false
        private set

    private fun key(): ByteArray {
        check(!isLocked) { "the vault is locked" }
        return vaultKey
    }

    fun items(): List<VaultItem> = allEntries().filter { !it.isFolder }

    fun folders(): List<String> = allEntries().filter { it.isFolder }.sortedBy { it.meta.createdAtMillis }.map { it.meta.name }.distinct()

    fun createFolder(name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= MAX_FOLDER_NAME_LENGTH) { "Folder names need 1 to $MAX_FOLDER_NAME_LENGTH characters" }
        require(folders().none { it.equals(trimmed, ignoreCase = true) }) { "A folder called $trimmed already exists" }
        addFile(trimmed, FOLDER_MIME, 0, System.currentTimeMillis(), PlainSource { java.io.ByteArrayInputStream(ByteArray(0)) }, null)
    }

    fun deleteFolder(name: String) {
        allEntries().filter { (it.isFolder && it.meta.name == name) || (!it.isFolder && it.meta.folder == name) }.forEach { store.markDeleted(it.id) }
    }

    private fun allEntries(): List<VaultItem> {
        val key = key()
        return store.all()
            .filter { it.state != SyncState.PENDING_DELETE }
            .mapNotNull { record -> runCatching { VaultItem(record.localId, openMeta(key, record.sealedMeta), record) }.getOrNull() }
            .sortedByDescending { it.meta.createdAtMillis }
    }

    fun addFile(
        name: String,
        mimeType: String,
        plainSize: Long,
        createdAtMillis: Long,
        source: PlainSource,
        thumbnail: ByteArray?,
        folder: String = "",
        onProgress: (Long) -> Unit = {},
    ): LocalRecord {
        val meta = FileMeta(name, mimeType, plainSize, createdAtMillis, randomBytes(KEY_BYTES), folder)
        val sealedThumb = thumbnail?.let { sealThumb(meta.fileKey, it) }
        return store.createPending(sealMeta(key(), meta), sealedSize(plainSize), sealedThumb) { out ->
            var written = 0L
            sealStream(meta.fileKey, plainSize, source, 0) { sealed ->
                out.write(sealed)
                written += sealed.size
                onProgress(written)
            }
        }
    }

    fun thumbnail(item: VaultItem): ByteArray? = store.thumb(item.id)?.let { openThumb(item.meta.fileKey, it) }

    fun isAvailableOffline(item: VaultItem): Boolean = store.isComplete(item.record)

    fun reader(item: VaultItem): ChunkReader =
        ChunkReader(item.meta.fileKey, item.meta.plainSize) { start, end ->
            val length = (end - start + 1).toInt()
            if (store.contentLength(item.id) > end) {
                store.readContent(item.id, start, length)
            } else {
                val remoteId = item.record.remoteId ?: throw IOException("file has not been uploaded yet")
                if (api.token == null) throw IOException("not downloaded yet and the server is unreachable")
                retryIo(READ_ATTEMPTS, RETRY_BASE_DELAY_MS) { api.getRange(remoteId, start, end) }
            }
        }

    fun delete(item: VaultItem) = store.markDeleted(item.id)

    fun moveToFolder(item: VaultItem, folder: String) {
        val record = store.get(item.id) ?: return
        val meta = openMeta(key(), record.sealedMeta)
        if (meta.folder == folder) return
        store.save(record.copy(sealedMeta = sealMeta(key(), meta.inFolder(folder)), metaDirty = record.remoteId != null))
    }

    fun exportVaultKey(): ByteArray = key().copyOf()

    fun lock() {
        isLocked = true
        vaultKey.fill(0)
    }

    fun logout() {
        try {
            if (api.token != null) api.logout()
        } finally {
            lock()
        }
    }

    companion object {
        fun createAccount(api: VaultApi, store: LocalStore, setupToken: String, passphrase: CharArray, params: KdfParams = newKdfParams()): UnlockResult {
            val keys = deriveKeys(passphrase, params)
            api.createAccount(setupToken, params, keys.authKey, wrapVaultKey(keys.masterKey, randomBytes(KEY_BYTES)))
            return unlockOnline(api, store, passphrase)
        }

        fun unlockOnline(api: VaultApi, store: LocalStore, passphrase: CharArray): UnlockResult {
            val kdf = api.getKdf()
            val keys = deriveKeys(passphrase, kdf)
            val login = api.login(keys.authKey)
            val vaultKey = try {
                unwrapVaultKey(keys.masterKey, login.wrappedVaultKey)
            } catch (error: DecryptionException) {
                throw IOException("server returned a vault key that does not match this passphrase", error)
            }
            return UnlockResult(VaultSession(api, store, vaultKey), keys.authKey, OfflineCredentials(kdf, login.wrappedVaultKey))
        }

        fun withVaultKey(api: VaultApi, store: LocalStore, vaultKey: ByteArray): VaultSession {
            require(vaultKey.size == KEY_BYTES) { "invalid vault key" }
            return VaultSession(api, store, vaultKey)
        }

        fun unlockOffline(api: VaultApi, store: LocalStore, credentials: OfflineCredentials, passphrase: CharArray): VaultSession {
            val keys = deriveKeys(passphrase, credentials.kdf)
            val vaultKey = unwrapVaultKey(keys.masterKey, credentials.wrappedVaultKey)
            return VaultSession(api, store, vaultKey)
        }
    }
}
