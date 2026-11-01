package com.inteldes.app.data.audio

import com.inteldes.app.data.audio.whisper.AudioDecoder
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.sqrt

/** A quiet stretch, in seconds from the start (fractions kept so the speech ranges can be padded exactly). */
data class Silence(val startSec: Double, val endSec: Double)

/**
 * Finds the quiet parts of a recording on the phone (works offline) and turns them into the speech ranges
 * the trim editor can use. Same rules as the web's silence detection: a stretch is silent when it stays below
 * the noise threshold for at least the minimum length; speech is the opposite, padded a little so word edges
 * survive, with anything under a second dropped.
 */
object SilenceDetector {
    const val SAMPLE_RATE = 16_000
    private const val WINDOW_SEC = 0.05
    private const val CHUNK_SEC = 600

    /** Loudness (dBFS) of each 50 ms window of [pcm]; a trailing partial window is measured as it is. */
    fun windowDb(pcm: FloatArray, sampleRate: Int = SAMPLE_RATE): FloatArray {
        val size = (sampleRate * WINDOW_SEC).toInt().coerceAtLeast(1)
        val count = (pcm.size + size - 1) / size
        return FloatArray(count) { w ->
            val from = w * size
            val to = minOf(pcm.size, from + size)
            var sum = 0.0
            for (i in from until to) sum += pcm[i].toDouble() * pcm[i]
            val rms = sqrt(sum / (to - from).coerceAtLeast(1))
            (20 * log10(rms.coerceAtLeast(1e-9))).toFloat()
        }
    }

    /** Runs of windows below [noiseDb] that last at least [minSilenceSec]. [windowSec] is the length of one window. */
    fun silences(db: FloatArray, noiseDb: Int, minSilenceSec: Double, windowSec: Double = WINDOW_SEC): List<Silence> {
        val out = mutableListOf<Silence>()
        var runStart = -1
        fun close(endIndex: Int) {
            if (runStart < 0) return
            val s = runStart * windowSec
            val e = endIndex * windowSec
            if (e - s >= minSilenceSec) out += Silence(s, e)
            runStart = -1
        }
        db.forEachIndexed { i, v -> if (v < noiseDb) { if (runStart < 0) runStart = i } else close(i) }
        close(db.size)
        return out
    }

    /** The opposite of the silences, padded by [padSec], dropping speech under [minSpeechSec], merged when the padding meets. */
    fun speechRanges(silences: List<Silence>, durationSec: Int, padSec: Double, minSpeechSec: Double = 1.0): List<TimeRange> {
        val raw = mutableListOf<Pair<Double, Double>>()
        var cursor = 0.0
        for (s in silences.sortedBy { it.startSec }) {
            if (s.startSec - cursor > 0) raw += cursor to s.startSec
            cursor = maxOf(cursor, s.endSec)
        }
        if (durationSec - cursor > 0) raw += cursor to durationSec.toDouble()
        val padded = raw
            .filter { it.second - it.first >= minSpeechSec }
            .map { (s, e) ->
                TimeRange(floor(maxOf(0.0, s - padSec)).toInt(), ceil(minOf(durationSec.toDouble(), e + padSec)).toInt())
            }
        return TrimPlan.normalize(padded, durationSec)
    }

    /** Presets of the editor, same numbers as the web: clean room, normal, noisy. */
    enum class Sensitivity(val label: String, val noiseDb: Int) {
        CLEAN("Bersih", -42), NORMAL("Normal", -35), NOISY("Berisik", -28)
    }

    /**
     * Reads [file] ten minutes at a time (a whole meeting as PCM would not fit in memory) and returns the speech
     * ranges. Slow-ish for long recordings; call off the main thread.
     */
    fun analyze(file: File, durationSec: Int, noiseDb: Int, minSilenceSec: Int, padSec: Double = 0.5): List<TimeRange> {
        val db = ArrayList<Float>((durationSec / WINDOW_SEC).toInt() + 1)
        var start = 0
        while (start < durationSec) {
            val end = minOf(durationSec, start + CHUNK_SEC)
            val pcm = AudioDecoder.decodeToWhisperPcm(file, start, end)
            // Pad or cut to exactly the chunk so window boundaries stay aligned to the recording's own clock.
            val wanted = (end - start) * SAMPLE_RATE
            val exact = if (pcm.size == wanted) pcm else pcm.copyOf(wanted)
            windowDb(exact).forEach { db += it }
            start = end
        }
        return speechRanges(silences(db.toFloatArray(), noiseDb, minSilenceSec.toDouble()), durationSec, padSec)
    }
}
