package com.inteldes.app.data.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class ImportedAudio(val file: File, val durationSec: Int, val mimeType: String)

/**
 * Copies a user-picked audio file (from the system file picker / Storage Access
 * Framework) into the app's own recordings directory and reads its real duration
 * and MIME type, so an uploaded file flows through the exact same transcription +
 * notulensi pipeline as a live recording — [com.inteldes.app.data.work.ProcessRecordingWorker]
 * doesn't need to know whether a file was recorded or imported.
 */
object AudioFileImporter {
    suspend fun import(context: Context, uri: Uri): ImportedAudio = withContext(Dispatchers.IO) {
        val mimeType = context.contentResolver.getType(uri) ?: "audio/mp4"
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "m4a"

        val dir = File(context.filesDir, "recordings").apply { mkdirs() }
        val destFile = File(dir, "upload_${System.currentTimeMillis()}.$extension")

        context.contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("Tidak bisa membaca berkas audio yang dipilih.")

        val durationSec = readDurationSec(destFile)
        ImportedAudio(destFile, durationSec, mimeType)
    }

    private fun readDurationSec(file: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            (ms / 1000).toInt()
        } finally {
            retriever.release()
        }
    }
}
