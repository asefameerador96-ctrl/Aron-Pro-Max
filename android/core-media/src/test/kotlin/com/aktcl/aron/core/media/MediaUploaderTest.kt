package com.aktcl.aron.core.media

import com.aktcl.aron.core.common.WallClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** F-SYS-010 and F-SYS-037 on the real store and compressor with a fake blob service. */
class MediaUploaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_791_000_000_000L
    private val clock = object : WallClock { override fun nowMs() = now; override fun elapsedRealtimeMs() = now }
    private val raw = JvmImageCodec.encode(JvmImageCodec.frame(1280, 720, 30), 95)
    private val dir get() = File(tmp.root, "media/u7")

    /** The server: blobs by path; SAS calls and PUTs counted; switches to make it fail. */
    inner class FakeBlobService : MediaUploadApi {
        val blobs = mutableMapOf<String, ByteArray>()
        val sasCalls = mutableListOf<List<SasItem>>()
        var puts = 0
        var sasAnswer: (() -> SasResult)? = null
        var putAnswer: ((SasTarget) -> PutResult)? = null
        var killAfterPut = false
        override suspend fun sas(items: List<SasItem>): SasResult {
            sasAnswer?.let { return it() }
            sasCalls += items
            return SasResult.Ok(items.map { SasTarget(it.mediaUuid, "https://blob.example/photos/${it.mediaUuid}?sig=x", path(it), mapOf("x-ms-blob-type" to "BlockBlob", "Content-Type" to "image/jpeg"), blobs.containsKey(path(it))) })
        }
        fun path(it: SasItem) = "photos/${it.businessDate}/00000000-0000-4000-8000-000000000007/${it.mediaUuid}.jpg"
        override suspend fun put(target: SasTarget, jpeg: ByteArray): PutResult {
            putAnswer?.let { a -> a(target).let { if (it != PutResult.STORED) return it } }
            puts++
            blobs[target.blobPath] = jpeg
            if (killAfterPut) throw KilledException()
            return PutResult.STORED
        }
    }

    /** A kill seen from inside the run: the coroutine is cancelled mid-step (WorkManager stop, process death). */
    class KilledException : kotlinx.coroutines.CancellationException("process killed")

    private val refs = mutableMapOf<String, RefState>()
    private val probe = RecordAckProbe { refs[it] ?: RefState.ABSENT }
    private val metas = mutableListOf<String>()
    private val sink = MediaMetaSink { item -> if (item.mediaUuid !in metas) metas += item.mediaUuid; true }

    private fun uploader(api: MediaUploadApi, wifiOnly: Boolean = true, cfg: MediaConfig = MediaConfig(), store: MediaStore = MediaStore(dir)) =
        MediaUploader(store, api, probe, sink, clock, { cfg }, { wifiOnly })

    private fun capturer(store: MediaStore = MediaStore(dir)) =
        PhotoCapturer({ raw }, JvmImageCodec, store, clock, { "2026-10-07" }, freeBytes = { Long.MAX_VALUE }, cpu = Dispatchers.Unconfined)

    private fun uuid(n: Int) = "%08x-0000-4000-8000-%012x".format(n, n)

    private suspend fun queued(n: Int, purpose: String = "force_sale", record: RefState = RefState.ACKED): String {
        val id = uuid(n)
        capturer().capture(id)
        capturer().attach(id, MediaRef(purpose, "visit", uuid(1000 + n)), PhotoStamp(23.8, 90.4, 9.0, false))
        refs[uuid(1000 + n)] = record
        return id
    }

    @Test fun aPhotoQueuedOfflineUploadsOnWifiAfterItsRecordAndQueuesItsMeta() = runTest {
        val api = FakeBlobService()
        val id = queued(1, record = RefState.PENDING)
        val up = uploader(api)
        // Offline: nothing leaves the phone.
        assertEquals(0, up.run(NetworkKind.NONE).uploaded)
        // Online, but the visit is not acked yet: the photo waits (the record always goes first).
        val r1 = up.run(NetworkKind.UNMETERED)
        assertEquals(1, r1.waitingForRecord); assertTrue(api.sasCalls.isEmpty())
        refs[uuid(1001)] = RefState.ACKED
        val r2 = up.run(NetworkKind.UNMETERED)
        assertEquals(1, r2.uploaded); assertEquals(1, r2.metaQueued); assertEquals(0, r2.remaining)
        val item = MediaStore(dir).get(id)!!
        assertEquals(MediaState.META_QUEUED, item.state)
        val blob = api.blobs[item.blobPath]!!
        assertEquals(item.sha256, PhotoCompressor.sha256Hex(blob))
        assertTrue(blob.size <= 150 * 1024)
        assertEquals(listOf(id), metas)
        // A later run changes nothing.
        assertEquals(MediaRunReport(), up.run(NetworkKind.UNMETERED).copy(remaining = 0))
        assertEquals(1, api.puts)
    }

    @Test fun killAndRelaunchResumesTheUploadAndSendsEachPhotoOnce() = runTest {
        val api = FakeBlobService()
        val id = queued(2)
        api.killAfterPut = true
        try { uploader(api).run(NetworkKind.UNMETERED); error("expected kill") } catch (_: KilledException) { }
        assertEquals(MediaState.ATTACHED, MediaStore(dir).get(id)!!.state) // the blob landed, the state did not
        // Relaunch: a new store and uploader over the same directory.
        api.killAfterPut = false
        val r = uploader(api, store = MediaStore(dir)).run(NetworkKind.UNMETERED)
        assertEquals(1, r.uploaded)
        assertEquals(1, api.blobs.size) // the same blob path: written twice with the same bytes, stored once
        assertEquals(listOf(id), metas)
        assertEquals(MediaState.META_QUEUED, MediaStore(dir).get(id)!!.state)
    }

    @Test fun aKillBeforeTheMetaStepIsResumedWithoutAnotherUpload() = runTest {
        val api = FakeBlobService()
        val id = queued(3)
        var fail = true
        val flaky = MediaMetaSink { if (fail) throw KilledException() else { metas += it.mediaUuid; true } }
        try { MediaUploader(MediaStore(dir), api, probe, flaky, clock).run(NetworkKind.UNMETERED); error("expected kill") } catch (_: KilledException) { }
        assertEquals(MediaState.UPLOADED, MediaStore(dir).get(id)!!.state)
        fail = false
        MediaUploader(MediaStore(dir), api, probe, flaky, clock).run(NetworkKind.UNMETERED)
        assertEquals(1, api.puts)
        assertEquals(listOf(id), metas)
    }

    @Test fun alreadyUploadedSkipsThePut() = runTest {
        val api = FakeBlobService()
        val id = queued(4)
        val item = MediaStore(dir).get(id)!!
        api.blobs[api.path(SasItem(id, "force_sale", item.sha256, item.bytes, item.businessDate))] = MediaStore(dir).readPhoto(id)
        val r = uploader(api).run(NetworkKind.UNMETERED)
        assertEquals(1, r.uploaded); assertEquals(0, api.puts)
    }

    @Test fun anExpiredSasIsRetriedWithAFreshOneNextRun() = runTest {
        val api = FakeBlobService()
        val id = queued(5)
        api.putAnswer = { PutResult.EXPIRED }
        val r = uploader(api).run(NetworkKind.UNMETERED)
        assertTrue(r.needsRetry); assertEquals(0, r.uploaded)
        assertEquals("sas_expired", MediaStore(dir).get(id)!!.lastError)
        api.putAnswer = null
        assertEquals(1, uploader(api).run(NetworkKind.UNMETERED).uploaded)
        assertEquals(2, api.sasCalls.size)
    }

    @Test fun aServerOrNetworkFailureStopsTheRunAndAsksForARetry() = runTest {
        val api = FakeBlobService()
        repeat(3) { queued(10 + it) }
        api.sasAnswer = { SasResult.Retry("http_503") }
        val r = uploader(api).run(NetworkKind.UNMETERED)
        assertTrue(r.needsRetry); assertEquals(0, r.uploaded); assertEquals(3, r.remaining)
    }

    @Test fun photosGoInBatchesOfAtMostTen() = runTest {
        val api = FakeBlobService()
        repeat(23) { queued(20 + it) }
        val r = uploader(api).run(NetworkKind.UNMETERED)
        assertEquals(23, r.uploaded)
        assertEquals(listOf(10, 10, 3), api.sasCalls.map { it.size })
    }

    @Test fun aDamagedLocalFileIsNeverUploaded() = runTest {
        val api = FakeBlobService()
        val id = queued(40)
        MediaStore(dir).photoFile(id).writeBytes(byteArrayOf(1, 2, 3))
        val r = uploader(api).run(NetworkKind.UNMETERED)
        assertEquals(0, api.puts); assertEquals(1, r.failed)
        assertEquals("local_file_damaged", MediaStore(dir).get(id)!!.lastError)
    }

    @Test fun aTargetWithAForeignOrMalformedBlobPathIsRefused() = runTest {
        val id = queued(41)
        val api = object : MediaUploadApi {
            var puts = 0
            override suspend fun sas(items: List<SasItem>) = SasResult.Ok(
                listOf(
                    SasTarget(id, "https://b/x", "photos/2026-10-07/00000000-0000-4000-8000-000000000007/${uuid(99)}.jpg", emptyMap(), false),
                    SasTarget(uuid(98), "https://b/y", "photos/2026-10-07/00000000-0000-4000-8000-000000000007/${uuid(98)}.jpg", emptyMap(), false),
                ),
            )
            override suspend fun put(target: SasTarget, jpeg: ByteArray): PutResult { puts++; return PutResult.STORED }
        }
        uploader(api).run(NetworkKind.UNMETERED)
        assertEquals(0, api.puts)
        assertEquals(MediaState.ATTACHED, MediaStore(dir).get(id)!!.state)
    }

    // ---- F-SYS-037: Wi-Fi only, evidence fallback after 6 h

    @Test fun wifiOnlyHoldsPhotosOnMobileDataAndUploadsThemOnWifi() = runTest {
        val api = FakeBlobService()
        queued(50, purpose = "survey")
        val r = uploader(api, wifiOnly = true).run(NetworkKind.METERED)
        assertEquals(1, r.heldForWifi); assertEquals(0, api.puts); assertNull(r.evidenceFallbackAtMs)
        now += 48 * MediaUploader.HOUR_MS
        assertEquals(0, uploader(api, wifiOnly = true).run(NetworkKind.METERED).uploaded) // non-evidence never falls back
        assertEquals(1, uploader(api, wifiOnly = true).run(NetworkKind.UNMETERED).uploaded)
    }

    @Test fun evidencePhotosFallBackToMobileDataAfterSixHours() = runTest {
        val api = FakeBlobService()
        val capturedAt = now
        queued(51, purpose = "force_sale")
        val r = uploader(api).run(NetworkKind.METERED)
        assertEquals(1, r.heldForWifi)
        assertEquals(capturedAt + 6 * MediaUploader.HOUR_MS, r.evidenceFallbackAtMs)
        now = capturedAt + 6 * MediaUploader.HOUR_MS - 1
        assertEquals(0, uploader(api).run(NetworkKind.METERED).uploaded)
        now = capturedAt + 6 * MediaUploader.HOUR_MS
        assertEquals(1, uploader(api).run(NetworkKind.METERED).uploaded)
    }

    @Test fun fallbackZeroMeansEvidenceWaitsForWifiForever() = runTest {
        val api = FakeBlobService()
        queued(52, purpose = "outlet_capture")
        now += 100 * MediaUploader.HOUR_MS
        val r = uploader(api, cfg = MediaConfig(evidenceMobileFallbackH = 0)).run(NetworkKind.METERED)
        assertEquals(0, r.uploaded); assertNull(r.evidenceFallbackAtMs)
    }

    @Test fun wifiOnlyOffUploadsOnMobileData() = runTest {
        val api = FakeBlobService()
        queued(53, purpose = "survey")
        assertEquals(1, uploader(api, wifiOnly = false).run(NetworkKind.METERED).uploaded)
    }

    // ---- housekeeping

    @Test fun orphansAndOldUploadsAreCleanedButPendingRecordsKeepTheirPhotos() = runTest {
        val api = FakeBlobService()
        val s = MediaStore(dir)
        val unclaimed = uuid(60); capturer().capture(unclaimed)
        val neverSaved = uuid(61); capturer().capture(neverSaved); capturer().attach(neverSaved, MediaRef("force_sale", "visit", uuid(2061)), null)
        val slowRecord = queued(62, record = RefState.PENDING)
        val done = queued(63)
        uploader(api).run(NetworkKind.UNMETERED)
        assertEquals(MediaState.META_QUEUED, s.get(done)!!.state)
        now += 8 * MediaUploader.DAY_MS
        val r = uploader(api).run(NetworkKind.UNMETERED)
        assertNull(s.get(unclaimed)); assertNull(s.get(neverSaved)); assertNull(s.get(done))
        assertFalse(s.photoFile(done).exists())
        assertEquals(MediaState.ATTACHED, s.get(slowRecord)!!.state) // a record still in the outbox keeps its photo
        assertEquals(3, r.removed)
    }

    @Test fun aRecordPurgedAfterItsAckStillLetsThePhotoUpload() = runTest {
        val api = FakeBlobService()
        val id = queued(70, record = RefState.PENDING)
        uploader(api).run(NetworkKind.METERED) // seen in the outbox (held for Wi-Fi anyway)
        refs.remove(uuid(1070)) // acked, then purged from the outbox
        now += 30 * MediaUploader.DAY_MS
        assertEquals(1, uploader(api).run(NetworkKind.UNMETERED).uploaded)
        assertEquals(MediaState.META_QUEUED, MediaStore(dir).get(id)!!.state)
    }

    @Test fun theRecordSyncNeverWaitsForPhotos() {
        // Structural guarantee: core-sync (the record upload) does not know core-media exists, and the media job has its own names.
        val androidRoot = File(System.getProperty("user.dir")).parentFile
        val syncBuild = File(androidRoot, "core-sync/build.gradle.kts").readText()
        assertFalse(syncBuild.contains("core-media"))
        val names = listOf(MediaWorkScheduler.WORK_WIFI, MediaWorkScheduler.WORK_ANY, MediaWorkScheduler.WORK_FALLBACK, MediaWorkScheduler.WORK_RECHECK)
        assertTrue(names.none { it == "aron-sync" || it == "aron-sync-periodic" })
        assertEquals("aron-media", MediaWorkScheduler.WORK_WIFI)
    }
}
