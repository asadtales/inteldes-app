package com.inteldes.app.data.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class WaveformMathTest {
    private val rate = 16_000

    @Test
    fun eachBinHoldsThePeakAndTheRmsOfItsTwentiethOfASecond() {
        val pcm = FloatArray(rate) { 0.5f * sin(2 * PI * 440 * it / rate).toFloat() }                  // 1 s of a half-scale tone
        val bytes = WaveformMath.bins(pcm, rate)
        assertEquals(20 * 2, bytes.size)
        val data = WaveformData(20, bytes)
        assertTrue(data.peak(5) in 120..130)                                                           // about 0.5 * 255
        assertTrue(data.rms(5) in 85..95)                                                              // about 0.35 * 255
        assertEquals(1.0, data.durationSec, 1e-9)
    }

    @Test
    fun aQuietBinIsADotAndALoudOneFillsTheBar() {
        val data = WaveformData(20, byteArrayOf(0, 0, 200.toByte(), 100, 2, 1))
        val ref = WaveformMath.reference(data)
        assertTrue(WaveformMath.height(0, 0, ref) < WaveformMath.DOT)
        assertEquals(1f, WaveformMath.height(200, 100, ref.coerceAtMost(110f)), 1e-6f)
        assertTrue(ref >= 8f)
    }

    @Test
    fun zoomingOutKeepsTheLoudestBinOfEachColumn() {
        val b = ByteArray(20 * 2 * 4)                                                                   // 4 seconds of silence...
        b[2 * 30] = 250.toByte()                                                                        // ...with one loud bin at 1.5 s
        val data = WaveformData(20, b)
        val ref = 100f
        val cols = WaveformMath.columns(data, ref, 0.0, 4.0, 4)                                         // one column per second
        assertTrue(cols[1] > 0.9f)
        assertEquals(0f, cols[0], 1e-6f)
        assertEquals(0f, cols[3], 1e-6f)
        assertEquals(0, WaveformMath.columns(data, ref, 3.0, 3.0, 5).count { it > 0f })
    }
}
