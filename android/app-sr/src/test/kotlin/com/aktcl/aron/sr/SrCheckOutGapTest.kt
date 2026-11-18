package com.aktcl.aron.sr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.AttendanceEventEntity
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.feature.attendance.OpenWork
import com.aktcl.aron.feature.attendance.OpenWorkAtCheckOutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * D-AGATE-22 (item 6): a call opened in the gap while the rep's check-out was reading its fix. The press check saw nothing open; the call is committed in Room (the session does not
 * know it yet). The check-out looks again INSIDE its transaction, refuses with the open-work answer and writes nothing: no check-out, no automatic submit, no queued record.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SrCheckOutGapTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var f: SrLockFixture

    @Before fun setUp() { Dispatchers.setMain(MainLooperImmediate.dispatcher); f = SrLockFixture(context, latePolicy = null, checkoutMinutes = 0) }
    @After fun tearDown() { try { f.close() } finally { Dispatchers.resetMain() } }

    private fun repCheckOut(): Pair<AttendanceEventEntity, GeoFixEntity> {
        val u = ClientIds.newUuid()
        val fix = GeoFixEntity(
            ClientIds.newUuid(), u, "attendance_out", "ok", 23.79, 90.4, 8.0, provider = "fused", isMock = false, reused = false, deviceOwner = true,
            devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false,
        )
        return AttendanceEventEntity(u, f.day.metaProvider.meta(0L).copy(routeId = null), "check_out", fix.clientUuid, null) to fix
    }

    @Test fun aCallOpenedInTheGapRefusesTheCheckOutInsideItsTransactionAndNothingIsWritten() = runBlocking {
        assertNull("the press check saw nothing open", f.day.openWorkNow())
        f.openCall() // committed in Room while the fix was being read; the session does not hold it
        assertNull("the session still knows nothing", f.day.visitSession.current.value)
        val before = f.db.outboxDao().countInState(com.aktcl.aron.core.database.entity.OutboxState.PENDING)
        val (e, fx) = repCheckOut()
        var refused: OpenWork? = null
        try { f.day.commitAttendance(e, fx) } catch (t: OpenWorkAtCheckOutException) { refused = t.open }
        assertEquals(OpenWork.OPEN_CALL, refused)
        assertTrue("no check-out stored", f.db.captureDao().attendanceOn(f.date).none { it.kind == "check_out" })
        assertTrue("no automatic submit", f.db.captureDao().daySubmitsOn(f.date).isEmpty())
        assertEquals("nothing queued", before, f.db.outboxDao().countInState(com.aktcl.aron.core.database.entity.OutboxState.PENDING))
    }

    @Test fun withNothingOpenTheSameCheckOutGoesThrough() = runBlocking {
        val (e, fx) = repCheckOut()
        f.day.commitAttendance(e, fx)
        assertEquals(1, f.db.captureDao().attendanceOn(f.date).count { it.kind == "check_out" })
    }
}
