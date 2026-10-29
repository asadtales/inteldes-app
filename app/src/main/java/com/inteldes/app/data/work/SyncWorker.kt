package com.inteldes.app.data.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.inteldes.app.IntelDesApp
import com.inteldes.app.R
import com.inteldes.app.data.sync.AuthException
import com.inteldes.app.data.sync.DriveAuth
import com.inteldes.app.data.sync.DriveClient
import com.inteldes.app.data.sync.RoomSyncLocal
import com.inteldes.app.data.sync.SyncEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** Runs one Drive sync (upload new/changed recordings, download ones from other devices, apply deletions). */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as IntelDesApp
        val store = app.syncStore
        if (!store.settings.value.connected) return Result.success()

        // Large audio uploads can run for minutes — keep this a foreground job so the OS doesn't kill it.
        runCatching { setForeground(foregroundInfo()) }

        return syncMutex.withLock {
            store.setRunning(true, "Menyambungkan ke Google Drive…")
            try {
                val auth = DriveAuth(applicationContext)
                val client = DriveClient(tokenProvider = { auth.tokenOrThrow() })
                val local = RoomSyncLocal(applicationContext, app.database, app.repository)
                val report = SyncEngine(local, client, store).run { store.setRunning(true, it) }
                store.finish(if (report.failed > 0) "${report.failed} item gagal: ${report.firstError}" else null)
                if (report.failed > 0 && runAttemptCount < MAX_ATTEMPTS - 1) Result.retry() else Result.success()
            } catch (e: CancellationException) {
                store.setRunning(false)
                throw e
            } catch (e: AuthException) {
                store.finish(e.message)
                Result.failure()
            } catch (e: IOException) {
                store.finish("Koneksi terputus: ${e.message ?: e.javaClass.simpleName}")
                if (runAttemptCount < MAX_ATTEMPTS - 1) Result.retry() else Result.failure()
            } catch (e: Exception) {
                store.finish(e.message ?: "Sinkronisasi gagal.")
                Result.failure()
            }
        }
    }

    private fun foregroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Sinkronisasi Drive", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Sinkronisasi Google Drive")
            .setContentText("Mengunggah / mengunduh rekaman…")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        // Same type as the processing worker: must match the manifest's SystemForegroundService type.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "sync_channel"
        private const val NOTIFICATION_ID = 3002
        private const val MAX_ATTEMPTS = 4
        private const val UNIQUE_NAME = "drive_sync"
        private val syncMutex = Mutex()

        /**
         * Queues a sync. [force] = the user tapped "Sinkronkan sekarang"; otherwise it only runs when
         * automatic sync is on. Respects the Wi-Fi-only option.
         */
        fun enqueue(context: Context, force: Boolean = false) {
            val store = (context.applicationContext as IntelDesApp).syncStore
            val s = store.settings.value
            if (!s.connected || (!force && !s.autoSync)) return
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (s.wifiOnly && !force) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            // APPEND_OR_REPLACE: a change made while a sync is running is picked up by a second run right after.
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
