package com.aktcl.aron.core.printing

import com.aktcl.aron.core.printing.doc.DaySummaryLine
import com.aktcl.aron.core.printing.doc.DaySummaryPrint
import com.aktcl.aron.core.printing.doc.DueReceiptPrint
import com.aktcl.aron.core.printing.doc.MemoPrint
import com.aktcl.aron.core.printing.doc.MemoPrintDiscount
import com.aktcl.aron.core.printing.doc.MemoPrintLine
import com.aktcl.aron.core.printing.doc.StockSlipLine
import com.aktcl.aron.core.printing.doc.StockSlipPrint
import com.aktcl.aron.core.printing.doc.VoidSlipPrint

/** The print samples behind the goldens; shared by the JVM tests and the on-phone test. */
object PrintSamples {
    /** 2026-10-07 10:32 Dhaka. */
    const val T = 1_791_347_520_000L

    /**
     * The seeded sale: the on-screen memo of docs/ui-reference/sr/memo.md (FB 3 dozen at 28.00 = 84.00, SL Match
     * 3 / 0.00 on the discount tab) plus cigarettes in sticks at 7.935, a lighter, a Bangla outlet name and a
     * DRP deduction, so conjuncts, both digit styles, 3-decimal line values and the totals block all print.
     */
    val seededSale = MemoPrint(
        kind = "credit_memo",
        memoNo = "sr001-261007-0042",
        committedAtEpochMs = T,
        outlet = "রহিম স্টোর্স (O-10234)",
        sr = "মোঃ করিম উদ্দিন",
        route = "মোহাম্মদপুর-২ (R-17)",
        lines = listOf(
            MemoPrintLine("FB", 3, 84_000),
            MemoPrintLine("Sheikh 20s", 12, 95_220),
            MemoPrintLine("Navy Filter", 1, 7_935),
            MemoPrintLine("SL Lighter", 35, 437_500),
        ),
        discounts = listOf(MemoPrintDiscount("SL Match", 3, 0), MemoPrintDiscount("Sheikh 20s", 12, 2_400)),
        grossMtk = 624_655,
        offerDiscountMtk = 0,
        drpDiscountMtk = 2_400,
        qcDeductionMtk = 10_000,
        roundAdjMtk = 5,
        netMtk = 612_260,
        paidMtk = 500_000,
        dueMtk = 112_260,
        isCredit = true,
    )

    /** 40 item lines: the N-019 long-memo print. */
    val fortyLines = seededSale.copy(
        lines = (1..40).map { MemoPrintLine(String.format(java.util.Locale.ROOT, "SKU-%02d স্টার ফিল্টার", it), it.toLong(), it * 7_935L) },
        discounts = emptyList(),
        grossMtk = (1..40).sumOf { it * 7_935L },
        drpDiscountMtk = 0, qcDeductionMtk = 0,
        roundAdjMtk = 0, netMtk = 0, paidMtk = 0, dueMtk = 0, isCredit = false, kind = "cash_memo",
    ).let { m ->
        val net = com.aktcl.aron.rules.Money.roundToPaisaHalfUp(m.grossMtk)
        m.copy(netMtk = net, roundAdjMtk = net - m.grossMtk, paidMtk = net)
    }

    val stockSlip = StockSlipPrint(
        printedAtEpochMs = T, sr = "মোঃ করিম উদ্দিন", route = "মোহাম্মদপুর-২", distributor = "এ কে ট্রেডার্স",
        lines = listOf(
            StockSlipLine("সিগারেট", "Sheikh 20s", 2000), StockSlipLine("সিগারেট", "Navy Filter", 1000),
            StockSlipLine("ম্যাচ", "FB", 50), StockSlipLine("লাইটার", "SL Lighter", 100),
        ),
    )

    val daySummary = DaySummaryPrint(
        printedAtEpochMs = T, sr = "মোঃ করিম উদ্দিন", route = "মোহাম্মদপুর-২",
        lines = listOf(DaySummaryLine("FB", 4, 12, 336_000), DaySummaryLine("Sheikh 20s", 9, 640, 5_078_400)),
        grossMtk = 5_414_400, discountMtk = 2_400, qcMtk = 10_000, netMtk = 5_402_000, dueMtk = 112_260, memoCount = 11,
    )

    val voidSlip = VoidSlipPrint("sr001-261007-0042", T, "রহিম স্টোর্স (O-10234)", "মোঃ করিম উদ্দিন", "ভুল পরিমাণ", 612_260)

    val dueReceipt = DueReceiptPrint("sr001-261005-0007", T, "রহিম স্টোর্স (O-10234)", "মোঃ করিম উদ্দিন", 112_260, 100_000, 12_260)
}
