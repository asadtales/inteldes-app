package com.inteldes.app.data.repository

import com.inteldes.app.data.db.ChatMessageEntity
import com.inteldes.app.data.db.IntelDesDatabase
import com.inteldes.app.data.db.NoteSectionEntity
import com.inteldes.app.data.db.PhotoEntity
import com.inteldes.app.data.db.RecordingEntity
import com.inteldes.app.data.db.SectionCodec
import com.inteldes.app.data.db.TranscriptSegmentEntity
import com.inteldes.app.data.db.toDomain
import com.inteldes.app.data.model.AiProvider
import com.inteldes.app.data.model.ChatMessage
import com.inteldes.app.data.model.NoteTemplate
import com.inteldes.app.data.model.Notulensi
import com.inteldes.app.data.model.Photo
import com.inteldes.app.data.model.Recording
import com.inteldes.app.data.model.RecordingKind
import com.inteldes.app.data.model.RecordingStatus
import com.inteldes.app.data.model.TranscriptSegment
import com.inteldes.app.data.model.WhisperEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.UUID

/** Single source of truth for recordings + their derived notulensi/transcript/chat/photos, backed by Room. */
class RecordingRepository(private val db: IntelDesDatabase) {

    fun observeRecordings(): Flow<List<Recording>> =
        db.recordingDao().observeAll().map { list -> list.map { it.toDomain() } }

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

    suspend fun createRecording(
        title: String,
        kind: RecordingKind,
        durationSec: Int,
        audioFilePath: String?,
        audioMimeType: String = "audio/mp4",
        engine: WhisperEngine,
        template: NoteTemplate,
    ): String {
        val id = UUID.randomUUID().toString()
        db.recordingDao().upsert(
            RecordingEntity(
                id = id, title = title, kind = kind, createdAt = System.currentTimeMillis(),
                durationSec = durationSec, audioFilePath = audioFilePath, audioMimeType = audioMimeType,
                status = RecordingStatus.RAW, speakerCount = 0, engine = engine,
                templateId = template.id, templateName = template.name,
            ),
        )
        return id
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

    suspend fun saveNotulensi(id: String, provider: AiProvider, notulensi: Notulensi) {
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
            db.recordingDao().update(it.copy(status = RecordingStatus.DONE, summary = notulensi.summary, provider = provider, notesGeneratedAt = System.currentTimeMillis(), errorMessage = null))
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
}
