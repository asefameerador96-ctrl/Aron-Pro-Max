package com.aktcl.aron.core.printing.text

/** One closed contour of a glyph in font units; [onCurve] marks TrueType on-curve points. */
internal class Contour(val x: IntArray, val y: IntArray, val onCurve: BooleanArray)

/**
 * A TrueType font (glyf outlines) read from its bytes (N-018): cmap, metrics, outlines and the OpenType layout
 * tables. Pure Kotlin so the same code shapes and rasterises on the JVM and on Android.
 */
class OpenTypeFont(bytes: ByteArray) {
    private val d = FontData(bytes)
    private val tables = HashMap<String, Int>()

    val unitsPerEm: Int
    val ascender: Int
    val descender: Int
    val numGlyphs: Int
    private val numHMetrics: Int
    private val hmtx: Int
    private val locaLong: Boolean
    private val loca: Int
    private val glyf: Int
    private val cmap: Map<Int, Int>
    internal val gdef: Gdef
    internal val gsub: LayoutTable
    internal val gpos: LayoutTable
    private val outlineCache = HashMap<Int, List<Contour>>()

    init {
        val n = d.u16(4)
        for (i in 0 until n) {
            val rec = 12 + 16 * i
            tables[d.tag(rec)] = d.u32(rec + 8).toInt()
        }
        val head = table("head")
        unitsPerEm = d.u16(head + 18)
        locaLong = d.s16(head + 50) == 1
        val hhea = table("hhea")
        ascender = d.s16(hhea + 4)
        descender = d.s16(hhea + 6)
        numHMetrics = d.u16(hhea + 34)
        numGlyphs = d.u16(table("maxp") + 4)
        hmtx = table("hmtx")
        loca = table("loca")
        glyf = table("glyf")
        cmap = parseCmap(table("cmap"))
        gdef = tables["GDEF"]?.let { Gdef.parse(d, it) } ?: Gdef.EMPTY
        gsub = tables["GSUB"]?.let { LayoutTable.parse(d, it, gpos = false) } ?: LayoutTable.EMPTY
        gpos = tables["GPOS"]?.let { LayoutTable.parse(d, it, gpos = true) } ?: LayoutTable.EMPTY
    }

    private fun table(tag: String): Int = tables[tag] ?: throw MalformedFontException("missing table $tag")

    /** Nominal glyph of [codePoint], 0 (.notdef) when the font has none. */
    fun glyphOf(codePoint: Int): Int = cmap[codePoint] ?: 0

    fun hasGlyph(codePoint: Int): Boolean = cmap.containsKey(codePoint)

    fun advance(glyph: Int): Int {
        val i = if (glyph < numHMetrics) glyph else numHMetrics - 1
        return d.u16(hmtx + 4 * i)
    }

    private fun parseCmap(at: Int): Map<Int, Int> {
        var best = -1
        var bestScore = -1
        for (i in 0 until d.u16(at + 2)) {
            val rec = at + 4 + 8 * i
            val platform = d.u16(rec)
            val encoding = d.u16(rec + 2)
            val sub = at + d.u32(rec + 4).toInt()
            val format = d.u16(sub)
            val score = when {
                platform == 3 && encoding == 10 && format == 12 -> 4
                platform == 0 && format == 12 -> 3
                platform == 3 && encoding == 1 && format == 4 -> 2
                platform == 0 && format == 4 -> 1
                else -> -1
            }
            if (score > bestScore) { bestScore = score; best = sub }
        }
        if (best < 0) throw MalformedFontException("no usable cmap")
        val m = HashMap<Int, Int>()
        if (d.u16(best) == 12) {
            val groups = d.u32(best + 12).toInt()
            for (g in 0 until groups) {
                val p = best + 16 + 12 * g
                val start = d.u32(p).toInt()
                val end = d.u32(p + 4).toInt()
                val gid = d.u32(p + 8).toInt()
                for (c in start..end) m[c] = gid + (c - start)
            }
        } else {
            val segX2 = d.u16(best + 6)
            val ends = best + 14
            val starts = ends + segX2 + 2
            val deltas = starts + segX2
            val ranges = deltas + segX2
            for (s in 0 until segX2 / 2) {
                val end = d.u16(ends + 2 * s)
                val start = d.u16(starts + 2 * s)
                val delta = d.s16(deltas + 2 * s)
                val rangeOffset = d.u16(ranges + 2 * s)
                for (c in start..end) {
                    if (c == 0xFFFF) continue
                    val g = if (rangeOffset == 0) {
                        (c + delta) and 0xFFFF
                    } else {
                        val p = ranges + 2 * s + rangeOffset + 2 * (c - start)
                        val raw = d.u16(p)
                        if (raw == 0) 0 else (raw + delta) and 0xFFFF
                    }
                    if (g != 0) m[c] = g
                }
            }
        }
        return m
    }

    /** Outline of [glyph] in font units (y up); composites are flattened. */
    internal fun outline(glyph: Int): List<Contour> = outlineCache.getOrPut(glyph) { readOutline(glyph, 0) }

    private fun glyphRange(glyph: Int): IntRange? {
        if (glyph < 0 || glyph >= numGlyphs) return null
        val start: Int
        val end: Int
        if (locaLong) {
            start = d.u32(loca + 4 * glyph).toInt(); end = d.u32(loca + 4 * glyph + 4).toInt()
        } else {
            start = 2 * d.u16(loca + 2 * glyph); end = 2 * d.u16(loca + 2 * glyph + 2)
        }
        return if (end <= start) null else start until end
    }

    private fun readOutline(glyph: Int, depth: Int): List<Contour> {
        if (depth > 8) throw MalformedFontException("composite glyph nesting too deep at $glyph")
        val range = glyphRange(glyph) ?: return emptyList()
        val g = glyf + range.first
        val nContours = d.s16(g)
        return if (nContours >= 0) readSimple(g, nContours) else readComposite(g, depth)
    }

    private fun readSimple(g: Int, nContours: Int): List<Contour> {
        if (nContours == 0) return emptyList()
        val ends = IntArray(nContours) { d.u16(g + 10 + 2 * it) }
        val nPoints = ends.last() + 1
        val instrLen = d.u16(g + 10 + 2 * nContours)
        var p = g + 12 + 2 * nContours + instrLen
        val flags = IntArray(nPoints)
        var i = 0
        while (i < nPoints) {
            val f = d.u8(p++)
            flags[i++] = f
            if (f and 0x08 != 0) {
                var rep = d.u8(p++)
                while (rep-- > 0 && i < nPoints) flags[i++] = f
            }
        }
        val xs = IntArray(nPoints)
        var v = 0
        for (k in 0 until nPoints) {
            val f = flags[k]
            if (f and 0x02 != 0) {
                val dx = d.u8(p++); v += if (f and 0x10 != 0) dx else -dx
            } else if (f and 0x10 == 0) {
                v += d.s16(p); p += 2
            }
            xs[k] = v
        }
        val ys = IntArray(nPoints)
        v = 0
        for (k in 0 until nPoints) {
            val f = flags[k]
            if (f and 0x04 != 0) {
                val dy = d.u8(p++); v += if (f and 0x20 != 0) dy else -dy
            } else if (f and 0x20 == 0) {
                v += d.s16(p); p += 2
            }
            ys[k] = v
        }
        val out = ArrayList<Contour>(nContours)
        var start = 0
        for (c in 0 until nContours) {
            val end = ends[c]
            if (end >= start) {
                out.add(Contour(xs.copyOfRange(start, end + 1), ys.copyOfRange(start, end + 1),
                    BooleanArray(end - start + 1) { flags[start + it] and 1 != 0 }))
            }
            start = end + 1
        }
        return out
    }

    private fun readComposite(g: Int, depth: Int): List<Contour> {
        val out = ArrayList<Contour>()
        var p = g + 10
        while (true) {
            val flags = d.u16(p)
            val child = d.u16(p + 2)
            p += 4
            val dx: Int
            val dy: Int
            if (flags and 0x01 != 0) {
                dx = d.s16(p); dy = d.s16(p + 2); p += 4
            } else {
                dx = d.u8(p).toByte().toInt(); dy = d.u8(p + 1).toByte().toInt(); p += 2
            }
            // F2Dot14 matrix, applied in integer arithmetic so the result never depends on the platform.
            var a = 16384; var b = 0; var c = 0; var e = 16384
            when {
                flags and 0x08 != 0 -> { a = d.s16(p); e = a; p += 2 }
                flags and 0x40 != 0 -> { a = d.s16(p); e = d.s16(p + 2); p += 4 }
                flags and 0x80 != 0 -> { a = d.s16(p); b = d.s16(p + 2); c = d.s16(p + 4); e = d.s16(p + 6); p += 8 }
            }
            val xy = flags and 0x02 != 0
            for (ct in readOutline(child, depth + 1)) {
                val n = ct.x.size
                val nx = IntArray(n)
                val ny = IntArray(n)
                for (k in 0 until n) {
                    nx[k] = roundDiv(a.toLong() * ct.x[k] + c.toLong() * ct.y[k], 16384) + if (xy) dx else 0
                    ny[k] = roundDiv(b.toLong() * ct.x[k] + e.toLong() * ct.y[k], 16384) + if (xy) dy else 0
                }
                out.add(Contour(nx, ny, ct.onCurve))
            }
            if (flags and 0x20 == 0) break
        }
        return out
    }

    private fun roundDiv(v: Long, by: Int): Int = Math.floorDiv(v + by / 2, by.toLong()).toInt()
}
