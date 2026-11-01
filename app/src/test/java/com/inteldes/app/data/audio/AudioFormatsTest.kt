package com.inteldes.app.data.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFormatsTest {
    @Test
    fun aacAndAmrAreCutIntoM4aAndOpusAndVorbisIntoWebm() {
        assertEquals("m4a", AudioFormats.onDeviceOutput("audio/mp4a-latm")!!.extension)
        assertEquals(AudioFormats.Muxer.MPEG4, AudioFormats.onDeviceOutput("audio/3gpp")!!.muxer)
        val webm = AudioFormats.onDeviceOutput("audio/opus")!!
        assertEquals(AudioFormats.Muxer.WEBM, webm.muxer)
        assertEquals("audio/webm", webm.mimeType)
        assertNotNull(AudioFormats.onDeviceOutput("audio/vorbis"))
    }

    @Test
    fun mp3WavAndFlacCannotBeCutOnThePhone() {
        listOf("audio/mpeg", "audio/raw", "audio/flac", "audio/g711-alaw").forEach { assertNull(it, AudioFormats.onDeviceOutput(it)) }
    }

    @Test
    fun containerNamesAreMappedToTheOnesTheServerKnows() {
        assertEquals("audio/mp4", AudioFormats.normalizeContainerMime("audio/x-m4a"))
        assertEquals("audio/mpeg", AudioFormats.normalizeContainerMime("audio/mp3"))
        assertEquals("audio/wav", AudioFormats.normalizeContainerMime("audio/x-wav"))
        assertEquals("audio/webm", AudioFormats.normalizeContainerMime("Audio/WebM; codecs=opus"))
        assertEquals("image/jpeg", AudioFormats.normalizeContainerMime("image/jpeg"))
    }

    @Test
    fun theServerCutsMp3AndWavButNotFlacOrAmr() {
        assertTrue(AudioFormats.serverCanCut("audio/mpeg"))
        assertTrue(AudioFormats.serverCanCut("audio/x-wav"))
        assertTrue(AudioFormats.serverCanCut("audio/webm;codecs=opus"))
        assertFalse(AudioFormats.serverCanCut("audio/flac"))
        assertFalse(AudioFormats.serverCanCut("audio/3gpp"))
    }

    @Test
    fun aDraftSurvivesItsTextFormAndIgnoresDamagedText() {
        val d = TrimDraft.of(600, TrimMode.KEEP, listOf(TimeRange(10, 20), TimeRange(40, 90)), "Judul saya", "standard", "CLOUD", false, 1, now = 5)
        val back = TrimDraftCodec.decode(TrimDraftCodec.encode(mapOf("r1" to d))).getValue("r1")
        assertEquals(d, back)
        assertEquals(listOf(TimeRange(10, 20), TimeRange(40, 90)), back.timeRanges())
        assertEquals(TrimMode.KEEP, back.trimMode())
        assertTrue(TrimDraftCodec.decode("{bukan json").isEmpty())
        assertTrue(TrimDraftCodec.decode(null).isEmpty())
    }
}
