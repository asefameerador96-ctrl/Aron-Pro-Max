package com.aktcl.aron.sr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.StockMovementEntity
import com.aktcl.aron.core.database.repo.AttendanceRefusal
import com.aktcl.aron.core.database.repo.AttendanceRefusedException
import com.aktcl.aron.feature.attendance.AttendanceResult
import com.aktcl.aron.feature.outlet.VisitUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * D-OWNER-PREREQ-01 rule 1 on a real [SrDay] (the SR kit as the screens wire it): without a stored check-in the day's writes are refused
 * by the repository the screens use, the UI mirror ([SrDay.dayGate]) says so, and a real check-in through the production flow opens them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SrCheckInGateTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var f: SrLockFixture
    private val crashes: MutableList<Throwable> = Collections.synchronizedList(ArrayList())
    private var previousDefault: Thread.UncaughtExceptionHandler? = null
    private var previousOwn: Thread.UncaughtExceptionHandler? = null

    @Before fun setUp() {
        Dispatchers.setMain(MainLooperImmediate.dispatcher)
        val catcher = Thread.UncaughtExceptionHandler { _, e -> crashes += e }
        previousDefault = Thread.getDefaultUncaughtExceptionHandler(); Thread.setDefaultUncaughtExceptionHandler(catcher)
        previousOwn = Thread.currentThread().uncaughtExceptionHandler; Thread.currentThread().uncaughtExceptionHandler = catcher
        f = SrLockFixture(context, latePolicy = null)
        // the fixture's day starts checked in (as a rep working a day does); these tests want the day BEFORE the check-in
        f.db.openHelper.writableDatabase.execSQL("DELETE FROM attendance_event")
    }

    @After fun tearDown() {
        try {
            f.close()
            assertEquals("nothing escaped by the end of the test", listOf<Throwable>(), crashes.toList())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousDefault); Thread.currentThread().uncaughtExceptionHandler = previousOwn
            Dispatchers.resetMain()
        }
    }

    private fun stock() = StockMovementEntity(ClientIds.newUuid(), f.meta(), "issue", 100, 10, "pack", 10, 100, null, false)

    @Test fun theDayStartsLockedAndTheMirrorSaysWhyUntilTheRealCheckIn() = runBlocking {
        f.day.refreshDayGate()
        assertEquals(AttendanceRefusal.NOT_CHECKED_IN, f.day.dayGate.value)
        assertThrows(AttendanceRefusedException::class.java) { runBlocking { f.day.capture.recordStock(listOf(stock())) } }
        assertEquals(0, f.db.captureDao().stockOn(f.date).size)
        // the production check-in flow (fix, event, outbox in one transaction) opens the day and the mirror follows it
        assertTrue(f.day.attendance.checkIn() is AttendanceResult.Done)
        assertNull(f.day.dayGate.value)
        f.day.capture.recordStock(listOf(stock()))
        assertEquals(1, f.db.captureDao().stockOn(f.date).size)
    }

    /** The visit screen's commit meets the gate before anything is stored: no call can open without today's check-in. */
    @Test fun aCallCannotOpenWithoutACheckInAndStoresNothing() = runBlocking {
        val state = f.day.visitFlow.open(f.day.visitOutlet(f.outlet()))
        assertTrue("a refused commit, not an open call: $state", state is VisitUiState.CommitFailed)
        assertEquals(0, f.db.captureDao().visitsOn(f.date).size)
        assertNull(f.day.visitSession.current.value)
    }

    @Test fun aRestoredOpenCallStillCannotSellAfterTheDayRolledOver() = runBlocking {
        // a call opened while checked in, then the rep's check-in is not on this (new) business date: the write layer refuses the sale
        f.checkIn()
        val v = f.openCall()
        f.db.openHelper.writableDatabase.execSQL("DELETE FROM attendance_event") // a new business date has no check-in
        f.day.refreshDayGate()
        assertEquals(AttendanceRefusal.NOT_CHECKED_IN, f.day.dayGate.value)
        assertThrows(AttendanceRefusedException::class.java) { runBlocking { f.day.capture.recordStock(listOf(stock())) } }
        assertEquals(v.visitUuid, f.db.captureDao().visitsOn(f.date).single().clientUuid)
    }
}
