package com.inteldes.app.data.export

/**
 * Lays text and pictures out on A4 pages. Pure arithmetic (the width of a piece of text comes in as a function),
 * so it can be tested without Android; [PdfExporter] draws the result.
 */
internal object PdfLayout {
    const val PAGE_W = 595
    const val PAGE_H = 842
    const val MARGIN = 56f
    private const val FOOTER = 22f
    private const val MAX_PICTURE_H = 240f

    /** Width in points of [text] at [size], bold or not. */
    fun interface Measure { fun width(text: String, size: Float, bold: Boolean): Float }

    sealed interface Block {
        /** [keepWithNext]: a heading is not left alone at the bottom of a page. */
        data class Text(
            val text: String, val size: Float, val bold: Boolean = false, val center: Boolean = false,
            val before: Float = 0f, val after: Float = 6f, val keepWithNext: Boolean = false,
        ) : Block

        data class Image(val key: String, val width: Int, val height: Int) : Block
    }

    data class Line(val text: String, val size: Float, val bold: Boolean, val center: Boolean, val baseline: Float)
    data class Picture(val key: String, val x: Float, val y: Float, val w: Float, val h: Float)
    class Page { val lines = mutableListOf<Line>(); val pictures = mutableListOf<Picture>() }

    private const val LINE_FACTOR = 1.35f

    /** Greedy word wrap; a single word wider than the line is broken by characters. Blank text gives one empty line. */
    fun wrap(text: String, maxWidth: Float, size: Float, bold: Boolean, measure: Measure): List<String> {
        if (text.isBlank()) return listOf("")
        val out = mutableListOf<String>()
        var line = ""
        for (word in text.trim().split(Regex("\\s+"))) {
            var w = word
            while (measure.width(w, size, bold) > maxWidth && w.length > 1) {          // too long for a line of its own
                if (line.isNotEmpty()) { out += line; line = "" }
                var cut = w.length
                while (cut > 1 && measure.width(w.substring(0, cut), size, bold) > maxWidth) cut--
                out += w.substring(0, cut)
                w = w.substring(cut)
            }
            val candidate = if (line.isEmpty()) w else "$line $w"
            if (measure.width(candidate, size, bold) <= maxWidth) line = candidate
            else { out += line; line = w }
        }
        if (line.isNotEmpty()) out += line
        return out
    }

    fun layout(blocks: List<Block>, measure: Measure): List<Page> {
        val contentW = PAGE_W - 2 * MARGIN
        val bottom = PAGE_H - MARGIN - FOOTER
        val pages = mutableListOf(Page())
        var y = MARGIN
        fun newPage() { pages += Page(); y = MARGIN }

        for (block in blocks) {
            when (block) {
                is Block.Text -> {
                    val lh = block.size * LINE_FACTOR
                    val lines = block.text.split("\n").flatMap { wrap(it, contentW, block.size, block.bold, measure) }
                    val before = if (y > MARGIN) block.before else 0f
                    val needed = before + lh * (if (block.keepWithNext) 3 else 1)
                    if (y + needed > bottom) newPage()
                    if (y > MARGIN) y += block.before
                    for (l in lines) {
                        if (y + lh > bottom) newPage()
                        pages.last().lines += Line(l, block.size, block.bold, block.center, y + block.size)
                        y += lh
                    }
                    y += block.after
                }
                is Block.Image -> {
                    if (block.width <= 0 || block.height <= 0) continue
                    val scale = minOf(contentW / block.width, MAX_PICTURE_H / block.height, 1f)
                    val w = block.width * scale
                    val h = block.height * scale
                    if (y + h > bottom) newPage()
                    pages.last().pictures += Picture(block.key, MARGIN, y, w, h)
                    y += h + 6f
                }
            }
        }
        return pages
    }
}
