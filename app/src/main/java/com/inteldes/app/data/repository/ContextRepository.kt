package com.inteldes.app.data.repository

import com.inteldes.app.data.db.ContextSourceEntity
import com.inteldes.app.data.db.IntelDesDatabase
import com.inteldes.app.data.db.KnowledgeContextEntity
import com.inteldes.app.data.export.NotulensiExporter
import com.inteldes.app.data.model.ContextSource
import com.inteldes.app.data.model.ContextSourceKind
import com.inteldes.app.data.model.KnowledgeContext
import com.inteldes.app.data.model.Notulensi
import com.inteldes.app.data.model.Recording
import com.inteldes.app.data.model.TranscriptSegment
import com.inteldes.app.ui.util.formatClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Turns recordings into the plain Markdown-ish text a knowledge context is made of. */
object ContextBuilder {
    private val dateFormat get() = SimpleDateFormat("d MMMM yyyy", Locale("id", "ID"))

    fun section(recording: Recording, notulensi: Notulensi?, transcript: List<TranscriptSegment>, kind: ContextSourceKind): String =
        buildString {
            appendLine("## ${recording.title} — ${dateFormat.format(Date(recording.createdAt))}")
            val meta = listOfNotNull(recording.kind.takeIf { it.isNotBlank() }, recording.locationName?.let { "Lokasi: $it" })
            if (meta.isNotEmpty()) appendLine(meta.joinToString(" · "))
            if (kind != ContextSourceKind.TRANSCRIPT) {
                if (notulensi != null) {
                    appendLine()
                    appendLine("### Ringkasan")
                    appendLine(notulensi.summary)
                    notulensi.sections.forEach { s ->
                        // The standard template's own "Ringkasan" section repeats the summary printed above.
                        val c = s.content
                        if (c is com.inteldes.app.data.model.SectionContent.Paragraph && c.text.trim() == notulensi.summary.trim()) return@forEach
                        val lines = NotulensiExporter.sectionLines(s.content)
                        if (lines.isNotEmpty()) {
                            appendLine()
                            appendLine("### ${s.title}")
                            lines.forEach { appendLine(it) }
                        }
                    }
                } else if (kind == ContextSourceKind.NOTULENSI) {
                    appendLine()
                    appendLine("(Notulensi belum dibuat untuk rekaman ini.)")
                }
            }
            if (kind != ContextSourceKind.NOTULENSI && transcript.isNotEmpty()) {
                appendLine()
                appendLine("### Transkrip")
                transcript.forEach { appendLine("[${formatClock(it.timestampSec)}] ${it.speaker}: ${it.text}") }
            }
        }.trim()

    /** Sections for several recordings, separated by a rule so they stay distinguishable inside one document. */
    fun join(sections: List<String>): String = sections.joinToString("\n\n---\n\n")
}

/** Saved knowledge contexts: merged, editable text built from one or more recordings. */
class ContextRepository(private val db: IntelDesDatabase, private val recordings: RecordingRepository) {

    fun observeAll(): Flow<List<KnowledgeContext>> =
        combine(db.contextDao().observeAll(), db.contextDao().observeSourceCounts()) { list, counts ->
            val byContext = counts.associate { it.contextId to it.n }
            list.map { it.toDomain(sources = List(byContext[it.id] ?: 0) { ContextSource("", "", ContextSourceKind.BOTH) }) }
        }

    fun observe(id: String): Flow<KnowledgeContext?> =
        combine(db.contextDao().observeOne(id), db.contextDao().observeSources(id)) { c, sources ->
            c?.toDomain(sources.map { ContextSource(it.recordingId, it.recordingTitle, runCatching { ContextSourceKind.valueOf(it.kind) }.getOrDefault(ContextSourceKind.BOTH)) })
        }

    suspend fun get(id: String): KnowledgeContext? = observe(id).first()

    /** Text for one recording in the requested form. */
    suspend fun sectionFor(recordingId: String, kind: ContextSourceKind): String? {
        val recording = recordings.getRecording(recordingId) ?: return null
        val notulensi = recordings.observeNotulensi(recordingId).first()
        val transcript = recordings.observeTranscript(recordingId).first()
        return ContextBuilder.section(recording, notulensi, transcript, kind)
    }

    /** Creates a context from [recordingIds], merged in the order given. Returns its id. */
    suspend fun create(name: String, recordingIds: List<String>, kind: ContextSourceKind): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val (text, sources) = merge(recordingIds, kind, now, id)
        db.contextDao().upsert(KnowledgeContextEntity(id = id, name = name.ifBlank { "Konteks baru" }, text = text, createdAt = now, updatedAt = now))
        db.contextDao().insertSources(sources)
        return id
    }

    /** Appends more recordings to an existing context (its current, possibly hand-edited, text is kept). */
    suspend fun addRecordings(contextId: String, recordingIds: List<String>, kind: ContextSourceKind) {
        val current = db.contextDao().getOne(contextId) ?: return
        val now = System.currentTimeMillis()
        val (added, sources) = merge(recordingIds, kind, now, contextId)
        if (sources.isEmpty()) return
        val text = if (current.text.isBlank()) added else current.text.trimEnd() + "\n\n---\n\n" + added
        db.contextDao().updateText(contextId, text, now)
        db.contextDao().insertSources(sources)
    }

    suspend fun updateText(id: String, text: String) = db.contextDao().updateText(id, text, System.currentTimeMillis())
    suspend fun rename(id: String, name: String) = db.contextDao().rename(id, name.ifBlank { "Konteks" }, System.currentTimeMillis())
    suspend fun delete(id: String) = db.contextDao().delete(id)

    private suspend fun merge(recordingIds: List<String>, kind: ContextSourceKind, now: Long, contextId: String): Pair<String, List<ContextSourceEntity>> {
        val sections = mutableListOf<String>()
        val sources = mutableListOf<ContextSourceEntity>()
        for (rid in recordingIds) {
            val recording = recordings.getRecording(rid) ?: continue
            val text = sectionFor(rid, kind) ?: continue
            sections += text
            sources += ContextSourceEntity(contextId = contextId, recordingId = rid, recordingTitle = recording.title, kind = kind.name, addedAt = now)
        }
        return ContextBuilder.join(sections) to sources
    }

    private fun KnowledgeContextEntity.toDomain(sources: List<ContextSource>) =
        KnowledgeContext(id, name, text, createdAt, updatedAt, sources)
}
