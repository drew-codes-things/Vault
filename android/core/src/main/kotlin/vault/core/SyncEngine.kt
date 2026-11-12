package vault.core

import java.io.IOException

private const val TRANSFER_BATCH_BYTES = 8L * SEALED_CHUNK_BYTES
private const val NOT_FOUND = 404

class SyncReport(val uploaded: Int, val deletedRemotely: Int, val added: Int, val removedLocally: Int, val downloaded: Int)

class SyncEngine(private val api: VaultApi, private val store: LocalStore, private val mirrorContent: Boolean = true) {

    fun sync(): SyncReport = synchronized(store.syncLock) { syncLocked() }

    private fun syncLocked(): SyncReport {
        removeDuplicates()
        val deleted = pushDeletes()
        val uploaded = pushUploads()
        pushMetadata()
        val (added, removed) = pull()
        val downloaded = if (mirrorContent) downloadContent() else 0
        return SyncReport(uploaded, deleted, added, removed, downloaded)
    }

    private fun removeDuplicates() {
        store.all().filter { it.remoteId != null }.groupBy { it.remoteId }.values.filter { it.size > 1 }.forEach { copies ->
            val keep = copies.maxWith(compareBy<LocalRecord>({ it.state != SyncState.SYNCED }, { store.contentLength(it.localId) }))
            copies.filter { it.localId != keep.localId }.forEach { store.remove(it.localId) }
        }
    }

    private fun pushDeletes(): Int {
        val pending = store.all().filter { it.state == SyncState.PENDING_DELETE }
        for (record in pending) {
            record.remoteId?.let { deleteRemote(it) }
            store.remove(record.localId)
        }
        return pending.size
    }

    private fun deleteRemote(remoteId: String) {
        try {
            api.delete(remoteId)
        } catch (error: ApiException) {
            if (error.status != NOT_FOUND) throw error
        }
    }

    private fun pushMetadata() {
        store.all().filter { it.metaDirty && it.state == SyncState.SYNCED }.forEach { record ->
            val remoteId = record.remoteId ?: return@forEach
            try {
                api.updateMeta(remoteId, record.sealedMeta)
            } catch (error: ApiException) {
                if (error.status != NOT_FOUND) throw error
            }
            store.get(record.localId)?.takeIf { it.sealedMeta.contentEquals(record.sealedMeta) }?.let { store.save(it.copy(metaDirty = false)) }
        }
    }

    private fun pushUploads(): Int {
        val pending = store.all().filter { it.state == SyncState.PENDING_UPLOAD }.sortedBy { it.addedAtMillis }
        pending.forEach { upload(it) }
        return pending.size
    }

    private fun upload(record: LocalRecord) {
        val localId = record.localId
        for (attempt in 0..1) {
            val current = store.get(localId)?.takeIf { it.state == SyncState.PENDING_UPLOAD } ?: return
            val remoteId = current.remoteId ?: createRemote(current) ?: return
            try {
                sendContent(remoteId, current)
                if (current.hasThumb) api.putThumb(remoteId, store.thumb(localId) ?: throw IOException("thumbnail missing"))
                api.finalize(remoteId, current.sealedSize)
                if (!store.markUploaded(localId, remoteId, current.sealedMeta)) discardUpload(localId, remoteId)
                return
            } catch (error: ApiException) {
                if (error.status != NOT_FOUND || attempt == 1) throw error
                store.forgetRemote(localId)
            }
        }
    }

    private fun createRemote(record: LocalRecord): String? {
        val remoteId = api.createFile(record.sealedMeta)
        if (store.attachRemote(record.localId, remoteId)) return remoteId
        deleteRemote(remoteId)
        return null
    }

    private fun discardUpload(localId: String, remoteId: String) {
        deleteRemote(remoteId)
        store.remove(localId)
    }

    private fun sendContent(remoteId: String, record: LocalRecord) {
        var offset = api.storedSize(remoteId)
        while (offset < record.sealedSize) {
            val length = minOf(TRANSFER_BATCH_BYTES, record.sealedSize - offset).toInt()
            api.appendContent(remoteId, offset, store.readContent(record.localId, offset, length))
            offset += length
        }
    }

    private fun pull(): Pair<Int, Int> {
        val remote = api.listFiles()
        val remoteIds = remote.map { it.id }.toSet()
        val known = store.all().mapNotNull { record -> record.remoteId?.let { it to record } }.toMap()
        var added = 0
        for (file in remote) {
            val local = known[file.id]
            if (local != null) {
                if (local.state == SyncState.SYNCED && !local.metaDirty && !local.sealedMeta.contentEquals(file.sealedMeta)) {
                    store.save(local.copy(sealedMeta = file.sealedMeta))
                }
                continue
            }
            val thumb = if (file.hasThumb) api.getThumb(file.id) else null
            store.createSynced(file, thumb)
            added++
        }
        val gone = known.values.filter { it.state == SyncState.SYNCED && it.remoteId !in remoteIds }
        gone.forEach { store.remove(it.localId) }
        return added to gone.size
    }

    private fun downloadContent(): Int {
        val incomplete = store.all().filter { it.state == SyncState.SYNCED && !store.isComplete(it) }
        for (record in incomplete) {
            var have = store.contentLength(record.localId)
            if (have > record.sealedSize) {
                store.truncateContent(record.localId)
                have = 0
            }
            val remoteId = record.remoteId ?: continue
            while (have < record.sealedSize) {
                val end = minOf(have + TRANSFER_BATCH_BYTES, record.sealedSize) - 1
                val bytes = retryIo(3, 1_000) { api.getRange(remoteId, have, end) }
                if (bytes.size.toLong() != end - have + 1) throw IOException("server returned a truncated range")
                store.appendContent(record.localId, bytes)
                have += bytes.size
            }
        }
        return incomplete.size
    }
}
