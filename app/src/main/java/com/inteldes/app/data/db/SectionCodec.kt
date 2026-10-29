package com.inteldes.app.data.db

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.inteldes.app.data.model.ActionItem
import com.inteldes.app.data.model.Decision
import com.inteldes.app.data.model.Question
import com.inteldes.app.data.model.SectionContent
import com.inteldes.app.data.model.SectionType
import com.inteldes.app.data.model.Topic

/** Stores a generated section's content as JSON in [NoteSectionEntity.payload] and reads it back. */
object SectionCodec {
    private val gson = Gson()
    private val stringList = object : TypeToken<List<String>>() {}.type
    private val topicList = object : TypeToken<List<Topic>>() {}.type
    private val questionList = object : TypeToken<List<Question>>() {}.type
    private val actionList = object : TypeToken<List<ActionItem>>() {}.type

    fun typeOf(content: SectionContent): SectionType = when (content) {
        is SectionContent.Paragraph -> SectionType.PARAGRAPH
        is SectionContent.Bullets -> SectionType.BULLETS
        is SectionContent.Topics -> SectionType.TOPICS
        is SectionContent.Decisions -> SectionType.DECISIONS
        is SectionContent.Questions -> SectionType.QUESTIONS
        is SectionContent.Actions -> SectionType.ACTIONS
    }

    fun encode(content: SectionContent): String = when (content) {
        is SectionContent.Paragraph -> gson.toJson(content.text)
        is SectionContent.Bullets -> gson.toJson(content.items)
        is SectionContent.Topics -> gson.toJson(content.items)
        is SectionContent.Decisions -> gson.toJson(content.items.map { it.text })
        is SectionContent.Questions -> gson.toJson(content.items)
        is SectionContent.Actions -> gson.toJson(content.items)
    }

    fun decode(type: SectionType, payload: String): SectionContent = try {
        when (type) {
            SectionType.PARAGRAPH -> SectionContent.Paragraph(gson.fromJson(payload, String::class.java).orEmpty())
            SectionType.BULLETS -> SectionContent.Bullets(gson.fromJson<List<String>>(payload, stringList).orEmpty())
            SectionType.TOPICS -> SectionContent.Topics(gson.fromJson<List<Topic>>(payload, topicList).orEmpty())
            SectionType.DECISIONS -> SectionContent.Decisions(
                gson.fromJson<List<String>>(payload, stringList).orEmpty()
                    .mapIndexed { i, text -> Decision("%02d".format(i + 1), text) },
            )
            SectionType.QUESTIONS -> SectionContent.Questions(gson.fromJson<List<Question>>(payload, questionList).orEmpty())
            SectionType.ACTIONS -> SectionContent.Actions(gson.fromJson<List<ActionItem>>(payload, actionList).orEmpty())
        }
    } catch (e: Exception) {
        SectionContent.Paragraph("")
    }
}
