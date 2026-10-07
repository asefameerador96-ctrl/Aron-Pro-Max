package com.aktcl.aron.core.printing

import com.aktcl.aron.core.printing.render.PrintFonts
import java.io.File

/** Shared print fixtures: the seeded sale, labels from this module's strings.xml, the renderer. */
object Fixtures {
    const val T = PrintSamples.T

    private fun parse(path: String): Map<String, String> {
        val f = listOf(File(path), File("android/core-printing/$path")).first { it.isFile }
        val re = Regex("""<string name="([a-z0-9_]+)">(.*?)</string>""")
        return re.findAll(f.readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }

    val bn: Map<String, String> by lazy { parse("src/main/res/values-bn/strings.xml") }
    val en: Map<String, String> by lazy { parse("src/main/res/values/strings.xml") }
    val bnLabels = PrintLabels { bn[it] }
    val enLabels = PrintLabels { en[it] }

    val fonts: PrintFonts by lazy { PrintFonts(TestFonts.regularBytes, TestFonts.boldBytes) }

    fun renderer(labels: PrintLabels = bnLabels, bundle: List<BundleTemplate> = emptyList()) =
        PaperRenderer(fonts, TemplateSet(bundle, labels), labels)

    val seededSale get() = PrintSamples.seededSale
    val fortyLines get() = PrintSamples.fortyLines
    val stockSlip get() = PrintSamples.stockSlip
    val daySummary get() = PrintSamples.daySummary
    val voidSlip get() = PrintSamples.voidSlip
    val dueReceipt get() = PrintSamples.dueReceipt
}
