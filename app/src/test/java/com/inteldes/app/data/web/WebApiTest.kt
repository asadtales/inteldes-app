package com.inteldes.app.data.web

import com.inteldes.app.data.server.ServerApi
import com.inteldes.app.data.server.ServerException
import com.inteldes.app.data.server.TokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

private class Tokens : TokenStore {
    override val accessToken: String? = "ACC"
    override val refreshToken: String? = "REF"
    override fun saveTokens(deviceId: String, accessToken: String, refreshToken: String, expiresInSec: Int) {}
    override fun clearSession() {}
}

class WebApiTest {
    @get:org.junit.Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout(30, java.util.concurrent.TimeUnit.SECONDS)

    private lateinit var server: MockWebServer
    private val seen = CopyOnWriteArrayList<RecordedRequest>()
    private val dir: File = Files.createTempDirectory("webapi").toFile()
    private fun api(chunk: Int = 64 * 1024) = WebApi(ServerApi(server.url("/").toString().trimEnd('/'), Tokens()), chunkBytes = chunk)
    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("content-type", "application/json").setBody(body)

    @Before fun setUp() { server = MockWebServer(); seen.clear() }
    @After fun tearDown() { server.shutdown() }

    private fun respond(f: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { seen += request; return f(request) }
        }
    }

    private val recording = WebRecordingInput("Rapat", "Musdes", 1_000L, 60, null, "audio/mp4", "RAW", 0, "CLOUD", "builtin_standard", "Notulensi Standar", null, null, null, null, null, null, null, null)

    @Test
    fun aShareLinkIsMadeWithTheChosenOptionsAndItsRelativeUrlBecomesAbsolute() {
        respond { json(201, """{"id":"s1","includeTranscript":true,"includeAudio":false,"createdAt":5,"expiresAt":null,"revokedAt":null,"downloadCount":0,"url":"/s/TOKEN123"}""") }
        val made = runBlocking { api().createShare("r1", includeTranscript = true, includeAudio = false, expiresInDays = null) }
        val req = seen.single()
        assertEquals("POST", req.method)
        assertEquals("/v1/recordings/r1/shares", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"includeTranscript\":true") && body.contains("\"includeAudio\":false"))
        assertTrue(made.url.endsWith("/s/TOKEN123") && made.url.startsWith("http"))
        assertEquals(null, made.expiresAt)
    }

    @Test
    fun sharesOfARecordingTheServerDoesNotKnowAreNone_andRevokingIsADelete() {
        respond { r -> if (r.method == "DELETE") MockResponse().setResponseCode(204) else json(404, """{"error":{"code":"not_found","message":"x"}}""") }
        assertTrue(runBlocking { api().listShares("r1") }.isEmpty())
        runBlocking { api().revokeShare("s1") }
        assertEquals("/v1/shares/s1", seen.last().path)
        assertEquals("DELETE", seen.last().method)
    }

    @Test
    fun putSendsAQuotedIfMatchAndTheBearerTokenAndNeverTheWorkingStatus() {
        respond { json(200, """{"id":"r1","title":"Rapat","kind":"Musdes","createdAt":1000,"durationSec":60,"audioMimeType":"audio/mp4","status":"RAW","speakerCount":0,"engine":"CLOUD","templateId":"builtin_standard","templateName":"x","version":4,"transcriptVersion":0,"notesVersion":0,"updatedAt":1}""") }
        val saved = runBlocking { api().putRecording("r1", recording, 3) }
        assertEquals(4, saved.version)
        val req = seen.single()
        assertEquals("PUT", req.method)
        assertEquals("/v1/recordings/r1", req.path)
        assertEquals("\"3\"", req.getHeader("If-Match"))
        assertEquals("Bearer ACC", req.getHeader("Authorization"))
        assertTrue(req.body.readUtf8().contains("\"status\":\"RAW\""))
    }

    @Test
    fun aStaleVersionSurfacesAsA409ServerException() {
        respond { json(409, """{"error":{"code":"version_conflict","message":"Versi basi"}}""") }
        try {
            runBlocking { api().putTranscript("r1", emptyList(), 1) }
            fail("expected a conflict")
        } catch (e: ServerException) {
            assertEquals(409, e.status)
            assertEquals("version_conflict", e.code)
        }
    }

    @Test
    fun aBlobThatIsNotThereIsFalseNotAnError() {
        respond { json(404, """{"error":{"code":"not_found","message":"x"}}""") }
        assertFalse(runBlocking { api().blobExists("a".repeat(64)) })
    }

    @Test
    fun getAggregateReturnsNullOn404() {
        respond { json(404, """{"error":{"code":"not_found","message":"x"}}""") }
        assertEquals(null, runBlocking { api().getAggregate("zzz") })
    }

    @Test
    fun marksAreSentWithoutIfMatch() {
        respond { MockResponse().setResponseCode(204) }
        runBlocking { api().putMarks("r1", listOf(WebMark("m1", 5, "a", 1L))) }
        assertEquals(null, seen.single().getHeader("If-Match"))
    }

    @Test
    fun tusUploadCreatesThenSendsPiecesWithGrowingOffsets() {
        val data = ByteArray(150 * 1024) { (it % 251).toByte() }
        val file = File(dir, "a.m4a").apply { writeBytes(data) }
        val sha = WebSyncEngine.sha256(file)
        var offset = 0L
        respond { r ->
            when (r.method) {
                "POST" -> MockResponse().setResponseCode(201).setHeader("Location", "/v1/uploads/u1")
                "PATCH" -> {
                    assertEquals(offset.toString(), r.getHeader("Upload-Offset"))
                    offset += r.bodySize
                    MockResponse().setResponseCode(204).setHeader("Upload-Offset", offset.toString())
                }
                else -> MockResponse().setResponseCode(500)
            }
        }
        runBlocking { api(chunk = 64 * 1024).uploadBlob(sha, "audio/mp4", file) }
        assertEquals(data.size.toLong(), offset)
        val create = seen.first()
        assertEquals("1.0.0", create.getHeader("Tus-Resumable"))
        assertEquals(data.size.toString(), create.getHeader("Upload-Length"))
        val meta = create.getHeader("Upload-Metadata")!!
        assertTrue(meta.startsWith("sha256 ") && meta.contains(",filetype "))
        assertEquals(3, seen.count { it.method == "PATCH" })        // 64 + 64 + 22 KB
    }

    @Test
    fun aDownloadWhoseHashDoesNotMatchIsRejectedAndLeavesNothingBehind() {
        respond { r ->
            if (r.path!!.endsWith("/url")) json(200, """{"url":"/v1/blobs/${"b".repeat(64)}?t=tok","expiresAt":1}""")
            else MockResponse().setResponseCode(200).setBody("not the right bytes")
        }
        val dest = File(dir, "out/x.m4a")
        try {
            runBlocking { api().downloadBlob("b".repeat(64), dest) }
            fail("expected a hash mismatch")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("sha256"))
        }
        assertFalse(dest.exists())
        assertFalse(File(dir, "out/x.m4a.part").exists())
    }

    @Test
    fun aGoodDownloadIsVerifiedAndMovedIntoPlace() {
        val body = "hello audio"
        val sha = WebSyncEngine.sha256(body)
        respond { r ->
            if (r.path!!.endsWith("/url")) json(200, """{"url":"/v1/blobs/$sha?t=tok","expiresAt":1}""")
            else MockResponse().setResponseCode(200).setBody(body)
        }
        val dest = File(dir, "ok/x.m4a")
        runBlocking { api().downloadBlob(sha, dest) }
        assertEquals(body, dest.readText())
    }
}
