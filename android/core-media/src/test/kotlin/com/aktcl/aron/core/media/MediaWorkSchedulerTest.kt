package com.aktcl.aron.core.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The media job is Wi-Fi first, separate from `aron-sync`, and never polls (F-SYS-010, F-SYS-037). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MediaWorkSchedulerTest {
    private lateinit var wm: WorkManager
    private var now = 1_000_000L

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        wm = WorkManager.getInstance(ctx)
    }

    private fun infos(name: String): List<WorkInfo> = wm.getWorkInfosForUniqueWork(name).get().filter { it.state == WorkInfo.State.ENQUEUED }

    @Test fun wifiOnlyEnqueuesOnlyAnUnmeteredJob() {
        MediaWorkScheduler(wm, wifiOnly = { true }, nowMs = { now }).requestUpload()
        assertEquals(NetworkType.UNMETERED, infos("aron-media").single().constraints.requiredNetworkType)
        assertTrue(infos("aron-media-any").isEmpty())
        assertTrue(wm.getWorkInfosForUniqueWork("aron-sync").get().isEmpty())
    }

    @Test fun wifiOnlyOffAlsoEnqueuesAConnectedJob() {
        MediaWorkScheduler(wm, wifiOnly = { false }, nowMs = { now }).requestUpload()
        assertEquals(NetworkType.CONNECTED, infos("aron-media-any").single().constraints.requiredNetworkType)
    }

    @Test fun repeatedRequestsKeepOneJob() {
        val s = MediaWorkScheduler(wm, wifiOnly = { true }, nowMs = { now })
        repeat(5) { s.requestUpload() }
        assertEquals(1, infos("aron-media").size)
    }

    private fun fallbacks() = wm.getWorkInfosByTag(MediaWorkScheduler.TAG).get()
        .filter { it.state == WorkInfo.State.ENQUEUED && it.constraints.requiredNetworkType == NetworkType.CONNECTED }

    private fun evidence(createdAt: Long, purpose: String = "force_sale") = MediaItem(
        "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", "2026-10-07", "2026-10-07T00:00:00Z", createdAt, "a".repeat(64), null, 1, 1, 1,
        state = MediaState.ATTACHED, ref = MediaRef(purpose, "visit", "aaaaaaaa-bbbb-4ccc-8ddd-ffffffffffff"),
    )

    @Test fun anEvidencePhotoArmsAConnectedJobSixHoursOutEvenWithoutAnyRun() {
        now = 10 * 3_600_000L // on the hour
        MediaWorkScheduler(wm, wifiOnly = { true }, nowMs = { now }).photoQueued(evidence(now))
        val i = fallbacks().single()
        assertEquals(6 * 3_600_000L, i.initialDelayMillis)
    }

    @Test fun theFallbackIsNeverEarlierThanSixHoursAndOneJobPerHour() {
        now = 10 * 3_600_000L + 60_000 // one minute past the hour
        val s = MediaWorkScheduler(wm, wifiOnly = { true }, nowMs = { now })
        s.photoQueued(evidence(now)); s.photoQueued(evidence(now + 1_000))
        s.afterRun(MediaRunReport(heldForWifi = 2, evidenceFallbackAtMs = now + 6 * 3_600_000L))
        val i = fallbacks().single()
        assertTrue(i.initialDelayMillis >= 6 * 3_600_000L)
        assertTrue(i.initialDelayMillis < 7 * 3_600_000L)
    }

    @Test fun nonEvidenceOrWifiOnlyOffArmsNoFallback() {
        MediaWorkScheduler(wm, wifiOnly = { true }, nowMs = { now }).photoQueued(evidence(now, purpose = "survey"))
        assertTrue(fallbacks().isEmpty())
        MediaWorkScheduler(wm, wifiOnly = { true }, nowMs = { now }, config = { MediaConfig(evidenceMobileFallbackH = 0) }).photoQueued(evidence(now))
        assertTrue(fallbacks().isEmpty())
    }

    private fun rechecks() = wm.getWorkInfosByTag(MediaWorkScheduler.TAG).get()
        .filter { !it.state.isFinished && it.tags.any { t -> t.startsWith(MediaWorkScheduler.NAME_TAG) } }

    @Test fun photosWaitingForTheirRecordGetOneRecheckNotAPoll() {
        val s = MediaWorkScheduler(wm, wifiOnly = { true }, nowMs = { now })
        repeat(3) { s.afterRun(MediaRunReport(waitingForRecord = 2)) }
        val i = rechecks().single()
        assertEquals(15 * 60_000L, i.initialDelayMillis)
        assertEquals(NetworkType.UNMETERED, i.constraints.requiredNetworkType)
        s.afterRun(MediaRunReport())
        assertEquals(1, rechecks().size)
    }

    @Test fun aRunningRecheckQueuesItsSuccessorInTheOtherSlotOnly() {
        val s = MediaWorkScheduler(wm, wifiOnly = { true }, nowMs = { now })
        s.afterRun(MediaRunReport(waitingForRecord = 1))
        val slot1 = "aron-media-recheck-wifi-1"
        assertEquals(1, infos(slot1).size)
        // Slot 1 is now the job running: its successor goes to slot 2, repeatedly at most once.
        repeat(4) { s.afterRun(MediaRunReport(waitingForRecord = 1), runningAs = slot1) }
        assertEquals(1, infos("aron-media-recheck-wifi-2").size)
        assertEquals(2, rechecks().size)
    }

    @Test fun anEvidencePhotoPastItsFallbackMakesTheRecheckRunOnMobileData() {
        val s = MediaWorkScheduler(wm, wifiOnly = { true }, nowMs = { now })
        s.afterRun(MediaRunReport(waitingForRecord = 1, evidenceDueOnMobile = true))
        assertEquals(NetworkType.CONNECTED, rechecks().single().constraints.requiredNetworkType)
    }
}
