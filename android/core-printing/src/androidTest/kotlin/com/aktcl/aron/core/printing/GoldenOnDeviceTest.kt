package com.aktcl.aron.core.printing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.printing.raster.MonoBitmap
import com.aktcl.aron.core.printing.render.Pbm
import com.aktcl.aron.core.printing.render.PrintFonts
import com.aktcl.aron.core.printing.template.DigitStyle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * N-018 on the phone (Galaxy A06): the renderer, fed with the real string resources, produces the same bits as
 * the JVM goldens. The goldens and the fonts are this test APK's assets (build.gradle.kts).
 */
@RunWith(AndroidJUnit4::class)
class GoldenOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().context
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private fun asset(name: String) = ctx.assets.open(name).use { it.readBytes() }
    private val fonts = PrintFonts(asset("noto_sans_bengali_regular.ttf"), asset("noto_sans_bengali_bold.ttf"))

    private fun renderer(language: AppLanguage): PaperRenderer {
        val labels = AndroidPrintLabels(target, language)
        return PaperRenderer(fonts, TemplateSet(emptyList(), labels), labels)
    }

    private fun same(name: String, actual: MonoBitmap) = assertEquals(name, Pbm.decode(asset("$name.pbm")), actual)

    @Test fun memoGoldensMatch() {
        val bn = renderer(AppLanguage.BN)
        same("memo_seeded_latin", bn.memo(PrintSamples.seededSale, DigitStyle.LATIN).bitmap)
        same("memo_seeded_bn_digits", bn.memo(PrintSamples.seededSale, DigitStyle.BENGALI).bitmap)
        same("memo_seeded_en", renderer(AppLanguage.EN).memo(PrintSamples.seededSale).bitmap)
        same("memo_reprint_2", bn.memo(PrintSamples.seededSale.copy(reprintNo = 2)).bitmap)
        same("memo_40_lines", bn.memo(PrintSamples.fortyLines).bitmap)
    }

    @Test fun otherPaperGoldensMatch() {
        val bn = renderer(AppLanguage.BN)
        same("stock_slip", bn.stockSlip(PrintSamples.stockSlip).bitmap)
        same("day_summary", bn.daySummary(PrintSamples.daySummary).bitmap)
        same("void_slip", bn.voidSlip(PrintSamples.voidSlip).bitmap)
        same("due_receipt", bn.dueReceipt(PrintSamples.dueReceipt).bitmap)
    }
}
