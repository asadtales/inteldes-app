package com.inteldes.app.data.importer

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Reads a picked conversation file: a plain .txt, or a WhatsApp export .zip (its chat .txt is extracted). */
object TextFileImporter {
    suspend fun read(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(2)
            val buffered = java.io.PushbackInputStream(input, 2)
            val n = buffered.read(head)
            if (n > 0) buffered.unread(head, 0, n)
            if (n == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) readZippedChat(buffered) else readLimited(buffered)
        } ?: throw IOException("Tidak bisa membaca berkas yang dipilih.")
        if (bytes.size > ChatImport.MAX_CHARS * 3) throw IOException("Berkas terlalu besar untuk dijadikan notulensi.")
        val text = bytes.toString(Charsets.UTF_8)
        if (text.length > ChatImport.MAX_CHARS) {
            throw IOException("Percakapan terlalu panjang (${text.length / 1000} ribu karakter). Potong dulu menjadi bagian yang lebih pendek.")
        }
        text
    }

    private fun readLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > ChatImport.MAX_CHARS * 3) break
        }
        return out.toByteArray()
    }

    /** First .txt in the archive; WhatsApp names it `_chat.txt` or "Obrolan WhatsApp dengan X.txt". */
    private fun readZippedChat(input: InputStream): ByteArray {
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.endsWith(".txt", ignoreCase = true)) return readLimited(zip)
            }
        }
        throw IOException("Tidak ada berkas teks (.txt) di dalam zip itu.")
    }
}
