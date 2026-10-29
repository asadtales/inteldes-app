package com.inteldes.app.data.sync

import android.content.Context
import androidx.room.withTransaction
import com.inteldes.app.data.db.CategoryEntity
import com.inteldes.app.data.db.IntelDesDatabase
import com.inteldes.app.data.db.NoteSectionEntity
import com.inteldes.app.data.db.PhotoEntity
import com.inteldes.app.data.db.RecordingEntity
import com.inteldes.app.data.db.RecordingMarkEntity
import com.inteldes.app.data.db.TranscriptSegmentEntity
import com.inteldes.app.data.export.NotulensiExporter
import com.inteldes.app.data.model.AiProvider
import com.inteldes.app.data.model.NotulensiDensity
import com.inteldes.app.data.model.RecordingStatus
import com.inteldes.app.data.model.SectionType
import com.inteldes.app.data.model.WhisperEngine
import com.inteldes.app.data.repository.RecordingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** [SyncLocal] backed by the Room database and the app's files directory. */
class RoomSyncLocal(
    context: Context,
    private val db: IntelDesDatabase,
    private val repository: RecordingRepository,
) : SyncLocal {
    private val filesDir = context.filesDir
    private val cacheDir = context.cacheDir

    override suspend fun recordingIds(): List<String> = db.recordingDao().observeAll().first().map { it.id }

    override suspend fun snapshot(id: String): LocalSnapshot? = withContext(Dispatchers.IO) {
        val r = db.recordingDao().getOne(id) ?: return@withContext null
        if (r.status == RecordingStatus.WORKING) return@withContext null

        val audio = r.audioFilePath?.let(::File)?.takeIf { it.exists() && it.length() > 0 }
        val audioName = audio?.let { SyncNames.audio(it.extension.ifBlank { "m4a" }) }

        val photoFiles = linkedMapOf<String, File>()
        val photos = db.photoDao().observe(id).first().mapNotNull { p ->
            val f = File(p.filePath).takeIf { it.exists() } ?: return@mapNotNull null
            val name = SyncNames.photo(sha1Prefix(f))
            photoFiles[name] = f
            PhotoJson(name, p.takenAtSec, p.caption, p.createdAt)
        }.distinctBy { it.name }

        val snapshot = RecordingSnapshot(
            id = r.id, title = r.title, kind = r.kind, createdAt = r.createdAt, durationSec = r.durationSec,
            audioMimeType = r.audioMimeType, status = r.status.name, speakerCount = r.speakerCount, engine = r.engine.name,
            templateId = r.templateId, templateName = r.templateName, summary = r.summary, provider = r.provider?.name,
            notesGeneratedAt = r.notesGeneratedAt, notulensiDensity = r.notulensiDensity?.name,
            locationName = r.locationName, latitude = r.latitude, longitude = r.longitude, audioName = audioName,
            transcript = db.transcriptDao().observeSegments(id).first().map { SegmentJson(it.speaker, it.timestampSec, it.text) },
            sections = db.noteSectionDao().observe(id).first().map { SectionJson(it.title, it.type.name, it.payload) },
            marks = db.recordingMarkDao().observeForRecording(id).first().map { MarkJson(it.timestampSec, it.label, it.createdAt) },
            photos = photos,
        )
        LocalSnapshot(snapshot, audio, photoFiles)
    }

    override suspend fun audioTarget(id: String, name: String): File =
        File(File(filesDir, "recordings").apply { mkdirs() }, "sync_$id.${name.substringAfterLast('.', "m4a")}")

    /** Word notulensi and plain-text transcript for the Drive folder, generated into the cache dir. */
    override suspend fun documents(id: String): List<DocFile> = withContext(Dispatchers.IO) {
        val recording = repository.getRecording(id) ?: return@withContext emptyList()
        val dir = File(cacheDir, "sync_docs").apply { mkdirs() }
        val docs = mutableListOf<DocFile>()
        repository.observeNotulensi(id).first()?.let { note ->
            val file = File(dir, "Notulensi_$id.docx")
            NotulensiExporter.writeDocx(file, recording, note, repository.observePhotos(id).first())
            docs += DocFile(SyncNames.NOTULENSI_DOC, SyncNames.DOCX_MIME, file)
        }
        val transcript = repository.observeTranscript(id).first()
        if (transcript.isNotEmpty()) {
            val file = File(dir, "Transkrip_$id.txt")
            file.writeText(NotulensiExporter.buildTranscriptText(recording, transcript))
            docs += DocFile(SyncNames.TRANSCRIPT_DOC, "text/plain", file)
        }
        docs
    }

    override suspend fun photoTarget(name: String): File = File(File(filesDir, "photos").apply { mkdirs() }, name)

    override suspend fun apply(snapshot: RecordingSnapshot, audioFile: File?, photoFiles: Map<String, File>) {
        val id = snapshot.id
        db.withTransaction {
            db.recordingDao().upsert(
                RecordingEntity(
                    id = id, title = snapshot.title, kind = snapshot.kind, createdAt = snapshot.createdAt,
                    durationSec = snapshot.durationSec, audioFilePath = audioFile?.absolutePath,
                    audioMimeType = snapshot.audioMimeType,
                    status = runCatching { RecordingStatus.valueOf(snapshot.status) }.getOrDefault(RecordingStatus.RAW),
                    speakerCount = snapshot.speakerCount,
                    engine = runCatching { WhisperEngine.valueOf(snapshot.engine) }.getOrDefault(WhisperEngine.CLOUD),
                    templateId = snapshot.templateId, templateName = snapshot.templateName,
                    summary = snapshot.summary,
                    provider = snapshot.provider?.let { runCatching { AiProvider.valueOf(it) }.getOrNull() },
                    notesGeneratedAt = snapshot.notesGeneratedAt,
                    notulensiDensity = snapshot.notulensiDensity?.let { runCatching { NotulensiDensity.valueOf(it) }.getOrNull() },
                    locationName = snapshot.locationName, latitude = snapshot.latitude, longitude = snapshot.longitude,
                ),
            )
            db.transcriptDao().clear(id)
            db.transcriptDao().insertSegments(
                snapshot.transcript.mapIndexed { i, s -> TranscriptSegmentEntity(recordingId = id, orderIndex = i, speaker = s.speaker, timestampSec = s.timestampSec, text = s.text) },
            )
            db.noteSectionDao().replace(
                id,
                snapshot.sections.mapIndexedNotNull { i, s ->
                    val type = runCatching { SectionType.valueOf(s.type) }.getOrNull() ?: return@mapIndexedNotNull null
                    NoteSectionEntity(recordingId = id, orderIndex = i, title = s.title, type = type, payload = s.payload)
                },
            )
            db.recordingMarkDao().clear(id)
            db.recordingMarkDao().insertAll(snapshot.marks.map { RecordingMarkEntity(recordingId = id, timestampSec = it.timestampSec, label = it.label, createdAt = it.createdAt) })
            db.photoDao().clear(id)
            snapshot.photos.forEach { p ->
                val f = photoFiles[p.name] ?: return@forEach
                db.photoDao().insert(PhotoEntity(recordingId = id, filePath = f.absolutePath, takenAtSec = p.takenAtSec, caption = p.caption, createdAt = p.createdAt))
            }
        }
    }

    override suspend fun delete(id: String) = repository.deleteRecording(id)

    override suspend fun categories(): List<String> = db.categoryDao().observeAll().first().map { it.label }

    override suspend fun addCategories(labels: List<String>) {
        val start = db.categoryDao().observeAll().first().size
        labels.forEachIndexed { i, label -> db.categoryDao().insert(CategoryEntity(label = label, orderIndex = start + i)) }
    }

    private fun sha1Prefix(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(12)
    }
}
