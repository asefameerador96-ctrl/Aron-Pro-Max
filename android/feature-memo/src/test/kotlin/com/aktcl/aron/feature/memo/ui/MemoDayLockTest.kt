package com.aktcl.aron.feature.memo.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.BundleSectionEntity
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.MemoEntity
import com.aktcl.aron.core.database.entity.MemoLineEntity
import com.aktcl.aron.core.database.entity.VisitEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.FinalSubmitLock
import com.aktcl.aron.core.database.repo.SaleCapture
import com.aktcl.aron.core.printing.flow.PrintAttempt
import com.aktcl.aron.feature.memo.domain.PrintNames
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * tso-manual X4a (R-W-11, D-TSO-MAN-31): the memo screen after the zone's Final Submit, on a real database with the
 * production store and Mark Paid writer. Mark Paid (a `due_collection`, which the server refuses after the lock) is hidden
 * while the route-day is final; one tapped before the lock was known is refused with the lock text, stores nothing and
 * never crashes. Reprints stay allowed (a print is a state record the server accepts).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class MemoDayLockTest {
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

    /** A credit memo of 84 Tk with 50 Tk due, sold on the route before the lock; returns its visit and memo uuids. */
    private fun creditMemo(): Pair<String, String> = runBlocking {
        val visit = ClientIds.newUuid(); val fixId = ClientIds.newUuid()
        val fix = GeoFixEntity(fixId, visit, "visit_open", "ok", 23.79, 90.40, 11.5, provider = "fused", isMock = false, reused = false, deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false)
        repo.recordVisitOpen(VisitEntity(visit, meta(), "sr_call", 1, "2026-10-05T04:31:07.120Z", 1, true, null, fixId, "in_range", 18.4, 100, 50, "master", 23.7, 90.4, "sale_allowed"), fix)
        val memo = ClientIds.newUuid()
        repo.recordSale(SaleCapture(
            MemoEntity(memo, meta(), visit, 1, "sr334001-261005-001", "sale", "2026-10-05T04:35:00.000Z", "2026-10-05", "outlet", 84_000, 0, 0, 0, 0, 84_000, 34_000, 50_000, true, null, 1, 0, 0),
            listOf(MemoLineEntity(ClientIds.newUuid(), meta(), memo, 1, 105, "sale", 3, "dozen", 1, 3, "outlet", "2026-09-01", 28_000, 1, 84_000)),
        ))
        visit to memo
    }

    private fun finalSubmitted() = runBlocking {
        db.referenceDao().insertSections(listOf(BundleSectionEntity("route.$route",
            """{"route_id":$route,"targets":[],"day_state":{"route_id":$route,"business_date":"$date","state":"final_submitted","submit_cycle":0,"submit_voided":false,"final_submitted_at":"2026-10-05T15:00:00.000Z"}}""")))
    }

    private val prints = Collections.synchronizedList(ArrayList<String>())

    private fun vm(visit: String) = MemoViewModel(
        RoomMemoStore(db), RoomDueCollectionWriter(repo, { meta() }, { visit }),
        object : MemoReprinter { override suspend fun print(memoUuid: String, memo: com.aktcl.aron.core.printing.doc.MemoPrint): PrintAttempt { prints += memoUuid; return PrintAttempt.Done } },
        { PrintNames({ "S$it" }, { "O$it" }, "sr", "r") }, { date },
        markPaidLocked = { FinalSubmitLock.isFinal(db, date, route) },
    )

    private fun await(what: String, cond: () -> Boolean) = runBlocking {
        assertTrue(what, withTimeoutOrNull(10_000) { while (!cond()) delay(10); true } == true)
    }

    @Test fun markPaidIsHiddenOnAFinalDayAndReprintsStayAllowed() {
        val (visit, memo) = creditMemo()
        finalSubmitted()
        val v = vm(visit)
        v.open(memo)
        await("the memo is open") { v.state.value.selected?.memo?.memoUuid == memo }
        assertTrue(v.state.value.dayLocked)
        assertFalse("Mark Paid is hidden", v.state.value.canMarkPaid)
        assertEquals(50_000, v.state.value.remainingDueMtk) // the due still shows
        v.reprint()
        await("the reprint went to the printer") { prints.toList() == listOf(memo) }
        assertEquals(PrintAttempt.Done, v.state.value.lastPrint)
        assertEquals(listOf<Throwable>(), crashes.toList())
    }

    @Test fun aMarkPaidTappedAfterTheLockArrivedIsRefusedWithTheTextAndStoresNothing() {
        val (visit, memo) = creditMemo()
        val v = vm(visit)
        v.open(memo)
        await("Mark Paid is drawn while the day is open") { v.state.value.canMarkPaid }
        finalSubmitted() // the push lands while the memo is on screen
        v.markPaid()
        await("the lock text shows") { v.state.value.lockRefused }
        assertEquals(listOf<Throwable>(), crashes.toList())
        assertTrue("no collection stored", runBlocking { db.captureDao().dueCollectionsOn(date) }.isEmpty())
        assertEquals("nothing but the call and the sale queued", setOf("visit", "memo", "memo_line"), runBlocking { db.outboxDao().committedCounts(date) }.map { it.recordType }.toSet())
        assertFalse(v.state.value.canMarkPaid); assertTrue(v.state.value.dayLocked)
        assertEquals(50_000, v.state.value.remainingDueMtk)
        v.dismissLockRefusal()
        assertFalse(v.state.value.lockRefused)
    }

    @Test fun anOpenDayKeepsMarkPaid() {
        val (visit, memo) = creditMemo()
        val v = vm(visit)
        v.open(memo)
        await("Mark Paid is drawn") { v.state.value.canMarkPaid }
        assertFalse(v.state.value.dayLocked)
        v.markPaid()
        await("collected") { runBlocking { db.captureDao().dueCollectionsOn(date) }.size == 1 }
        await("the button goes") { !v.state.value.canMarkPaid }
        assertFalse(v.state.value.lockRefused)
    }
}
