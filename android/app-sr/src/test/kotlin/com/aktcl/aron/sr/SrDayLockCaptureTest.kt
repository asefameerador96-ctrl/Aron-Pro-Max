package com.aktcl.aron.sr

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.contract.SyncTrigger
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.UserDatabases
import com.aktcl.aron.core.database.entity.BundleSectionEntity
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.VisitEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.FinalSubmitLock
import com.aktcl.aron.core.database.repo.RouteDayFinalSubmittedException
import com.aktcl.aron.core.printing.flow.PrintAttempt
import com.aktcl.aron.core.sync.SyncScheduler
import com.aktcl.aron.feature.dayclose.DaySource
import com.aktcl.aron.feature.dayclose.DuesAtSubmit
import com.aktcl.aron.feature.dayclose.OutboxStates
import com.aktcl.aron.feature.dayclose.RoomDaySubmitWriter
import com.aktcl.aron.feature.dayclose.SalesSubmitViewModel
import com.aktcl.aron.feature.memo.domain.MemoItem
import com.aktcl.aron.feature.memo.domain.PrintNames
import com.aktcl.aron.feature.memo.domain.StoredCollection
import com.aktcl.aron.feature.memo.domain.StoredMemo
import com.aktcl.aron.feature.memo.ui.MemoReprinter
import com.aktcl.aron.feature.memo.ui.MemoStore
import com.aktcl.aron.feature.memo.ui.MemoViewModel
import com.aktcl.aron.feature.memo.ui.RoomDueCollectionWriter
import com.aktcl.aron.feature.outlet.OpenVisit
import com.aktcl.aron.feature.outlet.VisitSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * tso-manual A3, checker fix (high): the Final Submit lock never turns into a crash on the SR phone. The zone's Final
 * Submit reaches the phone (the route section's day_state says `final_submitted`) while the SR is mid-visit, has not done
 * Sales Submit (the TSO used "Submit anyway") or opens a credit memo: leaving the call, Sales Submit and Mark Paid run
 * through the production writers on a real database and none throws out of the view model (D-TSO-MAN-31, D-A3-22).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SrDayLockCaptureTest {
    private val user = 21L
    private val date = "2026-10-26"
    private val route = 10231L
    private val now = "2026-10-26T12:00:00.000Z"
    private lateinit var databases: UserDatabases
    private lateinit var db: AronDatabase
    private lateinit var repo: CaptureRepository
    private val crashes: MutableList<Throwable> = Collections.synchronizedList(ArrayList())
    private var previousDefault: Thread.UncaughtExceptionHandler? = null
    private var previousOwn: Thread.UncaughtExceptionHandler? = null

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        databases = UserDatabases(ApplicationProvider.getApplicationContext<Application>()) { null }
        db = runBlocking { databases.of(user) }
        repo = CaptureRepository(db) { now }
        // an exception that leaves a view model's coroutine ends here (on a phone it would kill the process)
        val catcher = Thread.UncaughtExceptionHandler { _, e -> crashes += e }
        previousDefault = Thread.getDefaultUncaughtExceptionHandler(); Thread.setDefaultUncaughtExceptionHandler(catcher)
        previousOwn = Thread.currentThread().uncaughtExceptionHandler; Thread.currentThread().uncaughtExceptionHandler = catcher
    }

    @After fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousDefault); Thread.currentThread().uncaughtExceptionHandler = previousOwn
        databases.close(user)
        Dispatchers.resetMain()
    }

    private fun meta(routeId: Long? = route) = CaptureMeta(
        businessDate = date, capturedAt = now, capturedElapsedMs = 1_000, bootCount = 3, clockOffsetMs = 0L, capturedOffline = true,
        routeId = routeId, bundleVersion = "$date:1", configVersion = 1L,
    )

    /** The zone's Final Submit as the phone learns it: the delta's route section with day_state final_submitted. */
    private fun finalSubmitted() = runBlocking {
        db.referenceDao().insertSections(listOf(BundleSectionEntity("route.$route",
            """{"route_id":$route,"targets":[],"day_state":{"route_id":$route,"business_date":"$date","state":"final_submitted","submit_cycle":0,"submit_voided":false,"final_submitted_at":"2026-10-26T11:30:00.000Z"}}""")))
    }

    private fun openVisit(): OpenVisit = runBlocking {
        val u = ClientIds.newUuid()
        val f = GeoFixEntity(
            ClientIds.newUuid(), u, "visit_open", "ok", 23.79, 90.40, 8.0, provider = "fused", fixTime = now, fixElapsedRealtimeMs = 900, fixAgeMs = 10,
            timeToFixMs = 1000, requestPriority = "balanced", isMock = false, reused = false, refreshCount = 0, deviceOwner = true, devOptionsEnabled = false,
            adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false,
        )
        repo.recordVisitOpen(
            VisitEntity(
                clientUuid = u, meta = meta(), visitKind = "sr_call", outletId = 50001, openedAt = now, sequenceNo = 1, planned = true,
                fixClientUuid = f.clientUuid, geoVerdict = "in_range", geoDistanceM = 10.0, geoRadiusMUsed = 100, geoMaxAccuracyMUsed = 50,
                geoLocationBasis = "master", geoOutletLat = 23.79, geoOutletLng = 90.40, geoAction = "sale_allowed",
            ),
            f,
        )
        OpenVisit(u, 50001, route, "in_range", "sale_allowed", true, false, null, now)
    }

    /** Waits (bounded) for the outbox row [uuid] the view model's coroutine writes on Room's executor. */
    private fun awaitQueued(uuid: String) = runBlocking { withTimeoutOrNull(10_000) { while (db.outboxDao().byClientUuid(uuid) == null) delay(10) } }

    @Test fun leavingTheCallAfterTheLockArrivedMidVisitEndsItOnThePhone() = runBlocking {
        val v = openVisit()
        val session = VisitSession().also { it.restore(v) }
        finalSubmitted() // the lock arrives while the SR is in the call
        // the call prompt's No (or back): no close is queued (the server would refuse it), the call ends, nothing throws
        assertFalse(SrCallLock.abandon(db, repo, session, meta(), now))
        assertNull(session.current.value)
        assertTrue(db.captureDao().visitClosesOn(date).isEmpty())
        // a relaunch does not take the rep back into the call
        assertNull(FinalSubmitLock.callToRestore(db, date) { true })
        // and a new call on the closed route-day is refused before anything is stored (the visit flow shows a failed commit)
        val e = assertThrows(RouteDayFinalSubmittedException::class.java) { runBlocking { FinalSubmitLock.requireCallOpen(db, meta()) } }
        assertEquals("visit", e.recordType)
    }

    @Test fun beforeTheLockTheCallClosesAbandonedAsBefore() = runBlocking {
        val v = openVisit()
        assertEquals(v.visitUuid, FinalSubmitLock.callToRestore(db, date) { true }?.clientUuid)
        val session = VisitSession().also { it.restore(v) }
        assertTrue(SrCallLock.abandon(db, repo, session, meta(), now))
        assertNull(session.current.value)
        assertEquals("abandoned", db.captureDao().visitClosesOn(date).single().outcomeCode)
        assertFalse(SrCallLock.endsOnPhone(db, VisitSession().also { it.restore(v) }, meta()))
        FinalSubmitLock.requireCallOpen(db, meta()) // open
    }

    @Test fun salesSubmitAfterTheLockIsQueuedAndNeverCrashes() {
        finalSubmitted() // the TSO used "Submit anyway": this SR has not done Sales Submit yet
        val day = object : DaySource {
            override suspend fun outboxStates(businessDate: String) = OutboxStates(0, 0, 4, 0, 0)
            override suspend fun deviceCounts(businessDate: String) = mapOf("memo" to 2)
            override suspend fun dues(businessDate: String) = DuesAtSubmit(0, 0)
            override suspend fun alreadySubmitted(businessDate: String) = false
            override suspend fun submitSettled(businessDate: String) = false
        }
        val triggers = ArrayList<SyncTrigger>()
        val sched = object : SyncScheduler { override fun requestSync(userId: Long, trigger: SyncTrigger) { triggers += trigger } }
        val uuid = ClientIds.newUuid()
        val vm = SalesSubmitViewModel(user, { date }, day, { mapOf("memo" to 2) }, RoomDaySubmitWriter(db, repo, { meta() }, { "other" }, newUuid = { uuid }), sched, { true })
        vm.refresh(); assertTrue(vm.state.value.gate.enabled)
        vm.submit()
        awaitQueued(uuid)
        assertEquals(listOf<Throwable>(), crashes.toList())
        assertEquals("day_submit", runBlocking { db.outboxDao().byClientUuid(uuid) }?.recordType)
    }

    @Test fun markPaidAfterTheLockNeverCrashes() {
        val memoUuid = ClientIds.newUuid()
        val credit = StoredMemo(memoUuid, "sr1-261026-001", 50001, date, "2026-10-26T04:00:00.000Z", null, listOf(MemoItem(105, 3, 28_000, 84_000)), emptyList(), emptyList(), 84_000, 0, 0, 0, 84_000, 34_000, 50_000)
        val store = object : MemoStore {
            override suspend fun memos(businessDate: String) = listOf(credit)
            override suspend fun collections(businessDate: String) = emptyList<StoredCollection>()
        }
        val reprinter = object : MemoReprinter { override suspend fun print(memoUuid: String, memo: com.aktcl.aron.core.printing.doc.MemoPrint) = PrintAttempt.Done }
        finalSubmitted()
        val uuid = ClientIds.newUuid()
        val vm = MemoViewModel(store, RoomDueCollectionWriter(repo, { meta() }, newUuid = { uuid }), reprinter, { PrintNames({ "S$it" }, { "O$it" }, "sr", "r") }, { date })
        vm.open(memoUuid)
        vm.markPaid()
        awaitQueued(uuid)
        assertEquals(listOf<Throwable>(), crashes.toList())
        // queued for the server, which answers day_final_submitted; the day banner counts it once the answer is back
        assertNotNull(runBlocking { db.outboxDao().byClientUuid(uuid) })
    }
}
