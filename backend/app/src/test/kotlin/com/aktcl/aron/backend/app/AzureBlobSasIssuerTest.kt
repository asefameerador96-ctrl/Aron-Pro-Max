package com.aktcl.aron.backend.app

import com.azure.core.credential.AccessToken
import com.azure.core.credential.TokenCredential
import com.azure.storage.blob.BlobServiceClientBuilder
import com.azure.storage.blob.models.UserDelegationKey
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Offline: the SAS is signed locally with a fake delegation key; no request reaches Azure. */
class AzureBlobSasIssuerTest {
    private val t0 = Instant.parse("2026-10-07T06:00:00Z")
    private var clock = t0
    private var keyCalls = 0

    private fun issuer(): AzureBlobSasIssuer {
        val noToken = TokenCredential { Mono.just(AccessToken("unused", OffsetDateTime.now().plusHours(1))) }
        val service = BlobServiceClientBuilder().endpoint("https://staronx.blob.core.windows.net").credential(noToken).buildClient()
        return AzureBlobSasIssuer(service, "media", { start, expiry ->
            keyCalls++
            UserDelegationKey().setSignedObjectId("00000000-0000-0000-0000-000000000001").setSignedTenantId("00000000-0000-0000-0000-000000000002")
                .setSignedStart(start).setSignedExpiry(expiry).setSignedService("b").setSignedVersion("2025-01-05")
                .setValue(Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))
        }, now = { clock })
    }

    private fun query(url: String): Map<String, String> =
        url.substringAfter('?').split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    @Test
    fun writeSasIsCreateWriteOnOneBlobOverHttpsUntilTheGivenTime() {
        val url = issuer().writeSas("support/2026/7/abc.zip.enc", 1024, t0.plusSeconds(900))
        // The SDK percent-encodes the "/" of the blob name (%2F); the service decodes it to the same blob.
        assertEquals("https://staronx.blob.core.windows.net/media/support/2026/7/abc.zip.enc",
                     java.net.URLDecoder.decode(url.substringBefore('?'), "UTF-8"))
        val q = query(url)
        assertEquals("cw", q["sp"])
        assertEquals("https", q["spr"])
        assertEquals("b", q["sr"])
        assertEquals("2026-10-07T06:15:00Z", q["se"])
        assertTrue(q["sig"]!!.isNotEmpty() && q["skoid"] == "00000000-0000-0000-0000-000000000001")
    }

    @Test
    fun readUrlIsReadOnlyForADay() {
        val q = query(issuer().readUrl("assets/tutorial/x.mp4"))
        assertEquals("r", q["sp"])
        assertEquals("2026-10-08T06:00:00Z", q["se"])
    }

    @Test
    fun theDelegationKeyIsReusedAndRenewedBeforeItExpires() {
        val i = issuer()
        i.writeSas("a/1", 1, t0.plusSeconds(60)); i.readUrl("a/2")
        assertEquals(1, keyCalls, "one key for many SAS")
        clock = t0.plus(Duration.ofHours(24))
        i.readUrl("a/3")
        assertEquals(2, keyCalls, "renewed when less than 25 h of a 2-day key remain")
    }

    @Test
    fun rejectsBadInput() {
        val i = issuer()
        assertFailsWith<IllegalArgumentException> { i.writeSas("../etc", 1, t0.plusSeconds(60)) }
        assertFailsWith<IllegalArgumentException> { i.writeSas("/abs", 1, t0.plusSeconds(60)) }
        assertFailsWith<IllegalArgumentException> { i.writeSas("a/b", 0, t0.plusSeconds(60)) }
        assertFailsWith<IllegalArgumentException> { i.writeSas("a/b", 1, t0.minusSeconds(1)) }
    }

    @Test
    fun notConfiguredWithoutAnAccount() {
        assertNull(AzureBlobSasIssuer.fromEnvironment { null })
    }
}
