package com.aktcl.aron.core.media

import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore.ACTION_PICK_IMAGES
import android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.aktcl.aron.core.common.WallClock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.Random

/**
 * tso-manual A2 (D-TSO-MAN-23, D-TSO-MAN-36): a gallery picture goes through the camera's pipeline. Real Android codec
 * (native graphics), real media store, the content resolver standing in for the photo picker's read grant.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GalleryImportTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Context>()

    private var now = 1_793_000_000_000L
    private val clock = object : WallClock { override fun nowMs() = now; override fun elapsedRealtimeMs() = now }

    private val u1 = "11111111-1111-4111-8111-111111111111"
    private val u2 = "22222222-2222-4222-8222-222222222222"
    private val u3 = "55555555-5555-4555-8555-555555555555"
    private val ticket = "33333333-3333-4333-8333-333333333333"
    private val support = MediaRef("support", "support_ticket", ticket)

    @Before fun clean() { File(app.filesDir, "media").deleteRecursively() }

    private fun media(config: MediaConfig = MediaConfig(), scheduler: MediaWorkScheduler? = null) =
        MediaComponents(app, 7L, clock, { "2026-10-29" }, { config }, scheduler = scheduler)

    /** A phone photo: gradients with sensor-like noise, and a red block at its stored top-left corner. */
    private fun photo(w: Int, h: Int, noise: Int = 40, seed: Long = 1): Bitmap {
        val r = Random(seed)
        fun ch(base: Int) = (base + r.nextInt(noise + 1) - noise / 2).coerceIn(0, 255)
        val px = IntArray(w * h) { i -> val x = i % w; val y = i / w; Color.rgb(ch(x * 255 / w), ch(y * 255 / h), ch((x + y) * 127 / (w + h))) }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        Canvas(bmp).drawRect(0f, 0f, w / 4f, h / 4f, Paint().apply { color = Color.RED })
        return bmp
    }

    private fun encoded(bmp: Bitmap, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG) =
        ByteArrayOutputStream().also { check(bmp.compress(format, 95, it)) }.toByteArray()

    /** What a camera app leaves in the gallery: EXIF with the location, the phone, the original date and [orientation]. */
    private fun cameraFile(jpeg: ByteArray, orientation: Int = ExifInterface.ORIENTATION_NORMAL): ByteArray {
        val f = tmp.newFile()
        f.writeBytes(jpeg)
        ExifInterface(f).apply {
            setLatLong(23.7925, 90.4078)
            setAttribute(ExifInterface.TAG_MAKE, "PhoneMaker")
            setAttribute(ExifInterface.TAG_MODEL, "Model X")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:01 10:00:00")
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return f.readBytes()
    }

    private var opens = 0

    /** The photo picker's answer: a content URI the app may read once. */
    private fun picked(name: String, bytes: ByteArray): Uri {
        val uri = Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/$name")
        shadowOf(app.contentResolver).registerInputStreamSupplier(uri) { opens++; ByteArrayInputStream(bytes) }
        return uri
    }

    private suspend fun reason(block: suspend () -> Unit): GalleryImportException.Reason? =
        try { block(); null } catch (e: GalleryImportException) { e.reason }

    @Test fun aPickedPhotoIsCompressedToTheCapStrippedOfExifAndClaimedLikeACameraPhoto() = runTest {
        val source = cameraFile(encoded(photo(2400, 1600)))
        assertTrue("the source must be well over the cap, was ${source.size}", source.size > 600 * 1024)
        assertNotNull("the source carries a location", ExifInterface(ByteArrayInputStream(source)).latLong)
        val m = media()

        val item = m.importFromGallery(u1, picked("a", source), support)

        assertEquals(MediaState.ATTACHED, item.state)
        assertEquals(support, item.ref)
        assertNull("no shutter fix for a gallery picture", item.stamp)
        assertFalse(item.evidence)
        assertTrue("bytes ${item.bytes}", item.bytes <= 150 * 1024)
        assertTrue(maxOf(item.width, item.height) <= 1024)
        assertEquals("2026-10-29", item.businessDate)
        assertEquals("taken_at is the import time on the trusted clock", Instant.ofEpochMilli(now).toString(), item.takenAt)
        val stored = m.store.readPhoto(u1)
        assertEquals(item.bytes, stored.size)
        assertEquals(item.sha256, PhotoCompressor.sha256Hex(stored))
        assertTrue(JpegMetadata.headerMarkers(stored).none { it in 0xE1..0xEF })
        val exif = ExifInterface(ByteArrayInputStream(stored))
        assertNull(exif.latLong)
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
        assertNull(exif.getAttribute(ExifInterface.TAG_MODEL))
        assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertTrue(m.store.thumbnailFile(u1).length() in 1..(40 * 1024))
        // Nothing uncompressed is kept anywhere in the queue directory.
        assertTrue(m.store.dir.listFiles()!!.all { it.length() <= 150 * 1024 })
    }

    @Test fun theCapIsTheConfiguredPhotoBudget() = runTest {
        val item = media(MediaConfig(longEdgePx = 640, photoMaxKb = 40)).importFromGallery(u1, picked("a", encoded(photo(2400, 1600))), support)
        assertTrue("bytes ${item.bytes}", item.bytes <= 40 * 1024)
        assertTrue(maxOf(item.width, item.height) <= 640)
    }

    @Test fun orientationIsAppliedBeforeTheExifIsDroppedAndAPngScreenshotBecomesAJpeg() = runTest {
        val m = media()
        val turned = m.importFromGallery(u1, picked("o6", cameraFile(encoded(photo(400, 200, noise = 0)), ExifInterface.ORIENTATION_ROTATE_90)), support)
        assertEquals(200 to 400, turned.width to turned.height)
        val b = BitmapFactory.decodeByteArray(m.store.readPhoto(u1), 0, turned.bytes)
        val c = b.getPixel(b.width - 6, 5)
        assertTrue("the marker turned with the picture", Color.red(c) > 180 && Color.green(c) < 80)

        val png = m.importFromGallery(u2, picked("png", encoded(photo(1600, 900, noise = 0), Bitmap.CompressFormat.PNG)), support)
        val bytes = m.store.readPhoto(u2)
        assertEquals(0xFF.toByte() to 0xD8.toByte(), bytes[0] to bytes[1])
        assertEquals(1024 to 576, png.width to png.height)
    }

    @Test fun theSameMediaUuidTwiceGivesOneItemAndReadsThePictureOnce() = runTest {
        val m = media()
        val uri = picked("a", encoded(photo(800, 600)))
        val a = m.importFromGallery(u1, uri, support)
        now += 60_000
        val b = m.importFromGallery(u1, uri, support)
        assertEquals(a, b)
        assertEquals(1, opens)
        assertEquals(listOf(u1), m.store.all().map { it.mediaUuid })

        // After a relaunch, with the read grant gone, the UUID still names the one stored picture.
        assertEquals(a, media().importFromGallery(u1, Uri.parse("content://media/picker/0/gone/media/1"), support))
        assertEquals(1, opens)
        // The claim is final: the picture cannot move to another record.
        try { m.importFromGallery(u1, uri, support.copy(refClientUuid = u2)); fail() } catch (_: IllegalStateException) { }
    }

    /** Step 3 (tickets): claim = false only stages; the save claims it, and a removed picture is dropped and never queued. */
    @Test fun aStagedPictureIsNotClaimedUntilTheSaveAndARemovedOneIsDropped() = runTest {
        val m = media()
        val kept = m.importFromGallery(u1, picked("a", encoded(photo(800, 600))), support, claim = false)
        val removed = m.importFromGallery(u2, picked("b", encoded(photo(640, 480, seed = 2))), support, claim = false)
        assertEquals(MediaState.CAPTURED, kept.state)
        assertNull("staged, not claimed", kept.ref)
        assertEquals(MediaState.CAPTURED, removed.state)
        // a repeat of the stage reads nothing again and changes nothing
        assertEquals(kept, m.importFromGallery(u1, Uri.parse("content://media/picker/0/gone/media/1"), support, claim = false))
        assertEquals(2, opens)

        // the user removed the second picture: it is gone with its thumbnail
        m.discard(u2)
        assertNull(m.store.get(u2))
        assertFalse(m.store.thumbnailFile(u2).exists())

        // the record is saved: the kept picture is claimed for it (no shutter fix), exactly once
        val claimed = m.attach(u1, support, stamp = null)
        assertEquals(MediaState.ATTACHED, claimed.state)
        assertEquals(support, claimed.ref)
        assertEquals(claimed, m.attach(u1, support, stamp = null))
        // a claimed picture is never dropped by a late discard
        m.discard(u1)
        assertEquals(MediaState.ATTACHED, m.store.get(u1)!!.state)
        assertEquals(listOf(u1), m.store.all().map { it.mediaUuid })
    }

    @Test fun twoImportsOfOneUuidAtOnceStillStoreOneItem() = runTest {
        val m = media()
        val uri = picked("a", encoded(photo(800, 600)))
        val items = listOf(async { m.importFromGallery(u1, uri, support) }, async { m.importFromGallery(u1, uri, support) }).awaitAll()
        assertEquals(items[0], items[1])
        assertEquals(1, m.store.all().size)
    }

    @Test fun evidencePurposesStayCameraOnlyAndNothingIsRead() = runTest {
        val m = media()
        val uri = picked("a", encoded(photo(400, 300)))
        for (p in MediaItem.EVIDENCE_PURPOSES + "selfie") {
            try { m.importFromGallery(u1, uri, MediaRef(p, "visit", ticket)); fail(p) } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, opens)
        assertTrue(m.store.all().isEmpty())
        // Feedback (D-TSO-MAN-36) and support tickets are the gallery's purposes.
        assertEquals(MediaState.ATTACHED, m.importFromGallery(u1, uri, MediaRef("feedback", "feedback", ticket)).state)
    }

    @Test fun aPictureThatCannotBeAddedStoresNothingAndSaysWhy() = runTest {
        val m = media()
        assertEquals(GalleryImportException.Reason.UNREADABLE, reason { m.importFromGallery(u1, picked("txt", "not a picture".toByteArray()), support) })
        val denied = Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/denied")
        shadowOf(app.contentResolver).registerInputStreamSupplier(denied) { throw SecurityException("the read grant ended") }
        assertEquals(GalleryImportException.Reason.UNREADABLE, reason { m.importFromGallery(u2, denied, support) })
        assertTrue(m.store.all().isEmpty())
        assertTrue(m.store.dir.listFiles()!!.isEmpty())

        val store = MediaStore(File(tmp.root, "m"))
        val jpeg = encoded(photo(400, 300))
        var reads = 0
        fun importer(free: Long = Long.MAX_VALUE, cap: Int = GalleryImporter.MAX_SOURCE_BYTES) =
            GalleryImporter(AndroidImageCodec, store, clock, { "2026-10-29" }, freeBytes = { free }, maxSourceBytes = cap)
        assertEquals(GalleryImportException.Reason.TOO_LARGE, reason { importer(cap = jpeg.size - 1).import(u1, support) { reads++; ByteArrayInputStream(jpeg) } })
        assertEquals(GalleryImportException.Reason.NO_SPACE, reason { importer(free = PhotoCapturer.MIN_FREE_BYTES - 1).import(u2, support) { reads++; ByteArrayInputStream(jpeg) } })
        assertEquals("no space: the picture is not even read", 1, reads)
        assertEquals(GalleryImportException.Reason.UNREADABLE, reason { importer().import(u3, support) { null } })
        assertTrue(store.all().isEmpty())
        // Exactly at the cap is fine.
        assertEquals(MediaState.CAPTURED, importer(cap = jpeg.size).import(u1, support) { ByteArrayInputStream(jpeg) }.state)
    }

    @Test fun everyReasonHasItsMessage() {
        val en = GalleryImportException.Reason.entries.map { app.getString(GalleryImportException(it).messageRes) }
        assertEquals(listOf("No space left for photos. Delete other files on this phone.", "This picture is too large. Choose a smaller one.", "This picture could not be added. Choose another one."), en)
    }

    @Test @Config(qualifiers = "bn")
    fun everyReasonHasABanglaMessage() {
        val bangla = Regex("[\\u0980-\\u09FF]")
        for (r in GalleryImportException.Reason.entries) assertTrue(r.name, bangla.containsMatchIn(app.getString(GalleryImportException(r).messageRes)))
        assertTrue(bangla.containsMatchIn(app.getString(R.string.media_gallery_unavailable)))
    }

    @Test fun aGalleryPictureIsQueuedAndUploadedLikeACameraPhoto() = runTest {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val wm = WorkManager.getInstance(app)
        val m = media(scheduler = MediaWorkScheduler(wm, wifiOnly = { true }, nowMs = { now }))
        m.importFromGallery(u1, picked("a", cameraFile(encoded(photo(1600, 1200)))), support)
        assertEquals(1, wm.getWorkInfosForUniqueWork(MediaWorkScheduler.WORK_WIFI).get().count { it.state == WorkInfo.State.ENQUEUED })

        val blobs = mutableMapOf<String, ByteArray>()
        val metas = mutableListOf<MediaItem>()
        val api = object : MediaUploadApi {
            override suspend fun sas(items: List<SasItem>) = SasResult.Ok(items.map {
                val path = "photos/${it.businessDate}/00000000-0000-4000-8000-000000000007/${it.mediaUuid}.jpg"
                SasTarget(it.mediaUuid, "https://blob.example/$path?sig=x", path, mapOf("x-ms-blob-type" to "BlockBlob", "Content-Type" to "image/jpeg"), false)
            })
            override suspend fun put(target: SasTarget, jpeg: ByteArray): PutResult { blobs[target.blobPath] = jpeg; return PutResult.STORED }
        }
        val report = MediaUploader(m.store, api, RecordAckProbe { RefState.ACKED }, MediaMetaSink { metas += it; true }, clock).run(NetworkKind.UNMETERED)
        assertEquals(1, report.uploaded)
        val done = m.store.get(u1)!!
        assertEquals(MediaState.META_QUEUED, done.state)
        assertArrayEquals(m.store.readPhoto(u1), blobs.getValue(done.blobPath!!))
        assertEquals(listOf(u1), metas.map { it.mediaUuid })
        assertNull(metas.single().stamp)
    }

    @Test fun theMergedManifestHasNoStoragePermission() {
        val pm = app.packageManager
        // The manifest under test is merged with every dependency's (the test-only ComponentActivity entry proves it), so
        // androidx.activity's picker contract and every library core-media pulls in are part of what is checked here.
        pm.getActivityInfo(ComponentName(app, ComponentActivity::class.java), 0)
        val requested = pm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertTrue("the merged manifest was read: $requested", "android.permission.CAMERA" in requested)
        val storage = setOf(
            "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            "android.permission.ACCESS_MEDIA_LOCATION", "android.permission.MANAGE_MEDIA",
        )
        assertTrue("storage permissions requested: ${requested intersect storage}", (requested intersect storage).isEmpty())
    }

    @Test fun thePickerAsksForImagesOnlyAndKeepsTheLimit() {
        var limit by mutableIntStateOf(2)
        var got: List<Uri>? = null
        lateinit var launch: () -> Unit
        rule.setContent { launch = rememberGalleryPicker(maxItems = limit) { got = it } }
        val shadow = shadowOf(rule.activity)

        rule.runOnIdle { launch() }
        val multi = shadow.nextStartedActivityForResult.intent
        assertEquals("image/*", multi.type)
        assertTrue(multi.action, multi.action in PICKER_ACTIONS)
        if (multi.action == ACTION_PICK_IMAGES) assertEquals(2, multi.getIntExtra(EXTRA_PICK_IMAGES_MAX, -1))
        val (a, b, c) = (1..3).map { Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/$it") }
        val three = Intent().apply { clipData = ClipData.newRawUri(null, a).apply { addItem(ClipData.Item(b)); addItem(ClipData.Item(c)) } }
        shadow.receiveResult(multi, Activity.RESULT_OK, three)
        rule.waitForIdle()
        assertEquals("the documents fallback does not enforce the limit, so the app does", listOf(a, b), got)

        limit = 1
        rule.waitForIdle()
        got = null
        rule.runOnIdle { launch() }
        val single = shadow.nextStartedActivityForResult.intent
        assertEquals("image/*", single.type)
        shadow.receiveResult(single, Activity.RESULT_CANCELED, null)
        rule.waitForIdle()
        assertNull("backing out adds nothing", got)
        rule.runOnIdle { launch() }
        val again = shadow.nextStartedActivityForResult.intent
        shadow.receiveResult(again, Activity.RESULT_OK, Intent().setData(c))
        rule.waitForIdle()
        assertEquals(listOf(c), got)

        limit = 0
        rule.waitForIdle()
        rule.runOnIdle { launch() }
        assertNull("a full form opens no picker", shadow.nextStartedActivityForResult)
    }

    @Test fun pickedWithinDropsRepeatsAndExtras() {
        val (a, b) = listOf(Uri.parse("content://x/1"), Uri.parse("content://x/2"))
        assertEquals(listOf(a, b), pickedWithin(listOf(a, a, b, a), 3))
        assertEquals(emptyList<Uri>(), pickedWithin(listOf(a), 0))
        assertEquals(emptyList<Uri>(), pickedWithin(listOf(a), -1))
    }

    private companion object {
        /** The photo picker, the Play services backport, or the documents picker on old phones: never a permission prompt. */
        val PICKER_ACTIONS = setOf(
            ACTION_PICK_IMAGES, Intent.ACTION_OPEN_DOCUMENT,
            "androidx.activity.result.contract.action.PICK_IMAGES", "com.google.android.gms.provider.action.PICK_IMAGES",
        )
    }
}
