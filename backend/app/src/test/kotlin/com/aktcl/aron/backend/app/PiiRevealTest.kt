package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.auth.Totp
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Settings
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D-AUTHZ-PII (docs/21 s4.5, lead D-AUTHZ-QA-Q3) through the production wiring: POST /v1/auth/reauth and the guard's
 * reveal check. No grant: 403 before any hashing. Grant without step-up: masked. Wrong password or code: one 401,
 * counted to a lockout. Grant + step-up: the phone is served, GET /v1/me shows the window, and every unmasked request
 * writes an audit row that carries no personal value. A revoke or the end of the window masks again. ANALYST and
 * SUPPORT audit-log masking never changes with a reveal; ADMIN sees only the named personal members.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PiiRevealTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val now = AtomicReference(Instant.parse("2027-01-03T04:00:00Z"))
    private val clock = AronClock { now.get() }
    private val hasher = PasswordHasher()
    private val seq = AtomicInteger(0)
    private val password = "Admin-pass-00001"
    private val phone = "01712345678"

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val key = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("ALTER TABLE app.refresh_family ADD COLUMN IF NOT EXISTS amr text[] NOT NULL DEFAULT '{pwd}' CHECK (amr <@ '{pwd,mfa}'::text[] AND 'pwd' = ANY (amr))") }
        wiring = Wiring.production(Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to key.absolutePath)), clock)
    }

    @AfterAll
    fun tearDown() { wiring.securityStore?.close(); wiring.database?.close(); fresh.close() }

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun code(body: String) = json(body)["code"]!!.jsonPrimitive.content
    private fun claims(jwt: String): JsonObject = json(String(Base64.getUrlDecoder().decode(jwt.split('.')[1])))

    private fun user(role: String, withPhone: String? = null): Pair<Long, String> {
        val name = role.lowercase() + "p" + seq.incrementAndGet()
        val id = fresh.db.jdbi.withHandle<Long, Exception> { h ->
            h.createQuery("INSERT INTO app.app_user (username, full_name, role, password_hash, must_change_password, phone) VALUES (:u, 'Test', :r, :p, false, :ph) RETURNING id")
                .bind("u", name).bind("r", role).bind("p", hasher.hash(password)).bind("ph", withPhone).mapTo(Long::class.java).one()
                .also { if (role == "ADMIN") com.aktcl.aron.backend.platform.AdminGrantsFixture.grantAllModules(h, name) }
        }
        return id to name
    }

    private fun secretOf(uri: String): ByteArray {
        val b32 = Regex("secret=([A-Z2-7]+)").find(uri)!!.groupValues[1]
        val a = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0; var bits = 0
        val out = java.io.ByteArrayOutputStream()
        for (ch in b32) { buffer = (buffer shl 5) or a.indexOf(ch); bits += 5; if (bits >= 8) { out.write((buffer shr (bits - 8)) and 0xff); bits -= 8 } }
        return out.toByteArray()
    }

    private suspend fun HttpClient.login(name: String): JsonObject =
        json(post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"$name","password":"$password","client":"web"}""") }.bodyAsText())

    /** An MFA role's full web session (enrol from the login, confirm with the first code); returns (access, secret). */
    private suspend fun HttpClient.mfaSession(name: String): Pair<String, ByteArray> {
        val t = login(name)["mfa_token"]!!.jsonPrimitive.content
        val secret = secretOf(json(post("/v1/auth/mfa/enrol") { bearerAuth(t) }.bodyAsText())["otpauth_uri"]!!.jsonPrimitive.content)
        val ok = post("/v1/auth/mfa/verify") { contentType(ContentType.Application.Json); setBody("""{"mfa_token":"$t","code":"${Totp.code(secret, Totp.step(now.get()))}"}""") }
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        return json(ok.bodyAsText())["access_token"]!!.jsonPrimitive.content to secret
    }

    private suspend fun HttpClient.reauth(access: String, body: String): HttpResponse =
        post("/v1/auth/reauth") { bearerAuth(access); contentType(ContentType.Application.Json); setBody(body) }

    private fun nextCode(secret: ByteArray): String { now.set(now.get().plusSeconds(30)); return Totp.code(secret, Totp.step(now.get())) }

    private fun revealRows(userId: Long): List<String> = fresh.db.jdbi.withHandle<List<String>, Exception> { h ->
        h.createQuery("SELECT action || ' ' || coalesce(after->>'path', '') || ' ' || after::text FROM app.audit_log WHERE entity = 'pii_reveal' AND actor_user_id = :u ORDER BY id")
            .bind("u", userId).mapTo(String::class.java).list()
    }

    private fun grant(role: String, on: Boolean) = fresh.db.jdbi.useHandle<Exception> { h ->
        if (on) h.execute("INSERT INTO app.role_grant_map (role, permission) VALUES (?, 'pii.reveal') ON CONFLICT DO NOTHING", role)
        else h.execute("DELETE FROM app.role_grant_map WHERE role = ? AND permission = 'pii.reveal'", role)
    }

    @Test
    fun v0086SeedsTheGrantForAdminRolesOnlyAndRegistersTheKey() {
        val roles = fresh.db.jdbi.withHandle<List<String>, Exception> { h -> h.createQuery("SELECT role FROM app.role_grant_map WHERE permission = 'pii.reveal' ORDER BY role").mapTo(String::class.java).list() }
        assertEquals(listOf("ADMIN", "SUPERADMIN"), roles)
        val k = fresh.db.jdbi.withHandle<String, Exception> { h -> h.createQuery("SELECT default_value::text || ' ' || bounds::text || ' ' || risk_class || ' ' || editor_permission FROM app.cfg_key WHERE key = 'cfg.pii.reauth_min'").mapTo(String::class.java).one() }
        assertEquals("""15 {"max": 15, "min": 1} 2 cfg.edit.security""", k)
    }

    @Test
    fun anAdminRevealsAfterAStepUpAndEveryUnmaskedReadIsAudited() = testApplication {
        application { aronApi(wiring) }
        val (_, subject) = user("DMO", withPhone = phone)
        val subjectId = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT id FROM app.app_user WHERE username = :n").bind("n", subject).mapTo(Long::class.java).one() }
        val (adminId, admin) = user("ADMIN")
        val (access, secret) = client.mfaSession(admin)
        suspend fun phoneOf(token: String) = client.get("/v1/admin/users/$subjectId") { bearerAuth(token) }.let {
            assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()); json(it.bodyAsText())["phone"]
        }
        suspend fun me(token: String) = json(client.get("/v1/me") { bearerAuth(token) }.bodyAsText())

        // Grant without a step-up: masked, and the permission is visible on /v1/me.
        assertEquals(JsonNull, phoneOf(access))
        me(access).let { assertFalse(it["pii"]!!.jsonPrimitive.boolean); assertEquals(JsonNull, it["pii_until"]); assertTrue("pii.reveal" in it["permissions"]!!.jsonArray.map { p -> p.jsonPrimitive.content }) }

        // Wrong answers: one 401, a missing code is 400, a malformed body 400.
        client.reauth(access, """{"password":"wrong-password","code":"${nextCode(secret)}"}""").let { assertEquals(HttpStatusCode.Unauthorized, it.status); assertEquals("ERR_AUTH_INVALID_CREDENTIALS", code(it.bodyAsText())) }
        client.reauth(access, """{"password":"$password","code":"000000"}""").let { assertEquals(HttpStatusCode.Unauthorized, it.status); assertEquals("ERR_AUTH_INVALID_CREDENTIALS", code(it.bodyAsText())) }
        client.reauth(access, """{"password":"$password"}""").let { assertEquals(HttpStatusCode.BadRequest, it.status, it.bodyAsText()) }
        client.reauth(access, """{"password":"$password","code":"12ab56"}""").let { assertEquals(HttpStatusCode.BadRequest, it.status, it.bodyAsText()) }
        assertEquals(emptyList(), revealRows(adminId), "a refused step-up opens nothing")

        val ok = client.reauth(access, """{"password":"$password","code":"${nextCode(secret)}"}""")
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        assertEquals("no-store", ok.headers["Cache-Control"])
        val body = json(ok.bodyAsText())
        val revealed = body["access_token"]!!.jsonPrimitive.content
        val until = Instant.parse(body["pii_until"]!!.jsonPrimitive.content)
        assertEquals(now.get().plusSeconds(15 * 60).epochSecond, until.epochSecond)
        claims(revealed).let {
            assertEquals(true, it["pii"]!!.jsonPrimitive.boolean); assertEquals(until.epochSecond, it["pii_until"]!!.jsonPrimitive.content.toLong())
            assertEquals(listOf("pwd", "mfa"), it["amr"]!!.jsonArray.map { a -> a.jsonPrimitive.content })
        }
        // The same TOTP step is never accepted twice.
        client.reauth(access, """{"password":"$password","code":"${Totp.code(secret, Totp.step(now.get()))}"}""").let { assertEquals(HttpStatusCode.Unauthorized, it.status) }

        me(revealed).let { assertTrue(it["pii"]!!.jsonPrimitive.boolean); assertEquals(until, Instant.parse(it["pii_until"]!!.jsonPrimitive.content)) }
        fun budget() = fresh.db.jdbi.withHandle<Int, Exception> { h -> h.createQuery("SELECT coalesce(sum(rows_read), 0)::int FROM app.pii_read_budget WHERE user_id = :u").bind("u", adminId).mapTo(Int::class.java).one() }
        val before = budget()
        assertEquals(phone, phoneOf(revealed)!!.jsonPrimitive.content)
        assertEquals(before + 1, budget(), "an unmasked read counts against cfg.pii.list_rows_per_hour (checker B2)")
        assertEquals(JsonNull, phoneOf(access), "the old token still reveals nothing")
        assertEquals(before + 1, budget(), "a masked read costs nothing")
        val rows = revealRows(adminId)
        assertEquals(2, rows.size, rows.toString())
        assertTrue(rows[0].startsWith("step_up "), rows.toString())
        assertTrue(rows[1].startsWith("read /v1/admin/users/$subjectId "), rows.toString())
        assertTrue(rows.none { phone in it }, "no personal value in a reveal row: $rows")
        assertEquals(2, rows.size, "GET /v1/me writes no reveal row")

        // A revoke holds within the gate cache, the open window included.
        grant("ADMIN", false)
        now.set(now.get().plusSeconds(11))
        try {
            assertEquals(JsonNull, phoneOf(revealed))
            assertFalse(me(revealed)["pii"]!!.jsonPrimitive.boolean)
            client.reauth(access, """{"password":"$password","code":"${nextCode(secret)}"}""").let { assertEquals(HttpStatusCode.Forbidden, it.status, it.bodyAsText()) }
        } finally { grant("ADMIN", true) }
        now.set(now.get().plusSeconds(11))
        assertEquals(phone, phoneOf(revealed)!!.jsonPrimitive.content)

        // The window ends at pii_until (cfg.pii.reauth_min = 1 for the next step-up).
        fresh.db.jdbi.useHandle<Exception> { h ->
            // Closed again 10 minutes on, so the other tests of the class keep the default (checker N6).
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, effective_to, config_version, reason) SELECT 'cfg.pii.reauth_min', 'global', 0, '1'::jsonb, TIMESTAMPTZ '2026-01-01T00:00:00Z', TIMESTAMPTZ '${now.get().plusSeconds(600)}', max(config_version), 'test' FROM app.cfg_version")
        }
        now.set(now.get().plusSeconds(120))
        val short = json(client.reauth(access, """{"password":"$password","code":"${nextCode(secret)}"}""").bodyAsText())
        val shortToken = short["access_token"]!!.jsonPrimitive.content
        assertEquals(now.get().plusSeconds(60).epochSecond, Instant.parse(short["pii_until"]!!.jsonPrimitive.content).epochSecond)
        assertEquals(phone, phoneOf(shortToken)!!.jsonPrimitive.content)
        now.set(now.get().plusSeconds(61))
        assertEquals(JsonNull, phoneOf(shortToken), "masked once the window closed, before the token expired")
        assertFalse(me(shortToken)["pii"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun wrongStepUpsLockTheReauthOnly() = testApplication {
        application { aronApi(wiring) }
        val (_, admin) = user("SUPERADMIN")
        val (access, secret) = client.mfaSession(admin)
        repeat(10) { client.reauth(access, """{"password":"nope-nope-nope","code":"${nextCode(secret)}"}""").let { assertEquals(HttpStatusCode.Unauthorized, it.status) } }
        val locked = client.reauth(access, """{"password":"$password","code":"${nextCode(secret)}"}""")
        assertEquals(HttpStatusCode.Forbidden, locked.status)
        assertEquals("ERR_AUTH_ACCOUNT_LOCKED", code(locked.bodyAsText()))
        assertEquals(HttpStatusCode.OK, client.get("/v1/me") { bearerAuth(access) }.status, "the session itself is untouched")
    }

    @Test
    fun aRoleWithoutTheGrantIsRefusedAndAnalystAuditMaskingNeverChanges() = testApplication {
        application { aronApi(wiring) }
        // An outlet image with personal members in the audit log.
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute(
                "INSERT INTO app.audit_log (actor_user_id, actor_username, actor_role, via, entity, entity_id, action, before, after) " +
                    "VALUES (NULL, NULL, NULL, 'job', 'outlet', 'pii-reveal-1', 'update', '{\"owner_name\":\"Rahim\",\"owner_phone\":\"$phone\",\"status\":\"active\"}'::jsonb, '{\"owner_name\":\"Karim\",\"owner_phone\":\"01811111111\",\"status\":\"closed\"}'::jsonb)",
            )
        }
        suspend fun auditAfter(token: String): JsonObject {
            val r = client.get("/v1/admin/audit?entity=outlet&entity_id=pii-reveal-1") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            return json(r.bodyAsText())["items"]!!.jsonArray.single().jsonObject["after"]!!.jsonObject
        }

        // ANALYST (no MFA role): no grant, so 403 before the password is looked at.
        val (analystId, analyst) = user("ANALYST")
        val a = client.login(analyst)["access_token"]!!.jsonPrimitive.content
        client.reauth(a, """{"password":"wrong"}""").let { assertEquals(HttpStatusCode.Forbidden, it.status); assertEquals("ERR_FORBIDDEN", code(it.bodyAsText())) }
        // Even granted and stepped up, the ANALYST's audit view stays default-deny.
        grant("ANALYST", true)
        try {
            now.set(now.get().plusSeconds(11))
            val r = client.reauth(a, """{"password":"$password"}""")
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            val after = auditAfter(json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content)
            assertEquals("[masked]", after["owner_name"]!!.jsonPrimitive.content)
            assertEquals("[masked]", after["owner_phone"]!!.jsonPrimitive.content)
            assertTrue(revealRows(analystId).any { it.startsWith("read /v1/admin/audit ") })
        } finally { grant("ANALYST", false) }
        now.set(now.get().plusSeconds(11))

        // ADMIN: masked without a reveal; with one, only the named personal member (owner_name), never one that merely looks personal.
        val (adminId, admin) = user("ADMIN")
        val (access, secret) = client.mfaSession(admin)
        fun budget() = fresh.db.jdbi.withHandle<Int, Exception> { h -> h.createQuery("SELECT coalesce(sum(rows_read), 0)::int FROM app.pii_read_budget WHERE user_id = :u").bind("u", adminId).mapTo(Int::class.java).one() }
        auditAfter(access).let { assertEquals("[masked]", it["owner_name"]!!.jsonPrimitive.content); assertEquals("closed", it["status"]!!.jsonPrimitive.content) }
        val revealed = json(client.reauth(access, """{"password":"$password","code":"${nextCode(secret)}"}""").bodyAsText())["access_token"]!!.jsonPrimitive.content
        assertEquals(0, budget(), "a masked audit page costs nothing")
        auditAfter(revealed).let { assertEquals("Karim", it["owner_name"]!!.jsonPrimitive.content); assertEquals("[masked]", it["owner_phone"]!!.jsonPrimitive.content) }
        assertEquals(1, budget(), "a revealed audit page counts its rows (checker B4)")
    }
}
