package com.inteldes.app.data.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRulesTest {
    @Test
    fun aVideoWithinTheLimitsIsAccepted() {
        assertNull(VideoRules.problem(20L * 1024 * 1024, 90.0))
        assertNull(VideoRules.problem(VideoRules.MAX_BYTES, VideoRules.MAX_SEC))        // exactly at the limits
        assertNull(VideoRules.problem(5_000_000, null))                                  // length unknown: the server measures
    }

    @Test
    fun tooBigTooLongOrEmptyIsRefusedBeforeAnythingIsCopiedOrSent() {
        assertTrue(VideoRules.problem(VideoRules.MAX_BYTES + 1, 30.0)!!.contains("terlalu besar"))
        assertTrue(VideoRules.problem(10_000_000, 121.0)!!.contains("terlalu panjang"))
        assertTrue(VideoRules.problem(0, 10.0)!!.contains("kosong"))
    }

    @Test
    fun containerNamesAreMappedToTheOnesTheServerTakes() {
        assertEquals("video/mp4", VideoRules.normalizeMime("video/mp4"))
        assertEquals("video/mp4", VideoRules.normalizeMime("Video/X-M4V"))
        assertEquals("video/3gpp", VideoRules.normalizeMime("video/3gp"))
        assertEquals("video/webm", VideoRules.normalizeMime("video/webm; codecs=vp9"))
        assertNull(VideoRules.normalizeMime("video/x-matroska"))
        assertNull(VideoRules.normalizeMime("audio/mp4"))
        assertNull(VideoRules.normalizeMime(null))
        assertEquals("mov", VideoRules.extensionOf("video/quicktime"))
        assertEquals("mp4", VideoRules.extensionOf("video/mp4"))
    }
}
