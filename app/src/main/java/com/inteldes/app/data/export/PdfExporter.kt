package com.inteldes.app.data.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.inteldes.app.data.model.Notulensi
import com.inteldes.app.data.model.Photo
import com.inteldes.app.data.model.Recording
import com.inteldes.app.ui.util.formatMmSs
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a notulensi as a PDF on the phone (Android's own PdfDocument, no library): the same content as the Word
 * export — title, details, summary, the sections of the template, and the documentation photos — on A4 pages
 * with page numbers. Works offline.
 */
internal object PdfExporter {
    private val dateFormat = SimpleDateFormat("dd MMMM yyyy, HH:mm", Locale("id", "ID"))
    private const val MAX_PHOTO_PX = 1400

    private val regular = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SANS_SERIF }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }

    private fun paint(isBold: Boolean, size: Float, color: Int = 0xFF111111.toInt()) =
        (if (isBold) bold else regular).apply { textSize = size; this.color = color }

    private val measure = PdfLayout.Measure { text, size, isBold -> paint(isBold, size).measureText(text) }

    /** The blocks of the document; photos that cannot be read are left out. */
    fun blocks(recording: Recording, notulensi: Notulensi, photos: List<Photo>, photoSize: (Photo) -> Pair<Int, Int>?): List<PdfLayout.Block> {
        val out = mutableListOf<PdfLayout.Block>()
        out += PdfLayout.Block.Text(recording.title, 18f, bold = true, center = true, after = 8f)
        out += PdfLayout.Block.Text("Kategori: ${recording.kind}  |  Tanggal: ${dateFormat.format(Date(recording.createdAt))}", 9f, center = true, after = 2f)
        recording.locationName?.let { out += PdfLayout.Block.Text("Lokasi: $it", 9f, center = true, after = 2f) }
        out += PdfLayout.Block.Text("Template: ${recording.templateName}  |  Disusun oleh agent AI: ${notulensi.provider.label}", 9f, center = true, after = 10f)
        out += PdfLayout.Block.Text("Ringkasan", 14f, bold = true, before = 12f, after = 6f, keepWithNext = true)
        out += PdfLayout.Block.Text(notulensi.summary, 11f)
        notulensi.sections.forEach { section ->
            val lines = NotulensiExporter.sectionLines(section.content)
            if (lines.isEmpty()) return@forEach
            out += PdfLayout.Block.Text(section.title, 14f, bold = true, before = 12f, after = 6f, keepWithNext = true)
            lines.forEach { out += PdfLayout.Block.Text(it, 11f) }
        }
        val usable = photos.mapIndexedNotNull { i, p -> photoSize(p)?.let { Triple(i, p, it) } }
        if (usable.isNotEmpty()) {
            out += PdfLayout.Block.Text("Dokumentasi", 14f, bold = true, before = 12f, after = 6f, keepWithNext = true)
            usable.forEach { (i, p, size) ->
                out += PdfLayout.Block.Image(p.filePath, size.first, size.second)
                val at = p.takenAtSec?.let { " (menit ${formatMmSs(it)})" }.orEmpty()
                val cap = if (p.caption.isNotBlank()) ": ${p.caption}" else ""
                out += PdfLayout.Block.Text("Foto ${i + 1}$at$cap", 9f, after = 10f)
            }
        }
        return out
    }

    fun write(file: File, recording: Recording, notulensi: Notulensi, photos: List<Photo>) {
        val blocks = blocks(recording, notulensi, photos) { p ->
            val f = File(p.filePath).takeIf { it.exists() } ?: return@blocks null
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, o)
            if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
        }
        val pages = PdfLayout.layout(blocks, measure)
        val doc = PdfDocument()
        try {
            pages.forEachIndexed { index, layout ->
                val page = doc.startPage(PdfDocument.PageInfo.Builder(PdfLayout.PAGE_W, PdfLayout.PAGE_H, index + 1).create())
                val canvas = page.canvas
                layout.lines.forEach { l ->
                    val p = paint(l.bold, l.size)
                    val x = if (l.center) (PdfLayout.PAGE_W - p.measureText(l.text)) / 2f else PdfLayout.MARGIN
                    canvas.drawText(l.text, x, l.baseline, p)
                }
                layout.pictures.forEach { pic ->
                    loadBitmap(pic.key)?.let { bmp ->
                        canvas.drawBitmap(bmp, null, RectF(pic.x, pic.y, pic.x + pic.w, pic.y + pic.h), regular)
                        bmp.recycle()
                    }
                }
                val footer = paint(false, 8f, 0xFF666666.toInt())
                val label = "${recording.title.take(50)}  ·  Halaman ${index + 1} dari ${pages.size}"
                canvas.drawText(label, (PdfLayout.PAGE_W - footer.measureText(label)) / 2f, PdfLayout.PAGE_H - PdfLayout.MARGIN + 6f, footer)
                doc.finishPage(page)
            }
            FileOutputStream(file).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    /** Decodes a photo at a size that fits the page (a full-size camera photo would waste memory and file size). */
    private fun loadBitmap(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_PHOTO_PX) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
