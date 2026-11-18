package com.aktcl.aron.feature.sale.ui

import android.content.Context
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.RoomPrintLedger
import com.aktcl.aron.core.printing.bt.PrinterManager
import com.aktcl.aron.core.printing.bt.SavedPrinter
import com.aktcl.aron.core.printing.bt.SavedPrinterStore
import com.aktcl.aron.core.printing.flow.MemoPrinting
import com.aktcl.aron.core.printing.flow.SaveAndPrint
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.feature.sale.R
import com.aktcl.aron.feature.sale.domain.DraftStore
import com.aktcl.aron.feature.sale.domain.FakeNumbers
import com.aktcl.aron.feature.sale.domain.Fx
import com.aktcl.aron.feature.sale.domain.SaleCommitter
import com.aktcl.aron.feature.sale.domain.SaleDraft
import com.aktcl.aron.feature.sale.domain.SaleFlow
import com.aktcl.aron.feature.sale.domain.SaleVisit
import com.aktcl.aron.rules.QtyUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private class LaterMemStore : DraftStore {
    val map = HashMap<String, SaleDraft>()
    override fun load(visitUuid: String) = map[visitUuid]
    override fun save(draft: SaleDraft) { map[draft.visitUuid] = draft }
    override fun clear(visitUuid: String) { map.remove(visitUuid) }
}

/** P-SR1b (lead low 3): the "later changes were not saved" box, once closed, does not come back after a rotation (the saved state is dropped and restored). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SaleLaterDialogRestoreTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    @After fun tearDown() { scope.cancel(); db.close() }

    @Test fun aClosedLaterChangesBoxDoesNotComeBackAfterARestoration() {
        val repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }
        val committer = SaleCommitter(repo, FakeNumbers(), { _, r -> Fx.meta().copy(routeId = r) }, { "2026-10-05T04:35:00.000Z" }, findMemo = { db.captureDao().memo(it) })
        val flow = SaleFlow(LaterMemStore(), { _, _ -> Fx.catalog.values.toList() }, committer)
        runBlocking {
            val u = ClientIds.newUuid(); val (v, fix) = Fx.visit(u)
            repo.recordVisitOpen(v, fix); Fx.seedStock(repo)
            flow.start(SaleVisit(u, 50001, 10231, Fx.DATE, "outlet")); flow.setQuantity(100, 5, QtyUnit.STICK)
            val s = flow.state.value
            committer.commit(s.draft!!, s.catalog, null, emptyMap()) // the Save reached Room; the flow never heard of it
            flow.setQuantity(100, 6, QtyUnit.STICK)                  // the rep kept editing
            flow.commit()                                            // the replay answers with the stored memo and says the later changes were not saved
        }
        assertEquals(true, flow.state.value.laterChangesNotSaved)
        val meta = CaptureMeta("2026-10-05", "2026-10-05T04:31:07.120Z", 1, 1, 0, true, null, null, "b", false, 1)
        val manager = PrinterManager({ error("no printer here") }, object : SavedPrinterStore {
            override fun load(): SavedPrinter? = null
            override fun save(printer: SavedPrinter?) = Unit
        }, scope)
        val printing = MemoPrinting(manager, { error("nothing renders") }, RoomPrintLedger(db, { it ?: meta }, { "2026-10-05T04:31:07.120Z" }), ClientIds::newUuid, { 0L })
        val vm = SaleViewModel(flow, SaveAndPrint({ error("not used") }, printing))
        val body = context.getString(R.string.review_later_body)
        val ok = context.getString(R.string.sale_yes_ok)
        val tester = StateRestorationTester(compose)
        tester.setContent { AronTheme(AppLanguage.EN) { SaleRoute(vm, manager, { it.toString() }, onFinished = {}) } }
        compose.waitForIdle()
        compose.onNodeWithText(body).assertExists()
        compose.onNodeWithText(ok).performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText(body).let { assertEquals(0, it.fetchSemanticsNodes().size) }
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals("a box the rep closed stays closed", 0, compose.onAllNodesWithText(body).fetchSemanticsNodes().size)
    }
}
