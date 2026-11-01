package com.inteldes.app.data.export

import com.inteldes.app.data.export.PdfLayout.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfLayoutTest {
    // every character is half the font size wide, bold the same: easy to reason about
    private val measure = PdfLayout.Measure { text, size, _ -> text.length * size * 0.5f }
    private val contentW = PdfLayout.PAGE_W - 2 * PdfLayout.MARGIN

    @Test
    fun textIsWrappedWithinTheLineWidthAndLongWordsAreBroken() {
        val lines = PdfLayout.wrap("kata ".repeat(200).trim(), contentW, 11f, false, measure)
        assertTrue(lines.size > 5)
        assertTrue(lines.all { measure.width(it, 11f, false) <= contentW })
        val word = PdfLayout.wrap("x".repeat(500), contentW, 11f, false, measure)
        assertTrue(word.size > 1 && word.all { measure.width(it, 11f, false) <= contentW })
        assertEquals(listOf(""), PdfLayout.wrap("   ", contentW, 11f, false, measure))
    }

    @Test
    fun longTextRunsOntoMorePagesAndNothingLeavesThePage() {
        val pages = PdfLayout.layout(List(60) { Block.Text("Paragraf $it " + "isi ".repeat(60), 11f) }, measure)
        assertTrue(pages.size > 2)
        val bottom = PdfLayout.PAGE_H - PdfLayout.MARGIN
        pages.forEach { p -> assertTrue(p.lines.all { it.baseline <= bottom }) }
        assertEquals(60, pages.flatMap { it.lines }.map { it.text }.count { it.startsWith("Paragraf") })
    }

    @Test
    fun aHeadingIsNotLeftAloneAtTheBottomOfAPage() {
        // fill a page up to just a couple of lines short of the bottom, then a heading and its text
        val filler = Block.Text("a", 11f, after = 0f)
        val room = ((PdfLayout.PAGE_H - 2 * PdfLayout.MARGIN - 22f) / (11f * 1.35f)).toInt()
        val blocks = List(room - 1) { filler } + Block.Text("Judul bagian", 14f, bold = true, keepWithNext = true) + Block.Text("isi bagian", 11f)
        val pages = PdfLayout.layout(blocks, measure)
        assertEquals(2, pages.size)
        assertTrue(pages[1].lines.first().text == "Judul bagian")
    }

    @Test
    fun picturesAreScaledToFitAndMoveToTheNextPageWhenTheyDoNot() {
        val pages = PdfLayout.layout(listOf(Block.Image("a", 4000, 3000), Block.Image("b", 100, 100)), measure)
        val a = pages[0].pictures.first()
        assertTrue(a.w <= contentW + 0.01f && a.h <= 240.01f)
        assertEquals(4000f / 3000f, a.w / a.h, 0.01f)
        assertEquals(100f, pages[0].pictures.last().w, 0.01f)                     // small ones are not enlarged
        val many = PdfLayout.layout(List(8) { Block.Image("p$it", 800, 600) }, measure)
        assertTrue(many.size > 1)
        many.forEach { p -> assertTrue(p.pictures.all { it.y + it.h <= PdfLayout.PAGE_H - PdfLayout.MARGIN }) }
    }
}
