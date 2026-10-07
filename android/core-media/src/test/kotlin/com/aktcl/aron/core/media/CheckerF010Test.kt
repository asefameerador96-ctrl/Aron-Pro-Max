package com.aktcl.aron.core.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.aktcl.aron.core.common.WallClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Independent checker for F-SYS-010 (media queue and photo upload). Each test here demonstrates a defect. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CheckerF010Test {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_791_000_000_000L
    private val clock = object : WallClock { override fun nowMs() = now; override fun elapsedRealtimeMs() = now }
    private val raw = JvmImageCodec.encode(JvmImageCodec.frame(640, 480, 30), 95)
    private val dir get() = File(tmp.root, "media/u7")

    inner class Fake(private val putDelayMs: Long = 0, private val sasAnswer: (() -> SasResult)? = null) : MediaUploadApi {
        var sasCalls = 0
        var puts = 0
        override suspend fun sas(items: List<SasItem>): SasResult {
            sasCalls++
            sasAnswer?.let { return it() }
            return SasResult.Ok(items.map { SasTarget(it.mediaUuid, "https://blob.example/x?sig=s", "photos/${it.businessDate}/00000000-0000-4000-8000-000000000007/${it.mediaUuid}.jpg", emptyMap(), false) })
        }
        override suspend fun put(target: SasTarget, jpeg: ByteArray): PutResult { puts++; delay(putDelayMs); return PutResult.STORED }
    }

    private val refs = mutableMapOf<String, RefState>()
    private val probe = RecordAckProbe { refs[it] ?: RefState.ABSENT }
    private val sink = MediaMetaSink { true }
    private fun uuid(n: Int) = "%08x-0000-4000-8000-%012x".format(n, n)
    private fun uploader(api: MediaUploadApi, store: MediaStore = MediaStore(dir)) = MediaUploader(store, api, probe, sink, clock, { MediaConfig() }, { true })

    private suspend fun queued(n: Int, record: RefState? = RefState.ACKED): String {
        val store = MediaStore(dir)
        val c = PhotoCapturer({ raw }, JvmImageCodec, store, clock, { "2026-10-07" }, freeBytes = { Long.MAX_VALUE }, cpu = Dispatchers.Unconfined)
        val id = uuid(n)
        c.capture(id)
        c.attach(id, MediaRef("force_sale", "visit", uuid(1000 + n)), null)
        record?.let { refs[uuid(1000 + n)] = it }
        return id
    }

    /** D1: a damaged file (or a 400 / bad path / put_rejected) has no exit: it asks for a SAS on every run, forever. */
    @Test fun aDamagedFileDoesNotAskForASasForever() = runTest {
        val id = queued(1)
        File(dir, "$id.jpg").writeBytes(byteArrayOf(1, 2, 3))
        val api = Fake()
        val up = uploader(api)
        repeat(20) { up.run(NetworkKind.UNMETERED); now += MediaUploader.DAY_MS }
        assertTrue("SAS asked ${api.sasCalls} times for a photo that can never upload", api.sasCalls <= 5)
    }

    @Test fun aServerRejectedBatchIsNotRetriedForever() = runTest {
        queued(2)
        val api = Fake(sasAnswer = { SasResult.Rejected("ERR_VALIDATION") })
        val up = uploader(api)
        repeat(20) { up.run(NetworkKind.UNMETERED); now += MediaUploader.DAY_MS }
        assertTrue("SAS asked ${api.sasCalls} times for a rejected photo", api.sasCalls <= 5)
    }

    /** D2: aron-media and aron-media-any (or -recheck/-fallback) can run at once: the same photo is PUT twice. */
    @Test fun twoConcurrentRunsUploadAPhotoOnce() = runTest {
        queued(3)
        val api = Fake(putDelayMs = 1_000)
        val store = MediaStore(dir)
        launch { uploader(api, store).run(NetworkKind.UNMETERED) }
        launch { uploader(api, store).run(NetworkKind.UNMETERED) }
        testScheduler.advanceUntilIdle()
        assertEquals("the same 150 KB photo was sent twice", 1, api.puts)
    }

    /** D3: an acked record purged (7 days) before the media job's first probe reads as "never saved": the photo is deleted. */
    @Test fun aPhotoWhoseAckedRecordWasPurgedBeforeTheFirstProbeIsNotDropped() = runTest {
        val id = queued(4, record = null) // acked and purged while no media run happened (Wi-Fi-only, no Wi-Fi)
        now += 8 * MediaUploader.DAY_MS
        // Fixed (android-sys): the probe reads the domain table when the outbox row is gone, so a purged ack reads ACKED.
        val probe = OutboxRecordProbe(outboxState = { null }, recordExists = { it == uuid(1004) })
        val api = Fake()
        MediaUploader(MediaStore(dir), api, probe, sink, clock, { MediaConfig() }, { true }).run(NetworkKind.UNMETERED)
        assertNotNull("photo of an acked record was deleted unsent", MediaStore(dir).get(id))
        assertEquals(1, api.puts)
    }

    /** D4: with Wi-Fi-only on, nothing ever runs without Wi-Fi, so the evidence fallback (computed only by a run) never arms. */
    @Test fun wifiOnlyStillArmsSomeJobThatCanRunOnMobileData() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        val wm = WorkManager.getInstance(ctx)
        // Fixed (android-sys): attaching an evidence photo arms its fallback job at once.
        val item = MediaItem(uuid(6), "2026-10-07", "2026-10-07T00:00:00Z", now, "a".repeat(64), null, 1, 1, 1,
            state = MediaState.ATTACHED, ref = MediaRef("force_sale", "visit", uuid(1006)))
        MediaWorkScheduler(wm, wifiOnly = { true }, nowMs = { now }).photoQueued(item)
        val types = wm.getWorkInfosByTag(MediaWorkScheduler.TAG).get().filter { it.state == WorkInfo.State.ENQUEUED }
            .map { it.constraints.requiredNetworkType }
        assertTrue("only $types: an evidence photo taken off Wi-Fi never falls back to mobile data", NetworkType.CONNECTED in types)
    }

    /** D5: the cleartext guard is a prefix check: http://localhost.<anything> passes and the photo goes out in clear text. */
    @Test fun aLookalikeLoopbackHostIsNotCleartextAllowed() = runTest {
        var sent = 0
        val base = OkHttpClient.Builder().addInterceptor { ch ->
            sent++
            Response.Builder().request(ch.request()).protocol(Protocol.HTTP_1_1).code(201).message("Created").body("".toResponseBody()).build()
        }.build()
        val api = MediaHttpApi(com.aktcl.aron.core.network.AronApiClient(
            com.aktcl.aron.core.network.ApiOrigin.parse("https://api.example/"), base,
            com.aktcl.aron.core.network.ClientIdentity("1.0+1") { "dev-1" },
            object : com.aktcl.aron.core.network.AccessTokenSource {
                override fun currentAccessToken(grant: com.aktcl.aron.core.network.Grant) = "t"
                override suspend fun refreshAfterUnauthorized(grant: com.aktcl.aron.core.network.Grant, rejectedToken: String?, code: com.aktcl.aron.contract.ProblemCode?) = false
            }), base, object : MediaUploadAuth { override suspend fun token() = "t"; override suspend fun refresh(rejected: String?) = false })
        val id = uuid(5)
        val r = api.put(SasTarget(id, "http://localhost.evil.example/photos/x.jpg?sig=s", "photos/2026-10-07/$id/$id.jpg", emptyMap(), false), byteArrayOf(1))
        assertEquals("cleartext PUT sent to a non-loopback host", 0, sent)
        assertEquals(PutResult.REJECTED, r)
    }

    // ---- Re-check round 2 ----

    /** R1: one bad item makes the server 400 the whole batch: the nine good batch-mates go FAILED for good too. */
    @Test fun oneBadItemDoesNotFailItsWholeBatch() = runTest {
        val ids = (11..20).map { queued(it) }
        val bad = ids.first()
        val api = Fake(sasAnswer = null)
        val poisoned = object : MediaUploadApi by api {
            override suspend fun sas(items: List<SasItem>): SasResult =
                if (items.any { it.mediaUuid == bad }) SasResult.Rejected("ERR_VALIDATION") else api.sas(items)
        }
        val up = uploader(poisoned)
        repeat(4) { up.run(NetworkKind.UNMETERED) }
        val store = MediaStore(dir)
        val failedGood = ids.drop(1).count { store.get(it)!!.state == MediaState.FAILED }
        assertEquals("good photos failed for good because a batch-mate was bad", 0, failedGood)
    }

    /** R2: transient retries count toward the 3 "permanent" attempts: two outages then one 400 = FAILED at once. */
    @Test fun transientRetriesDoNotUseUpThePermanentBudget() = runTest {
        val id = queued(21)
        var answer: SasResult = SasResult.Retry("http_503")
        val up = uploader(Fake(sasAnswer = { answer }))
        repeat(2) { up.run(NetworkKind.UNMETERED) }
        answer = SasResult.Rejected("ERR_VALIDATION")
        up.run(NetworkKind.UNMETERED)
        assertEquals(MediaState.ATTACHED, MediaStore(dir).get(id)!!.state)
    }

    /** R3: the 6 h fallback job fires before the record is acked: the only follow-up is an UNMETERED recheck, so the
     * evidence photo waits for Wi-Fi again (the sync's requestUpload is also UNMETERED-only under Wi-Fi-only). */
    @Test fun anEvidencePhotoWhoseRecordAcksAfterTheFallbackStillGetsAMobileDataRun() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        val wm = WorkManager.getInstance(ctx)
        queued(22, record = RefState.PENDING)
        now += 7 * MediaUploader.HOUR_MS
        val report = uploader(Fake()).run(NetworkKind.METERED) // the fallback job, record not acked yet
        val s = MediaWorkScheduler(wm, wifiOnly = { true }, nowMs = { now })
        s.afterRun(report)
        s.requestUpload() // the sync then acks the record
        val types = wm.getWorkInfosByTag(MediaWorkScheduler.TAG).get().filter { it.state == WorkInfo.State.ENQUEUED }
            .map { it.constraints.requiredNetworkType }
        assertTrue("only $types after the fallback moment", NetworkType.CONNECTED in types)
    }

    /** R4: APPEND_OR_REPLACE chains a new recheck on every run that sees a waiting photo: a growing 15-min poll. */
    @Test fun repeatedRunsWithAWaitingPhotoKeepOneRecheck() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        val wm = WorkManager.getInstance(ctx)
        val s = MediaWorkScheduler(wm, wifiOnly = { true }, nowMs = { now })
        repeat(5) { s.afterRun(MediaRunReport(waitingForRecord = 1)) }
        val pending = wm.getWorkInfosByTag(MediaWorkScheduler.TAG).get().filter { !it.state.isFinished && it.tags.any { t -> t.contains(MediaWorkScheduler.WORK_RECHECK) } }
        assertEquals("pending rechecks: ${pending.map { it.state }}", 1, pending.size)
    }
}
