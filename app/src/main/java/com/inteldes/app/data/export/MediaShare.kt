package com.inteldes.app.data.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Hands a stored photo or video to any app the user picks (WhatsApp, Drive, e-mail...) through the system share sheet. */
object MediaShare {
    /** Null when the file is gone. [mime] like "image/jpeg" or "video/mp4". Caption and map link go along as text. */
    fun intent(context: Context, file: File, mime: String, caption: String, latitude: Double?, longitude: Double?, placeName: String?): Intent? {
        if (!file.exists()) return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val text = buildList {
            if (caption.isNotBlank()) add(caption.trim())
            if (latitude != null && longitude != null) {
                placeName?.takeIf { it.isNotBlank() }?.let { add(it) }
                add("https://maps.google.com/?q=$latitude,$longitude")
            }
        }.joinToString("\n")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            if (text.isNotBlank()) putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Bagikan").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
