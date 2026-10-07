package com.aktcl.aron.core.printing.render

import com.aktcl.aron.core.printing.raster.GlyphRasterizer
import com.aktcl.aron.core.printing.raster.MonoBitmap
import com.aktcl.aron.core.printing.template.PrintTemplate
import com.aktcl.aron.core.printing.template.PrintTemplate.Align
import com.aktcl.aron.core.printing.template.PrintTemplate.Block
import com.aktcl.aron.core.printing.text.OpenTypeFont
import com.aktcl.aron.core.printing.text.Shaper

/**
 * The data a template is filled with: already formatted texts (the document builders format money, quantities
 * and dates with the template's digit style), named row lists for tables, and flags for `if` blocks.
 * A row whose `_bold` value is `1` prints bold (category subtotals).
 */
class PrintDocument(
    val fields: Map<String, String>,
    val tables: Map<String, List<Map<String, String>>> = emptyMap(),
    val flags: Set<String> = emptySet(),
)

/** The two weights of the bundled Noto Sans Bengali (it also covers Latin, AC-09). */
class PrintFonts(regular: ByteArray, bold: ByteArray) {
    val regular = OpenTypeFont(regular)
    val bold = OpenTypeFont(bold)
    internal val regularShaper = Shaper(this.regular)
    internal val boldShaper = Shaper(this.bold)
}

/**
 * Renders a [PrintTemplate] filled with a [PrintDocument] into one 1-bit bitmap [widthDots] wide (384 for the
 * 58 mm MP-58N). Pure Kotlin from text to bits, so a golden image made on the JVM is the phone's output too.
 * Text that does not fit wraps at spaces (a single over-long word breaks between characters); nothing is cut.
 */
class TicketRenderer(private val fonts: PrintFonts, val widthDots: Int = 384, private val marginDots: Int = 4) {
    init {
        require(widthDots in 64..832 && widthDots % 8 == 0) { "width $widthDots" }
    }

    private val rasterizers = HashMap<Pair<Boolean, Int>, GlyphRasterizer>()

    private fun raster(bold: Boolean, size: Int) = rasterizers.getOrPut(bold to size) {
        GlyphRasterizer(if (bold) fonts.bold else fonts.regular, size)
    }

    private fun shaper(bold: Boolean) = if (bold) fonts.boldShaper else fonts.regularShaper

    /** Renders [template] with [doc]; [label] resolves `{@key}`. Unknown fields print empty. */
    fun render(template: PrintTemplate, doc: PrintDocument, label: (String) -> String): MonoBitmap {
        val strips = ArrayList<MonoBitmap>()
        emit(template.blocks, template, doc, label, null, strips)
        strips.add(MonoBitmap(widthDots, 24)) // tear-off margin
        return MonoBitmap.stack(widthDots, strips)
    }

    private val token = Regex("""\{(@?)([A-Za-z0-9_.]{1,60})}""")

    private fun fill(text: String, doc: PrintDocument, row: Map<String, String>?, label: (String) -> String): String =
        token.replace(text) { m ->
            val key = m.groupValues[2]
            if (m.groupValues[1] == "@") label(key) else row?.get(key) ?: doc.fields[key] ?: ""
        }

    private fun emit(
        blocks: List<Block>, t: PrintTemplate, doc: PrintDocument, label: (String) -> String,
        row: Map<String, String>?, out: MutableList<MonoBitmap>,
    ) {
        val usable = widthDots - 2 * marginDots
        for (b in blocks) when (b) {
            is Block.Text -> {
                val size = b.sizePx ?: t.sizePx
                val text = fill(b.value, doc, row, label)
                if (text.isNotBlank()) for (line in wrap(text, b.bold, size, usable)) out.add(lineStrip(listOf(Cell(line, marginDots, usable, b.align, b.bold)), size))
            }
            is Block.Pair -> {
                val size = b.sizePx ?: t.sizePx
                val left = fill(b.left, doc, row, label)
                val right = fill(b.right, doc, row, label)
                val rightW = measure(right, b.bold, size)
                val leftW = (usable - rightW - size / 2).coerceAtLeast(usable / 3)
                val leftLines = wrap(left, b.bold, size, leftW)
                val n = maxOf(1, leftLines.size)
                for (i in 0 until n) {
                    val cells = ArrayList<Cell>()
                    if (i < leftLines.size) cells.add(Cell(leftLines[i], marginDots, leftW, Align.LEFT, b.bold))
                    // The value sits on the last line of the label, right-aligned.
                    if (i == n - 1) cells.add(Cell(right, marginDots, usable, Align.RIGHT, b.bold))
                    out.add(lineStrip(cells, size))
                }
            }
            is Block.Rule -> {
                val strip = MonoBitmap(widthDots, 9)
                if (b.dashed) {
                    var x = marginDots
                    while (x < widthDots - marginDots) { strip.fillRect(x, 4, minOf(x + 6, widthDots - marginDots), 6); x += 10 }
                } else {
                    strip.fillRect(marginDots, 4, widthDots - marginDots, 6)
                }
                out.add(strip)
            }
            is Block.Space -> out.add(MonoBitmap(widthDots, b.dots))
            is Block.Table -> table(b, t, doc, label, out)
            is Block.If -> {
                val on = b.flag in doc.flags
                if (on != b.negate) emit(b.blocks, t, doc, label, row, out)
            }
        }
    }

    private fun table(b: Block.Table, t: PrintTemplate, doc: PrintDocument, label: (String) -> String, out: MutableList<MonoBitmap>) {
        val size = b.sizePx ?: t.sizePx
        val usable = widthDots - 2 * marginDots
        val gap = size / 4
        // Column x positions from percentages; the last column takes the rounding rest.
        val xs = IntArray(b.columns.size)
        val ws = IntArray(b.columns.size)
        var x = marginDots
        b.columns.forEachIndexed { i, c ->
            val w = if (i == b.columns.lastIndex) marginDots + usable - x else usable * c.widthPercent / 100
            xs[i] = x; ws[i] = w; x += w
        }
        fun rowStrips(texts: List<String>, bold: Boolean) {
            val wrapped = texts.mapIndexed { i, s -> wrap(s, bold, size, (ws[i] - gap).coerceAtLeast(size)) }
            val n = wrapped.maxOf { maxOf(1, it.size) }
            for (line in 0 until n) {
                val cells = b.columns.indices.mapNotNull { i ->
                    wrapped[i].getOrNull(line)?.let { Cell(it, xs[i], ws[i] - if (i == b.columns.lastIndex) 0 else gap, b.columns[i].align, bold) }
                }
                out.add(lineStrip(cells, size))
            }
        }
        val headers = b.columns.map { fill(it.header, doc, null, label) }
        if (headers.any { it.isNotBlank() }) rowStrips(headers, b.headerBold)
        for (r in doc.tables[b.rows].orEmpty()) {
            rowStrips(b.columns.map { fill(it.value, doc, r, label) }, r["_bold"] == "1")
        }
    }

    private class Cell(val text: String, val x: Int, val width: Int, val align: Align, val bold: Boolean)

    private fun lineHeight(size: Int): Pair<Int, Int> {
        val r = raster(false, size)
        // Bengali marks reach above the ascender and below the descender; a small pad keeps lines apart.
        return (r.ascentPx + 1) to (r.descentPx + 1)
    }

    private fun lineStrip(cells: List<Cell>, size: Int): MonoBitmap {
        val (asc, desc) = lineHeight(size)
        val strip = MonoBitmap(widthDots, asc + desc)
        for (c in cells) {
            val run = shaper(c.bold).shape(c.text)
            val r = raster(c.bold, size)
            val w = r.widthPx(run)
            val x = when (c.align) {
                Align.LEFT -> c.x
                Align.RIGHT -> c.x + c.width - w
                Align.CENTER -> c.x + (c.width - w) / 2
            }
            r.draw(strip, run, x, asc)
        }
        return strip
    }

    fun measure(text: String, bold: Boolean, size: Int): Int = raster(bold, size).widthPx(shaper(bold).shape(text))

    /** Greedy word wrap; a word wider than the line is split between characters (grapheme-safe enough for SKU codes). */
    internal fun wrap(text: String, bold: Boolean, size: Int, width: Int): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        for (para in text.split('\n')) {
            var line = ""
            for (word in para.split(' ').filter { it.isNotEmpty() }) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (measure(candidate, bold, size) <= width) { line = candidate; continue }
                if (line.isNotEmpty()) out.add(line)
                line = word
                while (measure(line, bold, size) > width && line.length > 1) {
                    var cut = line.length - 1
                    while (cut > 1 && (measure(line.substring(0, cut), bold, size) > width || !safeBreak(line, cut))) cut--
                    out.add(line.substring(0, cut))
                    line = line.substring(cut)
                }
            }
            if (line.isNotEmpty() || para.isEmpty()) out.add(line)
        }
        return out
    }

    /** No break before a combining sign, a virama or a joiner, or after a virama, so syllables stay whole. */
    private fun safeBreak(s: String, at: Int): Boolean {
        val next = s[at]
        val prev = s[at - 1]
        val t = Character.getType(next).toByte()
        if (t == Character.NON_SPACING_MARK || t == Character.COMBINING_SPACING_MARK || t == Character.FORMAT) return false
        if (prev == '্' || prev == '‍' || prev == '‌') return false
        if (Character.isSurrogate(next) && Character.isLowSurrogate(next)) return false
        return true
    }
}
