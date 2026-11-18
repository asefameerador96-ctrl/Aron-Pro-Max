package com.aktcl.aron.amo

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.StockMovementEntity
import com.aktcl.aron.core.database.repo.AttendanceRefusal
import com.aktcl.aron.core.database.repo.AttendanceRefusedException
import com.aktcl.aron.feature.attendance.AttendanceResult
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

/** D-OWNER-PREREQ-01 rule 1 for the AMO, on a real [AmoDay]: the same gate as the SR, read from stored attendance by business date. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AmoCheckInGateTest {
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
        f.db.openHelper.writableDatabase.execSQL("DELETE FROM attendance_event") // the fixture day starts checked in; these tests want it before the check-in
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

    @Test fun theAmoDayStartsLockedAndTheRealCheckInOpensIt() = runBlocking {
        f.day.refreshDayGate()
        assertEquals(AttendanceRefusal.NOT_CHECKED_IN, f.day.dayGate.value)
        assertThrows(AttendanceRefusedException::class.java) { runBlocking { f.day.capture.recordStock(listOf(stock())) } }
        assertEquals(0, f.db.captureDao().stockOn(f.date).size)
        assertTrue(f.day.attendance.checkIn() is AttendanceResult.Done)
        assertNull(f.day.dayGate.value)
        f.day.capture.recordStock(listOf(stock()))
        assertEquals(1, f.db.captureDao().stockOn(f.date).size)
    }
}
