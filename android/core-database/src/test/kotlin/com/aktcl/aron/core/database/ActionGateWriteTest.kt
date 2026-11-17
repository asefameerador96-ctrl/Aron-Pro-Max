package com.aktcl.aron.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ActionGate
import com.aktcl.aron.core.common.GateClosedException
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.OutletChangeRequestEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Developer options gate cannot be raced: a NEW record is refused at the write while the gate is closed (D-OWNER-MDM-02). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ActionGateWriteTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AronDatabase::class.java).allowMainThreadQueries().build()
    private val repo = CaptureRepository(db) { "2026-11-12T04:00:00.000Z" }
    private val meta = CaptureMeta("2026-11-12", "2026-11-12T04:00:00.000Z", 1, 1, 0, true, 1, null, "b", false, 1)
    private val id = "00000000-0000-4000-8000-00000000000a"; private val fixId = "00000000-0000-4000-8000-0000000000fa"

    @After fun tearDown() { ActionGate.check = { true }; db.close() }

    private fun fix() = GeoFixEntity(fixId, id, "outlet_capture", "ok", 23.78, 90.40, 10.0, provider = "fused", isMock = false, reused = false, deviceOwner = false, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false)
    private fun request() = OutletChangeRequestEntity(clientUuid = id, meta = meta, requestType = "info", outletId = 10, proposedJson = "{}", fixClientUuid = fixId, photoUuidsJson = "[]", originVisitClientUuid = null, note = "n")

    @Test fun aClosedGateRefusesANewRecordAndLeavesNothingBehind() = runTest {
        ActionGate.check = { false }
        val r = runCatching { repo.recordOutletRequest(request(), fix()) }
        assertTrue(r.exceptionOrNull() is GateClosedException)
        assertEquals(0, db.captureDao().outletRequestCount(id))
    }

    @Test fun anOpenGateWritesAsBefore() = runTest {
        repo.recordOutletRequest(request(), fix())
        assertEquals(1, db.captureDao().outletRequestCount(id))
    }

    @Test fun theDeviceStatusIsStillRecordedWhileGatedSoTheSignalReachesTheServer() = runTest {
        ActionGate.check = { false }
        // recordDeviceStatus does not ask the gate: the integrity signal must be able to ride the next upload.
        val src = java.io.File(System.getProperty("user.dir")!!, "src/main/kotlin/com/aktcl/aron/core/database/repo/CaptureRepository.kt").readText()
        val body = src.substringAfter("suspend fun recordDeviceStatus").substringBefore("suspend fun ").take(400)
        assertTrue("recordDeviceStatus must not be gated", "ActionGate" !in body.substringBefore("db.withTransaction"))
    }
}
