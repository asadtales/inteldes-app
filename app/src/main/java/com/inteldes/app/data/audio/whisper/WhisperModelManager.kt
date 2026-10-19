package com.inteldes.app.data.audio.whisper

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class ModelDownloadState {
    data object NotDownloaded : ModelDownloadState()
    data class Downloading(val progress: Float) : ModelDownloadState()
    data object Ready : ModelDownloadState()
    data class Failed(val message: String) : ModelDownloadState()
}

/**
 * Downloads and caches the ggml Whisper model used for fully-offline transcription.
 * Not bundled into the APK — at ~75MB it would bloat every install even for villages
 * that only ever use the cloud engine, so it's fetched once on first on-device use
 * and kept in app-private storage afterwards.
 */
class WhisperModelManager(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<ModelDownloadState>(
        if (modelFile.exists()) ModelDownloadState.Ready else ModelDownloadState.NotDownloaded,
    )
    val state: StateFlow<ModelDownloadState> = _state

    val modelFile: File get() = File(context.filesDir, MODEL_FILE_NAME)

    val isReady: Boolean get() = modelFile.exists() && modelFile.length() > 0

    suspend fun ensureDownloaded() = withContext(Dispatchers.IO) {
        if (isReady) {
            _state.value = ModelDownloadState.Ready
            return@withContext
        }
        // Cheap cleanup for anyone who already downloaded the old "tiny" model before
        // it was swapped for "base" — no longer referenced, just dead weight on disk.
        File(context.filesDir, "ggml-tiny.bin").delete()
        _state.value = ModelDownloadState.Downloading(0f)
        val tmpFile = File(context.filesDir, "$MODEL_FILE_NAME.part")
        try {
            val request = Request.Builder().url(MODEL_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Respons kosong")
                val total = body.contentLength()
                var written = 0L
                body.byteStream().use { input ->
                    tmpFile.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) {
                                _state.value = ModelDownloadState.Downloading(written.toFloat() / total)
                            }
                        }
                    }
                }
            }
            if (!tmpFile.renameTo(modelFile)) throw IOException("Gagal menyimpan berkas model")
            _state.value = ModelDownloadState.Ready
        } catch (e: Exception) {
            tmpFile.delete()
            _state.value = ModelDownloadState.Failed(e.message ?: "Gagal mengunduh model")
            throw e
        }
    }

    companion object {
        // "tiny" was tried first (smaller download) but produced unusable, looping
        // hallucinated text on real recordings — confirmed by the user on an actual
        // village meeting recording, not just quiet test audio. "base" is still a small
        // model by Whisper standards but meaningfully more accurate, especially for a
        // non-English language like Indonesian.
        private const val MODEL_FILE_NAME = "ggml-base.bin"
        private const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin"
    }
}
