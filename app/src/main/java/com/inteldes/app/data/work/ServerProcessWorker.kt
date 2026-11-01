package com.inteldes.app.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Constraints
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.gson.JsonObject
import com.inteldes.app.IntelDesApp
import com.inteldes.app.data.server.ServerException
import com.inteldes.app.data.sync.AuthException
import com.inteldes.app.data.web.RoomWebLibrary
import com.inteldes.app.data.web.RoomWebLocal
import com.inteldes.app.data.web.ServerProcessor
import com.inteldes.app.data.web.WebApi
import com.inteldes.app.data.web.WebLibrarySync
import com.inteldes.app.data.web.WebSyncEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Processes ONE recording on the server with the server's AI keys, then brings the result back. Only this
 * recording is sent and received; the rest of the phone's archive is not touched by this run.
 * What the user sees while it works comes from [com.inteldes.app.data.web.ServerJobs].
 */
class ServerProcessWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as IntelDesApp
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val jobs = app.serverJobs
        val store = app.webSyncStore
        val account = app.accountService
        try {
            if (!store.enabled || !account.loggedIn) return fail(id, "Masuk dan aktifkan sinkron web dulu.")
            if (store.pullOnly) return fail(id, "Sinkron sedang hanya menerima dari server.")
            jobs.running(id, "Menyiapkan…")

            val org = account.currentOrg()
            if (store.orgId != null && store.orgId != org.id) return fail(id, "Akun ini milik organisasi lain. Konfirmasi dulu di Pengaturan.")

            val api = WebApi(account.api)
            val local = RoomWebLocal(applicationContext, app.database)
            // This run sends and receives only [id]: every other recording is "kept out" for its duration.
            val others = app.database.recordingDao().ids().toSet() - id
            val engine = WebSyncEngine(local, api, store, excluded = { others })

            val settings = app.settingsStore.settings.first()
            val rec = app.repository.getRecording(id) ?: return fail(id, "Rekaman tidak ditemukan.")
            // Refuse early, before the audio travels, when the package clearly has no room (the server still has the last word).
            ServerProcessor.quotaBlock(runCatching { api.settings() }.getOrNull()?.aiAccess, rec.durationSec) { at ->
                java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale("id", "ID")).format(java.util.Date(at))
            }?.let { return fail(id, it) }
            // Custom templates must exist on the server before they can be named in the request.
            runCatching { WebLibrarySync(RoomWebLibrary(app.database), api, state = store.library).run() }
            val body = JsonObject().apply {
                addProperty("provider", settings.provider.name)
                addProperty("density", settings.density.name)
                addProperty("language", settings.outputLanguage.name)
                addProperty("economy", settings.economyMode)
                addProperty("templateId", rec.templateId)
            }
            val processor = ServerProcessor(
                syncThisRecording = { WebSyncWorker.mutex.withLock { engine.run() } },
                start = { api.processRecording(id, body) },
                status = { api.getAggregate(id)?.recording?.let { it.status to it.errorMessage } },
                progress = { jobs.running(id, it) },
            )
            return when (val outcome = processor.run()) {
                ServerProcessor.Outcome.Done -> { jobs.finish(id); Result.success() }
                is ServerProcessor.Outcome.Failed -> fail(id, outcome.message)
            }
        } catch (e: CancellationException) {
            jobs.finish(id)
            throw e
        } catch (e: AuthException) {
            return fail(id, e.message ?: "Sesi tidak berlaku. Masuk lagi.")
        } catch (e: ServerException) {
            // 429 quota_exceeded, 422 secret_missing ... arrive here with the server's own wording.
            return fail(id, e.message ?: "Server menolak permintaan.")
        } catch (e: IOException) {
            return fail(id, "Koneksi terputus: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            return fail(id, e.message ?: "Pemrosesan di server gagal.")
        }
    }

    private fun fail(id: String, message: String): Result {
        (applicationContext as IntelDesApp).serverJobs.failed(id, message)
        return Result.failure()
    }

    companion object {
        private const val KEY_ID = "id"

        fun enqueue(context: Context, recordingId: String) {
            val request = OneTimeWorkRequestBuilder<ServerProcessWorker>()
                .setInputData(Data.Builder().putString(KEY_ID, recordingId).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("server_process_$recordingId", ExistingWorkPolicy.KEEP, request)
        }
    }
}
