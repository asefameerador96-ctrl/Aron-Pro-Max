package com.aktcl.aron.amo

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.StockMovementEntity
import com.aktcl.aron.feature.attendance.AttendanceResult
import com.aktcl.aron.feature.attendance.OpenWork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Collections

/** D-OWNER-PREREQ-01 rule 3 for the AMO on a real [AmoDay]: the check-out submits the supervisor day by itself; an open call refuses it. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AmoCheckOutSubmitTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var f: AmoLockFixture
    private val crashes: MutableList<Throwable> = Collections.synchronizedList(ArrayList())
    private var previousDefault: Thread.UncaughtExceptionHandler? = null
    private var previousOwn: Thread.UncaughtExceptionHandler? = null

    @Before fun setUp() {
        Dispatchers.setMain(MainLooperImmediate.dispatcher)
        val catcher = Thread.UncaughtExceptionHandler { _, e -> crashes += e }
        previousDefault = Thread.getDefaultUncaughtExceptionHandler(); Thread.setDefaultUncaughtExceptionHandler(catcher)
        previousOwn = Thread.currentThread().uncaughtExceptionHandler; Thread.currentThread().uncaughtExceptionHandler = catcher
        f = AmoLockFixture(app, latePolicy = null)
        // 18:00 Dhaka on the fixture's own business date: after the default 17:00 check-out time, on the day that was checked into
        f.trustAt(java.time.LocalDate.parse(f.date).atTime(18, 0).toInstant(java.time.ZoneOffset.ofHours(6)).toEpochMilli())
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

    private fun stock() = StockMovementEntity(ClientIds.newUuid(), f.meta(null), "issue", 100, 10, "pack", 10, 100, null, false)

    @Test fun theAmoCheckOutSubmitsTheSupervisorDayByItself() = runBlocking {
        f.day.capture.recordStock(listOf(stock()))
        f.day.attendance.restore(f.day.attendanceToday())
        assertTrue(f.day.attendance.checkOut() is AttendanceResult.Done)
        val submit = f.db.captureDao().daySubmitsOn(f.date).single()
        assertEquals("supervisor_day", submit.scope); assertEquals(null, submit.meta.routeId)
        assertTrue(submit.pendingCount >= 2)
        assertEquals(1, f.db.captureDao().attendanceOn(f.date).count { it.kind == "check_out" })
        val order = f.db.outboxDao().nextPending(100).map { it.recordType }
        assertTrue(order.indexOf("day_submit") < order.lastIndexOf("attendance_event"))
    }

    @Test fun anOpenControlCallRefusesTheAmoCheckOut() = runBlocking {
        f.day.visitSession.restore(f.controlCall())
        f.day.attendance.restore(f.day.attendanceToday())
        assertEquals(AttendanceResult.Refused(OpenWork.OPEN_CALL), f.day.attendance.checkOut())
        assertEquals(0, f.db.captureDao().daySubmitsOn(f.date).size)
        f.day.closeVisit() // the real close (its visit_close is stored); D-AGATE-22 item 6: the check-out also looks at the STORED call
        assertTrue(f.day.attendance.checkOut() is AttendanceResult.Done)
    }
}
