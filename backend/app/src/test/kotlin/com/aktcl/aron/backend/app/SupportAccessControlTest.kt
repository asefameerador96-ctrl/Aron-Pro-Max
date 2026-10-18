package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Settings
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AZ-2 / AZ-6 positive controls through the production wiring: SUPPORT loses business reports and others' exports but
 * keeps what docs/24 gives it (s8.4 national read of users and devices, sync health; enrolment tokens; device OTP issue;
 * entry unlocks, cfg.web.entry_unlock_roles; risk signals; tutorials). A role gate answers 403 before any body is read,
 * so "not 403" on an empty body is the role passing; ANALYST is the negative control on the same writes.
 */
class SupportAccessControlTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val password = "Support-pass-0001"

    @BeforeEach
    fun setUp() {
        fresh = FreshDb.create()
        val key = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        fresh.db.jdbi.useHandle<Exception> { h ->
            for ((u, r) in listOf("supportz1" to "SUPPORT", "analystz1" to "ANALYST"))
                h.createUpdate("INSERT INTO app.app_user (username, full_name, role, password_hash, must_change_password) VALUES (:u, 'Test', :r, :p, false)")
                    .bind("u", u).bind("r", r).bind("p", PasswordHasher().hash(password)).execute()
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.auth.mfa_required_roles', 'global', 0, '[]'::jsonb, now() - interval '1 day', max(config_version), 'test: no TOTP step in this test' FROM app.cfg_version")
        }
        wiring = Wiring.production(Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to key.absolutePath)), AronClock.SYSTEM)
    }

    @AfterEach
    fun tearDown() { wiring.securityStore?.close(); wiring.database?.close(); fresh.close() }

    private suspend fun ApplicationTestBuilder.token(user: String): String {
        val r = client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$password","client":"web"}""") }
        val b = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals("ok", b["status"]!!.jsonPrimitive.content, b.toString())
        return b["access_token"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.status(method: String, path: String, token: String): HttpStatusCode =
        if (method == "GET") client.get(path) { bearerAuth(token) }.status
        else client.post(path) { bearerAuth(token); contentType(ContentType.Application.Json); setBody("{}") }.status

    @Test
    fun supportKeepsItsOwnRoutesAndLosesBusinessReports() = testApplication {
        application { aronApi(wiring) }
        val support = token("supportz1"); val analyst = token("analystz1")
        val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Dhaka"))
        val reads = listOf("/v1/admin/users", "/v1/admin/devices", "/v1/admin/device-otps", "/v1/admin/enrolment-tokens", "/v1/risk-signals", "/v1/tutorials", "/v1/dashboards/sync-health?business_date=$today", "/v1/report-exports")
        for (path in reads) assertEquals(HttpStatusCode.OK, status("GET", path, support), "SUPPORT GET $path")
        val writes = listOf("/v1/admin/enrolment-tokens", "/v1/admin/device-otps", "/v1/admin/entry-unlocks")
        for (path in writes) {
            val s = status("POST", path, support)
            assertTrue(s != HttpStatusCode.Forbidden && s != HttpStatusCode.Unauthorized && s.value < 500, "SUPPORT POST $path answered $s")
            assertEquals(HttpStatusCode.Forbidden, status("POST", path, analyst), "ANALYST POST $path (negative control)")
        }
        // What AZ-2 removes.
        assertEquals(HttpStatusCode.Forbidden, status("POST", "/v1/reports/route-memo/query", support))
        assertEquals(HttpStatusCode.Forbidden, status("GET", "/v1/dashboards/summary?business_date=$today", support))
    }
}
