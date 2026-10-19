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
    val templateId: String,
    val templateName: String,
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

/** A photo attached to a recording; [takenAtSec] is the offset into the audio when it was added, if known. */
data class Photo(val id: Long, val filePath: String, val takenAtSec: Int?, val caption: String)

sealed interface SectionContent {
    data class Paragraph(val text: String) : SectionContent
    data class Bullets(val items: List<String>) : SectionContent
    data class Topics(val items: List<Topic>) : SectionContent
    data class Decisions(val items: List<Decision>) : SectionContent
    data class Questions(val items: List<Question>) : SectionContent
    data class Actions(val items: List<ActionItem>) : SectionContent
}

data class NoteSection(val title: String, val content: SectionContent)

/** [summary] is a short preview (Home list, Tanya AI context); the document itself is [sections]. */
data class Notulensi(
    val summary: String,
    val provider: AiProvider,
    val sections: List<NoteSection>,
)

data class TemplateSection(val title: String, val type: SectionType, val instruction: String)

data class NoteTemplate(
    val id: String,
    val name: String,
    val description: String,
    val sections: List<TemplateSection>,
    val builtIn: Boolean,
)
