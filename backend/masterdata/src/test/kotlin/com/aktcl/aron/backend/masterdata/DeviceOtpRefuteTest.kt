package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.AccessTokenVerifier
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.PlatformContext
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.platform.ScopeVersionLookup
import com.aktcl.aron.backend.platform.installAronPlatform
import com.aktcl.aron.contract.Role
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Independent checker (F-API-036, F-TSO-019): attempts to refute the device OTP panel. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeviceOtpRefuteTest {
    private lateinit var env: SeededAdminDb
    private val cipher = OtpCipher("refute-secret".toByteArray())

    @BeforeAll fun setUp() { env = SeededAdminDb() }
    @AfterAll fun tearDown() = env.close()

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val cfg = RegistryDefaults(overrides = mapOf("cfg.api.rl.user_per_min" to JsonPrimitive(5000)))
        application {
            installAronPlatform(PlatformContext(com.aktcl.aron.backend.platform.AronClock.SYSTEM, cfg, { "00000000-0000-0000-0000-000000000000" }))
            val guard = AuthGuardDeps(AccessTokenVerifier(TestTokens.keys), ScopeVersionLookup { 1 }, cfg)
            routing { route("/v1") { deviceOtpRoutes(DeviceOtpDeps(env.fresh.db, SqlReachResolver(env.fresh.db, GeoRepository(env.fresh.db)), cipher, com.aktcl.aron.backend.platform.DbServerConfig(env.fresh.db, cfg), guard)) } }
        }
        block()
    }

    private fun id(u: String) = env.scalar("SELECT id FROM app.app_user WHERE username = '$u'")!!.toLong()
    private fun tok(user: String, role: Role) = TestTokens.web(id(user), role)
    private fun newSr(name: String, zoneCode: String?): Long {
        env.fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate("INSERT INTO app.app_user (username, full_name, role, locale, home_zone_id, pilot, must_change_password) VALUES (:n, :n, 'SR', 'bn', (SELECT id FROM app.zone WHERE code = :z), true, false)")
                .bind("n", name).bind("z", zoneCode).execute()
        }
        return id(name)
    }
    private suspend fun ApplicationTestBuilder.issue(as_: String, role: Role, uid: Long, reason: String = "Phone replaced, SR is at the depot"): HttpResponse =
        client.post("/v1/admin/device-otps") { bearerAuth(tok(as_, role)); contentType(ContentType.Application.Json); setBody("""{"user_id":$uid,"reason":"$reason"}""") }
    private suspend fun ApplicationTestBuilder.list(as_: String, role: Role, qs: String = "") =
        client.get("/v1/admin/device-otps$qs") { bearerAuth(tok(as_, role)) }

    /** "Stored AES-GCM encrypted": the unsalted SHA-256 beside the cipher gives the 4-digit OTP back in 10^4 hashes. */
    @Test
    fun storedHashDoesNotRevealTheOtp() = app {
        val sr = newSr("srhash01", "Z-MIR")
        val otp = Json.parseToJsonElement(issue("admin1001", Role.ADMIN, sr).bodyAsText()).jsonObject["otp"]!!.jsonPrimitive.content
        val h = env.fresh.db.jdbi.withHandle<ByteArray, Exception> { it.createQuery("SELECT otp_sha256 FROM app.device_otp WHERE user_id = $sr").mapTo(ByteArray::class.java).one() }
        val md = MessageDigest.getInstance("SHA-256")
        val recovered = (0 until 10_000).map { it.toString().padStart(otp.length, '0') }.firstOrNull { md.digest(it.toByteArray()).contentEquals(h) }
        assertNull(recovered, "OTP $otp recovered from app.device_otp.otp_sha256 without the key")
    }

    /** docs/24 s3 rate table: device OTP issue 10 per user per hour, then 429 (contract lists 429). */
    @Test
    fun eleventhIssueForOneUserInAnHourIsRateLimited() = app {
        val sr = newSr("srrate01", "Z-MIR")
        val codes = (1..11).map { issue("admin1001", Role.ADMIN, sr, "Reissue number $it for the new phone").status }
        assertEquals(HttpStatusCode.TooManyRequests, codes.last(), "statuses: $codes")
    }

    /** docs/24 s8.5: TSO has R (OTP of own zones, view) on device OTPs; W is SUPPORT, ADMIN, SUPERADMIN only. */
    @Test
    fun tsoCannotIssueEvenInOwnZone() = app {
        val sr = newSr("srtso01", "Z-MIR")
        assertEquals(HttpStatusCode.Forbidden, issue("tso1001", Role.TSO, sr, "TSO trying to issue an OTP").status)
    }

    /** Spec: 5 attempts per OTP (cfg.auth.otp_max_attempts), then the OTP expires; the panel must not show it as live. */
    @Test
    fun otpWithExhaustedAttemptsIsNotShownAsLive() = app {
        val sr = newSr("srattempt01", "Z-MIR")
        issue("admin1001", Role.ADMIN, sr)
        env.fresh.db.jdbi.useHandle<Exception> { it.execute("UPDATE app.device_otp SET attempts = 5 WHERE user_id = $sr") }
        val row = Json.parseToJsonElement(list("admin1001", Role.ADMIN, "?q=srattempt01").bodyAsText()).jsonObject["items"]!!.jsonArray.single().jsonObject
        assertEquals(JsonNull, row["otp"], "row: $row")
    }

    /** USER_ZONE ignores valid_from: an SR with no home zone and a FUTURE assignment in another zone moves to that zone today. */
    @Test
    fun futureRouteAssignmentDoesNotMoveSrIntoAnotherTsosPanel() = app {
        val sr = newSr("srfuture01", null)
        env.fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.route (code, name, display_label, zone_id, kind, visit_kind, visit_days_mask, sequence_no) SELECT 'MIR-F1', 'Mirpur F1', 'Daily', id, 'sr', 'daily', 127, 9 FROM app.zone WHERE code = 'Z-MIR'")
            h.execute("INSERT INTO app.route (code, name, display_label, zone_id, kind, visit_kind, visit_days_mask, sequence_no) SELECT 'OTH-F1', 'Other F1', 'Daily', id, 'sr', 'daily', 127, 9 FROM app.zone WHERE code = 'Z-OTHER'")
            h.execute("INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from, reason) SELECT r.id, $sr, 'primary', DATE '2026-01-01', 't' FROM app.route r WHERE r.code = 'MIR-F1'")
            h.execute("INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from, reason) SELECT r.id, $sr, 'primary', DATE '2030-01-01', 't' FROM app.route r WHERE r.code = 'OTH-F1'")
        }
        issue("admin1001", Role.ADMIN, sr)
        val other = Json.parseToJsonElement(list("tso2001", Role.TSO).bodyAsText()).jsonObject["items"]!!.jsonArray.map { it.jsonObject["username"]!!.jsonPrimitive.content }
        val own = Json.parseToJsonElement(list("tso1001", Role.TSO).bodyAsText()).jsonObject["items"]!!.jsonArray.map { it.jsonObject["username"]!!.jsonPrimitive.content }
        assertFalse("srfuture01" in other, "other-territory TSO sees $other"); assertTrue("srfuture01" in own, "own TSO sees $own")
    }

    @Test
    fun concurrentIdenticalPostsLeaveOneLiveOtp() = app {
        val sr = newSr("srrace01", "Z-MIR")
        val bodies = coroutineScope { (1..8).map { async { issue("admin1001", Role.ADMIN, sr) } }.awaitAll() }.map { it.status to it.bodyAsText() }
        assertTrue(bodies.all { it.first == HttpStatusCode.Created }, "$bodies")
        assertEquals(1, bodies.map { Json.parseToJsonElement(it.second).jsonObject["otp"] }.toSet().size)
        assertEquals("1", env.scalar("SELECT count(*) FROM app.device_otp WHERE user_id = $sr"))
    }

    @Test
    fun replayResponseMatchesTheFirstByteForByte() = app {
        val sr = newSr("srreplay01", "Z-MIR")
        val a = issue("admin1001", Role.ADMIN, sr).bodyAsText(); val b = issue("admin1001", Role.ADMIN, sr).bodyAsText()
        assertEquals(a, b)
    }

    @Test
    fun oddInputIs400Never500() = app {
        val adm = tok("admin1001", Role.ADMIN)
        val sr = id("sr1001")
        for (b in listOf("", "{", "[]", "null", """{"user_id":"x","reason":"Phone replaced at depot"}""", """{"user_id":99999999999999999999,"reason":"Phone replaced at depot"}""",
            """{"user_id":$sr,"reason":null}""", """{"user_id":$sr,"reason":"Phone replaced at depot","extra":1}""", """{"user_id":1.5,"reason":"Phone replaced at depot"}""")) {
            val s = client.post("/v1/admin/device-otps") { bearerAuth(adm); contentType(ContentType.Application.Json); setBody(b) }.status
            assertEquals(HttpStatusCode.BadRequest, s, "body <$b>")
        }
        assertEquals(HttpStatusCode.Forbidden, issue("admin1001", Role.ADMIN, Long.MAX_VALUE).status)
        for (qs in listOf("?cursor=abc", "?cursor=99999999999999999999", "?limit=0", "?limit=201", "?limit=x", "?zone_id=-1", "?q=a")) {
            assertEquals(HttpStatusCode.BadRequest, client.get("/v1/admin/device-otps$qs") { bearerAuth(adm) }.status, qs)
        }
        val inj = client.get("/v1/admin/device-otps?q=%27%20OR%201%3D1%20--") { bearerAuth(adm) }
        assertEquals(HttpStatusCode.OK, inj.status); assertEquals(0, Json.parseToJsonElement(inj.bodyAsText()).jsonObject["items"]!!.jsonArray.size)
        val pct = client.get("/v1/admin/device-otps?q=%25%25") { bearerAuth(adm) }
        assertEquals(0, Json.parseToJsonElement(pct.bodyAsText()).jsonObject["items"]!!.jsonArray.size)
    }

    @Test
    fun paginationWalksEveryFieldUserOnce() = app {
        val all = Json.parseToJsonElement(list("admin1001", Role.ADMIN, "?limit=200").bodyAsText()).jsonObject["items"]!!.jsonArray.map { it.jsonObject["user_id"]!!.jsonPrimitive.content }
        val seen = mutableListOf<String>(); var cur: String? = null
        do {
            val p = Json.parseToJsonElement(list("admin1001", Role.ADMIN, "?limit=1" + (cur?.let { "&cursor=$it" } ?: "")).bodyAsText()).jsonObject
            seen += p["items"]!!.jsonArray.map { it.jsonObject["user_id"]!!.jsonPrimitive.content }
            cur = (p["next_cursor"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        } while (cur != null)
        assertEquals(all, seen)
    }

    @Test
    fun expiredRevokedConsumedAreHiddenAndScopeHoldsForDmoAndNoZoneUser() = app {
        val sr = newSr("srstate01", "Z-MIR"); val nz = newSr("srnozone01", null)
        issue("admin1001", Role.ADMIN, sr); issue("admin1001", Role.ADMIN, nz)
        for (upd in listOf("expires_at = now() - interval '1 second'", "revoked_at = now()", "consumed_at = now()")) {
            env.fresh.db.jdbi.useHandle<Exception> { it.execute("UPDATE app.device_otp SET expires_at = now() + interval '1 hour', revoked_at = NULL, consumed_at = NULL WHERE user_id = $sr") }
            env.fresh.db.jdbi.useHandle<Exception> { it.execute("UPDATE app.device_otp SET $upd WHERE user_id = $sr") }
            val row = Json.parseToJsonElement(list("admin1001", Role.ADMIN, "?q=srstate01").bodyAsText()).jsonObject["items"]!!.jsonArray.single().jsonObject
            assertEquals(JsonNull, row["otp"], upd)
        }
        val tso = Json.parseToJsonElement(list("tso1001", Role.TSO).bodyAsText()).jsonObject["items"]!!.jsonArray.map { it.jsonObject["username"]!!.jsonPrimitive.content }
        assertFalse("srnozone01" in tso)
        assertEquals(HttpStatusCode.Forbidden, issue("tso1001", Role.TSO, nz, "No zone user via the TSO").status)
        val dmo = Json.parseToJsonElement(list("dmo1001", Role.DMO).bodyAsText()).jsonObject["items"]!!.jsonArray.map { it.jsonObject["username"]!!.jsonPrimitive.content }
        assertTrue("srstate01" in dmo); assertFalse("srnozone01" in dmo)
        for (r in listOf(Role.AMO, Role.TOP, Role.ANALYST, Role.SR)) assertEquals(HttpStatusCode.Forbidden, list("admin1001", r).status, r.name)
        assertEquals(HttpStatusCode.Forbidden, issue("dmo1001", Role.DMO, sr, "DMO must not issue an OTP").status)
    }
}
