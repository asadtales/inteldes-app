package com.inteldes.app.data.audio.whisper

import android.content.Context
import com.inteldes.app.data.model.TranscriptSegment
import com.inteldes.app.data.network.ApiException
import com.inteldes.app.data.network.TranscriptResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fully-offline counterpart to `WhisperCloudClient` — same [TranscriptResult] shape, so
 * `ProcessRecordingWorker` doesn't need to know which engine actually ran. Runs a local
 * ggml Whisper model via whisper.cpp/JNI; no audio ever leaves the device.
 *
 * Unlike the cloud path (Deepgram), plain whisper.cpp does no speaker diarization — every
 * segment comes back as a single "Pembicara 1". Making this accurate would need a
 * separate on-device diarization model, which is out of scope for now.
 */
class OnDeviceWhisperClient(private val context: Context) {
    private val modelManager = WhisperModelManager(context)

    /**
     * @param rangeStartSec/rangeEndSec Optional sub-range of [audioFile] to transcribe
     * instead of the whole recording — decoding skips straight to [rangeStartSec], so a
     * short range near the end of a long recording is genuinely cheap, not just truncated
     * after the fact. Resulting segment timestamps are offset back onto the *original*
     * recording's timeline (not the trimmed clip's), so they still line up with marks,
     * the seek bar, and anything transcribed from other ranges.
     */
    suspend fun transcribe(
        audioFile: File,
        languageCode: String = "id",
        rangeStartSec: Int? = null,
        rangeEndSec: Int? = null,
    ): TranscriptResult =
        withContext(Dispatchers.Default) {
            if (!modelManager.isReady) {
                runCatching { modelManager.ensureDownloaded() }
                    .onFailure { throw ApiException("Gagal mengunduh model Whisper on-device: ${it.message}") }
            }

            val pcm = try {
                AudioDecoder.decodeToWhisperPcm(audioFile, rangeStartSec, rangeEndSec)
            } catch (e: Exception) {
                throw ApiException("Gagal membaca audio untuk transkrip on-device: ${e.message}")
            }
            if (pcm.isEmpty()) throw ApiException("Audio kosong, tidak ada yang bisa ditranskrip.")

            val offsetSec = rangeStartSec ?: 0
            val ctx = WhisperContext.createContextFromFile(modelManager.modelFile.absolutePath)
            try {
                val segments = ctx.transcribeData(pcm, languageCode)
                val transcriptSegments = segments
                    .filter { it.text.isNotBlank() }
                    .map { seg ->
                        TranscriptSegment(
                            speaker = "Pembicara 1",
                            timestampSec = (seg.startMs / 1000).toInt() + offsetSec,
                            text = seg.text,
                        )
                    }
                if (transcriptSegments.isEmpty()) {
                    throw ApiException("Whisper on-device tidak mengembalikan hasil (audio mungkin kosong atau terlalu pendek).")
                }
                TranscriptResult(speakerCount = 1, segments = transcriptSegments)
            } finally {
                ctx.release()
            }
        }
}
