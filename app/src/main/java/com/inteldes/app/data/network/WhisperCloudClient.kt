package com.inteldes.app.data.network

import com.inteldes.app.data.audio.AudioTrimmer
import com.inteldes.app.data.model.TranscriptSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

data class TranscriptResult(val speakerCount: Int, val segments: List<TranscriptSegment>)

/**
 * Real cloud transcription — Deepgram's `/v1/listen`, requested against their hosted
 * `whisper-large` model (Deepgram runs OpenAI's actual Whisper weights) with
 * `diarize=true`. Chosen over calling OpenAI's own Whisper endpoint directly because
 * OpenAI's `audio/transcriptions` API has no speaker-diarization option, and the
 * design's transcript view is built entirely around per-speaker segments. Diarized
 * speakers come back as anonymous "Pembicara N" — matching the prototype's own
 * "ketuk paragraf untuk memperbaiki nama pembicara" affordance for renaming them.
 */
class WhisperCloudClient(private val apiKey: String) {

    /**
     * @param rangeStartSec/rangeEndSec Optional sub-range to transcribe instead of the
     * whole recording — trimmed into its own small file first so only that span is
     * actually uploaded (and billed) to Deepgram, not the full original.
     */
    suspend fun transcribe(
        audioFile: File,
        mimeType: String = "audio/mp4",
        languageCode: String = "id",
        rangeStartSec: Int? = null,
        rangeEndSec: Int? = null,
    ): TranscriptResult =
        withContext(Dispatchers.IO) {
            val trimmedFile = if (rangeStartSec != null && rangeEndSec != null) {
                File(audioFile.parentFile, "${audioFile.nameWithoutExtension}_trim_${rangeStartSec}_${rangeEndSec}.m4a").also {
                    // A trim failure (e.g. unsupported format) isn't a transient network blip —
                    // surface it as a permanent ApiException so the worker doesn't retry it 5x.
                    try {
                        AudioTrimmer.trim(audioFile, it, rangeStartSec, rangeEndSec)
                    } catch (e: java.io.IOException) {
                        throw ApiException(e.message ?: "Gagal memotong audio untuk transkrip sebagian.")
                    }
                }
            } else {
                null
            }
            val uploadFile = trimmedFile ?: audioFile
            val offsetSec = rangeStartSec ?: 0
            try {
                val url = "https://api.deepgram.com/v1/listen".toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("model", "whisper-large")
                    .addQueryParameter("language", languageCode)
                    .addQueryParameter("diarize", "true")
                    .addQueryParameter("punctuate", "true")
                    .addQueryParameter("utterances", "true")
                    .addQueryParameter("smart_format", "true")
                    .build()

                val body = uploadFile.asRequestBody(mimeType.toMediaType())
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Token $apiKey")
                    .post(body)
                    .build()

                HttpClient.client.newCall(request).await().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw ApiException("Transkripsi awan gagal (${resp.code}): ${raw.take(300)}", resp.code)
                    }
                    val parsed = HttpClient.gson.fromJson(raw, DeepgramResponse::class.java)
                    val utterances = parsed.results?.utterances.orEmpty()
                    if (utterances.isEmpty()) {
                        throw ApiException("Transkripsi awan tidak mengembalikan hasil (audio mungkin kosong atau terlalu pendek).")
                    }
                    val speakerIndices = utterances.map { it.speaker ?: 0 }.distinct().sorted()
                    val speakerNames = speakerIndices.withIndex().associate { (i, idx) -> idx to "Pembicara ${i + 1}" }
                    val segments = utterances.map { u ->
                        TranscriptSegment(
                            speaker = speakerNames[u.speaker ?: 0] ?: "Pembicara 1",
                            timestampSec = (u.start ?: 0.0).toInt() + offsetSec,
                            text = u.transcript.orEmpty().trim(),
                        )
                    }.filter { it.text.isNotEmpty() }
                    TranscriptResult(speakerCount = speakerIndices.size.coerceAtLeast(1), segments = segments)
                }
            } finally {
                trimmedFile?.delete()
            }
        }
}

private data class DeepgramResponse(val results: DeepgramResults?)
private data class DeepgramResults(val utterances: List<DeepgramUtterance>?)
private data class DeepgramUtterance(val speaker: Int?, val start: Double?, val end: Double?, val transcript: String?)
