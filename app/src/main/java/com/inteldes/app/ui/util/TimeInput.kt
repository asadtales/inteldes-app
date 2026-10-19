package com.inteldes.app.ui.util

/** "03:20", or "1:05:09" once past an hour. */
fun formatMmSs(totalSec: Int): String {
    val s = totalSec.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

/** Accepts "ss", "mm:ss" or "h:mm:ss"; null if empty/malformed. */
fun parseClock(text: String): Int? {
    val parts = text.trim().split(":")
    if (parts.isEmpty() || parts.size > 3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
    val nums = parts.map { it.toIntOrNull() ?: return null }
    return when (nums.size) {
        1 -> nums[0]
        2 -> nums[0] * 60 + nums[1]
        else -> nums[0] * 3600 + nums[1] * 60 + nums[2]
    }
}

/** The (start, end) pair if both fields parse and form a non-empty range inside [0, durationSec]. */
fun validRange(startText: String, endText: String, durationSec: Int): Pair<Int, Int>? {
    val start = parseClock(startText) ?: return null
    val end = parseClock(endText) ?: return null
    return if (start in 0 until end && end <= durationSec) start to end else null
}
