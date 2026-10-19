package com.inteldes.app.data.model

data class Recording(
    val id: String,
    val title: String,
    val kind: String,
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
data class RecordingMark(val id: Long, val timestampSec: Int, val label: String)

data class Notulensi(
    val summary: String,
    val provider: AiProvider,
    val topics: List<Topic>,
    val decisions: List<Decision>,
    val questions: List<Question>,
    val actions: List<ActionItem>,
)

/** A past notulensi generation, kept when regenerating with a different density so the
 * outgoing result isn't just lost — viewable and comparable against the current one. */
data class NotulensiHistoryEntry(
    val id: Long,
    val generatedAt: Long,
    val density: NotulensiDensity,
    val notulensi: Notulensi,
)
