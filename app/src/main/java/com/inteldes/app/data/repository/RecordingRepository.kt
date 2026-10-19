package com.inteldes.app.data.repository

import com.google.gson.Gson
import com.inteldes.app.data.audio.AudioTrimmer
import com.inteldes.app.ui.util.formatMmSs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import com.inteldes.app.data.db.ActionItemEntity
import com.inteldes.app.data.db.CategoryEntity
import com.inteldes.app.data.db.ChatMessageEntity
import com.inteldes.app.data.db.DecisionEntity
import com.inteldes.app.data.db.IntelDesDatabase
import com.inteldes.app.data.db.NotulensiHistoryEntity
import com.inteldes.app.data.db.QuestionEntity
import com.inteldes.app.data.db.RecordingEntity
import com.inteldes.app.data.db.RecordingMarkEntity
import com.inteldes.app.data.db.TopicEntity
import com.inteldes.app.data.db.TranscriptSegmentEntity
import com.inteldes.app.data.db.toDomain
import com.inteldes.app.data.model.AiProvider
import com.inteldes.app.data.model.ChatMessage
import com.inteldes.app.data.model.DEFAULT_CATEGORIES
import com.inteldes.app.data.model.NotulensiDensity
import com.inteldes.app.data.model.Notulensi
import com.inteldes.app.data.model.NotulensiHistoryEntry
import com.inteldes.app.data.model.Recording
import com.inteldes.app.data.model.RecordingMark
import com.inteldes.app.data.model.RecordingStatus
import com.inteldes.app.data.model.TranscriptSegment
import com.inteldes.app.data.model.WhisperEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val historyGson = Gson()

/** Single source of truth for recordings + their derived notulensi/transcript/chat, backed by Room. */
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
        kind: String,
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

    suspend fun setEngine(id: String, engine: WhisperEngine) {
        db.recordingDao().getOne(id)?.let { db.recordingDao().update(it.copy(engine = engine)) }
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
        // Archive whatever notulensi is about to be overwritten — regenerating (e.g. to try
        // a different density) shouldn't destroy the previous result, since the whole point
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
                        notulensiJson = historyGson.toJson(previousNotulensi),
                    ),
                )
            }
        }

        db.notesDao().replaceNotes(
            recordingId = id,
            topics = notulensi.topics.mapIndexed { i, t -> TopicEntity(recordingId = id, orderIndex = i, timestampLabel = t.timestampLabel, timestampSec = t.timestampSec, title = t.title, body = t.body) },
            decisions = notulensi.decisions.mapIndexed { i, d -> DecisionEntity(recordingId = id, orderIndex = i, text = d.text) },
            questions = notulensi.questions.mapIndexed { i, q -> QuestionEntity(recordingId = id, orderIndex = i, question = q.question, status = q.status, note = q.note) },
            actions = notulensi.actions.mapIndexed { i, a -> ActionItemEntity(recordingId = id, orderIndex = i, task = a.task, pic = a.pic, due = a.due, sourceTimestampLabel = a.sourceTimestampLabel, done = a.done) },
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
            list.map { NotulensiHistoryEntry(it.id, it.generatedAt, it.density, historyGson.fromJson(it.notulensiJson, Notulensi::class.java)) }
        }

    suspend fun addChatMessage(recordingId: String, isUser: Boolean, text: String) {
        val count = db.chatDao().count(recordingId)
        db.chatDao().insert(ChatMessageEntity(recordingId = recordingId, orderIndex = count, isUser = isUser, text = text, createdAt = System.currentTimeMillis()))
    }

    /**
     * "Potong & simpan": copies [startSec, endSec) of [source]'s audio into a brand-new
     * recording (new file, new row), carrying over the marks that fall inside the range
     * (shifted so they line up with the clip). The source recording and its file are only
     * read, never modified — deleting either one later doesn't affect the other.
     */
    suspend fun createTrimmedRecording(source: Recording, startSec: Int, endSec: Int, outputDir: File): String =
        withContext(Dispatchers.IO) {
            val sourcePath = source.audioFilePath ?: throw IOException("Rekaman ini tidak punya berkas audio.")
            val sourceFile = File(sourcePath)
            if (!sourceFile.exists()) throw IOException("Berkas audio rekaman ini tidak ditemukan di perangkat.")
            outputDir.mkdirs()
            val dest = File(outputDir, "trim_${System.currentTimeMillis()}.m4a")
            AudioTrimmer.trim(sourceFile, dest, startSec, endSec)

            val id = createRecording(
                title = "${source.title} (potongan ${formatMmSs(startSec)}–${formatMmSs(endSec)})",
                kind = source.kind,
                durationSec = endSec - startSec,
                audioFilePath = dest.absolutePath,
                audioMimeType = "audio/mp4",
                engine = source.engine,
            )
            val now = System.currentTimeMillis()
            db.recordingMarkDao().observeForRecording(source.id).first()
                .filter { it.timestampSec in startSec..endSec }
                .forEach {
                    db.recordingMarkDao().insert(
                        RecordingMarkEntity(recordingId = id, timestampSec = it.timestampSec - startSec, label = it.label, createdAt = now),
                    )
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

    suspend fun deleteMark(mark: RecordingMark, recordingId: String) {
        db.recordingMarkDao().delete(RecordingMarkEntity(id = mark.id, recordingId = recordingId, timestampSec = mark.timestampSec, label = mark.label, createdAt = 0))
    }

    /** Deletes the recording row (cascades to its transcript/notulensi/chat) and its audio file. */
    suspend fun deleteRecording(id: String) {
        val entity = db.recordingDao().getOne(id) ?: return
        entity.audioFilePath?.let { java.io.File(it).delete() }
        db.recordingDao().delete(entity)
    }
}
