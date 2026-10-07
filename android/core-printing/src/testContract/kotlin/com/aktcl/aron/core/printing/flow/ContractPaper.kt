package com.aktcl.aron.core.printing.flow

import com.aktcl.aron.core.printing.PaperRenderer
import com.aktcl.aron.core.printing.PrintLabels
import com.aktcl.aron.core.printing.TemplateSet
import com.aktcl.aron.core.printing.doc.MemoPrint
import com.aktcl.aron.core.printing.doc.MemoPrintLine
import com.aktcl.aron.core.printing.doc.StockSlipLine
import com.aktcl.aron.core.printing.doc.StockSlipPrint
import com.aktcl.aron.core.printing.raster.MonoBitmap
import com.aktcl.aron.core.printing.render.PrintFonts
import java.io.File

/**
 * Paper for the flow scenarios of [PrintLedgerContract]: the production renderer with the bundled fonts and the
 * Bangla `print_*` labels, located through sibling-module paths so the same code runs from core-printing and from
 * core-database. Only main classes are used here (the contract compiles into both modules' unit tests).
 */
internal object ContractPaper {
    private fun sibling(path: String): File =
        listOf(File("../$path"), File("android/$path")).firstOrNull { it.isFile } ?: error("$path not found from ${File(".").absolutePath}")

    private val labels: PrintLabels by lazy {
        val re = Regex("""<string name="([a-z0-9_]+)">(.*?)</string>""")
        val map = re.findAll(sibling("core-printing/src/main/res/values-bn/strings.xml").readText()).associate { it.groupValues[1] to it.groupValues[2] }
        // A label the scenarios depend on that the parse missed would quietly change the paper: fail loudly instead.
        for (k in listOf("print_reprint", "print_supersedes")) check(!map[k].isNullOrBlank()) { "label $k missing" }
        PrintLabels { map[it] }
    }

    private val fonts: PrintFonts by lazy {
        PrintFonts(
            sibling("core-ui/src/main/res/font/noto_sans_bengali_regular.ttf").readBytes(),
            sibling("core-ui/src/main/res/font/noto_sans_bengali_bold.ttf").readBytes(),
        )
    }

    val renderer: PaperRenderer by lazy { PaperRenderer(fonts, TemplateSet(emptyList(), labels), labels) }

    private const val T = 1_791_174_960_000L // 2026-10-05T04:36Z = 10:36 Dhaka, the date in the memo numbers

    val memo = MemoPrint(
        kind = "cash_memo", memoNo = "sr334001-261005-0101", committedAtEpochMs = T,
        outlet = "রহিম স্টোর্স (O-10234)", sr = "মোঃ করিম উদ্দিন", route = "মোহাম্মদপুর-২ (R-17)",
        lines = listOf(MemoPrintLine("Sheikh 20s", 20, 158_700), MemoPrintLine("FB", 2, 56_000)),
        discounts = emptyList(), grossMtk = 214_700, offerDiscountMtk = 0, drpDiscountMtk = 0, qcDeductionMtk = 0,
        roundAdjMtk = 300, netMtk = 215_000, paidMtk = 215_000, dueMtk = 0, isCredit = false,
    )

    val edited = memo.copy(kind = "edited_memo", memoNo = "sr334001-261005-0102", supersedesMemoNo = "sr334001-261005-0101")

    val slip = StockSlipPrint(
        printedAtEpochMs = T, sr = "মোঃ করিম উদ্দিন", route = "মোহাম্মদপুর-২", distributor = "এ কে ট্রেডার্স",
        lines = listOf(StockSlipLine("সিগারেট", "Sheikh 20s", 2000), StockSlipLine("সিগারেট", "Navy Filter", 1000), StockSlipLine("ম্যাচ", "FB", 50)),
    )

    /** The rows a printer puts on paper for [b]: everything down to the last inked row. */
    fun rows(b: MonoBitmap): List<ByteArray> {
        var last = -1
        for (y in b.height - 1 downTo 0) if ((0 until b.bytesPerRow).any { b.data[y * b.bytesPerRow + it].toInt() != 0 }) { last = y; break }
        return List(last + 1) { y -> b.data.copyOfRange(y * b.bytesPerRow, (y + 1) * b.bytesPerRow) }
    }

    /** True when [paper] (every row the printer put out, in order) is exactly the [expected] papers one after another. */
    fun same(paper: List<ByteArray>, expected: List<MonoBitmap>): Boolean {
        val want = expected.flatMap(::rows)
        return paper.size == want.size && paper.indices.all { paper[it].contentEquals(want[it]) }
    }
}
