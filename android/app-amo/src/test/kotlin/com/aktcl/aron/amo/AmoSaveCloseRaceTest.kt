package com.aktcl.aron.amo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.feature.sale.domain.ReviewProblem
import com.aktcl.aron.rules.QtyUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * D-AGATE-22 (item 9): a REAL race of the rep's Save and the close of the same call, two coroutines on the real kit and the real Room database. The test holds Room's executor
 * (`DrainableExecutor.hold`) so that the first coroutine sits INSIDE its critical section, holding the close lock, while the second one starts and must wait for it; then both finish.
 * Save first: the memo lands and the close reads it (`sold`). Close first: the close stores `abandoned` and the Save is refused with the call-closed words and stores NO memo.
 * Both orders, with the phone's own end at the 04:00 boundary and with the rep's own close.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AmoSaveCloseRaceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var f: AmoLockFixture
    private lateinit var d0: String

    @Before fun setUp() { Dispatchers.setMain(MainLooperImmediate.dispatcher); f = AmoLockFixture(context, latePolicy = null); d0 = f.date }
    @After fun tearDown() { try { f.work.roomExecutor.release(); f.close() } finally { Dispatchers.resetMain() } }

    private fun at(date: String, h: Int, m: Int) = LocalDate.parse(date).atTime(h, m).toInstant(ZoneOffset.ofHours(6)).toEpochMilli()
    private fun next() = LocalDate.parse(d0).plusDays(1).toString()
    /** Raw reads (not through Room's executor, which the test holds). */
    private fun count(sql: String): Int = f.db.openHelper.readableDatabase.query(sql).use { it.moveToFirst(); it.getInt(0) }
    private fun closeOutcome(visit: String): String? = f.db.openHelper.readableDatabase.query("SELECT outcome_code FROM visit_close WHERE visit_client_uuid = '$visit'").use { if (it.moveToFirst()) it.getString(0) else null }

    private fun draftedCall(): com.aktcl.aron.feature.outlet.OpenVisit {
        val v = f.controlCall(); f.day.visitSession.restore(v)
        f.stockedCatalog()
        runBlocking { f.day.sale.prepare(); f.day.sale.flow.start(f.day.sale.saleVisit(v)); f.day.sale.flow.setQuantity(105, 2, QtyUnit.DOZEN) }
        return v
    }

    private fun race(saveFirst: Boolean, phoneEnd: Boolean) = runBlocking {
        val v = draftedCall()
        if (phoneEnd) f.trustAt(at(next(), 4, 0)) // the 04:00 boundary of the next Dhaka day: the phone ends the call
        val exec = f.work.roomExecutor
        val saveFn = suspend { runCatching { f.day.sale.commitSale() } }
        val closeFn = suspend { f.day.closeVisit(phoneEnd = phoneEnd) }
        exec.hold()
        lateinit var first: kotlinx.coroutines.Deferred<Any?>
        lateinit var second: kotlinx.coroutines.Deferred<Any?>
        try {
            first = async(Dispatchers.Default) { if (saveFirst) saveFn() else closeFn() }
            val t0 = System.nanoTime()
            while (exec.waiting() == 0) { delay(10); check(System.nanoTime() - t0 < 10_000_000_000L) { "the first coroutine never reached the database" } }
            check(f.day.closeLock.isLocked) { "the first coroutine is inside its critical section" }
            second = async(Dispatchers.Default) { if (saveFirst) closeFn() else saveFn() }
            delay(300) // the second one has had every chance to run: it must be waiting for the lock, not racing past it
            assertTrue("the second coroutine waits for the first", second.isActive)
            assertEquals("and it has not reached the database: only the first one's command is at the hold (the lock, not the executor, is what holds the second back)", 1, exec.waiting())
            assertEquals("nothing is stored while the first one holds the lock", 0, count("SELECT COUNT(*) FROM visit_close WHERE visit_client_uuid = '${v.visitUuid}'"))
        } finally {
            // an assertion above must never leave the held commands waiting for ever (runBlocking would wait for them): release first, then the assertion fails
            exec.release()
        }
        first.await(); second.await()
        v
    }

    private fun memos(visit: String) = count("SELECT COUNT(*) FROM memo WHERE visit_client_uuid = '$visit'")

    @Test fun phoneEndAt0400SaveFirst() { val v = race(saveFirst = true, phoneEnd = true); assertEquals(1, memos(v.visitUuid)); assertEquals("sold", closeOutcome(v.visitUuid)) }

    @Test fun phoneEndAt0400CloseFirst() {
        val v = race(saveFirst = false, phoneEnd = true)
        assertEquals("the Save was refused and stored no memo", 0, memos(v.visitUuid)); assertEquals("abandoned", closeOutcome(v.visitUuid))
        assertEquals(listOf<ReviewProblem>(ReviewProblem.CallClosed), f.day.sale.flow.state.value.refusal)
    }

    @Test fun repCloseSaveFirst() { val v = race(saveFirst = true, phoneEnd = false); assertEquals(1, memos(v.visitUuid)); assertEquals("sold", closeOutcome(v.visitUuid)) }

    @Test fun repCloseCloseFirst() {
        val v = race(saveFirst = false, phoneEnd = false)
        assertEquals(0, memos(v.visitUuid)); assertEquals("abandoned", closeOutcome(v.visitUuid))
        assertEquals(listOf<ReviewProblem>(ReviewProblem.CallClosed), f.day.sale.flow.state.value.refusal)
    }
}
