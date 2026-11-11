package com.aktcl.aron.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.database.repo.FinalSubmitLock
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/**
 * D-AMO-CALLS-38: the lock reads keep CANCELLATION. `metLock`, `locks`, `lockApplies`, `sectionFinal` and `syncStates` each wrapped suspend Room reads in
 * `runCatching`, which turned the cancellation of the caller (a banner or dialog effect that left composition) into the read's default (`LockAcks(0, 0)`, false); the
 * cancelled caller (`refreshLock`) then went on and wrote that default over the shared day lock. Deterministic, no timing: Room's query executor is held, so the read
 * is suspended inside the query; the caller is cancelled there; the executor is then released. With `runCatching(..).getOrDefault(..)` back in `RouteDayLock`,
 * the caller carries on and `wrote` is true (both tests fail every time); with the fix the read throws and nothing is written.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class FinalSubmitLockCancellationTest {
    /** Queues what Room submits until [release]; after that it runs inline. */
    private class GatedExecutor : Executor {
        private val queued = ArrayList<Runnable>()
        @Volatile private var open = false
        @Synchronized override fun execute(command: Runnable) { if (open) command.run() else queued += command }
        fun release() { val run = synchronized(this) { open = true; queued.toList().also { queued.clear() } }; run.forEach { it.run() } }
        @Synchronized fun held() = queued.size
    }

    private val gate = GatedExecutor()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().setQueryExecutor(gate).setTransactionExecutor(gate).build()
    private val date = TestRows.meta().businessDate

    @After fun tearDown() { gate.release(); db.close() }

    @Test fun aMetLockCancelledMidReadWritesNothing() = runBlocking {
        var wrote = false
        val reader = launch(start = CoroutineStart.UNDISPATCHED) { FinalSubmitLock.metLock(db, date); wrote = true }
        assertTrue("the read is suspended inside Room's query", gate.held() > 0)
        reader.cancel(); gate.release(); reader.join()
        assertTrue(reader.isCancelled)
        assertFalse("a cancelled refreshLock must not carry on with LockAcks(0, 0)", wrote)
    }

    @Test fun aLocksCheckCancelledMidReadWritesNothing() = runBlocking {
        var wrote = false
        val reader = launch(start = CoroutineStart.UNDISPATCHED) { FinalSubmitLock.locks(db, date, 10231L, "2026-10-05T16:00:00.000Z"); wrote = true }
        assertTrue("the read is suspended inside Room's query", gate.held() > 0)
        reader.cancel(); gate.release(); reader.join()
        assertTrue(reader.isCancelled)
        assertFalse("a cancelled check must not carry on with false", wrote)
    }

    @Test fun anUncancelledReadStillGivesItsDefaultOrValue() = runBlocking {
        gate.release()
        assertEquals(com.aktcl.aron.core.database.repo.LockAcks(0, 0), FinalSubmitLock.metLock(db, date))
        assertFalse(FinalSubmitLock.locks(db, date, 10231L, "2026-10-05T16:00:00.000Z"))
    }
}
