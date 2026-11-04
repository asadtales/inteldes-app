package com.inteldes.app.data.audio

import com.google.gson.Gson

/** Whether a marked area is thrown away ([CUT]) or kept ([KEEP]). */
enum class RegionMode { KEEP, CUT }

/** A marked area, in seconds (fractions allowed). */
data class Region(val start: Double, val end: Double, val mode: RegionMode) {
    val length: Double get() = end - start
}

/** A stretch of the original that ends up in the result. */
data class Span(val start: Double, val end: Double) {
    val length: Double get() = end - start
}

/**
 * The single source of truth of the trim editor, the same idea as the web's: marked areas (each Keep or Cut, never
 * overlapping) plus what happens to the parts nobody marked ([uncovered]). Pure data and arithmetic, no Android.
 */
data class TrimModel(val regions: List<Region> = emptyList(), val uncovered: RegionMode = RegionMode.KEEP) {

    /**
     * Marks [start, end] as [mode]: whatever was marked there before is overwritten (an older area is shortened or
     * split around it), and touching areas of the same mode are merged.
     */
    fun addRegion(start: Double, end: Double, mode: RegionMode, durationSec: Double): TrimModel {
        val s = start.coerceIn(0.0, durationSec)
        val e = end.coerceIn(0.0, durationSec)
        if (e - s < MIN_AREA) return this
        val pieces = mutableListOf<Region>()
        for (r in regions) {
            if (r.end <= s || r.start >= e) { pieces += r; continue }
            if (s - r.start > MIN_AREA) pieces += Region(r.start, s, r.mode)
            if (r.end - e > MIN_AREA) pieces += Region(e, r.end, r.mode)
        }
        pieces += Region(s, e, mode)
        return copy(regions = merge(pieces))
    }

    fun removeRegionAt(index: Int): TrimModel = copy(regions = regions.filterIndexed { i, _ -> i != index })

    fun withUncovered(mode: RegionMode): TrimModel = copy(uncovered = mode)

    /** The parts that end up in the new recording, in playing order (parts shorter than [MIN_SPAN] are dropped). */
    fun kept(durationSec: Double): List<Span> {
        val clamped = regions.map { Region(it.start.coerceIn(0.0, durationSec), it.end.coerceIn(0.0, durationSec), it.mode) }.filter { it.length > 0 }
        val raw: List<Span> = if (uncovered == RegionMode.CUT) {
            clamped.filter { it.mode == RegionMode.KEEP }.map { Span(it.start, it.end) }
        } else {
            val out = mutableListOf<Span>()
            var cursor = 0.0
            for (r in clamped.filter { it.mode == RegionMode.CUT }.sortedBy { it.start }) {
                if (r.start > cursor) out += Span(cursor, r.start)
                cursor = maxOf(cursor, r.end)
            }
            if (cursor < durationSec) out += Span(cursor, durationSec)
            out
        }
        return mergeSpans(raw.sortedBy { it.start }).filter { it.length >= MIN_SPAN }
    }

    fun keptSec(durationSec: Double): Double = kept(durationSec).sumOf { it.length }
    fun removedSec(durationSec: Double): Double = (durationSec - keptSec(durationSec)).coerceAtLeast(0.0)

    /** True when something would be left to save ("keep at least one part"). */
    fun canApply(durationSec: Double): Boolean = kept(durationSec).isNotEmpty()

    /**
     * True when [positionSec] falls in a part that will be removed. [toleranceSec]: a player that was told to jump to
     * the start of a kept part may land a few milliseconds before it; that is still the kept part, not a reason to jump again.
     */
    fun isRemoved(positionSec: Double, durationSec: Double, toleranceSec: Double = 0.0): Boolean =
        kept(durationSec).none { positionSec >= it.start - toleranceSec && positionSec < it.end }

    /** Where to jump so playback skips what will be removed: the start of the next kept part, or null at the end. */
    fun nextKeptStart(positionSec: Double, durationSec: Double): Double? = kept(durationSec).firstOrNull { it.start > positionSec }?.start

    /** Where a mark made at [timestampSec] lands in the result, or null when its moment is removed. */
    fun remap(timestampSec: Double, durationSec: Double): Double? {
        var before = 0.0
        for (s in kept(durationSec)) {
            if (timestampSec >= s.start && timestampSec <= s.end) return before + (timestampSec - s.start)
            before += s.length
        }
        return null
    }

    companion object {
        /** Areas and parts shorter than this make no sense (the server also refuses ranges under 0.2 s). */
        const val MIN_AREA = 0.05
        const val MIN_SPAN = 0.2
        /** Areas of the same mode closer than this are one area. */
        const val TOUCH = 0.05

        private fun merge(pieces: List<Region>): List<Region> {
            val sorted = pieces.filter { it.length >= MIN_AREA }.sortedBy { it.start }
            val out = mutableListOf<Region>()
            for (r in sorted) {
                val last = out.lastOrNull()
                if (last != null && last.mode == r.mode && r.start - last.end <= TOUCH) out[out.lastIndex] = Region(last.start, maxOf(last.end, r.end), r.mode)
                else out += r
            }
            return out
        }

        private fun mergeSpans(sorted: List<Span>): List<Span> {
            val out = mutableListOf<Span>()
            for (s in sorted) {
                val last = out.lastOrNull()
                if (last != null && s.start - last.end <= TOUCH) out[out.lastIndex] = Span(last.start, maxOf(last.end, s.end)) else out += s
            }
            return out
        }
    }
}

/** Undo / redo as a list of snapshots: every change the user makes is one step. */
class UndoStack<T>(initial: T) {
    private val steps = mutableListOf(initial)
    private var at = 0

    val current: T get() = steps[at]
    val canUndo: Boolean get() = at > 0
    val canRedo: Boolean get() = at < steps.lastIndex

    /** A new state after a change. The "redo" branch is dropped. Pushing the same state again changes nothing. */
    fun push(state: T) {
        if (state == steps[at]) return
        while (steps.lastIndex > at) steps.removeAt(steps.lastIndex)
        steps += state
        at = steps.lastIndex
    }

    fun undo(): T { if (canUndo) at--; return current }
    fun redo(): T { if (canRedo) at++; return current }
    fun clear(to: T) { steps.clear(); steps += to; at = 0 }
}

/** A quiet stretch offered for removal, with the user's tick. */
data class SilenceItem(val startSec: Double, val endSec: Double, val use: Boolean = true) {
    val length: Double get() = endSec - startSec
}

object SilenceCuts {
    /** Quiet stretch kept on each side of a removed silence so the words next to it are not clipped. */
    const val PAD = 0.5

    /**
     * Marks every ticked silence as a Cut area, leaving [PAD] seconds of it at each side (none at the very start or
     * end of the recording, where there is no word to protect). A silence too short after the padding is skipped.
     */
    fun apply(model: TrimModel, items: List<SilenceItem>, durationSec: Double, pad: Double = PAD): TrimModel {
        var m = model
        for (it in items.filter { s -> s.use }) {
            val s = if (it.startSec <= 0.01) it.startSec else it.startSec + pad
            val e = if (it.endSec >= durationSec - 0.01) it.endSec else it.endSec - pad
            if (e - s >= pad) m = m.addRegion(s, e, RegionMode.CUT, durationSec)
        }
        return m
    }
}

/**
 * What the server keeps for an unfinished trim (`PUT /v1/recordings/{id}/trim-draft`, schema `TrimDraftState`).
 * Field names and the lower-case modes are the server's; unknown fields are refused there, so nothing else goes in.
 */
data class TrimDraftState(
    val version: Int = 1,
    val regions: List<DraftRegion> = emptyList(),
    val uncovered: String = "keep",
    val sel: DraftSel = DraftSel(0.0, 0.0),
    val pos: Double = 0.0,
    val zoomIdx: Int = 3,
    val analysis: DraftAnalysis? = null,
    val title: String = "",
    val kind: String = "",
    val copyMarks: Boolean = true,
) {
    data class DraftRegion(val start: Double, val end: Double, val mode: String)
    data class DraftSel(val start: Double, val end: Double)
    data class DraftSilence(val startSec: Double, val endSec: Double, val use: Boolean)
    data class DraftAnalysis(val noiseDb: Int, val minSilence: Int, val silences: List<DraftSilence>)

    fun model() = TrimModel(
        regions.map { Region(it.start, it.end, if (it.mode == "cut") RegionMode.CUT else RegionMode.KEEP) },
        if (uncovered == "cut") RegionMode.CUT else RegionMode.KEEP,
    )

    companion object {
        private val gson = Gson()

        fun of(
            model: TrimModel, selStart: Double, selEnd: Double, pos: Double, zoomIdx: Int, analysis: DraftAnalysis?,
            title: String, kind: String, copyMarks: Boolean,
        ) = TrimDraftState(
            regions = model.regions.take(300).map { DraftRegion(it.start, it.end, it.mode.name.lowercase()) },
            uncovered = model.uncovered.name.lowercase(),
            sel = DraftSel(selStart, selEnd), pos = pos, zoomIdx = zoomIdx.coerceIn(0, 10),
            analysis = analysis?.copy(silences = analysis.silences.take(2000)), title = title.take(300), kind = kind.take(100), copyMarks = copyMarks,
        )

        fun toJson(state: TrimDraftState): String = gson.toJson(state)
        fun fromJson(text: String?): TrimDraftState? = runCatching { gson.fromJson(text, TrimDraftState::class.java)?.takeIf { it.version == 1 } }.getOrNull()
    }
}
