package com.aktcl.aron.backend.media

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.Audience
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.RateLimiter
import com.aktcl.aron.contract.Role
import io.mockk.mockk
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * POST /v1/client-errors (F-SYS-032): web only, stored once per error_uuid with the user from the token, scrubbed of
 * PII and credentials by the server, a future browser clock clamped, malformed reports 400, at most 30 a minute per
 * user (429), 503 while `app.client_error` is missing (docs/requests/backend-core-client-error-table.md; the test
 * creates the requested table when the migrations do not have it yet).
 */
class ClientErrorsTest {
    private val now = Instant.parse("2027-01-03T19:00:00Z") // 2027-01-04 01:00 in Dhaka

    private fun web(id: Long) = AronPrincipal(id, "admin1001", Role.ADMIN, 1, Audience.API, null, null, "web", emptyList(), false, listOf("pwd"), "j", now)
    private fun report(uuid: String, message: String = "TypeError: x is undefined", occurred: String = "2027-01-03T18:59:00.000Z", stack: String? = null) =
        ClientErrorReport(uuid, "web", occurred, "/dashboards/sales", message, stack, "web-1.2.3")

    private fun tableExists(fresh: FreshDb) = fresh.db.jdbi.withHandle<Boolean, Exception> { it.createQuery("SELECT to_regclass('app.client_error') IS NOT NULL").mapTo(Boolean::class.java).one() }

    private fun createRequestedTable(fresh: FreshDb) = fresh.db.jdbi.useHandle<Exception> { h ->
        h.execute(
            """
            CREATE TABLE IF NOT EXISTS app.client_error (
              id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, error_uuid uuid NOT NULL UNIQUE, user_id bigint NOT NULL REFERENCES app.app_user(id),
              source text NOT NULL CHECK (source IN ('web')), occurred_at timestamptz NOT NULL, received_at timestamptz NOT NULL DEFAULT now(),
              business_date date NOT NULL, page text CHECK (length(page) <= 200), message text NOT NULL CHECK (length(message) <= 500),
              stack text CHECK (length(stack) <= 16000), build text CHECK (length(build) <= 40))
            """.trimIndent(),
        )
    }

    @Test
    fun aReportIsStoredOnceScrubbedAndDatedInDhaka() {
        FreshDb.create().use { fresh ->
            val me = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery("SELECT id FROM app.app_user WHERE username = 'aron.system'").mapTo(Long::class.java).one() }
            val ce = ClientErrors(MediaDeps(fresh.db, mockk(relaxed = true), mockk(relaxed = true), AronClock { now }), RateLimiter(30, 60, AronClock { now }))
            val a = "11111111-1111-4111-8111-111111111111"
            if (!tableExists(fresh)) {
                assertEquals("ERR_SERVICE_UNAVAILABLE", assertFailsWith<ApiProblem> { ce.report(web(me), report(a)) }.code.name, "missing table is 503, not 500")
                createRequestedTable(fresh)
            }
            val leaky = "failed for rahim@example.com 01712345678 at /x?sig=abc123&se=2027 Authorization: Bearer eyJhbGciOi.eyJzdWIiOiIx.c2lnbmF0dXJl card 1234567890123"
            ce.report(web(me), report(a, leaky, stack = "at f (app.js:10:5)\n" + "x".repeat(15_970) + " key="))
            ce.report(web(me), report(a, "a different body under the same uuid"))
            ce.report(web(me), report("22222222-2222-4222-8222-222222222222", occurred = "2027-01-05T00:00:00.000Z"))
            val rows = fresh.db.jdbi.withHandle<List<Map<String, Any?>>, Exception> { h ->
                h.createQuery("SELECT error_uuid::text AS u, user_id, message, length(stack) AS sl, business_date::text AS bd, occurred_at FROM app.client_error ORDER BY id").mapToMap().list()
            }
            assertEquals(2, rows.size, "a repeat of an error_uuid stores nothing")
            val msg = rows[0]["message"] as String
            for (leak in listOf("rahim@", "01712345678", "abc123", "eyJ", "1234567890123")) assertFalse(leak in msg, "$leak leaked: $msg")
            assertTrue("[email]" in msg && "[phone]" in msg && "sig=[redacted]" in msg && "[token]" in msg, msg)
            assertEquals(me, rows[0]["user_id"])
            assertEquals("2027-01-04", rows[0]["bd"], "Dhaka business date of the receive time")
            assertTrue((rows[0]["sl"] as Int) <= 16_000)
            assertEquals(now, (rows[1]["occurred_at"] as java.sql.Timestamp).toInstant(), "a browser clock ahead is clamped")
        }
    }

    @Test
    fun theScrubberIsFastOnHostileInputAndCatchesTheCheckersCases() {
        for (hostile in listOf("a".repeat(16_000), "a@" + "a.".repeat(8_000), "eyJ" + "a".repeat(16_000), "0".repeat(16_000), "token=" + "x".repeat(16_000))) {
            val t0 = System.nanoTime()
            ClientErrors.scrub(hostile)
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 200, "scrub of ${hostile.take(10)}... took too long")
        }
        val s = ClientErrors.scrub("password=hunter2 nul\u0000here +880 1712-345678 017-1234-5678 Authorization: Basic dXNlcjpwYXNz\nCookie: aron_rt=abc; x=y\nnext")
        for (leak in listOf("hunter2", "\u0000", "1712", "1234-5678", "dXNlcjpwYXNz", "aron_rt=abc")) assertFalse(leak in s, "$leak leaked: $s")
        assertTrue("password=[redacted]" in s && "Basic [token]" in s && "\nnext" in s, s)
    }

    @Test
    fun phonesMalformedReportsAndFloodsAreRefused() {
        FreshDb.create().use { fresh ->
            createRequestedTable(fresh)
            val me = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery("SELECT id FROM app.app_user WHERE username = 'aron.system'").mapTo(Long::class.java).one() }
            val ce = ClientErrors(MediaDeps(fresh.db, mockk(relaxed = true), mockk(relaxed = true), AronClock { now }), RateLimiter(3, 60, AronClock { now }))
            val ok = "33333333-3333-4333-8333-333333333333"
            val phone = web(me).copy(flavour = "sr", deviceUuid = "00000000-0000-4000-8000-000000000001", deviceId = 1)
            assertEquals("ERR_FORBIDDEN", assertFailsWith<ApiProblem> { ce.report(phone, report(ok)) }.code.name)
            for ((bad, pointer) in listOf(
                report("NOT-A-UUID") to "/error_uuid", report(ok).copy(source = "app") to "/source", report(ok, occurred = "2027-01-03 18:59") to "/occurred_at",
                report(ok, occurred = "2027-13-03T18:59:00.000Z") to "/occurred_at", report(ok, message = " ") to "/message", report(ok, message = "m".repeat(501)) to "/message",
                report(ok).copy(page = "p".repeat(201)) to "/page", report(ok, stack = "s".repeat(16_001)) to "/stack", report(ok).copy(build = "b".repeat(41)) to "/build",
            )) assertEquals("ERR_VALIDATION" to pointer, assertFailsWith<ApiProblem> { ce.report(web(me), bad) }.let { it.code.name to it.errors.single().pointer })
            repeat(3) { ce.report(web(me), report("4444444$it-4444-4444-8444-444444444444")) }
            val e = assertFailsWith<ApiProblem> { ce.report(web(me), report("55555555-5555-4555-8555-555555555555")) }
            assertEquals("ERR_RATE_LIMITED", e.code.name)
            assertEquals(3, fresh.db.jdbi.withHandle<Int, Exception> { it.createQuery("SELECT count(*) FROM app.client_error").mapTo(Int::class.java).one() })
        }
    }
}
