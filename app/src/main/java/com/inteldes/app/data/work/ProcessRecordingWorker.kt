package com.inteldes.app.data.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.inteldes.app.R
import com.inteldes.app.IntelDesApp
import com.inteldes.app.data.audio.whisper.OnDeviceWhisperClient
import com.inteldes.app.data.model.ProcessingStep
import com.inteldes.app.data.model.WhisperEngine
import com.inteldes.app.data.network.ApiException
import com.inteldes.app.data.network.NotulensiGenerator
import com.inteldes.app.data.network.TranscriptResult
import com.inteldes.app.data.network.WhisperCloudClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val KEY_RECORDING_ID = "recordingId"
private const val KEY_STEP = "step"
private const val KEY_RANGE_START = "rangeStartSec"
private const val KEY_RANGE_END = "rangeEndSec"
const val KEY_ERROR = "error"

/**
 * Real background pipeline: cloud transcription (with diarization) → AI notulensi
 * generation → persistence. Mirrors the Proses screen's step list 1:1 — each
 * [ProcessingStep] is reported via [setProgress] as it actually starts, not simulated.
 *
 * Each recording is its own independent unique work item (name = [nameFor]) — WorkManager
 * is free to run several concurrently. What actually keeps them from hitting the cloud
 * transcription API at the same time (and tripping its rate limit) is [processingMutex]:
 * every doWork() blocks on it before doing any real work, so only one recording's network
 * calls run at a time, in the order they arrived. An earlier attempt tried to serialize
 * them by chaining every recording into one shared WorkManager queue instead — but
 * WorkManager cancels chained work automatically once an earlier link in the same chain
 * permanently fails, so one bad recording silently doomed every recording queued after
 * it. A plain in-process Mutex has no such failure-propagation behavior.
 *
 * A 429 from the cloud transcription API, or any network-level [IOException] (timeout,
 * dropped connection, transient DNS failure — all common on real mobile networks,
 * especially uploading a long recording), triggers [Result.retry] with backoff instead
 * of failing outright, up to [MAX_ATTEMPTS] tries.
 */
class ProcessRecordingWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as IntelDesApp
        val recordingId = inputData.getString(KEY_RECORDING_ID) ?: return Result.failure()
        val repo = app.repository

        val recording = repo.getRecording(recordingId) ?: return Result.failure()
        val rangeStartSec = inputData.getInt(KEY_RANGE_START, -1).takeIf { it >= 0 }
        val rangeEndSec = inputData.getInt(KEY_RANGE_END, -1).takeIf { it >= 0 }
        repo.markWorking(recordingId)

        // Promotes this work to a real foreground service (with an ongoing notification) for
        // its whole duration — plain background WorkManager jobs have no such guarantee and
        // real devices (Samsung's battery manager especially) do kill them once the app is
        // fully backgrounded, silently breaking "PROSES DI LATAR BELAKANG" mid-transcription.
        runCatching { setForeground(foregroundInfo(recording.title)) }

        return processingMutex.withLock {
            try {
                setProgress(workDataOf(KEY_STEP to ProcessingStep.PREP.name))
                // A conversation imported from text has no audio: its transcript is already stored.
                val audioFile: File? = recording.audioFilePath?.let { path ->
                    File(path).also { if (!it.exists()) throw ApiException("Berkas audio tidak ditemukan di perangkat.") }
                }
                val storedTranscript = if (audioFile == null) repo.observeTranscript(recordingId).first() else emptyList()
                if (audioFile == null && storedTranscript.isEmpty()) throw ApiException("Berkas audio tidak ditemukan.")

                setProgress(workDataOf(KEY_STEP to ProcessingStep.TRANSCRIBE.name))
                val transcriptResult: TranscriptResult = if (audioFile == null) {
                    TranscriptResult(storedTranscript.map { it.speaker }.toSet().size, storedTranscript)
                } else if (recording.engine == WhisperEngine.ON_DEVICE) {
                    OnDeviceWhisperClient(app).transcribe(audioFile, rangeStartSec = rangeStartSec, rangeEndSec = rangeEndSec)
                } else {
                    val whisperKey = app.securePrefs.effectiveWhisperCloudKey
                        ?: throw ApiException("API key transkripsi awan belum diatur. Buka Pengaturan.")
                    WhisperCloudClient(whisperKey).transcribe(audioFile, recording.audioMimeType, rangeStartSec = rangeStartSec, rangeEndSec = rangeEndSec)
                }

                setProgress(workDataOf(KEY_STEP to ProcessingStep.DIARIZE.name))
                repo.saveTranscript(recordingId, transcriptResult.speakerCount, transcriptResult.segments)

                setProgress(workDataOf(KEY_STEP to ProcessingStep.SUMMARIZE.name))
                val current = app.settingsStore.settings.first()
                val generator = NotulensiGenerator(app.securePrefs)
                val template = app.templateRepository.find(recording.templateId)
                val notulensi = generator.generateNotulensi(
                    provider = current.provider,
                    durationSec = recording.durationSec,
                    meetingTitle = recording.title,
                    segments = transcriptResult.segments,
                    density = current.density,
                    template = template,
                    location = recording.locationName,
                    outputLanguage = current.outputLanguage,
                    economyMode = current.economyMode,
                )

                setProgress(workDataOf(KEY_STEP to ProcessingStep.LINK.name))
                val resolvedProvider = generator.resolve(current.provider, recording.durationSec)
                repo.saveNotulensi(recordingId, resolvedProvider, notulensi, current.density)

                if (!current.keepOriginalAudio) audioFile?.delete()

                SyncWorker.enqueue(applicationContext)
                Result.success()
            } catch (e: CancellationException) {
                // The coroutine is already cancelled at this point, so the DB write below needs
                // NonCancellable or it would throw immediately without persisting anything.
                withContext(NonCancellable) { repo.markFailed(recordingId, "Dibatalkan oleh pengguna.") }
                throw e
            } catch (e: ApiException) {
                android.util.Log.w("ProcessWorker", "ApiException ${e.httpCode}: ${e.message}", e)
                if (e.httpCode == 429 && runAttemptCount < MAX_ATTEMPTS - 1) {
                    return@withLock Result.retry()
                }
                val message = if (e.httpCode == 429) {
                    "Layanan transkripsi awan sedang dibatasi (terlalu banyak permintaan). Coba lagi beberapa menit lagi, atau cek kuota/plan API key kamu."
                } else {
                    e.message ?: "Gagal memproses rekaman."
                }
                repo.markFailed(recordingId, message)
                Result.failure(workDataOf(KEY_ERROR to message))
            } catch (e: IOException) {
                android.util.Log.w("ProcessWorker", "IOException attempt $runAttemptCount: ${e.message}", e)
                // Connect/read/write timeouts, DNS hiccups, dropped connections — all common
                // on real mobile networks, especially uploading a long recording. These are
                // transient, so retry with backoff instead of failing on the first blip.
                if (runAttemptCount < MAX_ATTEMPTS - 1) {
                    return@withLock Result.retry()
                }
                val message = "Gagal terhubung ke layanan awan setelah beberapa kali percobaan: ${e.message ?: e.javaClass.simpleName}. Periksa koneksi internet lalu coba lagi."
                repo.markFailed(recordingId, message)
                Result.failure(workDataOf(KEY_ERROR to message))
            } catch (e: Exception) {
                android.util.Log.w("ProcessWorker", "Failed: ${e.message}", e)
                repo.markFailed(recordingId, e.message ?: "Gagal memproses rekaman.")
                Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Gagal memproses rekaman.")))
            }
        }
    }

    private fun foregroundInfo(title: String): ForegroundInfo {
        ensureChannel()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Memproses rekaman")
            .setContentText(title)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        // Must match the foregroundServiceType declared for SystemForegroundService in the
        // manifest ("dataSync") — Android 14+ rejects a mismatch at runtime.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Memproses rekaman", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "processing_channel"
        private const val NOTIFICATION_ID = 3001
        private const val MAX_ATTEMPTS = 5
        // Guards the actual network/API work across all ProcessRecordingWorker instances in
        // this process, so recordings transcribe one at a time regardless of how many
        // WorkManager itself schedules concurrently.
        private val processingMutex = Mutex()

        fun enqueue(context: Context, recordingId: String, rangeStartSec: Int? = null, rangeEndSec: Int? = null) {
            val request = OneTimeWorkRequestBuilder<ProcessRecordingWorker>()
                .setInputData(
                    workDataOf(
                        KEY_RECORDING_ID to recordingId,
                        KEY_RANGE_START to (rangeStartSec ?: -1),
                        KEY_RANGE_END to (rangeEndSec ?: -1),
                    ),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // KEEP: a no-op if this recording already has pending/running work (so navigating
            // back into the Processing screen while it's genuinely still going doesn't
            // interrupt it), and starts a fresh attempt whenever nothing is currently pending —
            // which is also true right after a previous attempt finished (failed/cancelled/
            // succeeded), so this doubles as the retry path.
            WorkManager.getInstance(context).enqueueUniqueWork(nameFor(recordingId), ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, recordingId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(nameFor(recordingId))
        }

        fun observeStep(context: Context, recordingId: String): Flow<ProcessingStep?> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(nameFor(recordingId)).map { infos ->
                val info = infos.firstOrNull() ?: return@map null
                when (info.state) {
                    WorkInfo.State.SUCCEEDED -> ProcessingStep.entries.last()
                    else -> info.progress.getString(KEY_STEP)?.let { name -> ProcessingStep.entries.firstOrNull { it.name == name } }
                }
            }

        fun observeError(context: Context, recordingId: String): Flow<String?> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(nameFor(recordingId)).map { infos ->
                infos.firstOrNull()?.outputData?.getString(KEY_ERROR)
            }

        private fun nameFor(recordingId: String) = "process_$recordingId"
    }
}
