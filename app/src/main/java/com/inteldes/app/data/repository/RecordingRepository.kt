package com.inteldes.app.data.repository

import com.inteldes.app.data.db.ActionItemEntity
import com.inteldes.app.data.db.ChatMessageEntity
import com.inteldes.app.data.db.DecisionEntity
import com.inteldes.app.data.db.IntelDesDatabase
import com.inteldes.app.data.db.QuestionEntity
import com.inteldes.app.data.db.RecordingEntity
import com.inteldes.app.data.db.TopicEntity
import com.inteldes.app.data.db.TranscriptSegmentEntity
import com.inteldes.app.data.db.toDomain
import com.inteldes.app.data.model.AiProvider
import com.inteldes.app.data.model.ChatMessage
import com.inteldes.app.data.model.Notulensi
import com.inteldes.app.data.model.Recording
import com.inteldes.app.data.model.RecordingKind
import com.inteldes.app.data.model.RecordingStatus
import com.inteldes.app.data.model.TranscriptSegment
import com.inteldes.app.data.model.WhisperEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/** Single source of truth for recordings + their derived notulensi/transcript/chat, backed by Room. */
class RecordingRepository(private val db: IntelDesDatabase) {

    fun observeRecordings(): Flow<List<Recording>> =
        db.recordingDao().observeAll().map { list -> list.map { it.toDomain() } }

    fun observeRecording(id: String): Flow<Recording?> =
        db.recordingDao().observeOne(id).map { it?.toDomain() }

    suspend fun getRecording(id: String): Recording? = db.recordingDao().getOne(id)?.toDomain()

    fun observeNotulensi(id: String): Flow<Notulensi?> {
        val topics = db.notesDao().observeTopics(id)
        val decisions = db.notesDao().observeDecisions(id)
        val questions = db.notesDao().observeQuestions(id)
        val actions = db.notesDao().observeActionItems(id)
        val recording = db.recordingDao().observeOne(id)
        return kotlinx.coroutines.flow.combine(topics, decisions, questions, actions, recording) { t, d, q, a, r ->
            if (r == null || r.summary == null || r.provider == null) return@combine null
            Notulensi(
                summary = r.summary,
                provider = r.provider,
                topics = t.map { it.toDomain() },
                decisions = d.map { it.toDomain() },
                questions = q.map { it.toDomain() },
                actions = a.map { it.toDomain() },
            )
        }
    }

    fun observeTranscript(id: String): Flow<List<TranscriptSegment>> =
        db.transcriptDao().observeSegments(id).map { list -> list.map { it.toDomain() } }

    fun observeChat(id: String): Flow<List<ChatMessage>> =
        db.chatDao().observeMessages(id).map { list -> list.map { it.toDomain() } }

    suspend fun renameSpeaker(recordingId: String, oldName: String, newName: String) {
        if (newName.isBlank() || oldName == newName) return
        db.transcriptDao().renameSpeaker(recordingId, oldName, newName)
    }

    fun observeDueActionItems(): Flow<List<Pair<Recording, com.inteldes.app.data.model.ActionItem>>> =
        kotlinx.coroutines.flow.combine(
            db.recordingDao().observeAll(),
            db.notesDao().observeAllActionItems(),
        ) { recs, actions ->
            val byId = recs.associateBy { it.id }
            actions.filter { !it.done }.mapNotNull { a -> byId[a.recordingId]?.let { it.toDomain() to a.toDomain() } }
        }

    suspend fun createRecording(
        title: String,
        kind: RecordingKind,
        durationSec: Int,
        audioFilePath: String?,
        audioMimeType: String = "audio/mp4",
        engine: WhisperEngine,
    ): String {
        val id = UUID.randomUUID().toString()
        db.recordingDao().upsert(
            RecordingEntity(
                id = id, title = title, kind = kind, createdAt = System.currentTimeMillis(),
                durationSec = durationSec, audioFilePath = audioFilePath, audioMimeType = audioMimeType,
                status = RecordingStatus.RAW, speakerCount = 0, engine = engine,
            ),
        )
        return id
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
        db.notesDao().replaceNotes(
            recordingId = id,
            topics = notulensi.topics.mapIndexed { i, t -> TopicEntity(recordingId = id, orderIndex = i, timestampLabel = t.timestampLabel, timestampSec = t.timestampSec, title = t.title, body = t.body) },
            decisions = notulensi.decisions.mapIndexed { i, d -> DecisionEntity(recordingId = id, orderIndex = i, text = d.text) },
            questions = notulensi.questions.mapIndexed { i, q -> QuestionEntity(recordingId = id, orderIndex = i, question = q.question, status = q.status, note = q.note) },
            actions = notulensi.actions.mapIndexed { i, a -> ActionItemEntity(recordingId = id, orderIndex = i, task = a.task, pic = a.pic, due = a.due, sourceTimestampLabel = a.sourceTimestampLabel, done = a.done) },
        )
        db.recordingDao().getOne(id)?.let {
            db.recordingDao().update(it.copy(status = RecordingStatus.DONE, summary = notulensi.summary, provider = provider, notesGeneratedAt = System.currentTimeMillis(), errorMessage = null))
        }
    }

    suspend fun addChatMessage(recordingId: String, isUser: Boolean, text: String) {
        val count = db.chatDao().count(recordingId)
        db.chatDao().insert(ChatMessageEntity(recordingId = recordingId, orderIndex = count, isUser = isUser, text = text, createdAt = System.currentTimeMillis()))
    }
}
