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
    override suspend fun updateTemplate(id: String, name: String, description: String, sections: List<WebTemplateSection>, ifMatch: Int): Int {
        val i = templates.indexOfFirst { it.id == id }
        if (i < 0 || templates[i].version != ifMatch) throw ServerException(409, "version_conflict", "x")
        templates[i] = templates[i].copy(name = name, description = description, sections = sections, version = ifMatch + 1)
        return ifMatch + 1
    }
    override suspend fun deleteTemplate(id: String) { if (!templates.removeAll { it.id == id }) throw ServerException(404, "not_found", "x") }
    override suspend fun updateContext(id: String, name: String, text: String, sources: List<WebContextSource>, ifMatch: Int): Int {
        val i = contexts.indexOfFirst { it.id == id }
        if (i < 0 || contexts[i].version != ifMatch) throw ServerException(409, "version_conflict", "x")
        contexts[i] = contexts[i].copy(name = name, text = text, sources = sources, version = ifMatch + 1)
        return ifMatch + 1
    }
    override suspend fun deleteContext(id: String) { contexts.removeAll { it.id == id } }
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
    override suspend fun updateTemplate(t: LocalTemplate) { templates.replaceAll { if (it.id == t.id) t else it } }
    override suspend fun updateContext(c: LocalContext) { contexts.replaceAll { if (it.id == c.id) c else it } }
    override suspend fun contexts() = contexts.toList()
    override suspend fun addContext(c: LocalContext) { contexts += c }
}

class WebLibrarySyncTest {
    private val remote = FakeLibRemote()
    private val local = FakeLibLocal()
    private val state = MemoryLibraryState()
    private fun run() = runBlocking { WebLibrarySync(local, remote, state = state).run() }
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
    fun pullOnlyCopiesDownButSendsNothing() {
        local.templates += LocalTemplate("custom_a", "Punya HP", "", listOf(section))
        remote.templates += WebTemplate("custom_b", "Punya web", "", false, 1, listOf(section))
        local.contexts += LocalContext("c1", "Konteks HP", "isi", 1, 1, emptyList())
        val report = runBlocking { WebLibrarySync(local, remote, pullOnly = true).run() }
        assertEquals(0, report.pushed)
        assertTrue(remote.created.isEmpty())
        assertTrue(local.templates.any { it.id == "custom_b" })
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

    @Test
    fun aTemplateEditedOnThePhoneIsSentAndOneEditedOnTheWebComesDown() {
        local.templates += LocalTemplate("custom_a", "Awal", "", listOf(section))
        local.templates += LocalTemplate("custom_b", "B awal", "", listOf(section))
        run()                                                                           // both created on the server and agreed
        local.templates.replaceAll { if (it.id == "custom_a") LocalTemplate(it.id, "Diubah di HP", "", it.sections) else it }
        remote.templates.replaceAll { if (it.id == "custom_b") it.copy(name = "Diubah di web", version = it.version + 1) else it }
        val report = run()
        assertEquals(0, report.failed)
        assertEquals("Diubah di HP", remote.templates.first { it.id == "custom_a" }.name)
        assertEquals(2, remote.templates.first { it.id == "custom_a" }.version)
        assertEquals("Diubah di web", local.templates.first { it.id == "custom_b" }.name)
        val again = run()                                                               // and then it is quiet
        assertEquals(0, again.pushed + again.pulled)
    }

    @Test
    fun whenBothEditedTheSameTemplateThePhoneWins() {
        local.templates += LocalTemplate("custom_a", "Awal", "", listOf(section))
        run()
        local.templates.replaceAll { LocalTemplate(it.id, "HP", "", it.sections) }
        remote.templates.replaceAll { it.copy(name = "Web", version = it.version + 1) }
        run()
        assertEquals("HP", remote.templates.single().name)
        assertEquals("HP", local.templates.single().name)
    }

    @Test
    fun aTemplateDeletedOnThePhoneIsDeletedOnTheServerButNotTheOtherWayAround() {
        local.templates += LocalTemplate("custom_a", "Dihapus di HP", "", listOf(section))
        local.templates += LocalTemplate("custom_b", "Dihapus di web", "", listOf(section))
        run()
        local.templates.removeAll { it.id == "custom_a" }
        remote.templates.removeAll { it.id == "custom_b" }
        run()
        assertTrue(remote.templates.none { it.id == "custom_a" })
        assertTrue(local.templates.any { it.id == "custom_b" })                       // the web's delete never reaches the phone
        assertTrue(remote.templates.any { it.id == "custom_b" })                     // and the phone, which still has it, puts it back on the server
    }

    @Test
    fun pullOnlyNeverSendsAnEditOrADelete() {
        local.templates += LocalTemplate("custom_a", "Awal", "", listOf(section))
        run()
        local.templates.replaceAll { LocalTemplate(it.id, "Diubah", "", it.sections) }
        runBlocking { WebLibrarySync(local, remote, pullOnly = true, state = state).run() }
        assertEquals("Awal", remote.templates.single().name)
        local.templates.clear()
        runBlocking { WebLibrarySync(local, remote, pullOnly = true, state = state).run() }
        assertEquals(1, remote.templates.size)
    }

    @Test
    fun aContextEditedOnThePhoneIsSentAndAContextDeletedOnThePhoneIsRemoved() {
        local.contexts += LocalContext("c1", "Satu", "teks lama", 1, 1, emptyList())
        local.contexts += LocalContext("c2", "Dua", "isi", 1, 1, emptyList())
        run()
        local.contexts.replaceAll { if (it.id == "c1") LocalContext(it.id, it.name, "teks baru", 1, 2, emptyList()) else it }
        local.contexts.removeAll { it.id == "c2" }
        run()
        assertEquals("teks baru", remote.contexts.single { it.id == "c1" }.text)
        assertTrue(remote.contexts.none { it.id == "c2" })
    }
}
