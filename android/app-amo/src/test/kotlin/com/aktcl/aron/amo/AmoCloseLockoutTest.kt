package com.aktcl.aron.amo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * D-AGATE-22 (item 5): the lock-out fallback through the production resume of a real day object. A forgotten day (or an earlier call past its limit) whose close keeps failing raises
 * the plain "could not be closed" state after 3 failed attempts, with a short stable code and no payload; Try again runs the resume once; the first success clears it and check-in follows.
 * The failure is injected as a database trigger that aborts the insert the close needs (removed later to let the close through).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AmoCloseLockoutTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var f: AmoLockFixture
    private lateinit var d0: String

    @Before fun setUp() { Dispatchers.setMain(MainLooperImmediate.dispatcher); f = AmoLockFixture(app, latePolicy = null); d0 = f.date }
    @After fun tearDown() { try { f.close() } finally { Dispatchers.resetMain() } }

    private fun at(date: String, h: Int, m: Int) = LocalDate.parse(date).atTime(h, m).toInstant(ZoneOffset.ofHours(6)).toEpochMilli()
    private fun next() = LocalDate.parse(d0).plusDays(1).toString()
    private fun sql(q: String) = f.db.openHelper.writableDatabase.execSQL(q)
    private fun checkOuts(date: String) = runBlocking { f.db.captureDao().attendanceOn(date).filter { it.kind == "check_out" } }

    @Test fun aForgottenDayThatKeepsFailingToCloseRaisesTheScreenAfterThreeAttemptsAndTryAgainClearsIt() = runBlocking {
        sql("CREATE TRIGGER fail_close BEFORE INSERT ON geo_fix BEGIN SELECT RAISE(ABORT, 'disk says no, payload 017'); END")
        f.trustAt(at(next(), 10, 0))
        f.day.resumeDay(); assertNull("1 failure: nothing shown", f.day.closeLockout.value)
        f.day.resumeDay(); assertNull("2 failures: nothing shown", f.day.closeLockout.value)
        f.day.resumeDay()
        val l = f.day.closeLockout.value
        assertNotNull("3 failures: the plain screen", l)
        assertEquals(d0, l!!.businessDate)
        assertTrue("a short stable code: the step and the exception class", l.code.startsWith("E-CLOSE-DAY/"))
        assertTrue("no message, no payload", !l.code.contains("017") && !l.code.contains("disk"))
        assertTrue("the day is still open", checkOuts(d0).isEmpty())
        // Try again while it still fails: stays up
        f.day.resumeDay(); assertNotNull(f.day.closeLockout.value)
        // the cause goes away; Try again runs the resume once and the close succeeds
        sql("DROP TRIGGER fail_close")
        f.day.resumeDaySafely()
        assertNull("the screen goes", f.day.closeLockout.value)
        assertEquals("the day is closed (by the phone)", 1, checkOuts(d0).size)
        assertTrue("and no counter is left", f.db.referenceDao().metaWithPrefix("day_close_fail:").isEmpty())
    }

    @Test fun anEarlierCallThatKeepsFailingToCloseRaisesTheScreenAndClearsOnSuccess() = runBlocking {
        val v = f.controlCall()
        sql("CREATE TRIGGER fail_call BEFORE INSERT ON visit_close BEGIN SELECT RAISE(ABORT, 'no'); END")
        f.trustAt(at(next(), 8, 0)) // past the 04:00 limit
        repeat(3) { f.day.resumeDay() }
        val l = f.day.closeLockout.value
        assertNotNull(l); assertTrue(l!!.code.startsWith("E-CLOSE-CALL/")); assertEquals(d0, l.businessDate)
        assertNotNull("the call is still there, nothing lost", f.day.visitSession.current.value)
        sql("DROP TRIGGER fail_call")
        f.day.resumeDaySafely()
        assertNull(f.day.closeLockout.value)
        assertNull("the call was ended", f.day.visitSession.current.value)
        assertEquals(1, f.db.captureDao().visitClosesOn(d0).count { it.visitClientUuid == v.visitUuid })
        assertEquals("and the day then closed", 1, checkOuts(d0).size)
    }

    @Test fun aSingleFailureNeverShowsTheScreenAndASuccessResetsTheCount() = runBlocking {
        sql("CREATE TRIGGER fail_close BEFORE INSERT ON geo_fix BEGIN SELECT RAISE(ABORT, 'x'); END")
        f.trustAt(at(next(), 10, 0))
        f.day.resumeDay(); f.day.resumeDay()
        sql("DROP TRIGGER fail_close")
        f.day.resumeDay() // succeeds: counter reset
        assertNull(f.day.closeLockout.value)
        assertTrue(f.db.referenceDao().metaWithPrefix("day_close_fail:").isEmpty())
    }
}
