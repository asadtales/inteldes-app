package com.inteldes.app.ui.util

import java.util.concurrent.TimeUnit

fun formatDuration(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatClock(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

/** Matches the prototype's fixed "00:MM:SS" record-timer display (meetings never run past 24h). */
fun formatRecordTimer(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return "00:%02d:%02d".format(m, s)
}

fun formatRelativeDay(epochMillis: Long): String {
    val diffDays = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - epochMillis)
    return when {
        diffDays <= 0 -> "Hari ini"
        diffDays == 1L -> "Kemarin"
        diffDays < 7 -> "$diffDays hari lalu"
        diffDays < 14 -> "Pekan lalu"
        else -> "${diffDays / 7} minggu lalu"
    }
}
