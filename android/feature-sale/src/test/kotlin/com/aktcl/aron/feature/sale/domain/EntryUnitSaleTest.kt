package com.aktcl.aron.feature.sale.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.rules.MemoLine
import com.aktcl.aron.rules.MemoMath
import com.aktcl.aron.rules.QtyUnit
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** D-UNITS-02 (docs/34 s4.2) with the default rule: packets of 10 and 20, a lighter box of 25 and a match dozen, offline, stored and totalled. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class EntryUnitSaleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AronDatabase
    private lateinit var committer: SaleCommitter
    private lateinit var visitUuid: String

    // the bundle rows: base_per_pack and entry_unit_default come from the SKU master; nothing below hardcodes 10, 20 or 25 in the app
    private val c10 = SaleSku(1, "MAXR10", "cigarette", "MaxR 10S", null, "stick", 10, 8_000, 1, "2026-09-01", entryUnitDefault = "pack", stockBase = 400)
    private val c20 = SaleSku(2, "MAXR20", "cigarette", "MaxR 20S", null, "stick", 20, 7_935, 1, "2026-09-01", entryUnitDefault = "pack")
    private val box = SaleSku(3, "ASTER", "lighter", "Aster", null, "piece", 25, 12_500, 1, "2026-09-01", entryUnitDefault = "pack")
    private val doz = SaleSku(4, "FB", "match", "Flame Box", null, "dozen", 1, 28_000, 1, "2026-09-01", entryUnitDefault = "dozen")
    private val catalog = listOf(c10, c20, box, doz).associateBy { it.skuId }

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
        val repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }
        committer = SaleCommitter(repo, FakeNumbers(), { _, route -> Fx.meta().copy(routeId = route) }, { "2026-10-05T04:35:00.000Z" }, findMemo = { db.captureDao().memo(it) })
        visitUuid = com.aktcl.aron.core.common.ClientIds.newUuid()
        val (v, f) = Fx.visit(visitUuid)
        repo.recordVisitOpen(v, f)
    }
    @After fun tearDown() = db.close()

    @Test fun entryUnitsAndPacketPricesComeFromTheBundleRow() {
        assertEquals(listOf(QtyUnit.PACK, QtyUnit.PACK, QtyUnit.PACK, QtyUnit.DOZEN), catalog.values.map { it.entry.unit })
        assertEquals(listOf(10, 20, 25, 1), catalog.values.map { it.entry.factor })
        assertEquals(listOf("packet", "packet", "box", "dozen"), catalog.values.map { it.entryLabelKey })
        assertEquals(listOf(80_000L, 158_700L, 312_500L, 28_000L), catalog.values.map { it.entryPriceMtk })
    }

    @Test fun looseSticksAreRefusedUnlessTheConfigAllowsThem() {
        val d = SaleDraftOps.setQuantity(Fx.draft(visitUuid), 1, 20, QtyUnit.STICK)
        val r = SaleReviewCalculator.review(d, catalog)
        assertTrue(r.problems.single() is ReviewProblem.UnitNotAllowed); assertFalse(r.canCommit)
        assertTrue(SaleReviewCalculator.review(d, mapOf(1L to c10.copy(allowLoose = true))).canCommit)
    }

    @Test fun offlineSaleOfEveryUnitKindIsStoredWithItsEntryUnitAndTheTotalEqualsTheOracle() = runTest {
        var d = Fx.draft(visitUuid)
        d = SaleDraftOps.setQuantity(d, 1, 3, QtyUnit.PACK)
        d = SaleDraftOps.setQuantity(d, 2, 2, QtyUnit.PACK)
        d = SaleDraftOps.setQuantity(d, 3, 2, QtyUnit.PACK)
        d = SaleDraftOps.setQuantity(d, 4, 5, QtyUnit.DOZEN)
        val review = SaleReviewCalculator.review(d, catalog)
        assertTrue(review.problems.toString(), review.canCommit)
        val out = committer.commit(d, catalog)
        val memo = db.captureDao().memo(out.memoUuid)!!
        val lines = db.captureDao().linesOf(out.memoUuid).sortedBy { it.skuId }
        assertEquals(listOf(3L, 2L, 2L, 5L), lines.map { it.qtyEntered })
        assertEquals(listOf("pack", "pack", "pack", "dozen"), lines.map { it.unitEntered })
        assertEquals(listOf(10, 20, 25, 1), lines.map { it.packFactor })
        assertEquals(listOf(30L, 40L, 50L, 5L), lines.map { it.qtyBase })
        lines.forEach { assertEquals(it.qtyEntered * (if (it.unitEntered == "pack") it.packFactor else 1), it.qtyBase) }
        // independent oracle: packet price x packets, and MemoMath over the stored base quantities
        val expected = 3 * 80_000L + 2 * 158_700L + 2 * 312_500L + 5 * 28_000L
        assertEquals(expected, memo.grossMtk)
        val oracle = MemoMath.totals(lines.map { MemoLine.priced(it.skuId, it.qtyBase, it.basePriceMtk, it.pricePerQty.toLong()) }, emptyList(), emptyList())
        assertEquals(oracle.grossMtk, memo.grossMtk); assertEquals(oracle.netMtk, memo.netMtk); assertEquals(out.netMtk, memo.netMtk)
        assertEquals(listOf(3L, 2L, 2L, 5L), review.lines.map { it.entry.qtyEntered })
        val packets = review.categories.first { it.categoryCode == "cigarette" }
        assertEquals(5L, packets.qtyEntered); assertEquals(70L, packets.qtyBase)
    }

    @Test fun stockWarningComparesBaseUnits() {
        val d = SaleDraftOps.setQuantity(Fx.draft(visitUuid), 1, 41, QtyUnit.PACK) // 41 x 10 = 410 sticks against 400 in stock
        assertTrue(SaleReviewCalculator.review(d, catalog).lines.single().exceedsStock)
    }
}
