package com.inteldes.app.data.db

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.inteldes.app.data.model.AiProvider
import com.inteldes.app.data.model.NoteSection
import com.inteldes.app.data.model.Notulensi
import com.inteldes.app.data.model.SectionType

/**
 * JSON form of a whole [Notulensi] for the history archive: `{summary, provider, templateName,
 * sections:[{title, type, payload}]}`, reusing [SectionCodec] for each section's own payload.
 * Plain Gson can't round-trip [com.inteldes.app.data.model.SectionContent] (a sealed interface),
 * so the section list is written explicitly instead of serializing the object graph directly.
 */
object NotulensiSnapshot {
    fun encode(notulensi: Notulensi, templateName: String): String = JsonObject().apply {
        addProperty("summary", notulensi.summary)
        addProperty("provider", notulensi.provider.name)
        addProperty("templateName", templateName)
        add("sections", JsonArray().also { arr ->
            notulensi.sections.forEach { s ->
                arr.add(JsonObject().apply {
                    addProperty("title", s.title)
                    addProperty("type", SectionCodec.typeOf(s.content).name)
                    addProperty("payload", SectionCodec.encode(s.content))
                })
            }
        })
    }.toString()

    /** Returns the archived note and the name of the template it was generated with. */
    fun decode(json: String, fallbackProvider: AiProvider): Pair<Notulensi, String> {
        val root = JsonParser.parseString(json).asJsonObject
        val provider = root.get("provider")?.asString?.let { runCatching { AiProvider.valueOf(it) }.getOrNull() } ?: fallbackProvider
        val sections = root.get("sections")?.asJsonArray?.toList().orEmpty().map { el ->
            val o = el.asJsonObject
            val type = SectionType.valueOf(o.get("type").asString)
            NoteSection(o.get("title").asString, SectionCodec.decode(type, o.get("payload").asString))
        }
        val notulensi = Notulensi(root.get("summary")?.asString.orEmpty(), provider, sections)
        return notulensi to (root.get("templateName")?.asString ?: "Notulensi Standar")
    }
}
