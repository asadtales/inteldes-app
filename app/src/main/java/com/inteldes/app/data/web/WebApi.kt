package com.inteldes.app.data.web

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.inteldes.app.data.network.await
import com.inteldes.app.data.server.ServerApi
import com.inteldes.app.data.server.ServerException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * [WebRemote] over HTTP. Uses the session client of [ServerApi], so the Bearer token and the single-flight
 * refresh come for free. No logging: bodies carry the user's meeting content.
 */
class WebApi(
    private val server: ServerApi,
    private val gson: Gson = Gson(),
    chunkBytes: Int = 4 * 1024 * 1024,
) : WebRemote {
    private val base = server.baseUrlString.trimEnd('/')
    private val chunk = chunkBytes.coerceIn(64 * 1024, 8 * 1024 * 1024)       // the server caps one piece at 8 MB
    private val client: OkHttpClient = server.sessionClient.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build()

    // ───────────────────────── reads ─────────────────────────

    override suspend fun changes(since: Long): WebChangesPage =
        gson.fromJson(text(get("/v1/sync/changes?since=$since&limit=200")), WebChangesPage::class.java)

    override suspend fun getAggregate(id: String): WebAggregate? =
        try {
            val json = com.google.gson.JsonParser.parseString(text(get("/v1/recordings/$id"))).asJsonObject
            WebAggregate(
                recording = gson.fromJson(json["recording"], WebRecording::class.java),
                transcript = gson.fromJson(json["transcript"], WebTranscriptPart::class.java),
                notes = gson.fromJson(json["notes"], WebNotesPart::class.java),
                marks = gson.fromJson(json["marks"], Array<WebMark>::class.java).toList(),
                photos = gson.fromJson(json["photos"], Array<WebPhoto>::class.java).toList(),
                chat = gson.fromJson(json["chat"], Array<WebChat>::class.java).toList(),
            )
        } catch (e: ServerException) {
            if (e.status == 404) null else throw e
        }

    override suspend fun listCategories(): List<WebCategory> {
        val json = com.google.gson.JsonParser.parseString(text(get("/v1/categories"))).asJsonObject
        return gson.fromJson(json["items"], Array<WebCategory>::class.java).toList()
    }

    // ───────────────────────── writes ─────────────────────────

    override suspend fun putRecording(id: String, input: WebRecordingInput, ifMatch: Int): WebRecording =
        gson.fromJson(text(put("/v1/recordings/$id", gson.toJson(input), ifMatch)), WebRecording::class.java)

    override suspend fun putTranscript(id: String, segments: List<WebSegment>, ifMatch: Int): Int {
        val body = JsonObject().apply { add("segments", gson.toJsonTree(segments)) }
        return com.google.gson.JsonParser.parseString(text(put("/v1/recordings/$id/transcript", gson.toJson(body), ifMatch)))
            .asJsonObject["transcriptVersion"].asInt
    }

    override suspend fun putNotes(id: String, sections: List<WebSection>, ifMatch: Int): Int {
        val body = JsonObject().apply { add("sections", gson.toJsonTree(sections)) }
        return com.google.gson.JsonParser.parseString(text(put("/v1/recordings/$id/notes", gson.toJson(body), ifMatch)))
            .asJsonObject["notesVersion"].asInt
    }

    override suspend fun putMarks(id: String, marks: List<WebMark>) {
        for (part in marks.chunked(1000)) {
            val body = JsonObject().apply { add("marks", gson.toJsonTree(part)) }
            text(put("/v1/recordings/$id/marks", gson.toJson(body), null))
        }
    }

    override suspend fun postChat(id: String, messages: List<WebChat>) {
        for (part in messages.chunked(500)) {
            val body = JsonObject().apply { add("messages", gson.toJsonTree(part)) }
            text(Request.Builder().url("$base/v1/recordings/$id/chat").post(gson.toJson(body).toRequestBody(JSON)).build())
        }
    }

    override suspend fun deleteRecording(id: String) {
        text(Request.Builder().url("$base/v1/recordings/$id").delete().build())
    }

    override suspend fun putCategory(id: String, label: String, orderIndex: Int, ifMatch: Int): WebCategory {
        val body = JsonObject().apply { addProperty("label", label); addProperty("orderIndex", orderIndex) }
        return gson.fromJson(text(put("/v1/categories/$id", gson.toJson(body), ifMatch)), WebCategory::class.java)
    }

    // ───────────────────────── blobs ─────────────────────────

    override suspend fun blobExists(sha256: String): Boolean =
        try { text(get("/v1/blobs/$sha256/url")); true } catch (e: ServerException) { if (e.status == 404) false else throw e }

    override suspend fun downloadBlob(sha256: String, dest: File) {
        val rel = com.google.gson.JsonParser.parseString(text(get("/v1/blobs/$sha256/url"))).asJsonObject["url"].asString
        val url = if (rel.startsWith("http")) rel else base + rel
        val tmp = File(dest.parentFile, dest.name + ".part")
        withContext(Dispatchers.IO) {
            dest.parentFile?.mkdirs()
            client.newCall(Request.Builder().url(url).get().build()).await().use { resp ->
                if (!resp.isSuccessful) throw server.toException(resp.code, resp.body?.string().orEmpty())
                resp.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            }
            // A truncated or tampered download must never replace anything: verify before moving into place.
            if (!WebSyncEngine.sha256(tmp).equals(sha256, ignoreCase = true)) {
                tmp.delete()
                throw IOException("Unduhan rusak (sha256 tidak cocok)")
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
        }
    }

    /** tus 1.0.0: create, then send pieces; after a dropped connection, ask the server where it got to and continue. */
    override suspend fun uploadBlob(sha256: String, mimeType: String, file: File) {
        val length = file.length()
        val meta = "sha256 ${b64(sha256)},filetype ${b64(mimeType)}"
        val create = Request.Builder().url("$base/v1/uploads")
            .header("Tus-Resumable", "1.0.0").header("Upload-Length", length.toString()).header("Upload-Metadata", meta)
            .post(ByteArray(0).toRequestBody(null)).build()
        val location = client.newCall(create).await().use { resp ->
            if (resp.code != 201) throw server.toException(resp.code, resp.body?.string().orEmpty())
            resp.header("Location") ?: throw IOException("Server tidak memberi lokasi unggahan")
        }
        val uploadUrl = location.toHttpUrlOrNull(base)
        var offset = 0L
        var failures = 0
        withContext(Dispatchers.IO) {
            RandomAccessFile(file, "r").use { raf ->
                while (offset < length) {
                    val size = minOf(chunk.toLong(), length - offset).toInt()
                    val buf = ByteArray(size)
                    raf.seek(offset)
                    raf.readFully(buf)
                    val patch = Request.Builder().url(uploadUrl)
                        .header("Tus-Resumable", "1.0.0").header("Upload-Offset", offset.toString())
                        .patch(buf.toRequestBody("application/offset+octet-stream".toMediaType())).build()
                    try {
                        client.newCall(patch).await().use { resp ->
                            when {
                                resp.code == 204 -> offset = resp.header("Upload-Offset")?.toLongOrNull() ?: (offset + size)
                                resp.code == 409 -> offset = currentOffset(uploadUrl)
                                else -> throw server.toException(resp.code, resp.body?.string().orEmpty())
                            }
                        }
                    } catch (e: IOException) {
                        if (++failures > 3) throw e
                        offset = currentOffset(uploadUrl)                                // continue where the server got to
                    }
                }
            }
        }
    }

    private suspend fun currentOffset(url: okhttp3.HttpUrl): Long {
        val head = Request.Builder().url(url).header("Tus-Resumable", "1.0.0").head().build()
        return client.newCall(head).await().use { resp ->
            if (!resp.isSuccessful) throw server.toException(resp.code, "")
            resp.header("Upload-Offset")?.toLongOrNull() ?: throw IOException("Offset tidak diketahui")
        }
    }

    // ───────────────────────── plumbing ─────────────────────────

    private fun String.toHttpUrlOrNull(root: String): okhttp3.HttpUrl =
        if (startsWith("http")) toHttpUrl() else (root + this).toHttpUrl()

    private fun get(path: String) = Request.Builder().url(base + path).get().build()

    /** `If-Match` as a quoted version ("3"); null = no header (the marks merge has none). */
    private fun put(path: String, json: String, ifMatch: Int?): Request {
        val b = Request.Builder().url(base + path).put(json.toRequestBody(JSON))
        if (ifMatch != null) b.header("If-Match", "\"$ifMatch\"")
        return b.build()
    }

    private suspend fun text(request: Request): String =
        client.newCall(request).await().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw server.toException(resp.code, body)
            body
        }

    private fun b64(s: String) = java.util.Base64.getEncoder().encodeToString(s.toByteArray())

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
