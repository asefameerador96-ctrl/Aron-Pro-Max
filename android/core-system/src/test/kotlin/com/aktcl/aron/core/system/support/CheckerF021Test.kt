package com.aktcl.aron.core.system.support

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

/** Independent checker for F-SYS-021 (refute, don't rubber-stamp). */
class CheckerF021Test {
    @get:Rule val tmp = TemporaryFolder()
    private val keys: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val pub = Base64.getEncoder().encodeToString(keys.public.encoded)

    private fun input(log: List<String>) =
        SupportInput("1.2.3+12", 3, 7, "dev-1", "2026-10-07T05:00:00Z", mapOf("pending" to 1), listOf("""{"client_uuid":"u1"}"""), emptyList(), log, "2026-10-07T06:00:00Z")

    private fun open(sealed: ByteArray): String {
        val b = ByteBuffer.wrap(sealed); ByteArray(8).also(b::get)
        val wrapped = ByteArray(b.short.toInt()).also(b::get); val iv = ByteArray(12).also(b::get); val body = ByteArray(b.remaining()).also(b::get)
        val aes = Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
            init(Cipher.DECRYPT_MODE, keys.private, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
        }.doFinal(wrapped)
        val gz = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, iv)) }.doFinal(body)
        return GZIPInputStream(gz.inputStream()).readBytes().decodeToString()
    }

    /** docs/17 s10.4: "the last 200 log lines". The bundle passes through whatever it is given. */
    @Test fun checker_logIsCappedAtTheLast200Lines() {
        val sealed = SupportBundle.build(input((1..1000).map { "line $it" }), pub, 20 * 1024 * 1024)
        val log = Json.parseToJsonElement(open(sealed)).jsonObject["log"]!!.jsonArray
        assertEquals(200, log.size)
    }

    private suspend fun queued(q: SupportQueue): SupportJob {
        val sealed = SupportBundle.build(input(listOf("x")), pub, 1_000_000)
        return q.enqueue("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", sealed, "1.2.3+12", "2026-10-07T05:00:00Z", 1, 1000)
    }

    /**
     * Acceptance: "success and failure are shown". A file whose every attempt fails with a retryable error (503, 401, io,
     * SAS 403) stays QUEUED with no bound; SupportWorker gives up after 8 runs (Result.success) and nothing reschedules it,
     * while the screen keeps saying "Sending…" forever and the failure is never shown.
     */
    @Test fun checker_repeatedRetryableFailuresAreEventuallyShownNotSendingForever() = runTest {
        val q = SupportQueue(tmp.newFolder("c1"))
        queued(q)
        val api = SupportApi { _, _ -> SupportStep.RETRY to "http_503" }
        val up = SupportUploader(q, api) { 0 }
        repeat(50) { up.run(onWifi = true, online = true, wifiOnly = true) }
        val c = SupportController(q, { input(emptyList()) }, { pub }, { 20 }, {}, { 0 })
        val shown = c.status(online = true, onWifi = true, wifiOnly = true) { "t" }
        assertNotEquals(SupportStatus.Queued(online = true, needsWifi = false), shown)
    }

    /** A blob PUT 413 (file over the container limit) is shown as the generic refusal, not "too large". */
    @Test fun checker_put413IsShownAsTooLarge() = runTest {
        val q = SupportQueue(tmp.newFolder("c2"))
        queued(q)
        SupportUploader(q, { _, _ -> SupportStep.REFUSED to "put_413" }) { 0 }.run(true, true, true)
        val c = SupportController(q, { input(emptyList()) }, { pub }, { 20 }, {}, { 0 })
        assertEquals(SupportStatus.Failed(SupportFailure.TOO_LARGE), c.status(true, true, true) { "t" })
    }
}
