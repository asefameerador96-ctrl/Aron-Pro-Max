package com.aktcl.aron.core.system.support

import com.aktcl.aron.core.network.ApiOrigin
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.ClientIdentity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

class SupportTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    @Before fun up() { server = MockWebServer(); server.start() }
    @After fun down() { server.close() }

    private val keys: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val pub = Base64.getEncoder().encodeToString(keys.public.encoded)

    private fun input(unsent: List<String> = listOf("""{"type":"memo","client_uuid":"u1"}"""), acked: List<String> = emptyList(), log: List<String> = listOf("sync ok")) =
        SupportInput("1.2.3+12", 3, 7, "dev-1", "2026-10-07T05:00:00Z", mapOf("pending" to 1, "acked" to 40), unsent, acked, log, "2026-10-07T06:00:00Z")

    /** Support's side: unwrap, decrypt, gunzip. */
    private fun open(sealed: ByteArray): String {
        val b = ByteBuffer.wrap(sealed)
        val magic = ByteArray(8).also(b::get)
        assertEquals("ARONSUP1", String(magic))
        val wrapped = ByteArray(b.short.toInt()).also(b::get)
        val iv = ByteArray(12).also(b::get)
        val body = ByteArray(b.remaining()).also(b::get)
        val aes = Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
            init(Cipher.DECRYPT_MODE, keys.private, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
        }.doFinal(wrapped)
        val gz = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, iv)) }.doFinal(body)
        return GZIPInputStream(gz.inputStream()).readBytes().decodeToString()
    }

    @Test fun theFileCarriesVersionLastSyncAndTheUnsentRowsAndOnlySupportCanReadIt() {
        val sealed = SupportBundle.build(input(), pub, 20 * 1024 * 1024)
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("memo"))
        val o = Json.parseToJsonElement(open(sealed)).jsonObject
        assertEquals("1.2.3+12", o["app_version"]!!.jsonPrimitive.content)
        assertEquals("2026-10-07T05:00:00Z", o["last_sync_at"]!!.jsonPrimitive.content)
        assertEquals("u1", o["unsent"]!!.jsonArray[0].jsonObject["client_uuid"]!!.jsonPrimitive.content)
    }

    @Test fun overTheCapAckedRowsGoFirstAndUnsentRowsNever() {
        val big = (1..400).map { """{"type":"memo","client_uuid":"acked-$it","note":"${java.util.UUID.randomUUID()}${java.util.UUID.randomUUID()}"}""" }
        val unsent = (1..5).map { """{"type":"memo","client_uuid":"keep-$it"}""" }
        val sealed = SupportBundle.build(input(unsent = unsent, acked = big), pub, 12_000)
        val o = Json.parseToJsonElement(open(sealed)).jsonObject
        assertEquals(5, o["unsent"]!!.jsonArray.size)
        assertTrue(o["recent_acked_dropped"]!!.jsonPrimitive.content.toInt() > 0)
        val tooMuch = (1..2000).map { """{"client_uuid":"${java.util.UUID.randomUUID()}"}""" }
        try { SupportBundle.build(input(unsent = tooMuch), pub, 5_000); error("expected too large") } catch (_: SupportFileTooLargeException) { }
    }

    private fun api() = SupportHttpApi(
        AronApiClient(ApiOrigin.parse(server.url("/").toString(), allowCleartextLoopback = true), AronApiClient.defaultOkHttp(), ClientIdentity("1.2.3+12") { "dev-1" }),
        AronApiClient.defaultOkHttp(),
    )

    private fun sasAnswer(uuid: String) = MockResponse.Builder().code(200).addHeader("X-Aron-Api", "1").body(
        """{"upload_uuid":"$uuid","upload_url":"${server.url("/blob/support/$uuid.bin?sig=s")}","blob_path":"support/$uuid.bin","expires_at":"2026-10-07T07:00:00Z"}""",
    ).build()

    private suspend fun queued(q: SupportQueue): SupportJob {
        val sealed = SupportBundle.build(input(), pub, 1_000_000)
        return q.enqueue("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", sealed, "1.2.3+12", "2026-10-07T05:00:00Z", 1, 1000)
    }

    @Test fun offlineItWaitsQueuedThenOnReconnectItIsSentOnce() = runTest {
        val q = SupportQueue(tmp.newFolder("support"))
        val job = queued(q)
        val up = SupportUploader(q, api(), { 5000 })
        assertTrue(up.run(onWifi = false, online = false, wifiOnly = true)) // still waiting
        assertTrue(up.run(onWifi = false, online = true, wifiOnly = true)) // mobile data, Wi-Fi only: waiting
        assertEquals(SupportState.QUEUED, q.current()!!.state)
        assertEquals(0, server.requestCount)

        server.enqueue(sasAnswer(job.uploadUuid)); server.enqueue(MockResponse.Builder().code(201).build())
        assertFalse(up.run(onWifi = true, online = true, wifiOnly = true))
        val req = server.takeRequest()
        val body = Json.parseToJsonElement(req.body!!.utf8()).jsonObject
        assertEquals("1.2.3+12", body["app_version"]!!.jsonPrimitive.content)
        assertEquals("2026-10-07T05:00:00Z", body["last_sync_at"]!!.jsonPrimitive.content)
        assertEquals(job.sha256, body["sha256"]!!.jsonPrimitive.content)
        val put = server.takeRequest()
        assertEquals("PUT", put.method); assertEquals("BlockBlob", put.headers["x-ms-blob-type"]); assertNull(put.headers["Authorization"])
        val sent = q.current()!!
        assertEquals(SupportState.SENT, sent.state); assertEquals(5000L, sent.sentAtMs)
        assertNull(q.read(job.uploadUuid)) // the sent file is not kept on the phone
        assertFalse(up.run(onWifi = true, online = true, wifiOnly = true))
        assertEquals(2, server.requestCount)
    }

    @Test fun serverTroubleIsARetryAndARefusalIsShownAsFailed() = runTest {
        val q = SupportQueue(tmp.newFolder("s2"))
        queued(q)
        val up = SupportUploader(q, api(), { 0 })
        server.enqueue(MockResponse.Builder().code(503).addHeader("X-Aron-Api", "1").body("{}").build())
        assertTrue(up.run(true, true, true))
        assertEquals(SupportState.QUEUED, q.current()!!.state)
        server.enqueue(MockResponse.Builder().code(413).addHeader("X-Aron-Api", "1").body("{}").build())
        assertFalse(up.run(true, true, true))
        assertEquals(SupportState.FAILED, q.current()!!.state)
    }

    @Test fun aDamagedLocalFileIsNeverSent() = runTest {
        val dir = tmp.newFolder("s3")
        val q = SupportQueue(dir)
        val job = queued(q)
        java.io.File(dir, "${job.uploadUuid}.bin").writeBytes(byteArrayOf(1))
        assertFalse(SupportUploader(q, api(), { 0 }).run(true, true, false))
        assertEquals("local_file_damaged", q.current()!!.lastError)
        assertEquals(0, server.requestCount)
    }

    @Test fun theControllerQueuesAndSchedulesOrSaysWhyNot() = runTest {
        val q = SupportQueue(tmp.newFolder("s4"))
        var scheduled = 0
        val noKey = SupportController(q, { input() }, { null }, { 20 }, { scheduled++ }, { 0 })
        assertEquals(SupportStatus.Failed(SupportFailure.NO_KEY), noKey.send())
        val c = SupportController(q, { input() }, { pub }, { 20 }, { scheduled++ }, { 0 })
        assertTrue(c.send() is SupportStatus.Queued)
        assertEquals(1, scheduled)
        assertEquals(SupportStatus.Queued(online = false, needsWifi = false), c.status(online = false, onWifi = false, wifiOnly = true) { "x" })
        assertEquals(SupportStatus.Queued(online = true, needsWifi = true), c.status(online = true, onWifi = false, wifiOnly = true) { "x" })
        // A second tap replaces the first file: one job on disk.
        c.send()
        assertEquals(1, q.dir.listFiles { f -> f.name.endsWith(".json") }!!.size)
    }

    @Test fun enqueueIsIdempotentByUploadUuid() = runTest {
        val q = SupportQueue(tmp.newFolder("s5"))
        val a = queued(q); val b = queued(q)
        assertEquals(a, b)
    }
}
