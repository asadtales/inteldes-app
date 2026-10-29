package com.inteldes.app.data.db

import com.inteldes.app.data.model.ChatMessage
import com.inteldes.app.data.model.NoteSection
import com.inteldes.app.data.model.Photo
import com.inteldes.app.data.model.Recording
import com.inteldes.app.data.model.RecordingMark
import com.inteldes.app.data.model.TranscriptSegment

/** Rows written before custom categories existed stored the enum constant name (e.g. "BUMDES"); show the proper label for those. */
private val LEGACY_KIND_LABELS = mapOf("MUSDES" to "Musdes", "BPD" to "BPD", "BUMDES" to "BUMDes", "WARGA" to "Warga", "LAINNYA" to "Lainnya")

fun RecordingEntity.toDomain() = Recording(
    id = id, title = title, kind = LEGACY_KIND_LABELS[kind] ?: kind, createdAt = createdAt, durationSec = durationSec,
    audioFilePath = audioFilePath, audioMimeType = audioMimeType, status = status, speakerCount = speakerCount,
    engine = engine, templateId = templateId, templateName = templateName,
    summary = summary, provider = provider, errorMessage = errorMessage,
    locationName = locationName, latitude = latitude, longitude = longitude,
)

fun NoteSectionEntity.toDomain() = NoteSection(title, SectionCodec.decode(type, payload))
fun PhotoEntity.toDomain() = Photo(id, filePath, takenAtSec, caption)
fun TranscriptSegmentEntity.toDomain() = TranscriptSegment(speaker, timestampSec, text)
fun ChatMessageEntity.toDomain() = ChatMessage(isUser, text, createdAt)
fun RecordingMarkEntity.toDomain() = RecordingMark(id, timestampSec, label)
