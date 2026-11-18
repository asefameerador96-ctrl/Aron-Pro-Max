package com.aktcl.aron.feature.sale.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.StockMovementEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.EditTargetSupersededException
import com.aktcl.aron.core.database.repo.OverStockException
import com.aktcl.aron.core.database.repo.StockHeld
import com.aktcl.aron.rules.QtyUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Review of slice 1 (stock gate), lows 1, 2, 4, 5 and 10, on the real Room database: the date the stock is read for, an edit of a memo that
 * another edit already replaced, the one definition of "held", a refusal at save time that the screen and the row both word the same, an
 * edit that changes the SKU or the unit, and a stock row of another business date.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class StockMemoDateTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AronDatabase
    private lateinit var repo: CaptureRepository
    private lateinit var committer: SaleCommitter
    private lateinit var visitUuid: String
    private val d1 = Fx.DATE            // 2026-10-05: the day the visit opened
    private val d2 = "2026-10-06"        // the day after Dhaka midnight
    /** The clock's date. Production's meta source (CaptureMetaSource.dated, used by SrSaleKit and AmoSaleKit) gives a record the date of its visit or memo, never later than this. */
    private var clockDate = d1

    private val sku = Fx.maxr.copy(stockBase = 100)
    private val catalog = mapOf(sku.skuId to sku)
    private val asked = ArrayList<String>()

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
        repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }
        committer = SaleCommitter(repo, FakeNumbers(), CaptureMetaSource.dated { r -> Fx.meta().copy(businessDate = clockDate, routeId = r) }, { "2026-10-05T04:35:00.000Z" }, findMemo = { db.captureDao().memo(it) })
        visitUuid = ClientIds.newUuid()
        val (v, f) = Fx.visit(visitUuid)
        repo.recordVisitOpen(v, f)
        Fx.seedStock(repo, catalog) // 100 sticks, dated d1
    }
    @After fun tearDown() = db.close()

    private val store = object : DraftStore {
        val m = HashMap<String, SaleDraft>()
        override fun load(visitUuid: String) = m[visitUuid]
        override fun save(draft: SaleDraft) { m[draft.visitUuid] = draft }
        override fun clear(visitUuid: String) { m.remove(visitUuid) }
    }
    /** A catalog that reads the stock from Room for the date it is asked, as production does, and remembers the dates asked. */
    private fun roomCatalog() = SaleCatalog { date, _ -> asked += date; listOf(sku.copy(stockBase = StockHeld.read(db.captureDao(), date)[sku.skuId] ?: 0L)) }
    private fun memoCount() = db.query("SELECT COUNT(*) FROM memo", null).use { it.moveToFirst(); it.getInt(0) }
    private fun fix(owner: String) = GeoFixEntity(
        ClientIds.newUuid(), owner, "memo_edit", "ok", 23.79, 90.40, 10.0, provider = "fused", isMock = false, reused = false,
        deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false,
    )

    // ---- low 1: the screen and the transaction read the stock of the SAME date, the memo's ----

    @Test fun theCatalogIsReadForTheDateTheMemoWillCarryWhichIsTheVisitsOwn() = runTest {
        clockDate = d2 // the draft is opened after midnight on a visit of d1: ruling 2, the memo carries d1, which holds the stock
        val flow = SaleFlow(store, roomCatalog(), committer)
        flow.start(SaleVisit(visitUuid, 50001, 10231, d1, "outlet"))
        assertEquals("the stock was read for the memo's date", listOf(d1), asked)
        assertEquals("the row shows what the transaction will see", 100L, flow.state.value.catalog.getValue(sku.skuId).stockBase)
        assertEquals("so entry is allowed and the save agrees", 60L, flow.setQuantity(sku.skuId, 60, QtyUnit.STICK))
    }

    /** The day rolled over between opening and saving: the memo still carries the visit's date, so the save reads the stock of that date and succeeds (ruling 2). */
    @Test fun aSaleOpenedBeforeMidnightIsSavedAfterItOnTheVisitsDateAgainstThatDatesStock() = runTest {
        val flow = SaleFlow(store, roomCatalog(), committer)
        flow.start(SaleVisit(visitUuid, 50001, 10231, d1, "outlet"))   // read at d1: 100 held
        assertEquals(60L, flow.setQuantity(sku.skuId, 60, QtyUnit.STICK))
        clockDate = d2                                                  // midnight passes
        flow.commit()
        assertEquals(1, memoCount())
        assertEquals("the memo carries the VISIT's date", d1, db.query("SELECT business_date FROM memo", null).use { it.moveToFirst(); it.getString(0) })
        assertEquals(40L, StockHeld.read(db.captureDao(), d1)[sku.skuId])
        assertNull("the new day holds and received nothing", StockHeld.read(db.captureDao(), d2)[sku.skuId])
    }

    /**
     * Round-2 review, blocking 1: an edit started across Dhaka midnight uses ONE date, the memo's. The stock given back is the memo's own (so the row
     * and the transaction agree), the record carries the memo's date, and it supersedes the memo on that same date: it is saved, never let in and refused.
     */
    @Test fun anEditAcrossMidnightGivesBackTheMemosOwnStockAndIsSavedOnTheMemosDate() = runTest {
        val first = committer.commit(SaleDraftOps.setQuantity(Fx.draft(visitUuid), sku.skuId, 60, QtyUnit.STICK), catalog) // 60 of 100 sold on d1
        clockDate = d2
        val old = db.captureDao().memo(first.memoUuid)!!
        val flow = SaleFlow(store, roomCatalog(), committer, ownTaken = { m -> db.captureDao().linesOf(m).groupBy { it.skuId }.mapValues { (_, v) -> v.sumOf { it.qtyBase } } })
        flow.start(SaleVisit(visitUuid, 50001, 10231, old.meta.businessDate, "outlet"), editing = first.memoUuid)
        assertEquals("the stock is read for the memo's date", listOf(d1), asked)
        // the edit may take 90: the 40 held plus the memo's own 60 given back (both of d1)
        assertEquals(90L, flow.setQuantity(sku.skuId, 90, QtyUnit.STICK))
        flow.withEdit(EditContext(first.memoUuid, "wrong_quantity"))
        flow.commit(fix(flow.state.value.draft!!.memoUuid))
        val edited = db.query("SELECT business_date, supersedes_client_uuid FROM memo WHERE supersedes_client_uuid IS NOT NULL", null).use { it.moveToFirst(); it.getString(0) to it.getString(1) }
        assertEquals("the edit carries the memo's date, not the clock's", d1 to first.memoUuid, edited)
        assertEquals(10L, StockHeld.read(db.captureDao(), d1)[sku.skuId])
    }

    // ---- low 5: the OverStockException catch is really reached, with a stale catalog, and the screen is told ----

    @Test fun theFlowCatchIsReachedWhenAnotherMemoLandedAfterTheScreenOpened() = runTest {
        val flow = SaleFlow(store, roomCatalog(), committer)
        flow.start(SaleVisit(visitUuid, 50001, 10231, d1, "outlet"))   // the screen holds 100
        committer.commit(SaleDraftOps.setQuantity(Fx.draft(visitUuid), sku.skuId, 60, QtyUnit.STICK), catalog) // another memo takes 60 behind its back
        assertEquals(60L, flow.setQuantity(sku.skuId, 60, QtyUnit.STICK)) // the stale screen still lets 60 through
        val thrown = assertThrows(ReviewNotCommittable::class.java) { runBlocking { flow.commit() } }
        val over = thrown.problems.filterIsInstance<ReviewProblem.OverStock>().single() // SKU, held figure and unit, as the dialog words them
        assertEquals(sku.skuId, over.skuId); assertEquals(40L, over.heldBase); assertEquals(60L, over.requestedBase); assertTrue(over.unitKey.isNotEmpty())
        assertEquals(40L, flow.state.value.catalog.getValue(sku.skuId).stockBase)
        assertEquals(thrown.problems, flow.state.value.refusal)
        assertEquals(1, memoCount())
        // the next edit of the draft clears the refusal; a later failed attempt does not show this one's words
        flow.setQuantity(sku.skuId, 40, QtyUnit.STICK)
        assertTrue(flow.state.value.refusal.isEmpty())
    }


    /** Review low: a clock set BACK after the edit draft opened must not save the edit under an earlier day superseding a memo of another date: it is refused and nothing is written. */
    @Test fun anEditWhoseDateTheClockCannotCarryIsRefusedNotSavedUnderAnotherDay() = runTest {
        val first = committer.commit(SaleDraftOps.setQuantity(Fx.draft(visitUuid), sku.skuId, 60, QtyUnit.STICK), catalog)
        clockDate = "2026-10-04" // the trusted clock regressed a day: the memo's d1 is now in the future
        val edit = SaleDraftOps.setQuantity(Fx.draft(visitUuid), sku.skuId, 10, QtyUnit.STICK).copy(edit = EditContext(first.memoUuid, "wrong_quantity"))
        assertThrows(IllegalStateException::class.java) { runBlocking { committer.commit(edit, catalog, fix(edit.memoUuid)) } }
        assertEquals("nothing was written", 1, memoCount())
    }
}
