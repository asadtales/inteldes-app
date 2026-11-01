package com.inteldes.app.data.web

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.inteldes.app.data.db.ContextSourceEntity
import com.inteldes.app.data.db.IntelDesDatabase
import com.inteldes.app.data.db.KnowledgeContextEntity
import com.inteldes.app.data.db.TemplateEntity
import com.inteldes.app.data.model.ContextSourceKind
import com.inteldes.app.data.model.SectionType
import com.inteldes.app.data.model.TemplateSection
import androidx.room.withTransaction

/** [WebLibraryLocal] on Room: only ever ADDS rows. */
class RoomWebLibrary(private val db: IntelDesDatabase) : WebLibraryLocal {
    private val gson = Gson()
    private val listType = object : TypeToken<List<TemplateSection>>() {}.type

    override suspend fun templates(): List<LocalTemplate> = db.templateDao().all().map { e ->
        val sections = runCatching { gson.fromJson<List<TemplateSection>>(e.sectionsJson, listType) }.getOrNull().orEmpty()
        LocalTemplate(e.id, e.name, e.description, sections.map { WebTemplateSection(it.title, it.type.name, it.instruction) })
    }

    override suspend fun addTemplate(t: LocalTemplate) {
        if (db.templateDao().getOne(t.id) != null) return
        val sections = t.sections.mapNotNull { s ->
            val type = runCatching { SectionType.valueOf(s.type) }.getOrNull() ?: return@mapNotNull null
            TemplateSection(s.title, type, s.instruction)
        }
        if (sections.isEmpty()) return
        db.templateDao().upsert(TemplateEntity(t.id, t.name, t.description, gson.toJson(sections), System.currentTimeMillis()))
    }

    override suspend fun updateTemplate(t: LocalTemplate) {
        val old = db.templateDao().getOne(t.id) ?: return
        val sections = t.sections.mapNotNull { s ->
            val type = runCatching { SectionType.valueOf(s.type) }.getOrNull() ?: return@mapNotNull null
            TemplateSection(s.title, type, s.instruction)
        }
        if (sections.isEmpty()) return                                  // never leave the phone with a template that has nothing in it
        db.templateDao().upsert(old.copy(name = t.name, description = t.description, sectionsJson = gson.toJson(sections)))
    }

    override suspend fun contexts(): List<LocalContext> = db.contextDao().all().map { c ->
        val sources = db.contextDao().sourcesOf(c.id).map {
            WebContextSource(WebLibrarySync.sourceId(c.id, it.recordingId), it.recordingId, it.recordingTitle, it.kind, it.addedAt)
        }
        LocalContext(c.id, c.name, c.text, c.createdAt, c.updatedAt, sources)
    }

    override suspend fun updateContext(c: LocalContext) {
        db.withTransaction {
            val old = db.contextDao().getOne(c.id) ?: return@withTransaction
            db.contextDao().deleteSources(c.id)
            db.contextDao().upsert(old.copy(name = c.name, text = c.text, updatedAt = c.updatedAt))
            db.contextDao().insertSources(
                c.sources.mapNotNull { s ->
                    val rid = s.recordingId ?: return@mapNotNull null
                    val kind = runCatching { ContextSourceKind.valueOf(s.kind) }.getOrDefault(ContextSourceKind.BOTH)
                    ContextSourceEntity(contextId = c.id, recordingId = rid, recordingTitle = s.recordingTitle, kind = kind.name, addedAt = s.addedAt)
                },
            )
        }
    }

    override suspend fun addContext(c: LocalContext) {
        db.withTransaction {
            if (db.contextDao().getOne(c.id) != null) return@withTransaction
            db.contextDao().upsert(KnowledgeContextEntity(c.id, c.name, c.text, c.createdAt, c.updatedAt))
            db.contextDao().insertSources(
                c.sources.mapNotNull { s ->
                    val rid = s.recordingId ?: return@mapNotNull null
                    val kind = runCatching { ContextSourceKind.valueOf(s.kind) }.getOrDefault(ContextSourceKind.BOTH)
                    ContextSourceEntity(contextId = c.id, recordingId = rid, recordingTitle = s.recordingTitle, kind = kind.name, addedAt = s.addedAt)
                },
            )
        }
    }
}
