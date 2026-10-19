package com.inteldes.app.data.model

/**
 * Templates that ship with the app. A template is an ordered list of sections; the AI is asked
 * to fill each one following its [TemplateSection.instruction], so changing a template changes
 * the shape of the generated document without any code change.
 */
object BuiltInTemplates {
    const val STANDARD_ID = "builtin_standard"
    const val BERITA_ACARA_ID = "builtin_berita_acara"
    const val RINGKAS_ID = "builtin_ringkas"
    const val TINDAK_LANJUT_ID = "builtin_tindak_lanjut"

    val standard = NoteTemplate(
        id = STANDARD_ID,
        name = "Notulensi Standar",
        description = "Ringkasan, poin bahasan, keputusan, poin pertanyaan, dan action item.",
        builtIn = true,
        sections = listOf(
            TemplateSection("Ringkasan", SectionType.PARAGRAPH, "Ringkasan rapat 2-4 kalimat dalam bahasa Indonesia formal."),
            TemplateSection("Poin bahasan", SectionType.TOPICS, "Topik yang dibahas, urut waktu. Tiap topik: detik mulai dibahas, judul singkat 2-6 kata, dan ringkasan 1-2 kalimat."),
            TemplateSection("Keputusan", SectionType.DECISIONS, "Keputusan yang disepakati forum, satu kalimat lengkap per butir."),
            TemplateSection("Poin pertanyaan", SectionType.QUESTIONS, "Pertanyaan yang muncul namun belum atau baru sebagian terjawab, dengan status dan catatan konteks (siapa yang bertanya, kenapa belum tuntas)."),
            TemplateSection("Action item", SectionType.ACTIONS, "Tugas konkret beserta penanggung jawab (PIC) dan tenggat sebagaimana disebut dalam transkrip."),
        ),
    )

    val beritaAcara = NoteTemplate(
        id = BERITA_ACARA_ID,
        name = "Berita Acara Rapat",
        description = "Format resmi: pembukaan, peserta, uraian pembahasan, keputusan, tindak lanjut, penutup.",
        builtIn = true,
        sections = listOf(
            TemplateSection("Pembukaan", SectionType.PARAGRAPH, "Paragraf pembuka resmi bergaya berita acara: nama atau jenis rapat, hari, tanggal, tempat, dan waktu bila disebut dalam transkrip (jika tidak disebut tulis \"tidak disebutkan\"), pimpinan rapat, serta maksud dan tujuan rapat. Gunakan bahasa baku."),
            TemplateSection("Peserta", SectionType.BULLETS, "Daftar peserta atau pembicara yang teridentifikasi beserta jabatannya sebagaimana disebut dalam transkrip."),
            TemplateSection("Uraian pembahasan", SectionType.TOPICS, "Uraian tiap pokok pembahasan secara formal dan netral, urut waktu."),
            TemplateSection("Keputusan dan kesepakatan", SectionType.DECISIONS, "Seluruh keputusan dan kesepakatan rapat, satu kalimat resmi per butir."),
            TemplateSection("Tindak lanjut", SectionType.ACTIONS, "Tugas tindak lanjut beserta penanggung jawab dan tenggat."),
            TemplateSection("Penutup", SectionType.PARAGRAPH, "Paragraf penutup resmi: waktu rapat ditutup bila disebut, dan kalimat penutup bahwa berita acara ini dibuat dengan sebenarnya."),
        ),
    )

    val ringkas = NoteTemplate(
        id = RINGKAS_ID,
        name = "Ringkasan Singkat",
        description = "Satu paragraf ringkasan dan beberapa poin penting — cepat dibaca.",
        builtIn = true,
        sections = listOf(
            TemplateSection("Ringkasan", SectionType.PARAGRAPH, "Satu paragraf ringkasan rapat, maksimal 4 kalimat."),
            TemplateSection("Poin penting", SectionType.BULLETS, "3 sampai 7 poin terpenting dari rapat, masing-masing satu kalimat."),
        ),
    )

    val tindakLanjut = NoteTemplate(
        id = TINDAK_LANJUT_ID,
        name = "Fokus Tindak Lanjut",
        description = "Hanya keputusan dan action item — untuk rapat koordinasi cepat.",
        builtIn = true,
        sections = listOf(
            TemplateSection("Keputusan", SectionType.DECISIONS, "Keputusan yang disepakati, satu kalimat lengkap per butir."),
            TemplateSection("Action item", SectionType.ACTIONS, "Semua tugas beserta PIC dan tenggat yang disebut dalam transkrip."),
        ),
    )

    val all: List<NoteTemplate> = listOf(standard, beritaAcara, ringkas, tindakLanjut)

    fun byId(id: String): NoteTemplate? = all.firstOrNull { it.id == id }
}
