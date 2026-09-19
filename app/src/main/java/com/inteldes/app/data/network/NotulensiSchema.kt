package com.inteldes.app.data.network

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.inteldes.app.data.model.ActionItem
import com.inteldes.app.data.model.AiProvider
import com.inteldes.app.data.model.Decision
import com.inteldes.app.data.model.Notulensi
import com.inteldes.app.data.model.Question
import com.inteldes.app.data.model.QuestionStatus
import com.inteldes.app.data.model.Topic
import com.inteldes.app.data.model.TranscriptSegment

/**
 * One JSON Schema (JSON-Schema-ish; a subset both Gemini's `responseSchema` and
 * Claude's tool `input_schema` accept) describing the notulensi shape the prototype
 * renders: ringkasan, poin bahasan, keputusan, poin pertanyaan, action item.
 */
fun notulensiJsonSchema(): JsonObject {
    fun obj(build: JsonObject.() -> Unit) = JsonObject().apply(build)
    fun strProp(desc: String) = obj { addProperty("type", "string"); addProperty("description", desc) }
    fun intProp(desc: String) = obj { addProperty("type", "integer"); addProperty("description", desc) }

    val topicItem = obj {
        addProperty("type", "object")
        add(
            "properties",
            obj {
                add("timestampSec", intProp("Detik sejak awal rapat saat topik ini mulai dibahas"))
                add("title", strProp("Judul singkat topik, 2-6 kata"))
                add("body", strProp("Ringkasan 1-2 kalimat isi pembahasan topik ini"))
            },
        )
        add("required", JsonArray().apply { add("timestampSec"); add("title"); add("body") })
    }
    val decisionItem = obj {
        addProperty("type", "object")
        add("properties", obj { add("text", strProp("Satu keputusan yang disepakati forum, kalimat lengkap")) })
        add("required", JsonArray().apply { add("text") })
    }
    val questionItem = obj {
        addProperty("type", "object")
        add(
            "properties",
            obj {
                add("question", strProp("Pertanyaan yang muncul namun belum/sebagian terjawab"))
                add(
                    "status",
                    obj {
                        addProperty("type", "string")
                        add(
                            "enum",
                            JsonArray().apply { add("Belum terjawab"); add("Terjawab sebagian"); add("Terjawab") },
                        )
                    },
                )
                add("note", strProp("Konteks singkat: siapa yang bertanya / kenapa belum tuntas"))
            },
        )
        add("required", JsonArray().apply { add("question"); add("status"); add("note") })
    }
    val actionItem = obj {
        addProperty("type", "object")
        add(
            "properties",
            obj {
                add("task", strProp("Tugas konkret yang harus dikerjakan"))
                add("pic", strProp("Nama penanggung jawab (PIC) yang disebut dalam transkrip"))
                add("due", strProp("Tenggat dalam bahasa natural, mis. '12 Okt', bila disebut; jika tidak, perkirakan wajar"))
                add("sourceTimestampSec", intProp("Detik pada transkrip tempat tugas ini disebutkan"))
            },
        )
        add("required", JsonArray().apply { add("task"); add("pic"); add("due"); add("sourceTimestampSec") })
    }

    return obj {
        addProperty("type", "object")
        add(
            "properties",
            obj {
                add("summary", strProp("Ringkasan rapat 2-4 kalimat, bahasa Indonesia formal"))
                add("topics", obj { addProperty("type", "array"); add("items", topicItem) })
                add("decisions", obj { addProperty("type", "array"); add("items", decisionItem) })
                add("questions", obj { addProperty("type", "array"); add("items", questionItem) })
                add("actions", obj { addProperty("type", "array"); add("items", actionItem) })
            },
        )
        add("required", JsonArray().apply { add("summary"); add("topics"); add("decisions"); add("questions"); add("actions") })
    }
}

data class NotulensiJson(
    val summary: String,
    val topics: List<TopicJson> = emptyList(),
    val decisions: List<DecisionJson> = emptyList(),
    val questions: List<QuestionJson> = emptyList(),
    val actions: List<ActionJson> = emptyList(),
)
data class TopicJson(val timestampSec: Int, val title: String, val body: String)
data class DecisionJson(val text: String)
data class QuestionJson(val question: String, val status: String, val note: String)
data class ActionJson(val task: String, val pic: String, val due: String, val sourceTimestampSec: Int)

fun formatTimestamp(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return "%02d:%02d".format(m, s)
}

fun NotulensiJson.toDomain(provider: AiProvider): Notulensi = Notulensi(
    summary = summary,
    provider = provider,
    topics = topics.map { Topic(formatTimestamp(it.timestampSec), it.timestampSec, it.title, it.body) },
    decisions = decisions.mapIndexed { i, d -> Decision("%02d".format(i + 1), d.text) },
    questions = questions.map {
        Question(
            it.question,
            when (it.status) {
                "Terjawab" -> QuestionStatus.ANSWERED
                "Terjawab sebagian" -> QuestionStatus.PARTIAL
                else -> QuestionStatus.UNANSWERED
            },
            it.note,
        )
    },
    actions = actions.map { ActionItem(it.task, it.pic, it.due, formatTimestamp(it.sourceTimestampSec)) },
)

fun buildTranscriptPrompt(meetingTitle: String, segments: List<TranscriptSegment>, densityDetailed: Boolean): String {
    val transcriptText = segments.joinToString("\n") { "[${formatTimestamp(it.timestampSec)}] ${it.speaker}: ${it.text}" }
    val densityNote = if (densityDetailed) {
        "Buat notulensi bergaya DETAIL: sertakan lebih banyak poin bahasan dan konteks per butir."
    } else {
        "Buat notulensi bergaya RINGKAS: hanya poin-poin paling penting."
    }
    return """
        Kamu adalah asisten yang menyusun notulensi rapat desa dari transkrip mentah.
        Judul rapat: "$meetingTitle".
        $densityNote
        Tulis semua isi (summary, judul topik, teks) dalam Bahasa Indonesia formal.
        Kelompokkan hasil menjadi empat blok sesuai skema yang diberikan: poin bahasan (urut waktu),
        keputusan yang disepakati forum, poin pertanyaan yang belum/sebagian terjawab, dan action item
        dengan PIC serta tenggat bila disebutkan dalam transkrip. Jangan mengarang nama PIC atau tenggat
        yang tidak ada dasarnya di transkrip — jika tidak disebutkan, gunakan PIC "Belum ditentukan" dan
        due "Belum ditentukan".

        Transkrip (format "[mm:ss] Pembicara: ucapan"):
        $transcriptText
    """.trimIndent()
}
