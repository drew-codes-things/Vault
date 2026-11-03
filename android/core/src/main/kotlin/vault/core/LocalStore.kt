package vault.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile

private const val RECORD_VERSION = 2
private const val LEGACY_RECORD_VERSION = 1
private const val RECORD_FILE = "record.bin"
private const val CONTENT_FILE = "content.bin"
private const val THUMB_FILE = "thumb.bin"
private val localIdPattern = Regex("^[0-9a-f]{32}$")

enum class SyncState { SYNCED, PENDING_UPLOAD, PENDING_DELETE }

data class LocalRecord(
    val localId: String,
    val remoteId: String?,
    val state: SyncState,
    val sealedMeta: ByteArray,
    val sealedSize: Long,
    val hasThumb: Boolean,
    val addedAtMillis: Long,
    val metaDirty: Boolean = false,
)

private fun newLocalId(): String = randomBytes(16).joinToString("") { "%02x".format(it) }

private fun writeAtomically(target: File, bytes: ByteArray) {
    val temp = File(target.parentFile, target.name + ".tmp")
    FileOutputStream(temp).use { stream ->
        stream.write(bytes)
        stream.fd.sync()
    }
    if (!temp.renameTo(target)) throw IOException("could not replace ${target.name}")
}

class LocalStore(root: File) {
    private val recordsDir = File(root, "records")
    internal val syncLock = Any()

    init {
        if (!recordsDir.isDirectory && !recordsDir.mkdirs()) throw IOException("cannot create $recordsDir")
        recordsDir.listFiles()?.filter { !File(it, RECORD_FILE).exists() }?.forEach { it.deleteRecursively() }
    }

    private fun dirOf(localId: String): File {
        require(localIdPattern.matches(localId)) { "invalid local id" }
        return File(recordsDir, localId)
    }

    @Synchronized
    fun all(): List<LocalRecord> = recordsDir.listFiles()?.mapNotNull { dir ->
        if (localIdPattern.matches(dir.name)) read(dir) else null
    }.orEmpty()

    @Synchronized
    fun get(localId: String): LocalRecord? = read(dirOf(localId))

    private fun read(dir: File): LocalRecord? {
        val file = File(dir, RECORD_FILE)
        if (!file.exists()) return null
        DataInputStream(ByteArrayInputStream(file.readBytes())).use { input ->
            val version = input.readUnsignedByte()
            if (version != RECORD_VERSION && version != LEGACY_RECORD_VERSION) throw IOException("unsupported record version in ${dir.name}")
            val remoteId = input.readUTF().ifEmpty { null }
            val state = SyncState.entries[input.readUnsignedByte()]
            val sealedSize = input.readLong()
            val hasThumb = input.readBoolean()
            val addedAt = input.readLong()
            val meta = ByteArray(input.readInt())
            input.readFully(meta)
            val metaDirty = version >= RECORD_VERSION && input.readBoolean()
            return LocalRecord(dir.name, remoteId, state, meta, sealedSize, hasThumb, addedAt, metaDirty)
        }
    }

    @Synchronized
    fun save(record: LocalRecord) {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeByte(RECORD_VERSION)
            out.writeUTF(record.remoteId.orEmpty())
            out.writeByte(record.state.ordinal)
            out.writeLong(record.sealedSize)
            out.writeBoolean(record.hasThumb)
            out.writeLong(record.addedAtMillis)
            out.writeInt(record.sealedMeta.size)
            out.write(record.sealedMeta)
            out.writeBoolean(record.metaDirty)
        }
        writeAtomically(File(dirOf(record.localId), RECORD_FILE), buffer.toByteArray())
    }

    fun createPending(sealedMeta: ByteArray, sealedSize: Long, sealedThumb: ByteArray?, writeContent: (OutputStream) -> Unit): LocalRecord {
        val localId = newLocalId()
        val dir = dirOf(localId)
        if (!dir.mkdirs()) throw IOException("cannot create $dir")
        try {
            sealedThumb?.let { writeAtomically(File(dir, THUMB_FILE), it) }
            val temp = File(dir, "$CONTENT_FILE.tmp")
            FileOutputStream(temp).use { stream ->
                writeContent(stream)
                stream.fd.sync()
            }
            if (temp.length() != sealedSize) throw IOException("encrypted size ${temp.length()} does not match expected $sealedSize")
            if (!temp.renameTo(File(dir, CONTENT_FILE))) throw IOException("could not store content")
            val record = LocalRecord(localId, null, SyncState.PENDING_UPLOAD, sealedMeta, sealedSize, sealedThumb != null, System.currentTimeMillis())
            save(record)
            return record
        } catch (error: Exception) {
            dir.deleteRecursively()
            throw error
        }
    }

    fun createSynced(remote: RemoteFile, sealedThumb: ByteArray?): LocalRecord {
        val localId = newLocalId()
        val dir = dirOf(localId)
        if (!dir.mkdirs()) throw IOException("cannot create $dir")
        sealedThumb?.let { writeAtomically(File(dir, THUMB_FILE), it) }
        val record = LocalRecord(localId, remote.id, SyncState.SYNCED, remote.sealedMeta, remote.sealedSize, sealedThumb != null, System.currentTimeMillis())
        save(record)
        return record
    }

    fun thumb(localId: String): ByteArray? = File(dirOf(localId), THUMB_FILE).takeIf { it.exists() }?.readBytes()

    fun contentLength(localId: String): Long = File(dirOf(localId), CONTENT_FILE).length()

    fun isComplete(record: LocalRecord): Boolean = contentLength(record.localId) == record.sealedSize

    fun appendContent(localId: String, bytes: ByteArray) {
        FileOutputStream(File(dirOf(localId), CONTENT_FILE), true).use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
    }

    fun truncateContent(localId: String) {
        File(dirOf(localId), CONTENT_FILE).delete()
    }

    fun readContent(localId: String, offset: Long, length: Int): ByteArray =
        RandomAccessFile(File(dirOf(localId), CONTENT_FILE), "r").use { file ->
            val bytes = ByteArray(length)
            file.seek(offset)
            file.readFully(bytes)
            bytes
        }

    @Synchronized
    fun markDeleted(localId: String) {
        val record = get(localId) ?: return
        if (record.remoteId == null) remove(localId) else save(record.copy(state = SyncState.PENDING_DELETE))
    }

    @Synchronized
    fun remove(localId: String) {
        dirOf(localId).deleteRecursively()
    }

    @Synchronized
    fun clear() {
        recordsDir.listFiles()?.forEach { it.deleteRecursively() }
    }
}
