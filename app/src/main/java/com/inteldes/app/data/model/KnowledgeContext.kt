package com.inteldes.app.data.model

enum class ContextSourceKind(val label: String) {
    NOTULENSI("Notulensi"),
    TRANSCRIPT("Transkrip"),
    BOTH("Notulensi + transkrip"),
}

data class ContextSource(val recordingId: String, val recordingTitle: String, val kind: ContextSourceKind)

data class KnowledgeContext(
    val id: String,
    val name: String,
    val text: String,
    val createdAt: Long,
    val updatedAt: Long,
    val sources: List<ContextSource> = emptyList(),
) {
    val wordCount: Int get() = text.split(Regex("\\s+")).count { it.isNotBlank() }
}
