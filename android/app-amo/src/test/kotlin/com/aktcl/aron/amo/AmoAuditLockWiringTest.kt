package com.aktcl.aron.amo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.feature.amo.AmoBrand
import com.aktcl.aron.feature.amo.PerfState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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

/**
 * D-AGATE-25 (lead low 4): the PRODUCTION wiring of the audit saves to the close lock (`AmoDay.writer` gets `callLock = closeLock`). With a close holding `closeLock`, a real audit Save on the real
 * day suspends on the lock BEFORE it hands any command to Room's executor (counted synchronously); when the lock is released it goes on and stores its records. Remove the one wiring line and the
 * Save reaches Room while the lock is held: this test fails.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AmoAuditLockWiringTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var f: AmoLockFixture

    @Before fun setUp() { Dispatchers.setMain(MainLooperImmediate.dispatcher); f = AmoLockFixture(context, latePolicy = null) }
    @After fun tearDown() { try { f.close() } finally { Dispatchers.resetMain() } }

    private fun distributionChecks() = f.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM amo_record WHERE record_type = 'distribution_check'").use { it.moveToFirst(); it.getInt(0) }

    @Test fun anAuditSaveOnTheRealDayWaitsForTheCloseLock() = runBlocking {
        val v = f.controlCall(); f.day.visitSession.restore(v)
        val exec = f.work.roomExecutor
        f.day.closeLock.lock() // a close of the call is running
        val before = exec.submittedCount()
        val save = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            f.day.saveAssessment(v.visitUuid, listOf(AmoBrand(1, "A", null)), PerfState(present = setOf(1L), posm = true))
        }
        assertEquals("the Save suspended on the lock: no Room command was handed over", before, exec.submittedCount())
        assertTrue(save.isActive)
        assertEquals(0, distributionChecks())
        f.day.closeLock.unlock()
        save.join()
        assertEquals("after the close the Save went on and stored its record", 1, distributionChecks())
    }
}
