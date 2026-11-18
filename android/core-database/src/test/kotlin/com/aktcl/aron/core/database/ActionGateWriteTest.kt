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
import kotlinx.coroutines.launch
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

    @After fun tearDown() { ActionGate.check = { true }; ActionGate.onClosed = {}; db.close() }

    private fun fix() = GeoFixEntity(fixId, id, "outlet_capture", "ok", 23.78, 90.40, 10.0, provider = "fused", isMock = false, reused = false, deviceOwner = false, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false)
    private fun request() = OutletChangeRequestEntity(clientUuid = id, meta = meta, requestType = "info", outletId = 10, proposedJson = "{}", fixClientUuid = fixId, photoUuidsJson = "[]", originVisitClientUuid = null, note = "n")

    @Test fun aClosedGateRefusesANewRecordAndLeavesNothingBehind() = runTest {
        ActionGate.check = { false }
        val r = runCatching { repo.recordOutletRequest(request(), fix()) }
        assertTrue(r.exceptionOrNull() is GateClosedException)
        assertEquals(0, db.captureDao().outletRequestCount(id))
    }

    /**
     * The Developer options gate is independent of, and FIRST before, the check-in gate (D-OWNER-MDM-02; lead 2026-11-18): a rep who is not checked in and has Developer options
     * on sees the developer-options block, not "check in first". With the gate open the check-in gate answers as before. Integrity events and device status are exempt like
     * breadcrumbs (they describe the phone, they are not operational records).
     */
    @Test fun theDeveloperOptionsGateComesBeforeTheCheckInGate() = runTest {
        val strict = CaptureRepository(db, requireCheckIn = true) { "2026-11-12T04:00:00.000Z" }
        ActionGate.check = { false }
        val closed = runCatching { strict.recordOutletRequest(request(), fix()) }
        assertTrue("gate closed, not checked in: the developer-options block first", closed.exceptionOrNull() is GateClosedException)
        val co = runCatching { strict.recordCheckOut(com.aktcl.aron.core.database.entity.AttendanceEventEntity(clientUuid = id, meta = meta, kind = "check_out", fixClientUuid = fixId, addressDisplay = null), fix()) }
        assertTrue("a check-out is gated too", co.exceptionOrNull() is GateClosedException)
        ActionGate.check = { true }
        val open = runCatching { strict.recordOutletRequest(request(), fix()) }
        assertTrue("gate open, not checked in: the check-in gate answers", open.exceptionOrNull() is com.aktcl.aron.core.database.repo.AttendanceRefusedException)
    }

    @Test fun anOpenGateWritesAsBefore() = runTest {
        repo.recordOutletRequest(request(), fix())
        assertEquals(1, db.captureDao().outletRequestCount(id))
    }

    @Test fun resolvingATaskIsANewRecordAndIsRefusedWhileGated() = runTest {
        ActionGate.check = { false }
        val r = runCatching { repo.resolveTask(com.aktcl.aron.core.database.entity.TaskEventEntity(clientUuid = id, meta = meta, taskUuid = fixId, event = "resolved", note = null), "2026-11-12T04:00:00.000Z") }
        assertTrue(r.exceptionOrNull() is GateClosedException)
    }

    @Test fun aPhotoAlreadyTakenIsStillRecordedWhileGated() = runTest {
        // media_meta describes a photo that exists; the background worker writes it; the gate never holds back captured data.
        val (visit, fix) = TestRows.visit()
        repo.recordVisitOpen(visit, fix)
        ActionGate.check = { false }
        val media = com.aktcl.aron.core.common.ClientIds.newUuid()
        val capture = com.aktcl.aron.core.database.repo.MediaMetaCapture(
            mediaUuid = media, meta = TestRows.meta(), purpose = "outlet_capture", refType = "outlet_change_request", refClientUuid = visit.clientUuid,
            sha256 = "a".repeat(64), phash = "0123456789abcdef", bytes = 148_000, width = 1024, height = 768,
            blobPath = "photos/2026-10-05/6f1c2d3e-4b5a-4c6d-8e7f-9a0b1c2d3e4f/$media.jpg", takenAt = "2026-10-05T04:40:00.000Z", fixClientUuid = fix.clientUuid,
        )
        assertTrue(repo.recordMediaMeta(capture))
    }

    @Test fun aGateClosedWriteIsACancellationNotACrash() = runTest {
        ActionGate.check = { false }
        var closedCalls = 0
        ActionGate.onClosed = { closedCalls++ }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()) // no exception handler, like a ViewModel scope
        val job = scope.launch { repo.recordOutletRequest(request(), fix()) }
        job.join()
        assertTrue("the coroutine is cancelled quietly", job.isCancelled)
        assertEquals("and the UI is told at once", 1, closedCalls)
        assertEquals(0, db.captureDao().outletRequestCount(id))
        assertTrue(GateClosedException() is java.util.concurrent.CancellationException)
    }

    @Test fun theDeviceStatusIsStillRecordedWhileGatedSoTheSignalReachesTheServer() = runTest {
        ActionGate.check = { false }
        // recordDeviceStatus does not ask the gate: the integrity signal must be able to ride the next upload.
        val src = java.io.File(System.getProperty("user.dir")!!, "src/main/kotlin/com/aktcl/aron/core/database/repo/CaptureRepository.kt").readText()
        val body = src.substringAfter("suspend fun recordDeviceStatus").substringBefore("suspend fun ").take(400)
        assertTrue("recordDeviceStatus must not be gated", "ActionGate" !in body.substringBefore("db.withTransaction"))
    }
}
