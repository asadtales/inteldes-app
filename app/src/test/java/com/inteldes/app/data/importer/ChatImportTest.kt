package com.inteldes.app.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatImportTest {
    @Test
    fun whatsappAndroidExportKeepsSpeakersTimesAndMultilineMessages() {
        val text = """
            12/10/25 14.30 - Pesan dan panggilan terenkripsi end-to-end. Tidak ada orang di luar chat ini yang dapat membaca.
            12/10/25 14.30 - Pak Carik: Selamat siang bapak ibu, rapat kita mulai.
            12/10/25 14.32 - Bu RT: Izin, usul perbaikan jalan dusun 2.
            Biayanya sekitar 15 juta.
            12/10/25 14.40 - Bu RT: <Media tidak disertakan>
            12/10/25 15.00 - Pak Kades: Disetujui, mulai bulan depan.
        """.trimIndent()
        val chat = ChatImport.parse(text)!!
        assertEquals(3, chat.segments.size)
        assertEquals(listOf("Pak Carik", "Bu RT", "Pak Kades"), chat.segments.map { it.speaker })
        assertEquals(listOf(0, 120, 1800), chat.segments.map { it.timestampSec })
        assertEquals("Izin, usul perbaikan jalan dusun 2.\nBiayanya sekitar 15 juta.", chat.segments[1].text)
        assertEquals(3, chat.participants)
        assertEquals(1800, chat.durationSec)
        assertNotNull(chat.startMillis)
    }

    @Test
    fun whatsappIphoneBracketFormatAndFourDigitYear() {
        val text = "[12/10/2025 14.30.15] Andi: Halo\n[12/10/2025 14.31.45] Budi: Siap"
        val chat = ChatImport.parse(text)!!
        assertEquals(listOf("Andi", "Budi"), chat.segments.map { it.speaker })
        assertEquals(90, chat.segments[1].timestampSec)
    }

    @Test
    fun amPmClockAndMonthFirstDatesAreUnderstood() {
        val text = "10/25/25, 2:30 PM - Ann: Hi\n10/25/25, 2:45 PM - Bob: Hello"
        val chat = ChatImport.parse(text)!!
        assertEquals(900, chat.segments[1].timestampSec) // 15 minutes, so 10/25 was read as month/day
    }

    @Test
    fun plainDialogueWithoutTimestamps() {
        val chat = ChatImport.parse("Ketua: Kita mulai rapat.\nSekretaris: Notulen dicatat.\nlanjutan ucapan sekretaris")!!
        assertEquals(listOf("Ketua", "Sekretaris"), chat.segments.map { it.speaker })
        assertTrue(chat.segments[1].text.endsWith("lanjutan ucapan sekretaris"))
        assertNull(chat.startMillis)
        assertEquals(listOf(0, 5), chat.segments.map { it.timestampSec })
    }

    @Test
    fun unrelatedOrEmptyTextGivesNothing() {
        assertNull(ChatImport.parse(""))
        assertNull(ChatImport.parse("   \n  "))
        assertNull(ChatImport.parse("12/10/25 14.30 - Pesan dan panggilan terenkripsi.\n12/10/25 14.31 - Anda membuat grup"))
    }
}
