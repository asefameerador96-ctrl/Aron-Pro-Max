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
}
