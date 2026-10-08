package com.aktcl.aron.backend.media

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.contract.Role
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.mockk.mockk
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * F-API-007 (BC-81): the multipart fallback is idempotent by (media_uuid, purpose); a repeat writes nothing and
 * answers replayed; one uuid = one purpose; other bytes under the same uuid, a bad hash, a non-JPEG and an oversize
 * file are refused before anything is written. The path keeps the first upload's Dhaka business date.
 */
class MediaUploadTest {
    // 2027-01-03T19:00Z is 2027-01-04 01:00 in Dhaka: the business date is the Dhaka one.
    private var now = Instant.parse("2027-01-03T19:00:00Z")
    private val writes = ConcurrentHashMap<String, Int>()
    private val writer = PhotoBlobWriter { path, _ -> writes.merge(path, 1, Int::plus) }

    private fun jpeg(seed: Int, size: Int = 2000) = ByteArray(size) { (it * 31 + seed).toByte() }.also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun form(uuid: String, bytes: ByteArray, purpose: String = "feedback", s: String = sha(bytes), type: ContentType? = ContentType.Image.JPEG) =
        MediaUploadForm(uuid, purpose, s, bytes, type)

    private fun phone(userId: Long, device: String?) =
        AronPrincipal(userId, "sr1001", Role.SR, 1, com.aktcl.aron.backend.platform.Audience.API, 3, device, "sr", emptyList(), false, listOf("pwd"), "j", now)

    @Test
    fun aRepeatIsReplayedAndWritesNothingAndOneUuidKeepsOnePurpose() {
        FreshDb.create().use { fresh ->
            val user = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery("SELECT id FROM app.app_user WHERE username = 'aron.system'").mapTo(Long::class.java).one() }
            val m = MediaUpload(MediaDeps(fresh.db, mockk(relaxed = true), mockk(relaxed = true), AronClock { now }, writer))
            val web = phone(user, null)
            val a = "11111111-1111-4111-8111-111111111111"
            val bytes = jpeg(1)

            assertEquals(MediaUploaded(a, false), m.store(web, form(a, bytes)))
            assertEquals(mapOf("photos/2027-01-04/web-$user/$a.jpg" to 1), writes.toMap())
            now = now.plusSeconds(86_400)
            assertEquals(MediaUploaded(a, true), m.store(web, form(a, bytes)), "a retry on the next day is a replay")
            assertEquals(1, writes.values.sum(), "a replay writes nothing")
            val row = fresh.db.jdbi.withHandle<List<String>, Exception> { h ->
                h.createQuery("SELECT blob_path || ' ' || business_date || ' ' || bytes || ' ' || purpose FROM app.media_upload").mapTo(String::class.java).list()
            }
            assertEquals(listOf("photos/2027-01-04/web-$user/$a.jpg 2027-01-04 2000 feedback"), row)

            for ((f, pointer) in listOf(form(a, bytes, purpose = "support") to "/media_uuid", form(a, jpeg(2)) to "/sha256")) {
                val e = assertFailsWith<ApiProblem> { m.store(web, f) }
                assertEquals("ERR_VALIDATION" to pointer, e.code.name to e.errors.single().pointer)
            }
            assertEquals(1, writes.values.sum(), "nothing written for a refused upload")
        }
    }

    @Test
    fun concurrentDuplicatesStoreOnceAndAFailedWriteLeavesNoRow() {
        FreshDb.create().use { fresh ->
            val user = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery("SELECT id FROM app.app_user WHERE username = 'aron.system'").mapTo(Long::class.java).one() }
            val gate = java.util.concurrent.CountDownLatch(1)
            val slow = PhotoBlobWriter { path, b -> gate.await(); writer.put(path, b) }
            val m = MediaUpload(MediaDeps(fresh.db, mockk(relaxed = true), mockk(relaxed = true), AronClock { now }, slow))
            val a = "33333333-3333-4333-8333-333333333333"
            val bytes = jpeg(3)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
            val results = List(2) { pool.submit<MediaUploaded> { m.store(phone(user, null), form(a, bytes)) } }
            gate.countDown()
            assertEquals(setOf(false, true), results.map { it.get().replayed }.toSet(), "one stores, the other replays")
            pool.shutdown()
            assertEquals(1, writes.values.sum())

            val b = "44444444-4444-4444-8444-444444444444"
            val failing = MediaUpload(MediaDeps(fresh.db, mockk(relaxed = true), mockk(relaxed = true), AronClock { now }, PhotoBlobWriter { _, _ -> error("storage down") }))
            assertEquals("ERR_SERVICE_UNAVAILABLE", assertFailsWith<ApiProblem> { failing.store(phone(user, null), form(b, bytes)) }.code.name)
            assertEquals(MediaUploaded(b, false), m.store(phone(user, null), form(b, bytes)), "no ledger row was left: the retry stores")
            assertEquals(2, fresh.db.jdbi.withHandle<Int, Exception> { it.createQuery("SELECT count(*) FROM app.media_upload").mapTo(Int::class.java).one() })
        }
    }

    @Test
    fun badUploadsAreRefusedBeforeAnyWrite() {
        val m = MediaUpload(MediaDeps(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), AronClock { now }, writer))
        val p = phone(7, "00000000-0000-4000-8000-000000000001")
        val ok = "11111111-1111-4111-8111-111111111111"
        val b = jpeg(1)
        val png = b.copyOf().also { it[0] = 0x89.toByte() }
        for ((f, code) in listOf(
            form("NOT-A-UUID", b) to "ERR_VALIDATION", form("aaaaaaaa-1111-4111-8111-11111111111b".uppercase(), b) to "ERR_VALIDATION",
            form(ok, b, purpose = "outlet_capture") to "ERR_VALIDATION", form(ok, b, s = "a".repeat(64)) to "ERR_VALIDATION",
            MediaUploadForm(ok, "feedback", sha(b), null, null) to "ERR_VALIDATION",
            form(ok, jpeg(1, MediaUpload.MAX_BYTES + 1)) to "ERR_PAYLOAD_TOO_LARGE",
            form(ok, png, s = sha(png)) to "ERR_UNSUPPORTED_MEDIA_TYPE", form(ok, b, type = ContentType.Image.PNG) to "ERR_UNSUPPORTED_MEDIA_TYPE",
        )) {
            assertEquals(code, assertFailsWith<ApiProblem> { m.store(p, f) }.code.name, f.toString())
        }
        assertEquals(0, writes.size)
    }
}

/** The multipart reader: fields and the file part are read; a non-multipart body is 415; an oversize file is cut and then 413. */
class MediaUploadParseTest {
    private fun app(body: suspend (io.ktor.client.HttpClient) -> Unit) = io.ktor.server.testing.testApplication {
        routing {
            post("/t") {
                val out = try {
                    val f = MediaUpload.parse(call)
                    "${f.mediaUuid}|${f.purpose}|${f.sha256}|${f.file?.size}|${f.fileType}"
                } catch (e: ApiProblem) { e.code.name }
                call.respondText(out)
            }
        }
        body(client)
    }

    private suspend fun io.ktor.client.HttpClient.upload(size: Int, type: String = "image/jpeg") = submitFormWithBinaryData(
        "/t",
        formData {
            append("media_uuid", "11111111-1111-4111-8111-111111111111"); append("purpose", "support"); append("sha256", "ab")
            append("file", ByteArray(size), io.ktor.http.Headers.build {
                append(io.ktor.http.HttpHeaders.ContentType, type); append(io.ktor.http.HttpHeaders.ContentDisposition, "filename=\"p.jpg\"")
            })
        },
    ).bodyAsText()

    @Test
    fun readsTheFormAndBoundsTheFile() = app { c ->
        assertEquals("11111111-1111-4111-8111-111111111111|support|ab|1000|image/jpeg", c.upload(1000))
        assertEquals("${MediaUpload.MAX_BYTES + 1}", c.upload(MediaUpload.MAX_BYTES + 1).split('|')[3], "one byte over is read (then 413 in store)")
        assertEquals("ERR_PAYLOAD_TOO_LARGE", c.upload(MediaUpload.MAX_BYTES + 20_000), "a longer body is refused from its Content-Length")
        assertEquals("ERR_UNSUPPORTED_MEDIA_TYPE", c.post("/t") { setBody("{}"); contentType(ContentType.Application.Json) }.bodyAsText())
    }
}
