package com.inteldes.app.data.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.inteldes.app.data.model.Notulensi
import com.inteldes.app.data.model.Recording
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports a Notulensi to a structured .txt or .docx file in the app's cache dir
 * and returns a content:// Uri (via FileProvider) ready to share.
 */
object NotulensiExporter {

    private val dateFormat = SimpleDateFormat("dd MMMM yyyy, HH:mm", Locale("id", "ID"))

    private fun safeFileName(title: String): String {
        val cleaned = title.trim().ifBlank { "Notulensi" }.replace(Regex("[^A-Za-z0-9 _-]"), "").replace(" ", "_")
        return cleaned.take(60).ifBlank { "Notulensi" }
    }

    private fun exportDir(context: Context): File = File(context.cacheDir, "exports").apply { mkdirs() }

    fun exportTxt(context: Context, recording: Recording, notulensi: Notulensi): Uri {
        val file = File(exportDir(context), "${safeFileName(recording.title)}.txt")
        file.writeText(buildPlainText(recording, notulensi))
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun exportDocx(context: Context, recording: Recording, notulensi: Notulensi): Uri {
        val file = File(exportDir(context), "${safeFileName(recording.title)}.docx")
        writeDocx(file, recording, notulensi)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun shareIntent(uri: Uri, mimeType: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Bagikan notulensi")
    }

    // ───────────────────────── plain text ─────────────────────────

    private fun buildPlainText(recording: Recording, notulensi: Notulensi): String = buildString {
        appendLine(recording.title)
        appendLine("Kategori: ${recording.kind.label}  |  Tanggal: ${dateFormat.format(Date(recording.createdAt))}")
        appendLine("Disusun oleh agent AI: ${notulensi.provider.label}")
        appendLine()
        appendLine("RINGKASAN")
        appendLine(notulensi.summary)
        if (notulensi.topics.isNotEmpty()) {
            appendLine()
            appendLine("POIN BAHASAN")
            notulensi.topics.forEach { t ->
                appendLine("[${t.timestampLabel}] ${t.title}")
                appendLine(t.body)
            }
        }
        if (notulensi.decisions.isNotEmpty()) {
            appendLine()
            appendLine("KEPUTUSAN")
            notulensi.decisions.forEach { d -> appendLine("${d.number}. ${d.text}") }
        }
        if (notulensi.questions.isNotEmpty()) {
            appendLine()
            appendLine("POIN PERTANYAAN")
            notulensi.questions.forEach { q ->
                appendLine("- ${q.question} (${q.status.label})")
                if (q.note.isNotBlank()) appendLine("  Catatan: ${q.note}")
            }
        }
        if (notulensi.actions.isNotEmpty()) {
            appendLine()
            appendLine("ACTION ITEM")
            notulensi.actions.forEach { a ->
                appendLine("- ${a.task}")
                appendLine("  PIC: ${a.pic}  |  Tenggat: ${a.due}")
            }
        }
    }

    // ───────────────────────── docx (minimal OOXML, no external library) ─────────────────────────

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun docTitle(text: String): String =
        "<w:p><w:pPr><w:jc w:val=\"center\"/><w:spacing w:after=\"60\"/></w:pPr><w:r><w:rPr><w:b/><w:sz w:val=\"36\"/></w:rPr><w:t xml:space=\"preserve\">${xmlEscape(text)}</w:t></w:r></w:p>"

    private fun heading(text: String): String =
        "<w:p><w:pPr><w:spacing w:before=\"280\" w:after=\"120\"/></w:pPr><w:r><w:rPr><w:b/><w:sz w:val=\"28\"/></w:rPr><w:t xml:space=\"preserve\">${xmlEscape(text)}</w:t></w:r></w:p>"

    private fun paragraph(text: String, bold: Boolean = false, sizeHalfPt: Int = 22): String {
        val runs = text.split("\n").mapIndexed { i, line ->
            val br = if (i > 0) "<w:br/>" else ""
            "$br<w:r><w:rPr>${if (bold) "<w:b/>" else ""}<w:sz w:val=\"$sizeHalfPt\"/></w:rPr><w:t xml:space=\"preserve\">${xmlEscape(line)}</w:t></w:r>"
        }.joinToString("")
        return "<w:p><w:pPr><w:spacing w:after=\"140\"/></w:pPr>$runs</w:p>"
    }

    private fun writeDocx(file: File, recording: Recording, notulensi: Notulensi) {
        val body = buildString {
            append(docTitle(recording.title))
            append(paragraph("Kategori: ${recording.kind.label}  |  Tanggal: ${dateFormat.format(Date(recording.createdAt))}", sizeHalfPt = 18))
            append(paragraph("Disusun oleh agent AI: ${notulensi.provider.label}", sizeHalfPt = 18))
            append(heading("Ringkasan"))
            append(paragraph(notulensi.summary))
            if (notulensi.topics.isNotEmpty()) {
                append(heading("Poin Bahasan"))
                notulensi.topics.forEach { t ->
                    append(paragraph("[${t.timestampLabel}] ${t.title}", bold = true))
                    append(paragraph(t.body))
                }
            }
            if (notulensi.decisions.isNotEmpty()) {
                append(heading("Keputusan"))
                notulensi.decisions.forEach { d -> append(paragraph("${d.number}. ${d.text}")) }
            }
            if (notulensi.questions.isNotEmpty()) {
                append(heading("Poin Pertanyaan"))
                notulensi.questions.forEach { q ->
                    val note = if (q.note.isNotBlank()) "\nCatatan: ${q.note}" else ""
                    append(paragraph("${q.question} (${q.status.label})$note"))
                }
            }
            if (notulensi.actions.isNotEmpty()) {
                append(heading("Action Item"))
                notulensi.actions.forEach { a ->
                    append(paragraph("${a.task}\nPIC: ${a.pic}  |  Tenggat: ${a.due}"))
                }
            }
        }

        val documentXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:body>$body<w:sectPr>" +
            "<w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
            "<w:pgMar w:top=\"1417\" w:right=\"1417\" w:bottom=\"1417\" w:left=\"1417\"/>" +
            "</w:sectPr></w:body></w:document>"

        val contentTypesXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "</Types>"

        val rootRelsXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
            "</Relationships>"

        ZipOutputStream(FileOutputStream(file)).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("[Content_Types].xml", contentTypesXml)
            entry("_rels/.rels", rootRelsXml)
            entry("word/document.xml", documentXml)
        }
    }
}
