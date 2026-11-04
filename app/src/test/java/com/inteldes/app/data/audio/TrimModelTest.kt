package com.inteldes.app.data.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrimModelTest {
    private val d = 230.0
    private fun keep(a: Double, b: Double) = Region(a, b, RegionMode.KEEP)
    private fun cut(a: Double, b: Double) = Region(a, b, RegionMode.CUT)

    @Test
    fun theOwnersFiveAreasLeaveOnlyTheTwoKeptStretches() {
        var m = TrimModel()
        m = m.addRegion(0.0, 51.0, RegionMode.CUT, d)
            .addRegion(51.0, 80.0, RegionMode.KEEP, d)
            .addRegion(80.0, 121.0, RegionMode.CUT, d)
            .addRegion(121.0, 180.0, RegionMode.KEEP, d)
            .addRegion(180.0, 230.0, RegionMode.CUT, d)
        assertEquals(listOf(Span(51.0, 80.0), Span(121.0, 180.0)), m.kept(d))
        assertEquals(88.0, m.keptSec(d), 1e-9)
        assertEquals(142.0, m.removedSec(d), 1e-9)
        // the same result either way the unmarked rest is treated, because everything is marked
        assertEquals(m.kept(d), m.withUncovered(RegionMode.CUT).kept(d))
    }

    @Test
    fun anUnmarkedRestFollowsTheChosenDefault() {
        val m = TrimModel(listOf(cut(10.0, 20.0), keep(100.0, 120.0)))
        assertEquals(listOf(Span(0.0, 10.0), Span(20.0, 230.0)), m.kept(d))                       // rest kept: only Cut counts
        assertEquals(listOf(Span(100.0, 120.0)), m.withUncovered(RegionMode.CUT).kept(d))          // rest cut: only Keep counts
    }

    @Test
    fun aNewAreaOverwritesWhatWasMarkedUnderIt() {
        val base = TrimModel(listOf(cut(10.0, 50.0)))
        assertEquals(listOf(cut(10.0, 20.0), keep(20.0, 30.0), cut(30.0, 50.0)), base.addRegion(20.0, 30.0, RegionMode.KEEP, d).regions)      // middle splits it
        assertEquals(listOf(keep(0.0, 20.0), cut(20.0, 50.0)), base.addRegion(0.0, 20.0, RegionMode.KEEP, d).regions)                                      // overlaps the left end
        assertEquals(listOf(keep(5.0, 60.0)), base.addRegion(5.0, 60.0, RegionMode.KEEP, d).regions)                                             // covers it fully
        assertEquals(listOf(cut(10.0, 40.0), keep(40.0, 60.0)), base.addRegion(40.0, 60.0, RegionMode.KEEP, d).regions)                       // overlaps the right end
    }

    @Test
    fun touchingAreasOfTheSameModeBecomeOne() {
        val m = TrimModel().addRegion(10.0, 20.0, RegionMode.CUT, d).addRegion(20.02, 30.0, RegionMode.CUT, d).addRegion(30.0, 40.0, RegionMode.KEEP, d)
        assertEquals(listOf(cut(10.0, 30.0), keep(30.0, 40.0)), m.regions)
    }

    @Test
    fun anAreaCannotLeaveTheRecordingOrBeEmpty() {
        val m = TrimModel().addRegion(-5.0, 10.0, RegionMode.CUT, d).addRegion(220.0, 999.0, RegionMode.CUT, d).addRegion(50.0, 50.01, RegionMode.CUT, d)
        assertEquals(listOf(cut(0.0, 10.0), cut(220.0, 230.0)), m.regions)
    }

    @Test
    fun cuttingEverythingIsRefused() {
        val m = TrimModel().addRegion(0.0, 230.0, RegionMode.CUT, d)
        assertFalse(m.canApply(d))
        assertTrue(TrimModel().canApply(d))
        assertFalse(TrimModel().withUncovered(RegionMode.CUT).canApply(d))                         // nothing marked Keep
    }

    @Test
    fun fractionsOfASecondAreKeptAndPartsUnderTheServersMinimumAreDropped() {
        val m = TrimModel(listOf(cut(1.5, 3.25), cut(3.35, 229.9)))
        assertEquals(listOf(Span(0.0, 1.5), Span(3.25, 3.35)).filter { it.length >= 0.2 }, m.kept(d).filter { it.start < 4.0 })
        assertTrue(m.kept(d).none { it.length < TrimModel.MIN_SPAN })
    }

    @Test
    fun playbackSkipsWhatWillBeRemoved() {
        val m = TrimModel(listOf(cut(10.0, 20.0), cut(40.0, 60.0)))
        assertFalse(m.isRemoved(5.0, d))
        assertTrue(m.isRemoved(15.0, d))
        assertEquals(20.0, m.nextKeptStart(15.0, d)!!, 1e-9)
        assertEquals(60.0, m.nextKeptStart(45.0, d)!!, 1e-9)
        assertNull(m.nextKeptStart(100.0, d))
        // a player that lands 9 ms before the kept part it was sent to is not told to jump again
        assertTrue(m.isRemoved(19.991, d))
        assertFalse(m.isRemoved(19.991, d, toleranceSec = 0.15))
        assertTrue(m.isRemoved(19.5, d, toleranceSec = 0.15))
    }

    @Test
    fun marksFollowTheirMomentOrDisappearWithIt() {
        val m = TrimModel(listOf(cut(10.0, 20.0)))
        assertEquals(5.0, m.remap(5.0, d)!!, 1e-9)
        assertNull(m.remap(15.0, d))
        assertEquals(10.0 + 5.0, m.remap(25.0, d)!!, 1e-9)
    }

    @Test
    fun undoAndRedoStepThroughEveryChange() {
        val s = UndoStack(TrimModel())
        s.push(TrimModel().addRegion(0.0, 10.0, RegionMode.CUT, d))
        s.push(s.current.addRegion(20.0, 30.0, RegionMode.CUT, d))
        s.push(s.current)                                                                           // same state: no step
        assertEquals(2, s.current.regions.size)
        assertEquals(1, s.undo().regions.size)
        assertEquals(0, s.undo().regions.size)
        assertFalse(s.canUndo)
        assertEquals(1, s.redo().regions.size)
        s.push(TrimModel().addRegion(50.0, 60.0, RegionMode.KEEP, d))                               // a new change drops the redo branch
        assertFalse(s.canRedo)
    }

    @Test
    fun ticketedSilencesBecomeCutsWithHalfASecondKeptAtEachSide() {
        val items = listOf(SilenceItem(10.0, 20.0), SilenceItem(30.0, 30.8), SilenceItem(40.0, 50.0, use = false), SilenceItem(0.0, 5.0), SilenceItem(225.0, 230.0))
        val m = SilenceCuts.apply(TrimModel(), items, d)
        assertEquals(
            listOf(cut(0.0, 4.5), cut(10.5, 19.5), cut(225.5, 230.0)),
            m.regions,
        )                                                                                           // 0.8 s silence is too short after padding; the unticked one is untouched
    }

    @Test
    fun theServerDraftKeepsEverythingAndSpeaksLowerCase() {
        val model = TrimModel(listOf(cut(1.5, 9.0), keep(20.0, 40.25)), RegionMode.CUT)
        val state = TrimDraftState.of(
            model, 20.0, 40.25, 21.5, 4, TrimDraftState.DraftAnalysis(-35, 3, listOf(TrimDraftState.DraftSilence(5.0, 9.0, true))),
            "Judul", "Musdes", true,
        )
        val json = TrimDraftState.toJson(state)
        assertTrue(json.contains("\"mode\":\"cut\"") && json.contains("\"uncovered\":\"cut\""))
        val back = TrimDraftState.fromJson(json)!!
        assertEquals(model, back.model())
        assertEquals(4, back.zoomIdx)
        assertEquals(-35, back.analysis!!.noiseDb)
        assertNull(TrimDraftState.fromJson("{\"version\":2}"))
        assertNull(TrimDraftState.fromJson("bukan json"))
    }
}
