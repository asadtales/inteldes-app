package com.inteldes.app.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

private const val KEY_RECORDING_ID = "recordingId"
private const val KEY_STEP = "step"
const val KEY_ERROR = "error"

/**
 * Real background pipeline: cloud transcription (with diarization) → AI notulensi
 * generation → persistence. Mirrors the Proses screen's step list 1:1 — each
 * [ProcessingStep] is reported via [setProgress] as it actually starts, not simulated.
 */
class ProcessRecordingWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as IntelDesApp
        val recordingId = inputData.getString(KEY_RECORDING_ID) ?: return Result.failure()
        val repo = app.repository

        val recording = repo.getRecording(recordingId) ?: return Result.failure()
        repo.markWorking(recordingId)

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
            )

            setProgress(workDataOf(KEY_STEP to ProcessingStep.LINK.name))
            val resolvedProvider = generator.resolve(current.provider, recording.durationSec)
            repo.saveNotulensi(recordingId, resolvedProvider, notulensi)

            if (!current.keepOriginalAudio) audioFile.delete()

            return Result.success()
        } catch (e: Exception) {
            repo.markFailed(recordingId, e.message ?: "Gagal memproses rekaman.")
            return Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Gagal memproses rekaman.")))
        }
    }

    companion object {
        fun enqueue(context: Context, recordingId: String) {
            val request = OneTimeWorkRequestBuilder<ProcessRecordingWorker>()
                .setInputData(workDataOf(KEY_RECORDING_ID to recordingId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "process_$recordingId",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun observeStep(context: Context, recordingId: String): Flow<ProcessingStep?> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("process_$recordingId").map { infos ->
                val info = infos.firstOrNull() ?: return@map null
                when (info.state) {
                    WorkInfo.State.SUCCEEDED -> ProcessingStep.entries.last()
                    else -> info.progress.getString(KEY_STEP)?.let { name -> ProcessingStep.entries.firstOrNull { it.name == name } }
                }
            }

        fun observeError(context: Context, recordingId: String): Flow<String?> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("process_$recordingId").map { infos ->
                infos.firstOrNull()?.outputData?.getString(KEY_ERROR)
            }
    }
}
