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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F-API-036 and F-TSO-019: device OTPs stored AES-GCM encrypted, scoped by reach, idempotent on replay, audited without the value. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeviceOtpTest {
    private lateinit var env: SeededAdminDb
    private val cipher = OtpCipher("test-secret-for-otp".toByteArray())

    @BeforeAll fun setUp() { env = SeededAdminDb() }
    @AfterAll fun tearDown() = env.close()

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val cfg = RegistryDefaults(overrides = mapOf("cfg.api.rl.user_per_min" to JsonPrimitive(2000)))
        application {
            installAronPlatform(PlatformContext(com.aktcl.aron.backend.platform.AronClock.SYSTEM, cfg, { "00000000-0000-0000-0000-000000000000" }))
            val guard = AuthGuardDeps(AccessTokenVerifier(TestTokens.keys), ScopeVersionLookup { 1 }, cfg)
            val geo = GeoRepository(env.fresh.db)
            routing { route("/v1") { deviceOtpRoutes(DeviceOtpDeps(env.fresh.db, SqlReachResolver(env.fresh.db, geo), cipher, com.aktcl.aron.backend.platform.DbServerConfig(env.fresh.db, cfg), guard)) } }
        }
        block()
    }

    private fun tok(user: String, role: Role) = TestTokens.web(env.ids.getValue(user), role)
    private fun body(uid: Long, reason: String = "Phone replaced, SR is at the depot") = """{"user_id":$uid,"reason":"$reason"}"""

    @Test
    fun supportIssuesAnEncryptedOtpReplayChangesNothingAndANewReasonReplacesIt() = app {
        val sr = env.ids.getValue("sr1001")
        val r1 = client.post("/v1/admin/device-otps") { bearerAuth(tok("admin1001", Role.ADMIN)); contentType(ContentType.Application.Json); setBody(body(sr)) }
        assertEquals(HttpStatusCode.Created, r1.status)
        val otp = Json.parseToJsonElement(r1.bodyAsText()).jsonObject["otp"]!!.jsonPrimitive.content
        assertTrue(otp.matches(Regex("^[0-9]{4}$")))
        // Stored encrypted: neither the digits nor a plain hash-less copy sit in the row; another user's AAD does not open it.
        val blob = env.fresh.db.jdbi.withHandle<ByteArray, Exception> { h -> h.createQuery("SELECT otp_cipher FROM app.device_otp WHERE user_id = :u AND revoked_at IS NULL").bind("u", sr).mapTo(ByteArray::class.java).one() }
        assertFalse(String(blob, Charsets.ISO_8859_1).contains(otp)); assertEquals(otp, cipher.open(blob, sr)); assertNull(cipher.open(blob, sr + 1))
        val replay = client.post("/v1/admin/device-otps") { bearerAuth(tok("admin1001", Role.ADMIN)); contentType(ContentType.Application.Json); setBody(body(sr)) }
        assertEquals(otp, Json.parseToJsonElement(replay.bodyAsText()).jsonObject["otp"]!!.jsonPrimitive.content)
        assertEquals("1", env.scalar("SELECT count(*) FROM app.device_otp WHERE user_id = ${sr} AND revoked_at IS NULL"))
        val again = client.post("/v1/admin/device-otps") { bearerAuth(tok("admin1001", Role.ADMIN)); contentType(ContentType.Application.Json); setBody(body(sr, "Second device, the first one was lost")) }
        assertEquals(HttpStatusCode.Created, again.status)
        assertEquals("1", env.scalar("SELECT count(*) FROM app.device_otp WHERE user_id = ${sr} AND revoked_at IS NULL"))
        assertEquals("0", env.scalar("SELECT count(*) FROM app.audit_log WHERE after::text LIKE '%$otp%'").let { if (it == "0") "0" else it })
    }

    @Test
    fun aTsoSeesOnlyTheOtpOfSrsInOwnZonesAndCannotIssueOutsideThem() = app {
        val sr = env.ids.getValue("sr1001"); val other = env.ids.getValue("sr2001")
        client.post("/v1/admin/device-otps") { bearerAuth(tok("admin1001", Role.ADMIN)); contentType(ContentType.Application.Json); setBody(body(sr, "Bind for the new phone at the depot")) }
        client.post("/v1/admin/device-otps") { bearerAuth(tok("admin1001", Role.ADMIN)); contentType(ContentType.Application.Json); setBody(body(other, "Bind for the new phone at the depot")) }
        val mine = Json.parseToJsonElement(client.get("/v1/admin/device-otps") { bearerAuth(tok("tso1001", Role.TSO)) }.bodyAsText()).jsonObject["items"]!!.jsonArray
        val names = mine.map { it.jsonObject["username"]!!.jsonPrimitive.content }
        assertTrue("sr1001" in names); assertFalse("sr2001" in names)
        assertNotEquals(null, mine.first { it.jsonObject["username"]!!.jsonPrimitive.content == "sr1001" }.jsonObject["otp"]?.takeIf { it !is kotlinx.serialization.json.JsonNull })
        val theirs = Json.parseToJsonElement(client.get("/v1/admin/device-otps") { bearerAuth(tok("tso2001", Role.TSO)) }.bodyAsText()).jsonObject["items"]!!.jsonArray.map { it.jsonObject["username"]!!.jsonPrimitive.content }
        assertEquals(listOf("sr2001"), theirs)
        val denied = client.post("/v1/admin/device-otps") { bearerAuth(tok("tso2001", Role.TSO)); contentType(ContentType.Application.Json); setBody(body(sr, "Trying to reach into another territory")) }
        assertEquals(HttpStatusCode.Forbidden, denied.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/v1/admin/device-otps?zone_id=${env.scalar("SELECT id FROM app.zone WHERE code = 'Z-OTHER'")}") { bearerAuth(tok("tso1001", Role.TSO)) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/v1/admin/device-otps") { bearerAuth(tok("sr1001", Role.SR)) }.status)
        assertTrue(env.scalar("SELECT count(*) FROM app.audit_log WHERE entity = 'device_otp' AND action = 'view'")!!.toInt() >= 2)
    }

    @Test
    fun validationRefusesBadBodiesAndNonFieldUsers() = app {
        val adm = tok("admin1001", Role.ADMIN)
        assertEquals(HttpStatusCode.BadRequest, client.post("/v1/admin/device-otps") { bearerAuth(adm); contentType(ContentType.Application.Json); setBody("""{"user_id":1}""") }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/v1/admin/device-otps") { bearerAuth(adm); contentType(ContentType.Application.Json); setBody(body(env.ids.getValue("sr1001"), "short")) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/v1/admin/device-otps") { bearerAuth(adm); contentType(ContentType.Application.Json); setBody(body(env.ids.getValue("dmo1001"), "A DMO has no field phone to bind")) }.status)
    }

    @Test
    fun panelRowsCarryEmployeeCodeAndZoneNameAndCode() = app { // contract v1.2
        val sr = env.ids.getValue("sr1001")
        val issued = Json.parseToJsonElement(client.post("/v1/admin/device-otps") { bearerAuth(tok("admin1001", Role.ADMIN)); contentType(ContentType.Application.Json); setBody(body(sr, "Panel columns check for v1.2")) }.bodyAsText()).jsonObject
        val expected = env.scalar("SELECT u.employee_code || '|' || z.code || '|' || z.name FROM app.app_user u JOIN app.zone z ON z.id = u.home_zone_id WHERE u.id = $sr")!!
        assertEquals(expected, listOf("employee_code", "zone_code", "zone_name").joinToString("|") { issued[it]!!.jsonPrimitive.content })
        val listed = Json.parseToJsonElement(client.get("/v1/admin/device-otps?q=sr1001") { bearerAuth(tok("admin1001", Role.ADMIN)) }.bodyAsText()).jsonObject["items"]!!.jsonArray.single().jsonObject
        assertEquals(expected, listOf("employee_code", "zone_code", "zone_name").joinToString("|") { listed[it]!!.jsonPrimitive.content })
    }
}
