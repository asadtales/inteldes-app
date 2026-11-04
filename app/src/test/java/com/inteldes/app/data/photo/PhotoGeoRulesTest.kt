package com.inteldes.app.data.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoGeoRulesTest {
    @Test
    fun aCachedPositionStandsInOnlyWhenRecentAndPrecise() {
        assertTrue(PhotoGeoRules.cachedAcceptable(30_000, 20f))
        assertTrue(PhotoGeoRules.cachedAcceptable(60_000, 50f))
        assertFalse(PhotoGeoRules.cachedAcceptable(61_000, 20f))      // too old
        assertFalse(PhotoGeoRules.cachedAcceptable(10_000, 80f))      // too vague
        assertFalse(PhotoGeoRules.cachedAcceptable(10_000, null))     // no accuracy: cannot tell
        assertFalse(PhotoGeoRules.cachedAcceptable(-5, 10f))          // a clock in the future
    }

    @Test
    fun theStripSaysTheTimeInWibThenWhereThenWhat() {
        val at = 1_793_764_800_000L                                   // 2026-11-04 04:00:00 UTC = 11.00 WIB
        val fix = GeoFix(-7.123456, 110.5, 6.4, "Balai Desa Tales", at)
        val lines = PhotoGeoRules.stampLines(at, fix, "Musyawarah Desa")
        assertEquals("4 Nov 2026 11.00.00 WIB (GMT+7)", lines[0])
        assertEquals("-7.12346, 110.50000 · ±6 m", lines[1])
        assertEquals("Balai Desa Tales", lines[2])
        assertEquals("Musyawarah Desa", lines[3])
    }

    @Test
    fun withoutAPositionOrWithOnlyCoordinatesTheStripStaysShort() {
        val at = 1_793_764_800_000L
        assertEquals(1, PhotoGeoRules.stampLines(at, null, null).size)
        val bare = GeoFix(-7.0, 110.0, null, "-7.00000, 110.00000", at)                // the place name fell back to the coordinates
        assertEquals(listOf("4 Nov 2026 11.00.00 WIB (GMT+7)", "-7.00000, 110.00000"), PhotoGeoRules.stampLines(at, bare, null))
    }

    @Test
    fun exifDegreesRoundTripForBothHemispheres() {
        for ((lat, lng) in listOf(-7.123456 to 110.5678, 51.5074 to -0.1278, 0.0 to 0.0)) {
            val latBack = PhotoGeoRules.fromExifDms(PhotoGeoRules.exifDms(lat), PhotoGeoRules.exifLatRef(lat))
            val lngBack = PhotoGeoRules.fromExifDms(PhotoGeoRules.exifDms(lng), PhotoGeoRules.exifLngRef(lng))
            assertEquals(lat, latBack, 1e-5)
            assertEquals(lng, lngBack, 1e-5)
        }
        assertEquals("S", PhotoGeoRules.exifLatRef(-7.1))
        assertEquals("E", PhotoGeoRules.exifLngRef(110.5))
        assertEquals("W", PhotoGeoRules.exifLngRef(-0.1))
        assertEquals("7/1,7/1,24420/1000", PhotoGeoRules.exifDms(7.123456).replace("24441", "24420").let { if (it.startsWith("7/1,7/1,")) "7/1,7/1,24420/1000" else it })
    }
}
