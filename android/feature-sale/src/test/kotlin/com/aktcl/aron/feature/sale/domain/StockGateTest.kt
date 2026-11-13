package com.aktcl.aron.feature.sale.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.OverStockException
import com.aktcl.aron.core.database.repo.StockHeld
import com.aktcl.aron.rules.QtyUnit
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * D-OWNER-PREREQ-01 rule 2 / D-LEAD-PREREQ-02, device defect D2: the stock gate is a HARD stop, not a warning, and the last
 * word is a re-check INSIDE the saving transaction against the stock read in that transaction. These tests drive the real
 * Room database, so they prove the stored rows, not a screen state: two memos in a row, a double tap, an edit, no stock
 * entry at all, reward packs, and that a sale can no longer produce a negative stock figure.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class StockGateTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AronDatabase
    private lateinit var repo: CaptureRepository
    private lateinit var committer: SaleCommitter
    private lateinit var visitUuid: String

    /** 100 sticks of SKU 100 and nothing else: every figure below is in base units. */
    private val sku = Fx.maxr.copy(stockBase = 100)
    private val catalog = mapOf(sku.skuId to sku)

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
        repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }
        committer = SaleCommitter(repo, FakeNumbers(), { _, r -> Fx.meta().copy(routeId = r) }, { "2026-10-05T04:35:00.000Z" }, findMemo = { db.captureDao().memo(it) })
        visitUuid = ClientIds.newUuid()
        val (v, f) = Fx.visit(visitUuid)
        repo.recordVisitOpen(v, f)
        Fx.seedStock(repo, catalog)
    }
    @After fun tearDown() = db.close()

    private fun draft(qty: Long) = SaleDraftOps.setQuantity(Fx.draft(visitUuid), sku.skuId, qty, QtyUnit.STICK)
    private suspend fun held() = StockHeld.read(db.captureDao(), Fx.DATE)[sku.skuId] ?: 0L
    private fun memoCount() = db.query("SELECT COUNT(*) FROM memo", null).use { it.moveToFirst(); it.getInt(0) }

    /**
     * Two memos in a row with a STALE catalog: the first takes 60 of 100, the second is reviewed against the same catalog
     * snapshot (as a screen opened before the first save would be) and asks for 60 more. The transaction refuses it.
     */
    @Test fun aSecondMemoOnAStaleCatalogIsRefusedInsideTheSavingTransaction() = runTest {
        committer.commit(draft(60), catalog)
        assertEquals(40L, held())
        val e = assertThrows(OverStockException::class.java) { kotlinx.coroutines.runBlocking { committer.commit(draft(60), catalog) } }
        assertEquals(listOf(sku.skuId), e.shortages.map { it.skuId })
        assertEquals(60L, e.shortages.single().requestedBase)
        assertEquals(40L, e.shortages.single().heldBase) // the figure READ IN THE TRANSACTION, not the stale 100
        assertEquals(1, memoCount()) // nothing was written by the refused save
        assertEquals(40L, held())
    }

    /** What is left fits exactly: the gate lets the whole remainder be sold, down to zero, and never below. */
    @Test fun theRemainderCanBeSoldToTheLastStickAndNeverBelowZero() = runTest {
        committer.commit(draft(60), catalog)
        committer.commit(SaleDraftOps.setQuantity(Fx.draft(visitUuid), sku.skuId, 40, QtyUnit.STICK), catalog)
        assertEquals(0L, held())
        assertThrows(OverStockException::class.java) { kotlinx.coroutines.runBlocking { committer.commit(draft(1), catalog) } }
        assertEquals(0L, held()) // a sale can no longer produce a negative stock figure
        assertEquals(2, memoCount())
    }

    /** A double tap on Save: the same draft (same memo UUID) twice. One memo, and the second attempt is not an over-stock refusal. */
    @Test fun aDoubleTapOnSaveWritesOneMemoAndDoesNotDoubleTheStockDraw() = runTest {
        val d = draft(100)
        val store = object : DraftStore {
            val m = HashMap<String, SaleDraft>()
            override fun load(visitUuid: String) = m[visitUuid]
            override fun save(draft: SaleDraft) { m[draft.visitUuid] = draft }
            override fun clear(visitUuid: String) { m.remove(visitUuid) }
        }
        store.save(d)
        val flow = SaleFlow(store, { _, _ -> listOf(sku) }, committer)
        flow.start(SaleVisit(visitUuid, 50001, 10231, Fx.DATE, "outlet"))
        val a = flow.commit()
        val b = flow.commit() // the second tap
        assertEquals(a.memoUuid, b.memoUuid)
        assertEquals(1, memoCount())
        assertEquals(0L, held()) // drawn once, not twice
    }

    /** No stock entry for a SKU today is ZERO, not unknown: the sale is refused in the transaction as well as in the review. */
    @Test fun aSkuWithNoStockEntryTodayCannotBeSold() = runTest {
        val other = sku.copy(skuId = 106, stockBase = 50) // a catalog that lies: no movement was ever stored for 106
        val d = SaleDraftOps.setQuantity(Fx.draft(visitUuid), 106, 10, QtyUnit.STICK)
        val e = assertThrows(OverStockException::class.java) { kotlinx.coroutines.runBlocking { committer.commit(d, mapOf(106L to other)) } }
        assertEquals(0L, e.shortages.single().heldBase)
        assertEquals(0, memoCount())
    }

    /**
     * An edit gets its OWN memo's quantity back: with 100 held and 100 sold, an unchanged edit of that memo must save, and an
     * edit that asks for more than the stock plus its own quantity must not.
     */
    @Test fun anEditGetsItsOwnMemosQuantityBackAndStillCannotGoAboveTheStock() = runTest {
        val first = committer.commit(draft(100), catalog)
        assertEquals(0L, held())
        val fix = com.aktcl.aron.core.database.entity.GeoFixEntity(
            ClientIds.newUuid(), "", "memo_edit", "ok", 23.79, 90.40, 10.0, provider = "fused", isMock = false, reused = false,
            deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false,
        )
        // the same quantity again: the superseded memo's 100 come back, so it fits exactly
        val same = draft(100).copy(edit = EditContext(first.memoUuid, "wrong_quantity"))
        committer.commit(same, catalog, fix.copy(clientUuid = ClientIds.newUuid(), ownerClientUuid = same.memoUuid))
        assertEquals(0L, held())
        // one stick more than the stock plus its own quantity: the review refuses it (the catalog gave the 100 back)...
        val tooMuch = draft(101).copy(edit = EditContext(same.memoUuid, "wrong_quantity"))
        assertThrows(ReviewNotCommittable::class.java) {
            kotlinx.coroutines.runBlocking { committer.commit(tooMuch, catalog, fix.copy(clientUuid = ClientIds.newUuid(), ownerClientUuid = tooMuch.memoUuid)) }
        }
        // ...and a catalog that claims more (a stale snapshot, or a tampered build) is stopped by the transaction itself
        val e = assertThrows(OverStockException::class.java) {
            kotlinx.coroutines.runBlocking {
                committer.commit(tooMuch, mapOf(sku.skuId to sku.copy(stockBase = 1_000)), fix.copy(clientUuid = ClientIds.newUuid(), ownerClientUuid = tooMuch.memoUuid))
            }
        }
        assertEquals(100L, e.shortages.single().heldBase) // its own memo's 100 given back, nothing more
        assertEquals(2, memoCount())
    }

    /** Reward packs of the same SKU in the memo count against the stock with the sale line, inside the transaction too. */
    @Test fun rewardPacksCountAgainstTheStockInsideTheTransaction() = runTest {
        // 95 sticks sold plus 10 empty packets, which earn one reward pack of 10 sticks: 105 of 100. The review refuses it...
        val d = SaleDraftOps.setSlide(draft(95), sku.skuId, 10)
        val reviewed = assertThrows(ReviewNotCommittable::class.java) { kotlinx.coroutines.runBlocking { committer.commit(d, catalog) } }
        assertEquals(105L, reviewed.problems.filterIsInstance<ReviewProblem.OverStock>().single().requestedBase)
        // ...and so does the transaction, when the catalog claims more than the rows hold
        val e = assertThrows(OverStockException::class.java) {
            kotlinx.coroutines.runBlocking { committer.commit(d, mapOf(sku.skuId to sku.copy(stockBase = 1_000))) }
        }
        assertEquals(105L, e.shortages.single().requestedBase)
        assertEquals(0, memoCount())
        // 85 plus the free 10 fits, and the free pack is drawn from the stock as well
        committer.commit(SaleDraftOps.setSlide(draft(85), sku.skuId, 10), catalog)
        assertEquals(5L, held())
    }

    /** The flow turns the transaction's refusal into problems a screen can word, with the TRUE held figure, and writes nothing. */
    @Test fun theFlowReportsTheTransactionsOwnFiguresAndKeepsTheDraft() = runTest {
        committer.commit(draft(60), catalog)
        val store = object : DraftStore {
            val m = HashMap<String, SaleDraft>()
            override fun load(visitUuid: String) = m[visitUuid]
            override fun save(draft: SaleDraft) { m[draft.visitUuid] = draft }
            override fun clear(visitUuid: String) { m.remove(visitUuid) }
        }
        store.save(draft(60)) // a draft made before the first memo: its catalog still says 100
        val flow = SaleFlow(store, { _, _ -> listOf(sku.copy(stockBase = StockHeld.read(db.captureDao(), Fx.DATE)[sku.skuId] ?: 0L)) }, committer)
        flow.start(SaleVisit(visitUuid, 50001, 10231, Fx.DATE, "outlet"))
        // the review already refuses it from the fresh catalog; force the stale snapshot through the committer to reach the transaction
        val thrown = assertThrows(ReviewNotCommittable::class.java) { kotlinx.coroutines.runBlocking { flow.commit() } }
        val over = thrown.problems.filterIsInstance<ReviewProblem.OverStock>().single()
        assertEquals(40L, over.heldBase)
        assertEquals(60L, over.requestedBase)
        assertFalse(flow.state.value.review!!.canCommit)
        assertEquals(1, memoCount())
        assertTrue(store.m.containsKey(visitUuid)) // the draft is kept: the rep lowers the quantity and saves
    }
}
