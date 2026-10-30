package com.inteldes.app.data.web

import com.inteldes.app.data.server.ServerException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeLibRemote : WebLibraryRemote {
    val templates = mutableListOf<WebTemplate>()
    val contexts = mutableListOf<WebContext>()
    var rejectSources = false
    val created = mutableListOf<String>()
    override suspend fun listTemplates() = templates.toList()
    override suspend fun createTemplate(id: String, name: String, description: String, sections: List<WebTemplateSection>) {
        if (templates.any { it.id == id }) throw ServerException(409, "version_conflict", "x")
        templates += WebTemplate(id, name, description, false, 1, sections)
        created += id
    }
    override suspend fun listContexts() = contexts.toList()
    override suspend fun createContext(id: String, name: String, text: String, sources: List<WebContextSource>) {
        if (rejectSources && sources.isNotEmpty()) throw ServerException(400, "bad_source", "x")
        if (contexts.any { it.id == id }) throw ServerException(409, "version_conflict", "x")
        contexts += WebContext(id, name, text, 1, 1, 1, sources)
        created += id
    }
}

private class FakeLibLocal : WebLibraryLocal {
    val templates = mutableListOf<LocalTemplate>()
    val contexts = mutableListOf<LocalContext>()
    override suspend fun templates() = templates.toList()
    override suspend fun addTemplate(t: LocalTemplate) { templates += t }
    override suspend fun contexts() = contexts.toList()
    override suspend fun addContext(c: LocalContext) { contexts += c }
}

class WebLibrarySyncTest {
    private val remote = FakeLibRemote()
    private val local = FakeLibLocal()
    private fun run() = runBlocking { WebLibrarySync(local, remote).run() }
    private val section = WebTemplateSection("Ringkasan", "PARAGRAPH", "Ringkas")

    @Test
    fun customTemplatesAndContextsAreCopiedBothWaysAndBuiltInsAreIgnored() {
        local.templates += LocalTemplate("custom_a", "Punya HP", "", listOf(section))
        remote.templates += WebTemplate("custom_b", "Punya web", "", false, 1, listOf(section))
        remote.templates += WebTemplate("builtin_standard", "Standar", "", true, 1, listOf(section))
        local.contexts += LocalContext("c1", "Konteks HP", "isi", 1, 1, emptyList())
        remote.contexts += WebContext("c2", "Konteks web", "isi web", 1, 1, 1, listOf(WebContextSource("s1", null, "rapat lama", "BOTH", 1)))
        val report = run()
        assertEquals(0, report.failed)
        assertEquals(setOf("custom_a", "custom_b"), local.templates.map { it.id }.toSet())
        assertTrue(remote.templates.any { it.id == "custom_a" })
        assertTrue(local.templates.none { it.id == "builtin_standard" })
        assertTrue(remote.contexts.any { it.id == "c1" })
        assertEquals(listOf("c1", "c2"), local.contexts.map { it.id }.sorted())
        assertTrue(local.contexts.first { it.id == "c2" }.sources.isEmpty())     // a source without a recording is dropped, the context kept
    }

    @Test
    fun existingOnesAreNeverOverwrittenOrDeleted() {
        local.templates += LocalTemplate("custom_a", "Nama HP", "", listOf(section))
        remote.templates += WebTemplate("custom_a", "Nama web", "", false, 3, listOf(section))
        local.contexts += LocalContext("c1", "HP", "teks HP", 1, 1, emptyList())
        remote.contexts += WebContext("c1", "Web", "teks web", 2, 1, 1, emptyList())
        val before = remote.created.size
        run()
        assertEquals("Nama HP", local.templates.single().name)
        assertEquals("Nama web", remote.templates.single().name)
        assertEquals("teks HP", local.contexts.single().text)
        assertEquals("teks web", remote.contexts.single().text)
        assertEquals(before, remote.created.size)
    }

    @Test
    fun aContextWhoseSourcesTheServerRefusesIsStillSentWithoutThem() {
        remote.rejectSources = true
        local.contexts += LocalContext("c1", "HP", "teks", 1, 1, listOf(WebContextSource("s", "r1", "Rapat", "BOTH", 1)))
        val report = run()
        assertEquals(0, report.failed)
        assertTrue(remote.contexts.single().sources.isEmpty())
    }

    @Test
    fun aTemplateThePhoneHoldsWithNoSectionsIsSkippedNotAnError() {
        local.templates += LocalTemplate("custom_empty", "Kosong", "", emptyList())
        val report = run()
        assertEquals(0, report.failed)
        assertTrue(remote.templates.isEmpty())
    }
}
