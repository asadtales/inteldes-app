package com.inteldes.app.data.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SilenceDetectorTest {
    private val rate = SilenceDetector.SAMPLE_RATE

    private fun tone(sec: Int) = FloatArray(rate * sec) { 0.3f * sin(2 * PI * 440 * it / rate).toFloat() }
    private fun quiet(sec: Int) = FloatArray(rate * sec)

    private fun speechOf(pcm: FloatArray, noiseDb: Int = -35, minSilence: Double = 3.0, pad: Double = 0.5): List<TimeRange> {
        val sil = SilenceDetector.silences(SilenceDetector.windowDb(pcm), noiseDb, minSilence)
        return SilenceDetector.speechRanges(sil, pcm.size / rate, pad)
    }

    @Test
    fun toneSilenceToneGivesTwoSpeechRangesLikeTheServer() {
        val pcm = tone(3) + quiet(5) + tone(3)
        assertEquals(listOf(TimeRange(0, 4), TimeRange(7, 11)), speechOf(pcm))
    }

    @Test
    fun aPauseShorterThanTheMinimumIsKept() {
        val pcm = tone(3) + quiet(2) + tone(3)
        assertEquals(listOf(TimeRange(0, 8)), speechOf(pcm))
    }

    @Test
    fun allQuietGivesNoSpeech() {
        assertTrue(speechOf(quiet(10)).isEmpty())
    }

    @Test
    fun silenceAtTheEndIsDroppedAndNoisyRoomsNeedAHigherThreshold() {
        val noise = FloatArray(rate * 5) { 0.02f * sin(2 * PI * 120 * it / rate).toFloat() }     // about -37 dB
        val pcm = tone(3) + noise
        assertEquals(listOf(TimeRange(0, 8)), speechOf(pcm, noiseDb = -42))                       // counted as sound
        assertEquals(listOf(TimeRange(0, 4)), speechOf(pcm, noiseDb = -28))                       // counted as silence
    }
}
