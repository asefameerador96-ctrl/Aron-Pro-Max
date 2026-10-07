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
}
