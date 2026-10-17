package com.aktcl.aron.feature.memo.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.MemoEntity
import com.aktcl.aron.core.database.entity.MemoDiscountEntity
import com.aktcl.aron.core.database.entity.MemoLineEntity
import com.aktcl.aron.core.database.entity.QcLineEntity
import com.aktcl.aron.core.database.entity.VisitEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.SaleCapture
import com.aktcl.aron.core.printing.doc.DocumentBuilder
import com.aktcl.aron.core.printing.template.DigitStyle
import com.aktcl.aron.feature.memo.domain.PrintMapping
import com.aktcl.aron.feature.memo.domain.PrintNames
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Random

/**
 * End to end, host side (android-print T1 acceptance for F-SR-028/031): a memo committed to Room, read back by the
 * production [RoomMemoStore], mapped by [PrintMapping] and laid out by core-printing's [DocumentBuilder] prints exactly the
 * stored milli-taka columns (net, paid, due, discount, QC) and line amounts, in Latin and Bengali digits; nothing is
 * recomputed on the way. Amounts that are not whole paisa are included (printed with 3 decimals, AP-04).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class StoredMemoPrintTotalsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    @After fun tearDown() = db.close()

    private fun meta() = CaptureMeta("2026-10-05", "2026-10-05T04:31:07.120Z", 1, 1, 0, true, 10231, null, "b", false, 1)
    private val names = PrintNames(sku = { "SKU$it" }, outlet = { "Outlet $it" }, sr = "SR", route = "Route")

    /** Parses a printed amount ("1,234.50", "৭.৯৩৫", "-2.40") back to milli-taka. */
    private fun mtk(printed: String): Long {
        val ascii = printed.map { if (it in '০'..'৯') '0' + (it - '০') else it }.joinToString("").replace(",", "").replace(" ", "")
        val neg = ascii.startsWith("-")
        val body = ascii.removePrefix("-").removePrefix("+")
        val v = body.substringBefore('.').toLong() * 1000 + body.substringAfter('.', "").padEnd(3, '0').toLong()
        return if (neg) -v else v
    }

    @Test fun printedAmountsAreTheStoredColumns() = runTest {
        val repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }
        val rnd = Random(20261007)
        val stored = HashMap<String, MemoEntity>()
        repeat(40) { i ->
            val visit = ClientIds.newUuid(); val fixId = ClientIds.newUuid()
            val fix = GeoFixEntity(fixId, visit, "visit_open", "ok", 23.79, 90.40, 11.5, provider = "fused", isMock = false, reused = false, deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false)
            repo.recordVisitOpen(VisitEntity(visit, meta(), "sr_call", 1L + i, "2026-10-05T04:31:07.120Z", i + 1, true, null, fixId, "in_range", 18.4, 100, 50, "master", 23.7, 90.4, "sale_allowed"), fix)
            val memo = ClientIds.newUuid()
            // Prices in milli-taka, some not whole paisa (e.g. 9_005), so a recomputation or rounding would show.
            val lines = (1..1 + rnd.nextInt(6)).map { n ->
                val qty = 1L + rnd.nextInt(60)
                val price = 1_000L + rnd.nextInt(30_000)
                MemoLineEntity(ClientIds.newUuid(), meta(), memo, n, 100L + n, "sale", qty, "stick", 1, qty, "outlet", "2026-09-01", price, 1, qty * price)
            }
            val gross = lines.sumOf { it.grossMtk }
            // Half the memos carry a discount line and a QC deduction, so net differs from gross.
            val withDeductions = i % 2 == 0
            val disc = if (withDeductions) listOf(MemoDiscountEntity(ClientIds.newUuid(), meta(), memo, "offer", lines[0].skuId, 1, gross / 10 + 5, offerId = 9, offerVersionId = 12, lineNo = 1)) else emptyList()
            val qc = if (withDeductions) listOf(QcLineEntity(ClientIds.newUuid(), meta(), visit, memo, true, lines[0].skuId, "torn_pack", "MFC", 1, lines[0].basePriceMtk, lines[0].basePriceMtk)) else emptyList()
            val discount = disc.sumOf { it.valueMtk }
            val qcMtk = qc.sumOf { it.settlementMtk }
            // A DRP discount and a rounding adjustment on the memo row only: a mapper that recomputed net from the lines,
            // the discount rows or the QC rows would print a different total than the stored one.
            val drp = if (withDeductions) 1_000L + rnd.nextInt(5_000) else 0L
            val roundAdj = (rnd.nextInt(999) - 499).toLong().let { if (it == 0L) 7L else it }
            val net = gross - discount - drp - qcMtk + roundAdj
            val paid = if (rnd.nextBoolean()) net else (net * rnd.nextInt(100) / 100)
            val entity = MemoEntity(
                memo, meta(), visit, 1L + i, "sr334001-261005-${100 + i}", "sale", "2026-10-05T04:35:00.000Z", "2026-10-05", "outlet",
                gross, discount, drp, qcMtk, roundAdj, net, paid, net - paid, net - paid > 0, null, lines.size, disc.size, qc.size,
            )
            repo.recordSale(SaleCapture(entity, lines, disc, qc))
            stored[memo] = entity
        }

        val memos = RoomMemoStore(db).memos("2026-10-05")
        assertEquals(40, memos.size)
        for (m in memos) {
            val row = stored.getValue(m.memoUuid)
            val print = PrintMapping.memo(m, names)
            for (digits in DigitStyle.entries) {
                val doc = DocumentBuilder(digits).memo(print)
                assertEquals(row.netMtk, mtk(doc.fields.getValue("grand_total")))
                assertEquals(row.paidMtk, mtk(doc.fields.getValue("paid")))
                assertEquals(row.dueMtk, mtk(doc.fields.getValue("due")))
                assertEquals(row.qcDeductionMtk, mtk(doc.fields.getValue("total_qc")))
                assertEquals(row.offerDiscountMtk + row.drpDiscountMtk, mtk(doc.fields.getValue("total_discount")))
                assertEquals(row.roundAdjMtk, mtk(doc.fields.getValue("rounding")))
                assertEquals(db.captureDao().linesOf(m.memoUuid).map { it.grossMtk }, doc.tables.getValue("lines").map { mtk(it.getValue("value")) })
            }
            assertEquals(row.memoNo, print.memoNo)
        }
    }
}
