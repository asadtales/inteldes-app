package com.inteldes.app.ui.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Every date and time the app shows or writes is Western Indonesia Time (WIB, Asia/Jakarta, GMT+7), whatever zone the
 * phone itself is set to, so the app, the web, exports and photo stamps always agree on when a meeting was.
 * WIB has no daylight saving, so a fixed +7 hours is exact.
 */
object Wib {
    val zone: TimeZone = TimeZone.getTimeZone("Asia/Jakarta")
    private const val OFFSET_MS = 7L * 3_600_000L
    private const val DAY_MS = 86_400_000L

    /** A formatter for [pattern] (Indonesian month names) in WIB. Not shared: SimpleDateFormat is not thread-safe. */
    fun formatter(pattern: String, locale: Locale = Locale("id", "ID")): SimpleDateFormat = SimpleDateFormat(pattern, locale).apply { timeZone = zone }

    fun format(pattern: String, epochMillis: Long): String = formatter(pattern).format(Date(epochMillis))

    /** "baru saja", "5 menit lalu", "14 jam lalu", "3 hari lalu", else the WIB date (Indonesian, whatever the phone language). */
    fun ago(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
        val diff = now - epochMillis
        val minutes = diff / 60_000
        return when {
            diff < 60_000 -> "baru saja"
            minutes < 60 -> "$minutes menit lalu"
            minutes < 60 * 24 -> "${minutes / 60} jam lalu"
            minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)} hari lalu"
            else -> format("d MMM yyyy", epochMillis)
        }
    }

    /** The calendar day (in WIB) of [epochMillis] as a whole number: equal numbers are the same WIB day. */
    fun dayNumber(epochMillis: Long): Long = Math.floorDiv(epochMillis + OFFSET_MS, DAY_MS)
}
