package com.aktcl.aron.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.contract.SyncTrigger
import com.aktcl.aron.core.common.WallClock
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.OutboxState
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.network.ApiOrigin
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.ClientIdentity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * F-SYS-047 acceptance: after a server restore announces a new generation, the device re-sends the rows acked since the
 * restore point and the registry dedupes them with zero duplicates. Time is a test clock (no real-clock reads).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GenerationResyncTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private val fake = FakeIngestServer()
    private lateinit var db: AronDatabase
    private lateinit var client: AronApiClient
    private var hint: String? = null

    private object Clock : WallClock {
        var now = 1_791_194_400_000L // 2026-10-05T10:00:00Z
        override fun nowMs(): Long = now
        override fun elapsedRealtimeMs(): Long = 1_000_000L
    }

    @Before fun setUp() {
        Clock.now = 1_791_194_400_000L
        server.dispatcher = fake
        server.start()
        context.deleteDatabase(AronDatabase.fileName(USER))
        db = AronDatabase.open(context, USER, null)
        val ok = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        client = AronApiClient(ApiOrigin.parse(server.url("/").toString().trimEnd('/'), allowCleartextLoopback = true), ok, ClientIdentity("1.0.3+10003") { DEVICE })
    }

    @After fun tearDown() {
        db.close()
        server.close()
    }

    private fun engine(seed: Int = 7) = SyncEngine(
        USER, db, SyncBatchApi(client, null), SyncEngineTest.FakeAuth(), { DEVICE }, "1.0.3+10003", Clock, random = Random(seed),
        generationApi = SyncGenerationApi(client), generationHint = { hint },
    )

    private var n = 0
    private suspend fun sale(): String {
        n++
        val repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }
        val (visit, fix) = TestRows.visit(outletId = 50001L + n, seq = n)
        repo.recordVisitOpen(visit, fix)
        val s = TestRows.sale(visit.clientUuid, memoNo = "sr334001-261005-%03d".format(n))
        repo.recordSale(s.copy(memo = s.memo.copy(outletId = 50001L + n)))
        repo.recordVisitClose(TestRows.close(visit.clientUuid))
        return s.memo.clientUuid
    }

    private fun countState(state: String) = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM outbox WHERE state = ?", arrayOf(state)).use { it.moveToFirst(); it.getInt(0) }
    private fun total() = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM outbox").use { it.moveToFirst(); it.getInt(0) }
    private fun sentTriggers() = fake.requests.mapNotNull { it.body?.get("trigger")?.jsonPrimitive?.content }

    /** Three sales at 10:00, three at 12:00 (each family is 7 rows), all acked; the server then loses everything after 11:00. */
    private suspend fun dayThenRestore(): Int {
        repeat(3) { sale() }
        assertEquals(SyncStop.DRAINED, engine().run(SyncTrigger.MANUAL).stop)
        assertEquals("the first generation is the baseline", fake.generation, db.referenceDao().meta(SyncEngine.KEY_GENERATION))
        val kept = fake.registry.size
        Clock.now += 2 * 3_600_000L // 12:00
        repeat(3) { sale() }
        assertEquals(SyncStop.DRAINED, engine().run(SyncTrigger.MANUAL).stop)
        assertEquals(total(), fake.registry.size)
        fake.restore(keep = kept, newGeneration = NEW_GEN, lostAfter = "2026-10-05T11:00:00.000Z")
        return kept
    }

    @Test
    fun aRestoreReSendsOnlyTheRowsAckedSinceTheRestorePointWithZeroDuplicates() = runBlocking {
        val kept = dayThenRestore()
        val lost = total() - kept
        Clock.now += 3_600_000L // 13:00
        // The phone hears of the new generation from any answer (here the header hint); this run only notes it.
        hint = NEW_GEN
        val noted = engine().run(SyncTrigger.FOREGROUND)
        assertTrue(noted.resyncRequested)
        assertEquals(0, fake.generationReads)
        assertEquals("nothing re-sent before the jittered run", total(), countState(OutboxState.ACKED))
        // The scheduler's jittered resync run.
        val r = engine().run(SyncTrigger.RESYNC)
        assertEquals(SyncStop.DRAINED, r.stop)
        assertEquals(1, fake.generationReads)
        assertEquals("exactly the lost rows went again", lost, r.acked)
        assertEquals("every row once on the server", total(), fake.registry.size)
        assertEquals(n, fake.storedOf("memo").size)
        assertEquals(total(), countState(OutboxState.ACKED))
        assertEquals("resync", sentTriggers().last())
        assertEquals(NEW_GEN, db.referenceDao().meta(SyncEngine.KEY_GENERATION))
        assertNull(db.referenceDao().meta(SyncEngine.KEY_GENERATION_SEEN))
        // Handled once: later runs re-send nothing.
        val before = fake.requests.size
        engine().run(SyncTrigger.RESYNC)
        assertEquals(before, fake.requests.size)
        assertEquals(1, fake.generationReads)
    }

    @Test
    fun aBatchAnswerWithANewGenerationIsNotedAndHandledOnceDue() = runBlocking {
        dayThenRestore()
        Clock.now += 3_600_000L
        sale() // a new sale's batch carries the new generation in its answer
        val r = engine().run(SyncTrigger.WRITE_DEBOUNCE)
        assertTrue(r.resyncRequested)
        assertTrue(db.referenceDao().meta(SyncEngine.KEY_GENERATION_SEEN)!!.startsWith(NEW_GEN))
        // Any run after the due time (at most the 900 s jitter) handles it, even if the resync job was lost.
        Clock.now += 901_000L
        engine().run(SyncTrigger.PERIODIC)
        assertEquals(1, fake.generationReads)
        assertEquals(total(), fake.registry.size)
        assertEquals(total(), countState(OutboxState.ACKED))
    }

    @Test
    fun rowsOlderThanTheResyncWindowAreNotReSent() = runBlocking {
        dayThenRestore()
        fake.lostAfterUtc = "2026-10-01T00:00:00.000Z" // a restore point days back: only cfg.sync.resync_window_h (24 h) counts
        Clock.now += 40 * 3_600_000L // 2026-10-07 02:00: the 10:00 rows are outside 24 h, the 12:00 rows too
        hint = NEW_GEN
        engine().run(SyncTrigger.RESYNC)
        assertEquals(1, fake.generationReads)
        assertEquals("nothing inside the window", total(), countState(OutboxState.ACKED))
        assertEquals(NEW_GEN, db.referenceDao().meta(SyncEngine.KEY_GENERATION))
    }

    @Test
    fun aFalseAlarmAndAnOfflineStatementReSendNothing() = runBlocking {
        repeat(2) { sale() }
        engine().run(SyncTrigger.MANUAL)
        val acked = countState(OutboxState.ACKED)
        hint = NEW_GEN
        fake.generationFails = 1
        engine().run(SyncTrigger.RESYNC) // statement unavailable: the note stays
        assertTrue(db.referenceDao().meta(SyncEngine.KEY_GENERATION_SEEN)!!.startsWith(NEW_GEN))
        assertEquals(acked, countState(OutboxState.ACKED))
        // The server still states the old generation (a stale replica header): nothing re-sent, the note goes.
        engine().run(SyncTrigger.RESYNC)
        assertNull(db.referenceDao().meta(SyncEngine.KEY_GENERATION_SEEN))
        assertEquals(acked, countState(OutboxState.ACKED))
        assertEquals(fake.generation, db.referenceDao().meta(SyncEngine.KEY_GENERATION))
        // The nil generation is never a reason.
        hint = SyncEngine.NIL_GENERATION
        engine().run(SyncTrigger.RESYNC)
        assertEquals(2, fake.generationReads)
    }

    @Test
    fun aKillAfterThePutBackSendsTheRowsOnceAndNeverPutsThemBackTwice() = runBlocking {
        val kept = dayThenRestore()
        Clock.now += 3_600_000L
        hint = NEW_GEN
        // The run puts the rows back, then the process dies before any batch leaves.
        val dead = SyncEngine(
            USER, db, BatchSender { _, _, _, _, _ -> throw IllegalStateException("killed") }, SyncEngineTest.FakeAuth(), { DEVICE }, "1.0.3+10003", Clock,
            random = Random(1), generationApi = SyncGenerationApi(client), generationHint = { hint },
        )
        runCatching { dead.run(SyncTrigger.RESYNC) }
        db.close(); db = AronDatabase.open(context, USER, null)
        assertEquals(total() - kept, countState(OutboxState.PENDING) + countState(OutboxState.IN_FLIGHT))
        engine().run(SyncTrigger.WRITE_DEBOUNCE)
        assertEquals(1, fake.generationReads)
        assertEquals(total(), fake.registry.size)
        assertEquals(total(), countState(OutboxState.ACKED))
        assertFalse(engine().run(SyncTrigger.RESYNC).resyncRequested)
    }

    companion object {
        const val USER = 334002L
        const val DEVICE = "6f1c2d3e-4b5a-4c6d-8e7f-9a0b1c2d3e4f"
        const val NEW_GEN = "4d1e2f3a-5b6c-4d7e-8f90-a1b2c3d4e5f6"
    }
}
