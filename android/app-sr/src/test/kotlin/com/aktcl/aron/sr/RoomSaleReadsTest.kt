package com.aktcl.aron.sr

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.MemoEntity
import com.aktcl.aron.core.database.entity.MemoLineEntity
import com.aktcl.aron.core.database.entity.PriceEntity
import com.aktcl.aron.core.database.entity.SkuEntity
import com.aktcl.aron.core.database.entity.StockMovementEntity
import com.aktcl.aron.core.database.entity.VisitCloseEntity
import com.aktcl.aron.core.database.entity.VisitEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.ReferenceRepository
import com.aktcl.aron.core.database.repo.SaleCapture
import com.aktcl.aron.feature.dayclose.OTHER_CATEGORY
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The production Room reads behind Sale, Summary, Journey and KPI: priced catalog with the stock tracker, then the day after one sale. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RoomSaleReadsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    @After fun tearDown() = db.close()

    private val date = "2026-10-05"
    private fun meta() = CaptureMeta(date, "2026-10-05T04:31:07.120Z", 1, 1, 0, true, 10231, null, "b", false, 1)
    private fun sku(id: Long, cat: String, unit: String) = SkuEntity(id, "S$id", id, cat, "n$id", "Aster$id", null, unit, 10, "base", null, "1.000", id.toInt(), "active", 1)

    @Test fun catalogCarriesBundlePricesAndTheStockTrackerThenTheSaleLowersStockAndFeedsSummaryJourneyAndKpi() = runTest {
        db.referenceDao().insertSkus(listOf(sku(105, "match", "dozen"), sku(106, "bidi", "stick")))
        db.referenceDao().insertPrices(listOf(PriceEntity(1, 105, "outlet", 28_000, 1, "2026-09-01", null)))
        val repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }
        repo.recordStock(listOf(StockMovementEntity(ClientIds.newUuid(), meta(), "issue", 105, 20, "dozen", 1, 20, null, false)))
        val reads = RoomSaleReads(db, ReferenceRepository(db))

        // priced SKU only (106 has no outlet price), stock = issued so far
        val before = reads.catalog(date, "outlet")
        assertEquals(listOf(105L), before.map { it.skuId })
        assertEquals(28_000, before.single().unitPriceMtk); assertEquals("dozen", before.single().baseUnit); assertEquals(20L, before.single().stockBase)

        val visit = ClientIds.newUuid(); val fixId = ClientIds.newUuid()
        val fix = GeoFixEntity(fixId, visit, "visit_open", "ok", 23.79, 90.40, 11.5, provider = "fused", isMock = false, reused = false, deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false)
        repo.recordVisitOpen(VisitEntity(visit, meta(), "sr_call", 1, "2026-10-05T04:31:07.120Z", 1, true, null, fixId, "in_range", 18.4, 100, 50, "master", 23.7, 90.4, "sale_allowed"), fix)
        val memo = ClientIds.newUuid()
        repo.recordSale(SaleCapture(
            MemoEntity(memo, meta(), visit, 1, "sr334001-261005-001", "sale", "2026-10-05T04:35:00.000Z", date, "outlet", 84_000, 0, 0, 0, 0, 84_000, 84_000, 0, true, null, 1, 0, 0),
            listOf(MemoLineEntity(ClientIds.newUuid(), meta(), memo, 1, 105, "sale", 3, "dozen", 1, 3, "outlet", "2026-09-01", 28_000, 1, 84_000)),
        ))
        repo.recordVisitClose(VisitCloseEntity(ClientIds.newUuid(), meta(), visit, "sold", null, false, "2026-10-05T04:40:00.000Z", false))

        assertEquals(17L, reads.catalog(date, "outlet").single().stockBase) // 20 issued - 3 sold
        val s = reads.summary(date)
        assertEquals(84_000, s.summary.grandTotalMtk); assertEquals(1, s.summary.memoCount); assertEquals(0, s.qcMtk); assertEquals(0, s.dueMtk)
        assertEquals(17L, s.summary.skus.single().returnQtyBase) // issued 20 - sold 3 goes back

        val outlets = emptyList<com.aktcl.aron.core.database.entity.OutletEntity>()
        val j = reads.journey(date, outlets)
        assertEquals(0, j.planned)
        assertNull(reads.kpi(date, outlets).first.strikeRateHundredths)
        assertEquals(84_000, reads.kpi(date, outlets).second.grandTotalMtk)
    }

    @Test fun aZeroPriceSkuIsNotOfferedOnSaleButStaysInStock() = runTest {
        db.referenceDao().insertSkus(listOf(sku(105, "match", "dozen"), sku(107, "cigarette", "stick")))
        db.referenceDao().insertPrices(listOf(
            PriceEntity(1, 105, "outlet", 28_000, 1, "2026-09-01", null),
            PriceEntity(2, 107, "outlet", 0, 1, "2026-09-01", null), // the real catalogue has zero prices for three SKUs
        ))
        val repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }
        repo.recordStock(listOf(
            StockMovementEntity(ClientIds.newUuid(), meta(), "issue", 105, 20, "dozen", 1, 20, null, false),
            StockMovementEntity(ClientIds.newUuid(), meta(), "issue", 107, 200, "stick", 1, 200, null, false),
        ))
        val reads = RoomSaleReads(db, ReferenceRepository(db))

        assertEquals(listOf(105L), reads.catalog(date, "outlet").map { it.skuId }) // 107 (price 0) is not on the Sale list
        val stock = com.aktcl.aron.feature.stock.StockTracker.of(db, date)
        assertEquals(200L, stock[107L]?.current) // the Stock screen still has it
        assertEquals(20L, stock[105L]?.current)
    }

    private suspend fun openVisit(repo: CaptureRepository, outletId: Long, seq: Int): String {
        val visit = ClientIds.newUuid(); val fixId = ClientIds.newUuid()
        val fix = GeoFixEntity(fixId, visit, "visit_open", "ok", 23.79, 90.40, 11.5, provider = "fused", isMock = false, reused = false, deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false)
        repo.recordVisitOpen(VisitEntity(visit, meta(), "sr_call", outletId, "2026-10-05T04:31:07.120Z", seq, true, null, fixId, "in_range", 18.4, 100, 50, "master", 23.7, 90.4, "sale_allowed"), fix)
        return visit
    }

    private fun line(memo: String, no: Int, sku: Long, entered: Long, unit: String, factor: Int, base: Long) =
        MemoLineEntity(ClientIds.newUuid(), meta(), memo, no, sku, "sale", entered, unit, factor, base, "outlet", "2026-09-01", 1_000, 1, base * 1_000)

    // SRM-B-25 through the SR wiring: packets of 20s and 10s, a remainder, a superseded memo, a SKU without a master row, two visits to one outlet
    @Test fun businessTotalsCountLiveMemosPacketsRemainderOtherAndDistinctOutlets() = runTest {
        db.referenceDao().insertSkus(listOf(
            SkuEntity(201, "S201", 201, "cigarette", "n201", "Avon-20S", null, "stick", 20, "pack", null, "1.000", 1, "active", 1),
            SkuEntity(202, "S202", 202, "cigarette", "n202", "Boss-10S", null, "stick", 10, "pack", null, "1.000", 2, "active", 1),
            SkuEntity(203, "S203", 203, "match", "n203", "Zam", null, "dozen", 1, "dozen", null, "1.000", 3, "active", 1),
        ))
        val repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }
        repo.recordStock(listOf(
            StockMovementEntity(ClientIds.newUuid(), meta(), "issue", 201, 10, "pack", 20, 200, null, false),
            StockMovementEntity(ClientIds.newUuid(), meta(), "issue", 203, 20, "dozen", 1, 20, null, false),
        ))
        val v1 = openVisit(repo, 1, 1); val v2 = openVisit(repo, 1, 2); val v3 = openVisit(repo, 2, 3) // outlet 1 twice, outlet 2 once
        val first = ClientIds.newUuid(); val second = ClientIds.newUuid(); val other = ClientIds.newUuid()
        val editFixId = ClientIds.newUuid()
        val editFix = GeoFixEntity(editFixId, second, "memo_edit", "ok", 23.79, 90.40, 11.5, provider = "fused", isMock = false, reused = false, deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false)
        fun memo(id: String, visit: String, outlet: Long, no: String, supersedes: String? = null, lines: Int = 1) =
            MemoEntity(id, meta(), visit, outlet, no, "sale", "2026-10-05T04:35:00.000Z", date, "outlet", 60_000, 0, 0, 0, 0, 60_000, 60_000, 0, false, null, lines, 0, 0, supersedesClientUuid = supersedes, editReasonCode = supersedes?.let { "wrong_sku" }, editFixClientUuid = supersedes?.let { editFixId })
        repo.recordSale(SaleCapture(memo(first, v1, 1, "m-1"), listOf(line(first, 1, 201, 2, "pack", 20, 40)))) // edited away below: must not count
        repo.recordSale(SaleCapture(
            memo(second, v2, 1, "m-2", supersedes = first, lines = 3),
            listOf(line(second, 1, 201, 3, "pack", 20, 60), line(second, 2, 202, 17, "stick", 1, 17), line(second, 3, 999, 5, "stick", 1, 5)),
            editFix = editFix,
        ))
        repo.recordSale(SaleCapture(memo(other, v3, 2, "m-3"), listOf(line(other, 1, 203, 4, "dozen", 1, 4))))

        val t = RoomSaleReads(db, ReferenceRepository(db)).businessTotals(date)
        assertEquals("a revisit counts once", 2, t.outletsVisited)
        val cig = t.sold.filter { it.categoryCode == "cigarette" }
        assertEquals(1, cig.size); assertEquals("packet", cig.single().unitKey)
        assertEquals(77L, cig.single().qtyBase) // 60 + 17 sticks; the superseded 40 is not counted
        assertEquals(4L, cig.single().entered); assertEquals(7L, cig.single().remBase) // 3 packets of 20 + 17 sticks = 1 packet of 10 + 7
        assertEquals(listOf(4L), t.sold.filter { it.categoryCode == "match" }.map { it.qtyBase })
        assertEquals("a SKU with no master row is counted, not dropped", listOf(5L), t.sold.filter { it.categoryCode == OTHER_CATEGORY }.map { it.qtyBase })
        assertEquals(listOf(200L), t.stockIssued.filter { it.categoryCode == "cigarette" }.map { it.qtyBase })
        assertEquals(0L, t.promotionMtk)
    }
}
