package com.inteldes.app.ui

import com.inteldes.app.ui.util.Wib
import com.inteldes.app.ui.util.formatRelativeDay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.TimeZone

class WibTest {
    private val original = TimeZone.getDefault()
    @After fun restore() = TimeZone.setDefault(original)

    private val utc0400 = 1_793_764_800_000L          // 2026-11-04 04:00:00 UTC = 11.00 WIB

    @Test
    fun timesAreWibWhateverZoneThePhoneIsIn() {
        for (id in listOf("UTC", "America/New_York", "Asia/Jayapura", "Pacific/Auckland")) {
            TimeZone.setDefault(TimeZone.getTimeZone(id))
            assertEquals(id, "4 Nov 2026, 11:00", Wib.format("d MMM yyyy, HH:mm", utc0400))
        }
    }

    @Test
    fun theWibDayChangesAtSeventeenHoursUtc() {
        val lastMinuteOfTheDay = 1_793_811_540_000L    // 2026-11-04 16:59 UTC = 23.59 WIB
        assertEquals(Wib.dayNumber(utc0400), Wib.dayNumber(lastMinuteOfTheDay))
        assertNotEquals(Wib.dayNumber(utc0400), Wib.dayNumber(lastMinuteOfTheDay + 60_000))     // 17:00 UTC = 00.00 WIB of the next day
    }

    @Test
    fun relativeDaysCountCalendarDaysInWib() {
        val now = 1_793_811_600_000L + 3_600_000L       // 01.00 WIB on 5 Nov
        val yesterdayEvening = 1_793_811_540_000L       // 23.59 WIB on 4 Nov, only 61 minutes earlier
        assertEquals("Kemarin", formatRelativeDay(yesterdayEvening, now))                         // not "Hari ini"
        assertEquals("Hari ini", formatRelativeDay(now - 30 * 60_000, now))
        assertEquals("2 hari lalu", formatRelativeDay(now - 2 * 86_400_000L, now))
        assertEquals("Pekan lalu", formatRelativeDay(now - 8 * 86_400_000L, now))
        assertEquals("3 minggu lalu", formatRelativeDay(now - 22 * 86_400_000L, now))
    }
}
