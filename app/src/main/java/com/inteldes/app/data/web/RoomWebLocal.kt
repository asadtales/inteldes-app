package com.inteldes.app.data.web

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.JsonParser
import com.inteldes.app.data.db.CategoryEntity
import com.inteldes.app.data.db.ChatMessageEntity
import com.inteldes.app.data.db.IntelDesDatabase
import com.inteldes.app.data.db.NoteSectionEntity
import com.inteldes.app.data.db.PhotoEntity
import com.inteldes.app.data.db.RecordingEntity
import com.inteldes.app.data.db.RecordingMarkEntity
import com.inteldes.app.data.db.TranscriptSegmentEntity
import com.inteldes.app.data.model.AiProvider
import com.inteldes.app.data.model.NotulensiDensity
import com.inteldes.app.data.model.RecordingStatus
import com.inteldes.app.data.model.SectionType
import com.inteldes.app.data.model.WhisperEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [WebLocal] on Room. The one rule it exists to keep: it NEVER deletes a recording, and it never replaces
 * something the phone has with something empty.
 */
class RoomWebLocal(context: Context, private val db: IntelDesDatabase) : WebLocal {
    private val filesDir = context.filesDir

    override suspend fun recordingIds(): List<String> = db.recordingDao().ids()

    override suspend fun exists(id: String): Boolean = db.recordingDao().getOne(id) != null

    override suspend fun aggregate(id: String): LocalAggregate? = withContext(Dispatchers.IO) {
        val r = db.recordingDao().getOne(id) ?: return@withContext null
        if (r.status == RecordingStatus.WORKING) return@withContext null
        LocalAggregate(
            id = id,
            input = WebRecordingInput(
                title = r.title.ifBlank { "Rekaman" }.take(300), kind = r.kind.ifBlank { "Lainnya" }.take(100), createdAt = r.createdAt,
                durationSec = r.durationSec.coerceAtLeast(0), audioBlobSha256 = null, audioMimeType = r.audioMimeType.take(100),
                status = r.status.name, speakerCount = r.speakerCount.coerceAtLeast(0), engine = r.engine.name,
                templateId = r.templateId.take(100), templateName = r.templateName.take(200), summary = r.summary,
                provider = r.provider?.name, notesGeneratedAt = r.notesGeneratedAt, notulensiDensity = r.notulensiDensity?.name,
                locationName = r.locationName?.take(300), latitude = r.latitude, longitude = r.longitude, errorMessage = r.errorMessage,
            ),
            transcript = db.transcriptDao().list(id).map { WebSegment(WebIds.forSegment(id, it.orderIndex), it.orderIndex, it.speaker.take(200), it.timestampSec.coerceAtLeast(0), it.text) },
            sections = db.noteSectionDao().list(id).map {
                WebSection(WebIds.forSection(id, it.orderIndex), it.orderIndex, it.title.take(300), it.type.name, JsonParser.parseString(it.payload))
            },
            marks = db.recordingMarkDao().list(id).map { WebMark(markId(id, it), it.timestampSec.coerceAtLeast(0), it.label.take(300), it.createdAt) },
            chat = db.chatDao().list(id).map { WebChat(chatId(id, it), it.orderIndex, it.isUser, it.text, it.createdAt) },
            audioFile = r.audioFilePath?.let(::File)?.takeIf { it.exists() && it.length() > 0 },
            photos = db.photoDao().list(id).mapNotNull { p ->
                val f = File(p.filePath).takeIf { it.exists() && it.length() > 0 } ?: return@mapNotNull null
                LocalPhoto(photoId(id, p), f, p.takenAtSec, p.caption, p.createdAt)
            },
        )
    }

    private fun markId(recordingId: String, m: RecordingMarkEntity) = m.uuid ?: WebIds.forMark(recordingId, m.timestampSec.coerceAtLeast(0), m.label.take(300), m.createdAt)
    private fun chatId(recordingId: String, c: ChatMessageEntity) = c.uuid ?: WebIds.forChat(recordingId, c.orderIndex, c.isUser, c.createdAt)
    private fun photoId(recordingId: String, p: PhotoEntity) = p.uuid ?: WebIds.forPhoto(recordingId, p.takenAtSec, p.createdAt)

    override suspend fun hasAudio(id: String): Boolean =
        db.recordingDao().getOne(id)?.audioFilePath?.let { File(it).let { f -> f.exists() && f.length() > 0 } } == true

    override suspend fun hasPhoto(recordingId: String, photoId: String): Boolean =
        db.photoDao().list(recordingId).any { photoId(recordingId, it) == photoId }

    override suspend fun audioTarget(id: String, sha256: String, mimeType: String): File {
        val ext = when {
            mimeType.contains("mpeg") -> "mp3"
            mimeType.contains("ogg") -> "ogg"
            mimeType.contains("wav") -> "wav"
            mimeType.contains("webm") -> "webm"
            else -> "m4a"
        }
        return File(File(filesDir, "recordings").apply { mkdirs() }, "web_${id}_${sha256.take(8)}.$ext")
    }

    override suspend fun photoTarget(sha256: String): File = File(File(filesDir, "photos").apply { mkdirs() }, "web_$sha256.jpg")

    override suspend fun attachAudio(id: String, file: File) {
        if (!hasAudio(id)) db.recordingDao().setAudioPath(id, file.absolutePath)
    }

    override suspend fun applyServer(server: WebAggregate, audio: File?, photoFiles: Map<String, File>) {
        val s = server.recording
        val id = s.id
        db.withTransaction {
            val existing = db.recordingDao().getOne(id)
            if (existing?.status == RecordingStatus.WORKING) return@withTransaction          // the pipeline owns it right now
            // The server may be processing it itself (WORKING); the phone has no such state, so keep what it had.
            val status = when (s.status) {
                "DONE" -> RecordingStatus.DONE
                "RAW" -> RecordingStatus.RAW
                else -> existing?.status ?: RecordingStatus.RAW
            }
            val entity = RecordingEntity(
                id = id, title = s.title, kind = s.kind, createdAt = s.createdAt, durationSec = s.durationSec,
                audioFilePath = audio?.absolutePath ?: existing?.audioFilePath, audioMimeType = s.audioMimeType, status = status,
                speakerCount = s.speakerCount, engine = runCatching { WhisperEngine.valueOf(s.engine) }.getOrDefault(WhisperEngine.CLOUD),
                templateId = s.templateId, templateName = s.templateName, summary = s.summary,
                provider = s.provider?.let { runCatching { AiProvider.valueOf(it) }.getOrNull() },
                notesGeneratedAt = s.notesGeneratedAt, errorMessage = s.errorMessage,
                notulensiDensity = s.notulensiDensity?.let { runCatching { NotulensiDensity.valueOf(it) }.getOrNull() },
                locationName = s.locationName, latitude = s.latitude, longitude = s.longitude,
            )
            // update(), never REPLACE: replacing deletes the row first and the cascade would wipe its chat and history.
            if (existing == null) db.recordingDao().upsert(entity) else db.recordingDao().update(entity)

            if (server.transcript.segments.isNotEmpty()) {
                db.transcriptDao().clear(id)
                db.transcriptDao().insertSegments(
                    server.transcript.segments.sortedBy { it.orderIndex }.mapIndexed { i, g ->
                        TranscriptSegmentEntity(recordingId = id, orderIndex = i, speaker = g.speaker, timestampSec = g.timestampSec, text = g.text)
                    },
                )
            }
            if (server.notes.sections.isNotEmpty()) {
                db.noteSectionDao().replace(
                    id,
                    server.notes.sections.sortedBy { it.orderIndex }.mapIndexedNotNull { i, g ->
                        val type = runCatching { SectionType.valueOf(g.type) }.getOrNull() ?: return@mapIndexedNotNull null
                        NoteSectionEntity(recordingId = id, orderIndex = i, title = g.title, type = type, payload = g.payload.toString())
                    },
                )
            }

            mergeChildren(id, server, photoFiles)
        }
    }

    override suspend fun applyChildren(server: WebAggregate, photoFiles: Map<String, File>): Boolean = db.withTransaction {
        val id = server.recording.id
        val existing = db.recordingDao().getOne(id) ?: return@withTransaction false
        if (existing.status == RecordingStatus.WORKING) return@withTransaction false          // the pipeline owns it right now
        mergeChildren(id, server, photoFiles)
        true
    }

    /** Marks, chat and photos merged by id (web tombstones remove). Adds and removes only; never edits the recording itself. */
    private suspend fun mergeChildren(id: String, server: WebAggregate, photoFiles: Map<String, File>) {
        run {
            val marks = db.recordingMarkDao().list(id)
            val markIds = marks.associateBy { markId(id, it) }
            server.marks.filter { it.deletedAt == null && it.id !in markIds }.forEach {
                db.recordingMarkDao().insert(RecordingMarkEntity(recordingId = id, timestampSec = it.timestampSec, label = it.label, createdAt = it.createdAt, uuid = it.id))
            }
            server.marks.filter { it.deletedAt != null }.forEach { markIds[it.id]?.let { m -> db.recordingMarkDao().deleteById(m.id) } }

            val chatIds = db.chatDao().list(id).map { chatId(id, it) }.toSet()
            server.chat.filter { it.id !in chatIds }.forEach {
                db.chatDao().insert(ChatMessageEntity(recordingId = id, orderIndex = it.orderIndex, isUser = it.isUser, text = it.text, createdAt = it.createdAt, uuid = it.id))
            }

            val photoIds = db.photoDao().list(id).map { photoId(id, it) }.toSet()
            server.photos.filter { it.deletedAt == null && it.id !in photoIds }.forEach { p ->
                val file = photoFiles[p.id] ?: return@forEach
                db.photoDao().insert(PhotoEntity(recordingId = id, filePath = file.absolutePath, takenAtSec = p.takenAtSec, caption = p.caption, createdAt = p.createdAt, uuid = p.id))
            }
        }
    }

    override suspend fun categories(): List<LocalCategory> = db.categoryDao().list().map { LocalCategory(it.id, it.label, it.orderIndex) }

    override suspend fun addCategory(id: String, label: String, orderIndex: Int) {
        db.categoryDao().insert(CategoryEntity(id = id, label = label, orderIndex = orderIndex))      // IGNORE on a duplicate label
    }
}
