package com.inteldes.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.inteldes.app.data.model.AiProvider
import com.inteldes.app.data.model.BuiltInTemplates
import com.inteldes.app.data.model.RecordingKind
import com.inteldes.app.data.model.RecordingStatus
import com.inteldes.app.data.model.SectionType
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
    val templateId: String = BuiltInTemplates.STANDARD_ID,
    val templateName: String = BuiltInTemplates.standard.name,
    val summary: String? = null,
    val provider: AiProvider? = null,
    val notesGeneratedAt: Long? = null,
    val errorMessage: String? = null,
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
