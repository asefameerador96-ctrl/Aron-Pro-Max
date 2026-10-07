package com.aktcl.aron.core.printing.render

import com.aktcl.aron.core.printing.Fixtures
import com.aktcl.aron.core.printing.raster.MonoBitmap
import com.aktcl.aron.core.printing.template.DigitStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * N-018 acceptance: the renderer's bitmaps for the seeded sale (both digit styles) and every other paper
 * document equal the stored goldens bit for bit. The same .pbm files are the assets of the on-phone test
 * (`GoldenOnDeviceTest`, Galaxy A06), so JVM and phone are held to one image.
 *
 * To regenerate after an intended layout change: `-Daron.updateGoldens=true`, then look at every changed file.
 */
class GoldenImageTest {
    private val dir = listOf(File("src/test/resources/goldens"), File("android/core-printing/src/test/resources/goldens")).first { it.parentFile.isDirectory }

    private fun golden(name: String, actual: MonoBitmap) {
        val f = File(dir, "$name.pbm")
        if (System.getProperty("aron.updateGoldens") == "true") {
            dir.mkdirs(); f.writeBytes(Pbm.encode(actual)); return
        }
        if (!f.isFile) fail("missing golden ${f.path}; run with -Daron.updateGoldens=true and review it")
        val want = Pbm.decode(f.readBytes())
        if (want != actual) {
            File("build/golden-diff").mkdirs()
            File("build/golden-diff/$name.actual.pbm").writeBytes(Pbm.encode(actual))
            var diff = 0
            for (y in 0 until maxOf(want.height, actual.height)) for (x in 0 until want.width) if (want[x, y] != actual[x, y]) diff++
            fail("$name differs from its golden: ${want.width}x${want.height} vs ${actual.width}x${actual.height}, $diff dots; actual in build/golden-diff")
        }
    }

    private val bn = Fixtures.renderer()

    @Test fun seededSaleLatinDigits() = golden("memo_seeded_latin", bn.memo(Fixtures.seededSale, DigitStyle.LATIN).bitmap)

    @Test fun seededSaleBengaliDigits() = golden("memo_seeded_bn_digits", bn.memo(Fixtures.seededSale, DigitStyle.BENGALI).bitmap)

    @Test fun seededSaleEnglishLabels() = golden("memo_seeded_en", Fixtures.renderer(Fixtures.enLabels).memo(Fixtures.seededSale).bitmap)

    @Test fun reprintWithDuplicateMarker() = golden("memo_reprint_2", bn.memo(Fixtures.seededSale.copy(reprintNo = 2)).bitmap)

    @Test fun editedMemoSupersedes() = golden(
        "memo_edited",
        bn.memo(Fixtures.seededSale.copy(kind = "edited_memo", memoNo = "sr001-261007-0043", supersedesMemoNo = "sr001-261007-0042")).bitmap,
    )

    @Test fun fortyLineMemo() = golden("memo_40_lines", bn.memo(Fixtures.fortyLines).bitmap)

    @Test fun stockSlip() = golden("stock_slip", bn.stockSlip(Fixtures.stockSlip).bitmap)

    @Test fun daySummary() = golden("day_summary", bn.daySummary(Fixtures.daySummary).bitmap)

    @Test fun voidSlip() = golden("void_slip", bn.voidSlip(Fixtures.voidSlip).bitmap)

    @Test fun dueReceipt() = golden("due_receipt", bn.dueReceipt(Fixtures.dueReceipt).bitmap)

    @Test fun renderingIsDeterministic() {
        val a = Fixtures.renderer().memo(Fixtures.seededSale).bitmap
        val b = Fixtures.renderer().memo(Fixtures.seededSale).bitmap
        assertEquals(a, b)
        assertEquals(384, a.width)
    }
}
