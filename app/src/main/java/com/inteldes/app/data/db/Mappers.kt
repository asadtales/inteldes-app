package com.inteldes.app.data.db

import com.inteldes.app.data.model.ActionItem
import com.inteldes.app.data.model.ChatMessage
import com.inteldes.app.data.model.Decision
import com.inteldes.app.data.model.Question
import com.inteldes.app.data.model.Recording
import com.inteldes.app.data.model.Topic
import com.inteldes.app.data.model.TranscriptSegment

fun RecordingEntity.toDomain() = Recording(
    id = id, title = title, kind = kind, createdAt = createdAt, durationSec = durationSec,
    audioFilePath = audioFilePath, audioMimeType = audioMimeType, status = status, speakerCount = speakerCount, engine = engine,
    summary = summary, provider = provider, errorMessage = errorMessage,
)

fun TopicEntity.toDomain() = Topic(timestampLabel, timestampSec, title, body)
fun DecisionEntity.toDomain() = Decision(orderIndex.let { "%02d".format(it + 1) }, text)
fun QuestionEntity.toDomain() = Question(question, status, note)
fun ActionItemEntity.toDomain() = ActionItem(task, pic, due, sourceTimestampLabel, done)
fun TranscriptSegmentEntity.toDomain() = TranscriptSegment(speaker, timestampSec, text)
fun ChatMessageEntity.toDomain() = ChatMessage(isUser, text, createdAt)
