package com.aktcl.aron.backend.auth

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Checker tests for AUD-SEC-02 (commit 6dbdb241). Each test states the behaviour the row asks for. */
class LoginAbuseCheckerTest {

    /**
     * An anonymous caller only has to claim to be a phone (client app_sr, a fresh random device_uuid each time) to
     * hash in the phones' pool: with `cfg.device.require_enrolled` false (every non-PROD env, the pilot included) an
     * unknown device passes checkDevice, perDevice is keyed by the attacker's uuid and there is no global phone bound.
     */
    @Test
    fun anAnonymousLoginWithARandomDeviceUuidDoesNotHashInThePhonePool() {
        val f = AuthFixture()
        try {
            testApplication {
                application { f.application(this) }
                val statuses = (1..10).map { i ->
                    client.login("nobody$i", "wrong password $i", UUID.randomUUID().toString(), client = "app_sr").status
                }
                assertTrue(statuses.none { it == HttpStatusCode.OK }, statuses.toString())
                assertEquals(0, f.limiter.peakConcurrent, "unknown-device logins hashed in the phone pool: $statuses")
            }
        } finally { f.close() }
    }

    /**
     * The refresh limiter is keyed by the client-chosen X-Device-Id and consumed before the token is even looked up:
     * anyone who knows a phone's device_uuid can send 20 junk refreshes and the real phone cannot refresh for up to
     * an hour (docs/21 s6.1 keys the limit by the device_id of the grant, not by a header).
     */
    @Test
    fun junkRefreshesWithAVictimsDeviceIdDoNotUseUpTheVictimsBudget() {
        val f = AuthFixture()
        try {
            testApplication {
                application { f.application(this) }
                val login = client.login("sr334001", "correct horse 1", f.srDevice)
                assertEquals(HttpStatusCode.OK, login.status, login.bodyAsText())
                val rt = json(login.bodyAsText())["refresh_token"]!!.jsonPrimitive.content
                repeat(20) { client.refresh("x".repeat(43), f.srDevice) }
                val real = client.refresh(rt, f.srDevice)
                assertEquals(HttpStatusCode.OK, real.status, real.bodyAsText())
            }
        } finally { f.close() }
    }

    /**
     * The "global bound for junk tokens" is skipped by sending a fresh random X-Device-Id with each call: every uuid
     * gets its own 20/h bucket and refreshAnonymous is never touched, so the flood is unbounded.
     */
    @Test
    fun junkRefreshesWithRandomDeviceIdsAreStillBounded() {
        val f = AuthFixture()
        try {
            testApplication {
                application { f.application(this) }
                val statuses = (1..650).map { client.refresh("x".repeat(43), UUID.randomUUID().toString()).status }
                assertTrue(statuses.any { it == HttpStatusCode.TooManyRequests }, "650 junk refreshes in one minute, none limited")
            }
        } finally { f.close() }
    }

    /**
     * The global web bucket (120/min per replica) is shared by attacker and admins: 2 junk logins a second from one
     * address keep every real web user out for as long as the flood lasts (before, the same flood only competed for
     * hash slots). A bound per IP class (trusted Front Door address) must come before the shared one.
     */
    @Test
    fun aWebFloodFromOneAddressDoesNotKeepARealAdminOut() {
        val f = AuthFixture()
        try {
            testApplication {
                application { f.application(this) }
                repeat(120) { i -> client.login("nobody$i", "wrong password $i", client = "web", ip = "198.51.100.7", fdid = "fd-test") }
                val admin = client.login("tso5012", "correct horse 1", client = "web", ip = "103.4.145.10", fdid = "fd-test")
                assertNotEquals(HttpStatusCode.TooManyRequests, admin.status, admin.bodyAsText())
            }
        } finally { f.close() }
    }

    /** docs/21 s2.4 / D-102: doubling stops at 2 h. The JDBI store caps at 7200 s; the in-memory store still at 24 h. */
    @Test
    fun theInMemoryLockoutStoreIsAlsoCappedAtTwoHours() {
        val store = InMemoryLockoutStore()
        var now = Instant.parse("2027-01-03T04:00:00Z")
        repeat(8) {
            store.recordFailure("u|-|x", now, Duration.ofMinutes(15))
            val until = store.lock("u|-|x", now, Duration.ofMinutes(15))
            assertTrue(Duration.between(now, until) <= Duration.ofHours(2), "lock ${Duration.between(now, until)}")
            now = until.plusSeconds(1)
        }
    }
}
