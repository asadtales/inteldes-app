package com.inteldes.app.data.audio

import com.inteldes.app.data.repository.ContextBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrimPlanTest {
    private fun r(a: Int, b: Int) = TimeRange(a, b)

    @Test
    fun normalizeSortsMergesAndClampsRanges() {
        val out = TrimPlan.normalize(listOf(r(50, 70), r(10, 30), r(25, 40), r(40, 45), r(-5, 3), r(95, 140), r(60, 60)), 100)
        assertEquals(listOf(r(0, 3), r(10, 45), r(50, 70), r(95, 100)), out)
    }

    @Test
    fun removeModeKeepsEverythingAroundTheThrownAwayParts() {
        val keep = TrimPlan.keepRanges(TrimMode.REMOVE, listOf(r(10, 20), r(40, 60)), 100)
        assertEquals(listOf(r(0, 10), r(20, 40), r(60, 100)), keep)
        assertEquals(70, TrimPlan.totalSec(keep))
    }

    @Test
    fun removeModeAtTheEdgesLeavesNoEmptyPieces() {
        assertEquals(listOf(r(10, 90)), TrimPlan.keepRanges(TrimMode.REMOVE, listOf(r(0, 10), r(90, 100)), 100))
        assertTrue(TrimPlan.keepRanges(TrimMode.REMOVE, listOf(r(0, 100)), 100).isEmpty())
    }

    @Test
    fun keepModeKeepsOnlyThePickedParts() {
        assertEquals(listOf(r(5, 15), r(30, 40)), TrimPlan.keepRanges(TrimMode.KEEP, listOf(r(30, 40), r(5, 15)), 100))
    }

    @Test
    fun togglingASegmentAddsItAndTakesItBackOut() {
        val seg = r(20, 40)
        val added = TrimPlan.toggle(listOf(r(0, 10)), seg, 100)
        assertEquals(listOf(r(0, 10), r(20, 40)), added)
        assertTrue(TrimPlan.covers(added, seg))
        assertEquals(listOf(r(0, 10)), TrimPlan.toggle(added, seg, 100))
    }

    @Test
    fun togglingInsideABiggerPickSplitsIt() {
        assertEquals(listOf(r(0, 20), r(40, 100)), TrimPlan.toggle(listOf(r(0, 100)), r(20, 40), 100))
    }

    @Test
    fun marksFollowTheirMomentAndDisappearWithTheCutPart() {
        val keep = listOf(r(0, 10), r(20, 40), r(60, 100))
        assertEquals(5, TrimPlan.remapMark(5, keep))
        assertNull(TrimPlan.remapMark(15, keep))
        assertEquals(10 + 5, TrimPlan.remapMark(25, keep))
        assertEquals(10 + 20 + 10, TrimPlan.remapMark(70, keep))
    }

    @Test
    fun segmentsRunBetweenMarksAndIgnoreEdgesAndDuplicates() {
        assertEquals(listOf(r(0, 30), r(30, 70), r(70, 100)), TrimPlan.segmentsBetween(listOf(70, 30, 30, 0, 100, 250), 100))
        assertEquals(listOf(r(0, 100)), TrimPlan.segmentsBetween(emptyList(), 100))
        assertFalse(TrimPlan.segmentsBetween(listOf(10), 0).isNotEmpty())
    }

    @Test
    fun aContextSectionComesOutWithoutTouchingTheOthersOrHandWrittenText() {
        val a = "## Rapat A — 1 Januari 2026\nisi a"
        val b = "## Rapat B — 2 Januari 2026\nisi b\n\n### Ringkasan\nx"
        val text = ContextBuilder.join(listOf(a, b)) + "\n\n---\n\ncatatan saya sendiri"
        val out = ContextBuilder.withoutSection(text, "Rapat A")
        assertEquals(ContextBuilder.join(listOf(b)) + "\n\n---\n\ncatatan saya sendiri", out)
        assertEquals(text, ContextBuilder.withoutSection(text, "Rapat tidak ada"))
    }

    @Test
    fun theDefaultTitleNamesTheStretchOrCountsThem() {
        assertEquals("Rapat (potongan 03:20–05:10)", TrimPlan.defaultTitle("Rapat", listOf(r(200, 310))))
        assertEquals("Rapat (potongan 1:00:00–1:02:05)", TrimPlan.defaultTitle("Rapat", listOf(r(3600, 3725))))
        assertEquals("Rapat (potongan 3 rentang)", TrimPlan.defaultTitle("Rapat", listOf(r(0, 10), r(20, 30), r(40, 50))))
    }
}
