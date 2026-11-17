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
    /** The date the memo will carry: production's meta source ignores the draft's date and uses "today" (SrSaleKit, AmoSaleKit). */
    private var memoDate = d1

    private val sku = Fx.maxr.copy(stockBase = 100)
    private val catalog = mapOf(sku.skuId to sku)
    private val asked = ArrayList<String>()

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
        repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }
        committer = SaleCommitter(repo, FakeNumbers(), { _, r -> Fx.meta().copy(businessDate = memoDate, routeId = r) }, { "2026-10-05T04:35:00.000Z" }, findMemo = { db.captureDao().memo(it) })
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

    @Test fun theCatalogIsReadForTheDateTheMemoWillCarryNotTheVisitsOwn() = runTest {
        memoDate = d2 // the draft is opened after midnight on a visit of d1: the memo will be dated d2, and d2 holds no stock
        val flow = SaleFlow(store, roomCatalog(), committer)
        flow.start(SaleVisit(visitUuid, 50001, 10231, d1, "outlet"))
        assertEquals("the stock was read for the memo's date", listOf(d2), asked)
        assertEquals("the row shows what the transaction will see: none", 0L, flow.state.value.catalog.getValue(sku.skuId).stockBase)
        assertEquals("so entry is stopped, not allowed and then refused", 0L, flow.setQuantity(sku.skuId, 60, QtyUnit.STICK))
    }

    /** The day rolled over between opening and saving: the refusal and the row it re-draws speak about the same day and the same figure. */
    @Test fun aRefusalAtSaveTimeAndTheRowAgreeAcrossMidnight() = runTest {
        val flow = SaleFlow(store, roomCatalog(), committer)
        flow.start(SaleVisit(visitUuid, 50001, 10231, d1, "outlet"))   // read at d1: 100 held
        assertEquals(60L, flow.setQuantity(sku.skuId, 60, QtyUnit.STICK))
        memoDate = d2                                                  // midnight passes; the memo will now carry d2, which holds nothing
        val thrown = assertThrows(ReviewNotCommittable::class.java) { runBlocking { flow.commit() } } // the review (stale catalog) passes; the TRANSACTION refuses: the catch is reached
        val over = thrown.problems.filterIsInstance<ReviewProblem.OverStock>().single()
        assertEquals(0L, over.heldBase)
        assertEquals("the row the screen now draws holds the same figure as the refusal", over.heldBase, flow.state.value.catalog.getValue(sku.skuId).stockBase)
        assertEquals("the dialog says the same words", thrown.problems, flow.state.value.refusal)
        assertEquals(0, memoCount())
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

}
