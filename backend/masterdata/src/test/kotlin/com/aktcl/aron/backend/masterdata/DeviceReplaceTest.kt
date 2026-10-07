package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.AccessTokenVerifier
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.PlatformContext
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.platform.ScopeVersionLookup
import com.aktcl.aron.backend.platform.installAronPlatform
import com.aktcl.aron.contract.Role
import io.ktor.client.request.bearerAuth
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** F-API-086: upload-first or revoke-now for the old phone, and an OTP for the new one. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeviceReplaceTest {
    private lateinit var env: SeededAdminDb
    @BeforeAll fun setUp() { env = SeededAdminDb() }
    @AfterAll fun tearDown() = env.close()

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val cfg = RegistryDefaults(overrides = mapOf("cfg.api.rl.user_per_min" to JsonPrimitive(2000)))
        application {
            installAronPlatform(PlatformContext(com.aktcl.aron.backend.platform.AronClock.SYSTEM, cfg, { "00000000-0000-0000-0000-000000000000" }))
            val guard = AuthGuardDeps(AccessTokenVerifier(TestTokens.keys), ScopeVersionLookup { 1 }, cfg)
            val geo = GeoRepository(env.fresh.db)
            routing { route("/v1") { deviceReplaceRoutes(DeviceOtpDeps(env.fresh.db, SqlReachResolver(env.fresh.db, geo), OtpCipher("k".toByteArray()), com.aktcl.aron.backend.platform.DbServerConfig(env.fresh.db, cfg), guard)) } }
        }
        block()
    }

    @Test
    fun uploadFirstKeepsTheOldPhoneAndRevokeNowRevokesItBothIssueAnOtp() = app {
        val dev = env.scalar("SELECT device_id FROM app.device_binding WHERE status = 'active' LIMIT 1")!!.toLong()
        val adm = TestTokens.web(env.ids.getValue("admin1001"), Role.ADMIN)
        env.fresh.db.jdbi.useHandle<Exception> { it.execute("UPDATE app.device SET pending_rows_reported = 7 WHERE id = $dev") }
        val r1 = client.post("/v1/admin/devices/$dev/replace") { bearerAuth(adm); contentType(ContentType.Application.Json); setBody("""{"mode":"upload_first","reason":"Screen broke, new phone tomorrow"}""") }
        assertEquals(HttpStatusCode.OK, r1.status)
        val j1 = Json.parseToJsonElement(r1.bodyAsText()).jsonObject
        assertEquals(7, j1["pending_rows"]!!.jsonPrimitive.content.toInt()); assertEquals("active", j1["old_state"]!!.jsonPrimitive.content)
        assertTrue(j1["otp"]!!.jsonObject["otp"]!!.jsonPrimitive.content.matches(Regex("^[0-9]{4}$")))
        val r2 = client.post("/v1/admin/devices/$dev/replace") { bearerAuth(adm); contentType(ContentType.Application.Json); setBody("""{"mode":"revoke_now","reason":"Phone stolen, revoke it now"}""") }
        assertEquals("revoked", Json.parseToJsonElement(r2.bodyAsText()).jsonObject["old_state"]!!.jsonPrimitive.content)
        assertEquals("revoked", env.scalar("SELECT status FROM app.device WHERE id = $dev"))
        assertEquals("0", env.scalar("SELECT count(*) FROM app.device_binding WHERE device_id = $dev AND status = 'active'"))
        // already revoked: conflict; unknown: 404; wrong role: 403; bad body: 400
        assertEquals(HttpStatusCode.Conflict, client.post("/v1/admin/devices/$dev/replace") { bearerAuth(adm); contentType(ContentType.Application.Json); setBody("""{"mode":"revoke_now","reason":"Second revoke should conflict"}""") }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/v1/admin/devices/987654/replace") { bearerAuth(adm); contentType(ContentType.Application.Json); setBody("""{"mode":"revoke_now","reason":"No such device exists here"}""") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/admin/devices/$dev/replace") { bearerAuth(TestTokens.web(env.ids.getValue("tso1001"), Role.TSO)); contentType(ContentType.Application.Json); setBody("""{"mode":"revoke_now","reason":"A TSO may not replace devices"}""") }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/v1/admin/devices/$dev/replace") { bearerAuth(adm); contentType(ContentType.Application.Json); setBody("""{"mode":"later","reason":"Bad mode value here ok"}""") }.status)
    }
}
