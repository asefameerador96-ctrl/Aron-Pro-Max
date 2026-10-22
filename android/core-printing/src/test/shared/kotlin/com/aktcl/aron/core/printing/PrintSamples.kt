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
     * 3 / 0.00 on the discount tab) plus cigarettes counted in packets of 20 and 10 at 7.935 a stick, a lighter box of 25, a
     * Bangla outlet name and a DRP deduction, so conjuncts, both digit styles, 3-decimal line values, the unit words
     * (packet, box, dozen: docs/34 s4.2) and the totals block all print.
     */
    val seededSale = MemoPrint(
        kind = "credit_memo",
        memoNo = "sr001-261007-0042",
        committedAtEpochMs = T,
        outlet = "রহিম স্টোর্স (O-10234)",
        sr = "মোঃ করিম উদ্দিন",
        route = "মোহাম্মদপুর-২ (R-17)",
        lines = listOf(
            MemoPrintLine("FB", 3, 84_000, "ম্যাচ", qtyEntered = 3, unit = "dozen", baseUnit = "dozen"),
            MemoPrintLine("Sheikh 20s", 40, 317_400, "সিগারেট", qtyEntered = 2, unit = "packet", baseUnit = "stick"),
            MemoPrintLine("Navy Filter", 10, 79_350, "সিগারেট", qtyEntered = 1, unit = "packet", baseUnit = "stick"),
            MemoPrintLine("SL Lighter", 50, 625_000, "লাইটার", qtyEntered = 2, unit = "box", baseUnit = "piece"),
        ),
        discounts = listOf(MemoPrintDiscount("SL Match", 3, 0, qtyEntered = 3, unit = "dozen"), MemoPrintDiscount("Sheikh 20s", 20, 158_700, qtyEntered = 1, unit = "packet")),
        grossMtk = 1_105_750,
        offerDiscountMtk = 0,
        drpDiscountMtk = 158_700,
        qcDeductionMtk = 10_000,
        roundAdjMtk = 0,
        netMtk = 937_050,
        paidMtk = 800_000,
        dueMtk = 137_050,
        isCredit = true,
    )

    /** 40 item lines: the N-019 long-memo print. */
    val fortyLines = seededSale.copy(
        lines = (1..40).map { MemoPrintLine(String.format(java.util.Locale.ROOT, "SKU-%02d স্টার ফিল্টার", it), it * 10L, it * 79_350L, qtyEntered = it.toLong(), unit = "packet") },
        discounts = emptyList(),
        grossMtk = (1..40).sumOf { it * 79_350L },
        drpDiscountMtk = 0, qcDeductionMtk = 0,
        roundAdjMtk = 0, netMtk = 0, paidMtk = 0, dueMtk = 0, isCredit = false, kind = "cash_memo",
    ).let { m ->
        val net = com.aktcl.aron.rules.Money.roundToPaisaHalfUp(m.grossMtk)
        m.copy(netMtk = net, roundAdjMtk = net - m.grossMtk, paidMtk = net)
    }

    val stockSlip = StockSlipPrint(
        printedAtEpochMs = T, sr = "মোঃ করিম উদ্দিন", route = "মোহাম্মদপুর-২", distributor = "এ কে ট্রেডার্স",
        lines = listOf(
            StockSlipLine("সিগারেট", "Sheikh 20s", 2000, 100, "packet"), StockSlipLine("সিগারেট", "Navy Filter", 1000, 100, "packet"),
            StockSlipLine("ম্যাচ", "FB", 50, 50, "dozen"), StockSlipLine("লাইটার", "SL Lighter", 100, 4, "box"),
        ),
    )

    val daySummary = DaySummaryPrint(
        printedAtEpochMs = T, sr = "মোঃ করিম উদ্দিন", route = "মোহাম্মদপুর-২",
        lines = listOf(DaySummaryLine("FB", 4, 12, 336_000, 12, "dozen"), DaySummaryLine("Sheikh 20s", 9, 640, 5_078_400, 32, "packet")),
        grossMtk = 5_414_400, discountMtk = 2_400, qcMtk = 10_000, netMtk = 5_402_000, dueMtk = 112_260, memoCount = 11,
    )

    val voidSlip = VoidSlipPrint("sr001-261007-0042", T, "রহিম স্টোর্স (O-10234)", "মোঃ করিম উদ্দিন", "ভুল পরিমাণ", 612_260)

    val dueReceipt = DueReceiptPrint("sr001-261005-0007", T, "রহিম স্টোর্স (O-10234)", "মোঃ করিম উদ্দিন", 112_260, 100_000, 12_260)
}
