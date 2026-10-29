package com.inteldes.app.data.export

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import com.inteldes.app.data.model.Notulensi
import com.inteldes.app.data.model.Photo
import com.inteldes.app.data.model.Recording
import com.inteldes.app.data.model.SectionContent
import com.inteldes.app.data.model.TranscriptSegment
import com.inteldes.app.ui.util.formatClock
import com.inteldes.app.ui.util.formatMmSs
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports a Notulensi to a structured .txt or .docx file in the app's cache dir
 * and returns a content:// Uri (via FileProvider) ready to share. The .docx embeds the
 * documentation photos; the .txt lists them by caption only.
 */
object NotulensiExporter {

    private val dateFormat = SimpleDateFormat("dd MMMM yyyy, HH:mm", Locale("id", "ID"))

    private fun safeFileName(title: String): String {
        val cleaned = title.trim().ifBlank { "Notulensi" }.replace(Regex("[^A-Za-z0-9 _-]"), "").replace(" ", "_")
        return cleaned.take(60).ifBlank { "Notulensi" }
    }

    private fun exportDir(context: Context): File = File(context.cacheDir, "exports").apply { mkdirs() }

    fun exportTxt(context: Context, recording: Recording, notulensi: Notulensi, photos: List<Photo> = emptyList()): Uri {
        val file = File(exportDir(context), "${safeFileName(recording.title)}.txt")
        file.writeText(buildPlainText(recording, notulensi, photos))
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun exportDocx(context: Context, recording: Recording, notulensi: Notulensi, photos: List<Photo> = emptyList()): Uri {
        val file = File(exportDir(context), "${safeFileName(recording.title)}.docx")
        writeDocx(file, recording, notulensi, photos)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun shareIntent(uri: Uri, mimeType: String, chooserTitle: String = "Bagikan notulensi"): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, chooserTitle)
    }

    /**
     * Copies the recording's own audio file to a friendlier name under the shareable
     * exports dir and returns a content:// Uri for it — for sharing the raw recording
     * itself (e.g. to WhatsApp) or saving it out of the app, separate from exporting the
     * generated notulensi text.
     */
    fun exportAudio(context: Context, recording: Recording): Uri {
        val sourcePath = recording.audioFilePath ?: throw IllegalStateException("Rekaman ini tidak punya berkas audio.")
        val source = File(sourcePath)
        val extension = source.extension.ifBlank { "m4a" }
        val file = File(exportDir(context), "${safeFileName(recording.title)}.$extension")
        source.copyTo(file, overwrite = true)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    // ───────────────────────── shared content ─────────────────────────

    private fun photoLabel(index: Int, photo: Photo): String {
        val at = photo.takenAtSec?.let { " (menit ${formatMmSs(it)})" }.orEmpty()
        val cap = if (photo.caption.isNotBlank()) ": ${photo.caption}" else ""
        return "Foto ${index + 1}$at$cap"
    }

    /** One entry per list item (a multi-line item uses \n), shared by the .txt and .docx writers. */
    internal fun sectionLines(content: SectionContent): List<String> = when (content) {
        is SectionContent.Paragraph -> listOfNotNull(content.text.takeIf { it.isNotBlank() })
        is SectionContent.Bullets -> content.items.map { "- $it" }
        is SectionContent.Topics -> content.items.map { "[${it.timestampLabel}] ${it.title}\n${it.body}" }
        is SectionContent.Decisions -> content.items.map { "${it.number}. ${it.text}" }
        is SectionContent.Questions -> content.items.map { q ->
            "- ${q.question} (${q.status.label})" + if (q.note.isNotBlank()) "\n  Catatan: ${q.note}" else ""
        }
        is SectionContent.Actions -> content.items.map { "- ${it.task}\n  PIC: ${it.pic}  |  Tenggat: ${it.due}" }
    }

    // ───────────────────────── plain text ─────────────────────────

    /** Plain-text transcript: header, then one "[hh:mm:ss] Pembicara: ucapan" line per segment. */
    fun buildTranscriptText(recording: Recording, segments: List<TranscriptSegment>): String = buildString {
        appendLine("Transkrip: ${recording.title}")
        appendLine("Kategori: ${recording.kind}  |  Tanggal: ${dateFormat.format(Date(recording.createdAt))}")
        recording.locationName?.let { appendLine("Lokasi: $it") }
        appendLine()
        segments.forEach { appendLine("[${formatClock(it.timestampSec)}] ${it.speaker}: ${it.text}") }
    }

    internal fun writeTxtFile(file: File, recording: Recording, notulensi: Notulensi, photos: List<Photo>) {
        file.writeText(buildPlainText(recording, notulensi, photos))
    }

    private fun buildPlainText(recording: Recording, notulensi: Notulensi, photos: List<Photo>): String = buildString {
        appendLine(recording.title)
        appendLine("Kategori: ${recording.kind}  |  Tanggal: ${dateFormat.format(Date(recording.createdAt))}")
        recording.locationName?.let { appendLine("Lokasi: $it") }
        appendLine("Template: ${recording.templateName}  |  Disusun oleh agent AI: ${notulensi.provider.label}")
        appendLine()
        appendLine("RINGKASAN")
        appendLine(notulensi.summary)
        notulensi.sections.forEach { section ->
            val lines = sectionLines(section.content)
            if (lines.isEmpty()) return@forEach
            appendLine()
            appendLine(section.title.uppercase())
            lines.forEach { appendLine(it) }
        }
        if (photos.isNotEmpty()) {
            appendLine()
            appendLine("DOKUMENTASI")
            photos.forEachIndexed { i, p -> appendLine("- ${photoLabel(i, p)}") }
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

    private class DocxImage(val name: String, val file: File, val widthEmu: Long, val heightEmu: Long)

    /** Fits [photo] within a 15 x 11 cm box (1 cm = 360000 EMU) keeping its aspect ratio; null if unreadable. */
    private fun loadImage(index: Int, photo: Photo): DocxImage? {
        val file = File(photo.filePath).takeIf { it.exists() } ?: return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        val scale = minOf(15.0 * 360000 / opts.outWidth, 11.0 * 360000 / opts.outHeight)
        return DocxImage("image${index + 1}.jpg", file, (opts.outWidth * scale).toLong(), (opts.outHeight * scale).toLong())
    }

    private fun imageParagraph(rId: String, id: Int, img: DocxImage): String =
        "<w:p><w:pPr><w:spacing w:before=\"120\" w:after=\"40\"/></w:pPr><w:r><w:drawing>" +
            "<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"${img.widthEmu}\" cy=\"${img.heightEmu}\"/>" +
            "<wp:docPr id=\"$id\" name=\"Foto $id\"/>" +
            "<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">" +
            "<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:nvPicPr><pic:cNvPr id=\"$id\" name=\"${img.name}\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip r:embed=\"$rId\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"${img.widthEmu}\" cy=\"${img.heightEmu}\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>" +
            "</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"

    internal fun writeDocx(file: File, recording: Recording, notulensi: Notulensi, photos: List<Photo>) {
        val images = photos.mapIndexedNotNull { i, p -> loadImage(i, p)?.let { Triple(i, p, it) } }
        val body = buildString {
            append(docTitle(recording.title))
            append(paragraph("Kategori: ${recording.kind}  |  Tanggal: ${dateFormat.format(Date(recording.createdAt))}", sizeHalfPt = 18))
            recording.locationName?.let { append(paragraph("Lokasi: $it", sizeHalfPt = 18)) }
            append(paragraph("Template: ${recording.templateName}  |  Disusun oleh agent AI: ${notulensi.provider.label}", sizeHalfPt = 18))
            append(heading("Ringkasan"))
            append(paragraph(notulensi.summary))
            notulensi.sections.forEach { section ->
                val lines = sectionLines(section.content)
                if (lines.isEmpty()) return@forEach
                append(heading(section.title))
                lines.forEach { append(paragraph(it)) }
            }
            if (images.isNotEmpty()) {
                append(heading("Dokumentasi"))
                images.forEachIndexed { n, (i, photo, img) ->
                    append(imageParagraph("rIdImg${n + 1}", n + 1, img))
                    append(paragraph(photoLabel(i, photo), sizeHalfPt = 18))
                }
            }
        }

        val documentXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"" +
            " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"" +
            " xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\">" +
            "<w:body>$body<w:sectPr>" +
            "<w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
            "<w:pgMar w:top=\"1417\" w:right=\"1417\" w:bottom=\"1417\" w:left=\"1417\"/>" +
            "</w:sectPr></w:body></w:document>"

        val contentTypesXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "</Types>"

        val rootRelsXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
            "</Relationships>"

        val docRelsXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            images.mapIndexed { n, (_, _, img) ->
                "<Relationship Id=\"rIdImg${n + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/${img.name}\"/>"
            }.joinToString("") +
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
            if (images.isNotEmpty()) {
                entry("word/_rels/document.xml.rels", docRelsXml)
                images.forEach { (_, _, img) ->
                    zip.putNextEntry(ZipEntry("word/media/${img.name}"))
                    img.file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }
}
