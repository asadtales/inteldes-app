package com.inteldes.app.data.sync

import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What is stored on Drive for one recording (`rec_<id>.json`). Audio and photos are separate files
 * in the same folder, referenced by name, so a metadata edit never re-uploads megabytes of audio.
 */
data class RecordingSnapshot(
    val id: String,
    val title: String,
    val kind: String,
    val createdAt: Long,
    val durationSec: Int,
    val audioMimeType: String,
    val status: String,
    val speakerCount: Int,
    val engine: String,
    val templateId: String,
    val templateName: String,
    val summary: String?,
    val provider: String?,
    val notesGeneratedAt: Long?,
    val notulensiDensity: String?,
    val locationName: String?,
    val latitude: Double?,
    val longitude: Double?,
    val audioName: String?,
    val transcript: List<SegmentJson>,
    val sections: List<SectionJson>,
    val marks: List<MarkJson>,
    val photos: List<PhotoJson>,
)

data class SegmentJson(val speaker: String, val timestampSec: Int, val text: String)
data class SectionJson(val title: String, val type: String, val payload: String)
data class MarkJson(val timestampSec: Int, val label: String, val createdAt: Long)
data class PhotoJson(val name: String, val takenAtSec: Int?, val caption: String, val createdAt: Long)

/** A snapshot plus the real files behind it (only the local side has these as [File]s). */
class LocalSnapshot(val snapshot: RecordingSnapshot, val audioFile: File?, val photoFiles: Map<String, File>)

/** A ready-to-read output document (Word notulensi, plain-text transcript) generated on this device. */
class DocFile(val name: String, val mimeType: String, val file: File)

data class RemoteFile(
    val id: String,
    val name: String,
    val md5: String?,
    val parentId: String? = null,
    val isFolder: Boolean = false,
)

/** The cloud side (Google Drive in the app, an in-memory fake in tests): a tree of folders and files. */
interface SyncRemote {
    /** The app's own top folder (INTELDES), created on first use. */
    suspend fun rootId(): String

    /** Every file and folder the app can see (the engine ignores anything outside [rootId]). */
    suspend fun listFiles(): List<RemoteFile>
    suspend fun createFolder(parentId: String, name: String): RemoteFile

    /** Renames and/or moves: pass [newName] and/or [newParentId] (+ the [oldParentId] to leave). */
    suspend fun update(fileId: String, newName: String?, newParentId: String?, oldParentId: String?): RemoteFile
    suspend fun download(file: RemoteFile): ByteArray
    suspend fun downloadTo(file: RemoteFile, dest: File)
    suspend fun uploadBytes(parentId: String, name: String, bytes: ByteArray, mimeType: String, existingId: String?): RemoteFile
    suspend fun uploadFile(parentId: String, name: String, file: File, mimeType: String, existingId: String?): RemoteFile
    suspend fun delete(fileId: String)
}

/** The device side. */
interface SyncLocal {
    suspend fun recordingIds(): List<String>

    /** Null when the recording is missing or still being processed (not safe to sync yet). */
    suspend fun snapshot(id: String): LocalSnapshot?
    suspend fun documents(id: String): List<DocFile>
    suspend fun audioTarget(id: String, name: String): File
    suspend fun photoTarget(name: String): File

    /** Replaces the recording (create or overwrite) with [snapshot]; media already sit in the given files. */
    suspend fun apply(snapshot: RecordingSnapshot, audioFile: File?, photoFiles: Map<String, File>)
    suspend fun delete(id: String)
    suspend fun categories(): List<String>
    suspend fun addCategories(labels: List<String>)
}

data class RecState(val localHash: String, val remoteMd5: String?)
data class SyncState(val recordings: Map<String, RecState> = emptyMap(), val pendingDeletes: Set<String> = emptySet())

interface SyncStateStorage {
    fun load(): SyncState
    fun save(state: SyncState)
}

data class SyncReport(
    var uploaded: Int = 0,
    var downloaded: Int = 0,
    var deletedLocal: Int = 0,
    var deletedRemote: Int = 0,
    var migrated: Int = 0,
    var failed: Int = 0,
    var firstError: String? = null,
) {
    val changedSomething get() = uploaded + downloaded + deletedLocal + deletedRemote + migrated > 0
}

object SyncNames {
    const val DELETED = "deleted.json"
    const val CATEGORIES = "categories.json"
    const val NOTULENSI_DOC = "Notulensi.docx"
    const val TRANSCRIPT_DOC = "Transkrip.txt"
    const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    private val recJson = Regex("^rec_(.+)\\.json$")
    private val legacyAudio = Regex("^aud_(.+)\\.([A-Za-z0-9]+)$")
    private val legacyPhoto = Regex("^pho_(.+)__([0-9a-f]{12})\\.jpg$")
    private val folderName = Regex("^(\\d{4})-(\\d{2})-(\\d{2}) (\\d{4}) - (.*)$")
    private val months = listOf("Januari", "Februari", "Maret", "April", "Mei", "Juni", "Juli", "Agustus", "September", "Oktober", "November", "Desember")

    fun json(id: String) = "rec_$id.json"
    fun audio(ext: String) = "Rekaman.$ext"
    fun photo(hash12: String) = "Foto-$hash12.jpg"

    fun jsonOwner(name: String): String? = recJson.matchEntire(name)?.groupValues?.get(1)

    /** Pre-folder layout: everything for a recording sat loose in the root with the id in the name. */
    fun legacyOwner(name: String): String? =
        jsonOwner(name) ?: legacyAudio.matchEntire(name)?.groupValues?.get(1) ?: legacyPhoto.matchEntire(name)?.groupValues?.get(1)

    fun modernName(legacyName: String): String =
        legacyAudio.matchEntire(legacyName)?.let { audio(it.groupValues[2]) }
            ?: legacyPhoto.matchEntire(legacyName)?.let { photo(it.groupValues[2]) }
            ?: legacyName

    fun cleanTitle(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|]"), "-").replace(Regex("\\s+"), " ").trim().take(60).trim().ifBlank { "Rapat" }

    /** Date part of a recording's folder name; computed once, then reused so time zones can't rename it. */
    fun datePrefix(createdAt: Long): String = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date(createdAt))

    fun recordingFolderName(prefix: String, title: String) = "$prefix - ${cleanTitle(title)}"

    /** Splits an existing folder name into its date prefix, or null if it isn't one of ours. */
    fun prefixOf(folder: String): String? = folderName.matchEntire(folder)?.let { "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]} ${it.groupValues[4]}" }

    fun yearOf(prefix: String) = prefix.substring(0, 4)
    fun monthOf(prefix: String) = prefix.substring(5, 7).let { m -> "$m - ${months.getOrElse(m.toInt() - 1) { m }}" }
}

/** The Drive folder tree as the engine sees it, kept in step with what the engine creates/moves/deletes. */
private class RemoteTree(val rootId: String, files: List<RemoteFile>) {
    private val byId = LinkedHashMap<String, RemoteFile>()

    init {
        val all = files.associateBy { it.id }
        fun underRoot(f: RemoteFile): Boolean {
            var cur: RemoteFile? = f; var hops = 0
            while (cur != null && hops++ < 20) { if (cur.parentId == rootId) return true; cur = cur.parentId?.let(all::get) }
            return false
        }
        files.filter(::underRoot).forEach { byId[it.id] = it }
    }

    fun all() = byId.values.toList()
    fun get(id: String?) = id?.let(byId::get)
    fun children(parentId: String) = byId.values.filter { it.parentId == parentId }
    fun childNamed(parentId: String, name: String, folder: Boolean) = children(parentId).firstOrNull { it.name == name && it.isFolder == folder }
    fun put(file: RemoteFile) { byId[file.id] = file }
    fun remove(id: String) {
        byId.remove(id)
        byId.values.filter { it.parentId == id }.map { it.id }.forEach(::remove)
    }
    fun jsonOf(recordingId: String) = byId.values.firstOrNull { !it.isFolder && it.name == SyncNames.json(recordingId) }
    fun recordingIds() = byId.values.filter { !it.isFolder }.mapNotNull { SyncNames.jsonOwner(it.name) }.toSet()
    fun looseFilesOf(recordingId: String) = children(rootId).filter { !it.isFolder && SyncNames.legacyOwner(it.name) == recordingId }
}

/**
 * Two-way sync of recordings between this device and one Drive folder.
 *
 * Layout on Drive (one self-contained folder per recording):
 *
 *     INTELDES/2026/10 - Oktober/2026-10-21 1030 - Judul rapat/
 *         rec_<id>.json   Rekaman.m4a   Foto-<hash>.jpg   Notulensi.docx   Transkrip.txt
 *
 *  - A recording present on one side only is copied to the other.
 *  - On both sides: whichever changed since the last sync wins; if both changed, this device wins.
 *  - Deleting a recording writes its id to `deleted.json`; every device removes it on its next sync.
 *  - Categories are merged (union); category deletions are not synced.
 *  - Word/transcript documents are write-only outputs, regenerated whenever the recording changes.
 *  - Recordings from the earlier flat layout are moved into folders (no re-upload) on the next sync.
 */
class SyncEngine(
    private val local: SyncLocal,
    private val remote: SyncRemote,
    private val storage: SyncStateStorage,
    private val gson: Gson = Gson(),
    /** Recordings the user keeps out of the sync: neither uploaded nor overwritten. Asked once per run. */
    private val excluded: () -> Set<String> = { emptySet() },
) {
    suspend fun run(progress: (String) -> Unit = {}): SyncReport {
        val report = SyncReport()
        val state = storage.load()
        val recStates = state.recordings.toMutableMap()
        val root = remote.rootId()
        val tree = RemoteTree(root, remote.listFiles())

        // 1. Deletions first, so a deleted recording isn't re-created by the steps below.
        val remoteTombstones = tree.childNamed(root, SyncNames.DELETED, folder = false)
            ?.let { parseStringList(remote.download(it)).toSet() } ?: emptySet()
        val tombstones = remoteTombstones + state.pendingDeletes
        if (tombstones.isNotEmpty()) {
            progress("Menerapkan penghapusan…")
            val localIds = local.recordingIds().toSet()
            for (id in tombstones) {
                guarded(report, id) {
                    val folder = tree.jsonOf(id)?.parentId?.takeIf { it != root }
                    val doomed = listOfNotNull(folder?.let(tree::get)) + tree.looseFilesOf(id)
                    doomed.forEach { remote.delete(it.id); tree.remove(it.id); report.deletedRemote++ }
                    if (id in localIds) { local.delete(id); report.deletedLocal++ }
                    recStates.remove(id)
                }
            }
            if (tombstones != remoteTombstones) {
                val existing = tree.childNamed(root, SyncNames.DELETED, folder = false)
                tree.put(remote.uploadBytes(root, SyncNames.DELETED, gson.toJson(tombstones.sorted()).toByteArray(), "application/json", existing?.id))
            }
        }
        val pendingAfter = if (report.failed == 0) emptySet() else state.pendingDeletes

        // 2. Move recordings left over from the flat layout into their folders.
        val legacyIds = tree.children(root).filter { !it.isFolder }.mapNotNull { SyncNames.legacyOwner(it.name) }.toSet() - tombstones
        for (id in legacyIds) {
            progress("Merapikan folder Drive…")
            guarded(report, id) { migrateLegacy(id, root, tree, recStates, report) }
        }

        // 3. Recordings.
        val localIds = local.recordingIds().toSet()
        val all = (localIds + tree.recordingIds() - tombstones - excluded()).sorted()
        all.forEachIndexed { index, id ->
            progress("Sinkronisasi rekaman ${index + 1}/${all.size}")
            guarded(report, id) { syncOne(id, id in localIds, root, tree, recStates, report) }
        }

        // 4. Categories.
        guarded(report, "kategori") { syncCategories(root, tree) }

        storage.save(SyncState(recStates, pendingAfter))
        return report
    }

    private suspend fun syncOne(
        id: String,
        existsLocally: Boolean,
        root: String,
        tree: RemoteTree,
        recStates: MutableMap<String, RecState>,
        report: SyncReport,
    ) {
        // Still loose in the root = its move into a folder failed this round; syncing now would duplicate it.
        if (tree.looseFilesOf(id).isNotEmpty()) return
        val remoteJson = tree.jsonOf(id)?.takeIf { it.parentId != root }
        val snap = if (existsLocally) local.snapshot(id) else null
        if (existsLocally && snap == null) return // still processing; next round

        when {
            snap != null && remoteJson == null -> { upload(id, snap, root, tree, recStates); report.uploaded++ }
            snap == null && remoteJson != null -> { download(id, remoteJson, tree, recStates); report.downloaded++ }
            snap != null && remoteJson != null -> {
                val known = recStates[id]
                val localChanged = known == null || hash(snap.snapshot) != known.localHash
                val remoteChanged = known == null || remoteJson.md5 != known.remoteMd5
                when {
                    localChanged -> { upload(id, snap, root, tree, recStates); report.uploaded++ } // also wins a true conflict
                    remoteChanged -> { download(id, remoteJson, tree, recStates); report.downloaded++ }
                    else -> addMissingDocuments(id, snap.snapshot, remoteJson.parentId!!, tree)
                }
            }
        }
    }

    private suspend fun upload(id: String, snap: LocalSnapshot, root: String, tree: RemoteTree, recStates: MutableMap<String, RecState>) {
        val s = snap.snapshot
        val folder = ensureRecordingFolder(id, s.createdAt, s.title, root, tree)
        val inFolder = tree.children(folder.id).associateBy { it.name }
        // Media first, json last: Drive never holds a json that points at a file that isn't there yet.
        snap.audioFile?.let { f ->
            val name = s.audioName ?: return@let
            if (name !in inFolder) tree.put(remote.uploadFile(folder.id, name, f, s.audioMimeType, null))
        }
        snap.photoFiles.forEach { (name, f) -> if (name !in inFolder) tree.put(remote.uploadFile(folder.id, name, f, "image/jpeg", null)) }
        uploadDocuments(id, folder.id, tree)
        val json = gson.toJson(s)
        val rf = remote.uploadBytes(folder.id, SyncNames.json(id), json.toByteArray(), "application/json", inFolder[SyncNames.json(id)]?.id)
        tree.put(rf)
        recStates[id] = RecState(sha256(json), rf.md5)
    }

    /** Word notulensi + transcript text, regenerated from the current data every time a recording is uploaded. */
    private suspend fun uploadDocuments(id: String, folderId: String, tree: RemoteTree) {
        val docs = local.documents(id)
        try {
            docs.forEach { doc ->
                val existing = tree.childNamed(folderId, doc.name, folder = false)
                tree.put(remote.uploadFile(folderId, doc.name, doc.file, doc.mimeType, existing?.id))
            }
        } finally {
            docs.forEach { it.file.delete() }
        }
    }

    /** A recording synced before documents existed (or whose documents were removed) gets them without any other change. */
    private suspend fun addMissingDocuments(id: String, s: RecordingSnapshot, folderId: String, tree: RemoteTree) {
        val needsNotulensi = s.sections.isNotEmpty() && tree.childNamed(folderId, SyncNames.NOTULENSI_DOC, false) == null
        val needsTranscript = s.transcript.isNotEmpty() && tree.childNamed(folderId, SyncNames.TRANSCRIPT_DOC, false) == null
        if (needsNotulensi || needsTranscript) uploadDocuments(id, folderId, tree)
    }

    private suspend fun download(id: String, remoteJson: RemoteFile, tree: RemoteTree, recStates: MutableMap<String, RecState>) {
        val folderFiles = tree.children(remoteJson.parentId!!).associateBy { it.name }
        val s = gson.fromJson(remote.download(remoteJson).decodeToString(), RecordingSnapshot::class.java)
        val audio = s.audioName?.let { name ->
            folderFiles[name]?.let { rf ->
                local.audioTarget(id, name).also { if (!it.exists() || it.length() == 0L) remote.downloadTo(rf, it) }
            }
        }
        val photos = mutableMapOf<String, File>()
        s.photos.forEach { p ->
            val rf = folderFiles[p.name] ?: return@forEach
            val dest = local.photoTarget(p.name)
            if (!dest.exists() || dest.length() == 0L) remote.downloadTo(rf, dest)
            photos[p.name] = dest
        }
        local.apply(s.copy(audioName = if (audio != null) s.audioName else null, photos = s.photos.filter { it.name in photos }), audio, photos)
        val after = local.snapshot(id)?.snapshot
        recStates[id] = RecState(if (after != null) hash(after) else sha256(gson.toJson(s)), remoteJson.md5)
    }

    /** Finds or creates `root/<year>/<month>/<date - title>` for a recording; renames the folder if the title changed. */
    private suspend fun ensureRecordingFolder(id: String, createdAt: Long, title: String, root: String, tree: RemoteTree): RemoteFile {
        val existing = tree.jsonOf(id)?.parentId?.takeIf { it != root }?.let(tree::get)
        if (existing != null) {
            val prefix = SyncNames.prefixOf(existing.name) ?: return existing // user renamed it: leave it alone
            val wanted = SyncNames.recordingFolderName(prefix, title)
            if (wanted == existing.name) return existing
            return remote.update(existing.id, wanted, null, null).also(tree::put)
        }
        val prefix = SyncNames.datePrefix(createdAt)
        val year = findOrCreateFolder(root, SyncNames.yearOf(prefix), tree)
        val month = findOrCreateFolder(year.id, SyncNames.monthOf(prefix), tree)
        return remote.createFolder(month.id, SyncNames.recordingFolderName(prefix, title)).also(tree::put)
    }

    private suspend fun findOrCreateFolder(parentId: String, name: String, tree: RemoteTree): RemoteFile =
        tree.childNamed(parentId, name, folder = true) ?: remote.createFolder(parentId, name).also(tree::put)

    /** Moves loose files from the old flat layout into the recording's folder, renaming media to the plain names. */
    private suspend fun migrateLegacy(id: String, root: String, tree: RemoteTree, recStates: MutableMap<String, RecState>, report: SyncReport) {
        val loose = tree.looseFilesOf(id)
        val jsonFile = loose.firstOrNull { it.name == SyncNames.json(id) } ?: return // media without data: leave alone
        val old = gson.fromJson(remote.download(jsonFile).decodeToString(), RecordingSnapshot::class.java)
        val folder = ensureRecordingFolder(id, old.createdAt, old.title, root, tree)
        loose.forEach { f ->
            val moved = remote.update(f.id, SyncNames.modernName(f.name).takeIf { it != f.name }, folder.id, root)
            tree.put(moved.copy(parentId = folder.id))
        }
        val modern = old.copy(
            audioName = old.audioName?.let(SyncNames::modernName),
            photos = old.photos.map { it.copy(name = SyncNames.modernName(it.name)) },
        )
        val json = gson.toJson(modern)
        val rf = remote.uploadBytes(folder.id, SyncNames.json(id), json.toByteArray(), "application/json", tree.jsonOf(id)?.id)
        tree.put(rf)
        recStates[id]?.let { recStates[id] = it.copy(remoteMd5 = rf.md5) }
        report.migrated++
    }

    private suspend fun syncCategories(root: String, tree: RemoteTree) {
        val existing = tree.childNamed(root, SyncNames.CATEGORIES, folder = false)
        val remoteList = existing?.let { parseStringList(remote.download(it)) } ?: emptyList()
        val localList = local.categories()
        val missingLocally = remoteList.filter { r -> localList.none { it.equals(r, ignoreCase = true) } }
        if (missingLocally.isNotEmpty()) local.addCategories(missingLocally)
        val merged = localList + missingLocally
        if (merged.map { it.lowercase() }.toSet() != remoteList.map { it.lowercase() }.toSet()) {
            tree.put(remote.uploadBytes(root, SyncNames.CATEGORIES, gson.toJson(merged).toByteArray(), "application/json", existing?.id))
        }
    }

    /** One bad recording (e.g. a network hiccup mid-upload) must not stop the others. */
    private suspend fun guarded(report: SyncReport, what: String, block: suspend () -> Unit) {
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

    private fun hash(s: RecordingSnapshot) = sha256(gson.toJson(s))

    private fun parseStringList(bytes: ByteArray): List<String> =
        runCatching { gson.fromJson(bytes.decodeToString(), Array<String>::class.java).toList() }.getOrDefault(emptyList())

    companion object {
        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** Thrown when the Google sign-in is no longer valid — stops the whole sync instead of failing item by item. */
class AuthException(message: String) : Exception(message)
