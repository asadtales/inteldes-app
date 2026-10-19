package com.inteldes.app.data.model

enum class RecordingStatus { RAW, WORKING, DONE }

/** Seeded once on first launch; users can add their own categories afterwards. */
val DEFAULT_CATEGORIES = listOf("Musdes", "BPD", "BUMDes", "Warga")

enum class WhisperEngine { ON_DEVICE, CLOUD }

enum class AiProvider(val label: String) { AUTO("Otomatis (disarankan)"), GEMINI("Gemini 2.5 Pro"), CLAUDE("Claude Sonnet") }

enum class OutputLanguage(val label: String, val promptName: String) {
    INDONESIA("Indonesia", "Bahasa Indonesia formal"),
    ENGLISH("English", "formal English"),
}

enum class NotulensiDensity(val label: String, val desc: String) {
    RINGKAS("Ringkas", "Poin-poin inti saja, tanpa detail pendukung."),
    SEDANG("Sedang", "Poin utama disertai konteks singkat."),
    DETAIL("Detail", "Selengkap mungkin, termasuk detail pendukung dan kutipan."),
}

enum class QuestionStatus(val label: String) {
    UNANSWERED("Belum terjawab"), PARTIAL("Terjawab sebagian"), ANSWERED("Terjawab");
}

enum class ProcessingStep(val title: String, val desc: String) {
    PREP("Menyiapkan audio", "Normalisasi level dan pemotongan hening."),
    TRANSCRIBE("Transkrip Whisper", "Mengirim audio ke mesin transkrip yang dipilih."),
    DIARIZE("Memisahkan pembicara", "Label bisa diperbaiki manual."),
    SUMMARIZE("Menyusun notulensi", "Poin bahasan, keputusan, poin pertanyaan, action item."),
    LINK("Menautkan ke timestamp", "Tiap butir dikaitkan ke menit sumbernya."),
}
