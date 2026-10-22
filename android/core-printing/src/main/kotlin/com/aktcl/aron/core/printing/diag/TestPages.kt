package com.aktcl.aron.core.printing.diag

import com.aktcl.aron.core.printing.escpos.EscPos
import com.aktcl.aron.core.printing.raster.MonoBitmap

/** The three ways the diagnostics screen talks to a printer (D-PRINT-02, docs/34 s4.1). */
enum class TestMode(val label: String) {
    /** `GS v 0` raster bands: what every memo uses. */
    RASTER("GS v 0 raster"), // i18n-ignore: printed test page / mode label: printer font is ASCII (D-PRINT-02)
    /** `ESC * 33` 24-dot bit image: older firmware without `GS v 0`. */
    BIT_IMAGE("ESC * 24-dot"),
    /** Plain ASCII in the printer's own font: no image command at all. */
    ASCII("ASCII self-test"), // i18n-ignore: printed test page / mode label: printer font is ASCII (D-PRINT-02)
}

/**
 * Test pages for the Printer diagnostics screen. The image pages carry their mode's name in a built-in 5x7 font (no
 * font file, no shaping), a frame, a solid bar and a ruler, so the paper alone shows which mode printed and whether
 * the width, the band joins and the dot density are right.
 */
object TestPages {
    const val WIDTH_DOTS = 384

    fun commands(mode: TestMode, bandRows: Int = 24): List<ByteArray> = when (mode) {
        TestMode.RASTER -> EscPos.job(pattern("ARON TEST GS V 0"), bandRows) // i18n-ignore: printed test page / mode label: printer font is ASCII (D-PRINT-02)
        TestMode.BIT_IMAGE -> EscPos.bitImageJob(pattern("ARON TEST ESC * 24")) // i18n-ignore: printed test page / mode label: printer font is ASCII (D-PRINT-02)
        TestMode.ASCII -> EscPos.textJob(
            listOf("ARON PRINTER TEST", "MODE: ASCII SELF-TEST", "0123456789", "ABCDEFGHIJKLMNOPQRSTUVWXYZ", "-".repeat(32), "32 COLUMNS FIT ON 58 MM PAPER"), // i18n-ignore: printed test page / mode label: printer font is ASCII (D-PRINT-02)
        )
    }

    /** The image test pattern: frame, title, a 16-dot black bar, a ruler with a tick every 8 dots and one every 48. */
    fun pattern(title: String, width: Int = WIDTH_DOTS): MonoBitmap {
        val b = MonoBitmap(width, 120)
        b.fillRect(0, 0, width, 2); b.fillRect(0, 118, width, 120); b.fillRect(0, 0, 2, 120); b.fillRect(width - 2, 0, width, 120)
        drawText(b, title, 8, 8, scale = 2)
        b.fillRect(8, 36, width - 8, 52)
        for (x in 0 until width step 8) b.fillRect(x, 60, x + 1, if (x % 48 == 0) 80 else 68)
        // a checkerboard of 4-dot squares: a printer that drops or shifts bits shows it at once
        for (y in 88 until 112) for (x in 8 until width - 8) if (((x - 8) / 4 + (y - 88) / 4) % 2 == 0) b.set(x, y)
        return b
    }

    fun drawText(b: MonoBitmap, text: String, x0: Int, y0: Int, scale: Int = 1) {
        var x = x0
        for (c in text.uppercase()) {
            val g = FONT[c] ?: FONT['?']!!
            for (row in 0 until 7) for (col in 0 until 5) {
                if (g[row][col] == '#') b.fillRect(x + col * scale, y0 + row * scale, x + (col + 1) * scale, y0 + (row + 1) * scale)
            }
            x += 6 * scale
        }
    }

    private val FONT: Map<Char, List<String>> = run {
        val src = """
            A .###. #...# #...# ##### #...# #...# #...#
            B ####. #...# #...# ####. #...# #...# ####.
            C .###. #...# #.... #.... #.... #...# .###.
            D ####. #...# #...# #...# #...# #...# ####.
            E ##### #.... #.... ####. #.... #.... #####
            F ##### #.... #.... ####. #.... #.... #....
            G .###. #...# #.... #.### #...# #...# .###.
            H #...# #...# #...# ##### #...# #...# #...#
            I .###. ..#.. ..#.. ..#.. ..#.. ..#.. .###.
            J ..### ...#. ...#. ...#. ...#. #..#. .##..
            K #...# #..#. #.#.. ##... #.#.. #..#. #...#
            L #.... #.... #.... #.... #.... #.... #####
            M #...# ##.## #.#.# #.#.# #...# #...# #...#
            N #...# ##..# #.#.# #..## #...# #...# #...#
            O .###. #...# #...# #...# #...# #...# .###.
            P ####. #...# #...# ####. #.... #.... #....
            Q .###. #...# #...# #...# #.#.# #..#. .##.#
            R ####. #...# #...# ####. #.#.. #..#. #...#
            S .#### #.... #.... .###. ....# ....# ####.
            T ##### ..#.. ..#.. ..#.. ..#.. ..#.. ..#..
            U #...# #...# #...# #...# #...# #...# .###.
            V #...# #...# #...# #...# #...# .#.#. ..#..
            W #...# #...# #...# #.#.# #.#.# #.#.# .#.#.
            X #...# #...# .#.#. ..#.. .#.#. #...# #...#
            Y #...# #...# .#.#. ..#.. ..#.. ..#.. ..#..
            Z ##### ....# ...#. ..#.. .#... #.... #####
            0 .###. #...# #..## #.#.# ##..# #...# .###.
            1 ..#.. .##.. ..#.. ..#.. ..#.. ..#.. .###.
            2 .###. #...# ....# ...#. ..#.. .#... #####
            3 ####. ....# ....# .###. ....# ....# ####.
            4 ...#. ..##. .#.#. #..#. ##### ...#. ...#.
            5 ##### #.... ####. ....# ....# #...# .###.
            6 .###. #.... #.... ####. #...# #...# .###.
            7 ##### ....# ...#. ..#.. .#... .#... .#...
            8 .###. #...# #...# .###. #...# #...# .###.
            9 .###. #...# #...# .#### ....# ....# .###.
            * ..... #.#.# .###. ##### .###. #.#.# .....
            - ..... ..... ..... ##### ..... ..... .....
            ? .###. #...# ....# ...#. ..#.. ..... ..#..
        """.trimIndent()
        val m = src.lines().associate { line -> val p = line.trim().split(' '); p[0][0] to p.drop(1) }
        m + (' ' to List(7) { "....." })
    }
}
