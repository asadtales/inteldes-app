package com.inteldes.app.data.video

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** What the server accepts for a short video (it may change these limits; its own message is shown when it refuses). */
object VideoRules {
    const val MAX_BYTES = 100L * 1024 * 1024
    const val MAX_SEC = 120.0
    const val MAX_PER_RECORDING = 20

    /** Container types the server takes. */
    private val accepted = setOf("video/mp4", "video/webm", "video/quicktime", "video/3gpp")

    /** The MIME as the server names it, or null for something that is not an accepted video. */
    fun normalizeMime(mime: String?): String? {
        val base = mime?.substringBefore(';')?.trim()?.lowercase() ?: return null
        val mapped = when (base) {
            "video/x-m4v", "video/mpeg4" -> "video/mp4"
            "video/3gp" -> "video/3gpp"
            else -> base
        }
        return mapped.takeIf { it in accepted }
    }

    fun extensionOf(mime: String): String = when (mime) {
        "video/webm" -> "webm"
        "video/quicktime" -> "mov"
        "video/3gpp" -> "3gp"
        else -> "mp4"
    }

    /** Null when a video of this size and length may be sent; otherwise what to tell the user, before anything is copied or sent. */
    fun problem(sizeBytes: Long, durationSec: Double?): String? = when {
        sizeBytes <= 0 -> "Berkas video kosong."
        sizeBytes > MAX_BYTES -> "Video ${"%.0f".format(sizeBytes / 1_048_576.0)} MB terlalu besar (maksimal ${MAX_BYTES / 1_048_576} MB). Pilih video yang lebih pendek atau lebih kecil."
        durationSec != null && durationSec > MAX_SEC -> "Video ${"%.0f".format(durationSec)} detik terlalu panjang (maksimal ${MAX_SEC.toInt()} detik). Potong dulu di galeri."
        else -> null
    }
}

class ImportedVideo(val file: File, val durationSec: Double?, val sizeBytes: Long, val mime: String)

/** Videos attached to a recording, kept in the app's own `files/videos`. */
object VideoStorage {
    fun authority(context: Context) = "${context.packageName}.fileprovider"

    /** A cache file plus the FileProvider URI the camera app writes the recording to. */
    fun newCameraTarget(context: Context): Uri {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        return FileProvider.getUriForFile(context, authority(context), File(dir, "video_${UUID.randomUUID()}.mp4"))
    }

    /** The URI a player app can open for a stored video. */
    fun playUri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)

    /** Checks the limits first, then copies the chosen video into the app. Throws [IOException] with a sentence for the user. */
    suspend fun import(context: Context, uri: Uri): ImportedVideo = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
            ?: resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            ?: throw IOException("Video tidak bisa dibaca.")
        val mime = VideoRules.normalizeMime(resolver.getType(uri) ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(uri.lastPathSegment?.substringAfterLast('.', "")))
            ?: throw IOException("Jenis video ini belum didukung. Yang bisa: MP4, WebM, MOV, 3GP.")
        val duration = durationOf(context, uri)
        VideoRules.problem(size, duration)?.let { throw IOException(it) }

        val dir = File(context.filesDir, "videos").apply { mkdirs() }
        val out = File(dir, "video_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.${VideoRules.extensionOf(mime)}")
        resolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: throw IOException("Video tidak bisa dibaca.")
        if (out.length() > VideoRules.MAX_BYTES) { out.delete(); throw IOException(VideoRules.problem(out.length(), duration) ?: "Video terlalu besar.") }
        ImportedVideo(out, duration, out.length(), mime)
    }

    private fun durationOf(context: Context, uri: Uri): Double? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { it / 1000.0 }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }
}
