package com.inteldes.app.data.sync

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Calendar

/** In-memory Drive: a tree of folders/files with ids, parents and md5, like the real thing. */
private class FakeRemote : SyncRemote {
    class Entry(val id: String, var name: String, var parent: String?, val folder: Boolean, var bytes: ByteArray = ByteArray(0))

    val entries = linkedMapOf<String, Entry>()
    private var next = 1
    private val root = Entry("root_inteldes", "INTELDES", "my_drive", true).also { entries[it.id] = it }

    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }
    private fun Entry.file() = RemoteFile(id, name, if (folder) null else md5(bytes), parent, folder)

    override suspend fun rootId() = root.id
    override suspend fun listFiles() = entries.values.filter { it.id != root.id }.map { it.file() }
    override suspend fun createFolder(parentId: String, name: String): RemoteFile =
        Entry("d${next++}", name, parentId, true).also { entries[it.id] = it }.file()

    override suspend fun update(fileId: String, newName: String?, newParentId: String?, oldParentId: String?): RemoteFile {
        val e = entries.getValue(fileId)
        newName?.let { e.name = it }
        if (newParentId != null) e.parent = newParentId
        return e.file()
    }

    override suspend fun download(file: RemoteFile) = entries.getValue(file.id).bytes
    override suspend fun downloadTo(file: RemoteFile, dest: File) { dest.parentFile.mkdirs(); dest.writeBytes(entries.getValue(file.id).bytes) }
    override suspend fun uploadBytes(parentId: String, name: String, bytes: ByteArray, mimeType: String, existingId: String?): RemoteFile {
        val e = existingId?.let { entries.getValue(it).also { x -> x.bytes = bytes } }
            ?: Entry("f${next++}", name, parentId, false, bytes).also { entries[it.id] = it }
        return e.file()
    }
    override suspend fun uploadFile(parentId: String, name: String, file: File, mimeType: String, existingId: String?) =
        uploadBytes(parentId, name, file.readBytes(), mimeType, existingId)

    override suspend fun delete(fileId: String) {
        entries.remove(fileId)
        entries.values.filter { it.parent == fileId }.map { it.id }.forEach { delete(it) }
    }

    /** Path below the INTELDES root, e.g. 2026/10 - Oktober/2026-10-21 1030 - Rapat/rec_x.json */
    fun path(e: Entry): String = if (e.parent == root.id) e.name else path(entries.getValue(e.parent!!)) + "/" + e.name
    fun paths() = entries.values.filter { !it.folder }.map { path(it) }.toSet()
    fun file(path: String) = entries.values.firstOrNull { !it.folder && path(it) == path }
}

private class FakeStorage : SyncStateStorage {
    var state = SyncState()
    override fun load() = state
    override fun save(state: SyncState) { this.state = state }
}

private class FakeLocal(private val dir: File) : SyncLocal {
    val recs = linkedMapOf<String, RecordingSnapshot>()
    val processing = mutableSetOf<String>()
    val cats = mutableListOf<String>()
    var documentCalls = 0

    private fun audioFile(id: String) = File(dir, "aud_$id.m4a")
    private fun hash12(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }.take(12)

    fun create(
        id: String, title: String, audio: String? = null, photoBytes: String? = null,
        createdAt: Long = millis(2026, 10, 21, 10, 30), withNotes: Boolean = false,
    ) {
        val photos = mutableListOf<PhotoJson>()
        if (photoBytes != null) {
            val name = SyncNames.photo(hash12(photoBytes.toByteArray()))
            File(dir, name).writeText(photoBytes)
            photos += PhotoJson(name, 12, "foto", 1L)
        }
        if (audio != null) audioFile(id).writeText(audio)
        recs[id] = RecordingSnapshot(
            id, title, "Musdes", createdAt, 60, "audio/mp4", "DONE", 2, "CLOUD", "BUILTIN", "Std", "ringkas", "GEMINI", 5L, "SEDANG",
            null, null, null, if (audio != null) SyncNames.audio("m4a") else null,
            listOf(SegmentJson("A", 0, "halo")),
            if (withNotes) listOf(SectionJson("Ringkasan", "PARAGRAPH", "\"x\"")) else emptyList(),
            listOf(MarkJson(3, "m", 1L)), photos,
        )
    }

    fun rename(id: String, title: String) { recs[id] = recs.getValue(id).copy(title = title) }

    override suspend fun recordingIds() = recs.keys.toList()
    override suspend fun snapshot(id: String): LocalSnapshot? {
        val s = recs[id] ?: return null
        if (id in processing) return null
        val photos = s.photos.associate { it.name to File(dir, it.name) }
        return LocalSnapshot(s, audioFile(id).takeIf { s.audioName != null && it.exists() }, photos)
    }
    override suspend fun documents(id: String): List<DocFile> {
        documentCalls++
        val s = recs[id] ?: return emptyList()
        val docs = mutableListOf<DocFile>()
        if (s.sections.isNotEmpty()) docs += DocFile(SyncNames.NOTULENSI_DOC, SyncNames.DOCX_MIME, File(dir, "n_$id.docx").also { it.writeText("DOCX ${s.title}") })
        if (s.transcript.isNotEmpty()) docs += DocFile(SyncNames.TRANSCRIPT_DOC, "text/plain", File(dir, "t_$id.txt").also { it.writeText("TXT ${s.title}") })
        return docs
    }
    override suspend fun audioTarget(id: String, name: String) = File(dir, "aud_$id.m4a")
    override suspend fun photoTarget(name: String) = File(dir, name)
    override suspend fun apply(snapshot: RecordingSnapshot, audioFile: File?, photoFiles: Map<String, File>) { recs[snapshot.id] = snapshot }
    override suspend fun delete(id: String) { recs.remove(id); audioFile(id).delete() }
    override suspend fun categories() = cats.toList()
    override suspend fun addCategories(labels: List<String>) { cats += labels }

    private fun millis(y: Int, m: Int, d: Int, h: Int, min: Int) =
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, min) }.timeInMillis
}

class SyncEngineTest {
    private lateinit var remote: FakeRemote
    private lateinit var a: FakeLocal
    private lateinit var b: FakeLocal
    private lateinit var storeA: FakeStorage
    private lateinit var storeB: FakeStorage

    @Before
    fun setUp() {
        remote = FakeRemote()
        a = FakeLocal(Files.createTempDirectory("syncA").toFile())
        b = FakeLocal(Files.createTempDirectory("syncB").toFile())
        storeA = FakeStorage(); storeB = FakeStorage()
    }

    private fun syncA() = runBlocking { SyncEngine(a, remote, storeA).run() }
    private fun syncB() = runBlocking { SyncEngine(b, remote, storeB).run() }
    private val folder = "2026/10 - Oktober/2026-10-21 1030 - Rapat 1"

    private fun photoName(content: String) =
        SyncNames.photo(MessageDigest.getInstance("SHA-1").digest(content.toByteArray()).joinToString("") { "%02x".format(it) }.take(12))

    @Test
    fun everyRecordingGetsItsOwnYearMonthDateFolderWithAllFiles() {
        a.create("r1", "Rapat 1", audio = "AUDIODATA", photoBytes = "PHOTO", withNotes = true)
        val ra = syncA()
        assertEquals(1, ra.uploaded)
        assertEquals(
            setOf(
                "$folder/rec_r1.json", "$folder/Rekaman.m4a", "$folder/${photoName("PHOTO")}",
                "$folder/Notulensi.docx", "$folder/Transkrip.txt",
            ),
            remote.paths() - SyncNames.CATEGORIES,
        )
    }

    @Test
    fun notulensiAndTranscriptDocumentsFollowTheContent() {
        a.create("r1", "Rapat 1", withNotes = true)
        syncA()
        assertEquals("DOCX Rapat 1", remote.file("$folder/Notulensi.docx")!!.bytes.decodeToString())
        assertEquals("TXT Rapat 1", remote.file("$folder/Transkrip.txt")!!.bytes.decodeToString())
        // a recording that was never generated has a transcript but no notulensi
        a.create("r2", "Belum", createdAt = 1L)
        syncA()
        assertTrue(remote.paths().any { it.endsWith("/Transkrip.txt") && it.contains("Belum") })
        assertFalse(remote.paths().any { it.endsWith("/Notulensi.docx") && it.contains("Belum") })
    }

    @Test
    fun secondDeviceGetsTheWholeRecording() {
        a.create("r1", "Rapat 1", audio = "AUDIODATA", photoBytes = "PHOTO", withNotes = true)
        syncA()
        val rb = syncB()
        assertEquals(1, rb.downloaded)
        assertEquals("Rapat 1", b.recs.getValue("r1").title)
        assertEquals("AUDIODATA", runBlocking { b.audioTarget("r1", "x") }.readText())
        assertEquals(1, b.recs.getValue("r1").photos.size)
        // nothing changed -> nothing moves, on either side, and the documents aren't regenerated
        val calls = a.documentCalls
        val again = syncB()
        val againA = syncA()
        assertEquals(0, again.uploaded + again.downloaded + againA.uploaded + againA.downloaded)
        assertEquals(calls, a.documentCalls)
    }

    @Test
    fun editReachesTheOtherDeviceAndRenamesTheFolder() {
        a.create("r1", "Lama", withNotes = true)
        syncA(); syncB()
        a.rename("r1", "Baru")
        assertEquals(1, syncA().uploaded)
        assertEquals(1, syncB().downloaded)
        assertEquals("Baru", b.recs.getValue("r1").title)
        assertTrue(remote.paths().contains("2026/10 - Oktober/2026-10-21 1030 - Baru/rec_r1.json"))
        assertFalse(remote.paths().any { it.contains("- Lama") })
        assertEquals("DOCX Baru", remote.file("2026/10 - Oktober/2026-10-21 1030 - Baru/Notulensi.docx")!!.bytes.decodeToString())
    }

    @Test
    fun conflictMeansTheSyncingDeviceWins() {
        a.create("r1", "Awal")
        syncA(); syncB()
        a.rename("r1", "Dari A")
        b.rename("r1", "Dari B")
        syncA(); syncB(); syncA()
        assertEquals("Dari B", a.recs.getValue("r1").title)
        assertEquals("Dari B", b.recs.getValue("r1").title)
        assertEquals(1, remote.paths().count { it.endsWith("rec_r1.json") })
    }

    @Test
    fun deletionRemovesTheWholeFolderEverywhereAndDoesNotComeBack() {
        a.create("r1", "Hapus aku", audio = "X", photoBytes = "P", withNotes = true)
        syncA(); syncB()
        assertNotNull(b.recs["r1"])

        runBlocking { a.delete("r1") }
        storeA.state = storeA.state.copy(pendingDeletes = setOf("r1"))
        val ra = syncA()
        assertEquals(1, ra.deletedRemote)
        assertEquals(setOf(SyncNames.DELETED, SyncNames.CATEGORIES).filter { remote.paths().contains(it) }.toSet(), remote.paths())
        assertTrue(storeA.state.pendingDeletes.isEmpty())

        val rb = syncB()
        assertEquals(1, rb.deletedLocal)
        assertNull(b.recs["r1"])
        syncA(); syncB()
        assertNull(a.recs["r1"]); assertNull(b.recs["r1"])
        assertFalse(remote.paths().any { it.endsWith("rec_r1.json") })
    }

    @Test
    fun recordingStillProcessingIsSkipped() {
        a.create("r1", "Sedang diproses")
        a.processing += "r1"
        assertEquals(0, syncA().uploaded)
        assertFalse(remote.paths().any { it.endsWith("rec_r1.json") })
        a.processing.clear()
        assertEquals(1, syncA().uploaded)
    }

    @Test
    fun categoriesAreMergedNotOverwritten() {
        a.cats += listOf("Musdes", "BPD")
        b.cats += listOf("Warga", "bpd")
        syncA(); syncB(); syncA()
        assertEquals(setOf("musdes", "bpd", "warga"), a.cats.map { it.lowercase() }.toSet())
        assertEquals(setOf("musdes", "bpd", "warga"), b.cats.map { it.lowercase() }.toSet())
    }

    @Test
    fun oneFailingRecordingDoesNotStopTheRest() {
        a.create("r1", "Satu"); a.create("r2", "Dua")
        val bad = object : SyncRemote by remote {
            override suspend fun uploadBytes(parentId: String, name: String, bytes: ByteArray, mimeType: String, existingId: String?): RemoteFile {
                if (name == "rec_r1.json") throw java.io.IOException("jaringan putus")
                return remote.uploadBytes(parentId, name, bytes, mimeType, existingId)
            }
        }
        val r = runBlocking { SyncEngine(a, bad, storeA).run() }
        assertEquals(1, r.uploaded)
        assertEquals(1, r.failed)
        assertTrue(r.firstError!!.contains("jaringan putus"))
        assertEquals(1, syncA().uploaded)
    }

    /** What the previous app version left on Drive: everything loose in the root, ids in the names. */
    private fun seedLegacyLayout(id: String, title: String, audio: String, photo: String) {
        val s = RecordingSnapshot(
            id, title, "Musdes", 1_790_000_000_000L, 60, "audio/mp4", "DONE", 2, "CLOUD", "BUILTIN", "Std", "ringkas", "GEMINI", 5L, "SEDANG",
            null, null, null, "aud_$id.m4a", listOf(SegmentJson("A", 0, "halo")), listOf(SectionJson("Ringkasan", "PARAGRAPH", "\"x\"")),
            emptyList(), listOf(PhotoJson("pho_${id}__abcdef123456.jpg", 3, "cap", 2L)),
        )
        runBlocking {
            val root = remote.rootId()
            remote.uploadBytes(root, "aud_$id.m4a", audio.toByteArray(), "audio/mp4", null)
            remote.uploadBytes(root, "pho_${id}__abcdef123456.jpg", photo.toByteArray(), "image/jpeg", null)
            remote.uploadBytes(root, "rec_$id.json", Gson().toJson(s).toByteArray(), "application/json", null)
        }
    }

    @Test
    fun flatLayoutFromTheOldVersionIsMovedIntoFoldersWithoutReuploading() {
        seedLegacyLayout("old1", "Rapat Lama", "AUDIO", "FOTO")
        a.create("old1", "Rapat Lama", audio = "AUDIO", withNotes = true) // this device already has it
        storeA.state = SyncState(mapOf("old1" to RecState("stale", "stale")))

        val r = syncA()
        assertEquals(1, r.migrated)
        assertEquals(0, r.failed)
        val paths = remote.paths()
        assertTrue(paths.any { it.endsWith(" - Rapat Lama/rec_old1.json") })
        assertTrue(paths.any { it.endsWith(" - Rapat Lama/Rekaman.m4a") })
        assertTrue(paths.any { it.endsWith(" - Rapat Lama/Foto-abcdef123456.jpg") })
        // nothing is left loose in the root except the shared files
        assertFalse(paths.any { it.startsWith("aud_") || it.startsWith("pho_") || it.startsWith("rec_") })
        assertEquals(1, paths.count { it.endsWith("Rekaman.m4a") }) // moved, not duplicated
        assertTrue(paths.any { it.endsWith("/Notulensi.docx") })
    }

    @Test
    fun legacyRecordingOnlyOnDriveStillDownloadsOnANewDevice() {
        seedLegacyLayout("old2", "Hanya di Drive", "AUDIO2", "FOTO2")
        val rb = syncB()
        assertEquals(1, rb.migrated)
        assertEquals(1, rb.downloaded)
        val got = b.recs.getValue("old2")
        assertEquals("Rekaman.m4a", got.audioName)
        assertEquals("Foto-abcdef123456.jpg", got.photos.single().name)
        assertEquals("AUDIO2", runBlocking { b.audioTarget("old2", "x") }.readText())
    }
}
