package com.inteldes.app.data.web

import com.inteldes.app.data.server.ServerException
import com.inteldes.app.data.sync.AuthException
import kotlinx.coroutines.CancellationException
import java.nio.charset.StandardCharsets
import java.util.UUID

data class WebTemplateSection(val title: String, val type: String, val instruction: String)
data class WebTemplate(val id: String, val name: String, val description: String, val builtin: Boolean, val version: Int, val sections: List<WebTemplateSection>)

data class WebContextSource(val id: String, val recordingId: String?, val recordingTitle: String, val kind: String, val addedAt: Long)
data class WebContext(val id: String, val name: String, val text: String, val version: Int, val createdAt: Long, val updatedAt: Long, val sources: List<WebContextSource>)

interface WebLibraryRemote {
    suspend fun listTemplates(): List<WebTemplate>

    /** Creates only (`If-Match: 0`); a template that already exists answers 409. */
    suspend fun createTemplate(id: String, name: String, description: String, sections: List<WebTemplateSection>)
    suspend fun listContexts(): List<WebContext>
    suspend fun createContext(id: String, name: String, text: String, sources: List<WebContextSource>)
}

class LocalTemplate(val id: String, val name: String, val description: String, val sections: List<WebTemplateSection>)
class LocalContext(val id: String, val name: String, val text: String, val createdAt: Long, val updatedAt: Long, val sources: List<WebContextSource>)

interface WebLibraryLocal {
    suspend fun templates(): List<LocalTemplate>
    suspend fun addTemplate(t: LocalTemplate)
    suspend fun contexts(): List<LocalContext>
    suspend fun addContext(c: LocalContext)
}

class LibraryReport(var pushed: Int = 0, var pulled: Int = 0, var failed: Int = 0, var firstError: String? = null)

/**
 * Custom note templates and saved knowledge contexts, merged by id in both directions.
 *
 * ADDITIVE ONLY, on purpose: a template/context that exists on one side and not the other is copied across;
 * nothing is overwritten and nothing is deleted on either side (edits and deletes are not propagated yet).
 * Built-in templates are never touched.
 */
class WebLibrarySync(private val local: WebLibraryLocal, private val remote: WebLibraryRemote, private val pullOnly: Boolean = false) {
    suspend fun run(): LibraryReport {
        val report = LibraryReport()
        guarded(report, "template") { templates(report) }
        guarded(report, "konteks") { contexts(report) }
        return report
    }

    private suspend fun templates(report: LibraryReport) {
        val server = remote.listTemplates().filter { !it.builtin }
        val mine = local.templates()
        val serverIds = server.map { it.id }.toSet()
        val mineIds = mine.map { it.id }.toSet()
        for (t in server.filter { it.id !in mineIds && it.sections.isNotEmpty() }) {
            local.addTemplate(LocalTemplate(t.id, t.name, t.description, t.sections))
            report.pulled++
        }
        for (t in if (pullOnly) emptyList() else mine.filter { it.id !in serverIds }) {
            if (t.sections.isEmpty() || t.sections.size > 30 || t.name.isBlank()) continue          // the server would refuse it
            try {
                remote.createTemplate(t.id, t.name.take(200), t.description.take(1000), t.sections.map { WebTemplateSection(it.title.take(200).ifBlank { "Bagian" }, it.type, it.instruction.take(2000)) })
                report.pushed++
            } catch (e: ServerException) {
                if (e.status != 409) throw e
            }
        }
    }

    private suspend fun contexts(report: LibraryReport) {
        val server = remote.listContexts()
        val mine = local.contexts()
        val serverIds = server.map { it.id }.toSet()
        val mineIds = mine.map { it.id }.toSet()
        for (c in server.filter { it.id !in mineIds }) {
            local.addContext(LocalContext(c.id, c.name, c.text, c.createdAt, c.updatedAt, c.sources.filter { it.recordingId != null }))
            report.pulled++
        }
        for (c in if (pullOnly) emptyList() else mine.filter { it.id !in serverIds }) {
            if (c.name.isBlank() || c.text.length > 40_000) continue                                   // too long for the server: stays local
            try {
                try {
                    remote.createContext(c.id, c.name.take(200), c.text, c.sources.take(200))
                } catch (e: ServerException) {
                    if (e.code != "bad_source") throw e
                    remote.createContext(c.id, c.name.take(200), c.text, emptyList())              // its recordings aren't on the server (yet)
                }
                report.pushed++
            } catch (e: ServerException) {
                if (e.status != 409) throw e
            }
        }
    }

    private suspend fun guarded(report: LibraryReport, what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthException) {
            throw e
        } catch (e: Exception) {
            report.failed++
            if (report.firstError == null) report.firstError = "$what: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    companion object {
        /** Stable id for a context's source row, so pushing twice never duplicates it. */
        fun sourceId(contextId: String, recordingId: String): String =
            UUID.nameUUIDFromBytes("$contextId|src|$recordingId".toByteArray(StandardCharsets.UTF_8)).toString()
    }
}
