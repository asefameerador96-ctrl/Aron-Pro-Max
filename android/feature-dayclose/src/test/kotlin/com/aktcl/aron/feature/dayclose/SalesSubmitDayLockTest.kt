package com.aktcl.aron.feature.dayclose

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.contract.SyncTrigger
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.BundleSectionEntity
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.ConfigValueEntity
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.VisitEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.FinalSubmitLock
import com.aktcl.aron.core.database.repo.RouteDayFinalSubmittedException
import com.aktcl.aron.core.sync.SyncScheduler
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * tso-manual X4a (R-W-11, D-TSO-MAN-31): Sales Submit is never locked. After the zone's Final Submit (the TSO used "Submit
 * anyway" before this SR submitted) the database refuses the locked captures, but the SR's `day_submit` is a state record
 * the server accepts after the lock: Submit queues it through the production writer, the day counts as submitted (the
 * check-out opens) and nothing crashes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SalesSubmitDayLockTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    private val repo = CaptureRepository(db) { "2026-10-05T16:00:00.000Z" }
    private val date = "2026-10-05"
    private val route = 10231L
    private val crashes: MutableList<Throwable> = Collections.synchronizedList(ArrayList())
    private var previousDefault: Thread.UncaughtExceptionHandler? = null
    private var previousOwn: Thread.UncaughtExceptionHandler? = null

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // owner decision 2026-11-04: the phone locks only under quarantine or reject (default accept_and_flag); this class tests the lock
        runBlocking {
            db.referenceDao().insertConfig(listOf(ConfigValueEntity(
                key = FinalSubmitLock.KEY_LATE_POLICY, valueJson = "\"quarantine\"", scopeType = "global", scopeId = null, effectiveFrom = null,
                effectiveTo = null, configVersion = 1, requiresAck = false, scheduled = false,
            )))
        }
        val catcher = Thread.UncaughtExceptionHandler { _, e -> crashes += e }
        previousDefault = Thread.getDefaultUncaughtExceptionHandler(); Thread.setDefaultUncaughtExceptionHandler(catcher)
        previousOwn = Thread.currentThread().uncaughtExceptionHandler; Thread.currentThread().uncaughtExceptionHandler = catcher
    }

    @After fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousDefault); Thread.currentThread().uncaughtExceptionHandler = previousOwn
        db.close()
        Dispatchers.resetMain()
    }

    private fun meta() = CaptureMeta(date, "2026-10-05T04:31:07.120Z", 1, 1, 0, true, route, null, "b", false, 1)

    private fun visit(seq: Int) = ClientIds.newUuid().let { u ->
        val fixId = ClientIds.newUuid()
        VisitEntity(u, meta(), "sr_call", 50000L + seq, "2026-10-05T04:31:07.120Z", seq, true, null, fixId, "in_range", 18.4, 100, 50, "master", 23.7, 90.4, "sale_allowed") to
            GeoFixEntity(fixId, u, "visit_open", "ok", 23.79, 90.40, 11.5, provider = "fused", isMock = false, reused = false, deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false)
    }

    @Test fun salesSubmitAfterTheFinalSubmitIsQueuedAndTheDayCountsAsSubmitted() {
        runBlocking { visit(1).let { (v, f) -> repo.recordVisitOpen(v, f) } }
        // the day's records are synced (the gate wants nothing pending): the server acked the call
        db.openHelper.writableDatabase.execSQL("UPDATE outbox SET state = 'acked', acked_at = '2026-10-05T05:00:00.000Z'")
        runBlocking {
            db.referenceDao().insertSections(listOf(BundleSectionEntity("route.$route",
                """{"route_id":$route,"targets":[],"day_state":{"route_id":$route,"business_date":"$date","state":"final_submitted","submit_cycle":0,"submit_voided":false,"final_submitted_at":"2026-10-05T15:00:00.000Z"}}""")))
        }
        assertTrue(runBlocking { FinalSubmitLock.isFinal(db, date, route) })
        // the lock is real: a new call is refused by the database
        assertThrows(RouteDayFinalSubmittedException::class.java) { runBlocking { visit(2).let { (v, f) -> repo.recordVisitOpen(v, f) } } }

        val triggers = Collections.synchronizedList(ArrayList<SyncTrigger>())
        val scheduler = object : SyncScheduler { override fun requestSync(userId: Long, trigger: SyncTrigger) { triggers += trigger } }
        val uuid = ClientIds.newUuid()
        val source = RoomDaySource(db)
        val vm = SalesSubmitViewModel(7, { date }, source, { mapOf("visit" to 1) }, RoomDaySubmitWriter(db, repo, { meta() }, { "other" }, newUuid = { uuid }), scheduler, { true })
        vm.refresh()
        runBlocking { withTimeoutOrNull(10_000) { while (!vm.state.value.gate.enabled) delay(10) } }
        assertTrue("Submit is offered", vm.state.value.gate.enabled)
        vm.submit()
        runBlocking { withTimeoutOrNull(10_000) { while (db.outboxDao().byClientUuid(uuid) == null) delay(10) } }
        assertEquals(listOf<Throwable>(), crashes.toList())
        assertEquals("day_submit", runBlocking { db.outboxDao().byClientUuid(uuid) }?.recordType)
        assertTrue("the day counts as submitted (check-out opens)", runBlocking { source.alreadySubmitted(date) })
        runBlocking { withTimeoutOrNull(10_000) { while (SyncTrigger.DAY_SUBMIT !in triggers) delay(10) } }
        assertTrue(SyncTrigger.DAY_SUBMIT in triggers)
    }
}
