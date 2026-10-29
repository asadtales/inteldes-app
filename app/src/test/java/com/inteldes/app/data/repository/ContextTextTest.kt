package com.inteldes.app.data.repository

import com.inteldes.app.data.model.ActionItem
import com.inteldes.app.data.model.AiProvider
import com.inteldes.app.data.model.ContextSourceKind
import com.inteldes.app.data.model.KnowledgeContext
import com.inteldes.app.data.model.NoteSection
import com.inteldes.app.data.model.Notulensi
import com.inteldes.app.data.model.Recording
import com.inteldes.app.data.model.RecordingStatus
import com.inteldes.app.data.model.SectionContent
import com.inteldes.app.data.model.TranscriptSegment
import com.inteldes.app.data.model.WhisperEngine
import com.inteldes.app.data.network.ContextPrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextTextTest {
    private val recording = Recording(
        id = "r1", title = "Rapat jalan dusun 2", kind = "Musdes", createdAt = 1_790_000_000_000L, durationSec = 600,
        audioFilePath = null, audioMimeType = "audio/mp4", status = RecordingStatus.DONE, speakerCount = 2, engine = WhisperEngine.CLOUD,
        templateId = "builtin_standard", templateName = "Notulensi Standar", summary = "s", provider = AiProvider.GEMINI,
        errorMessage = null, locationName = "Balai Desa",
    )
    private val notulensi = Notulensi(
        "Disepakati perbaikan jalan.", AiProvider.GEMINI,
        listOf(NoteSection("Action item", SectionContent.Actions(listOf(ActionItem("Buat surat edaran", "Pak Carik", "Rabu", "01:00"))))),
    )
    private val transcript = listOf(TranscriptSegment("Pak Kades", 0, "Kita mulai."), TranscriptSegment("Bu RT", 75, "Izin usul."))

    @Test
    fun bothKindsIncludeSummarySectionsAndTranscript() {
        val text = ContextBuilder.section(recording, notulensi, transcript, ContextSourceKind.BOTH)
        assertTrue(text.startsWith("## Rapat jalan dusun 2 — "))
        assertTrue(text.contains("Musdes · Lokasi: Balai Desa"))
        assertTrue(text.contains("### Ringkasan\nDisepakati perbaikan jalan."))
        assertTrue(text.contains("### Action item"))
        assertTrue(text.contains("Buat surat edaran"))
        assertTrue(text.contains("### Transkrip"))
        assertTrue(text.contains("Pak Kades: Kita mulai."))
        assertTrue(text.contains("Bu RT: Izin usul."))
    }

    @Test
    fun notulensiOnlyHasNoTranscriptAndTranscriptOnlyHasNoSummary() {
        val n = ContextBuilder.section(recording, notulensi, transcript, ContextSourceKind.NOTULENSI)
        assertFalse(n.contains("### Transkrip"))
        val t = ContextBuilder.section(recording, notulensi, transcript, ContextSourceKind.TRANSCRIPT)
        assertFalse(t.contains("### Ringkasan"))
        assertTrue(t.contains("### Transkrip"))
    }

    @Test
    fun missingNotulensiIsSaidPlainlyNotSilentlyDropped() {
        val n = ContextBuilder.section(recording, null, transcript, ContextSourceKind.NOTULENSI)
        assertTrue(n.contains("Notulensi belum dibuat"))
    }

    @Test
    fun aSectionThatJustRepeatsTheSummaryIsNotPrintedTwice() {
        val dup = Notulensi("Rapat singkat.", AiProvider.GEMINI, listOf(NoteSection("Ringkasan", SectionContent.Paragraph("Rapat singkat."))))
        val text = ContextBuilder.section(recording, dup, transcript, ContextSourceKind.NOTULENSI)
        assertEquals(1, Regex("Rapat singkat[.]").findAll(text).count())
    }

    @Test
    fun severalRecordingsAreSeparatedByARule() {
        val one = ContextBuilder.section(recording, notulensi, transcript, ContextSourceKind.NOTULENSI)
        assertEquals("$one\n\n---\n\n$one", ContextBuilder.join(listOf(one, one)))
    }

    @Test
    fun aiRewriteWrappedInACodeFenceIsUnwrapped() {
        assertEquals("## Judul\nisi", ContextPrompts.stripFences("```markdown\n## Judul\nisi\n```"))
        assertEquals("## Judul\nisi", ContextPrompts.stripFences("  ## Judul\nisi \n"))
        assertEquals("a ``` b", ContextPrompts.stripFences("a ``` b"))
    }

    @Test
    fun wordCountIgnoresWhitespaceRuns() {
        val c = KnowledgeContext("1", "x", "satu  dua\n\n tiga ", 0, 0)
        assertEquals(3, c.wordCount)
    }
}
