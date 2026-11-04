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
    fun aStoredDraftSurvivesItsTextFormAndDamagedTextIsIgnored() {
        val model = TrimModel(listOf(Region(10.0, 20.5, RegionMode.CUT), Region(40.0, 90.0, RegionMode.KEEP)), RegionMode.CUT)
        val state = TrimDraftState.of(model, 5.0, 8.0, 12.5, 3, null, "Judul", "Musdes", true)
        val stored = StoredDraft(600, 1234L, state)
        val back = TrimDraftCodec.decode(TrimDraftCodec.encode(mapOf("r1" to stored))).getValue("r1")
        assertEquals(stored, back)
        assertEquals(model, back.state.model())
        assertTrue(TrimDraftCodec.decode("{bukan json").isEmpty())
        assertTrue(TrimDraftCodec.decode(null).isEmpty())
    }
}
