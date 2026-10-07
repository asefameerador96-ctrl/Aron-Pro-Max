package com.aktcl.aron.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.contract.SyncTrigger
import com.aktcl.aron.core.common.WallClock
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.network.ApiOrigin
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.ClientIdentity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** F-SYS-081: the daily telemetry object (doc 17 s4.4), its samples, its cap and its ride on the batch. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class DeviceTelemetryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AronDatabase

    /** 2026-10-05 in Dhaka at [hh]:[mm] (UTC+6). */
    private fun dhaka(hh: Int, mm: Int, day: Int = 5) = 1_791_158_400_000L + (day - 5) * 86_400_000L + ((hh - 6) * 3600L + mm * 60L) * 1000L
    private var now = dhaka(7, 0)
    private val clock = object : WallClock {
        override fun nowMs(): Long = now
        override fun elapsedRealtimeMs(): Long = 1_000_000L
    }

    private class FakeProbe : TelemetryProbe {
        var bytes: Long? = 1_000
        var cpu = 100L
        override var processToken = "p1"
        var bat: Pair<Int, Boolean>? = 90 to false
        var metered: Boolean? = true
        override fun uidBytes() = bytes
        override fun processCpuMs() = cpu
        override fun battery() = bat
        override fun metered() = metered
    }
    private val probe = FakeProbe()
    private val telemetry get() = DeviceTelemetry(db, probe, clock)

    @Before fun setUp() {
        context.deleteDatabase(AronDatabase.fileName(USER))
        db = AronDatabase.open(context, USER, null)
    }

    @After fun tearDown() { db.close() }

    private suspend fun day(day: Int = 6): JsonObject {
        now = dhaka(9, 0, day)
        return telemetry.pendingDay(true, 1024)!!.second.jsonObject
    }

    @Test fun bytesCpuAndStartsAreDeltasAcrossProcessesAndReboots() = runBlocking {
        telemetry.sample() // first sample: a baseline only for bytes; this process's CPU so far counts
        probe.bytes = 5_000; probe.cpu = 400
        telemetry.sample() // +4,000 on mobile, +300 ms
        probe.metered = false; probe.bytes = 6_000
        telemetry.sample() // +1,000 on Wi-Fi
        probe.processToken = "p2"; probe.cpu = 50; probe.bytes = 800 // a new process after a reboot
        probe.metered = true
        telemetry.sample() // CPU of the new process counts whole; the uid counters restarted: +800 on mobile
        telemetry.noteMobileMediaBytes(300)
        telemetry.noteWake(2_500)
        val d = day()
        assertEquals("2026-10-05", d["d"]!!.jsonPrimitive.content)
        assertEquals(4_800L - 300L, d["b_mob"]!!.jsonPrimitive.long)
        assertEquals(300L, d["b_mob_media"]!!.jsonPrimitive.long)
        assertEquals(1_000L, d["b_wifi"]!!.jsonPrimitive.long)
        assertEquals(100L + 300L + 50L, d["cpu_ms"]!!.jsonPrimitive.long)
        assertEquals(2, d["starts"]!!.jsonPrimitive.int)
        assertEquals(2_500L, d["wake_ms"]!!.jsonPrimitive.long)
    }

    @Test fun batteryIsTheFirstSampleInEachHalfHourAfterTheSlots() = runBlocking {
        now = dhaka(7, 59); probe.bat = 99 to true; telemetry.sample() // before 08:00: no slot, plugged does not count
        now = dhaka(8, 5); probe.bat = 95 to false; telemetry.sample()
        now = dhaka(8, 20); probe.bat = 93 to false; telemetry.sample() // the slot is already taken
        now = dhaka(12, 45); probe.bat = 70 to false; telemetry.sample() // after 12:30: missed
        now = dhaka(17, 10); probe.bat = 41 to true; telemetry.sample()
        val d = day()
        assertEquals(JsonArray(listOf(JsonPrimitive(95), JsonNull, JsonPrimitive(41))), d["bat"])
        assertEquals(1, d["plug"]!!.jsonPrimitive.int)
    }

    @Test fun onlyClosedDatesAreSentWithinTheCapAndAnAnsweredDateIsGone() = runBlocking {
        telemetry.sample()
        now = dhaka(23, 59)
        assertNull("today is still open", telemetry.pendingDay(true, 1024))
        val (date, obj) = run { now = dhaka(0, 5, 6); telemetry.pendingDay(true, 1024)!! }
        assertEquals("2026-10-05", date)
        assertTrue(obj.toString().toByteArray().size <= 1024)
        assertTrue(telemetry.pendingDay(true, 64)!!.second.toString().toByteArray().size <= 64) // the guard trims
        telemetry.markSent(date)
        assertNull(telemetry.pendingDay(true, 1024))
    }

    @Test fun disabledDropsTheKeptDaysAndOldDaysExpire() = runBlocking {
        telemetry.sample()
        now = dhaka(9, 0, 6); telemetry.sample()
        now = dhaka(9, 0, 7)
        assertNull(telemetry.pendingDay(false, 1024))
        assertTrue(db.referenceDao().metaWithPrefix(DeviceTelemetry.DAY_PREFIX).isEmpty())
        now = dhaka(9, 0, 7); telemetry.sample()
        now = dhaka(9, 0, 16)
        assertNull("older than 7 days: dropped unsent", telemetry.pendingDay(true, 1024))
    }

    @Test fun gpsCountsTheFixesTakenForThatDatesCaptures() = runBlocking {
        val repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }
        TestRows.visit(outletId = 50001L, seq = 1).let { (v, f) -> repo.recordVisitOpen(v, f) }
        TestRows.visit(outletId = 50002L, seq = 2).let { (v, f) -> repo.recordVisitOpen(v, f.copy(reused = true)) }
        telemetry.sample() // 2026-10-05
        assertEquals(1, day()["gps"]!!.jsonPrimitive.int)
    }

    /** The object rides the next batch, is kept through a 503 and sent again, and is dropped once a batch is answered. */
    @Test fun theDayRidesTheBatchUntilAnAnswer() = runBlocking {
        val server = MockWebServer()
        val fake = FakeIngestServer()
        server.dispatcher = fake
        server.start()
        try {
            telemetry.sample()
            now = dhaka(9, 0, 6)
            val repo = CaptureRepository(db) { "2026-10-06T03:00:00.000Z" }
            TestRows.visit(outletId = 50001L, seq = 1).let { (v, f) -> repo.recordVisitOpen(v, f) }
            val ok = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
            val client = AronApiClient(ApiOrigin.parse(server.url("/").toString().trimEnd('/'), allowCleartextLoopback = true), ok, ClientIdentity("1.0.3+10003") { DEVICE })
            val auth = object : UploadAuth {
                override suspend fun token(userId: Long): String? = "upload-1"
                override suspend fun refresh(userId: Long, rejected: String?): Boolean = false
            }
            fun engine() = SyncEngine(USER, db, SyncBatchApi(client, null), auth, { DEVICE }, "1.0.3+10003", clock, SyncPolicy(), random = Random(7),
                telemetry = telemetry.forBatch { null })
            fake.failBefore += 503
            assertEquals(SyncStop.RETRY_LATER, engine().run(SyncTrigger.MANUAL).stop)
            assertNotNull(db.referenceDao().meta(DeviceTelemetry.DAY_PREFIX + "2026-10-05"))
            assertEquals(SyncStop.DRAINED, engine().run(SyncTrigger.MANUAL).stop)
            val carried = fake.requests.mapNotNull { it.body?.get("telemetry")?.jsonObject?.get("d")?.jsonPrimitive?.content }
            assertEquals(listOf("2026-10-05", "2026-10-05"), carried)
            assertNull(db.referenceDao().meta(DeviceTelemetry.DAY_PREFIX + "2026-10-05"))
        } finally {
            server.close()
        }
    }

    private companion object {
        const val USER = 334081L
        const val DEVICE = "6f1c2d3e-4b5a-4c6d-8e7f-9a0b1c2d3e4f"
    }
}
