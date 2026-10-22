package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Settings
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * AZ-1: `cfg.auth.mfa_required_roles` is enforced per request. An ADMIN signs in with the password while the list is
 * empty (amr ["pwd"]); once the list names ADMIN again, that still-valid token is refused on every write and on reads of
 * config and enrolment tokens (401 ERR_AUTH_MFA_INVALID) but keeps plain reads and can still log out.
 */
class MfaPerRequestTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val now = AtomicReference(Instant.now())   // wall-clock-ok: the config rows are dated by the database's now()
    private val clock = AronClock { now.get() }
    private val password = "Admin-pass-00001"

    @BeforeEach
    fun setUp() {
        fresh = FreshDb.create()
        val key = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate("INSERT INTO app.app_user (username, full_name, role, password_hash, must_change_password) VALUES ('adminz1', 'Test', 'ADMIN', :p, false)")
                .bind("p", PasswordHasher().hash(password)).execute()
            com.aktcl.aron.backend.platform.AdminGrantsFixture.grantAllModules(h, "adminz1")
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.auth.mfa_required_roles', 'global', 0, '[]'::jsonb, now() - interval '1 day', max(config_version), 'test: MFA off at first' FROM app.cfg_version")
        }
        wiring = Wiring.production(Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to key.absolutePath)), clock)
    }

    @AfterEach
    fun tearDown() { wiring.securityStore?.close(); wiring.database?.close(); fresh.close() }

    private fun code(r: String) = Json.parseToJsonElement(r).jsonObject["code"]?.jsonPrimitive?.content

    private fun turnMfaOn() = fresh.db.jdbi.useHandle<Exception> { h ->
        h.execute("UPDATE app.cfg_value SET effective_to = now() WHERE key = 'cfg.auth.mfa_required_roles' AND effective_to IS NULL")
        h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.auth.mfa_required_roles', 'global', 0, '[\"ADMIN\",\"SUPERADMIN\",\"SUPPORT\"]'::jsonb, now(), max(config_version), 'test: MFA back on' FROM app.cfg_version")
    }

    private suspend fun ApplicationTestBuilder.call(method: String, path: String, token: String, body: String = "{}"): HttpResponse = when (method) {
        "GET" -> client.get(path) { bearerAuth(token) }
        else -> client.post(path) { bearerAuth(token); contentType(ContentType.Application.Json); setBody(body) }
    }

    @Test
    fun aPasswordOnlyAdminTokenLosesWritesAndConfigReadsOnceTheFlagTurnsOn() = testApplication {
        application { aronApi(wiring) }
        val login = client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"adminz1","password":"$password","client":"web"}""") }
        val b = Json.parseToJsonElement(login.bodyAsText()).jsonObject
        assertEquals("ok", b["status"]!!.jsonPrimitive.content, b.toString())
        val token = b["access_token"]!!.jsonPrimitive.content
        assertEquals(listOf("pwd"), Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(token.split('.')[1]))).jsonObject["amr"]!!.jsonArray.map { it.jsonPrimitive.content })

        val gated = listOf(
            "POST" to "/v1/admin/config/changes", "POST" to "/v1/admin/enrolment-tokens", "POST" to "/v1/admin/dues-adjustments",
            "POST" to "/v1/admin/prices/preview", "GET" to "/v1/admin/config/values", "GET" to "/v1/admin/config/changes", "GET" to "/v1/admin/enrolment-tokens",
            "GET" to "/v1/admin/%63onfig/values", "GET" to "/v1/admin/enrolment%2Dtokens", "GET" to "/v1/admin/audit", "GET" to "/v1/admin/%61udit",
        )
        // Before: the token is judged on its merits (validation, not found, ...), never refused for MFA.
        for ((m, path) in gated) assertNotEquals("ERR_AUTH_MFA_INVALID", code(call(m, path, token).bodyAsText().ifBlank { "{}" }), "$m $path before")

        turnMfaOn()
        now.set(now.get().plusSeconds(31))   // past the 30 s config cache; the token (15 min) is still valid

        for ((m, path) in gated) {
            val r = call(m, path, token)
            assertEquals(HttpStatusCode.Unauthorized, r.status, "$m $path")
            assertEquals("ERR_AUTH_MFA_INVALID", code(r.bodyAsText()), "$m $path")
        }
        // Plain reads stay; logout stays so the user can sign in again with the code.
        assertEquals(HttpStatusCode.OK, call("GET", "/v1/reports", token).status)
        assertNotEquals(HttpStatusCode.Unauthorized, call("POST", "/v1/auth/logout", token).status)
    }
}
