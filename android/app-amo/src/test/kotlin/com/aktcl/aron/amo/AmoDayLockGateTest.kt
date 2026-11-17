package com.aktcl.aron.amo

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.BundleSectionEntity
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.DueCollectionEntity
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.VisitEntity
import com.aktcl.aron.core.database.entity.VisitSkipEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.FinalSubmitLock
import com.aktcl.aron.core.database.repo.LockAcks
import com.aktcl.aron.core.database.repo.MemoNumbering
import com.aktcl.aron.core.database.repo.RoomPrintLedger
import com.aktcl.aron.core.database.repo.RouteDayFinalSubmittedException
import com.aktcl.aron.core.printing.bt.PrinterManager
import com.aktcl.aron.core.printing.bt.SavedPrinter
import com.aktcl.aron.core.printing.bt.SavedPrinterStore
import com.aktcl.aron.core.printing.flow.MemoPrinting
import com.aktcl.aron.core.printing.flow.SaveAndPrint
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.feature.memo.domain.PrintMapping
import com.aktcl.aron.feature.memo.domain.PrintNames
import com.aktcl.aron.feature.memo.ui.RoomMemoStore
import com.aktcl.aron.feature.outlet.OpenVisit
import com.aktcl.aron.feature.outlet.VisitSession
import com.aktcl.aron.feature.sale.domain.FileDraftStore
import com.aktcl.aron.feature.sale.domain.MemoPrintSource
import com.aktcl.aron.feature.sale.domain.SaleCommitStep
import com.aktcl.aron.feature.sale.domain.SaleCommitter
import com.aktcl.aron.feature.sale.domain.SaleFlow
import com.aktcl.aron.feature.sale.domain.SaleSku
import com.aktcl.aron.feature.sale.domain.SaleVisit
import com.aktcl.aron.feature.sale.domain.VisitCloser
import com.aktcl.aron.feature.sale.domain.VisitOutcomeCode
import com.aktcl.aron.feature.sale.domain.VisitToClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * tso-manual X4a (R-W-11, A3 asks 1 and 11, D-TSO-MAN-31): the AMO's control, joint and sale calls on a route closed by its
 * zone's Final Submit, through the production pieces the AMO kit wires ([AmoLockGate], SaleFlow and SaleCommitter with
 * memo numbering, [VisitCloser]) on a real database: a sale saved in a call open when the lock arrived is refused before
 * anything is stored or printed, a close or a skip writes nothing and never throws, a collection on the closed route is
 * refused, and another route of the zone stays open. The Home banner and the refusal text are checked through
 * [AmoDayLockWords], the words [amoDayLockBanners] and [AmoDayLockDialog] draw.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AmoDayLockGateTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    /** CI run 41750277921: the printer scope and Room's executor of this class stop before the database closes. */
    private val work = TestWork()
    private val db = Room.inMemoryDatabaseBuilder(app, AronDatabase::class.java).allowMainThreadQueries()
        .setQueryExecutor(work.roomExecutor).setTransactionExecutor(work.roomExecutor).build()
    private val date = "2026-10-26"
    private val route = 10231L
    private val other = 10232L
    private val now = "2026-10-26T12:00:00.000Z"
    private val repo = CaptureRepository(db) { now }
    private val printerScope = work.printerScope

    @Before fun lockingPolicy() {
        // owner decision 2026-11-04: the phone locks only under quarantine or reject (default accept_and_flag); this class tests the lock
        latePolicyOn(db, "quarantine")
    }

    @After fun tearDown() {
        try {
            work.stop() // the printer manager's scope cancelled and joined, Room drained
            db.close()
        } finally {
            work.roomExecutor.shutdown()
        }
    }

    private fun meta(routeId: Long? = route) = CaptureMeta(
        businessDate = date, capturedAt = now, capturedElapsedMs = 1_000, bootCount = 3, clockOffsetMs = 0L, capturedOffline = true,
        routeId = routeId, bundleVersion = "$date:1", configVersion = 1L,
    )

    private fun finalSubmitted(routeId: Long = route) = runBlocking {
        db.referenceDao().insertSections(listOf(BundleSectionEntity("route.$routeId",
            """{"route_id":$routeId,"targets":[],"day_state":{"route_id":$routeId,"business_date":"$date","state":"final_submitted","submit_cycle":0,"submit_voided":false,"final_submitted_at":"2026-10-26T11:30:00.000Z"}}""")))
    }

    private fun controlCall(routeId: Long = route, seq: Int = 1): OpenVisit = runBlocking {
        val u = ClientIds.newUuid()
        val f = GeoFixEntity(
            ClientIds.newUuid(), u, "visit_open", "ok", 23.79, 90.40, 8.0, provider = "fused", fixTime = now, fixElapsedRealtimeMs = 900, fixAgeMs = 10,
            timeToFixMs = 1000, requestPriority = "balanced", isMock = false, reused = false, refreshCount = 0, deviceOwner = true, devOptionsEnabled = false,
            adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false,
        )
        repo.recordVisitOpen(
            VisitEntity(
                clientUuid = u, meta = meta(routeId), visitKind = "amo_control_call", outletId = 50001, openedAt = now, sequenceNo = seq, planned = true,
                fixClientUuid = f.clientUuid, geoVerdict = "in_range", geoDistanceM = 10.0, geoRadiusMUsed = 100, geoMaxAccuracyMUsed = 50,
                geoLocationBasis = "master", geoOutletLat = 23.79, geoOutletLng = 90.40, geoAction = "sale_allowed",
            ),
            f,
        )
        OpenVisit(u, 50001, routeId, "in_range", "sale_allowed", true, false, null, now)
    }

    @Volatile private var loads = 0
    private val sku = SaleSku(1, "MAXR20", "cigarette", "MaxR 20S", null, "stick", 20, 7_935, 1, "2026-09-01", entryUnitDefault = "pack", stockBase = 200)

    /** The stock gate (D-OWNER-PREREQ-01 rule 2, AMO as well as SR): the day's issue of [sku] before any sale. */
    private fun issueStock(routeId: Long? = route) = runBlocking {
        repo.recordStock(
            listOf(
                com.aktcl.aron.core.database.entity.StockMovementEntity(
                    clientUuid = ClientIds.newUuid(), meta = meta(routeId), kind = "issue", skuId = sku.skuId, qtyEntered = 10,
                    unitEntered = "pack", packFactor = 20, qtyBase = 200, slipPrinted = false,
                ),
            ),
        )
    }

    /** The AMO kit's Save and Print on a draft of two packets on [v] (AmoSaleKit.newSaleViewModel's chain). */
    private fun saveAndPrint(v: OpenVisit, refused: MutableStateFlow<Boolean>): SaveAndPrint = runBlocking {
        val committer = SaleCommitter(
            repo, numbers = { _, _ -> error("numbered through MemoNumbering") }, metaSource = { _, rid -> meta(rid) }, nowIso = { now },
            findMemo = { db.captureDao().memo(it) }, numbering = MemoNumbering("amo334001", 0, 500),
        )
        runCatching { issueStock() } // after the zone's Final Submit a stock issue is refused too; the sale below is what this test drives
        val flow = SaleFlow(FileDraftStore(File(app.cacheDir, "x4a-amo-drafts-" + ClientIds.newUuid())), { _, _ -> listOf(sku) }, committer)
        flow.start(SaleVisit(v.visitUuid, v.outletId, v.routeId, date, "outlet"))
        flow.setQuantity(sku.skuId, 2, sku.entry.unit)
        val printing = MemoPrinting(
            PrinterManager({ error("nothing prints in this test") }, object : SavedPrinterStore {
                override fun load(): SavedPrinter? = null
                override fun save(printer: SavedPrinter?) = Unit
            }, printerScope),
            { error("nothing is rendered in this test") }, RoomPrintLedger(db, { it ?: meta(null) }, { now }), ClientIds::newUuid, { 0L },
        )
        // as AmoSaleKit's printSource: the stored memo through the production memo store; counted, so a refused sale shows none
        val source = MemoPrintSource { uuid -> loads++; PrintMapping.memo(RoomMemoStore(db).memos(date).first { it.memoUuid == uuid }, PrintNames({ "S$it" }, { "O$it" }, "amo", "r")) }
        AmoLockGate.saveAndPrint(SaleCommitStep(flow, source)::invoke, printing) { refused.value = true }
    }

    @Test fun aSaleSavedAfterTheLockArrivedIsRefusedAndAnotherRouteStillSells() {
        val locked = controlCall(route, seq = 1)
        val open = controlCall(other, seq = 2)
        val refused = MutableStateFlow(false)
        val onLocked = saveAndPrint(locked, refused)
        finalSubmitted(route) // the lock reaches the phone while the call is open
        runBlocking { onLocked.onPrintTapped(); onLocked.onSaveAnswer(true) }
        assertEquals(SaveAndPrint.Step.SaveFailed, onLocked.step.value)
        assertTrue("the host shows the lock text", refused.value)
        assertNull(onLocked.memo)
        assertEquals("nothing was loaded for print", 0, loads)
        runBlocking {
            assertTrue(db.captureDao().memosOn(date).isEmpty())
            assertNull("no memo number taken", db.referenceDao().memoCounter(date))
        }
        // the zone's other route is not closed: its sale saves and asks to print
        val refusedOther = MutableStateFlow(false)
        val onOther = saveAndPrint(open, refusedOther)
        runBlocking { onOther.onPrintTapped(); onOther.onSaveAnswer(true) }
        assertEquals(SaveAndPrint.Step.AskPrint, onOther.step.value)
        assertFalse(refusedOther.value)
        assertEquals(1, runBlocking { db.captureDao().memosOn(date) }.size)
    }

    @Test fun aCloseOrSkipOnTheClosedRouteWritesNothingAndNeverThrows() {
        val v = controlCall()
        val session = VisitSession().also { it.restore(v) }
        finalSubmitted()
        // AmoDay.closeVisit's own check (A3's AmoCallLock) ends the call first ...
        assertTrue(runBlocking { AmoCallLock.endsOnPhone(repo, VisitSession().also { it.restore(v) }, meta()) })
        // ... and a lock that lands after that check ends it in the write (AmoSaleKit.closeVisit, AmoDay.closeAbandoned)
        val closer = VisitCloser(repo, { _, rid -> meta(rid) }, { now })
        assertFalse(runBlocking { AmoLockGate.closeCall(session) { o -> closer.close(VisitToClose(o.visitUuid, o.routeId, null), VisitOutcomeCode.SOLD, date) } })
        assertNull(session.current.value)
        assertTrue(runBlocking { db.captureDao().visitClosesOn(date) }.isEmpty())
        assertFalse(runBlocking { AmoLockGate.skip { repo.recordVisitSkip(VisitSkipEntity(ClientIds.newUuid(), meta(), 50002, VisitOutcomeCode.CLOSED.wire)) } })
        assertTrue(runBlocking { db.captureDao().visitSkipsOn(date) }.isEmpty())
        // a relaunch does not restore the call
        assertNull(runBlocking { FinalSubmitLock.callToRestore(db, date) { it.visitKind.startsWith("amo_") } })
    }

    /**
     * The repository's side of Mark Paid: a collection that carries the closed route is refused, a routeless one is not.
     * Fix round: this does not prove the AMO screen's wiring (the kit now collects on the memo's call route through
     * VisitDueCollectionWriter); AmoKitLockTest proves that on a real AmoDay.
     */
    @Test fun aCollectionOnTheClosedRouteIsRefusedAndARoutelessOneIsNot() {
        finalSubmitted()
        assertTrue(runBlocking { FinalSubmitLock.refuses(db, meta(route), "due_collection") })
        assertFalse("the lock never applies to a routeless record", runBlocking { FinalSubmitLock.refuses(db, meta(null), "due_collection") })
        assertFalse(runBlocking { repo.acceptsDueCollection(meta(route)) })
        assertTrue(runBlocking { repo.acceptsDueCollection(meta(null)) })
        fun due(m: CaptureMeta) = DueCollectionEntity(ClientIds.newUuid(), m, 50001, ClientIds.newUuid(), "amo334001-261026-001", date, 50_000, false, 120_000)
        val refused = due(meta(route))
        assertThrows(RouteDayFinalSubmittedException::class.java) { runBlocking { repo.recordDueCollection(refused) } }
        assertNull(runBlocking { db.outboxDao().byClientUuid(refused.clientUuid) })
        val routeless = due(meta(null))
        runBlocking { repo.recordDueCollection(routeless) } // the server never locks a routeless collection
        assertNotNull(runBlocking { db.outboxDao().byClientUuid(routeless.clientUuid) })
    }

    @Test fun theHomeBannerWordsInEnglishAndBangla() {
        val en = AppLocale.wrap(app, AppLanguage.EN).resources
        val bn = AppLocale.wrap(app, AppLanguage.BN).resources
        val lock = AmoDayLock(closedRouteIds = listOf(route, other), acks = LockAcks(held = 3, refused = 1))
        val text = AmoDayLockWords.text(lock, en, AppLanguage.EN)!!
        assertTrue(text, text.startsWith("Routes closed by Final Submit\nFinal Submit is done today for 2 of your zone's routes."))
        assertTrue(text, text.contains("Printing still works."))
        assertTrue(text, text.contains("3 records from before the Final Submit are held for review"))
        assertTrue(text, text.endsWith("1 records made after the Final Submit were not accepted."))
        val bangla = AmoDayLockWords.text(lock, bn, AppLanguage.BN)!!
        assertTrue(bangla, bangla.startsWith("ফাইনাল সাবমিটে রুট বন্ধ"))
        assertTrue(bangla, bangla.contains("২টি রুটের ফাইনাল সাবমিট হয়েছে"))
        assertTrue(bangla, bangla.contains("প্রিন্ট করা যাবে।"))
        // the Home gets one Warning banner while a route is closed, and none while every route is open
        val banners = AmoDayLockWords.banners(text)
        assertEquals(1, banners.size); assertEquals(BannerKind.Warning, banners.single().kind); assertEquals(text, banners.single().text)
        assertNull(AmoDayLockWords.text(null, en, AppLanguage.EN))
        assertTrue(AmoDayLockWords.banners(AmoDayLockWords.text(null, en, AppLanguage.EN)).isEmpty())
        // only refused records (no route closed any more after a reopen): no title, just the line
        assertEquals("2 records made after the Final Submit were not accepted.", AmoDayLockWords.text(AmoDayLock(emptyList(), LockAcks(0, 2)), en, AppLanguage.EN))
    }

    @Test fun theRefusalTextCountsAtLeastTheRefusedRoute() {
        val en = AppLocale.wrap(app, AppLanguage.EN).resources
        val (title, body) = AmoDayLockWords.refusal(0, en, AppLanguage.EN)
        assertEquals("Routes closed by Final Submit", title)
        assertTrue(body, body.startsWith("Final Submit is done today for 1 of your zone's routes."))
        assertTrue(body, body.contains("sales, stock entries, memo edits and collections on them are not accepted"))
        val (bnTitle, bnBody) = AmoDayLockWords.refusal(3, AppLocale.wrap(app, AppLanguage.BN).resources, AppLanguage.BN)
        assertEquals("ফাইনাল সাবমিটে রুট বন্ধ", bnTitle)
        assertTrue(bnBody, bnBody.contains("৩টি রুটের"))
    }
}
