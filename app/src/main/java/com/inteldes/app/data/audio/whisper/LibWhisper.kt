package com.inteldes.app.data.audio.whisper

import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

private const val LOG_TAG = "LibWhisper"

// Ordinary meeting-speech Indonesian, used only as a decoding nudge (see fullTranscribe).
private const val INITIAL_PROMPT = "Ini adalah rekaman rapat warga desa dalam Bahasa Indonesia."

/** One loaded ggml model in native memory. Not thread-safe — whisper.cpp requires
 * single-threaded access per context, enforced here with a dedicated executor. */
class WhisperContext private constructor(private var ptr: Long) {
    private val scope: CoroutineScope = CoroutineScope(
        Executors.newSingleThreadExecutor().asCoroutineDispatcher(),
    )

    /** @param language Whisper language code of the *spoken* audio, e.g. "id" for Indonesian. */
    suspend fun transcribeData(data: FloatArray, language: String): List<WhisperSegment> = withContext(scope.coroutineContext) {
        require(ptr != 0L)
        val numThreads = WhisperCpuConfig.preferredThreadCount
        Log.d(LOG_TAG, "Transcribing ${data.size} samples with $numThreads threads, language=$language")
        WhisperLib.fullTranscribe(ptr, numThreads, language, INITIAL_PROMPT, data)
        val count = WhisperLib.getTextSegmentCount(ptr)
        (0 until count).map { i ->
            WhisperSegment(
                text = WhisperLib.getTextSegment(ptr, i).trim(),
                // whisper.cpp reports timestamps in centiseconds (1/100s).
                startMs = WhisperLib.getTextSegmentT0(ptr, i) * 10,
                endMs = WhisperLib.getTextSegmentT1(ptr, i) * 10,
            )
        }
    }

    suspend fun release() = withContext(scope.coroutineContext) {
        if (ptr != 0L) {
            WhisperLib.freeContext(ptr)
            ptr = 0
        }
    }

    protected fun finalize() {
        runBlocking { release() }
    }

    companion object {
        fun createContextFromFile(filePath: String): WhisperContext {
            val ptr = WhisperLib.initContext(filePath)
            if (ptr == 0L) throw RuntimeException("Couldn't load Whisper model at $filePath")
            return WhisperContext(ptr)
        }

        fun getSystemInfo(): String = WhisperLib.getSystemInfo()
    }
}

data class WhisperSegment(val text: String, val startMs: Long, val endMs: Long)

private class WhisperLib {
    companion object {
        init {
            Log.d(LOG_TAG, "Primary ABI: ${Build.SUPPORTED_ABIS[0]}")
            var loadVfpv4 = false
            var loadV8fp16 = false
            if (isArmEabiV7a()) {
                cpuInfo()?.let {
                    if (it.contains("vfpv4")) loadVfpv4 = true
                }
            } else if (isArmEabiV8a()) {
                cpuInfo()?.let {
                    if (it.contains("fphp")) loadV8fp16 = true
                }
            }
            when {
                loadVfpv4 -> System.loadLibrary("whisper_vfpv4")
                loadV8fp16 -> System.loadLibrary("whisper_v8fp16_va")
                else -> System.loadLibrary("whisper")
            }
        }

        external fun initContext(modelPath: String): Long
        external fun freeContext(contextPtr: Long)
        external fun fullTranscribe(contextPtr: Long, numThreads: Int, language: String, initialPrompt: String, audioData: FloatArray)
        external fun getTextSegmentCount(contextPtr: Long): Int
        external fun getTextSegment(contextPtr: Long, index: Int): String
        external fun getTextSegmentT0(contextPtr: Long, index: Int): Long
        external fun getTextSegmentT1(contextPtr: Long, index: Int): Long
        external fun getSystemInfo(): String
    }
}

private fun isArmEabiV7a() = Build.SUPPORTED_ABIS[0] == "armeabi-v7a"
private fun isArmEabiV8a() = Build.SUPPORTED_ABIS[0] == "arm64-v8a"

private fun cpuInfo(): String? = try {
    File("/proc/cpuinfo").inputStream().bufferedReader().use { it.readText() }
} catch (e: Exception) {
    Log.w(LOG_TAG, "Couldn't read /proc/cpuinfo", e)
    null
}
