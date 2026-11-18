package com.aktcl.aron.feature.sale.ui

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import com.aktcl.aron.feature.sale.domain.DraftStore
import com.aktcl.aron.feature.sale.domain.FakeNumbers
import com.aktcl.aron.feature.sale.domain.Fx
import com.aktcl.aron.feature.sale.domain.SaleCommitter
import com.aktcl.aron.feature.sale.domain.SaleDraft
import com.aktcl.aron.feature.sale.domain.SaleFlow
import com.aktcl.aron.feature.sale.domain.SaleNotice
import com.aktcl.aron.feature.sale.domain.SaleVisit
import com.aktcl.aron.rules.QtyUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private class VmMemStore : DraftStore {
    val map = HashMap<String, SaleDraft>()
    override fun load(visitUuid: String) = map[visitUuid]
    override fun save(draft: SaleDraft) { map[draft.visitUuid] = draft }
    override fun clear(visitUuid: String) { map.remove(visitUuid) }
}

/**
 * P-SR1b (lead, lows of the locked-sale review): the view model's net, at view-model level. A change the DRAFT refuses becomes the "Refused" notice (nothing written); any other
 * failure still reaches the uncaught-exception handler, so a real defect (a missing sale, Room) is never worded as "that change was not accepted".
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SaleViewModelGuardTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    @After fun tearDown() { scope.cancel(); db.close() }

    private fun committer() = SaleCommitter(CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }, FakeNumbers(), { _, r -> Fx.meta().copy(routeId = r) }, { "2026-10-05T04:35:00.000Z" }, findMemo = { db.captureDao().memo(it) })
    private fun vmOf(flow: SaleFlow): SaleViewModel {
        val meta = CaptureMeta("2026-10-05", "2026-10-05T04:31:07.120Z", 1, 1, 0, true, null, null, "b", false, 1)
        val printing = MemoPrinting(
            PrinterManager({ error("no printer here") }, object : SavedPrinterStore {
                override fun load(): SavedPrinter? = null
                override fun save(printer: SavedPrinter?) = Unit
            }, scope),
            { error("nothing renders") }, RoomPrintLedger(db, { it ?: meta }, { "2026-10-05T04:31:07.120Z" }), ClientIds::newUuid, { 0L },
        )
        return SaleViewModel(flow, SaveAndPrint({ error("not used") }, printing))
    }
    private fun flow() = SaleFlow(VmMemStore(), { _, _ -> Fx.catalog.values.toList() }, committer())
    private suspend fun open(f: SaleFlow) {
        val u = ClientIds.newUuid(); val (v, fix) = Fx.visit(u)
        val repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }
        repo.recordVisitOpen(v, fix); Fx.seedStock(repo)
        f.start(SaleVisit(u, 50001, 10231, Fx.DATE, "outlet"))
        f.setQuantity(100, 20, QtyUnit.STICK)
    }

    @Test fun aChangeTheDraftRefusesBecomesTheRefusedNoticeAndNothingIsWritten() {
        val f = flow(); runBlocking { open(f) }; val vm = vmOf(f); val before = f.state.value.draft
        vm.setPaid(-5)  // a negative payment: the draft's own check refuses it
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SaleNotice.Refused, f.state.value.notice)
        assertEquals(before, f.state.value.draft)
    }

    @Test fun anyOtherFailureIsRethrownByTheHandlerSoItStillCrashesAndIsNotWordedAsARefusal() {
        val f = flow(); runBlocking { open(f) }
        val handler = refusalGuard(f)
        val ctx = kotlin.coroutines.EmptyCoroutineContext
        // a defect (a missing sale, Room, a serialization failure) is not a draft refusal: the handler rethrows it, so it reaches the process's uncaught handler as before
        val thrown = runCatching { handler.handleException(ctx, IllegalStateException("no sale in progress")) }.exceptionOrNull()
        assertTrue("rethrown unchanged", thrown is IllegalStateException && thrown.message == "no sale in progress")
        assertEquals("and not turned into a notice", null, f.state.value.notice)
        handler.handleException(ctx, com.aktcl.aron.feature.sale.domain.DraftRefusedException("out of range"))
        assertEquals(SaleNotice.Refused, f.state.value.notice)
    }

    /** Every launch of the view model that writes the draft goes through the one net; `start` has none (a start failure must crash, not become a notice). Source-level, so a new launch added without the net is caught. */
    @Test fun everyGuardedLaunchUsesTheNetAndStartHasNone() {
        val src = java.io.File("src/main/kotlin/com/aktcl/aron/feature/sale/ui/SaleViewModel.kt").readText()
        assertTrue("the net is refusalGuard(flow)", src.contains("private val guard = refusalGuard(flow)"))
        val launches = src.lines().filter { it.contains("viewModelScope.launch") && !it.trimStart().startsWith("//") && !it.trimStart().startsWith("*") && !it.contains("fun viewModelScopeForDialogs") }
        assertTrue("there are launches", launches.size >= 8)
        val (start, others) = launches.partition { it.contains("fun start(") }
        assertEquals(1, start.size)
        assertTrue("start() has no net: ${start[0]}", !start[0].contains("launch(guard)"))
        others.forEach { assertTrue("a launch without the net: $it", it.contains("launch(guard)")) }
    }
}
