package com.aktcl.aron.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.contract.SyncTrigger
import com.aktcl.aron.core.common.WallClock
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.OutboxEntity
import com.aktcl.aron.core.database.entity.OutboxState
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.network.ApiOrigin
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.ClientIdentity
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.random.Random

/** Independent checker (refuter) tests for F-SYS-008. Each test states the behaviour the spec requires. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CheckerF008Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private val fake = FakeIngestServer()

    /** client_uuids whose presence in a batch makes the server answer 400 ERR_VALIDATION (strict request, s3.1 item 2). */
    private val invalid400 = mutableSetOf<String>()
    private lateinit var db: AronDatabase
    private lateinit var api: SyncBatchApi
    private val auth = SyncEngineTest.FakeAuth()

    private object Clock : WallClock {
        override fun nowMs(): Long = 1_791_194_400_000L
        override fun elapsedRealtimeMs(): Long = 1_000_000L
    }

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (invalid400.isNotEmpty() && request.headers["Content-Encoding"] == "gzip") {
                    val raw = request.body?.toByteArray() ?: ByteArray(0)
                    val text = GZIPInputStream(raw.inputStream()).readBytes().decodeToString()
                    if (invalid400.any { text.contains(it) }) {
                        return MockResponse.Builder().code(400).addHeader("X-Aron-Api", "1").addHeader("Content-Type", "application/json")
                            .body("""{"type":"about:blank","status":400,"code":"ERR_VALIDATION"}""").build()
                    }
                }
                return fake.dispatch(request)
            }
        }
        server.start()
        context.deleteDatabase(AronDatabase.fileName(USER))
        db = AronDatabase.open(context, USER, null)
        val ok = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        val client = AronApiClient(ApiOrigin.parse(server.url("/").toString().trimEnd('/'), allowCleartextLoopback = true), ok, ClientIdentity("1.0.3+10003") { DEVICE })
        api = SyncBatchApi(client, null)
    }

    @After fun tearDown() {
        db.close()
        server.close()
    }

    private fun engine(policy: SyncPolicy = SyncPolicy()) =
        SyncEngine(USER, db, api, auth, { DEVICE }, "1.0.3+10003", Clock, policy, random = Random(7))

    private suspend fun sale(n: Int): String {
        val repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }
        val (visit, fix) = TestRows.visit(outletId = 50001L + n, seq = n)
        repo.recordVisitOpen(visit, fix)
        val s = TestRows.sale(visit.clientUuid, memoNo = "sr334001-261005-%03d".format(n))
        repo.recordSale(s.copy(memo = s.memo.copy(outletId = 50001L + n)))
        repo.recordVisitClose(TestRows.close(visit.clientUuid))
        return s.memo.clientUuid
    }

    private suspend fun all(): List<OutboxEntity> {
        val out = ArrayList<OutboxEntity>()
        val c = db.openHelper.readableDatabase.query("SELECT client_uuid FROM outbox ORDER BY seq")
        while (c.moveToNext()) out += db.outboxDao().byClientUuid(c.getString(0))!!
        c.close()
        return out
    }

    /**
     * s4.6: a row is `rejected(retry_exhausted)` only after `row_max_retries` (10) failed attempts. Two short server-wide 500
     * episodes (two runs each) must not exhaust healthy rows: each bisect re-batch increments `attempts` of every row in the
     * halves, so one run of 500s costs a row log2(batch) attempts instead of one.
     */
    @Test fun transient500OutageMustNotExhaustHealthyRows() = runBlocking {
        repeat(30) { sale(it + 1) }
        repeat(3) {
            repeat(200) { fake.failBefore += 500 }
            engine().run(SyncTrigger.PERIODIC)
            fake.failBefore.clear()
        }
        val maxAttempts = all().maxOf { it.attempts }
        repeat(3) { engine().run(SyncTrigger.PERIODIC) }
        val exhausted = all().filter { it.state == OutboxState.REJECTED }
        assertTrue(
            "after 3 runs of 500s (max attempts $maxAttempts) ${exhausted.size} healthy rows were rejected(retry_exhausted)",
            exhausted.isEmpty(),
        )
        assertEquals(30, fake.storedOf("memo").size)
    }

    /**
     * One record that the strict request validator refuses (400 ERR_VALIDATION, s3.1 item 2) must not strand every other row:
     * today the engine releases the whole batch and re-assembles the same rows forever (no bisect, no exhaustion).
     */
    @Test fun oneInvalidRecordMustNotBlockTheWholeOutbox() = runBlocking {
        val bad = sale(1); sale(2); sale(3)
        invalid400 += bad
        repeat(15) { engine().run(SyncTrigger.PERIODIC) }
        assertEquals("the two healthy sales reach the server", 2, fake.storedOf("memo").size)
    }

    /**
     * s4.7: on 500 the phone bisects until the failing family is alone, then sends that family alone with backoff; the
     * families behind it should go at normal batch size. The reduced `limit` is never reset, so every later batch in the run
     * is a few rows (families split too) and the run hits `maxBatchesPerRun` with healthy rows unsent.
     */
    @Test fun afterIsolatingAPoisonFamilyTheRestGoAtNormalBatchSize() = runBlocking {
        val poison = sale(1)
        repeat(29) { sale(it + 2) }
        fake.poison += poison
        val r = engine().run(SyncTrigger.MANUAL)
        assertNotEquals("run ended at the batch cap with ${r.unsent} rows unsent after ${r.batches} batches", SyncStop.RUN_LIMIT, r.stop)
        assertEquals(29, fake.storedOf("memo").size)
    }

    private companion object {
        const val USER = 334002L
        const val DEVICE = "6f1c2d3e-4b5a-4c6d-8e7f-9a0b1c2d3e4f"
    }
}
