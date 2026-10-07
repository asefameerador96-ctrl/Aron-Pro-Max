package com.aktcl.aron.feature.sale.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.printing.doc.MemoPrint
import com.aktcl.aron.rules.QtyUnit
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SaleSaveAndPrintTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    @After fun tearDown() = db.close()

    private class Mem : DraftStore {
        val m = HashMap<String, SaleDraft>()
        override fun load(visitUuid: String) = m[visitUuid]
        override fun save(draft: SaleDraft) { m[draft.visitUuid] = draft }
        override fun clear(visitUuid: String) { m.remove(visitUuid) }
    }

    @Test fun theCommitStepSavesTheMemoWithoutAnyPrinterAndReturnsItsPrintModelOnce() = runTest {
        val repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }
        val visitUuid = ClientIds.newUuid(); val (v, f) = Fx.visit(visitUuid); repo.recordVisitOpen(v, f)
        val committer = SaleCommitter(repo, FakeNumbers(), { _, r -> Fx.meta().copy(routeId = r) }, { "2026-10-05T04:35:00.000Z" }, findMemo = { db.captureDao().memo(it) })
        val flow = SaleFlow(Mem(), { _, _ -> Fx.catalog.values.toList() }, committer)
        flow.start(SaleVisit(visitUuid, 50001, 10231, Fx.DATE, "outlet"))
        flow.setQuantity(100, 20, QtyUnit.STICK)
        var loads = 0
        val step = SaleCommitStep(flow, { uuid ->
            loads++
            val m = db.captureDao().memo(uuid)!!
            MemoPrint("cash_memo", m.memoNo, 0L, "Outlet", "sr", "route", emptyList(), emptyList(), m.grossMtk, 0, 0, 0, m.roundAdjMtk, m.netMtk, m.paidMtk, m.dueMtk, false)
        })
        val a = step(); val b = step() // a second tap after "save failed" returns the same memo
        assertEquals(a.memoClientUuid, b.memoClientUuid)
        assertEquals(1, db.captureDao().memosOn(Fx.DATE).size)
        assertEquals(160_000, a.print.netMtk)
        assertEquals(2, loads)
    }
}
