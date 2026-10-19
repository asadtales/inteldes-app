package com.inteldes.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.inteldes.app.data.model.AiProvider
import com.inteldes.app.data.model.NotulensiDensity
import com.inteldes.app.data.model.QuestionStatus
import com.inteldes.app.data.model.RecordingStatus
import com.inteldes.app.data.model.WhisperEngine
import java.util.UUID

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
    val summary: String? = null,
    val provider: AiProvider? = null,
    val notesGeneratedAt: Long? = null,
    val errorMessage: String? = null,
    /** Density the *current* notulensi (above) was generated with — recorded so
     * regenerating with a different density can archive the outgoing one correctly. */
    val notulensiDensity: NotulensiDensity? = null,
)

/**
 * A past notulensi generation, kept so switching density (Ringkas/Sedang/Detail) and
 * regenerating doesn't destroy the previous result — stored as one JSON blob rather than
 * mirroring topics/decisions/questions/actions into their own history tables, since this
 * is write-once/read-whole and never queried by its internals.
 */
/** A user-tapped "mark this moment" bookmark, made during recording (or later on the
 * transcript timeline) — anchors a shortcut selection when transcribing only part of a
 * long recording (see [NotulensiHistoryEntity] siblings in the repository layer). */
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
)

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

@Entity(
    tableName = "topics",
    foreignKeys = [ForeignKey(entity = RecordingEntity::class, parentColumns = ["id"], childColumns = ["recordingId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("recordingId")],
)
data class TopicEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: String,
    val orderIndex: Int,
    val timestampLabel: String,
    val timestampSec: Int,
    val title: String,
    val body: String,
)

@Entity(
    tableName = "decisions",
    foreignKeys = [ForeignKey(entity = RecordingEntity::class, parentColumns = ["id"], childColumns = ["recordingId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("recordingId")],
)
data class DecisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: String,
    val orderIndex: Int,
    val text: String,
)

@Entity(
    tableName = "questions",
    foreignKeys = [ForeignKey(entity = RecordingEntity::class, parentColumns = ["id"], childColumns = ["recordingId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("recordingId")],
)
data class QuestionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: String,
    val orderIndex: Int,
    val question: String,
    val status: QuestionStatus,
    val note: String,
)

@Entity(
    tableName = "action_items",
    foreignKeys = [ForeignKey(entity = RecordingEntity::class, parentColumns = ["id"], childColumns = ["recordingId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("recordingId")],
)
data class ActionItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: String,
    val orderIndex: Int,
    val task: String,
    val pic: String,
    val due: String,
    val sourceTimestampLabel: String,
    val done: Boolean = false,
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
)
