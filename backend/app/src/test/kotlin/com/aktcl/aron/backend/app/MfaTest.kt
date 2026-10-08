package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.auth.Totp
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Settings
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
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
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F-WEB-043 through the production wiring: POST /v1/auth/mfa/enrol and POST /v1/auth/mfa/verify. An MFA role (ADMIN)
 * logs in to `mfa_required`; with only the mfa_token it enrols (TOTP secret URI and ten recovery codes), the first TOTP
 * code confirms the enrolment and completes the login (amr pwd+mfa, refresh token only in Set-Cookie); a code is never
 * accepted twice; a recovery code works only once and only after confirmation; a confirmed enrolment cannot be
 * replaced (409); wrong codes lock the second step; an admin reset (scope_version bump) spends outstanding mfa_tokens.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MfaTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val now = AtomicReference(Instant.parse("2027-01-03T04:00:00Z"))
    private val clock = AronClock { now.get() }
    private val hasher = PasswordHasher()
    private val seq = AtomicInteger(0)
    private val password = "Admin-pass-00001"

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val key = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        // BC-89: the column docs/requests/backend-core-refresh-family-amr.md asks for (no-op once the db lane ships it).
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("ALTER TABLE app.refresh_family ADD COLUMN IF NOT EXISTS amr text[] NOT NULL DEFAULT '{pwd}' CHECK (amr <@ '{pwd,mfa}'::text[] AND 'pwd' = ANY (amr))") }
        wiring = Wiring.production(Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to key.absolutePath)), clock)
    }

    @AfterAll
    fun tearDown() { wiring.securityStore?.close(); wiring.database?.close(); fresh.close() }

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun code(r: HttpResponse, body: String) = json(body)["code"]!!.jsonPrimitive.content

    private fun user(role: String): Pair<Long, String> {
        val name = role.lowercase() + "m" + seq.incrementAndGet()
        val id = fresh.db.jdbi.withHandle<Long, Exception> { h ->
            h.createQuery("INSERT INTO app.app_user (username, full_name, role, password_hash, must_change_password) VALUES (:u, 'Test', :r, :p, false) RETURNING id")
                .bind("u", name).bind("r", role).bind("p", hasher.hash(password)).mapTo(Long::class.java).one()
        }
        return id to name
    }

    private suspend fun HttpClient.mfaToken(name: String): String {
        val r = post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"$name","password":"$password","client":"web"}""") }
        val b = json(r.bodyAsText())
        assertEquals("mfa_required", b["status"]!!.jsonPrimitive.content, b.toString())
        return b["mfa_token"]!!.jsonPrimitive.content
    }

    private suspend fun HttpClient.verify(token: String, c: String): HttpResponse = post("/v1/auth/mfa/verify") {
        contentType(ContentType.Application.Json); setBody("""{"mfa_token":"$token","code":"$c"}""")
    }

    private suspend fun HttpClient.enrol(token: String): HttpResponse = post("/v1/auth/mfa/enrol") { bearerAuth(token) }

    private fun secretOf(uri: String): ByteArray {
        val b32 = Regex("secret=([A-Z2-7]+)").find(uri)!!.groupValues[1]
        val a = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0; var bits = 0
        val out = java.io.ByteArrayOutputStream()
        for (ch in b32) { buffer = (buffer shl 5) or a.indexOf(ch); bits += 5; if (bits >= 8) { out.write((buffer shr (bits - 8)) and 0xff); bits -= 8 } }
        return out.toByteArray()
    }

    private fun claims(jwt: String): JsonObject = json(String(Base64.getUrlDecoder().decode(jwt.split('.')[1])))

    @Test
    fun anAdminEnrolsFromTheLoginConfirmsWithTheFirstCodeAndNeverReusesACode() = testApplication {
        application { aronApi(wiring) }
        val (id, name) = user("ADMIN")
        val t1 = client.mfaToken(name)
        client.verify(t1, "123456").let { assertEquals(HttpStatusCode.Unauthorized, it.status); assertEquals("ERR_AUTH_MFA_INVALID", code(it, it.bodyAsText())) }

        val e = client.enrol(t1)
        assertEquals(HttpStatusCode.OK, e.status, e.bodyAsText())
        assertEquals("no-store", e.headers["Cache-Control"])
        val enrolment = json(e.bodyAsText())
        val uri = enrolment["otpauth_uri"]!!.jsonPrimitive.content
        assertTrue(uri.startsWith("otpauth://totp/Aron%3A$name?secret=") && "issuer=Aron" in uri, uri)
        val codes = enrolment["recovery_codes"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(10, codes.toSet().size)
        assertTrue(codes.all { Regex("^[A-Z0-9]{4}-[A-Z0-9]{4}$").matches(it) })
        val stored = fresh.db.jdbi.withHandle<String, Exception> { h -> h.createQuery("SELECT array_to_string(recovery_code_hashes, ',') || '|' || encode(secret_cipher, 'hex') FROM app.mfa_secret WHERE user_id = :u").bind("u", id).mapTo(String::class.java).one() }
        assertTrue(codes.none { it in stored } && Totp.base32(secretOf(uri)).lowercase() !in stored, "only sealed and keyed values are stored")

        val secret = secretOf(uri)
        client.verify(t1, codes[0]).let { assertEquals("ERR_AUTH_MFA_INVALID", code(it, it.bodyAsText()), "no recovery code before confirmation") }
        val good = Totp.code(secret, Totp.step(now.get()))
        val ok = client.verify(t1, good)
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        val body = json(ok.bodyAsText())
        assertEquals("ok", body["status"]!!.jsonPrimitive.content)
        assertTrue(body["refresh_token"] is kotlinx.serialization.json.JsonNull, "web refresh token only in the cookie")
        assertTrue(ok.headers.getAll("Set-Cookie")!!.any { it.startsWith("aron_rt=") })
        val access = body["access_token"]!!.jsonPrimitive.content
        assertEquals(listOf("pwd", "mfa"), claims(access)["amr"]!!.jsonArray.map { it.jsonPrimitive.content })
        // BC-89: a refresh of the family keeps the login's amr.
        val rt = ok.headers.getAll("Set-Cookie")!!.single { it.startsWith("aron_rt=") }.substringAfter("aron_rt=").substringBefore(';')
        val refreshed = client.post("/v1/auth/refresh") { contentType(ContentType.Application.Json); header("Cookie", "aron_rt=$rt"); setBody("""{"grant":"full"}""") }
        assertEquals(HttpStatusCode.OK, refreshed.status, refreshed.bodyAsText())
        assertEquals(listOf("pwd", "mfa"), claims(json(refreshed.bodyAsText())["access_token"]!!.jsonPrimitive.content)["amr"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("{pwd,mfa}", fresh.db.jdbi.withHandle<String, Exception> { h -> h.createQuery("SELECT amr::text FROM app.refresh_family WHERE user_id = :u ORDER BY id DESC LIMIT 1").bind("u", id).mapTo(String::class.java).one() })
        client.verify(client.mfaToken(name), good).let { assertEquals("ERR_AUTH_MFA_INVALID", code(it, it.bodyAsText()), "a used code is never accepted again") }

        val me = client.get("/v1/me") { bearerAuth(access) }
        assertTrue(json(me.bodyAsText())["mfa_enabled"]!!.jsonPrimitive.boolean)
        client.enrol(client.mfaToken(name)).let { assertEquals(HttpStatusCode.Conflict, it.status, "a confirmed enrolment is not replaced from a login") }
        client.enrol(access).let { assertEquals(HttpStatusCode.Conflict, it.status, "nor from a full session") }

        // The next step's code, then a recovery code once.
        now.set(now.get().plusSeconds(30))
        assertEquals(HttpStatusCode.OK, client.verify(client.mfaToken(name), Totp.code(secret, Totp.step(now.get()))).status)
        assertEquals(HttpStatusCode.OK, client.verify(client.mfaToken(name), codes[3].lowercase()).status, "a recovery code is read case-insensitively")
        client.verify(client.mfaToken(name), codes[3]).let { assertEquals("ERR_AUTH_MFA_INVALID", code(it, it.bodyAsText()), "a recovery code is single use") }

        val audit = fresh.db.jdbi.withHandle<List<String>, Exception> { h -> h.createQuery("SELECT action FROM app.audit_log WHERE entity = 'mfa_secret' AND entity_id = :u ORDER BY id").bind("u", id.toString()).mapTo(String::class.java).list() }
        assertEquals(listOf("mfa.enrol", "mfa.confirm", "mfa.recovery_spent"), audit)

        // An admin reset bumps scope_version: an mfa_token minted before it is spent.
        val stale = client.mfaToken(name)
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.app_user SET scope_version = scope_version + 1 WHERE id = ?", id) }
        now.set(now.get().plusSeconds(30))
        client.verify(stale, Totp.code(secret, Totp.step(now.get()))).let { assertEquals("ERR_AUTH_MFA_INVALID", code(it, it.bodyAsText())) }
    }

    /** BC-89 (checker): a web session opened without TOTP does not refresh once its user is promoted into an MFA role. */
    @Test
    fun aPromotionIntoAnMfaRoleEndsTheRefreshOfAPasswordOnlySession() = testApplication {
        application { aronApi(wiring) }
        val (id, name) = user("TSO")
        val login = client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"$name","password":"$password","client":"web"}""") }
        assertEquals("ok", json(login.bodyAsText())["status"]!!.jsonPrimitive.content, login.bodyAsText())
        var rt = login.headers.getAll("Set-Cookie")!!.single { it.startsWith("aron_rt=") }.substringAfter("aron_rt=").substringBefore(';')
        suspend fun refresh() = client.post("/v1/auth/refresh") { contentType(ContentType.Application.Json); header("Cookie", "aron_rt=$rt"); setBody("""{"grant":"full"}""") }
        val r1 = refresh()
        assertEquals(HttpStatusCode.OK, r1.status, r1.bodyAsText())
        assertEquals(listOf("pwd"), claims(json(r1.bodyAsText())["access_token"]!!.jsonPrimitive.content)["amr"]!!.jsonArray.map { it.jsonPrimitive.content })
        rt = r1.headers.getAll("Set-Cookie")!!.single { it.startsWith("aron_rt=") }.substringAfter("aron_rt=").substringBefore(';')

        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.app_user SET role = 'ADMIN', scope_version = scope_version + 1 WHERE id = ?", id) }
        val r2 = refresh()
        assertEquals(HttpStatusCode.Unauthorized, r2.status, r2.bodyAsText())
        assertEquals("ERR_AUTH_REFRESH_INVALID", code(r2, r2.bodyAsText()))
    }

    @Test
    fun wrongCodesLockTheSecondStepAndOtherTokensAreRefused() = testApplication {
        application { aronApi(wiring) }
        val (_, name) = user("SUPPORT")
        val t = client.mfaToken(name)
        val secret = secretOf(json(client.enrol(t).bodyAsText())["otpauth_uri"]!!.jsonPrimitive.content)
        // Malformed bodies are 400; an access token or junk in place of the mfa_token is 401.
        client.verify(t, "12345").let { assertEquals(HttpStatusCode.BadRequest, it.status) }
        client.verify("not-a-jwt", "123456").let { assertEquals(HttpStatusCode.Unauthorized, it.status) }
        val (_, plain) = user("DMO")
        val access = json(client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"$plain","password":"$password","client":"web"}""") }.bodyAsText())["access_token"]!!.jsonPrimitive.content
        client.verify(access, "123456").let { assertEquals("ERR_AUTH_MFA_INVALID", code(it, it.bodyAsText()), "an access token is not an mfa_token") }
        // Other roles cannot enrol: their login never reaches the TOTP step that confirms it.
        client.enrol(access).let { assertEquals(HttpStatusCode.Forbidden, it.status, it.bodyAsText()) }

        val wrong = (Totp.code(secret, Totp.step(now.get())).toInt() + 500_000).rem(1_000_000).toString().padStart(6, '0')
        repeat(10) { assertEquals(HttpStatusCode.Unauthorized, client.verify(t, wrong).status) }
        val locked = client.verify(t, Totp.code(secret, Totp.step(now.get())))
        assertEquals(HttpStatusCode.Forbidden, locked.status)
        assertEquals("ERR_AUTH_ACCOUNT_LOCKED", code(locked, locked.bodyAsText()))
        assertTrue(locked.headers["Retry-After"] != null)
        now.set(now.get().plus(Duration.ofMinutes(16)))
        assertEquals(HttpStatusCode.OK, client.verify(client.mfaToken(name), Totp.code(secret, Totp.step(now.get()))).status, "usable again after the lock")

        // A secret no key of the ring opens is 503 and never counts towards the lockout.
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.mfa_secret SET secret_cipher = decode(repeat('00', 40), 'hex') WHERE user_id = (SELECT id FROM app.app_user WHERE username = ?)", name) }
        now.set(now.get().plusSeconds(30))
        val tk = client.mfaToken(name)
        repeat(11) { assertEquals(HttpStatusCode.ServiceUnavailable, client.verify(tk, Totp.code(secret, Totp.step(now.get()))).status) }

        // V0070 kinds in app.security_event (the sink writes off the request path, so wait for it).
        val uid = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT id FROM app.app_user WHERE username = :n").bind("n", name).mapTo(Long::class.java).one() }
        fun kinds() = fresh.db.jdbi.withHandle<List<String>, Exception> { h ->
            h.createQuery("SELECT kind || ':' || coalesce(detail->>'reason', '') FROM app.security_event WHERE user_id = :u ORDER BY id").bind("u", uid).mapTo(String::class.java).list()
        }
        val deadline = System.currentTimeMillis() + 15_000
        while (kinds().count { it == "mfa_verify_failure:unreadable" } < 11 && System.currentTimeMillis() < deadline) Thread.sleep(200)
        val k = kinds()
        assertEquals(1, k.count { it.startsWith("mfa_enrol:") }, k.toString())
        assertEquals(10, k.count { it == "mfa_verify_failure:wrong_totp" }, "every wrong code is one event, never deduped: $k")
        assertEquals(11, k.count { it == "mfa_verify_failure:unreadable" }, k.toString())
        assertTrue(k.none { it.startsWith("login_failure") }, "MFA failures are not login failures: $k")
        assertTrue(k.any { it.startsWith("lockout") })
    }
}
