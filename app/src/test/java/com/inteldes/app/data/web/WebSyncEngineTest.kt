package com.inteldes.app.data.web

import com.google.gson.JsonPrimitive
import com.inteldes.app.data.server.ServerException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

// ───────────────────────── fakes ─────────────────────────

/** An in-memory IntelDes server with the real one's versioning rules (If-Match, 409, tombstones, union of marks). */
private class FakeServer : WebRemote {
    class Rec(
        var meta: WebRecording,
        var segments: List<WebSegment> = emptyList(),
        var sections: List<WebSection> = emptyList(),
        val marks: MutableMap<String, WebMark> = linkedMapOf(),
        val chat: MutableMap<String, WebChat> = linkedMapOf(),
        val photos: MutableList<WebPhoto> = mutableListOf(),
    )

    val recs = linkedMapOf<String, Rec>()
    val blobs = linkedMapOf<String, ByteArray>()
    val categories = mutableListOf<WebCategory>()
    private val log = mutableListOf<Pair<Long, WebChange>>()
    private var seq = 0L
    val calls = mutableListOf<String>()
    var uploadsFail = false

    private fun touch(id: String, op: String = "upsert") {
        recs[id]?.let { it.meta = it.meta.copy(updatedAt = seq + 1) }     // like the real server: ANY change moves updatedAt
        log += ++seq to WebChange("recording", id, op, recs[id]?.meta?.version)
    }

    override suspend fun changes(since: Long): WebChangesPage {
        calls += "changes"
        val latest = log.filter { it.first > since }.groupBy { it.second.id }.map { (_, v) -> v.last().second }
        return WebChangesPage(latest, seq, false)
    }

    override suspend fun getAggregate(id: String): WebAggregate? {
        calls += "get"
        val r = recs[id] ?: return null
        return WebAggregate(r.meta, WebTranscriptPart(r.meta.transcriptVersion, r.segments), WebNotesPart(r.meta.notesVersion, r.sections), r.marks.values.toList(), r.photos.toList(), r.chat.values.toList())
    }

    private fun conflict() = ServerException(409, "version_conflict", "Versi basi")

    override suspend fun putRecording(id: String, input: WebRecordingInput, ifMatch: Int): WebRecording {
        calls += "putRecording"
        require(input.status == "RAW" || input.status == "DONE") { "client must never send ${input.status}" }
        val r = recs[id]
        if (r != null && r.meta.deletedAt != null) throw conflict()
        if (r == null) {
            if (ifMatch != 0) throw conflict()
            recs[id] = Rec(WebRecording(id, input.title, input.kind, input.createdAt, input.durationSec, input.audioBlobSha256, input.audioMimeType, input.status, input.speakerCount, input.engine, input.templateId, input.templateName, input.summary, input.provider, input.notesGeneratedAt, input.notulensiDensity, input.locationName, input.latitude, input.longitude, input.errorMessage, 1, 0, 0, 1, null))
        } else {
            if (ifMatch != r.meta.version) throw conflict()
            if (input.audioBlobSha256 != null) check(input.audioBlobSha256 in blobs) { "blob_missing" }
            r.meta = r.meta.copy(title = input.title, kind = input.kind, durationSec = input.durationSec, audioBlobSha256 = input.audioBlobSha256, status = input.status, summary = input.summary, locationName = input.locationName, speakerCount = input.speakerCount, version = r.meta.version + 1)
        }
        touch(id)
        return recs.getValue(id).meta
    }

    override suspend fun putTranscript(id: String, segments: List<WebSegment>, ifMatch: Int): Int {
        calls += "putTranscript"
        val r = recs[id] ?: throw ServerException(404, "not_found", "x")
        if (ifMatch != r.meta.transcriptVersion) throw conflict()
        r.segments = segments
        r.meta = r.meta.copy(transcriptVersion = r.meta.transcriptVersion + 1)
        touch(id)
        return r.meta.transcriptVersion
    }

    override suspend fun putNotes(id: String, sections: List<WebSection>, ifMatch: Int): Int {
        calls += "putNotes"
        val r = recs[id] ?: throw ServerException(404, "not_found", "x")
        if (ifMatch != r.meta.notesVersion) throw conflict()
        r.sections = sections
        r.meta = r.meta.copy(notesVersion = r.meta.notesVersion + 1)
        touch(id)
        return r.meta.notesVersion
    }

    override suspend fun putMarks(id: String, marks: List<WebMark>) {
        calls += "putMarks"
        val r = recs[id] ?: throw ServerException(404, "not_found", "x")
        marks.forEach { if (it.deletedAt != null) r.marks.remove(it.id) else r.marks[it.id] = it }
        touch(id)
    }

    override suspend fun postChat(id: String, messages: List<WebChat>) {
        calls += "postChat"
        val r = recs[id] ?: throw ServerException(404, "not_found", "x")
        messages.forEach { r.chat.putIfAbsent(it.id, it) }
        touch(id)
    }

    override suspend fun putPhotos(id: String, photos: List<WebPhoto>) {
        calls += "putPhotos"
        val r = recs[id] ?: throw ServerException(404, "not_found", "x")
        photos.forEach { p ->
            if (p.deletedAt == null && p.blobSha256 !in blobs) throw ServerException(409, "blob_missing", "x")
            r.photos.removeAll { it.id == p.id }
            r.photos += p
        }
        touch(id)
    }

    override suspend fun deleteRecording(id: String) {
        calls += "delete"
        val r = recs[id] ?: throw ServerException(404, "not_found", "x")
        r.meta = r.meta.copy(deletedAt = 1L)
        touch(id, "delete")
    }

    override suspend fun listCategories() = categories.toList()
    override suspend fun putCategory(id: String, label: String, orderIndex: Int, ifMatch: Int): WebCategory {
        calls += "putCategory"
        if (categories.any { it.label.equals(label, ignoreCase = true) && it.id != id }) throw ServerException(409, "duplicate_label", "x")
        return WebCategory(id, label, orderIndex, 1, 1, null).also { categories += it }
    }

    override suspend fun blobExists(sha256: String): Boolean { calls += "blobExists"; return sha256 in blobs }
    override suspend fun uploadBlob(sha256: String, mimeType: String, file: File) {
        calls += "upload"
        if (uploadsFail) throw IOException("jaringan putus")
        blobs[sha256] = file.readBytes()
    }
    override suspend fun downloadBlob(sha256: String, dest: File) { calls += "download"; dest.parentFile?.mkdirs(); dest.writeBytes(blobs.getValue(sha256)) }

    /** What a web user doing something in the browser looks like to the phone. */
    fun webEditTitle(id: String, title: String) { val r = recs.getValue(id); r.meta = r.meta.copy(title = title, version = r.meta.version + 1); touch(id) }
    fun webReplaceNotes(id: String, text: String) {
        val r = recs.getValue(id)
        r.sections = listOf(WebSection(WebIds.forSection(id, 0), 0, "Ringkasan", "PARAGRAPH", JsonPrimitive(text)))
        r.meta = r.meta.copy(notesVersion = r.meta.notesVersion + 1, status = "DONE", summary = text)
        touch(id)
    }
    fun webCreate(id: String, title: String, withAudio: ByteArray? = null) {
        val sha = withAudio?.let { WebSyncEngine.sha256(String(it)) }
        if (withAudio != null) blobs[sha!!] = withAudio
        recs[id] = Rec(WebRecording(id, title, "Musdes", 1_000L, 60, sha, "audio/mp4", "RAW", 0, "CLOUD", "builtin_standard", "Notulensi Standar", null, null, null, null, null, null, null, null, 1, 0, 0, 1, null))
        touch(id)
    }
    /** The web adds a mark / the AI answers: no version number moves, only updatedAt (exactly what the real server does). */
    fun webAddMark(id: String, sec: Int, label: String) {
        val r = recs.getValue(id)
        val m = WebMark(java.util.UUID.randomUUID().toString(), sec, label, 7)
        r.marks[m.id] = m
        touch(id)
    }
    fun webAddChat(id: String, text: String) {
        val r = recs.getValue(id)
        val c = WebChat(java.util.UUID.randomUUID().toString(), r.chat.size, false, text, 7)
        r.chat[c.id] = c
        touch(id)
    }
    /** The server's retention setting deleting the audio of a finished meeting: the sha becomes null, the file is gone. */
    fun webPurgeAudio(id: String) {
        val r = recs.getValue(id)
        r.meta.audioBlobSha256?.let { blobs.remove(it) }
        r.meta = r.meta.copy(audioBlobSha256 = null, version = r.meta.version + 1)
        touch(id)
    }
    fun count(name: String) = calls.count { it == name }
}

private class FakeLocal(private val dir: File) : WebLocal {
    class Rec(var input: WebRecordingInput, var transcript: List<WebSegment> = emptyList(), var sections: List<WebSection> = emptyList(),
              var marks: List<WebMark> = emptyList(), var chat: List<WebChat> = emptyList(), var audio: File? = null, var working: Boolean = false,
              var photos: List<LocalPhoto> = emptyList())

    val recs = linkedMapOf<String, Rec>()
    val cats = mutableListOf<LocalCategory>()
    var childrenApplied = 0

    fun create(id: String, title: String, status: String = "RAW", audio: String? = null, transcript: Boolean = false, notes: Boolean = false, createdAt: Long = 1_000L) {
        val file = audio?.let { File(dir, "aud_$id.m4a").also { f -> f.writeText(it) } }
        recs[id] = Rec(
            WebRecordingInput(title, "Musdes", createdAt, 60, null, "audio/mp4", status, 1, "CLOUD", "builtin_standard", "Notulensi Standar", null, null, null, null, null, null, null, null),
            transcript = if (transcript) listOf(WebSegment(WebIds.forSegment(id, 0), 0, "A", 0, "halo")) else emptyList(),
            sections = if (notes) listOf(WebSection(WebIds.forSection(id, 0), 0, "Ringkasan", "PARAGRAPH", JsonPrimitive("isi"))) else emptyList(),
            audio = file,
        )
    }
    fun title(id: String, t: String) { recs.getValue(id).input = recs.getValue(id).input.copy(title = t) }
    fun mark(id: String, sec: Int, label: String) { val r = recs.getValue(id); r.marks = r.marks + WebMark(WebIds.forMark(id, sec, label, 5), sec, label, 5) }

    override suspend fun recordingIds() = recs.keys.toList()
    override suspend fun exists(id: String) = id in recs
    override suspend fun aggregate(id: String): LocalAggregate? {
        val r = recs[id] ?: return null
        if (r.working) return null
        return LocalAggregate(id, r.input, r.transcript, r.sections, r.marks, r.chat, r.audio, r.photos)
    }
    override suspend fun hasAudio(id: String) = recs[id]?.audio?.exists() == true
    override suspend fun hasPhoto(recordingId: String, photoId: String) = false
    override suspend fun audioTarget(id: String, sha256: String, mimeType: String) = File(dir, "aud_$id.m4a")
    override suspend fun photoTarget(sha256: String) = File(dir, "photo_$sha256.jpg")
    override suspend fun attachAudio(id: String, file: File) { recs[id]?.audio = file }
    override suspend fun applyServer(server: WebAggregate, audio: File?, photoFiles: Map<String, File>) {
        val s = server.recording
        val input = WebRecordingInput(s.title, s.kind, s.createdAt, s.durationSec, null, s.audioMimeType, s.status, s.speakerCount, s.engine, s.templateId, s.templateName, s.summary, s.provider, s.notesGeneratedAt, s.notulensiDensity, s.locationName, s.latitude, s.longitude, s.errorMessage)
        val r = recs.getOrPut(s.id) { Rec(input) }
        r.input = input
        r.transcript = server.transcript.segments
        r.sections = server.notes.sections
        val byId = (r.marks + server.marks.filter { it.deletedAt == null }).associateBy { it.id }.toMutableMap()
        server.marks.filter { it.deletedAt != null }.forEach { byId.remove(it.id) }
        r.marks = byId.values.toList()
        r.chat = (r.chat + server.chat).distinctBy { it.id }
        if (audio != null) r.audio = audio
    }
    override suspend fun applyChildren(server: WebAggregate, photoFiles: Map<String, File>): Boolean {
        val r = recs[server.recording.id] ?: return false
        if (r.working) return false
        childrenApplied++
        val byId = (r.marks + server.marks.filter { it.deletedAt == null }).associateBy { it.id }.toMutableMap()
        server.marks.filter { it.deletedAt != null }.forEach { byId.remove(it.id) }
        r.marks = byId.values.toList()
        r.chat = (r.chat + server.chat).distinctBy { it.id }
        return true
    }
    override suspend fun categories() = cats.toList()
    override suspend fun addCategory(id: String, label: String, orderIndex: Int) { cats += LocalCategory(id, label, orderIndex) }
}

private class FakeStore : WebStateStore {
    val states = linkedMapOf<String, WebState>()
    var cursorValue = 0L
    val deletes = linkedSetOf<String>()
    val retries = linkedSetOf<String>()
    override suspend fun get(id: String) = states[id]
    override suspend fun put(state: WebState) { states[state.recordingId] = state }
    override suspend fun remove(id: String) { states.remove(id) }
    override suspend fun all() = states.values.toList()
    override fun cursor() = cursorValue
    override fun setCursor(value: Long) { cursorValue = value }
    override fun pendingDeletes(): Set<String> = deletes.toSet()
    override fun removePendingDelete(id: String) { deletes.remove(id) }
    override fun retryPulls(): Set<String> = retries.toSet()
    override fun addRetry(id: String) { retries += id }
    override fun removeRetry(id: String) { retries.remove(id) }
}

// ───────────────────────── tests ─────────────────────────

class WebSyncEngineTest {
    private lateinit var server: FakeServer
    private lateinit var phone: FakeLocal
    private lateinit var store: FakeStore
    private lateinit var dir: File

    @Before
    fun setUp() {
        server = FakeServer()
        dir = Files.createTempDirectory("websync").toFile()
        phone = FakeLocal(dir)
        store = FakeStore()
    }

    private fun sync() = runBlocking { WebSyncEngine(phone, server, store).run() }

    @Test
    fun aNewLocalRecordingIsCreatedOnTheServerWithItsAudioAndParts() {
        phone.create("r1", "Rapat 1", status = "DONE", audio = "AUDIODATA", transcript = true, notes = true)
        phone.mark("r1", 12, "penting")
        val report = sync()
        assertEquals(0, report.failed)
        val rec = server.recs.getValue("r1")
        assertEquals("Rapat 1", rec.meta.title)
        assertEquals(1, rec.segments.size)
        assertEquals(1, rec.sections.size)
        assertEquals(1, rec.marks.size)
        assertEquals("AUDIODATA", String(server.blobs.getValue(rec.meta.audioBlobSha256!!)))
        assertEquals(1, report.pushed)
    }

    @Test
    fun audioTheServerDeletedByRetentionStaysOnThePhoneAndIsNeverSentBack() {
        phone.create("r1", "Rapat 1", status = "DONE", audio = "AUDIODATA", transcript = true, notes = true)
        sync()
        val sha = server.recs.getValue("r1").meta.audioBlobSha256!!
        server.webPurgeAudio("r1")
        sync()
        assertEquals("AUDIODATA", phone.recs.getValue("r1").audio!!.readText())          // the phone keeps its own copy
        assertEquals(WebSyncEngine.AUDIO_PURGED, store.states.getValue("r1").audioSha)
        phone.mark("r1", 30, "penting")                                                  // any later edit pushes the recording again
        phone.title("r1", "Rapat 1 (ubah)")
        server.calls.clear()
        val report = sync()
        assertEquals(0, report.failed)
        assertEquals(0, server.count("upload"))
        assertFalse(sha in server.blobs)
        assertNull(server.recs.getValue("r1").meta.audioBlobSha256)
        assertEquals("Rapat 1 (ubah)", server.recs.getValue("r1").meta.title)           // the edit itself still goes up
        assertEquals(1, server.recs.getValue("r1").marks.size)
    }

    @Test
    fun pullOnlyReceivesTheArchiveAndSendsNothingOfThePhone() {
        phone.create("mine", "Rekaman HP", status = "DONE", audio = "HPAUDIO", transcript = true, notes = true)
        phone.mark("mine", 5, "penting")
        phone.cats += LocalCategory("c-hp", "Hanya di HP", 0)
        server.webCreate("theirs", "Rapat arsip pribadi", withAudio = "SRVAUDIO".toByteArray())
        server.categories += WebCategory("c-srv", "Dari server", 0, 1, 1, null)
        server.calls.clear()
        val report = runBlocking { WebSyncEngine(phone, server, store, pullOnly = true).run() }
        assertEquals(0, report.failed)
        assertEquals(0, report.pushed)
        assertTrue("theirs" in phone.recs)                                              // received, with its audio
        assertEquals("SRVAUDIO", phone.recs.getValue("theirs").audio!!.readText())
        assertFalse("mine" in server.recs)                                              // the phone's own recording never went up
        assertEquals(0, server.count("upload") + server.count("putRecording") + server.count("putMarks") + server.count("putCategory"))
        assertTrue(phone.cats.any { it.label == "Dari server" })                        // categories still come down
        assertEquals("Rekaman HP", phone.recs.getValue("mine").input.title)             // and the phone's recording is untouched
    }

    @Test
    fun pullOnlyDoesNotSendADeleteMadeOnThePhone() {
        server.webCreate("theirs", "Rapat arsip pribadi")
        runBlocking { WebSyncEngine(phone, server, store, pullOnly = true).run() }
        store.deletes += "theirs"                                                        // the user deleted it on the phone
        server.calls.clear()
        runBlocking { WebSyncEngine(phone, server, store, pullOnly = true).run() }
        assertEquals(0, server.count("delete"))
        assertFalse(server.recs.getValue("theirs").meta.deletedAt != null)
    }

    @Test
    fun aSecondRunWithNothingChangedSendsNothing() {
        phone.create("r1", "Rapat 1", audio = "A", transcript = true)
        sync()
        server.calls.clear()
        val again = sync()
        assertEquals(0, again.pushed + again.pulled)
        assertFalse(server.calls.any { it.startsWith("put") || it == "upload" || it == "postChat" })
    }

    @Test
    fun aLocalEditSendsOnlyTheChangedPartWithTheKnownVersion() {
        phone.create("r1", "Lama", status = "DONE", transcript = true, notes = true)
        sync()
        server.calls.clear()
        phone.title("r1", "Baru")
        sync()
        assertEquals("Baru", server.recs.getValue("r1").meta.title)
        assertEquals(1, server.count("putRecording"))
        assertEquals(0, server.count("putTranscript"))
        assertEquals(0, server.count("putNotes"))
    }

    @Test
    fun aWebEditIsPulledWhenThePhoneHasNotChangedAnything() {
        phone.create("r1", "Lama")
        sync()
        server.webEditTitle("r1", "Diubah di web")
        val report = sync()
        assertEquals("Diubah di web", phone.recs.getValue("r1").input.title)
        assertEquals(1, report.pulled)
        // and the pull is not pushed straight back
        server.calls.clear()
        sync()
        assertEquals(0, server.count("putRecording"))
    }

    @Test
    fun whenBothChangedThePhoneWinsAndTheServerAdoptsIt() {
        phone.create("r1", "Awal", status = "DONE", transcript = true, notes = true)
        sync()
        phone.title("r1", "Dari HP")
        server.webReplaceNotes("r1", "Catatan baru dari web")
        sync()
        assertEquals("Dari HP", server.recs.getValue("r1").meta.title)
        assertEquals("Dari HP", phone.recs.getValue("r1").input.title)
        // the part only the web edited (the notes) is kept, not overwritten by the phone's stale copy
        assertEquals("Catatan baru dari web", (server.recs.getValue("r1").sections.single().payload as JsonPrimitive).asString)
        assertEquals("Catatan baru dari web", (phone.recs.getValue("r1").sections.single().payload as JsonPrimitive).asString)
    }

    @Test
    fun firstContactTheRicherSideWins() {
        // server has the notulensi, the phone only a bare recording with the same id
        server.webCreate("r1", "Di server")
        server.webReplaceNotes("r1", "Sudah ada notulensi")
        phone.create("r1", "Di HP")
        sync()
        assertEquals("Di server", phone.recs.getValue("r1").input.title)
        assertEquals(1, phone.recs.getValue("r1").sections.size)

        // the other way round: the phone is richer, so it is pushed
        phone.create("r2", "HP kaya", status = "DONE", transcript = true, notes = true)
        server.webCreate("r2", "Server kosong")
        sync()
        assertEquals("HP kaya", server.recs.getValue("r2").meta.title)
        assertEquals(1, server.recs.getValue("r2").sections.size)
    }

    @Test
    fun aRecordingDeletedOnTheWebIsNeverDeletedOnThePhone() {
        phone.create("r1", "Penting", status = "DONE", audio = "A", transcript = true, notes = true)
        sync()
        runBlocking { server.deleteRecording("r1") }
        val report = sync()
        assertNotNull(phone.recs["r1"])
        assertNotNull(phone.recs.getValue("r1").audio)
        assertTrue(report.ignoredServerDeletes >= 1)
    }

    @Test
    fun aRecordingDeletedOnThePhoneIsDeletedOnTheServer() {
        phone.create("r1", "Hapus")
        sync()
        phone.recs.remove("r1")
        store.deletes += "r1"
        val report = sync()
        assertEquals(1, report.deletedOnServer)
        assertNotNull(server.recs.getValue("r1").meta.deletedAt)
        assertTrue(store.deletes.isEmpty())
        assertNull(store.states["r1"])
    }

    @Test
    fun anEmptyTranscriptOrNotulensiNeverOverwritesAFilledOne() {
        phone.create("r1", "Rapat", status = "DONE", transcript = true, notes = true)
        sync()
        // the phone later loses its transcript locally (e.g. a failed re-process); the server copy must survive
        phone.recs.getValue("r1").transcript = emptyList()
        phone.recs.getValue("r1").sections = emptyList()
        phone.title("r1", "Ganti judul")
        sync()
        assertEquals(1, server.recs.getValue("r1").segments.size)
        assertEquals(1, server.recs.getValue("r1").sections.size)
        assertEquals("Ganti judul", server.recs.getValue("r1").meta.title)
    }

    @Test
    fun aRecordingBeingProcessedIsNotPushedNorOverwritten() {
        phone.create("r1", "Sedang diproses")
        phone.recs.getValue("r1").working = true
        sync()
        assertNull(server.recs["r1"])
        // a web change to the same recording is deferred, not applied over the running pipeline
        server.webCreate("r2", "Dari web")
        phone.create("r2", "Lokal", status = "RAW")
        phone.recs.getValue("r2").working = true
        sync()
        assertEquals("Lokal", phone.recs.getValue("r2").input.title)
    }

    @Test
    fun marksAreMergedAndAMarkRemovedOnThePhoneIsRemovedOnTheServer() {
        phone.create("r1", "Rapat")
        phone.mark("r1", 10, "a")
        phone.mark("r1", 20, "b")
        sync()
        assertEquals(2, server.recs.getValue("r1").marks.size)
        // phone removes one
        phone.recs.getValue("r1").marks = phone.recs.getValue("r1").marks.filter { it.label != "a" }
        sync()
        assertEquals(listOf("b"), server.recs.getValue("r1").marks.values.map { it.label })
    }

    @Test
    fun audioAlreadyOnTheServerIsNotUploadedAgain() {
        phone.create("r1", "Rapat 1", audio = "SAMEAUDIO")
        phone.create("r2", "Rapat 2", audio = "SAMEAUDIO")
        sync()
        assertEquals(1, server.count("upload"))
    }

    @Test
    fun aFailedAudioUploadIsRetriedNextTimeAndDoesNotMarkTheRecordingSynced() {
        phone.create("r1", "Rapat", audio = "AUDIO")
        server.uploadsFail = true
        val first = sync()
        assertEquals(1, first.failed)
        assertNull(server.recs["r1"])
        assertNull(store.states["r1"])
        server.uploadsFail = false
        val second = sync()
        assertEquals(0, second.failed)
        assertNotNull(server.recs["r1"]?.meta?.audioBlobSha256)
    }

    @Test
    fun aRecordingCreatedOnTheWebComesDownWithItsAudio() {
        server.webCreate("w1", "Dari web", withAudio = "WEBAUDIO".toByteArray())
        val report = sync()
        assertEquals(1, report.pulled)
        val got = phone.recs.getValue("w1")
        assertEquals("Dari web", got.input.title)
        assertEquals("WEBAUDIO", got.audio!!.readText())
    }

    @Test
    fun categoriesAreMergedByLabelAndADuplicateIsNotAnError() {
        phone.cats += LocalCategory("c1", "Musdes", 0)
        phone.cats += LocalCategory("c2", "BPD", 1)
        server.categories += WebCategory("s1", "bpd", 0, 1, 1, null)
        server.categories += WebCategory("s2", "Warga", 1, 1, 1, null)
        val report = sync()
        assertEquals(0, report.failed)
        assertEquals(setOf("musdes", "bpd", "warga"), phone.cats.map { it.label.lowercase() }.toSet())
        assertEquals(setOf("musdes", "bpd", "warga"), server.categories.map { it.label.lowercase() }.toSet())
    }

    @Test
    fun oneFailingRecordingDoesNotStopTheOthers() {
        phone.create("bad", "Gagal", audio = "X")
        phone.create("ok", "Berhasil")
        server.uploadsFail = true
        val report = sync()
        assertEquals(1, report.failed)
        assertNotNull(server.recs["ok"])
    }

    @Test
    fun photosAreUploadedOnceMergedAndARemovedPhotoBecomesATombstone() {
        phone.create("r1", "Rapat")
        val img = File(dir, "p1.jpg").apply { writeText("JPEGDATA") }
        val img2 = File(dir, "p2.jpg").apply { writeText("JPEGDATA") }       // same bytes: one blob
        phone.recs.getValue("r1").photos = listOf(LocalPhoto("ph1", img, 30, "Papan tulis", 5L), LocalPhoto("ph2", img2, null, "", 6L))
        sync()
        assertEquals(2, server.recs.getValue("r1").photos.size)
        assertEquals(1, server.count("upload"))
        phone.recs.getValue("r1").photos = phone.recs.getValue("r1").photos.filter { it.id == "ph1" }
        sync()
        assertEquals(listOf("ph1"), server.recs.getValue("r1").photos.filter { it.deletedAt == null }.map { it.id })
        assertTrue(server.recs.getValue("r1").photos.any { it.id == "ph2" && it.deletedAt != null })
    }

    @Test
    fun marksOnlyThePhoneHasStillGoUpAfterAFirstContactAdoptsTheServerCopy() {
        server.webCreate("r1", "Di server")
        server.webReplaceNotes("r1", "Sudah ada notulensi")
        phone.create("r1", "Di HP")
        phone.mark("r1", 42, "hanya di hp")
        sync()
        assertTrue(server.recs.getValue("r1").marks.values.any { it.label == "hanya di hp" })
        assertEquals(1, phone.recs.getValue("r1").marks.count { it.label == "hanya di hp" })
    }

    // ───────────── marks / photos / chat changed on the web move no version, only updatedAt ─────────────

    @Test
    fun marksAndChatAddedOnTheWebReachAPhoneThatAlreadySyncedTheRecording() {
        phone.create("r1", "Rapat 1", status = "DONE", audio = "A", transcript = true, notes = true)
        sync()
        val transcriptBefore = phone.recs.getValue("r1").transcript
        server.webAddMark("r1", 30, "dari web")
        server.webAddChat("r1", "jawaban AI")
        val report = sync()
        assertEquals(0, report.failed)
        assertTrue(phone.recs.getValue("r1").marks.any { it.label == "dari web" })
        assertTrue(phone.recs.getValue("r1").chat.any { it.text == "jawaban AI" })
        assertEquals(transcriptBefore, phone.recs.getValue("r1").transcript)      // only the children are merged
        assertEquals(0, report.pushed)                                             // and nothing is pushed back
    }

    @Test
    fun aChildrenRefreshHappensOnceNotOnEveryRun() {
        phone.create("r1", "Rapat 1", audio = "A", transcript = true)
        sync()
        server.webAddMark("r1", 1, "x")
        sync()
        val applied = phone.childrenApplied
        assertTrue(applied >= 1)
        sync()
        sync()
        assertEquals(applied, phone.childrenApplied)
    }

    @Test
    fun aRecordingBeingProcessedOnThePhoneGetsItsWebChildrenLater() {
        phone.create("r1", "Rapat 1", audio = "A", transcript = true)
        sync()
        server.webAddMark("r1", 5, "web")
        phone.recs.getValue("r1").working = true
        sync()
        assertTrue(phone.recs.getValue("r1").marks.none { it.label == "web" })
        assertTrue("r1" in store.retries)
        phone.recs.getValue("r1").working = false
        sync()
        assertTrue(phone.recs.getValue("r1").marks.any { it.label == "web" })
        assertTrue("r1" !in store.retries)
    }

    @Test
    fun localMarksNotYetSentAreStillPushedWhenWebChildrenArrive() {
        phone.create("r1", "Rapat 1", audio = "A", transcript = true)
        sync()
        phone.mark("r1", 40, "dari HP")
        server.webAddMark("r1", 50, "dari web")
        sync()
        assertEquals(setOf("dari HP", "dari web"), phone.recs.getValue("r1").marks.map { it.label }.toSet())
        assertEquals(setOf("dari HP", "dari web"), server.recs.getValue("r1").marks.values.map { it.label }.toSet())
    }
}
