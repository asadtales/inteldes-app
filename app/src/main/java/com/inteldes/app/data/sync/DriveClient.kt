package com.inteldes.app.data.sync

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class DriveException(val code: Int, message: String) : IOException(message)

/**
 * Minimal Google Drive v3 REST client over OkHttp. With the `drive.file` scope the app can only see
 * files it created itself, so listing "everything" returns just the INTELDES tree — it never touches
 * anything else in the user's Drive. [tokenProvider] returns a fresh OAuth access token.
 */
class DriveClient(
    private val tokenProvider: suspend () -> String,
    private val folderName: String = "INTELDES",
    private val http: OkHttpClient = defaultHttp(),
) : SyncRemote {
    private var rootFolderId: String? = null

    // ───────────────────────── SyncRemote ─────────────────────────

    /** The top INTELDES folder; if several exist (two devices raced on first sync) the oldest wins. */
    override suspend fun rootId(): String = withContext(Dispatchers.IO) {
        rootFolderId?.let { return@withContext it }
        val url = "$API/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", "name = '$folderName' and mimeType = '$FOLDER_MIME' and trashed = false and 'root' in parents")
            .addQueryParameter("fields", "files(id,createdTime)")
            .addQueryParameter("orderBy", "createdTime")
            .build()
        val existing = execJson(Request.Builder().url(url)).getAsJsonArray("files")?.firstOrNull()?.asJsonObject?.get("id")?.asString
        val id = existing ?: run {
            val body = JsonObject().apply { addProperty("name", folderName); addProperty("mimeType", FOLDER_MIME) }
                .toString().toRequestBody(JSON_UTF8)
            execJson(Request.Builder().url("$API/files?fields=id").post(body)).get("id").asString
        }
        rootFolderId = id
        id
    }

    override suspend fun listFiles(): List<RemoteFile> = withContext(Dispatchers.IO) {
        val out = mutableListOf<RemoteFile>()
        var pageToken: String? = null
        do {
            val url = "$API/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", "trashed = false")
                .addQueryParameter("fields", "nextPageToken,files($FILE_FIELDS)")
                .addQueryParameter("pageSize", "1000")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val json = execJson(Request.Builder().url(url))
            json.getAsJsonArray("files")?.forEach { out += it.asJsonObject.toRemoteFile() }
            pageToken = json.get("nextPageToken")?.takeIf { !it.isJsonNull }?.asString
        } while (pageToken != null)
        out
    }

    override suspend fun createFolder(parentId: String, name: String): RemoteFile = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("name", name)
            addProperty("mimeType", FOLDER_MIME)
            add("parents", JsonArray().apply { add(parentId) })
        }.toString().toRequestBody(JSON_UTF8)
        execJson(Request.Builder().url("$API/files?fields=$FILE_FIELDS").post(body)).toRemoteFile()
    }

    override suspend fun update(fileId: String, newName: String?, newParentId: String?, oldParentId: String?): RemoteFile =
        withContext(Dispatchers.IO) {
            val url = "$API/files/$fileId".toHttpUrl().newBuilder().addQueryParameter("fields", FILE_FIELDS)
                .apply {
                    newParentId?.let { addQueryParameter("addParents", it) }
                    if (newParentId != null && oldParentId != null) addQueryParameter("removeParents", oldParentId)
                }.build()
            val body = JsonObject().apply { newName?.let { addProperty("name", it) } }.toString().toRequestBody(JSON_UTF8)
            execJson(Request.Builder().url(url).patch(body)).toRemoteFile()
        }

    override suspend fun download(file: RemoteFile): ByteArray = withContext(Dispatchers.IO) {
        authed(Request.Builder().url("$API/files/${file.id}?alt=media")).use { it.body!!.bytes() }
    }

    override suspend fun downloadTo(file: RemoteFile, dest: File) = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        authed(Request.Builder().url("$API/files/${file.id}?alt=media")).use { resp ->
            resp.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) throw IOException("Gagal menyimpan ${dest.name}")
    }

    override suspend fun uploadBytes(parentId: String, name: String, bytes: ByteArray, mimeType: String, existingId: String?): RemoteFile =
        withContext(Dispatchers.IO) {
            val media = bytes.toRequestBody(mimeType.toMediaType())
            val request = if (existingId != null) {
                Request.Builder().url("$UPLOAD/$existingId?uploadType=media&fields=$FILE_FIELDS").patch(media)
            } else {
                val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
                    .addPart(metadata(name, parentId).toString().toRequestBody(JSON_UTF8))
                    .addPart(media)
                    .build()
                Request.Builder().url("$UPLOAD?uploadType=multipart&fields=$FILE_FIELDS").post(body)
            }
            execJson(request).toRemoteFile()
        }

    /** Large files (audio, docx with photos) go through a resumable upload session so they stream from disk. */
    override suspend fun uploadFile(parentId: String, name: String, file: File, mimeType: String, existingId: String?): RemoteFile =
        withContext(Dispatchers.IO) {
            val meta = (if (existingId == null) metadata(name, parentId) else JsonObject()).toString().toRequestBody(JSON_UTF8)
            val start = if (existingId != null) {
                Request.Builder().url("$UPLOAD/$existingId?uploadType=resumable&fields=$FILE_FIELDS").patch(meta)
            } else {
                Request.Builder().url("$UPLOAD?uploadType=resumable&fields=$FILE_FIELDS").post(meta)
            }.header("X-Upload-Content-Type", mimeType).header("X-Upload-Content-Length", file.length().toString())
            val sessionUrl = authed(start).use { it.header("Location") ?: throw IOException("Drive tidak memberi alamat unggah") }
            val put = Request.Builder().url(sessionUrl).put(file.asRequestBody(mimeType.toMediaType()))
            http.newCall(put.build()).execute().use { resp ->
                if (!resp.isSuccessful) throw DriveException(resp.code, "Unggah ${file.name} gagal (${resp.code})")
                JsonParser.parseString(resp.body!!.string()).asJsonObject.toRemoteFile()
            }
        }

    override suspend fun delete(fileId: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$API/files/$fileId").delete()
        val resp = http.newCall(request.header("Authorization", "Bearer ${tokenProvider()}").build()).execute()
        resp.use { if (!it.isSuccessful && it.code != 404) throw DriveException(it.code, "Hapus berkas gagal (${it.code})") }
    }

    /** "name (email)" of the signed-in Google account, shown in Settings. */
    suspend fun accountLabel(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val user = execJson(Request.Builder().url("$API/about?fields=user(displayName,emailAddress)")).getAsJsonObject("user")
            val email = user.get("emailAddress")?.asString
            val name = user.get("displayName")?.asString
            listOfNotNull(name, email?.let { "($it)" }).joinToString(" ").ifBlank { null }
        }.getOrNull()
    }

    // ───────────────────────── plumbing ─────────────────────────

    private fun metadata(name: String, parentId: String) = JsonObject().apply {
        addProperty("name", name)
        add("parents", JsonArray().apply { add(parentId) })
    }

    private suspend fun execJson(builder: Request.Builder): JsonObject =
        authed(builder).use { JsonParser.parseString(it.body!!.string()).asJsonObject }

    /** Runs [builder] with the access token; maps failures to [DriveException] / [AuthException]. */
    private suspend fun authed(builder: Request.Builder): Response {
        val resp = http.newCall(builder.header("Authorization", "Bearer ${tokenProvider()}").build()).execute()
        if (resp.isSuccessful) return resp
        val code = resp.code
        val detail = runCatching { resp.body?.string()?.take(300) }.getOrNull().orEmpty()
        resp.close()
        if (code == 401) throw AuthException("Sesi Google Drive berakhir. Hubungkan ulang di Pengaturan.")
        if (code == 403 && detail.contains("storageQuotaExceeded")) throw DriveException(code, "Penyimpanan Google Drive penuh.")
        throw DriveException(code, "Drive menolak permintaan ($code) $detail")
    }

    private fun JsonObject.toRemoteFile() = RemoteFile(
        id = get("id").asString,
        name = get("name")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
        md5 = get("md5Checksum")?.takeIf { !it.isJsonNull }?.asString,
        parentId = get("parents")?.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull()?.asString,
        isFolder = get("mimeType")?.takeIf { !it.isJsonNull }?.asString == FOLDER_MIME,
    )

    companion object {
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        private const val FILE_FIELDS = "id,name,md5Checksum,parents,mimeType"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private val JSON_UTF8 = "application/json; charset=UTF-8".toMediaType()

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .build()
    }
}
