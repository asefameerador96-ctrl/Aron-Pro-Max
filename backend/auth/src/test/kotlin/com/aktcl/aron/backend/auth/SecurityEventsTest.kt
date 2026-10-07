package com.aktcl.aron.backend.auth

import com.aktcl.aron.backend.platform.SecurityEvent
import com.aktcl.aron.backend.platform.SecurityEventKind
import com.aktcl.aron.backend.platform.SecurityEvents
import com.aktcl.aron.backend.platform.safely
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** AUD-SEC-03: authentication leaves a durable trail (for now the `aron.security` log line; the table follows). */
class SecurityEventsTest {
    private val made = mutableListOf<AuthFixture>()

    @AfterEach
    fun close() { made.forEach { it.close() }; made.clear() }

    private fun fixture(overrides: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()) = AuthFixture(overrides).also { made += it }

    private fun AuthFixture.events(kind: SecurityEventKind) = securityLog.filter { "\"security_event\":\"${kind.wire}\"" in it }

    @Test
    fun aRefreshTokenReplayedAfterSixtySecondsWritesExactlyOneReuseEventAndRevokesTheFamily() {
        val f = fixture()
        testApplication {
            application { f.application(this) }
            val rt = json(client.login("sr334001", "correct horse 1", f.srDevice).bodyAsText())["refresh_token"]!!.jsonPrimitive.content
            val rt2 = json(client.refresh(rt, f.srDevice).bodyAsText())["refresh_token"]!!.jsonPrimitive.content
            f.clock.advance(61)
            assertEquals("ERR_AUTH_REFRESH_REUSED", json(client.refresh(rt, f.srDevice).bodyAsText()).code)
            // The family is revoked: the replacement and further replays are refused, and add no second event.
            assertEquals("ERR_AUTH_REFRESH_REUSED", json(client.refresh(rt2, f.srDevice).bodyAsText()).code)
            assertEquals("ERR_AUTH_REFRESH_REUSED", json(client.refresh(rt, f.srDevice).bodyAsText()).code)
        }
        assertEquals(1, f.events(SecurityEventKind.REFRESH_REUSE).size, f.securityLog.joinToString("\n"))
    }

    @Test
    fun failedLoginsAreOneEventPerAccountAndDevicePerMinuteAndTheLockoutIsOne() {
        val f = fixture(mapOf("cfg.auth.lockout_attempts" to JsonPrimitive(5)))
        testApplication {
            application { f.application(this) }
            repeat(4) { assertEquals(HttpStatusCode.Unauthorized, client.login("sr334001", "bad-guess-$it", f.srDevice).status) }
            f.clock.advance(60)
            assertEquals(HttpStatusCode.Unauthorized, client.login("sr334001", "bad-guess-5", f.srDevice).status)
            assertEquals("ERR_AUTH_ACCOUNT_LOCKED", json(client.login("sr334001", "correct horse 1", f.srDevice).bodyAsText()).code)
        }
        assertEquals(2, f.events(SecurityEventKind.LOGIN_FAILURE).size, f.securityLog.joinToString("\n"))
        assertEquals(1, f.events(SecurityEventKind.LOCKOUT).size)
        // PII-free: the username and the passwords never appear; the account is a hash.
        f.securityLog.forEach { l ->
            assertTrue("sr334001" !in l && "bad-guess" !in l && "correct horse" !in l, l)
        }
        assertTrue(f.events(SecurityEventKind.LOGIN_FAILURE).all { SecurityEvents.usernameHash("sr334001") in it })
    }

    @Test
    fun aDeviceHeaderThatDiffersFromTheTokenIsRecorded() {
        val f = fixture()
        testApplication {
            application { f.application(this) }
            val at = json(client.login("sr334001", "correct horse 1", f.srDevice).bodyAsText())["access_token"]!!.jsonPrimitive.content
            val r = client.get("/v1/me") { bearerAuth(at); header("X-Device-Id", "7a1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c11") }
            assertEquals("ERR_DEVICE_PROOF_INVALID", json(r.bodyAsText()).code)
        }
        val e = f.events(SecurityEventKind.DEVICE_PROOF_INVALID)
        assertEquals(1, e.size, f.securityLog.joinToString("\n"))
        assertTrue("\"route\":\"/v1/me\"" in e.single())
    }

    @Test
    fun aBrokenSinkNeverFailsTheCaller() {
        SecurityEvents { error("store down") }.safely(SecurityEvent(SecurityEventKind.LOGIN_FAILURE, Instant.parse("2026-10-05T01:00:00Z")))
    }
}
