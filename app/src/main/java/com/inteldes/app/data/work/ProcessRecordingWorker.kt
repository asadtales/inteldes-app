package com.inteldes.app.data.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.inteldes.app.IntelDesApp
import com.inteldes.app.data.model.ProcessingStep
import com.inteldes.app.data.model.WhisperEngine
import com.inteldes.app.data.network.ApiException
import com.inteldes.app.data.network.NotulensiGenerator
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
import java.util.concurrent.TimeUnit

private const val KEY_RECORDING_ID = "recordingId"
private const val KEY_STEP = "step"
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
 * A 429 from the cloud transcription API specifically triggers [Result.retry] with
 * backoff instead of failing outright, up to [MAX_ATTEMPTS] tries.
 */
class ProcessRecordingWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as IntelDesApp
        val recordingId = inputData.getString(KEY_RECORDING_ID) ?: return Result.failure()
        val repo = app.repository

        val recording = repo.getRecording(recordingId) ?: return Result.failure()
        repo.markWorking(recordingId)

        return processingMutex.withLock {
            try {
                setProgress(workDataOf(KEY_STEP to ProcessingStep.PREP.name))
                val audioPath = recording.audioFilePath
                    ?: throw ApiException("Berkas audio tidak ditemukan.")
                val audioFile = File(audioPath)
                if (!audioFile.exists()) throw ApiException("Berkas audio tidak ditemukan di perangkat.")

                if (recording.engine == WhisperEngine.ON_DEVICE) {
                    throw ApiException("Whisper on-device belum tersedia di versi ini. Pilih mesin ‘Awan’ di layar rekam atau Pengaturan.")
                }

                setProgress(workDataOf(KEY_STEP to ProcessingStep.TRANSCRIBE.name))
                val whisperKey = app.securePrefs.whisperCloudApiKey
                    ?: throw ApiException("API key transkripsi awan belum diatur. Buka Pengaturan.")
                val transcriptResult = WhisperCloudClient(whisperKey).transcribe(audioFile, recording.audioMimeType)

                setProgress(workDataOf(KEY_STEP to ProcessingStep.DIARIZE.name))
                repo.saveTranscript(recordingId, transcriptResult.speakerCount, transcriptResult.segments)

                setProgress(workDataOf(KEY_STEP to ProcessingStep.SUMMARIZE.name))
                val current = app.settingsStore.settings.first()
                val generator = NotulensiGenerator(app.securePrefs)
                val notulensi = generator.generateNotulensi(
                    provider = current.provider,
                    durationSec = recording.durationSec,
                    meetingTitle = recording.title,
                    segments = transcriptResult.segments,
                    densityDetailed = current.densityDetailed,
                    outputLanguage = current.outputLanguage,
                    economyMode = current.economyMode,
                )

                setProgress(workDataOf(KEY_STEP to ProcessingStep.LINK.name))
                val resolvedProvider = generator.resolve(current.provider, recording.durationSec)
                repo.saveNotulensi(recordingId, resolvedProvider, notulensi)

                if (!current.keepOriginalAudio) audioFile.delete()

                Result.success()
            } catch (e: CancellationException) {
                // The coroutine is already cancelled at this point, so the DB write below needs
                // NonCancellable or it would throw immediately without persisting anything.
                withContext(NonCancellable) { repo.markFailed(recordingId, "Dibatalkan oleh pengguna.") }
                throw e
            } catch (e: ApiException) {
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
            } catch (e: Exception) {
                repo.markFailed(recordingId, e.message ?: "Gagal memproses rekaman.")
                Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Gagal memproses rekaman.")))
            }
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 5
        // Guards the actual network/API work across all ProcessRecordingWorker instances in
        // this process, so recordings transcribe one at a time regardless of how many
        // WorkManager itself schedules concurrently.
        private val processingMutex = Mutex()

        fun enqueue(context: Context, recordingId: String) {
            val request = OneTimeWorkRequestBuilder<ProcessRecordingWorker>()
                .setInputData(workDataOf(KEY_RECORDING_ID to recordingId))
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
