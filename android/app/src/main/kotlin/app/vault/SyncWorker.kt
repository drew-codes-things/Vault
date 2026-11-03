package app.vault

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vault.core.ApiException
import vault.core.SyncEngine
import java.io.IOException

private const val CHANNEL_ID = "sync"
private const val NOTIFICATION_ID = 2
private const val UNAUTHORIZED = 401

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val environment = VaultEnvironment.get(applicationContext)
        val authKey = environment.authKey() ?: return@withContext Result.success()
        runCatching { setForeground(getForegroundInfo()) }.onFailure { Log.w("Vault", "sync running without foreground notification", it) }
        val api = environment.newApi()
        try {
            api.login(authKey)
            val report = SyncEngine(api, environment.store).sync()
            Log.i("Vault", "sync done: up=${report.uploaded} del=${report.deletedRemotely} new=${report.added} gone=${report.removedLocally} dl=${report.downloaded}")
            Result.success()
        } catch (error: ApiException) {
            Log.w("Vault", "sync rejected by server", error)
            if (error.status == UNAUTHORIZED) environment.forgetAuthKey()
            if (error.status in 500..599) Result.retry() else Result.failure()
        } catch (error: IOException) {
            Log.w("Vault", "sync interrupted, will retry", error)
            Result.retry()
        } finally {
            if (api.token != null) runCatching { api.logout() }.onFailure { Log.w("Vault", "sync logout failed", it) }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Sync", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Syncing Vault")
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
