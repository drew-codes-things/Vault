package app.vault

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import vault.core.LocalStore
import vault.core.OfflineCredentials
import vault.core.StagingArea
import vault.core.VaultApi
import vault.core.normaliseServerUrl
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

const val SYNC_WORK = "vault-sync"
const val SYNC_TAG = "vault-sync-any"
private const val PERIODIC_SYNC_WORK = "vault-sync-periodic"
private const val PERIODIC_SYNC_HOURS = 1L
private const val CREDENTIALS = "credentials.bin"
private const val AUTH_KEY = "auth.bin"
private const val SERVER_PREFS = "server"
private const val SERVER_URL_KEY = "url"

class VaultEnvironment private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val serverPrefs = appContext.getSharedPreferences(SERVER_PREFS, Context.MODE_PRIVATE)
    val store = LocalStore(File(appContext.filesDir, "vault"))
    val secrets = DeviceSecrets(File(appContext.filesDir, "secrets"))
    val staging = StagingArea(File(appContext.cacheDir, "staging"))

    init {
        if (!staging.clear()) Log.w("Vault", "could not remove every unencrypted staged copy")
    }

    /** The user picks the server at setup unless a build bakes one in. */
    val serverConfigurable: Boolean = BuildConfig.SERVER_CONFIGURABLE

    /** Empty until the user enters an address. */
    val serverUrl: String
        get() = if (serverConfigurable) serverPrefs.getString(SERVER_URL_KEY, null) ?: BuildConfig.SERVER_URL else BuildConfig.SERVER_URL

    fun hasServer(): Boolean = serverUrl.isNotEmpty()

    /**
     * Validates and saves a new server address. The offline copy and stored keys belong to the
     * old server, so switching wipes them from this phone. Returns true when the address changed.
     */
    fun changeServer(input: String): Boolean {
        check(serverConfigurable) { "this edition has a fixed server" }
        val url = normaliseServerUrl(input)
        val previous = serverPrefs.getString(SERVER_URL_KEY, null)
        if (url == previous) return false
        if (previous != null || hasOfflineCredentials()) forgetVault()
        if (!serverPrefs.edit().putString(SERVER_URL_KEY, url).commit()) throw IOException("could not save the server address")
        return true
    }

    private fun forgetVault() {
        store.clear()
        secrets.removeBiometric()
        secrets.remove(CREDENTIALS)
        secrets.remove(AUTH_KEY)
    }

    fun newApi(): VaultApi {
        check(hasServer()) { "no server address set" }
        return VaultApi(serverUrl)
    }

    fun offlineCredentials(): OfflineCredentials? = secrets.get(CREDENTIALS)?.let { OfflineCredentials.decode(it) }

    fun hasOfflineCredentials(): Boolean = secrets.exists(CREDENTIALS)

    fun authKey(): ByteArray? = secrets.get(AUTH_KEY)

    fun remember(credentials: OfflineCredentials, authKey: ByteArray) {
        val previous = offlineCredentials()
        if (previous != null && !previous.wrappedVaultKey.contentEquals(credentials.wrappedVaultKey)) {
            store.clear()
            secrets.removeBiometric()
        }
        secrets.put(CREDENTIALS, credentials.encode())
        secrets.put(AUTH_KEY, authKey)
    }

    fun forgetAuthKey() = secrets.remove(AUTH_KEY)

    fun requestSync() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(SYNC_TAG)
            .build()
        val workManager = WorkManager.getInstance(appContext)
        workManager.enqueueUniqueWork(SYNC_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        val periodic = PeriodicWorkRequestBuilder<SyncWorker>(PERIODIC_SYNC_HOURS, TimeUnit.HOURS).setConstraints(constraints).addTag(SYNC_TAG).build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_SYNC_WORK, ExistingPeriodicWorkPolicy.UPDATE, periodic)
    }

    companion object {
        @Volatile
        private var instance: VaultEnvironment? = null

        fun get(context: Context): VaultEnvironment =
            instance ?: synchronized(this) { instance ?: VaultEnvironment(context).also { instance = it } }
    }
}
