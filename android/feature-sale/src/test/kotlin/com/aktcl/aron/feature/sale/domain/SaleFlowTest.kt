package com.aktcl.aron.feature.sale.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.rules.QtyUnit
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private class MemStore : DraftStore {
    val map = HashMap<String, SaleDraft>()
    override fun load(visitUuid: String) = map[visitUuid]
    override fun save(draft: SaleDraft) { map[draft.visitUuid] = draft }
    override fun clear(visitUuid: String) { map.remove(visitUuid) }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SaleFlowTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    @After fun tearDown() = db.close()

    private val numbers = FakeNumbers()
    private val seq: Int get() = numbers.consumed
    private fun committer() = SaleCommitter(CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }, numbers, { _, r -> Fx.meta().copy(routeId = r) }, { "2026-10-05T04:35:00.000Z" }, findMemo = { db.captureDao().memo(it) })
    private fun flow(store: DraftStore) = SaleFlow(store, { _, _ -> Fx.catalog.values.toList() }, committer())
    private suspend fun visit(): SaleVisit {
        val u = ClientIds.newUuid(); val (v, f) = Fx.visit(u)
        CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }.recordVisitOpen(v, f)
        return SaleVisit(u, 50001, 10231, Fx.DATE, "outlet")
    }

    @Test fun everyChangeIsPersistedSoARelaunchResumesTheSameDraft() = runTest {
        val store = MemStore(); val v = visit()
        val a = flow(store); a.start(v)
        a.setQuantity(100, 20, QtyUnit.STICK); a.setPaid(60_000)
        val relaunched = flow(store); relaunched.start(v) // new flow, same store: a kill and relaunch
        assertEquals(a.state.value.draft, relaunched.state.value.draft)
        assertEquals(20, relaunched.state.value.review!!.lines.single().qtyBase)
    }

    @Test fun commitIsRememberedSoADoubleTapWritesOneMemo() = runTest {
        val store = MemStore(); val v = visit(); val f = flow(store); f.start(v)
        f.setQuantity(100, 5, QtyUnit.STICK)
        val r1 = f.commit(); val r2 = f.commit()
        assertSame(r1, r2); assertEquals(1, seq)
        assertNull(store.load(v.visitUuid))
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { f.setQuantity(100, 6, QtyUnit.STICK) } }
    }

    @Test fun aBlockedVisitCannotSellAndNothingIsCreated() = runTest {
        val store = MemStore(); val f = flow(store)
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { f.start(visit().copy(canSell = false)) } }
        assertEquals(0, store.map.size)
    }

    @Test fun relaunchAfterTheCommitLandedButBeforeTheDraftWasClearedDoesNotDoubleTheMemo() = runTest {
        val store = MemStore(); val v = visit(); val a = flow(store); a.start(v)
        a.setQuantity(100, 5, QtyUnit.STICK)
        val saved = store.load(v.visitUuid)!!
        a.commit()
        store.save(saved) // the kill happened between the Room commit and the draft clear
        val b = flow(store); b.start(v)
        assertEquals(a.state.value.committed, b.state.value.committed) // recognised as already committed
        assertEquals(a.state.value.committed, b.commit())              // and a commit tap returns it without writing
        assertNull(store.load(v.visitUuid))
        assertEquals(1, db.captureDao().memosOn(Fx.DATE).size)
        assertNotNull(db.captureDao().memo(saved.memoUuid))
    }

    // ---- QC safety (checker findings: unsubmitted QC, orphan rows, cap dead end, negative net) ----
    private val maxrQc = QcEntry(100, "damaged_crushed", "MFC", 5)

    @Test fun qcThatWasNeverSubmittedDoesNotSurviveARelaunch() = runTest {
        val store = MemStore(); val v = visit(); val a = flow(store); a.start(v)
        a.setQuantity(100, 20, QtyUnit.STICK); a.setQc(maxrQc)
        val relaunched = flow(store); relaunched.start(v)
        assertEquals(emptyList<QcEntry>(), relaunched.state.value.draft!!.qc)
        assertEquals(0, relaunched.state.value.review!!.totals.qcDeductionMtk)
    }

    @Test fun submittedQcSurvivesARelaunchButRowsOfUnknownTypesDoNot() = runTest {
        val store = MemStore(); val v = visit(); val a = flow(store); a.start(v)
        a.setQuantity(100, 20, QtyUnit.STICK); a.setQc(maxrQc); a.completeQc()
        assertEquals(true, a.state.value.draft!!.qcCompleted)
        // a draft written by an older build: a row with the old code and group
        store.save(store.load(v.visitUuid)!!.copy(qc = listOf(maxrQc, QcEntry(100, "production_fault", "TFC", 3))))
        val relaunched = flow(store); relaunched.start(v)
        assertEquals(listOf(maxrQc), relaunched.state.value.draft!!.qc)
    }

    @Test fun removingASkuLineRemovesItsQcRows() = runTest {
        val store = MemStore(); val v = visit(); val f = flow(store); f.start(v)
        f.setQuantity(100, 20, QtyUnit.STICK); f.setQuantity(103, 3, QtyUnit.PIECE)
        f.setQc(maxrQc); f.setQc(QcEntry(103, "taste", "MKT", 1))
        f.setQuantity(100, 0, QtyUnit.STICK)
        assertEquals(listOf(103L), f.state.value.draft!!.qc.map { it.skuId })
    }

    @Test fun cancelRestoresTheQcRowsFromBeforeTheScreenOpened() = runTest {
        val store = MemStore(); val v = visit(); val f = flow(store); f.start(v)
        f.setQuantity(100, 20, QtyUnit.STICK); f.setQc(maxrQc)
        val before = f.state.value.draft!!.qc
        f.setQc(QcEntry(100, "taste", "MKT", 7)); f.setQc(QcEntry(100, "damaged_crushed", "MFC", 9))
        f.restoreQc(before)
        assertEquals(before, f.state.value.draft!!.qc)
        assertEquals(5L * 8_000, f.state.value.review!!.totals.qcDeductionMtk)
    }

    @Test fun completeQcRefusesWhileTheReviewWouldRefuseIt() = runTest {
        val store = MemStore(); val v = visit()
        // one stick sold at 8.00 and 100 sticks of QC: the net would be negative, which the server rejects
        val f = flow(store); f.start(v)
        f.setQuantity(100, 1, QtyUnit.STICK); f.setQc(QcEntry(100, "damaged_crushed", "MFC", 100))
        assertEquals(true, f.state.value.review!!.problems.any { it is ReviewProblem.NetNegative })
        f.completeQc()
        assertEquals("a QC that makes the net negative is never locked in", false, f.state.value.draft!!.qcCompleted)
        assertEquals(false, f.state.value.review!!.canCommit)
        f.restoreQc(emptyList()); f.setQc(QcEntry(100, "damaged_crushed", "MFC", 1)); f.completeQc() // 1 stick of 1: net 0 is allowed
        assertEquals(true, f.state.value.draft!!.qcCompleted)
    }

    @Test fun completeQcRefusesAboveTheCapAndTheCapIsOneSourceInTheState() = runTest {
        val store = MemStore(); val v = visit()
        val f = SaleFlow(store, { _, _ -> Fx.catalog.values.toList() }, committer(), qcCapMtkBySku = { mapOf(100L to 24_000L) })
        f.start(v)
        f.setQuantity(100, 20, QtyUnit.STICK); f.setQc(QcEntry(100, "damaged_crushed", "MFC", 4)) // 32.00 above a 24.00 cap
        assertEquals(mapOf(100L to 24_000L), f.state.value.qcCapMtkBySku)
        f.completeQc()
        assertEquals(false, f.state.value.draft!!.qcCompleted)
    }
}
