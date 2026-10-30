package com.inteldes.app.data.web

import com.google.gson.Gson
import com.inteldes.app.data.server.ServerException
import com.inteldes.app.data.sync.AuthException
import kotlinx.coroutines.CancellationException
import java.io.File
import java.security.MessageDigest

/** The server side of the sync. The real one talks HTTP ([WebApi]); the tests use an in-memory fake. */
interface WebRemote {
    suspend fun changes(since: Long): WebChangesPage

    /** Null when the server doesn't have it (404). */
    suspend fun getAggregate(id: String): WebAggregate?

    /** [ifMatch] = the metadata version this client knows (0 = create). A stale version is a [ServerException] 409. */
    suspend fun putRecording(id: String, input: WebRecordingInput, ifMatch: Int): WebRecording
    suspend fun putTranscript(id: String, segments: List<WebSegment>, ifMatch: Int): Int
    suspend fun putNotes(id: String, sections: List<WebSection>, ifMatch: Int): Int

    /** Merged by id on the server; an entry with `deletedAt` deletes. */
    suspend fun putMarks(id: String, marks: List<WebMark>)
    suspend fun postChat(id: String, messages: List<WebChat>)

    /** Merged by id on the server; the photo files must be uploaded first (`409 blob_missing` otherwise). */
    suspend fun putPhotos(id: String, photos: List<WebPhoto>)
    suspend fun deleteRecording(id: String)
    suspend fun listCategories(): List<WebCategory>
    suspend fun putCategory(id: String, label: String, orderIndex: Int, ifMatch: Int): WebCategory
    suspend fun blobExists(sha256: String): Boolean
    suspend fun uploadBlob(sha256: String, mimeType: String, file: File)
    suspend fun downloadBlob(sha256: String, dest: File)
}

class LocalCategory(val id: String, val label: String, val orderIndex: Int)

/** A photo as the phone holds it: [id] is the server's id (or the content-derived one), [file] the image. */
class LocalPhoto(val id: String, val file: File, val takenAtSec: Int?, val caption: String, val createdAt: Long)

/** A recording as the phone holds it, in the shapes the server takes. */
class LocalAggregate(
    val id: String,
    val input: WebRecordingInput,
    val transcript: List<WebSegment>,
    val sections: List<WebSection>,
    val marks: List<WebMark>,
    val chat: List<WebChat>,
    val audioFile: File?,
    val photos: List<LocalPhoto> = emptyList(),
)

/** The phone side. Implementations must never delete a recording on their own. */
interface WebLocal {
    suspend fun recordingIds(): List<String>
    suspend fun exists(id: String): Boolean

    /** Null when missing or still being processed (WORKING: the pipeline owns it, don't touch it). */
    suspend fun aggregate(id: String): LocalAggregate?
    suspend fun hasAudio(id: String): Boolean
    suspend fun hasPhoto(recordingId: String, photoId: String): Boolean
    suspend fun audioTarget(id: String, sha256: String, mimeType: String): File
    suspend fun photoTarget(sha256: String): File
    suspend fun attachAudio(id: String, file: File)

    /** Creates or updates the recording from [server]: transcript/notes replaced, marks/chat/photos merged by id. */
    suspend fun applyServer(server: WebAggregate, audio: File?, photoFiles: Map<String, File>)

    /**
     * Merges ONLY marks, chat and photos from [server] (by id, web tombstones included). Never touches the
     * recording's metadata, transcript or notulensi. Returns false when it declined (recording missing, or
     * being processed here right now): the caller then tries again on a later sync.
     */
    suspend fun applyChildren(server: WebAggregate, photoFiles: Map<String, File>): Boolean
    suspend fun categories(): List<LocalCategory>
    suspend fun addCategory(id: String, label: String, orderIndex: Int)
}

/** What was last agreed with the server for one recording: its versions, and a hash of each part as pushed/pulled. */
data class WebState(
    val recordingId: String,
    val serverVersion: Int,
    val transcriptVersion: Int,
    val notesVersion: Int,
    val metaHash: String,
    val transcriptHash: String,
    val notesHash: String,
    val marksHash: String,
    val chatHash: String,
    val audioSha: String?,
    val audioLen: Long,
    val pushedMarksJson: String,
    val audioMissing: Boolean,
    val photosHash: String = "",
    val pushedPhotosJson: String = "[]",
    /**
     * The server's `updatedAt` for this recording when we last looked. The three versions above only move when
     * metadata, transcript or notulensi change; marks, photos and chat added on the web move NONE of them, only
     * `updatedAt`. 0 = never recorded (a refresh happens once, which is harmless).
     */
    val serverUpdatedAt: Long = 0,
)

interface WebStateStore {
    suspend fun get(id: String): WebState?
    suspend fun put(state: WebState)
    suspend fun remove(id: String)
    suspend fun all(): List<WebState>
    fun cursor(): Long
    fun setCursor(value: Long)
    fun pendingDeletes(): Set<String>
    fun removePendingDelete(id: String)
    fun retryPulls(): Set<String>
    fun addRetry(id: String)
    fun removeRetry(id: String)
}

data class WebSyncReport(
    var pushed: Int = 0,
    var pulled: Int = 0,
    var deletedOnServer: Int = 0,
    var ignoredServerDeletes: Int = 0,
    var failed: Int = 0,
    var firstError: String? = null,
)

/**
 * Two-way sync of recordings between this phone and the IntelDes server (web).
 *
 * Safety rules (the phone's recordings are the user's data):
 *  - NOTHING here deletes a local recording. A delete made on the server/web is counted and ignored.
 *  - When both sides changed the same recording, the PHONE wins; the server keeps the replaced notes in its revisions.
 *  - Empty never overwrites non-empty: an empty transcript/notulensi is not pushed over a filled one.
 *  - A recording still being processed (WORKING) is neither pushed nor overwritten.
 *  - The first time a recording meets the server, the richer side wins (notulensi > transcript > bare), phone on a tie.
 *  - A phone-side delete (the user deleted it here) IS sent to the server.
 */
class WebSyncEngine(
    private val local: WebLocal,
    private val remote: WebRemote,
    private val store: WebStateStore,
    private val gson: Gson = Gson(),
) {
    suspend fun run(progress: (String) -> Unit = {}): WebSyncReport {
        val report = WebSyncReport()
        progress("Kategori…")
        guarded(report, "kategori") { syncCategories() }
        guarded(report, "hapus") { flushDeletes(report) }
        progress("Mengambil perubahan dari server…")
        pull(report)
        val ids = local.recordingIds()
        ids.forEachIndexed { i, id ->
            progress("Mengirim rekaman ${i + 1}/${ids.size}")
            guarded(report, id) { pushOne(id, report) }
        }
        guarded(report, "audio") { retryMissingAudio() }
        return report
    }

    // ───────────────────────── categories ─────────────────────────

    private suspend fun syncCategories() {
        val server = remote.listCategories().filter { it.deletedAt == null }
        val mine = local.categories()
        for (c in server) {
            if (mine.none { it.label.equals(c.label, ignoreCase = true) }) local.addCategory(c.id, c.label, c.orderIndex)
        }
        for (c in mine) {
            if (server.none { it.label.equals(c.label, ignoreCase = true) }) {
                try {
                    remote.putCategory(c.id, c.label, c.orderIndex, 0)
                } catch (e: ServerException) {
                    if (e.status != 409) throw e   // 409 = the label already exists there (e.g. created meanwhile): fine
                }
            }
        }
    }

    // ───────────────────────── deletes made on this phone ─────────────────────────

    private suspend fun flushDeletes(report: WebSyncReport) {
        for (id in store.pendingDeletes()) {
            try {
                remote.deleteRecording(id)
            } catch (e: ServerException) {
                if (e.status != 404) throw e
            }
            store.removePendingDelete(id)
            store.remove(id)
            report.deletedOnServer++
        }
    }

    // ───────────────────────── pull ─────────────────────────

    private suspend fun pull(report: WebSyncReport) {
        for (id in store.retryPulls()) guarded(report, id) { pullRecording(id, report) }
        var since = store.cursor()
        while (true) {
            val page = try {
                remote.changes(since)
            } catch (e: CancellationException) { throw e } catch (e: AuthException) { throw e } catch (e: Exception) {
                fail(report, "perubahan", e); return
            }
            for (c in page.items) {
                if (c.entity != "recording") continue
                if (c.op == "delete") { report.ignoredServerDeletes++; continue }
                try {
                    pullRecording(c.id, report)
                } catch (e: CancellationException) { throw e } catch (e: AuthException) { throw e } catch (e: Exception) {
                    store.addRetry(c.id)            // the cursor moves on, so remember to come back to this one
                    fail(report, c.id, e)
                }
            }
            since = page.next_seq
            store.setCursor(since)
            if (!page.more) break
        }
    }

    private suspend fun pullRecording(id: String, report: WebSyncReport) {
        if (id in store.pendingDeletes()) return                    // deleted on this phone, the server doesn't know yet: don't bring it back
        val server = remote.getAggregate(id)
        if (server == null) { store.removeRetry(id); return }
        if (server.recording.deletedAt != null) { report.ignoredServerDeletes++; store.removeRetry(id); return }
        val st = store.get(id)
        if (st != null && st.serverVersion == server.recording.version &&
            st.transcriptVersion == server.transcript.version && st.notesVersion == server.notes.version
        ) {
            // Same versions, but the web may still have added/removed marks, photos or chat (they bump `updatedAt` only).
            if (st.serverUpdatedAt != server.recording.updatedAt && !refreshChildren(server, st)) return   // incomplete: retried next run
            store.removeRetry(id)
            return                                                // nothing else new (typically the echo of our own push)
        }

        when {
            !local.exists(id) -> adoptFromServer(server, report)
            else -> {
                val agg = local.aggregate(id)
                when {
                    agg == null -> { store.addRetry(id); return }   // being processed here right now: later
                    st == null -> Unit                              // never synced: pushOne decides (first contact)
                    hashesOf(agg).matches(st) -> adoptFromServer(server, report)
                    else -> {                                       // both sides changed: per part, the phone wins where it edited, the web's edit is kept elsewhere
                        val h = hashesOf(agg)
                        val keep = buildSet {
                            if (h.meta != st.metaHash) add("meta")
                            if (h.transcript != st.transcriptHash) add("transcript")
                            if (h.notes != st.notesHash) add("notes")
                        }
                        adoptFromServer(server, report, st, agg, keep)
                    }
                }
            }
        }
        store.removeRetry(id)
    }

    /**
     * Takes the server's copy into the phone. [keep] names the parts the phone edited since the last sync
     * ("meta", "transcript", "notes"): those stay as on the phone and stay marked as unsent, so the next push
     * sends them over the server's version.
     */
    private suspend fun adoptFromServer(
        server: WebAggregate, report: WebSyncReport,
        prior: WebState? = null, phone: LocalAggregate? = null, keep: Set<String> = emptySet(), children: Boolean = false,
    ) {
        val id = server.recording.id
        if (phone != null) {
            val i = phone.input
            val s = server.recording
            val mine = if ("meta" in keep) s.copy(
                title = i.title, kind = i.kind, durationSec = i.durationSec, audioMimeType = i.audioMimeType, status = i.status,
                speakerCount = i.speakerCount, engine = i.engine, templateId = i.templateId, templateName = i.templateName,
                summary = i.summary, provider = i.provider, notesGeneratedAt = i.notesGeneratedAt, notulensiDensity = i.notulensiDensity,
                locationName = i.locationName, latitude = i.latitude, longitude = i.longitude, errorMessage = i.errorMessage,
            ) else s
            return adoptMerged(
                WebAggregate(
                    mine,
                    if ("transcript" in keep) WebTranscriptPart(server.transcript.version, phone.transcript) else server.transcript,
                    if ("notes" in keep) WebNotesPart(server.notes.version, phone.sections) else server.notes,
                    keptMarks(server, prior, phone), keptPhotos(server, prior, phone), server.chat,
                ),
                server, report, prior, keep, children = true,
            )
        }
        adoptMerged(server, server, report, prior, emptySet(), children)
    }

    private suspend fun adoptMerged(server: WebAggregate, original: WebAggregate, report: WebSyncReport, prior: WebState?, keep: Set<String>, children: Boolean) {
        val id = server.recording.id
        val sha = server.recording.audioBlobSha256
        var audio: File? = null
        var audioMissing = false
        if (sha != null && !local.hasAudio(id)) {
            val target = local.audioTarget(id, sha, server.recording.audioMimeType)
            audio = try { remote.downloadBlob(sha, target); target } catch (e: CancellationException) { throw e } catch (e: AuthException) { throw e } catch (e: Exception) { audioMissing = true; null }
        }
        val photoFiles = downloadPhotos(server)
        local.applyServer(server, audio, photoFiles)
        val after = local.aggregate(id)
        val h = if (after != null) hashesOf(after) else null
        val file = after?.audioFile
        store.put(
            WebState(
                recordingId = id,
                serverVersion = server.recording.version, transcriptVersion = server.transcript.version, notesVersion = server.notes.version,
                // a kept part stays at its old hash, so it still counts as "edited on the phone" and is pushed next
                metaHash = if ("meta" in keep && prior != null) prior.metaHash else h?.meta.orEmpty(),
                transcriptHash = if ("transcript" in keep && prior != null) prior.transcriptHash else h?.transcript.orEmpty(),
                notesHash = if ("notes" in keep && prior != null) prior.notesHash else h?.notes.orEmpty(),
                // Marks, chat and photos are merged by id, so anything only the phone has must still go up: when the phone
                // held its own copy (merge / first contact) these stay "dirty" and the next push sends the union.
                marksHash = if (children) prior?.marksHash.orEmpty() else h?.marks.orEmpty(),
                chatHash = if (children) prior?.chatHash.orEmpty() else h?.chat.orEmpty(),
                photosHash = if (children) prior?.photosHash.orEmpty() else h?.photos.orEmpty(),
                audioSha = sha, audioLen = file?.length() ?: 0,
                pushedMarksJson = gson.toJson(
                    (marksOf(prior?.pushedMarksJson) + server.marks.filter { it.deletedAt == null } + (after?.marks ?: emptyList())).distinctBy { it.id },
                ),
                pushedPhotosJson = gson.toJson(
                    (photosOf(prior?.pushedPhotosJson) + server.photos.filter { it.deletedAt == null }).distinctBy { it.id },
                ),
                audioMissing = audioMissing,
                serverUpdatedAt = server.recording.updatedAt,
            ),
        )
        report.pulled++
    }

    /** Downloads the photo files the phone lacks. A failed one is skipped (not worth failing the recording). */
    private suspend fun downloadPhotos(server: WebAggregate): Map<String, File> {
        val id = server.recording.id
        val files = mutableMapOf<String, File>()
        for (p in server.photos.filter { it.deletedAt == null }) {
            if (local.hasPhoto(id, p.id)) continue
            val dest = local.photoTarget(p.blobSha256)
            try { remote.downloadBlob(p.blobSha256, dest); files[p.id] = dest } catch (e: CancellationException) { throw e } catch (e: AuthException) { throw e } catch (e: Exception) { /* photo later */ }
        }
        return files
    }

    /**
     * Brings in marks, photos and chat the web changed while the recording's versions stayed put. Returns false
     * (and queues a retry) when it could not finish, so the new [WebState.serverUpdatedAt] is only saved once
     * everything arrived. Hashes of the children are moved forward only if the phone had no unsent children of
     * its own; otherwise they stay "dirty" and the next push sends the union (the server merges by id).
     */
    private suspend fun refreshChildren(server: WebAggregate, st: WebState): Boolean {
        val id = server.recording.id
        val before = local.aggregate(id)
        val clean = before != null && hashesOf(before).let { it.marks == st.marksHash && it.chat == st.chatHash && it.photos == st.photosHash }
        // A mark/photo the phone deleted since the last sync is still on the server until the next push tombstones it:
        // don't let the pull bring it back.
        val incoming = if (before != null) server.copy(marks = keptMarks(server, st, before), photos = keptPhotos(server, st, before)) else server
        val photoFiles = downloadPhotos(incoming)
        if (!local.applyChildren(incoming, photoFiles)) { store.addRetry(id); return false }
        if (incoming.photos.any { it.deletedAt == null && it.id !in photoFiles && !local.hasPhoto(id, it.id) }) { store.addRetry(id); return false }
        val after = local.aggregate(id)
        val h = if (after != null) hashesOf(after) else null
        store.put(
            st.copy(
                serverUpdatedAt = server.recording.updatedAt,
                marksHash = if (clean && h != null) h.marks else st.marksHash,
                chatHash = if (clean && h != null) h.chat else st.chatHash,
                photosHash = if (clean && h != null) h.photos else st.photosHash,
                pushedMarksJson = gson.toJson((marksOf(st.pushedMarksJson) + server.marks.filter { it.deletedAt == null } + (after?.marks ?: emptyList())).distinctBy { it.id }),
                pushedPhotosJson = gson.toJson((photosOf(st.pushedPhotosJson) + server.photos.filter { it.deletedAt == null }).distinctBy { it.id }),
            ),
        )
        return true
    }

    /** Server marks minus the ones both sides had at the last sync that the phone has since deleted. */
    private fun keptMarks(server: WebAggregate, prior: WebState?, phone: LocalAggregate): List<WebMark> {
        if (prior == null) return server.marks
        val known = marksOf(prior.pushedMarksJson).map { it.id }.toSet()
        val here = phone.marks.map { it.id }.toSet()
        return server.marks.filter { it.id !in known || it.id in here || it.deletedAt != null }
    }

    private fun keptPhotos(server: WebAggregate, prior: WebState?, phone: LocalAggregate): List<WebPhoto> {
        if (prior == null) return server.photos
        val known = photosOf(prior.pushedPhotosJson).map { it.id }.toSet()
        val here = phone.photos.map { it.id }.toSet()
        return server.photos.filter { it.id !in known || it.id in here || it.deletedAt != null }
    }

    private suspend fun retryMissingAudio() {
        for (st in store.all().filter { it.audioMissing && it.audioSha != null }) {
            if (local.hasAudio(st.recordingId)) { store.put(st.copy(audioMissing = false)); continue }
            val sha = st.audioSha ?: continue
            val target = local.audioTarget(st.recordingId, sha, "audio/mp4")
            remote.downloadBlob(sha, target)
            local.attachAudio(st.recordingId, target)
            store.put(st.copy(audioMissing = false, audioLen = target.length()))
        }
    }

    // ───────────────────────── push ─────────────────────────

    private class Versions(val server: Int, val transcript: Int, val notes: Int)

    private suspend fun pushOne(id: String, report: WebSyncReport) {
        val agg = local.aggregate(id) ?: return                     // missing or WORKING
        val st = store.get(id)
        val h = hashesOf(agg)
        if (st != null && h.matches(st)) return

        // Audio goes first: the server rejects a recording whose audio blob isn't there yet (422 blob_missing).
        var sha = st?.audioSha
        var len = st?.audioLen ?: 0L
        val file = agg.audioFile?.takeIf { it.exists() && it.length() > 0 }
        if (file != null && (sha == null || len != file.length())) {
            val computed = sha256(file)
            if (!remote.blobExists(computed)) remote.uploadBlob(computed, agg.input.audioMimeType, file)
            sha = computed
            len = file.length()
        }
        val input = agg.input.copy(audioBlobSha256 = sha)

        if (st == null) {
            val server = remote.getAggregate(id)
            when {
                server == null -> pushParts(id, agg, input, h, Versions(0, 0, 0), prior = null, sha, len, report)
                server.recording.deletedAt != null -> {              // removed on the web: don't resurrect it, don't delete it here either
                    report.ignoredServerDeletes++
                    saveState(id, h, Versions(server.recording.version, server.transcript.version, server.notes.version), sha, len, agg)
                }
                richness(server) > richness(agg) -> {
                    adoptFromServer(server, report, null, agg, emptySet(), children = true)
                    pushOne(id, report)                              // now that it has a state: send what only the phone had (marks, chat, photos)
                }
                else -> pushParts(id, agg, input, h, Versions(server.recording.version, server.transcript.version, server.notes.version), prior = null, sha, len, report)
            }
        } else {
            pushParts(id, agg, input, h, Versions(st.serverVersion, st.transcriptVersion, st.notesVersion), prior = st, sha, len, report)
        }
    }

    private suspend fun pushParts(
        id: String, agg: LocalAggregate, input: WebRecordingInput, h: Hashes, base: Versions,
        prior: WebState?, sha: String?, len: Long, report: WebSyncReport, retried: Boolean = false,
    ) {
        val everything = prior == null || retried
        fun dirty(old: String?, now: String) = everything || old != now
        try {
            var meta = base.server
            var transcript = base.transcript
            var notes = base.notes
            if (dirty(prior?.metaHash, h.meta) || sha != prior?.audioSha) meta = remote.putRecording(id, input, base.server).version
            if (agg.transcript.isNotEmpty() && dirty(prior?.transcriptHash, h.transcript)) transcript = remote.putTranscript(id, agg.transcript, base.transcript)
            if (agg.sections.isNotEmpty() && dirty(prior?.notesHash, h.notes)) notes = remote.putNotes(id, agg.sections, base.notes)
            if (dirty(prior?.marksHash, h.marks)) {
                val before = prior?.let { runCatching { gson.fromJson(it.pushedMarksJson, Array<WebMark>::class.java).toList() }.getOrDefault(emptyList()) }.orEmpty()
                val now = System.currentTimeMillis()
                val gone = before.filter { b -> agg.marks.none { it.id == b.id } }.map { it.copy(deletedAt = now) }
                if (agg.marks.isNotEmpty() || gone.isNotEmpty()) remote.putMarks(id, agg.marks + gone)
            }
            if (agg.chat.isNotEmpty() && dirty(prior?.chatHash, h.chat)) remote.postChat(id, agg.chat)
            val photosJson = if (dirty(prior?.photosHash, h.photos)) pushPhotos(id, agg, prior) else prior?.pushedPhotosJson ?: "[]"
            saveState(id, h, Versions(meta, transcript, notes), sha, len, agg, photosJson)
            report.pushed++
        } catch (e: ServerException) {
            if (e.status != 409 || retried) throw e
            // Stale version: someone changed it on the server since we last looked. Phone wins — rebase on the
            // server's current versions and push everything we hold (the server archives what it replaces).
            val server = remote.getAggregate(id)
            when {
                server == null -> pushParts(id, agg, input, h, Versions(0, 0, 0), prior, sha, len, report, retried = true)
                server.recording.deletedAt != null -> {
                    report.ignoredServerDeletes++
                    saveState(id, h, Versions(server.recording.version, server.transcript.version, server.notes.version), sha, len, agg)
                }
                else -> pushParts(id, agg, input, h, Versions(server.recording.version, server.transcript.version, server.notes.version), prior, sha, len, report, retried = true)
            }
        }
    }

    /** Uploads the photo files the server lacks, then merges the list. A photo removed on the phone is sent as a tombstone. */
    private suspend fun pushPhotos(id: String, agg: LocalAggregate, prior: WebState?): String {
        val before = photosOf(prior?.pushedPhotosJson)
        val shaOf = before.associate { it.id to it.blobSha256 }
        val list = mutableListOf<WebPhoto>()
        for (p in agg.photos) {
            val sha = sha256(p.file)
            if (!remote.blobExists(sha)) remote.uploadBlob(sha, mimeOf(p.file), p.file)
            list += WebPhoto(p.id, sha, p.takenAtSec?.coerceAtLeast(0), p.caption.take(1000), p.createdAt)
        }
        val now = System.currentTimeMillis()
        val gone = before.filter { b -> agg.photos.none { it.id == b.id } }.map { WebPhoto(it.id, shaOf.getValue(it.id), it.takenAtSec, it.caption, it.createdAt, deletedAt = now) }
        if (list.isNotEmpty() || gone.isNotEmpty()) remote.putPhotos(id, list + gone)
        return gson.toJson(list)
    }

    private fun mimeOf(file: File): String = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    private fun marksOf(json: String?): List<WebMark> =
        if (json.isNullOrBlank()) emptyList() else runCatching { gson.fromJson(json, Array<WebMark>::class.java).toList() }.getOrDefault(emptyList())

    private fun photosOf(json: String?): List<WebPhoto> =
        if (json.isNullOrBlank()) emptyList() else runCatching { gson.fromJson(json, Array<WebPhoto>::class.java).toList() }.getOrDefault(emptyList())

    private suspend fun saveState(id: String, h: Hashes, v: Versions, sha: String?, len: Long, agg: LocalAggregate, photosJson: String = "[]") {
        store.put(
            WebState(
                recordingId = id, serverVersion = v.server, transcriptVersion = v.transcript, notesVersion = v.notes,
                metaHash = h.meta, transcriptHash = h.transcript, notesHash = h.notes, marksHash = h.marks, chatHash = h.chat,
                audioSha = sha, audioLen = len, pushedMarksJson = gson.toJson(agg.marks), audioMissing = false,
                photosHash = h.photos, pushedPhotosJson = photosJson,
            ),
        )
    }

    // ───────────────────────── helpers ─────────────────────────

    private fun richness(server: WebAggregate): Int =
        (if (server.recording.status == "DONE") 4 else 0) + (if (server.notes.sections.isNotEmpty()) 2 else 0) + (if (server.transcript.segments.isNotEmpty()) 1 else 0)

    private fun richness(agg: LocalAggregate): Int =
        (if (agg.input.status == "DONE") 4 else 0) + (if (agg.sections.isNotEmpty()) 2 else 0) + (if (agg.transcript.isNotEmpty()) 1 else 0)

    class Hashes(val meta: String, val transcript: String, val notes: String, val marks: String, val chat: String, val photos: String) {
        fun matches(s: WebState) = meta == s.metaHash && transcript == s.transcriptHash && notes == s.notesHash && marks == s.marksHash && chat == s.chatHash && photos == s.photosHash
    }

    private fun hashesOf(agg: LocalAggregate): Hashes = Hashes(
        meta = sha256(gson.toJson(agg.input.copy(audioBlobSha256 = null)) + "|len=" + (agg.audioFile?.length() ?: 0L)),
        transcript = sha256(gson.toJson(agg.transcript)),
        notes = sha256(gson.toJson(agg.sections)),
        marks = sha256(gson.toJson(agg.marks.sortedBy { it.id })),
        chat = sha256(gson.toJson(agg.chat.sortedBy { it.id })),
        photos = sha256(gson.toJson(agg.photos.sortedBy { it.id }.map { listOf(it.id, it.takenAtSec, it.caption, it.createdAt, it.file.length()) })),
    )

    private fun fail(report: WebSyncReport, what: String, e: Exception) {
        report.failed++
        if (report.firstError == null) report.firstError = "$what: ${e.message ?: e.javaClass.simpleName}"
    }

    /** One bad recording (a network hiccup mid-upload) must not stop the others. */
    private suspend fun guarded(report: WebSyncReport, what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthException) {
            throw e
        } catch (e: Exception) {
            fail(report, what, e)
        }
    }

    companion object {
        fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
