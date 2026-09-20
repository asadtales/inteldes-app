package com.inteldes.app.data.model

data class Recording(
    val id: String,
    val title: String,
    val kind: RecordingKind,
    val createdAt: Long,
    val durationSec: Int,
    val audioFilePath: String?,
    val audioMimeType: String,
    val status: RecordingStatus,
    val speakerCount: Int,
    val engine: WhisperEngine,
    val summary: String?,
    val provider: AiProvider?,
    val errorMessage: String?,
)

data class Topic(val timestampLabel: String, val timestampSec: Int, val title: String, val body: String)
data class Decision(val number: String, val text: String)
data class Question(val question: String, val status: QuestionStatus, val note: String)
data class ActionItem(val task: String, val pic: String, val due: String, val sourceTimestampLabel: String, val done: Boolean = false)
data class TranscriptSegment(val speaker: String, val timestampSec: Int, val text: String)
data class ChatMessage(val isUser: Boolean, val text: String, val createdAt: Long)

data class Notulensi(
    val summary: String,
    val provider: AiProvider,
    val topics: List<Topic>,
    val decisions: List<Decision>,
    val questions: List<Question>,
    val actions: List<ActionItem>,
)
