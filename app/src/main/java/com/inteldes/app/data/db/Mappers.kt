package com.inteldes.app.data.db

import com.inteldes.app.data.model.ChatMessage
import com.inteldes.app.data.model.NoteSection
import com.inteldes.app.data.model.Photo
import com.inteldes.app.data.model.Recording
import com.inteldes.app.data.model.TranscriptSegment

fun RecordingEntity.toDomain() = Recording(
    id = id, title = title, kind = kind, createdAt = createdAt, durationSec = durationSec,
    audioFilePath = audioFilePath, audioMimeType = audioMimeType, status = status, speakerCount = speakerCount,
    engine = engine, templateId = templateId, templateName = templateName,
    summary = summary, provider = provider, errorMessage = errorMessage,
)

fun NoteSectionEntity.toDomain() = NoteSection(title, SectionCodec.decode(type, payload))
fun PhotoEntity.toDomain() = Photo(id, filePath, takenAtSec, caption)
fun TranscriptSegmentEntity.toDomain() = TranscriptSegment(speaker, timestampSec, text)
fun ChatMessageEntity.toDomain() = ChatMessage(isUser, text, createdAt)
