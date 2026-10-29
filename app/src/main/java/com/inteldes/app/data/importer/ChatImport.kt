package com.inteldes.app.data.importer

import com.inteldes.app.data.model.TranscriptSegment
import java.util.Calendar
import java.util.TimeZone

/** A conversation read from a text file, ready to be saved as a transcript. */
data class ParsedChat(
    val segments: List<TranscriptSegment>,
    /** Time of the first message when the file carries timestamps (WhatsApp export), else null. */
    val startMillis: Long?,
    val durationSec: Int,
    val participants: Int,
)

/**
 * Turns pasted/exported conversation text into transcript segments so it can go through the same
 * notulensi pipeline as a transcribed recording.
 *
 * Understood formats:
 *  - WhatsApp "Ekspor chat" (Android): `12/10/25 14.30 - Nama: pesan`, `12/10/2025, 14:30 - Nama: pesan`
 *  - WhatsApp (iPhone): `[12/10/25 14.30.15] Nama: pesan`
 *  - Plain text where each line is `Nama: ucapan` (e.g. a typed-up or app-exported dialogue); lines without
 *    a name continue the previous speaker, or belong to "Peserta" at the start.
 * Lines that continue a message (no timestamp) are appended to it. System lines ("Pesan dan panggilan
 * terenkripsi…", media placeholders) are dropped.
 */
object ChatImport {
    const val MAX_CHARS = 300_000

    private const val DATE = "(\\d{1,2})[/.](\\d{1,2})[/.](\\d{2,4})"
    private const val CLOCK = "(\\d{1,2})[.:](\\d{2})(?:[.:](\\d{2}))?\\s*([AaPp][Mm])?"
    private val android = Regex("^$DATE,?\\s+$CLOCK\\s+-\\s+(.*)$")
    private val ios = Regex("^\\[$DATE,?\\s+$CLOCK]\\s+(.*)$")
    private val named = Regex("^([^:\\n]{1,40}):\\s+(.+)$")
    private val noise = Regex("(?i)^(<.*(tidak disertakan|omitted|attached).*>|.*(tidak disertakan|omitted)$|null|pesan ini dihapus|anda menghapus pesan ini|this message was deleted)$")
    private val invisible = Regex("[\\u200e\\u200f\\u202a-\\u202e\\uFEFF]")

    private class Raw(val stamp: IntArray?, var speaker: String, val text: StringBuilder)

    fun parse(input: String): ParsedChat? {
        val lines = input.replace(' ', ' ').replace(' ', ' ').replace(invisible, "").lines()
        val raws = parseTimestamped(lines) ?: parsePlain(lines)
        val messages = raws.map { it to it.text.toString().trim() }
            .filter { (_, t) -> t.isNotEmpty() && !noise.matches(t) }
        if (messages.size < 1) return null

        val stamps = messages.mapNotNull { (r, _) -> r.stamp }
        return if (stamps.size == messages.size) timestamped(messages.map { it.first.stamp!! to (it.first.speaker to it.second) }, dayFirst(stamps))
        else sequential(messages.map { it.first.speaker to it.second })
    }

    // ───────────────────────── timestamped (WhatsApp) ─────────────────────────

    /** Null when no line looks like a WhatsApp message header. */
    private fun parseTimestamped(lines: List<String>): List<Raw>? {
        val out = mutableListOf<Raw>()
        var headers = 0
        for (line in lines) {
            val m = android.matchEntire(line) ?: ios.matchEntire(line)
            if (m != null) {
                val body = m.groupValues[m.groupValues.size - 1]
                val parts = named.matchEntire(body)
                headers++
                if (parts == null) { out += Raw(null, "", StringBuilder()); continue } // system line: dropped below
                val g = m.groupValues
                val hour = g[4].toInt().let { h -> when (g[7].lowercase()) { "pm" -> if (h < 12) h + 12 else h; "am" -> if (h == 12) 0 else h; else -> h } }
                val stamp = intArrayOf(g[1].toInt(), g[2].toInt(), g[3].toInt(), hour, g[5].toInt(), g[6].toIntOrNull() ?: 0)
                out += Raw(stamp, parts.groupValues[1].trim(), StringBuilder(parts.groupValues[2]))
            } else if (out.isNotEmpty() && line.isNotBlank() && out.last().stamp != null) {
                out.last().text.append('\n').append(line.trim())
            }
        }
        if (headers < 1) return null
        return out.filter { it.stamp != null }
    }

    /** True for dd/mm, false for mm/dd. Indonesian exports are day-first, so that's the default. */
    private fun dayFirst(stamps: List<IntArray>): Boolean = when {
        stamps.any { it[0] > 12 } -> true
        stamps.any { it[1] > 12 } -> false
        else -> true
    }

    private fun timestamped(items: List<Pair<IntArray, Pair<String, String>>>, dayFirst: Boolean): ParsedChat? {
        val zone = TimeZone.getDefault()
        fun millis(s: IntArray): Long {
            val (a, b, yRaw) = Triple(s[0], s[1], s[2])
            val day = if (dayFirst) a else b
            val month = if (dayFirst) b else a
            val year = if (yRaw < 100) 2000 + yRaw else yRaw
            return Calendar.getInstance(zone).apply { clear(); set(year, month - 1, day, s[3], s[4], s[5]) }.timeInMillis
        }
        val times = items.map { millis(it.first) }
        val start = times.min()
        val segments = items.mapIndexed { i, (_, who) ->
            TranscriptSegment(speaker = who.first, timestampSec = ((times[i] - start) / 1000).toInt().coerceAtLeast(0), text = who.second)
        }.sortedBy { it.timestampSec }
        return ParsedChat(segments, start, ((times.max() - start) / 1000).toInt().coerceAtLeast(60), segments.map { it.speaker }.toSet().size)
    }

    // ───────────────────────── plain "Nama: ucapan" text ─────────────────────────

    private fun parsePlain(lines: List<String>): List<Raw> {
        val out = mutableListOf<Raw>()
        for (line in lines) {
            if (line.isBlank()) continue
            val m = named.matchEntire(line.trim())
            if (m != null) out += Raw(null, m.groupValues[1].trim(), StringBuilder(m.groupValues[2]))
            else if (out.isNotEmpty()) out.last().text.append('\n').append(line.trim())
            else out += Raw(null, "Peserta", StringBuilder(line.trim()))
        }
        return out
    }

    /** No real times: keep the order, spaced 5 s apart so the notulensi still gets sensible "menit" labels. */
    private fun sequential(items: List<Pair<String, String>>): ParsedChat {
        val segments = items.mapIndexed { i, (who, text) -> TranscriptSegment(who, i * 5, text) }
        return ParsedChat(segments, null, (items.size * 5).coerceAtLeast(60), segments.map { it.speaker }.toSet().size)
    }
}
