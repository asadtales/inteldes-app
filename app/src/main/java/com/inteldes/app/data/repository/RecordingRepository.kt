package com.inteldes.app.data.repository

import com.inteldes.app.data.audio.AudioTrimmer
import com.inteldes.app.data.audio.TimeRange
import com.inteldes.app.data.audio.TrimPlan
import com.inteldes.app.data.db.CategoryEntity
import com.inteldes.app.data.db.ChatMessageEntity
import com.inteldes.app.data.db.IntelDesDatabase
import com.inteldes.app.data.db.NoteSectionEntity
import com.inteldes.app.data.db.NotulensiHistoryEntity
import com.inteldes.app.data.db.NotulensiSnapshot
import com.inteldes.app.data.db.PhotoEntity
import com.inteldes.app.data.db.RecordingEntity
import com.inteldes.app.data.db.RecordingMarkEntity
import com.inteldes.app.data.db.SectionCodec
import com.inteldes.app.data.db.TranscriptSegmentEntity
import com.inteldes.app.data.db.toDomain
import com.inteldes.app.data.model.AiProvider
import com.inteldes.app.data.model.ChatMessage
import com.inteldes.app.data.model.DEFAULT_CATEGORIES
import com.inteldes.app.data.model.LocationInfo
import com.inteldes.app.data.model.NoteTemplate
import com.inteldes.app.data.model.Notulensi
import com.inteldes.app.data.model.NotulensiDensity
import com.inteldes.app.data.model.NotulensiHistoryEntry
import com.inteldes.app.data.model.Photo
import com.inteldes.app.data.model.Recording
import com.inteldes.app.data.model.RecordingMark
import com.inteldes.app.data.model.RecordingStatus
import com.inteldes.app.data.model.TranscriptSegment
import com.inteldes.app.data.model.WhisperEngine
import com.inteldes.app.ui.util.formatMmSs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

const val FALLBACK_CATEGORY = "Lainnya"

/** Single source of truth for recordings + their derived notulensi/transcript/chat/photos, backed by Room. */
class RecordingRepository(private val db: IntelDesDatabase) {

    fun observeRecordings(): Flow<List<Recording>> =
        db.recordingDao().observeAll().map { list -> list.map { it.toDomain() } }

    fun observeCategories(): Flow<List<String>> =
        db.categoryDao().observeAll().map { list -> list.map { it.label } }

    /** Idempotent — safe to call on every app start. Inserts are ignored once a label exists. */
    suspend fun ensureDefaultCategories() {
        if (db.categoryDao().count() == 0) {
            db.categoryDao().insertAll(DEFAULT_CATEGORIES.mapIndexed { i, label -> CategoryEntity(label = label, orderIndex = i) })
        }
    }

    suspend fun addCategory(label: String): Boolean {
        val trimmed = label.trim()
        if (trimmed.isBlank()) return false
        val existing = db.categoryDao().observeAll().first()
        if (existing.any { it.label.equals(trimmed, ignoreCase = true) }) return false
        db.categoryDao().insert(CategoryEntity(label = trimmed, orderIndex = existing.size))
        return true
    }

    /** Recordings in the removed category move to [FALLBACK_CATEGORY] so they stay reachable by filter. */
    suspend fun deleteCategory(label: String) {
        if (label == FALLBACK_CATEGORY) return
        addCategory(FALLBACK_CATEGORY)
        db.recordingDao().reassignKind(label, FALLBACK_CATEGORY)
        db.categoryDao().delete(label)
    }

    fun observeRecording(id: String): Flow<Recording?> =
        db.recordingDao().observeOne(id).map { it?.toDomain() }

    suspend fun getRecording(id: String): Recording? = db.recordingDao().getOne(id)?.toDomain()

    fun observeNotulensi(id: String): Flow<Notulensi?> =
        combine(db.noteSectionDao().observe(id), db.recordingDao().observeOne(id)) { sections, r ->
            val summary = r?.summary
            val provider = r?.provider
            if (summary == null || provider == null) null
            else Notulensi(summary = summary, provider = provider, sections = sections.map { it.toDomain() })
        }

    fun observeTranscript(id: String): Flow<List<TranscriptSegment>> =
        db.transcriptDao().observeSegments(id).map { list -> list.map { it.toDomain() } }

    fun observeChat(id: String): Flow<List<ChatMessage>> =
        db.chatDao().observeMessages(id).map { list -> list.map { it.toDomain() } }

    fun observePhotos(id: String): Flow<List<Photo>> =
        db.photoDao().observe(id).map { list -> list.map { it.toDomain() } }

    suspend fun renameSpeaker(recordingId: String, oldName: String, newName: String) {
        if (newName.isBlank() || oldName == newName) return
        db.transcriptDao().renameSpeaker(recordingId, oldName, newName)
    }

    /** Fixes the wording of one transcript line ([index] = its position in the transcript). */
    suspend fun updateTranscriptText(recordingId: String, index: Int, text: String) {
        db.transcriptDao().updateText(recordingId, index, text.trim())
    }

    suspend fun createRecording(
        title: String,
        kind: String,
        durationSec: Int,
        audioFilePath: String?,
        audioMimeType: String = "audio/mp4",
        engine: WhisperEngine,
        template: NoteTemplate,
        location: LocationInfo? = null,
        createdAt: Long? = null,
    ): String = insertRecording(title, kind, durationSec, audioFilePath, audioMimeType, engine, template.id, template.name, location, createdAt)

    private suspend fun insertRecording(
        title: String,
        kind: String,
        durationSec: Int,
        audioFilePath: String?,
        audioMimeType: String,
        engine: WhisperEngine,
        templateId: String,
        templateName: String,
        location: LocationInfo? = null,
        createdAt: Long? = null,
    ): String {
        val id = UUID.randomUUID().toString()
        db.recordingDao().upsert(
            RecordingEntity(
                id = id, title = title, kind = kind, createdAt = createdAt ?: System.currentTimeMillis(),
                durationSec = durationSec, audioFilePath = audioFilePath, audioMimeType = audioMimeType,
                status = RecordingStatus.RAW, speakerCount = 0, engine = engine,
                templateId = templateId, templateName = templateName,
                locationName = location?.name?.takeIf { it.isNotBlank() },
                latitude = location?.latitude, longitude = location?.longitude,
            ),
        )
        return id
    }

    suspend fun setEngine(id: String, engine: WhisperEngine) {
        db.recordingDao().getOne(id)?.let { db.recordingDao().update(it.copy(engine = engine)) }
    }

    /** Blank clears the location. */
    suspend fun setLocation(id: String, name: String) {
        db.recordingDao().getOne(id)?.let {
            val trimmed = name.trim()
            db.recordingDao().update(
                if (trimmed.isEmpty()) it.copy(locationName = null, latitude = null, longitude = null)
                else it.copy(locationName = trimmed),
            )
        }
    }

    suspend fun setTemplate(id: String, template: NoteTemplate) {
        db.recordingDao().getOne(id)?.let { db.recordingDao().update(it.copy(templateId = template.id, templateName = template.name)) }
    }

    suspend fun markWorking(id: String) {
        db.recordingDao().getOne(id)?.let { db.recordingDao().update(it.copy(status = RecordingStatus.WORKING, errorMessage = null)) }
    }

    suspend fun markFailed(id: String, message: String) {
        db.recordingDao().getOne(id)?.let { db.recordingDao().update(it.copy(status = RecordingStatus.RAW, errorMessage = message)) }
    }

    suspend fun saveTranscript(id: String, speakerCount: Int, segments: List<TranscriptSegment>) {
        db.transcriptDao().clear(id)
        db.transcriptDao().insertSegments(
            segments.mapIndexed { i, s -> TranscriptSegmentEntity(recordingId = id, orderIndex = i, speaker = s.speaker, timestampSec = s.timestampSec, text = s.text) },
        )
        db.recordingDao().getOne(id)?.let { db.recordingDao().update(it.copy(speakerCount = speakerCount)) }
    }

    suspend fun saveNotulensi(id: String, provider: AiProvider, notulensi: Notulensi, density: NotulensiDensity) {
        // Archive whatever notulensi is about to be overwritten — regenerating (with another
        // density or template) shouldn't destroy the previous result, since the whole point
        // is to be able to compare them.
        val previous = db.recordingDao().getOne(id)
        if (previous?.summary != null && previous.provider != null && previous.notulensiDensity != null) {
            observeNotulensi(id).first()?.let { previousNotulensi ->
                db.notulensiHistoryDao().insert(
                    NotulensiHistoryEntity(
                        recordingId = id,
                        generatedAt = previous.notesGeneratedAt ?: System.currentTimeMillis(),
                        density = previous.notulensiDensity,
                        provider = previous.provider,
                        notulensiJson = NotulensiSnapshot.encode(previousNotulensi, previous.templateName),
                    ),
                )
            }
        }

        db.noteSectionDao().replace(
            id,
            notulensi.sections.mapIndexed { i, s ->
                NoteSectionEntity(
                    recordingId = id, orderIndex = i, title = s.title,
                    type = SectionCodec.typeOf(s.content), payload = SectionCodec.encode(s.content),
                )
            },
        )
        db.recordingDao().getOne(id)?.let {
            db.recordingDao().update(
                it.copy(
                    status = RecordingStatus.DONE, summary = notulensi.summary, provider = provider,
                    notesGeneratedAt = System.currentTimeMillis(), errorMessage = null, notulensiDensity = density,
                ),
            )
        }
    }

    fun observeNotulensiHistory(id: String): Flow<List<NotulensiHistoryEntry>> =
        db.notulensiHistoryDao().observeForRecording(id).map { list ->
            list.mapNotNull { row ->
                // A snapshot that can't be decoded is skipped rather than taking the whole screen down.
                runCatching { NotulensiSnapshot.decode(row.notulensiJson, row.provider) }.getOrNull()?.let { (note, templateName) ->
                    NotulensiHistoryEntry(row.id, row.generatedAt, row.density, templateName, note)
                }
            }
        }

    /** [takenAtSec] is the offset into the audio when the photo was added; null when added after the fact. */
    suspend fun addPhoto(recordingId: String, file: File, takenAtSec: Int?) {
        db.photoDao().insert(
            PhotoEntity(recordingId = recordingId, filePath = file.absolutePath, takenAtSec = takenAtSec, createdAt = System.currentTimeMillis()),
        )
    }

    suspend fun updatePhotoCaption(photoId: Long, caption: String) {
        db.photoDao().updateCaption(photoId, caption.trim())
    }

    suspend fun deletePhoto(photo: Photo) {
        db.photoDao().delete(photo.id)
        File(photo.filePath).delete()
    }

    suspend fun addChatMessage(recordingId: String, isUser: Boolean, text: String) {
        val count = db.chatDao().count(recordingId)
        db.chatDao().insert(ChatMessageEntity(recordingId = recordingId, orderIndex = count, isUser = isUser, text = text, createdAt = System.currentTimeMillis()))
    }

    /**
     * Trim editor: copies the [keep] stretches of [source]'s audio, joined in order, into a brand-new
     * recording (new file, new row), carrying over the marks that fall inside them (moved to where they
     * land in the clip). The source recording and its file are only read, never modified — deleting either
     * one later doesn't affect the other. The new recording starts RAW; the caller may choose its template
     * and engine and start processing.
     */
    suspend fun createTrimmedRecording(
        source: Recording,
        keep: List<TimeRange>,
        outputDir: File,
        title: String = "${source.title} (hasil potong)",
        template: NoteTemplate? = null,
        engine: WhisperEngine = source.engine,
    ): String =
        withContext(Dispatchers.IO) {
            require(keep.isNotEmpty()) { "Tidak ada bagian yang disimpan." }
            val sourcePath = source.audioFilePath ?: throw IOException("Rekaman ini tidak punya berkas audio.")
            val sourceFile = File(sourcePath)
            if (!sourceFile.exists()) throw IOException("Berkas audio rekaman ini tidak ditemukan di perangkat.")
            outputDir.mkdirs()
            // The new file keeps the source's kind of container: m4a stays m4a, a browser's webm stays webm.
            val output = AudioTrimmer.outputFor(sourceFile)
                ?: throw AudioTrimmer.UnsupportedFormatException("Format audio ini (${source.audioMimeType}) belum bisa dipotong di HP.")
            val dest = File(outputDir, "trim_${System.currentTimeMillis()}.${output.extension}")
            AudioTrimmer.trimRanges(sourceFile, dest, keep)

            val id = insertRecording(
                title = title.ifBlank { "${source.title} (hasil potong)" },
                kind = source.kind,
                durationSec = TrimPlan.totalSec(keep),
                audioFilePath = dest.absolutePath,
                audioMimeType = output.mimeType,
                engine = engine,
                templateId = template?.id ?: source.templateId,
                templateName = template?.name ?: source.templateName,
                location = source.locationName?.let { LocationInfo(it, source.latitude, source.longitude) },
            )
            val now = System.currentTimeMillis()
            db.recordingMarkDao().observeForRecording(source.id).first().forEach { mark ->
                val at = TrimPlan.remapMark(mark.timestampSec, keep) ?: return@forEach
                db.recordingMarkDao().insert(RecordingMarkEntity(recordingId = id, timestampSec = at, label = mark.label, createdAt = now))
            }
            id
        }

    fun observeMarks(id: String): Flow<List<RecordingMark>> =
        db.recordingMarkDao().observeForRecording(id).map { list -> list.map { it.toDomain() } }

    /** Bulk insert used right after a recording is created, for marks tapped mid-recording
     * (before the recording had an id to attach them to). */
    suspend fun addMarks(recordingId: String, timestampsSec: List<Int>) {
        if (timestampsSec.isEmpty()) return
        val now = System.currentTimeMillis()
        db.recordingMarkDao().insertAll(
            timestampsSec.mapIndexed { i, sec -> RecordingMarkEntity(recordingId = recordingId, timestampSec = sec, label = "Penanda ${i + 1}", createdAt = now) },
        )
    }

    suspend fun addMark(recordingId: String, timestampSec: Int, label: String) {
        db.recordingMarkDao().insert(RecordingMarkEntity(recordingId = recordingId, timestampSec = timestampSec, label = label, createdAt = System.currentTimeMillis()))
    }

    /**
     * Renames a mark. Its web id is fixed first (derived ids include the label), so the next sync sends the same
     * mark with the new label instead of a second mark.
     */
    suspend fun renameMark(markId: Long, label: String) {
        val clean = label.trim().take(300)
        if (clean.isEmpty()) return
        val m = db.recordingMarkDao().get(markId) ?: return
        val id = m.uuid ?: com.inteldes.app.data.web.WebIds.forMark(m.recordingId, m.timestampSec.coerceAtLeast(0), m.label.take(300), m.createdAt)
        db.recordingMarkDao().rename(markId, clean, id)
    }

    suspend fun deleteMark(mark: RecordingMark, recordingId: String) {
        db.recordingMarkDao().delete(RecordingMarkEntity(id = mark.id, recordingId = recordingId, timestampSec = mark.timestampSec, label = mark.label, createdAt = 0))
    }

    /** Set by the app: called with the id of a recording the user deleted on this phone and the web already had. */
    var onDeletedHere: ((String) -> Unit)? = null

    /** Deletes the recording row (cascades to its transcript/notulensi/chat/photo rows), its audio file and photo files. */
    suspend fun deleteRecording(id: String) {
        val entity = db.recordingDao().getOne(id) ?: return
        // Tell the web sync (once the user turns it on) that THIS phone deleted it, so the server copy goes too.
        // Only recordings the server already knows about are reported.
        if (db.webSyncDao().get(id) != null) onDeletedHere?.invoke(id)
        entity.audioFilePath?.let { File(it).delete() }
        // Photo rows cascade with the recording, but the image files don't.
        db.photoDao().observe(id).first().forEach { File(it.filePath).delete() }
        db.recordingDao().delete(entity)
    }
}
