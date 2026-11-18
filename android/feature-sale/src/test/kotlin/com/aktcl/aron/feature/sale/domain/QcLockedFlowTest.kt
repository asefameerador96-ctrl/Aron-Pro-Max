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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private class LockMemStore : DraftStore {
    val map = HashMap<String, SaleDraft>()
    override fun load(visitUuid: String) = map[visitUuid]
    override fun save(draft: SaleDraft) { map[draft.visitUuid] = draft }
    override fun clear(visitUuid: String) { map.remove(visitUuid) }
}

/**
 * P-SR1b (lead's QC check, B1): once QC is submitted the screens may still call the QC writers (a tap on বাতিল of a locked page, step 1's বাতিল on the one-scroll or the
 * two-step screen). The flow ignores them: no exception into the view model's coroutine, the draft unchanged. The draft ops themselves still refuse (their own tests).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class QcLockedFlowTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    @After fun tearDown() = db.close()
    private val numbers = FakeNumbers()
    private fun committer() = SaleCommitter(CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }, numbers, { _, r -> Fx.meta().copy(routeId = r) }, { "2026-10-05T04:35:00.000Z" }, findMemo = { db.captureDao().memo(it) })
    private fun flow(store: DraftStore) = SaleFlow(store, { _, _ -> Fx.catalog.values.toList() }, committer())
    private suspend fun visit(): SaleVisit {
        val u = ClientIds.newUuid(); val (v, f) = Fx.visit(u)
        val repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }
        repo.recordVisitOpen(v, f)
        Fx.seedStock(repo)
        return SaleVisit(u, 50001, 10231, Fx.DATE, "outlet")
    }
    private val qc = QcEntry(100, "damaged_crushed", "MFC", 1)

    private suspend fun lockedFlow(): SaleFlow {
        val f = flow(LockMemStore()); f.start(visit())
        f.setQuantity(100, 20, QtyUnit.STICK); f.setQc(qc); f.completeQc()
        assertEquals(true, f.state.value.draft!!.qcCompleted)
        return f
    }

    @Test fun aQcWriteAfterSubmitIsIgnoredAndTheDraftIsUnchanged() = runTest {
        val f = lockedFlow(); val before = f.state.value.draft
        f.setQc(QcEntry(100, "taste", "MKT", 3))
        assertEquals(before, f.state.value.draft)
    }

    @Test fun restoreQcAfterSubmitIsIgnoredAndTheDraftIsUnchanged() = runTest {
        val f = lockedFlow(); val before = f.state.value.draft
        f.restoreQc(emptyList())
        assertEquals(before, f.state.value.draft)
        assertEquals(listOf(qc), f.state.value.draft!!.qc)
    }

    @Test fun cancelQcOnlyAfterSubmitIsIgnoredAndTheDraftIsUnchanged() = runTest {
        val f = lockedFlow(); val before = f.state.value.draft
        f.cancelQcOnly()
        assertEquals(before, f.state.value.draft)
    }

    @Test fun beforeSubmitTheWritersStillWork() = runTest {
        val f = flow(LockMemStore()); f.start(visit())
        f.setQuantity(100, 20, QtyUnit.STICK); f.setQc(qc)
        f.restoreQc(emptyList())
        assertEquals(emptyList<QcEntry>(), f.state.value.draft!!.qc)
    }

    // ---- the lead's locked-sale crash: a screen that is back on the entry list (rotation, relaunch) still sends quantity and slide changes ----
    @Test fun aQuantityChangeOnALockedSaleIsRefusedWithANoticeAndNothingThrows() = runTest {
        val f = lockedFlow(); val before = f.state.value.draft
        val set = f.setQuantity(100, 5, QtyUnit.STICK)
        assertEquals(before, f.state.value.draft)
        assertEquals(SaleNotice.Locked, f.state.value.notice)
        assertEquals("the figure the screen shows is the draft's, not the refused one", 20L, f.state.value.draft!!.lines.single().qtyEntered)
        assertEquals(5L, set) // the refused call reports what it was asked to set; the draft above is the truth
    }

    @Test fun aSlideChangeOnALockedSaleIsRefusedWithANotice() = runTest {
        val f = lockedFlow(); val before = f.state.value.draft
        f.setSlide(100, 3)
        assertEquals(before, f.state.value.draft)
        assertEquals(SaleNotice.Locked, f.state.value.notice)
    }

    @Test fun anAcceptedChangeClearsTheNotice() = runTest {
        val f = flow(LockMemStore()); f.start(visit())
        f.setQuantity(100, 20, QtyUnit.STICK); f.noteRefusal()
        assertEquals(SaleNotice.Refused, f.state.value.notice)
        f.setQuantity(100, 21, QtyUnit.STICK)
        assertEquals(null, f.state.value.notice)
    }

    @Test fun aRefusalThatReachesTheViewModelNetIsLockedOnALockedDraftAndRefusedOtherwise() = runTest {
        val f = lockedFlow(); f.noteRefusal()
        assertEquals(SaleNotice.Locked, f.state.value.notice)
    }

    @Test fun typedQcThatWasNeverSubmittedIsDroppedAtStartWithANotice() = runTest {
        val store = LockMemStore(); val v = visit(); val a = flow(store); a.start(v)
        a.setQuantity(100, 20, QtyUnit.STICK); a.setQc(qc)
        val b = flow(store); b.start(v)
        assertEquals(emptyList<QcEntry>(), b.state.value.draft!!.qc)
        assertEquals(SaleNotice.QcDropped, b.state.value.notice)
        b.start(v) // a second start (a rotation) keeps the same notice until the next change
        assertEquals(SaleNotice.QcDropped, b.state.value.notice)
    }

    @Test fun noNoticeWhenNothingWasDropped() = runTest {
        val store = LockMemStore(); val v = visit(); val a = flow(store); a.start(v)
        a.setQuantity(100, 20, QtyUnit.STICK); a.setQc(qc); a.completeQc()
        val b = flow(store); b.start(v)
        assertEquals(null, b.state.value.notice)
    }
}
