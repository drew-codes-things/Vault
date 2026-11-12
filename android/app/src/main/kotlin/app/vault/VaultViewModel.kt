package app.vault

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.util.LruCache
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.biometric.BiometricManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.vault.media.imageThumbnail
import app.vault.media.videoThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import vault.core.ApiException
import vault.core.ChunkReader
import vault.core.DecryptionException
import vault.core.MediaKind
import vault.core.PlainSource
import vault.core.VaultApi
import vault.core.VaultItem
import vault.core.VaultSession
import vault.core.mediaKindOf
import vault.core.sealedSize
import vault.core.isTemporaryServerProblem
import vault.core.skipFully
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher

private const val AUTO_LOCK_DELAY_MS = 30_000L
private const val ADD_WAIT_POLL_MS = 5_000L
private const val THUMB_CACHE_BYTES = 48 * 1024 * 1024
private const val THUMB_PARALLELISM = 4
private const val MAX_INLINE_IMAGE_BYTES = 96L * 1024 * 1024
private const val MAX_IMAGE_EDGE = 4096
private const val SAVE_BUFFER_BYTES = 1 shl 20
private const val MIN_PASSPHRASE_LENGTH = 8
private const val ADD_STATUS_VISIBLE_MS = 1_500L
private const val ADD_FAILURE_VISIBLE_MS = 8_000L
private const val UNKNOWN_SIZE = -1L
val DEFAULT_FOLDERS = BuildConfig.DEFAULT_FOLDERS.split(",").map { it.trim() }.filter { it.isNotEmpty() }
const val UNSORTED = ""

class AddState(val name: String, val totalBytes: Long) {
    var doneBytes by mutableStateOf(0L)
    var failure by mutableStateOf<String?>(null)
    var finished by mutableStateOf(false)
}

class VaultViewModel(application: Application) : AndroidViewModel(application) {
    private val context: Context get() = getApplication()
    private val environment = VaultEnvironment.get(application)
    private val limiter = AttemptLimiter(application.getSharedPreferences("unlock", Context.MODE_PRIVATE))

    var unlocked by mutableStateOf(false)
        private set
    var offline by mutableStateOf(false)
        private set
    var syncing by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var items by mutableStateOf<List<VaultItem>>(emptyList())
        private set
    var folders by mutableStateOf<List<String>>(emptyList())
        private set
    var currentFolder by mutableStateOf<String?>(null)
    var shareAwaitingFolder by mutableStateOf<List<Uri>>(emptyList())
        private set
    val unsortedItems: List<VaultItem> get() = items.filter { it.meta.folder !in folders }
    val visibleItems: List<VaultItem>
        get() = when (val folder = currentFolder) {
            null -> emptyList()
            UNSORTED -> unsortedItems
            else -> items.filter { it.meta.folder == folder }
        }

    fun itemsIn(folder: String): List<VaultItem> = if (folder == UNSORTED) unsortedItems else items.filter { it.meta.folder == folder }

    fun openFolder(folder: String?) {
        currentFolder = folder
        selection = emptySet()
        viewerIndex = null
    }

    fun createFolder(name: String) {
        val current = session ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { current.createFolder(name) }
                reloadItems()
                environment.requestSync()
            } catch (error: IllegalArgumentException) {
                message = error.message
            } catch (error: Exception) {
                Log.w("Vault", "create folder failed", error)
                message = describe(error)
            }
        }
    }

    fun deleteFolder(name: String) {
        val current = session ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { current.deleteFolder(name) }
                itemsIn(name).forEach {
                    readers.remove(it.id)
                    thumbnailCache.remove(it.id)
                }
                if (currentFolder == name) openFolder(null)
                reloadItems()
                environment.requestSync()
            } catch (error: Exception) {
                Log.w("Vault", "delete folder failed", error)
                message = describe(error)
            }
        }
    }

    fun saveSharedTo(folder: String?) {
        val uris = shareAwaitingFolder
        shareAwaitingFolder = emptyList()
        if (folder != null) addAll(uris, folder)
    }
    var viewerIndex by mutableStateOf<Int?>(null)
    var selection by mutableStateOf<Set<String>>(emptySet())
    var choosingExternally = false
    val adds = mutableStateListOf<AddState>()
    val pendingCount: Int get() = items.count { it.pendingUpload }
    var hasOfflineCopy by mutableStateOf(environment.hasOfflineCredentials())
        private set
    val serverConfigurable = environment.serverConfigurable
    var serverUrl by mutableStateOf(environment.serverUrl)
        private set
    var biometricEnabled by mutableStateOf(environment.secrets.hasBiometricKey())
        private set
    var offerBiometric by mutableStateOf(false)
        private set
    private val unlockPreferences = application.getSharedPreferences("biometric", Context.MODE_PRIVATE)
    private val viewerPreferences = application.getSharedPreferences("viewer", Context.MODE_PRIVATE)

    var replayVideos by mutableStateOf(viewerPreferences.getBoolean("replay_videos", true))
        private set

    fun toggleReplayVideos() {
        replayVideos = !replayVideos
        viewerPreferences.edit().putBoolean("replay_videos", replayVideos).apply()
    }

    private var session: VaultSession? = null
    private var lockJob: Job? = null
    private var reloadGeneration = 0
    private val addJobs = mutableListOf<Job>()
    private val pendingUris = mutableListOf<Uri>()
    private val activeAdds = AtomicInteger(0)
    private val readers = ConcurrentHashMap<String, ChunkReader>()
    private val thumbnailGate = Semaphore(THUMB_PARALLELISM)
    private val thumbnailCache = object : LruCache<String, Bitmap>(THUMB_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    init {
        viewModelScope.launch {
            WorkManager.getInstance(application).getWorkInfosByTagFlow(SYNC_TAG).collect { infos ->
                val running = infos.any { it.state == WorkInfo.State.RUNNING }
                if (syncing && !running) reloadItems()
                syncing = running
            }
        }
    }

    fun unlock(passphrase: CharArray, setupToken: String?, confirmation: CharArray?, server: String? = null) {
        if (setupToken != null) {
            if (passphrase.size < MIN_PASSPHRASE_LENGTH) return fail("Use at least $MIN_PASSPHRASE_LENGTH characters")
            if (!passphrase.contentEquals(confirmation ?: CharArray(0))) return fail("Passkeys do not match")
        }
        if (server != null && !applyServer(server)) return
        if (!environment.hasServer()) return fail("Enter the server address")
        busy = true
        message = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    openSession(passphrase, setupToken)
                    if (setupToken != null) DEFAULT_FOLDERS.forEach { session?.createFolder(it) }
                }
                onUnlocked()
                offerBiometric = !biometricEnabled && biometricAvailable() && !unlockPreferences.getBoolean("declined", false)
            } catch (error: Exception) {
                message = describe(error)
            } finally {
                passphrase.fill(' ')
                confirmation?.fill(' ')
                busy = false
            }
        }
    }

    private fun applyServer(input: String): Boolean {
        try {
            if (environment.changeServer(input)) {
                serverUrl = environment.serverUrl
                hasOfflineCopy = false
                biometricEnabled = false
            }
        } catch (error: IllegalArgumentException) {
            fail(error.message ?: "Invalid server address")
            return false
        } catch (error: IOException) {
            fail("Could not save the server address")
            return false
        }
        return true
    }

    private fun onUnlocked() {
        unlocked = true
        reloadItems()
        environment.requestSync()
        flushPendingUris()
    }

    fun biometricAvailable(): Boolean =
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    fun prepareBiometricEnrollment(): Cipher? =
        runCatching { environment.secrets.biometricEncryptCipher() }.onFailure { Log.w("Vault", "cannot prepare fingerprint key", it) }.getOrNull()

    fun completeBiometricEnrollment(cipher: Cipher) {
        offerBiometric = false
        val current = session ?: return
        val vaultKey = current.exportVaultKey()
        try {
            environment.secrets.storeBiometric(cipher, vaultKey)
            biometricEnabled = true
            message = "Fingerprint unlock is on"
        } catch (error: Exception) {
            Log.w("Vault", "storing fingerprint key failed", error)
            environment.secrets.removeBiometric()
            message = "Could not turn on fingerprint unlock"
        } finally {
            vaultKey.fill(0)
        }
    }

    fun declineBiometric() {
        offerBiometric = false
        unlockPreferences.edit().putBoolean("declined", true).apply()
    }

    fun disableBiometric() {
        environment.secrets.removeBiometric()
        biometricEnabled = false
        message = "Fingerprint unlock is off"
    }

    fun biometricUnlockCipher(): Cipher? {
        val cipher = runCatching { environment.secrets.biometricDecryptCipher() }.onFailure { Log.w("Vault", "fingerprint key unavailable", it) }.getOrNull()
        if (cipher == null) {
            biometricEnabled = environment.secrets.hasBiometricKey()
            message = "Fingerprint unlock was reset. Use your passkey"
        }
        return cipher
    }

    fun unlockWithBiometric(cipher: Cipher) {
        busy = true
        message = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val vaultKey = environment.secrets.readBiometric(cipher)
                    val api = environment.newApi()
                    offline = !loginWithStoredKey(api)
                    session = VaultSession.withVaultKey(api, environment.store, vaultKey)
                    vaultKey.fill(0)
                    Log.i("Vault", if (offline) "unlocked offline with fingerprint" else "unlocked online with fingerprint")
                }
                onUnlocked()
            } catch (error: Exception) {
                Log.w("Vault", "fingerprint unlock failed", error)
                message = describe(error)
            } finally {
                busy = false
            }
        }
    }

    private fun loginWithStoredKey(api: VaultApi): Boolean {
        val authKey = environment.authKey() ?: return false
        return try {
            api.login(authKey)
            true
        } catch (error: ApiException) {
            Log.w("Vault", "server rejected stored login", error)
            if (error.status == 401) environment.forgetAuthKey()
            false
        } catch (error: IOException) {
            false
        }
    }

    private fun openSession(passphrase: CharArray, setupToken: String?) {
        val api = environment.newApi()
        try {
            val result = if (setupToken != null) {
                VaultSession.createAccount(api, environment.store, setupToken, passphrase)
            } else {
                VaultSession.unlockOnline(api, environment.store, passphrase)
            }
            environment.remember(result.credentials, result.authKey)
            hasOfflineCopy = true
            session = result.session
            offline = false
            limiter.recordSuccess()
            Log.i("Vault", "unlocked online")
        } catch (error: IOException) {
            if (!isTemporaryServerProblem(error) || setupToken != null) throw error
            val credentials = environment.offlineCredentials() ?: throw error
            val waitMs = limiter.remainingLockoutMs()
            if (waitMs > 0) throw ApiException(429, "locked", (waitMs / 1000).toInt() + 1)
            try {
                session = VaultSession.unlockOffline(api, environment.store, credentials, passphrase)
            } catch (wrong: DecryptionException) {
                limiter.recordFailure()
                throw ApiException(401, "wrong passkey")
            }
            limiter.recordSuccess()
            offline = true
            Log.i("Vault", "unlocked offline after ${error.javaClass.simpleName}")
        }
    }

    private fun fail(text: String) {
        message = text
    }

    private fun describe(error: Throwable): String = when {
        error is ApiException && error.status == 401 -> "Wrong passkey"
        error is ApiException && error.status == 429 -> "Too many attempts. Try again in ${error.retryAfterSeconds}s"
        error is ApiException && error.status == 403 -> "Setup token rejected"
        error is ApiException && error.status == 404 -> "No account on this server yet. Use first-time setup"
        error is ApiException && error.status == 409 -> "An account already exists on this server"
        error is ApiException -> error.message ?: "Server error"
        error is DecryptionException -> "Could not decrypt this data"
        error is IllegalStateException && session == null -> "Vault was locked before this finished"
        error is IOException && !hasOfflineCopy -> "Cannot reach the server. Connect once to set up this phone"
        error is IOException -> error.message ?: "Cannot reach the server"
        else -> error.message ?: "Something went wrong"
    }

    fun reloadItems() {
        val current = session ?: return
        val generation = ++reloadGeneration
        viewModelScope.launch {
            try {
                val (loadedItems, loadedFolders) = withContext(Dispatchers.IO) { current.items() to current.folders() }
                if (generation != reloadGeneration || session !== current) return@launch
                folders = loadedFolders
                items = loadedItems
            } catch (error: Exception) {
                message = describe(error)
            }
        }
    }

    fun syncNow() {
        val current = session
        if (current != null && offline) {
            viewModelScope.launch {
                if (withContext(Dispatchers.IO) { loginWithStoredKey(current.api) }) offline = false
            }
        }
        environment.requestSync()
        reloadItems()
    }

    fun lock() {
        val ending = session
        session = null
        unlocked = false
        offline = false
        items = emptyList()
        folders = emptyList()
        currentFolder = null
        shareAwaitingFolder = emptyList()
        viewerIndex = null
        selection = emptySet()
        adds.clear()
        synchronized(addJobs) {
            addJobs.forEach { it.cancel() }
            addJobs.clear()
        }
        readers.clear()
        thumbnailCache.evictAll()
        if (ending != null) {
            Thread {
                runCatching { ending.logout() }.onFailure { Log.w("Vault", "server logout failed", it) }
            }.start()
        }
    }

    override fun onCleared() {
        lock()
    }

    fun appStarted() {
        choosingExternally = false
        lockJob?.cancel()
        lockJob = null
    }

    fun appStopped() {
        if (!unlocked || choosingExternally) return
        lockJob?.cancel()
        lockJob = viewModelScope.launch {
            delay(AUTO_LOCK_DELAY_MS)
            while (activeAdds.get() > 0) delay(ADD_WAIT_POLL_MS)
            lock()
        }
    }

    fun readerFor(id: String): ChunkReader? {
        readers[id]?.let { return it }
        val current = session ?: return null
        val item = items.firstOrNull { it.id == id } ?: return null
        return readers.getOrPut(id) { current.reader(item) }
    }

    suspend fun thumbnailFor(item: VaultItem): Bitmap? {
        thumbnailCache.get(item.id)?.let { return it }
        val current = session ?: return null
        if (!item.hasThumb) return null
        return thumbnailGate.withPermit {
            withContext(Dispatchers.IO) {
                val bytes = runCatching { current.thumbnail(item) }.getOrNull() ?: return@withContext null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { thumbnailCache.put(item.id, it) }
            }
        }
    }

    suspend fun loadImage(item: VaultItem): Bitmap? {
        if (item.meta.plainSize > MAX_INLINE_IMAGE_BYTES) return null
        val reader = readerFor(item.id) ?: return null
        return withContext(Dispatchers.IO) {
            val bytes = reader.read(0, item.meta.plainSize.toInt())
            val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                var sample = 1
                while (maxOf(info.size.width, info.size.height) / sample > MAX_IMAGE_EDGE) sample *= 2
                decoder.setTargetSampleSize(sample)
            }
        }
    }

    fun toggleSelection(id: String) {
        selection = if (id in selection) selection - id else selection + id
    }

    fun deleteSelected() = delete(items.filter { it.id in selection })

    fun moveSelected(folder: String) {
        val current = session ?: return
        val targets = items.filter { it.id in selection }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { targets.forEach { current.moveToFolder(it, folder) } }
                selection = emptySet()
                reloadItems()
                environment.requestSync()
                message = "Moved ${targets.size} to ${folder.ifEmpty { "Unsorted" }}"
            } catch (error: Exception) {
                Log.w("Vault", "move failed", error)
                message = describe(error)
            }
        }
    }

    fun delete(targets: List<VaultItem>) {
        val current = session ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { targets.forEach { current.delete(it) } }
                targets.forEach {
                    readers.remove(it.id)
                    thumbnailCache.remove(it.id)
                }
                selection = emptySet()
                viewerIndex = null
                reloadItems()
                environment.requestSync()
            } catch (error: Exception) {
                message = describe(error)
            }
        }
    }

    fun saveTo(item: VaultItem, destination: Uri) {
        val reader = readerFor(item.id) ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(destination) ?: throw IOException("cannot write destination")
                    output.use { stream ->
                        var position = 0L
                        while (position < item.meta.plainSize) {
                            val bytes = reader.read(position, SAVE_BUFFER_BYTES)
                            stream.write(bytes)
                            position += bytes.size
                        }
                    }
                }
                message = "Saved ${item.meta.name}"
            } catch (error: Exception) {
                Log.w("Vault", "save of ${item.id} failed", error)
                message = describe(error)
            }
        }
    }

    fun upload(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (session == null) {
            pendingUris += uris
            return
        }
        routeToFolder(uris)
    }

    private fun routeToFolder(uris: List<Uri>) {
        val folder = currentFolder
        if (folder == null) shareAwaitingFolder = shareAwaitingFolder + uris else addAll(uris, folder)
    }

    private fun flushPendingUris() {
        if (pendingUris.isEmpty()) return
        val queued = pendingUris.toList()
        pendingUris.clear()
        routeToFolder(queued)
    }

    private fun addAll(uris: List<Uri>, folder: String) {
        val current = session ?: return
        activeAdds.incrementAndGet()
        UploadService.start(context)
        val job = viewModelScope.launch(Dispatchers.IO) {
            try {
                for (uri in uris) {
                    if (current.isLocked) break
                    addOne(current, uri, folder)
                }
            } finally {
                if (activeAdds.decrementAndGet() == 0) UploadService.stop(context)
                reloadItems()
                environment.requestSync()
            }
        }
        synchronized(addJobs) { addJobs += job }
        job.invokeOnCompletion { synchronized(addJobs) { addJobs -= job } }
    }

    private suspend fun addOne(current: VaultSession, uri: Uri, folder: String) {
        val resolver = context.contentResolver
        var name = "file"
        var size = UNKNOWN_SIZE
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)?.let { name = it }
                if (!cursor.isNull(1)) size = cursor.getLong(1)
            }
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        if (size == UNKNOWN_SIZE) {
            size = runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull()?.takeIf { it >= 0 } ?: UNKNOWN_SIZE
        }
        if (name == "file") {
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            name = (uri.lastPathSegment ?: "file") + (extension?.let { ".$it" } ?: "")
        }
        val staged = if (size == UNKNOWN_SIZE) stageLocally(uri, name) else null
        if (staged != null) size = staged.length()
        val state = AddState(name, sealedSize(size.coerceAtLeast(0)))
        withContext(Dispatchers.Main) { adds.add(state) }
        try {
            if (size == UNKNOWN_SIZE) throw IOException("cannot determine size of $name")
            val thumbnail = when (mediaKindOf(mime)) {
                MediaKind.IMAGE -> if (staged != null) imageThumbnail(staged) else imageThumbnail(context, uri)
                MediaKind.VIDEO -> if (staged != null) videoThumbnail(staged) else videoThumbnail(context, uri)
                MediaKind.OTHER -> null
            }
            val source = PlainSource { skip ->
                val stream = if (staged != null) staged.inputStream() else resolver.openInputStream(uri) ?: throw IOException("cannot open $name")
                stream.skipFully(skip)
                stream
            }
            current.addFile(name, mime, size, System.currentTimeMillis(), source, thumbnail, folder) { done -> state.doneBytes = done }
            state.finished = true
            reloadItems()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("Vault", "adding $name failed", error)
            state.failure = describe(error)
        } finally {
            staged?.delete()
        }
        delay(if (state.failure == null) ADD_STATUS_VISIBLE_MS else ADD_FAILURE_VISIBLE_MS)
        withContext(Dispatchers.Main) { adds.remove(state) }
    }

    private fun stageLocally(uri: Uri, name: String): File? {
        val target = File(context.cacheDir, "staging").apply { mkdirs() }.let { File.createTempFile("add-", ".bin", it) }
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open $name")
            input.use { stream -> FileOutputStream(target).use { out -> stream.copyTo(out) } }
            target
        } catch (error: IOException) {
            Log.w("Vault", "staging $name failed", error)
            target.delete()
            null
        }
    }
}
