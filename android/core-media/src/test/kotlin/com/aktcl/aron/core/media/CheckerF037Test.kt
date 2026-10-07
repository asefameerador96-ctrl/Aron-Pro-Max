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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Independent checker for F-SYS-037 (Wi-Fi-only photo setting). Failing tests here are defects found by the checker.
 */
class CheckerF037Test {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_791_000_000_000L
    private val clock = object : WallClock { override fun nowMs() = now; override fun elapsedRealtimeMs() = now }
    private val raw = JvmImageCodec.encode(JvmImageCodec.frame(1280, 720, 30), 95)
    private val dir get() = File(tmp.root, "media/u7")
    private val refs = mutableMapOf<String, RefState>()
    private val probe = RecordAckProbe { refs[it] ?: RefState.ABSENT }
    private val sink = MediaMetaSink { true }
    private val api = object : MediaUploadApi {
        override suspend fun sas(items: List<SasItem>) = SasResult.Ok(items.map {
            SasTarget(it.mediaUuid, "https://blob.example/x", "photos/${it.businessDate}/00000000-0000-4000-8000-000000000007/${it.mediaUuid}.jpg", emptyMap(), false)
        })
        override suspend fun put(target: SasTarget, jpeg: ByteArray) = PutResult.STORED
    }

    private fun uuid(n: Int) = "%08x-0000-4000-8000-%012x".format(n, n)

    private suspend fun queued(n: Int, purpose: String) {
        val capturer = PhotoCapturer({ raw }, JvmImageCodec, MediaStore(dir), clock, { "2026-10-07" }, freeBytes = { Long.MAX_VALUE }, cpu = Dispatchers.Unconfined)
        capturer.capture(uuid(n))
        capturer.attach(uuid(n), MediaRef(purpose, "visit", uuid(1000 + n)), PhotoStamp(23.8, 90.4, 9.0, false))
        refs[uuid(1000 + n)] = RefState.ACKED
    }

    private fun uploader(wifiOnly: Boolean) = MediaUploader(MediaStore(dir), api, probe, sink, clock, { MediaConfig() }, { wifiOnly })

    /**
     * DEFECT: MediaUploader line "if (network == NetworkKind.NONE && ready.isNotEmpty()) retry = true" is dead code:
     * networkGate never returns null for NONE, so `ready` is always empty. A CONNECTED job (aron-media-any) that starts
     * while the validated network has just gone (networkKind() -> NONE) finishes SUCCESS with no retry and no follow-up;
     * with Wi-Fi-only OFF the photo then waits for Wi-Fi or the next unrelated trigger.
     */
    @Test fun wifiOnlyOffAndTheNetworkGoneAtRunTimeAsksForARetry() = runTest {
        queued(1, purpose = "survey")
        val r = uploader(wifiOnly = false).run(NetworkKind.NONE)
        assertEquals(1, r.remaining)
        assertTrue("an uploadable photo with no network must ask WorkManager to retry", r.needsRetry)
    }

    /**
     * DEFECT (same root cause, evidence path): the fallback job runs at its hour bucket, but finds no validated network.
     * The report carries an evidenceFallbackAtMs already in the past and needsRetry=false; afterRun then re-arms the SAME
     * bucket name with KEEP while that job is still running, so nothing new is queued: the evidence photo is stranded on
     * mobile data until app restart, a new photo, or Wi-Fi.
     */
    @Test fun evidenceDueButNoNetworkAtTheFallbackRunAsksForARetry() = runTest {
        queued(2, purpose = "force_sale")
        now += 6 * MediaUploader.HOUR_MS + 60_000
        val r = uploader(wifiOnly = true).run(NetworkKind.NONE)
        assertTrue("the fallback moment is already past", (r.evidenceFallbackAtMs ?: Long.MAX_VALUE) <= now)
        assertTrue("an evidence photo due on mobile with no network must retry", r.needsRetry)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CheckerF037SchedulerTest {
    private lateinit var wm: WorkManager
    private val app get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        wm = WorkManager.getInstance(app)
    }

    private fun evidence(createdAt: Long) = MediaItem(
        "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", "2026-10-07", "2026-10-07T00:00:00Z", createdAt, "a".repeat(64), null, 1, 1, 1,
        state = MediaState.ATTACHED, ref = MediaRef("force_sale", "visit", "aaaaaaaa-bbbb-4ccc-8ddd-ffffffffffff"),
    )

    private fun pendingConnected() = wm.getWorkInfosByTag(MediaWorkScheduler.TAG).get()
        .filter { !it.state.isFinished && it.constraints.requiredNetworkType == NetworkType.CONNECTED }

    /**
     * DEFECT: the fallback job (bucket B) is the one calling afterRun (its run held the photo: no network, or the uploader's
     * clock still before the moment). afterRun(evidenceFallbackAtMs <= B) maps to bucket B again; KEEP sees the running B
     * and queues nothing. Once B finishes, no CONNECTED job is left for the evidence photo.
     */
    @Test fun aFallbackRunThatHeldTheEvidencePhotoLeavesAFollowUp() {
        val h = 3_600_000L
        val created = 10 * h
        var now = created
        val s = MediaWorkScheduler(wm, wifiOnly = { true }, nowMs = { now })
        s.photoQueued(evidence(created)) // arms aron-media-fallback-16
        assertEquals(1, pendingConnected().size)
        // 16:00: the fallback job (still unfinished: it is the caller) runs, holds the photo, reports the past moment.
        now = 16 * h + 5_000
        s.afterRun(MediaRunReport(heldForWifi = 1, evidenceFallbackAtMs = created + 6 * h))
        assertEquals("a successor CONNECTED job besides the running fallback job", 2, pendingConnected().size)
    }
}

/** The settings hint promises "6 hours" whatever cfg.media.evidence_mobile_fallback_h says (1..48, or 0 = never). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CheckerF037StringsTest {
    private val app get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun englishHintDoesNotHardcodeTheFallbackHours() {
        assertFalse(app.getString(R.string.media_wifi_only_hint), app.getString(R.string.media_wifi_only_hint).contains("6 hours"))
    }

    @Config(qualifiers = "bn")
    @Test fun banglaHintDoesNotHardcodeTheFallbackHours() {
        assertFalse(app.getString(R.string.media_wifi_only_hint), app.getString(R.string.media_wifi_only_hint).contains("৬ ঘণ্টা"))
    }
}
