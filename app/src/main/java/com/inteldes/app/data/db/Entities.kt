package com.inteldes.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.inteldes.app.data.model.AiProvider
import com.inteldes.app.data.model.QuestionStatus
import com.inteldes.app.data.model.RecordingKind
import com.inteldes.app.data.model.RecordingStatus
import com.inteldes.app.data.model.WhisperEngine
import java.util.UUID

@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val kind: RecordingKind,
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
