package com.inteldes.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.inteldes.app.data.model.AiProvider
import com.inteldes.app.data.model.BuiltInTemplates
import com.inteldes.app.data.model.NotulensiDensity
import com.inteldes.app.data.model.RecordingStatus
import com.inteldes.app.data.model.SectionType
import com.inteldes.app.data.model.WhisperEngine
import java.util.UUID

/**
 * A saved "knowledge context": the merged text of several recordings' transcripts/notulensi, kept
 * (and freely editable) independently of those recordings so it survives them being trimmed or deleted.
 */
@Entity(tableName = "knowledge_contexts")
data class KnowledgeContextEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val text: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Which recordings fed a context — for display only; no foreign key to recordings on purpose. */
@Entity(
    tableName = "context_sources",
    foreignKeys = [ForeignKey(entity = KnowledgeContextEntity::class, parentColumns = ["id"], childColumns = ["contextId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("contextId")],
)
data class ContextSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contextId: String,
    val recordingId: String,
    val recordingTitle: String,
    val kind: String,
    val addedAt: Long,
)

@Entity(tableName = "categories", indices = [Index("label", unique = true)])
data class CategoryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val label: String,
    val orderIndex: Int,
)

@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val kind: String,
    val createdAt: Long,
    val durationSec: Int,
    val audioFilePath: String?,
    val audioMimeType: String = "audio/mp4",
    val status: RecordingStatus,
    val speakerCount: Int,
    val engine: WhisperEngine,
    val templateId: String = BuiltInTemplates.STANDARD_ID,
    val templateName: String = BuiltInTemplates.standard.name,
    val summary: String? = null,
    val provider: AiProvider? = null,
    val notesGeneratedAt: Long? = null,
    val errorMessage: String? = null,
    /** Density the *current* notulensi (above) was generated with — recorded so
     * regenerating with a different density can archive the outgoing one correctly. */
    val notulensiDensity: NotulensiDensity? = null,
    /** Where the meeting took place: free text (auto-filled from GPS, editable) plus the raw fix. */
    val locationName: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/** A user-tapped "mark this moment" bookmark, made during recording (or later on the
 * transcript timeline) — anchors a shortcut selection when transcribing only part of a
 * long recording. */
@Entity(
    tableName = "recording_marks",
    foreignKeys = [ForeignKey(entity = RecordingEntity::class, parentColumns = ["id"], childColumns = ["recordingId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("recordingId")],
)
data class RecordingMarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: String,
    val timestampSec: Int,
    val label: String,
    val createdAt: Long,
    /** Id the web server knows this mark by; null = derive it from the content (see WebIds). */
    val uuid: String? = null,
)

/**
 * A past notulensi generation, kept so switching density or template and regenerating doesn't
 * destroy the previous result — stored as one JSON snapshot (see NotulensiSnapshot in the
 * repository) rather than mirrored into its own section rows, since this is write-once/read-whole.
 */
@Entity(
    tableName = "notulensi_history",
    foreignKeys = [ForeignKey(entity = RecordingEntity::class, parentColumns = ["id"], childColumns = ["recordingId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("recordingId")],
)
data class NotulensiHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: String,
    val generatedAt: Long,
    val density: NotulensiDensity,
    val provider: AiProvider,
    val notulensiJson: String,
)

/** One generated section of a note; [payload] is JSON shaped by [type] (see [SectionCodec]). */
@Entity(
    tableName = "note_sections",
    foreignKeys = [ForeignKey(entity = RecordingEntity::class, parentColumns = ["id"], childColumns = ["recordingId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("recordingId")],
)
data class NoteSectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: String,
    val orderIndex: Int,
    val title: String,
    val type: SectionType,
    val payload: String,
)

/** User-created templates only; built-ins live in code ([BuiltInTemplates]). */
@Entity(tableName = "templates")
data class TemplateEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val sectionsJson: String,
    val createdAt: Long,
)

@Entity(
    tableName = "photos",
    foreignKeys = [ForeignKey(entity = RecordingEntity::class, parentColumns = ["id"], childColumns = ["recordingId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("recordingId")],
)
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: String,
    val filePath: String,
    val takenAtSec: Int?,
    val caption: String = "",
    val createdAt: Long,
    /** Id the web server knows this photo by; null = derive it from the content (see WebIds). */
    val uuid: String? = null,
)

@Entity(
    tableName = "transcript_segments",
    foreignKeys = [ForeignKey(entity = RecordingEntity::class, parentColumns = ["id"], childColumns = ["recordingId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("recordingId")],
)
data class TranscriptSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: String,
    val orderIndex: Int,
    val speaker: String,
    val timestampSec: Int,
    val text: String,
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [ForeignKey(entity = RecordingEntity::class, parentColumns = ["id"], childColumns = ["recordingId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("recordingId")],
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: String,
    val orderIndex: Int,
    val isUser: Boolean,
    val text: String,
    val createdAt: Long,
    /** Id the web server knows this message by; null = derive it from the content (see WebIds). */
    val uuid: String? = null,
)

/** What the phone and the web server last agreed on for one recording (see WebSyncEngine). No FK: it must outlive a local delete until the server is told. */
@Entity(tableName = "web_sync_state")
data class WebSyncStateEntity(
    @PrimaryKey val recordingId: String,
    val serverVersion: Int,
    val transcriptVersion: Int,
    val notesVersion: Int,
    val metaHash: String,
    val transcriptHash: String,
    val notesHash: String,
    val marksHash: String,
    val chatHash: String,
    val audioSha: String?,
    val audioLen: Long,
    val pushedMarksJson: String,
    val audioMissing: Boolean,
)
