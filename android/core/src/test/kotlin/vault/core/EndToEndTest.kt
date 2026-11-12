package vault.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val SETUP_TOKEN = "e2e-setup-token"
private const val PASSPHRASE = "correct horse battery"
private val fastParams = KdfParams(ByteArray(16) { 3 }, 1, 64, 1)
private val fastFloor = KdfFloor(fastParams.iterations, fastParams.memoryKiB)

private fun bytesSource(bytes: ByteArray) = PlainSource { skip -> ByteArrayInputStream(bytes, skip.toInt(), bytes.size - skip.toInt()) }

class EndToEndTest {
    private var process: Process? = null
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        process?.destroy()
        process?.waitFor()
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile().also { tempDirs += it }

    private fun startServer(): String? {
        val binary = File(System.getenv("VAULT_SERVER_BIN") ?: return null)
        if (!binary.canExecute()) return null
        val port = ServerSocket(0).use { it.localPort }
        val data = tempDir("vault-e2e")
        val builder = ProcessBuilder(binary.path)
        builder.environment().putAll(mapOf("VAULT_ADDR" to "127.0.0.1:$port", "VAULT_DATA" to data.path, "VAULT_SETUP_TOKEN" to SETUP_TOKEN))
        builder.redirectErrorStream(true).redirectOutput(File(data, "server.log"))
        process = builder.start()
        repeat(100) {
            if (runCatching { Socket("127.0.0.1", port).close() }.isSuccess) return "http://127.0.0.1:$port"
            Thread.sleep(50)
        }
        error("server did not start")
    }

    private fun newDevice(url: String, create: Boolean = false): Triple<VaultApi, LocalStore, UnlockResult> {
        val api = VaultApi(url)
        val store = LocalStore(tempDir("device"))
        val result = if (create) {
            VaultSession.createAccount(api, store, SETUP_TOKEN, PASSPHRASE.toCharArray(), fastParams, fastFloor)
        } else {
            VaultSession.unlockOnline(api, store, PASSPHRASE.toCharArray(), fastFloor)
        }
        return Triple(api, store, result)
    }

    private fun readAll(session: VaultSession, item: VaultItem): ByteArray = session.reader(item).read(0, item.meta.plainSize.toInt())

    @Test
    fun filesAddedOfflineUploadOnSyncAndReachOtherDevices() {
        val url = startServer() ?: return
        val (apiA, storeA, a) = newDevice(url, create = true)
        val video = Random(1).nextBytes(2 * PLAIN_CHUNK_BYTES + 12345)
        val thumb = Random(2).nextBytes(4000)
        a.session.addFile("clip.mp4", "video/mp4", video.size.toLong(), 10L, bytesSource(video), thumb)

        val pending = a.session.items().single()
        assertTrue(pending.pendingUpload)
        assertContentEquals(video, readAll(a.session, pending))
        assertTrue(apiA.listFiles().isEmpty())

        assertEquals(1, SyncEngine(apiA, storeA).sync().uploaded)
        assertFalse(a.session.items().single().pendingUpload)
        assertEquals(1, apiA.listFiles().size)

        val (apiB, storeB, b) = newDevice(url)
        val report = SyncEngine(apiB, storeB).sync()
        assertEquals(1, report.added)
        assertEquals(1, report.downloaded)
        val mirrored = b.session.items().single()
        assertEquals("clip.mp4", mirrored.meta.name)
        assertTrue(b.session.isAvailableOffline(mirrored))
        assertContentEquals(thumb, b.session.thumbnail(mirrored))
        assertContentEquals(video, readAll(b.session, mirrored))
    }

    @Test
    fun deletesPropagateBetweenDevices() {
        val url = startServer() ?: return
        val (apiA, storeA, a) = newDevice(url, create = true)
        a.session.addFile("a.txt", "text/plain", 5, 1L, bytesSource("hello".toByteArray()), null)
        SyncEngine(apiA, storeA).sync()
        val (apiB, storeB, b) = newDevice(url)
        SyncEngine(apiB, storeB).sync()

        a.session.delete(a.session.items().single())
        assertTrue(a.session.items().isEmpty())
        assertEquals(1, SyncEngine(apiA, storeA).sync().deletedRemotely)
        assertTrue(apiA.listFiles().isEmpty())
        assertEquals(1, SyncEngine(apiB, storeB).sync().removedLocally)
        assertTrue(b.session.items().isEmpty())
    }

    @Test
    fun deletingAFileThatNeverUploadedStaysLocal() {
        val url = startServer() ?: return
        val (apiA, storeA, a) = newDevice(url, create = true)
        a.session.addFile("draft.txt", "text/plain", 3, 1L, bytesSource("abc".toByteArray()), null)
        a.session.delete(a.session.items().single())
        assertTrue(storeA.all().isEmpty())
        val report = SyncEngine(apiA, storeA).sync()
        assertEquals(0, report.uploaded)
        assertTrue(apiA.listFiles().isEmpty())
    }

    @Test
    fun offlineUnlockUsesLocalCopyAndRejectsWrongPassphrase() {
        val url = startServer() ?: return
        val (apiA, storeA, a) = newDevice(url, create = true)
        val data = Random(3).nextBytes(PLAIN_CHUNK_BYTES + 77)
        a.session.addFile("doc.bin", "application/octet-stream", data.size.toLong(), 1L, bytesSource(data), null)
        SyncEngine(apiA, storeA).sync()
        val credentials = OfflineCredentials.decode(a.credentials.encode())
        process?.destroy()
        process?.waitFor()

        val offline = VaultSession.unlockOffline(VaultApi(url), storeA, credentials, PASSPHRASE.toCharArray())
        assertContentEquals(data, readAll(offline, offline.items().single()))
        offline.addFile("later.txt", "text/plain", 2, 2L, bytesSource("hi".toByteArray()), null)
        assertEquals(2, offline.items().size)
        assertFailsWith<DecryptionException> {
            VaultSession.unlockOffline(VaultApi(url), storeA, credentials, "wrong passphrase".toCharArray())
        }
    }

    @Test
    fun interruptedUploadResumesFromServerSize() {
        val url = startServer() ?: return
        val (apiA, storeA, a) = newDevice(url, create = true)
        val data = Random(4).nextBytes(3 * PLAIN_CHUNK_BYTES + 999)
        val record = a.session.addFile("big.bin", "application/octet-stream", data.size.toLong(), 1L, bytesSource(data), null)
        val remoteId = apiA.createFile(record.sealedMeta)
        storeA.save(record.copy(remoteId = remoteId))
        apiA.appendContent(remoteId, 0, storeA.readContent(record.localId, 0, SEALED_CHUNK_BYTES + 4321))

        SyncEngine(apiA, storeA).sync()
        val (apiB, storeB, b) = newDevice(url)
        SyncEngine(apiB, storeB).sync()
        assertContentEquals(data, readAll(b.session, b.session.items().single()))
    }

    @Test
    fun uploadStartsOverWhenServerNoLongerHasTheIncompleteFile() {
        val url = startServer() ?: return
        val (apiA, storeA, a) = newDevice(url, create = true)
        val record = a.session.addFile("n.txt", "text/plain", 4, 1L, bytesSource("data".toByteArray()), null)
        storeA.save(record.copy(remoteId = "0".repeat(32)))
        assertEquals(1, SyncEngine(apiA, storeA).sync().uploaded)
        assertEquals(1, apiA.listFiles().size)
    }

    @Test
    fun partialDownloadResumesAndStreamingWorksBeforeDownload() {
        val url = startServer() ?: return
        val (apiA, storeA, a) = newDevice(url, create = true)
        val data = Random(5).nextBytes(3 * PLAIN_CHUNK_BYTES + 5)
        a.session.addFile("v.mp4", "video/mp4", data.size.toLong(), 1L, bytesSource(data), null)
        SyncEngine(apiA, storeA).sync()

        val (apiB, storeB, b) = newDevice(url)
        SyncEngine(apiB, storeB, mirrorContent = false).sync()
        val item = b.session.items().single()
        assertFalse(b.session.isAvailableOffline(item))
        assertContentEquals(data, readAll(b.session, item))

        storeB.appendContent(item.id, apiB.getRange(item.record.remoteId!!, 0, SEALED_CHUNK_BYTES + 99L))
        assertEquals(1, SyncEngine(apiB, storeB).sync().downloaded)
        assertTrue(b.session.isAvailableOffline(item))
        assertContentEquals(data, readAll(b.session, item))
    }

    @Test
    fun wrongPassphraseAndUnauthenticatedAccessAreRejected() {
        val url = startServer() ?: return
        newDevice(url, create = true)
        val denied = assertFailsWith<ApiException> {
            VaultSession.unlockOnline(VaultApi(url), LocalStore(tempDir("x")), "wrong passphrase".toCharArray(), fastFloor)
        }
        assertEquals(401, denied.status)
        assertFailsWith<ApiException> { VaultApi(url).listFiles() }
        val bad = assertFailsWith<ApiException> {
            VaultSession.createAccount(VaultApi(url), LocalStore(tempDir("y")), "wrong-token", "x".toCharArray(), fastParams, fastFloor)
        }
        assertEquals(403, bad.status)
    }

    @Test
    fun logoutRevokesTheSessionOnTheServer() {
        val url = startServer() ?: return
        val (api, _, result) = newDevice(url, create = true)
        val token = api.token!!
        result.session.logout()
        val revoked = assertFailsWith<ApiException> { VaultApi(url, token).listFiles() }
        assertEquals(401, revoked.status)
    }

    @Test
    fun localStoreDiscardsHalfWrittenRecordsOnOpen() {
        val root = tempDir("store")
        val store = LocalStore(root)
        val orphan = File(root, "records/" + "a".repeat(32)).apply { mkdirs() }
        File(orphan, "content.bin.tmp").writeBytes(ByteArray(10))
        LocalStore(root)
        assertFalse(orphan.exists())
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun concurrentSyncsDoNotDuplicateFiles() {
        val url = startServer() ?: return
        val (apiA, storeA, a) = newDevice(url, create = true)
        repeat(3) { index -> a.session.addFile("f$index.txt", "text/plain", 1, index.toLong(), bytesSource(byteArrayOf(index.toByte())), null) }
        SyncEngine(apiA, storeA).sync()

        val (_, storeB, b) = newDevice(url)
        val workers = (1..4).map { Thread { SyncEngine(VaultApi(url).also { it.login(b.authKey) }, storeB).sync() } }
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        assertEquals(3, b.session.items().size)
    }

    @Test
    fun duplicateRecordsForTheSameServerFileAreRemoved() {
        val url = startServer() ?: return
        val (apiA, storeA, a) = newDevice(url, create = true)
        a.session.addFile("once.txt", "text/plain", 4, 1L, bytesSource("once".toByteArray()), null)
        SyncEngine(apiA, storeA).sync()
        storeA.createSynced(apiA.listFiles().single(), null)
        assertEquals(2, a.session.items().size)
        SyncEngine(apiA, storeA).sync()
        val remaining = a.session.items().single()
        assertTrue(a.session.isAvailableOffline(remaining))
        assertContentEquals("once".toByteArray(), readAll(a.session, remaining))
    }

    @Test
    fun foldersSyncAndMovesReachOtherDevices() {
        val url = startServer() ?: return
        val (apiA, storeA, a) = newDevice(url, create = true)
        a.session.addFile("us.jpg", "image/jpeg", 2, 1L, bytesSource("us".toByteArray()), null, folder = "Holidays")
        a.session.addFile("loose.txt", "text/plain", 1, 2L, bytesSource("l".toByteArray()), null)
        SyncEngine(apiA, storeA).sync()

        val (apiB, storeB, b) = newDevice(url)
        SyncEngine(apiB, storeB).sync()
        assertEquals(mapOf("us.jpg" to "Holidays", "loose.txt" to ""), b.session.items().associate { it.meta.name to it.meta.folder })

        val loose = a.session.items().single { it.meta.name == "loose.txt" }
        a.session.moveToFolder(loose, "Family")
        assertEquals("Family", a.session.items().single { it.meta.name == "loose.txt" }.meta.folder)
        SyncEngine(apiA, storeA).sync()
        assertFalse(storeA.all().any { it.metaDirty })

        SyncEngine(apiB, storeB).sync()
        assertEquals("Family", b.session.items().single { it.meta.name == "loose.txt" }.meta.folder)
        assertContentEquals("l".toByteArray(), readAll(b.session, b.session.items().single { it.meta.name == "loose.txt" }))
    }

    @Test
    fun movingAFileBeforeItUploadsNeedsNoExtraRequest() {
        val url = startServer() ?: return
        val (apiA, storeA, a) = newDevice(url, create = true)
        a.session.addFile("later.txt", "text/plain", 1, 1L, bytesSource("x".toByteArray()), null)
        a.session.moveToFolder(a.session.items().single(), "Work")
        assertFalse(storeA.all().single().metaDirty)
        SyncEngine(apiA, storeA).sync()
        val (apiB, storeB, b) = newDevice(url)
        SyncEngine(apiB, storeB).sync()
        assertEquals("Work", b.session.items().single().meta.folder)
    }

    @Test
    fun foldersAreCreatedDeletedAndSyncedWithTheirFiles() {
        val url = startServer() ?: return
        val (apiA, storeA, a) = newDevice(url, create = true)
        listOf("Family", "Work", "Holidays").forEach { a.session.createFolder(it) }
        assertEquals(listOf("Family", "Work", "Holidays"), a.session.folders())
        assertTrue(a.session.items().isEmpty())
        assertFailsWith<IllegalArgumentException> { a.session.createFolder("holidays") }
        assertFailsWith<IllegalArgumentException> { a.session.createFolder("   ") }

        a.session.addFile("pic.jpg", "image/jpeg", 3, 1L, bytesSource("pic".toByteArray()), null, folder = "Holidays")
        a.session.addFile("keep.jpg", "image/jpeg", 4, 2L, bytesSource("keep".toByteArray()), null, folder = "Family")
        SyncEngine(apiA, storeA).sync()

        val (apiB, storeB, b) = newDevice(url)
        SyncEngine(apiB, storeB).sync()
        assertEquals(listOf("Family", "Work", "Holidays"), b.session.folders())
        assertEquals(2, b.session.items().size)

        b.session.deleteFolder("Holidays")
        assertEquals(listOf("Family", "Work"), b.session.folders())
        assertEquals(listOf("keep.jpg"), b.session.items().map { it.meta.name })
        SyncEngine(apiB, storeB).sync()
        SyncEngine(apiA, storeA).sync()
        assertEquals(listOf("Family", "Work"), a.session.folders())
        assertEquals(listOf("keep.jpg"), a.session.items().map { it.meta.name })
    }

    @Test
    fun aLockedSessionRefusesToEncryptOrRead() {
        val url = startServer() ?: return
        val (_, storeA, a) = newDevice(url, create = true)
        a.session.addFile("before.txt", "text/plain", 1, 1L, bytesSource("b".toByteArray()), null)
        val item = a.session.items().single()
        a.session.lock()
        assertTrue(a.session.isLocked)
        assertFailsWith<IllegalStateException> {
            a.session.addFile("after.txt", "text/plain", 1, 2L, bytesSource("a".toByteArray()), null)
        }
        assertFailsWith<IllegalStateException> { a.session.moveToFolder(item, "Holidays") }
        assertFailsWith<IllegalStateException> { a.session.items() }
        assertFailsWith<IllegalStateException> { a.session.exportVaultKey() }
        assertEquals(1, storeA.all().size)
    }
}
