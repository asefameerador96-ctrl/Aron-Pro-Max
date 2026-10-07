package com.aktcl.aron.core.printing.raster

import com.aktcl.aron.core.printing.text.OpenTypeFont
import com.aktcl.aron.core.printing.text.ShapedRun

/**
 * Draws shaped glyphs into a [MonoBitmap] with integer arithmetic only (N-018).
 *
 * Outlines are scaled to 1/64 dot, quadratic curves flattened with an integer step count, and each dot is
 * sampled 4 x 4 with the non-zero winding rule; a dot is black when at least [threshold] of its 16 samples
 * are inside. No floating point is involved, so the JVM and ART give the same bits for the same input.
 */
class GlyphRasterizer(private val font: OpenTypeFont, val sizePx: Int, private val threshold: Int = 7) {
    init {
        require(sizePx in 4..200) { "size $sizePx" }
        require(threshold in 1..16) { "threshold $threshold" }
    }

    private val upem = font.unitsPerEm.toLong()

    /** Font units to 1/64 dot, rounded half up. */
    private fun scale64(v: Long): Int = Math.floorDiv(v * sizePx * 64 + upem / 2, upem).toInt()

    /** Font units to whole dots, rounded half up. */
    fun toPx(v: Int): Int = Math.floorDiv(v.toLong() * sizePx + upem / 2, upem).toInt()

    val ascentPx: Int get() = toPx(font.ascender)
    val descentPx: Int get() = toPx(-font.descender)

    fun widthPx(run: ShapedRun): Int = toPx(run.advance)

    private class Edges {
        var n = 0
        var x0 = IntArray(64); var y0 = IntArray(64); var x1 = IntArray(64); var y1 = IntArray(64)
        fun add(ax: Int, ay: Int, bx: Int, by: Int) {
            if (ay == by) return
            if (n == x0.size) {
                x0 = x0.copyOf(n * 2); y0 = y0.copyOf(n * 2); x1 = x1.copyOf(n * 2); y1 = y1.copyOf(n * 2)
            }
            x0[n] = ax; y0[n] = ay; x1[n] = bx; y1[n] = by; n++
        }
    }

    private fun isqrt(v: Long): Long {
        if (v <= 0) return 0
        var r = Math.sqrt(v.toDouble()).toLong() // exact for the small values used; corrected below
        while (r * r > v) r--
        while ((r + 1) * (r + 1) <= v) r++
        return r
    }

    private fun quad(e: Edges, ax: Int, ay: Int, cx: Int, cy: Int, bx: Int, by: Int) {
        val dd = Math.abs(ax - 2L * cx + bx) + Math.abs(ay - 2L * cy + by)
        val n = (isqrt(dd / 32) + 1).coerceIn(1, 24)
        var px = ax
        var py = ay
        val n2 = n * n
        for (k in 1..n) {
            val a = (n - k) * (n - k)
            val b = 2 * k * (n - k)
            val c = k * k
            val qx = Math.floorDiv(a * ax + b * cx + c * bx + n2 / 2, n2).toInt()
            val qy = Math.floorDiv(a * ay + b * cy + c * by + n2 / 2, n2).toInt()
            e.add(px, py, qx, qy)
            px = qx; py = qy
        }
    }

    /**
     * Draws [run] with its pen origin at dot ([x], [baseline]); glyphs are clipped to the bitmap.
     * Returns the advance in dots.
     */
    fun draw(target: MonoBitmap, run: ShapedRun, x: Int, baseline: Int): Int {
        for (i in run.glyphs.indices) drawGlyph(target, run.glyphs[i], run.x[i], run.y[i], x, baseline)
        return widthPx(run)
    }

    private fun drawGlyph(target: MonoBitmap, glyph: Int, gx: Int, gy: Int, originX: Int, baseline: Int) {
        val contours = font.outline(glyph)
        if (contours.isEmpty()) return
        val e = Edges()
        val ox = originX * 64
        val oy = baseline * 64
        for (ct in contours) {
            val n = ct.x.size
            if (n < 2) continue
            // Device coordinates in 1/64 dot, y down.
            val dx = IntArray(n) { ox + scale64((gx + ct.x[it]).toLong()) }
            val dy = IntArray(n) { oy - scale64((gy + ct.y[it]).toLong()) }
            // Start at an on-curve point (or the midpoint of the first two off-curve points).
            var startX: Int
            var startY: Int
            var first = (0 until n).firstOrNull { ct.onCurve[it] } ?: -1
            if (first < 0) {
                startX = (dx[0] + dx[1]) shr 1; startY = (dy[0] + dy[1]) shr 1
                first = 0
            } else {
                startX = dx[first]; startY = dy[first]
            }
            var curX = startX
            var curY = startY
            var ctrl = false
            var cx = 0
            var cy = 0
            val begin = if (ct.onCurve.any { it }) first + 1 else 1
            for (k in 0 until n) {
                val i = (begin + k) % n
                val px = dx[i]
                val py = dy[i]
                if (ct.onCurve[i]) {
                    if (ctrl) quad(e, curX, curY, cx, cy, px, py) else e.add(curX, curY, px, py)
                    curX = px; curY = py; ctrl = false
                } else {
                    if (ctrl) {
                        val mx = (cx + px) shr 1
                        val my = (cy + py) shr 1
                        quad(e, curX, curY, cx, cy, mx, my)
                        curX = mx; curY = my
                    }
                    cx = px; cy = py; ctrl = true
                }
            }
            if (ctrl) quad(e, curX, curY, cx, cy, startX, startY) else e.add(curX, curY, startX, startY)
        }
        fill(target, e)
    }

    private fun fill(target: MonoBitmap, e: Edges) {
        if (e.n == 0) return
        var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE; var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE
        for (i in 0 until e.n) {
            minY = minOf(minY, e.y0[i], e.y1[i]); maxY = maxOf(maxY, e.y0[i], e.y1[i])
            minX = minOf(minX, e.x0[i], e.x1[i]); maxX = maxOf(maxX, e.x0[i], e.x1[i])
        }
        val rowFrom = maxOf(0, Math.floorDiv(minY, 64))
        val rowTo = minOf(target.height - 1, Math.floorDiv(maxY, 64))
        val colFrom = maxOf(0, Math.floorDiv(minX, 64))
        val colTo = minOf(target.width - 1, Math.floorDiv(maxX, 64))
        if (rowFrom > rowTo || colFrom > colTo) return
        val cols = colTo - colFrom + 1
        val cover = IntArray(cols)
        val xs = IntArray(e.n)
        val dirs = IntArray(e.n)
        for (row in rowFrom..rowTo) {
            java.util.Arrays.fill(cover, 0)
            for (sy in 0 until 4) {
                val y = row * 64 + 8 + 16 * sy
                var m = 0
                for (i in 0 until e.n) {
                    val y0 = e.y0[i]; val y1 = e.y1[i]
                    val down = y1 > y0
                    val lo = if (down) y0 else y1
                    val hi = if (down) y1 else y0
                    if (y < lo || y >= hi) continue
                    val x = e.x0[i] + Math.floorDiv((y - y0).toLong() * (e.x1[i] - e.x0[i]), (y1 - y0).toLong()).toInt()
                    // insertion sort by x
                    var k = m
                    while (k > 0 && xs[k - 1] > x) { xs[k] = xs[k - 1]; dirs[k] = dirs[k - 1]; k-- }
                    xs[k] = x; dirs[k] = if (down) 1 else -1
                    m++
                }
                var winding = 0
                for (k in 0 until m - 1) {
                    winding += dirs[k]
                    if (winding == 0) continue
                    // Samples at x = col*64 + 8 + 16*sx inside [xs[k], xs[k+1]).
                    val a = xs[k]; val b = xs[k + 1]
                    val firstSample = ceilDiv(a - 8, 16)
                    val lastSample = ceilDiv(b - 8, 16) - 1
                    for (s in firstSample..lastSample) {
                        val col = Math.floorDiv(s, 4)
                        if (col < colFrom || col > colTo) continue
                        cover[col - colFrom]++
                    }
                }
            }
            for (c in 0 until cols) if (cover[c] >= threshold) target.set(colFrom + c, row)
        }
    }

    private fun ceilDiv(a: Int, b: Int): Int = -Math.floorDiv(-a, b)
}
