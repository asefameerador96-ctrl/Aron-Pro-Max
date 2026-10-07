package com.aktcl.aron.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** android-sys request media-meta: the photo's `media_meta` record through the outbox, once per media uuid. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class MediaMetaRecordTest {
    // ---- media_meta (android-sys request media-meta)

    private fun mediaCapture(media: String, ref: String, fix: String?) = com.aktcl.aron.core.database.repo.MediaMetaCapture(
        mediaUuid = media, meta = TestRows.meta(), purpose = "outlet_capture", refType = "outlet_change_request", refClientUuid = ref,
        sha256 = "a".repeat(64), phash = "0123456789abcdef", bytes = 148_000, width = 1024, height = 768,
        blobPath = "photos/2026-10-05/6f1c2d3e-4b5a-4c6d-8e7f-9a0b1c2d3e4f/$media.jpg", takenAt = "2026-10-05T04:40:00.000Z", fixClientUuid = fix,
    )

    @Test
    fun aMediaMetaRecordIsQueuedOnceWithItsStoredFix() = kotlinx.coroutines.runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
        val repo = com.aktcl.aron.core.database.repo.CaptureRepository(db) { "2026-10-05T04:41:00.000Z" }
        val (visit, fix) = TestRows.visit()
        repo.recordVisitOpen(visit, fix)
        val media = com.aktcl.aron.core.common.ClientIds.newUuid()
        assertTrue(repo.recordMediaMeta(mediaCapture(media, visit.clientUuid, fix.clientUuid)))
        assertTrue("a repeat after a kill is a no-op", !repo.recordMediaMeta(mediaCapture(media, visit.clientUuid, fix.clientUuid)))
        val row = db.outboxDao().byClientUuid(media)!!
        assertEquals("media_meta", row.recordType)
        assertEquals(media, row.familyUuid)
        val record = Json.parseToJsonElement(row.payloadJson).jsonObject
        val payload = record["payload"]!!.jsonObject
        assertEquals("image/jpeg", payload["mime"]!!.jsonPrimitive.content)
        assertEquals(ContractYaml.propertyNames("MediaMetaPayload"), payload.keys)
        assertEquals("ok", payload["fix"]!!.jsonObject["fix_status"]!!.jsonPrimitive.content)
        assertTrue("route_id" !in record)
        val noFix = com.aktcl.aron.core.common.ClientIds.newUuid()
        repo.recordMediaMeta(mediaCapture(noFix, visit.clientUuid, null))
        val p2 = Json.parseToJsonElement(db.outboxDao().byClientUuid(noFix)!!.payloadJson).jsonObject["payload"]!!.jsonObject
        assertTrue("fix" !in p2 || p2["fix"] is kotlinx.serialization.json.JsonNull)
        assertTrue(runCatching { repo.recordMediaMeta(mediaCapture(com.aktcl.aron.core.common.ClientIds.newUuid(), visit.clientUuid, null).copy(bytes = 400_000)) }.isFailure)
        assertTrue(runCatching { repo.recordMediaMeta(mediaCapture(com.aktcl.aron.core.common.ClientIds.newUuid(), visit.clientUuid, null).copy(blobPath = "x.jpg")) }.isFailure)
        db.close()
    }
}
