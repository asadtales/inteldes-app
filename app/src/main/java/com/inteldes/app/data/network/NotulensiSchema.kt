package com.inteldes.app.data.network

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.inteldes.app.data.model.ActionItem
import com.inteldes.app.data.model.AiProvider
import com.inteldes.app.data.model.Decision
import com.inteldes.app.data.model.NoteSection
import com.inteldes.app.data.model.NoteTemplate
import com.inteldes.app.data.model.Notulensi
import com.inteldes.app.data.model.NotulensiDensity
import com.inteldes.app.data.model.OutputLanguage
import com.inteldes.app.data.model.Question
import com.inteldes.app.data.model.QuestionStatus
import com.inteldes.app.data.model.SectionContent
import com.inteldes.app.data.model.SectionType
import com.inteldes.app.data.model.Topic
import com.inteldes.app.data.model.TranscriptSegment

/** Property name the AI fills for the template's [index]-th section. */
fun sectionKey(index: Int): String = "section_${index + 1}"

private fun obj(build: JsonObject.() -> Unit) = JsonObject().apply(build)
private fun scalar(type: String, description: String) = obj { addProperty("type", type); addProperty("description", description) }
private fun arraySchema(items: JsonObject, description: String) =
    obj { addProperty("type", "array"); addProperty("description", description); add("items", items) }
private fun requiredNames(names: List<String>) = JsonArray().apply { names.forEach { add(it) } }

private fun topicItem() = obj {
    addProperty("type", "object")
    add(
        "properties",
        obj {
            add("timestampSec", scalar("integer", "Detik sejak awal rapat saat topik ini mulai dibahas"))
            add("title", scalar("string", "Judul singkat topik, 2-6 kata"))
            add("body", scalar("string", "Ringkasan 1-2 kalimat isi pembahasan topik ini"))
        },
    )
    add("required", requiredNames(listOf("timestampSec", "title", "body")))
}

private fun questionItem() = obj {
    addProperty("type", "object")
    add(
        "properties",
        obj {
            add("question", scalar("string", "Pertanyaan yang muncul namun belum atau sebagian terjawab"))
            add(
                "status",
                obj {
                    addProperty("type", "string")
                    add("enum", requiredNames(QuestionStatus.entries.map { it.label }))
                },
            )
            add("note", scalar("string", "Konteks singkat: siapa yang bertanya / kenapa belum tuntas"))
        },
    )
    add("required", requiredNames(listOf("question", "status", "note")))
}

private fun actionItem() = obj {
    addProperty("type", "object")
    add(
        "properties",
        obj {
            add("task", scalar("string", "Tugas konkret yang harus dikerjakan"))
            add("pic", scalar("string", "Nama penanggung jawab (PIC) yang disebut dalam transkrip"))
            add("due", scalar("string", "Tenggat dalam bahasa natural, mis. '12 Okt', bila disebut"))
            add("sourceTimestampSec", scalar("integer", "Detik pada transkrip tempat tugas ini disebutkan"))
        },
    )
    add("required", requiredNames(listOf("task", "pic", "due", "sourceTimestampSec")))
}

private fun schemaFor(type: SectionType, description: String): JsonObject = when (type) {
    SectionType.PARAGRAPH -> scalar("string", description)
    SectionType.BULLETS -> arraySchema(scalar("string", "Satu poin, satu kalimat"), description)
    SectionType.TOPICS -> arraySchema(topicItem(), description)
    SectionType.DECISIONS -> arraySchema(scalar("string", "Satu keputusan, kalimat lengkap"), description)
    SectionType.QUESTIONS -> arraySchema(questionItem(), description)
    SectionType.ACTIONS -> arraySchema(actionItem(), description)
}

/**
 * JSON Schema for one note: a short `summary` plus one property per template section, shaped by the
 * section's type. Both Gemini's `responseSchema` and Claude's tool `input_schema` accept this subset.
 */
fun notulensiJsonSchema(template: NoteTemplate): JsonObject {
    val properties = JsonObject()
    properties.add("summary", scalar("string", "Ringkasan rapat 1-3 kalimat, dipakai sebagai pratinjau singkat"))
    template.sections.forEachIndexed { i, s ->
        properties.add(sectionKey(i), schemaFor(s.type, "${s.title}: ${s.instruction}"))
    }
    val names = listOf("summary") + template.sections.indices.map { sectionKey(it) }
    return obj {
        addProperty("type", "object")
        add("properties", properties)
        add("required", requiredNames(names))
    }
}

/** Gemini's `responseSchema` uses upper-case type names (STRING, OBJECT, ...); everything else is shared. */
fun toGeminiSchema(schema: JsonObject): JsonObject {
    val copy = JsonObject()
    for ((key, value) in schema.entrySet()) {
        copy.add(key, upperCaseTypes(key, value))
    }
    return copy
}

private fun upperCaseTypes(key: String, element: JsonElement): JsonElement = when {
    key == "type" && element.isJsonPrimitive && element.asJsonPrimitive.isString ->
        JsonPrimitive(element.asString.uppercase())
    element.isJsonObject -> toGeminiSchema(element.asJsonObject)
    element.isJsonArray -> JsonArray().apply { element.asJsonArray.forEach { add(upperCaseTypes("", it)) } }
    else -> element
}

fun formatTimestamp(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return "%02d:%02d".format(m, s)
}

// ───────────────────────── parsing the AI's answer ─────────────────────────

private fun JsonElement?.textOrEmpty(): String =
    if (this != null && isJsonPrimitive) asString.trim() else ""

private fun JsonObject.str(key: String): String = get(key).textOrEmpty()

private fun JsonObject.int(key: String): Int = runCatching { get(key)?.asInt }.getOrNull() ?: 0

private fun JsonElement.objectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null

/** Reads a list of strings; tolerates a single multi-line string or `{ "text": ... }` objects. */
private fun textItems(el: JsonElement?): List<String> = when {
    el == null || el.isJsonNull -> emptyList()
    el.isJsonArray -> el.asJsonArray.mapNotNull { item ->
        when {
            item.isJsonPrimitive -> item.asString
            item.isJsonObject -> item.asJsonObject.str("text")
            else -> null
        }
    }.map { it.trim() }.filter { it.isNotEmpty() }
    el.isJsonPrimitive -> el.asString.lines().map { it.trim().trimStart('-', '•', '*').trim() }.filter { it.isNotEmpty() }
    else -> emptyList()
}

private fun questionStatus(raw: String): QuestionStatus {
    val s = raw.lowercase()
    return when {
        "sebagian" in s -> QuestionStatus.PARTIAL
        s == "terjawab" -> QuestionStatus.ANSWERED
        else -> QuestionStatus.UNANSWERED
    }
}

private fun parseContent(type: SectionType, el: JsonElement?): SectionContent {
    val objects = if (el != null && el.isJsonArray) el.asJsonArray.mapNotNull { it.objectOrNull() } else emptyList()
    return when (type) {
        SectionType.PARAGRAPH -> SectionContent.Paragraph(el.textOrEmpty())
        SectionType.BULLETS -> SectionContent.Bullets(textItems(el))
        SectionType.DECISIONS -> SectionContent.Decisions(
            textItems(el).mapIndexed { i, text -> Decision("%02d".format(i + 1), text) },
        )
        SectionType.TOPICS -> SectionContent.Topics(
            objects.map { o ->
                val sec = o.int("timestampSec")
                Topic(formatTimestamp(sec), sec, o.str("title"), o.str("body"))
            },
        )
        SectionType.QUESTIONS -> SectionContent.Questions(
            objects.map { o -> Question(o.str("question"), questionStatus(o.str("status")), o.str("note")) },
        )
        SectionType.ACTIONS -> SectionContent.Actions(
            objects.map { o ->
                ActionItem(
                    task = o.str("task"),
                    pic = o.str("pic").ifBlank { "Belum ditentukan" },
                    due = o.str("due").ifBlank { "Belum ditentukan" },
                    sourceTimestampLabel = formatTimestamp(o.int("sourceTimestampSec")),
                )
            },
        )
    }
}

/** Turns the model's JSON (shaped by [notulensiJsonSchema]) into a [Notulensi] laid out like [template]. */
fun parseNotulensi(root: JsonObject, template: NoteTemplate, provider: AiProvider): Notulensi =
    Notulensi(
        summary = root.str("summary"),
        provider = provider,
        sections = template.sections.mapIndexed { i, s ->
            NoteSection(s.title, parseContent(s.type, root.get(sectionKey(i))))
        },
    )

// ───────────────────────── prompt ─────────────────────────

fun buildTranscriptPrompt(
    meetingTitle: String,
    segments: List<TranscriptSegment>,
    density: NotulensiDensity,
    template: NoteTemplate,
    outputLanguage: OutputLanguage = OutputLanguage.INDONESIA,
    location: String? = null,
): String {
    val locationLine = location?.takeIf { it.isNotBlank() }?.let {
        "Lokasi rapat (dicatat perangkat): $it. Cantumkan lokasi ini pada dokumen bila template memintanya (mis. tempat pada pembukaan berita acara)."
    } ?: ""
    val transcriptText = segments.joinToString("\n") { "[${formatTimestamp(it.timestampSec)}] ${it.speaker}: ${it.text}" }
    val densityNote = when (density) {
        NotulensiDensity.RINGKAS -> "Gaya RINGKAS: hanya butir-butir paling penting pada tiap bagian, satu kalimat pendek per butir, tanpa konteks tambahan."
        NotulensiDensity.SEDANG -> "Gaya SEDANG: butir-butir utama pada tiap bagian, masing-masing disertai satu-dua kalimat konteks singkat."
        NotulensiDensity.DETAIL -> "Gaya DETAIL: sertakan sebanyak mungkin butir, konteks pendukung, dan kutipan singkat relevan pada tiap bagian."
    }
    val sectionList = template.sections.mapIndexed { i, s ->
        "${i + 1}. ${sectionKey(i)} — ${s.title} (${s.type.label}): ${s.instruction}"
    }.joinToString("\n")
    return """
        Kamu adalah asisten yang menyusun dokumen rapat dari transkrip mentah.
        Judul rapat: "$meetingTitle".
        Format dokumen: "${template.name}" — ${template.description}
        $locationLine
        $densityNote
        Tulis semua isi (summary, judul topik, teks) dalam ${outputLanguage.promptName}, apa pun bahasa transkrip aslinya.
        Isi field "summary" dengan ringkasan rapat 1-3 kalimat (dipakai sebagai pratinjau singkat).
        Lalu isi setiap bagian dokumen berikut sesuai panduannya:
        $sectionList

        Jangan mengarang nama, PIC, tenggat, tanggal, atau tempat yang tidak ada dasarnya di transkrip.
        Jika suatu hal tidak disebutkan, tulis "Belum ditentukan" atau "Tidak disebutkan"; jika sebuah bagian
        memang tidak ada isinya, kembalikan daftar kosong atau teks kosong untuk bagian itu.

        Transkrip (format "[mm:ss] Pembicara: ucapan"):
        $transcriptText
    """.trimIndent()
}
