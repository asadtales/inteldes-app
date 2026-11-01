package com.inteldes.app.data.web

import com.inteldes.app.data.server.ServerException
import com.inteldes.app.data.sync.AuthException
import kotlinx.coroutines.CancellationException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

data class WebTemplateSection(val title: String, val type: String, val instruction: String)
data class WebTemplate(val id: String, val name: String, val description: String, val builtin: Boolean, val version: Int, val sections: List<WebTemplateSection>)

data class WebContextSource(val id: String, val recordingId: String?, val recordingTitle: String, val kind: String, val addedAt: Long)
data class WebContext(val id: String, val name: String, val text: String, val version: Int, val createdAt: Long, val updatedAt: Long, val sources: List<WebContextSource>)

interface WebLibraryRemote {
    suspend fun listTemplates(): List<WebTemplate>

    /** Creates only (`If-Match: 0`); a template that already exists answers 409. */
    suspend fun createTemplate(id: String, name: String, description: String, sections: List<WebTemplateSection>)

    /** Replaces an existing template (`If-Match: [ifMatch]`); returns its new version. A stale version answers 409. */
    suspend fun updateTemplate(id: String, name: String, description: String, sections: List<WebTemplateSection>, ifMatch: Int): Int
    suspend fun deleteTemplate(id: String)
    suspend fun listContexts(): List<WebContext>
    suspend fun createContext(id: String, name: String, text: String, sources: List<WebContextSource>)
    suspend fun updateContext(id: String, name: String, text: String, sources: List<WebContextSource>, ifMatch: Int): Int
    suspend fun deleteContext(id: String)
}

class LocalTemplate(val id: String, val name: String, val description: String, val sections: List<WebTemplateSection>)
class LocalContext(val id: String, val name: String, val text: String, val createdAt: Long, val updatedAt: Long, val sources: List<WebContextSource>)

interface WebLibraryLocal {
    suspend fun templates(): List<LocalTemplate>
    suspend fun addTemplate(t: LocalTemplate)

    /** Overwrites name, description and sections of the template with this id (the server's edit reaching the phone). */
    suspend fun updateTemplate(t: LocalTemplate)
    suspend fun contexts(): List<LocalContext>
    suspend fun addContext(c: LocalContext)
    suspend fun updateContext(c: LocalContext)
}

/** What both sides last agreed on for one template or context: the server's version and a hash of the phone's copy. */
class LibraryMark(val version: Int, val hash: String)

/** Remembers [LibraryMark]s between runs. `kind` is "template" or "context". */
interface WebLibraryState {
    fun get(kind: String, id: String): LibraryMark?
    fun put(kind: String, id: String, mark: LibraryMark)
    fun remove(kind: String, id: String)
}

class MemoryLibraryState : WebLibraryState {
    private val map = mutableMapOf<String, LibraryMark>()
    override fun get(kind: String, id: String) = map["$kind:$id"]
    override fun put(kind: String, id: String, mark: LibraryMark) { map["$kind:$id"] = mark }
    override fun remove(kind: String, id: String) { map.remove("$kind:$id") }
}

class LibraryReport(var pushed: Int = 0, var pulled: Int = 0, var failed: Int = 0, var firstError: String? = null)

/**
 * Custom note templates and saved knowledge contexts, kept the same on the phone and on the server.
 *
 *  - New on one side: copied to the other.
 *  - Edited on the phone since the last agreement: sent (the phone wins a clash with a web edit made meanwhile).
 *  - Edited only on the web: comes down to the phone.
 *  - Deleted on the phone: deleted on the server too. Deleted on the web: IGNORED, the phone keeps its copy.
 *  - An item that exists on both sides with different content and no earlier agreement is left alone on both.
 *
 * Built-in templates are never touched. In pull-only mode nothing is ever sent or deleted on the server.
 */
class WebLibrarySync(
    private val local: WebLibraryLocal,
    private val remote: WebLibraryRemote,
    private val pullOnly: Boolean = false,
    private val state: WebLibraryState = MemoryLibraryState(),
) {
    suspend fun run(): LibraryReport {
        val report = LibraryReport()
        guarded(report, "template") { templates(report) }
        guarded(report, "konteks") { contexts(report) }
        return report
    }

    // ───────────────────────── templates ─────────────────────────

    private fun sendable(t: LocalTemplate) = t.sections.isNotEmpty() && t.sections.size <= 30 && t.name.isNotBlank()

    private fun body(t: LocalTemplate) = t.sections.map { WebTemplateSection(it.title.take(200).ifBlank { "Bagian" }, it.type, it.instruction.take(2000)) }

    private suspend fun freshTemplateHash(id: String): String? = local.templates().firstOrNull { it.id == id }?.let(::hashOf)

    private suspend fun templates(report: LibraryReport) {
        val server = remote.listTemplates().filter { !it.builtin }
        val mine = local.templates().associateBy { it.id }
        val serverIds = server.map { it.id }.toSet()
        for (t in server) {
            val m = mine[t.id]
            val mark = state.get(TEMPLATE, t.id)
            if (m == null) {
                if (mark != null) {                                   // it was agreed once and is gone from the phone: the user deleted it
                    if (!pullOnly) {
                        ignore404 { remote.deleteTemplate(t.id) }
                        state.remove(TEMPLATE, t.id)
                        report.pushed++
                    }
                } else if (t.sections.isNotEmpty()) {
                    local.addTemplate(LocalTemplate(t.id, t.name, t.description, t.sections))
                    report.pulled++
                    freshTemplateHash(t.id)?.let { state.put(TEMPLATE, t.id, LibraryMark(t.version, it)) }
                }
                continue
            }
            val h = hashOf(m)
            if (mark == null) {
                if (h == hashOf(t)) state.put(TEMPLATE, t.id, LibraryMark(t.version, h))      // same on both sides: from now on tracked
                continue
            }
            when {
                h != mark.hash -> if (!pullOnly && sendable(m)) {
                    val v = remote.updateTemplate(t.id, m.name.take(200), m.description.take(1000), body(m), t.version)
                    state.put(TEMPLATE, t.id, LibraryMark(v, h))
                    report.pushed++
                }
                t.version != mark.version && t.sections.isNotEmpty() -> {
                    local.updateTemplate(LocalTemplate(t.id, t.name, t.description, t.sections))
                    freshTemplateHash(t.id)?.let { state.put(TEMPLATE, t.id, LibraryMark(t.version, it)) }
                    report.pulled++
                }
            }
        }
        if (pullOnly) return
        for (t in mine.values.filter { it.id !in serverIds }) {
            if (!sendable(t)) continue                                // the server would refuse it
            try {
                remote.createTemplate(t.id, t.name.take(200), t.description.take(1000), body(t))
                state.put(TEMPLATE, t.id, LibraryMark(1, hashOf(t)))
                report.pushed++
            } catch (e: ServerException) {
                if (e.status != 409) throw e
            }
        }
    }

    // ───────────────────────── contexts ─────────────────────────

    private fun sendable(c: LocalContext) = c.name.isNotBlank() && c.text.length <= 40_000

    private suspend fun freshContextHash(id: String): String? = local.contexts().firstOrNull { it.id == id }?.let(::hashOf)

    /** Sends a context; when the server does not know one of its recordings the sources are left out rather than failing the rest. */
    private suspend fun <T> withSourceFallback(c: LocalContext, send: suspend (List<WebContextSource>) -> T): T =
        try {
            send(c.sources.take(200))
        } catch (e: ServerException) {
            if (e.code != "bad_source") throw e
            send(emptyList())
        }

    private suspend fun contexts(report: LibraryReport) {
        val server = remote.listContexts()
        val mine = local.contexts().associateBy { it.id }
        val serverIds = server.map { it.id }.toSet()
        for (c in server) {
            val m = mine[c.id]
            val mark = state.get(CONTEXT, c.id)
            if (m == null) {
                if (mark != null) {
                    if (!pullOnly) {
                        ignore404 { remote.deleteContext(c.id) }
                        state.remove(CONTEXT, c.id)
                        report.pushed++
                    }
                } else {
                    local.addContext(LocalContext(c.id, c.name, c.text, c.createdAt, c.updatedAt, c.sources.filter { it.recordingId != null }))
                    report.pulled++
                    freshContextHash(c.id)?.let { state.put(CONTEXT, c.id, LibraryMark(c.version, it)) }
                }
                continue
            }
            val h = hashOf(m)
            if (mark == null) {
                if (h == hashOf(c)) state.put(CONTEXT, c.id, LibraryMark(c.version, h))
                continue
            }
            when {
                h != mark.hash -> if (!pullOnly && sendable(m)) {
                    val v = withSourceFallback(m) { src -> remote.updateContext(c.id, m.name.take(200), m.text, src, c.version) }
                    state.put(CONTEXT, c.id, LibraryMark(v, h))
                    report.pushed++
                }
                c.version != mark.version -> {
                    local.updateContext(LocalContext(c.id, c.name, c.text, c.createdAt, c.updatedAt, c.sources.filter { it.recordingId != null }))
                    freshContextHash(c.id)?.let { state.put(CONTEXT, c.id, LibraryMark(c.version, it)) }
                    report.pulled++
                }
            }
        }
        if (pullOnly) return
        for (c in mine.values.filter { it.id !in serverIds }) {
            if (!sendable(c)) continue                                // too long for the server: stays local
            try {
                withSourceFallback(c) { src -> remote.createContext(c.id, c.name.take(200), c.text, src) }
                state.put(CONTEXT, c.id, LibraryMark(1, hashOf(c)))
                report.pushed++
            } catch (e: ServerException) {
                if (e.status != 409) throw e
            }
        }
    }

    // ───────────────────────── plumbing ─────────────────────────

    private suspend fun ignore404(block: suspend () -> Unit) {
        try { block() } catch (e: ServerException) { if (e.status != 404) throw e }
    }

    private fun hashOf(t: LocalTemplate) = hashTemplate(t.name, t.description, t.sections)
    private fun hashOf(t: WebTemplate) = hashTemplate(t.name, t.description, t.sections)
    private fun hashOf(c: LocalContext) = hashContext(c.name, c.text, c.sources)
    private fun hashOf(c: WebContext) = hashContext(c.name, c.text, c.sources)

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
        private const val TEMPLATE = "template"
        private const val CONTEXT = "context"

        /** Stable id for a context's source row, so pushing twice never duplicates it. */
        fun sourceId(contextId: String, recordingId: String): String =
            UUID.nameUUIDFromBytes("$contextId|src|$recordingId".toByteArray(StandardCharsets.UTF_8)).toString()

        private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

        // Cut to the same lengths as what is sent, so a long name does not look "edited" against its own server copy.
        internal fun hashTemplate(name: String, description: String, sections: List<WebTemplateSection>) =
            sha(name.take(200) + "|" + description.take(1000) + "|" + sections.joinToString("¦") { "${it.title.take(200)}~${it.type}~${it.instruction.take(2000)}" })

        internal fun hashContext(name: String, text: String, sources: List<WebContextSource>) =
            sha(name.take(200) + "|" + text + "|" + sources.filter { it.recordingId != null }.map { "${it.recordingId}~${it.kind}" }.sorted().joinToString(","))
    }
}
