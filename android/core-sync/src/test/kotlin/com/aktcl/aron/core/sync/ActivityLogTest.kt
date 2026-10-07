package com.aktcl.aron.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.contract.SyncTrigger
import com.aktcl.aron.core.common.WallClock
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.reference.BundleReference
import com.aktcl.aron.core.database.repo.ReferenceRepository
import com.aktcl.aron.core.network.ApiOrigin
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.ClientIdentity
import com.aktcl.aron.core.session.TrustedClockSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** F-SYS-024: events ride the next batch as one record (no request of their own) and appear once on the server. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ActivityLogTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private val fake = FakeIngestServer()
    private lateinit var db: AronDatabase
    private val wall = 1_791_165_600_000L // 2026-10-05T02:00Z

    @Before fun setUp() {
        server.dispatcher = fake
        server.start()
        context.deleteDatabase(AronDatabase.fileName(USER))
        db = AronDatabase.open(context, USER, null)
    }

    @After fun tearDown() { db.close(); server.close() }

    private val logJob = kotlinx.coroutines.SupervisorJob()

    private fun log(pct: Int?): ActivityLog = runBlocking {
        val base = Json.parseToJsonElement(javaClass.getResourceAsStream("/fixtures/sr_day_bundle.json")!!.readBytes().decodeToString()).jsonObject
        val values = pct?.let { """{"key":"cfg.app.activity_log_sample_pct","value":$it,"scope_type":"global","requires_ack":false}""" } ?: ""
        val raw = JsonObject(base + ("config" to Json.parseToJsonElement("""{"config_version":318,"values":[$values],"scheduled":[]}""")))
        ReferenceRepository(db).apply(BundleReference.json.decodeFromJsonElement(BundleReference.serializer(), raw), raw)
        val clock = TrustedClockSource(tmp.newFile(), { 41 }, { 10_000_000L }, { wall })
        ActivityLog({ db }, clock, { true }, kotlinx.coroutines.CoroutineScope(logJob + kotlinx.coroutines.Dispatchers.IO)).also { it.refreshSampling(USER) }
    }

    private suspend fun activityRows() = db.outboxDao().nextPending(100).filter { it.recordType == "activity_log" }

    private fun engine(): SyncEngine {
        val ok = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        val client = AronApiClient(ApiOrigin.parse(server.url("/").toString().trimEnd('/'), allowCleartextLoopback = true), ok, ClientIdentity("1.0.3+10003") { DEVICE })
        val auth = object : UploadAuth {
            override suspend fun token(userId: Long): String? = "upload-1"
            override suspend fun refresh(userId: Long, rejected: String?): Boolean = false
        }
        val clock = object : WallClock { override fun nowMs() = wall; override fun elapsedRealtimeMs() = 1_000_000L }
        return SyncEngine(USER, db, SyncBatchApi(client, null), auth, { DEVICE }, "1.0.3+10003", clock, SyncPolicy(), random = Random(7))
    }

    @Test fun eventsWaitInMemoryAndBecomeOneRecordAtFlush() = runBlocking {
        val log = log(100)
        assertTrue(log.log(USER, "home", "open"))
        assertTrue(log.log(USER, "sale.review", "save", durationMs = 4_200))
        assertFalse("contract names only", log.log(USER, "Home Screen", "open"))
        assertTrue("nothing written per event", activityRows().isEmpty())
        assertEquals(1, log.flush(USER))
        assertEquals(0, log.flush(USER))
        val record = Json.parseToJsonElement(activityRows().single().payloadJson).jsonObject
        val events = record["payload"]!!.jsonObject["events"]!!.jsonArray
        assertEquals(2, events.size)
        assertEquals(setOf("at", "screen", "action", "duration_ms"), events[1].jsonObject.keys)
        assertFalse(record.containsKey("route_id"))
    }

    @Test fun aUserOutsideTheSampleLogsNothing() = runBlocking {
        val log = log(0)
        assertFalse(log.log(USER, "home", "open"))
        assertEquals(0, log.flush(USER))
    }

    @Test fun moreThan200EventsSplitIntoRecordsOf200() = runBlocking {
        val log = log(100)
        repeat(250) { log.log(USER, "home", "tap") }
        logJob.children.toList().forEach { it.join() } // the automatic flush at 200
        log.flush(USER)
        val sizes = activityRows().map { Json.parseToJsonElement(it.payloadJson).jsonObject["payload"]!!.jsonObject["events"]!!.jsonArray.size }
        assertEquals(250, sizes.sum())
        assertTrue(sizes.all { it in 1..200 })
    }

    @Test fun aReplayedBatchStoresTheEventsOnce() = runBlocking {
        val log = log(100)
        log.log(USER, "home", "open")
        log.flush(USER)
        fake.dropAfterCommit = 1 // the server commits, the answer is lost: the same batch is sent again
        engine().run(SyncTrigger.MANUAL)
        engine().run(SyncTrigger.MANUAL)
        assertEquals(1, fake.storedOf("activity_log").size)
        assertEquals(0, db.outboxDao().unsentCount())
    }

    /** Checker: the same record in a NEW batch is a per-uuid duplicate on the server, never a second copy. */
    @Test fun theSameRecordInANewBatchIsADuplicate() = runBlocking {
        val log = log(100)
        log.log(USER, "home", "open")
        log.flush(USER)
        engine().run(SyncTrigger.MANUAL)
        db.openHelper.writableDatabase.execSQL("UPDATE outbox SET state = 'pending', batch_uuid = NULL WHERE record_type = 'activity_log'")
        engine().run(SyncTrigger.MANUAL)
        assertEquals(1, fake.storedOf("activity_log").size)
        assertEquals("acked", activityState())
    }

    /** No request of its own: the events ride the batch of the visit. */
    @Test fun theEventsRideTheBusinessBatch() = runBlocking {
        val log = log(100)
        val repo = com.aktcl.aron.core.database.repo.CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }
        val (visit, fix) = TestRows.visit()
        repo.recordVisitOpen(visit, fix)
        log.log(USER, "visit", "open")
        log.flush(USER)
        engine().run(SyncTrigger.MANUAL)
        assertEquals(1, fake.requests.count { it.body != null })
        assertEquals(1, fake.storedOf("activity_log").size)
        assertEquals(1, fake.storedOf("visit").size)
    }

    /** Checker: 200 waiting events flush on the log's own scope, without a screen or a sync asking. */
    @Test fun aFullBufferFlushesByItself() = runBlocking {
        val log = log(100)
        repeat(200) { log.log(USER, "home", "tap") }
        logJob.children.toList().forEach { it.join() }
        assertEquals(1, activityRows().size)
        assertEquals(0, log.pending(USER))
    }

    /** Checker: a user first seen after start (an in-app login) is sampled by the first event; the next ones count. */
    @Test fun samplingIsDecidedByTheFirstEventOfANewUser() = runBlocking {
        val log = log(100)
        val other = USER + 1
        assertFalse(log.log(other, "home", "open"))
        logJob.children.toList().forEach { it.join() }
        assertTrue(log.log(other, "home", "open"))
    }

    private suspend fun activityState() = db.outboxDao().nextPending(100).let { _ ->
        db.openHelper.readableDatabase.query("SELECT state FROM outbox WHERE record_type = 'activity_log'").use { it.moveToFirst(); it.getString(0) }
    }

    private companion object {
        const val USER = 334005L
        const val DEVICE = "6f1c2d3e-4b5a-4c6d-8e7f-9a0b1c2d3e4f"
    }
}
