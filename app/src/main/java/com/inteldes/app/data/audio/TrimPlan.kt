package com.inteldes.app.data.audio

/** A stretch of a recording, in whole seconds: [start, end). */
data class TimeRange(val start: Int, val end: Int) {
    val length: Int get() = end - start
}

/** What the picked ranges mean: [REMOVE] throws them away and keeps the rest, [KEEP] keeps only them. */
enum class TrimMode { REMOVE, KEEP }

/**
 * The arithmetic behind the trim editor, kept free of Android so it can be tested: ranges are always held
 * sorted, inside the recording and never overlapping, so what the screen shows is what gets cut.
 */
object TrimPlan {

    /** Clamps to [0, duration], drops empty ranges, sorts, and merges overlapping or touching ones. */
    fun normalize(ranges: List<TimeRange>, durationSec: Int): List<TimeRange> {
        val clamped = ranges
            .map { TimeRange(it.start.coerceIn(0, durationSec), it.end.coerceIn(0, durationSec)) }
            .filter { it.end > it.start }
            .sortedBy { it.start }
        val merged = mutableListOf<TimeRange>()
        for (r in clamped) {
            val last = merged.lastOrNull()
            if (last != null && r.start <= last.end) merged[merged.lastIndex] = TimeRange(last.start, maxOf(last.end, r.end))
            else merged += r
        }
        return merged
    }

    fun add(ranges: List<TimeRange>, range: TimeRange, durationSec: Int): List<TimeRange> =
        normalize(ranges + range, durationSec)

    /** [ranges] with the stretch [cut] taken out of them (a range straddling it is split in two). */
    fun subtract(ranges: List<TimeRange>, cut: TimeRange): List<TimeRange> = ranges.flatMap { r ->
        if (cut.end <= r.start || cut.start >= r.end) listOf(r)
        else listOfNotNull(
            TimeRange(r.start, cut.start).takeIf { it.length > 0 },
            TimeRange(cut.end, r.end).takeIf { it.length > 0 },
        )
    }

    /** True when every second of [range] is already inside [ranges]. */
    fun covers(ranges: List<TimeRange>, range: TimeRange): Boolean = ranges.any { it.start <= range.start && it.end >= range.end }

    /** Tap on a segment: unselects it when already selected, otherwise adds it. */
    fun toggle(ranges: List<TimeRange>, range: TimeRange, durationSec: Int): List<TimeRange> =
        if (covers(ranges, range)) normalize(subtract(ranges, range), durationSec) else add(ranges, range, durationSec)

    /** The stretches that end up in the new recording, in playing order. Empty when nothing would be left. */
    fun keepRanges(mode: TrimMode, picked: List<TimeRange>, durationSec: Int): List<TimeRange> {
        val ranges = normalize(picked, durationSec)
        if (mode == TrimMode.KEEP) return ranges
        val kept = mutableListOf<TimeRange>()
        var cursor = 0
        for (r in ranges) {
            if (r.start > cursor) kept += TimeRange(cursor, r.start)
            cursor = r.end
        }
        if (cursor < durationSec) kept += TimeRange(cursor, durationSec)
        return kept
    }

    fun totalSec(keep: List<TimeRange>): Int = keep.sumOf { it.length }

    /** "Rapat (potongan 03:20–05:10)" for one stretch, "Rapat (potongan 3 rentang)" for several. */
    fun defaultTitle(sourceTitle: String, keep: List<TimeRange>): String = when (keep.size) {
        0 -> "$sourceTitle (potongan)"
        1 -> "$sourceTitle (potongan ${clock(keep[0].start)}–${clock(keep[0].end)})"
        else -> "$sourceTitle (potongan ${keep.size} rentang)"
    }

    private fun clock(totalSec: Int): String {
        val h = totalSec / 3600
        val m = totalSec % 3600 / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    /** Where a mark at [timestampSec] lands in the new recording, or null when its moment was cut away. */
    fun remapMark(timestampSec: Int, keep: List<TimeRange>): Int? {
        var before = 0
        for (r in keep) {
            if (timestampSec in r.start..r.end) return before + (timestampSec - r.start)
            before += r.length
        }
        return null
    }

    /**
     * The pieces between consecutive marks (plus the start and end of the recording): the units the editor
     * offers for quick picking. Marks at the very edges or duplicated are folded away.
     */
    fun segmentsBetween(markSecs: List<Int>, durationSec: Int): List<TimeRange> {
        if (durationSec <= 0) return emptyList()
        val points = (listOf(0) + markSecs.filter { it in 1 until durationSec } + durationSec).distinct().sorted()
        return points.zipWithNext { a, b -> TimeRange(a, b) }
    }
}
