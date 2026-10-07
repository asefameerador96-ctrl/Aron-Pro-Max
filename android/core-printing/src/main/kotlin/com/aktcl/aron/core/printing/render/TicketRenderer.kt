package com.aktcl.aron.core.printing.render

import com.aktcl.aron.core.printing.raster.GlyphRasterizer
import com.aktcl.aron.core.printing.raster.MonoBitmap
import com.aktcl.aron.core.printing.template.PrintTemplate
import com.aktcl.aron.core.printing.template.PrintTemplate.Align
import com.aktcl.aron.core.printing.template.PrintTemplate.Block
import com.aktcl.aron.core.printing.text.Bengali
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
class TicketRenderer(
    private val fonts: PrintFonts,
    val widthDots: Int = 384,
    private val marginDots: Int = 4,
    private val maxHeightDots: Int = 16_000,
) {
    init {
        require(widthDots in 64..832 && widthDots % 8 == 0) { "width $widthDots" }
    }

    private val rasterizers = HashMap<Pair<Boolean, Int>, GlyphRasterizer>()

    private fun raster(bold: Boolean, size: Int) = rasterizers.getOrPut(bold to size) {
        GlyphRasterizer(if (bold) fonts.bold else fonts.regular, size)
    }

    private fun shaper(bold: Boolean) = if (bold) fonts.boldShaper else fonts.regularShaper

    /**
     * Renders [template] with [doc]; [label] resolves `{@key}`. Unknown fields print empty. A document longer
     * than [maxHeightDots] (about 2 m of paper) throws [PaperTooLongException] instead of printing for minutes.
     */
    fun render(template: PrintTemplate, doc: PrintDocument, label: (String) -> String): MonoBitmap {
        val strips = ArrayList<MonoBitmap>()
        emit(template.blocks, template, doc, label, null, strips)
        strips.add(MonoBitmap(widthDots, 24)) // tear-off margin
        val height = strips.sumOf { it.height }
        if (height > maxHeightDots) throw PaperTooLongException(height)
        return MonoBitmap.stack(widthDots, strips)
    }

    private fun fill(text: String, doc: PrintDocument, row: Map<String, String>?, label: (String) -> String): String =
        PrintTemplate.TOKEN.replace(text) { m ->
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
                val gap = size / 2
                val rightW = measure(right, b.bold, size)
                val leftAlone = if (left.isBlank()) 0 else measure(left, b.bold, size)
                if (rightW + gap + minOf(leftAlone, usable / 3) <= usable) {
                    // The value sits on the last line of the label, right-aligned; the label wraps beside it.
                    val leftW = usable - rightW - gap
                    val leftLines = if (left.isBlank()) emptyList() else wrap(left, b.bold, size, leftW)
                    val n = maxOf(1, leftLines.size)
                    for (i in 0 until n) {
                        val cells = ArrayList<Cell>()
                        if (i < leftLines.size) cells.add(Cell(leftLines[i], marginDots, leftW, Align.LEFT, b.bold))
                        if (i == n - 1) cells.add(Cell(right, marginDots, usable, Align.RIGHT, b.bold))
                        out.add(lineStrip(cells, size))
                    }
                } else {
                    // A value too wide to share a line goes below the label, right-aligned, wrapped if needed.
                    if (left.isNotBlank()) for (l in wrap(left, b.bold, size, usable)) out.add(lineStrip(listOf(Cell(l, marginDots, usable, Align.LEFT, b.bold)), size))
                    for (r in wrap(right, b.bold, size, usable)) out.add(lineStrip(listOf(Cell(r, marginDots, usable, Align.RIGHT, b.bold)), size))
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
        return (r.ascentPx + 1) to (r.descentPx + 1)
    }

    /**
     * One line of cells. The strip has the font's line height, but it is drawn with headroom and grows when a
     * stacked sign (reph with a vowel sign and chandrabindu) reaches beyond it, so no ink is ever cut.
     */
    private fun lineStrip(cells: List<Cell>, size: Int): MonoBitmap {
        val (asc, desc) = lineHeight(size)
        val room = size
        val tall = MonoBitmap(widthDots, room + asc + desc + room)
        val baseline = room + asc
        for (c in cells) {
            val run = shaper(c.bold).shape(c.text)
            val r = raster(c.bold, size)
            val w = r.widthPx(run)
            val x = when (c.align) {
                Align.LEFT -> c.x
                Align.RIGHT -> c.x + c.width - w
                Align.CENTER -> c.x + (c.width - w) / 2
            }
            r.draw(tall, run, x, baseline)
        }
        var top = room
        var bottom = room + asc + desc
        val bpr = tall.bytesPerRow
        fun inked(y: Int) = (0 until bpr).any { tall.data[y * bpr + it].toInt() != 0 }
        for (y in 0 until room) if (inked(y)) { top = y; break }
        for (y in tall.height - 1 downTo room + asc + desc) if (inked(y)) { bottom = y + 1; break }
        return tall.rows(top, bottom)
    }

    fun measure(text: String, bold: Boolean, size: Int): Int = raster(bold, size).widthPx(shaper(bold).shape(text))

    /**
     * Greedy word wrap. A word wider than the line is cut only between syllables (never after a virama or
     * before a sign) and between whole characters, found by binary search; a single syllable wider than the
     * line stays whole and may overhang rather than print broken.
     */
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
                while (measure(line, bold, size) > width) {
                    val cuts = breakPoints(line)
                    if (cuts.isEmpty()) break
                    // Largest cut whose prefix fits; at least the first cut.
                    var lo = 0
                    var hi = cuts.size - 1
                    var best = 0
                    while (lo <= hi) {
                        val mid = (lo + hi) ushr 1
                        if (measure(line.substring(0, cuts[mid]), bold, size) <= width) { best = mid; lo = mid + 1 } else hi = mid - 1
                    }
                    out.add(line.substring(0, cuts[best]))
                    line = line.substring(cuts[best])
                }
            }
            if (line.isNotEmpty() || para.isEmpty()) out.add(line)
        }
        return out
    }

    /** Char offsets (exclusive of 0 and the end) where [word] may be cut: syllable starts, never inside a pair. */
    private fun breakPoints(word: String): IntArray {
        val cps = word.codePoints().toArray()
        if (cps.size < 2) return IntArray(0)
        val starts = BooleanArray(cps.size)
        for ((s, _, _) in Bengali.syllables(IntArray(cps.size) { Bengali.category(cps[it]) })) starts[s] = true
        val out = ArrayList<Int>()
        var offset = 0
        for (i in cps.indices) {
            if (i > 0 && starts[i]) {
                val t = Character.getType(cps[i]).toByte()
                val mark = t == Character.NON_SPACING_MARK || t == Character.COMBINING_SPACING_MARK ||
                    t == Character.ENCLOSING_MARK || t == Character.FORMAT
                val prev = cps[i - 1]
                if (!mark && prev != 0x09CD && prev != 0x200D && prev != 0x200C) out.add(offset)
            }
            offset += Character.charCount(cps[i])
        }
        return out.toIntArray()
    }
}

/** A document that would print longer than the renderer's limit (a runaway template or data). */
class PaperTooLongException(val heightDots: Int) : IllegalStateException("paper of $heightDots dots exceeds the limit")
