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
    private val file get() = java.io.File(context.noBackupFilesDir, "aron/telemetry-test.json")

    /** 2026-10-05 in Dhaka at [hh]:[mm] (UTC+6). */
    private fun dhaka(hh: Int, mm: Int, day: Int = 5) = 1_791_158_400_000L + (day - 5) * 86_400_000L + ((hh - 6) * 3600L + mm * 60L) * 1000L
    private var now = dhaka(7, 0)
    private var elapsed = 5_000_000L
    private val clock = object : WallClock {
        override fun nowMs(): Long = now
        override fun elapsedRealtimeMs(): Long = elapsed
    }

    private class FakeProbe : TelemetryProbe {
        var boot: Int? = null
        override fun bootCount() = boot
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
    /** One per process in the app; a new instance here reads the same file, as a new process would. */
    private val telemetry by lazy { DeviceTelemetry(file, probe, clock) }

    @Before fun setUp() {
        file.delete()
        context.deleteDatabase(AronDatabase.fileName(USER))
        db = AronDatabase.open(context, USER, null)
    }

    @After fun tearDown() { db.close(); file.delete() }

    private suspend fun day(day: Int = 6): JsonObject {
        now = dhaka(9, 0, day)
        return telemetry.pendingDay(true, 1024)!!.second.jsonObject
    }

    @Test fun bytesAreBilledToTheNetworkOfThePreviousSampleAndRebootsAreSeenFromElapsedTime() = runBlocking {
        telemetry.sample() // baseline on mobile; this process's CPU so far counts
        probe.bytes = 5_000; probe.cpu = 400; elapsed += 60_000
        probe.metered = false // Wi-Fi now: the 4,000 bytes moved before the change were on mobile
        telemetry.sample()
        probe.bytes = 6_000; elapsed += 60_000
        telemetry.sample() // +1,000 on Wi-Fi
        // Reboot, then more than the old total moved: elapsed time went back, so all 9,000 bytes since boot count.
        probe.processToken = "p2"; probe.cpu = 50; probe.bytes = 9_000; elapsed = 120_000
        telemetry.sample()
        telemetry.noteWake(2_500)
        val d = day()
        assertEquals("2026-10-05", d["d"]!!.jsonPrimitive.content)
        assertEquals(4_000L, d["b_mob"]!!.jsonPrimitive.long)
        assertEquals(1_000L + 9_000L, d["b_wifi"]!!.jsonPrimitive.long)
        assertNull("not reported: absent, not zero", d["b_mob_media"])
        assertEquals(100L + 300L + 50L, d["cpu_ms"]!!.jsonPrimitive.long)
        assertEquals(2, d["starts"]!!.jsonPrimitive.int)
        assertEquals(2_500L, d["wake_ms"]!!.jsonPrimitive.long)
    }

    /** Re-check: a reboot whose new uptime passed the old one is still a reboot, by boot count. */
    @Test fun aRebootIsSeenByBootCountEvenWhenUptimeGrewPastTheOldSample() = runBlocking {
        probe.boot = 41; elapsed = 10_000
        telemetry.sample()
        probe.boot = 42; elapsed = 900_000; probe.bytes = 5_000; probe.processToken = "p2"
        telemetry.sample()
        assertEquals(5_000L, day()["b_mob"]!!.jsonPrimitive.long) // all bytes since the new boot, not 5,000 - 1,000
    }

    /** Waits for the background write by reading the file, never on a fixed sleep. */
    private suspend fun awaitFile(contains: String) {
        kotlinx.coroutines.withTimeout(10_000) { while (!(file.isFile && file.readText().contains(contains))) kotlinx.coroutines.yield() }
    }

    @Test fun onlyARegainAfterALossIsKeptAndOldDaysArePrunedWithoutABatch() = runBlocking {
        telemetry.onNetworkChange(true) // the callback at registration (online at start): not a regain
        now = dhaka(10, 0)
        telemetry.onNetworkChange(false)
        now = dhaka(10, 30)
        telemetry.onNetworkChange(true) // the time is taken at the callback, not when the write runs
        now = dhaka(11, 0)
        awaitFile("regained")
        assertEquals("2026-10-05T04:30:00.000Z", day()["regained"]!!.jsonPrimitive.content)
        now = dhaka(9, 0, 20)
        telemetry.sample() // day 20: day 5 is past the keep window and goes on this write
        assertTrue(!file.readText().contains("2026-10-05"))
    }

    /** Re-check: a process that starts offline gets no "lost" callback; its first connection is still a regain. */
    @Test fun aProcessStartedOfflineRecordsItsFirstConnectionAsARegain() = runBlocking {
        probe.metered = null
        val t = telemetry
        now = dhaka(9, 15)
        t.onNetworkChange(true)
        awaitFile("regained")
        assertEquals("2026-10-05T03:15:00.000Z", day()["regained"]!!.jsonPrimitive.content)
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
        now = dhaka(0, 5, 6)
        val (date, obj) = telemetry.pendingDay(true, 1024)!!
        assertEquals("2026-10-05", date)
        assertTrue(obj.toString().toByteArray().size <= 1024)
        assertTrue(telemetry.pendingDay(true, 64)!!.second.toString().toByteArray().size <= 64) // the guard trims
        telemetry.markSent(date)
        assertNull(telemetry.pendingDay(true, 1024))
    }

    @Test fun disabledOldAndTwiceRefusedDaysAreDropped() = runBlocking {
        telemetry.sample()
        now = dhaka(9, 0, 6); telemetry.sample()
        now = dhaka(9, 0, 7)
        assertNull(telemetry.pendingDay(false, 1024))
        assertNull(telemetry.pendingDay(true, 1024))
        telemetry.sample() // day 7
        now = dhaka(9, 0, 16)
        assertNull("older than 7 days: dropped unsent", telemetry.pendingDay(true, 1024))
        telemetry.sample() // day 16
        now = dhaka(9, 0, 17)
        telemetry.markFailed("2026-10-16")
        assertNotNull(telemetry.pendingDay(true, 1024))
        telemetry.markFailed("2026-10-16")
        assertNull("refused twice: never holds back a batch again", telemetry.pendingDay(true, 1024))
    }

    /** Shared phone: one device stream; gps is summed over the users' databases, bytes are never counted per user. */
    @Test fun aSharedPhoneKeepsOneStreamAndSumsGpsOverUsers() = runBlocking {
        val other = AronDatabase.open(context, USER + 1, null)
        try {
            TestRows.visit(outletId = 50001L, seq = 1).let { (v, f) -> CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }.recordVisitOpen(v, f) }
            TestRows.visit(outletId = 50002L, seq = 2).let { (v, f) -> CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }.recordVisitOpen(v, f.copy(reused = true)) }
            TestRows.visit(outletId = 50003L, seq = 3).let { (v, f) -> CaptureRepository(other) { "2026-10-05T04:36:00.000Z" }.recordVisitOpen(v, f) }
            telemetry.sample() // user A's run
            probe.bytes = 3_000; elapsed += 60_000
            telemetry.sample() // user B's run: +2,000, once
            telemetry.noteGps(USER) { d -> DeviceTelemetry.gpsFixes(db, d) }
            telemetry.noteGps(USER + 1) { d -> DeviceTelemetry.gpsFixes(other, d) }
            telemetry.noteGps(USER) { d -> DeviceTelemetry.gpsFixes(db, d) } // a second run recounts, never adds
            val d = day()
            assertEquals(2, d["gps"]!!.jsonPrimitive.int)
            assertEquals(2_000L, d["b_mob"]!!.jsonPrimitive.long)
        } finally {
            other.close()
            context.deleteDatabase(AronDatabase.fileName(USER + 1))
        }
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
            TestRows.visit(outletId = 50001L, seq = 1).let { (v, f) -> CaptureRepository(db) { "2026-10-06T03:00:00.000Z" }.recordVisitOpen(v, f) }
            val ok = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
            val client = AronApiClient(ApiOrigin.parse(server.url("/").toString().trimEnd('/'), allowCleartextLoopback = true), ok, ClientIdentity("1.0.3+10003") { DEVICE })
            val auth = object : UploadAuth {
                override suspend fun token(userId: Long): String? = "upload-1"
                override suspend fun refresh(userId: Long, rejected: String?): Boolean = false
            }
            fun engine() = SyncEngine(USER, db, SyncBatchApi(client, null), auth, { DEVICE }, "1.0.3+10003", clock, SyncPolicy(), random = Random(7),
                telemetry = telemetry.forBatch { null })
            // Re-check: holds and outages are never refusals, however many.
            repeat(3) {
                fake.failBefore += 503
                assertEquals(SyncStop.RETRY_LATER, engine().run(SyncTrigger.MANUAL).stop)
            }
            assertNotNull(telemetry.pendingDay(true, 1024))
            assertEquals(SyncStop.DRAINED, engine().run(SyncTrigger.MANUAL).stop)
            val carried = fake.requests.mapNotNull { it.body?.get("telemetry")?.jsonObject?.get("d")?.jsonPrimitive?.content }
            assertEquals(List(4) { "2026-10-05" }, carried)
            assertNull(telemetry.pendingDay(true, 1024))
        } finally {
            server.close()
        }
    }

    /** A batch the server refuses (400) twice while carrying the day stops carrying it; the records go on alone. */
    @Test fun aRefusedDayStopsRidingAfterTwoRefusals() = runBlocking {
        val server = MockWebServer()
        val fake = FakeIngestServer()
        server.dispatcher = fake
        server.start()
        try {
            telemetry.sample()
            now = dhaka(9, 0, 6)
            TestRows.visit(outletId = 50001L, seq = 1).let { (v, f) -> CaptureRepository(db) { "2026-10-06T03:00:00.000Z" }.recordVisitOpen(v, f) }
            val ok = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
            val client = AronApiClient(ApiOrigin.parse(server.url("/").toString().trimEnd('/'), allowCleartextLoopback = true), ok, ClientIdentity("1.0.3+10003") { DEVICE })
            val auth = object : UploadAuth {
                override suspend fun token(userId: Long): String? = "upload-1"
                override suspend fun refresh(userId: Long, rejected: String?): Boolean = false
            }
            fun engine() = SyncEngine(USER, db, SyncBatchApi(client, null), auth, { DEVICE }, "1.0.3+10003", clock, SyncPolicy(), random = Random(7),
                telemetry = telemetry.forBatch { null })
            fake.failBefore += 400
            fake.failBefore += 400 // envelope refusals; a single-family 500 counts the same way
            engine().run(SyncTrigger.MANUAL)
            engine().run(SyncTrigger.MANUAL)
            assertNull(telemetry.pendingDay(true, 1024))
            assertEquals(SyncStop.DRAINED, engine().run(SyncTrigger.MANUAL).stop)
            assertNull(fake.requests.last().body?.get("telemetry"))
        } finally {
            server.close()
        }
    }

    /** Round 3: a day the server answers with 500 is dropped before a lone sale family runs out of retries. */
    @Test fun aDayThatMakesTheServerFailIsDroppedBeforeTheSaleRunsOutOfRetries() = runBlocking {
        val server = MockWebServer()
        val fake = FakeIngestServer()
        server.dispatcher = fake
        server.start()
        try {
            telemetry.sample()
            now = dhaka(9, 0, 6)
            val (visit, fix) = TestRows.visit(outletId = 50001L, seq = 1)
            CaptureRepository(db) { "2026-10-06T03:00:00.000Z" }.recordVisitOpen(visit, fix)
            val ok = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
            val client = AronApiClient(ApiOrigin.parse(server.url("/").toString().trimEnd('/'), allowCleartextLoopback = true), ok, ClientIdentity("1.0.3+10003") { DEVICE })
            val auth = object : UploadAuth {
                override suspend fun token(userId: Long): String? = "upload-1"
                override suspend fun refresh(userId: Long, rejected: String?): Boolean = false
            }
            fun engine() = SyncEngine(USER, db, SyncBatchApi(client, null), auth, { DEVICE }, "1.0.3+10003", clock, SyncPolicy(), random = Random(7),
                telemetry = telemetry.forBatch { null })
            fake.failBefore += 500
            fake.failBefore += 500
            engine().run(SyncTrigger.MANUAL)
            engine().run(SyncTrigger.MANUAL)
            assertNull(telemetry.pendingDay(true, 1024))
            assertEquals(SyncStop.DRAINED, engine().run(SyncTrigger.MANUAL).stop)
            assertEquals("acked", db.outboxDao().byClientUuid(visit.clientUuid)!!.state)
        } finally {
            server.close()
        }
    }

    /** Re-check guard: a 500 on a batch of several families only splits it; the day is not charged with a refusal. */
    @Test fun aMultiFamily500NeverChargesTheDay() = runBlocking {
        val server = MockWebServer()
        val fake = FakeIngestServer()
        server.dispatcher = fake
        server.start()
        try {
            telemetry.sample()
            now = dhaka(9, 0, 6)
            val capture = CaptureRepository(db) { "2026-10-06T03:00:00.000Z" }
            listOf(50001L, 50002L).forEachIndexed { i, outlet -> TestRows.visit(outletId = outlet, seq = i + 1).let { (v, f) -> capture.recordVisitOpen(v, f) } }
            val ok = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
            val client = AronApiClient(ApiOrigin.parse(server.url("/").toString().trimEnd('/'), allowCleartextLoopback = true), ok, ClientIdentity("1.0.3+10003") { DEVICE })
            val auth = object : UploadAuth {
                override suspend fun token(userId: Long): String? = "upload-1"
                override suspend fun refresh(userId: Long, rejected: String?): Boolean = false
            }
            val inner = telemetry.forBatch { null }
            var charged = 0
            val counting = object : BatchTelemetry by inner {
                override suspend fun failed(date: String) { charged++; inner.failed(date) }
            }
            fake.failBefore += 500
            SyncEngine(USER, db, SyncBatchApi(client, null), auth, { DEVICE }, "1.0.3+10003", clock, SyncPolicy(), random = Random(7),
                telemetry = counting).run(SyncTrigger.MANUAL)
            assertEquals(0, charged)
        } finally {
            server.close()
        }
    }

    private companion object {
        const val USER = 334081L
        const val DEVICE = "6f1c2d3e-4b5a-4c6d-8e7f-9a0b1c2d3e4f"
    }
}
