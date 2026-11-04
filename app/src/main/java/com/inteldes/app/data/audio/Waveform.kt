package com.inteldes.app.data.audio

import android.content.Context
import com.inteldes.app.data.audio.whisper.AudioDecoder
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The shape of a recording for the trim editor: [BINS_PER_SEC] bins per second, two bytes per bin (peak, then RMS,
 * both 0..255). The same layout the server's `/waveform` answers with, so either source can feed the same drawing.
 */
class WaveformData(val binsPerSec: Int, val bytes: ByteArray) {
    val bins: Int get() = bytes.size / 2
    fun peak(i: Int): Int = bytes[i * 2].toInt() and 0xFF
    fun rms(i: Int): Int = bytes[i * 2 + 1].toInt() and 0xFF
    val durationSec: Double get() = bins.toDouble() / binsPerSec

    companion object { const val BINS_PER_SEC = 20 }
}

/** Arithmetic of the waveform, free of Android so the drawing rules can be tested. */
object WaveformMath {
    /** Peak and RMS of each 1/[binsPerSec] second of [pcm], as bytes. */
    fun bins(pcm: FloatArray, sampleRate: Int, binsPerSec: Int = WaveformData.BINS_PER_SEC): ByteArray {
        val size = (sampleRate / binsPerSec).coerceAtLeast(1)
        val count = (pcm.size + size - 1) / size
        val out = ByteArray(count * 2)
        for (b in 0 until count) {
            val from = b * size
            val to = min(pcm.size, from + size)
            var peak = 0f
            var sum = 0.0
            for (i in from until to) {
                val v = abs(pcm[i])
                if (v > peak) peak = v
                sum += pcm[i].toDouble() * pcm[i]
            }
            val rms = sqrt(sum / (to - from).coerceAtLeast(1)).toFloat()
            out[b * 2] = (peak * 255f).toInt().coerceIn(0, 255).toByte()
            out[b * 2 + 1] = (rms * 255f).toInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    private fun level(peak: Int, rms: Int): Float = maxOf(peak * 0.55f, rms * 1.9f)

    /** The loudness that fills a full bar: the 97th percentile of all bins (at least 8), so one click does not flatten the rest. */
    fun reference(data: WaveformData): Float {
        if (data.bins == 0) return 8f
        val levels = FloatArray(data.bins) { level(data.peak(it), data.rms(it)) }.sortedArray()
        return levels[((levels.size - 1) * 0.97f).toInt()].coerceAtLeast(8f)
    }

    /** Height 0..1 of one bar from a peak/rms pair; values under [DOT] are drawn as a dot. */
    fun height(peak: Int, rms: Int, reference: Float): Float = (level(peak, rms) / reference).coerceAtMost(1f).pow(0.7f)

    const val DOT = 0.05f

    /**
     * Heights for [columns] columns covering [fromSec, toSec]: each column takes the loudest bin it spans, so zooming
     * out never hides a loud moment. Columns outside the recording are 0.
     */
    fun columns(data: WaveformData, reference: Float, fromSec: Double, toSec: Double, columns: Int): FloatArray {
        val out = FloatArray(columns)
        if (columns <= 0 || toSec <= fromSec) return out
        val perColumn = (toSec - fromSec) / columns
        for (c in 0 until columns) {
            val a = ((fromSec + c * perColumn) * data.binsPerSec).toInt()
            val b = ((fromSec + (c + 1) * perColumn) * data.binsPerSec).toInt().coerceAtLeast(a + 1)
            var best = 0f
            for (i in a.coerceAtLeast(0) until min(b, data.bins)) best = maxOf(best, height(data.peak(i), data.rms(i), reference))
            out[c] = best
        }
        return out
    }
}

/** Builds and caches the waveform of a local audio file (works offline). */
object WaveformBuilder {
    private const val CHUNK_SEC = 600

    /** Decodes ten minutes at a time (a whole meeting as PCM would not fit in memory). Call off the main thread. */
    fun build(file: File, durationSec: Int, progress: (Float) -> Unit = {}): WaveformData {
        val rate = SilenceDetector.SAMPLE_RATE
        val out = java.io.ByteArrayOutputStream()
        var start = 0
        while (start < durationSec) {
            val end = min(durationSec, start + CHUNK_SEC)
            val pcm = AudioDecoder.decodeToWhisperPcm(file, start, end)
            val wanted = (end - start) * rate
            val exact = if (pcm.size == wanted) pcm else pcm.copyOf(wanted)            // keep bins aligned to the recording's own clock
            out.write(WaveformMath.bins(exact, rate))
            start = end
            progress(start.toFloat() / durationSec)
        }
        return WaveformData(WaveformData.BINS_PER_SEC, out.toByteArray())
    }

    private fun cacheFile(context: Context, key: String) = File(File(context.filesDir, "waveforms").apply { mkdirs() }, "$key.wf")

    /** The cached waveform for [key] (recording id + audio length), or null. */
    fun cached(context: Context, key: String): WaveformData? =
        cacheFile(context, key).takeIf { it.exists() && it.length() > 0 }?.readBytes()?.let { WaveformData(WaveformData.BINS_PER_SEC, it) }

    fun store(context: Context, key: String, data: WaveformData) { cacheFile(context, key).writeBytes(data.bytes) }
}
