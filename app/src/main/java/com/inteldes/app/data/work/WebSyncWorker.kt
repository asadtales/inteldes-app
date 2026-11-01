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
import com.inteldes.app.data.server.ServerException
import com.inteldes.app.data.sync.AuthException
import com.inteldes.app.data.web.RoomWebLocal
import com.inteldes.app.data.web.WebApi
import com.inteldes.app.data.web.WebSyncEngine
import com.inteldes.app.data.web.WebSyncStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * One two-way sync with the IntelDes web server. Runs only when the user turned it on AND is signed in, and
 * never against an organisation the user has not confirmed. Independent of the Google Drive sync, which it
 * does not touch.
 */
class WebSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as IntelDesApp
        val store = app.webSyncStore
        val account = app.accountService
        if (!store.enabled || !account.loggedIn) return Result.success()
        if (account.session.value?.role == "pembaca") {
            store.lastSummary = "Peran pembaca tidak bisa mengirim rekaman ke web."
            return Result.success()
        }
        runCatching { setForeground(foregroundInfo()) }

        return mutex.withLock {
            try {
                val org = account.currentOrg()
                val known = store.orgId
                if (known == null) {
                    store.orgId = org.id
                } else if (known != org.id) {
                    // A different organisation: sending this phone's recordings there could mix two villages' data.
                    store.lastSummary = "Akun ini milik organisasi lain (${org.name}). Konfirmasi di Pengaturan sebelum mengirim."
                    store.needsOrgConfirm = org.name
                    store.needsOrgKind = org.kind ?: "team"
                    return@withLock Result.success()
                }
                store.needsOrgConfirm = null
                store.needsOrgKind = null
                val api = WebApi(account.api)
                val engine = WebSyncEngine(RoomWebLocal(applicationContext, app.database), api, store, pullOnly = store.pullOnly, excluded = app.syncExclusions::snapshot)
                val report = engine.run { store.progress = it }
                val lib = com.inteldes.app.data.web.WebLibrarySync(com.inteldes.app.data.web.RoomWebLibrary(app.database), api, pullOnly = store.pullOnly, state = store.library).run()
                report.pushed += lib.pushed
                report.pulled += lib.pulled
                report.failed += lib.failed
                if (report.firstError == null) report.firstError = lib.firstError
                store.progress = null
                store.lastRunAt = System.currentTimeMillis()
                // The run right after a big one only finds echoes of what was just sent (all zeros): keep showing the
                // last run that actually did something, and clear the failure count once a run is clean.
                if (report.pushed + report.pulled + report.failed > 0) {
                    store.lastPushed = report.pushed
                    store.lastPulled = report.pulled
                }
                store.lastFailed = report.failed
                store.lastSummary = if (report.failed > 0) "${report.failed} gagal: ${report.firstError}" else "Selesai."
                if (report.failed > 0 && runAttemptCount < MAX_ATTEMPTS - 1) Result.retry() else Result.success()
            } catch (e: CancellationException) {
                store.progress = null
                throw e
            } catch (e: AuthException) {
                fail(store, e.message ?: "Sesi tidak berlaku.")
                Result.failure()
            } catch (e: ServerException) {
                fail(store, e.message ?: "Server menolak permintaan.")
                if (e.status in 500..599 && runAttemptCount < MAX_ATTEMPTS - 1) Result.retry() else Result.failure()
            } catch (e: IOException) {
                fail(store, "Koneksi terputus: ${e.message ?: e.javaClass.simpleName}")
                if (runAttemptCount < MAX_ATTEMPTS - 1) Result.retry() else Result.failure()
            } catch (e: Exception) {
                fail(store, e.message ?: "Sinkronisasi web gagal.")
                Result.failure()
            }
        }
    }

    private fun fail(store: WebSyncStore, message: String) {
        store.progress = null
        store.lastSummary = message
        store.lastFailed = store.lastFailed.coerceAtLeast(1)
    }

    private fun foregroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Sinkron ke web", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Sinkron ke web IntelDes")
            .setContentText("Mengirim / mengambil rekaman…")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "web_sync_channel"
        private const val NOTIFICATION_ID = 3003
        private const val MAX_ATTEMPTS = 4
        private const val UNIQUE_NAME = "web_sync"
        internal val mutex = Mutex()

        /** Queues a run; does nothing unless the user switched the web sync on and is signed in. */
        fun enqueue(context: Context) {
            val app = context.applicationContext as IntelDesApp
            if (!app.webSyncStore.enabled || !app.accountService.loggedIn) return
            val request = OneTimeWorkRequestBuilder<WebSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
