package com.inteldes.app.data.model

enum class RecordingStatus { RAW, WORKING, DONE }

enum class RecordingKind(val label: String) {
    MUSDES("Musdes"), BPD("BPD"), BUMDES("BUMDes"), WARGA("Warga"), LAINNYA("Lainnya");

    companion object {
        fun fromLabel(label: String): RecordingKind = entries.firstOrNull { it.label == label } ?: LAINNYA
    }
}

enum class WhisperEngine { ON_DEVICE, CLOUD }

enum class AiProvider(val label: String) { AUTO("Otomatis (disarankan)"), GEMINI("Gemini 2.5 Pro"), CLAUDE("Claude Sonnet") }

enum class QuestionStatus(val label: String) {
    UNANSWERED("Belum terjawab"), PARTIAL("Terjawab sebagian"), ANSWERED("Terjawab");
}

/** How one section of a note template is shaped, and therefore rendered. */
enum class SectionType(val label: String) {
    PARAGRAPH("Paragraf"),
    BULLETS("Daftar poin"),
    TOPICS("Poin bahasan"),
    DECISIONS("Keputusan"),
    QUESTIONS("Pertanyaan"),
    ACTIONS("Action item"),
}

enum class ProcessingStep(val title: String, val desc: String) {
    PREP("Menyiapkan audio", "Normalisasi level dan pemotongan hening."),
    TRANSCRIBE("Transkrip Whisper", "Mengirim audio ke mesin transkrip yang dipilih."),
    DIARIZE("Memisahkan pembicara", "Label bisa diperbaiki manual."),
    SUMMARIZE("Menyusun dokumen", "Mengisi tiap bagian sesuai template yang dipilih."),
    LINK("Menautkan ke timestamp", "Tiap butir dikaitkan ke menit sumbernya."),
}
