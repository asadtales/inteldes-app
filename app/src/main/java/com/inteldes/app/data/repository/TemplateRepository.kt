package com.inteldes.app.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.inteldes.app.data.db.TemplateDao
import com.inteldes.app.data.db.TemplateEntity
import com.inteldes.app.data.model.BuiltInTemplates
import com.inteldes.app.data.model.NoteTemplate
import com.inteldes.app.data.model.TemplateSection
import com.inteldes.app.data.prefs.AppSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.UUID

data class TemplateChoices(val templates: List<NoteTemplate>, val defaultId: String) {
    val default: NoteTemplate get() = templates.firstOrNull { it.id == defaultId } ?: BuiltInTemplates.standard

    companion object {
        val Initial = TemplateChoices(BuiltInTemplates.all, BuiltInTemplates.STANDARD_ID)
    }
}

/** Built-in templates (in code) plus the user's own (in Room), exposed as one list. */
class TemplateRepository(private val dao: TemplateDao) {
    private val gson = Gson()
    private val sectionListType = object : TypeToken<List<TemplateSection>>() {}.type

    val templates: Flow<List<NoteTemplate>> = dao.observeAll().map { custom ->
        BuiltInTemplates.all + custom.map { it.toDomain() }
    }

    fun observeChoices(settings: AppSettingsStore): Flow<TemplateChoices> =
        combine(templates, settings.settings) { list, s -> TemplateChoices(list, s.defaultTemplateId) }

    /** Falls back to the standard template when the id is gone (e.g. a deleted custom template). */
    suspend fun find(id: String): NoteTemplate =
        BuiltInTemplates.byId(id) ?: dao.getOne(id)?.toDomain() ?: BuiltInTemplates.standard

    fun newId(): String = "custom_" + UUID.randomUUID().toString()

    suspend fun saveCustom(template: NoteTemplate) {
        dao.upsert(
            TemplateEntity(
                id = template.id,
                name = template.name.trim(),
                description = template.description.trim(),
                sectionsJson = gson.toJson(template.sections),
                createdAt = dao.getOne(template.id)?.createdAt ?: System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteCustom(id: String) = dao.delete(id)

    private fun TemplateEntity.toDomain(): NoteTemplate {
        val sections = runCatching { gson.fromJson<List<TemplateSection>>(sectionsJson, sectionListType) }.getOrNull().orEmpty()
        return NoteTemplate(id = id, name = name, description = description, sections = sections, builtIn = false)
    }
}
