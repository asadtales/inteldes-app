package com.inteldes.app.data.network

import com.inteldes.app.data.model.OutputLanguage

/** Prompt text for the Konteks screen; kept apart from the clients so it can be unit-tested. */
object ContextPrompts {
    /** Revising returns the whole document, so refuse contexts that can't plausibly come back in one answer. */
    const val MAX_REVISE_CHARS = 40_000

    fun askSystem(name: String, context: String, language: OutputLanguage): String = """
        Kamu asisten yang menjawab pertanyaan berdasarkan satu KONTEKS tersimpan bernama "$name": gabungan notulensi/transkrip dari beberapa rapat.
        Jawab dalam ${language.promptName}, ringkas dan jelas. Sebutkan rapat sumbernya (judul/tanggal) bila relevan.
        Jika jawabannya tidak ada di konteks, katakan terus terang bahwa tidak ditemukan — jangan mengarang.

        ===== KONTEKS =====
        $context
        ===== AKHIR KONTEKS =====
    """.trimIndent()

    fun reviseSystem(name: String, language: OutputLanguage): String = """
        Kamu mengedit dokumen konteks bernama "$name" (gabungan notulensi/transkrip beberapa rapat).
        Terapkan instruksi pengguna pada dokumen, lalu kembalikan SELURUH dokumen hasil revisi dalam ${language.promptName}.
        Aturan: hanya ubah bagian yang diminta; pertahankan struktur dan format Markdown (judul ##, ###, daftar); jangan menambah fakta baru
        yang tidak ada di dokumen; jangan menulis komentar, pembuka, atau penutup — keluarkan dokumennya saja.
    """.trimIndent()

    fun reviseMessage(context: String, instruction: String): String =
        "INSTRUKSI:\n$instruction\n\n===== DOKUMEN =====\n$context\n===== AKHIR DOKUMEN ====="

    /** Models sometimes wrap a whole document in a ```markdown fence; drop it. */
    fun stripFences(text: String): String {
        val t = text.trim()
        val m = Regex("^```[a-zA-Z]*\\n([\\s\\S]*?)\\n```$").matchEntire(t)
        return (m?.groupValues?.get(1) ?: t).trim()
    }
}
