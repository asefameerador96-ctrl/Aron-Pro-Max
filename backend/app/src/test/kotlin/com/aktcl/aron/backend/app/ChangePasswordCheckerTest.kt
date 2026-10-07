package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Independent checker of F-API-004, F-SYS-004 and contract v1.2 R15/R18 (same production wiring as ChangePasswordTest). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChangePasswordCheckerTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val now = AtomicReference(Instant.parse("2027-01-03T04:00:00Z"))
    private val clock = AronClock { now.get() }
    private val hasher = PasswordHasher()
    private val seq = AtomicInteger(0)
    private val temp = "Temp-pass-0001"

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val key = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        wiring = Wiring.production(Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to key.absolutePath)), clock)
    }

    @AfterAll
    fun tearDown() { wiring.database?.close(); fresh.close() }

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun count(sql: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }

    private fun user(role: String, mustChange: Boolean = true, password: String = temp, changedAgo: Duration = Duration.ofDays(3)): Pair<Long, String> {
        val name = "ck" + role.lowercase() + seq.incrementAndGet()
        val id = fresh.db.jdbi.withHandle<Long, Exception> { h ->
            h.createQuery("INSERT INTO app.app_user (username, full_name, role, password_hash, must_change_password, password_changed_at) VALUES (:u, 'Test', :r, :p, :m, :at) RETURNING id")
                .bind("u", name).bind("r", role).bind("p", hasher.hash(password)).bind("m", mustChange)
                .bind("at", java.time.OffsetDateTime.ofInstant(now.get().minus(changedAgo), java.time.ZoneOffset.UTC))
                .mapTo(Long::class.java).one()
        }
        return id to name
    }

    /** An enrolled SR phone with an active binding of [userId] (ordinal 0). */
    private fun boundPhone(userId: Long): String {
        val uuid = UUID.randomUUID().toString()
        fresh.db.jdbi.useHandle<Exception> { h ->
            val did = h.createQuery(
                """INSERT INTO app.device (device_uuid, flavour, app_package, status, device_owner, lockdown_level, public_key_jwk, public_key_thumbprint, app_signing_cert_sha256)
                   VALUES (CAST(:u AS uuid), 'sr', 'com.aktcl.aron.sr', 'active', true, 'dev', '{"kty":"none"}'::jsonb, :t, decode(repeat('ab', 32), 'hex')) RETURNING id""",
            ).bind("u", uuid).bind("t", "thumb-$uuid").mapTo(Long::class.java).one()
            h.execute("INSERT INTO app.device_binding (device_id, user_id, bind_ordinal) VALUES ($did, $userId, 0)")
        }
        return uuid
    }

    private suspend fun HttpClient.login(name: String, password: String = temp, client: String = "web", device: String? = null): HttpResponse = post("/v1/auth/login") {
        contentType(ContentType.Application.Json)
        setBody("""{"username":"$name","password":"$password","client":"$client"${device?.let { ",\"device_uuid\":\"$it\"" } ?: ""}}""")
    }

    private suspend fun HttpClient.change(token: String, current: String, new: String, deviceUuid: String? = null): HttpResponse = post("/v1/auth/change-password") {
        bearerAuth(token); contentType(ContentType.Application.Json)
        deviceUuid?.let { header("X-Device-Id", it) }
        setBody("""{"current_password":"$current","new_password":"$new"}""")
    }

    private fun code(body: String): String? = runCatching { json(body)["code"]!!.jsonPrimitive.content }.getOrNull()
    private suspend fun HttpResponse.errs(): List<String> =
        json(bodyAsText())["errors"]?.jsonArray?.map { it.jsonObject["code"]!!.jsonPrimitive.content }.orEmpty()

    private fun rtCookie(r: HttpResponse): String = r.headers.getAll("Set-Cookie")!!.single { it.startsWith("aron_rt=") }.substringAfter("aron_rt=").substringBefore(';')

    // ---- token audience ----

    @Test
    fun pwchangeTokenIsRefusedBySyncBatchAndRefresh() = testApplication {
        application { aronApi(wiring) }
        val (_, name) = user("DMO")
        val pw = json(client.login(name).bodyAsText())["password_change_token"]!!.jsonPrimitive.content
        val sync = client.post("/v1/sync/batch") { bearerAuth(pw); contentType(ContentType.Application.Json); setBody("{}") }
        assertEquals(HttpStatusCode.Unauthorized, sync.status, sync.bodyAsText())
        val refresh = client.post("/v1/auth/refresh") { bearerAuth(pw); contentType(ContentType.Application.Json); setBody("""{"grant":"full"}""") }
        assertNotEquals(HttpStatusCode.OK, refresh.status)
    }

    @Test
    fun anMfaTokenIsNotAcceptedByChangePassword() = testApplication {
        application { aronApi(wiring) }
        val (_, name) = user("ADMIN", mustChange = false)
        val b = json(client.login(name).bodyAsText())
        assertEquals("mfa_required", b["status"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.Unauthorized, client.change(b["mfa_token"]!!.jsonPrimitive.content, temp, "Admin-Other-881").status)
    }

    // ---- TOTP / client ----

    @Test
    fun aTemporaryMfaRoleOnAPhoneClientGetsNoApiToken() = testApplication {
        application { aronApi(wiring) }
        val (id, name) = user("ADMIN")
        val phone = boundPhone(id)
        val r = client.login(name, client = "app_sr", device = phone)
        val b = json(r.bodyAsText())
        assertEquals(JsonNull, b["access_token"] ?: JsonNull, "a web role with a temporary password got an API token through client app_sr: $b")
        assertEquals(HttpStatusCode.Forbidden, r.status, "a phone app serves its own field role only")
        assertEquals("ERR_FORBIDDEN", code(r.bodyAsText()))
    }

    @Test
    fun anMfaRoleCannotSkipTotpThroughAPhoneClientAfterTheChange() = testApplication {
        application { aronApi(wiring) }
        val (id, name) = user("ADMIN")
        val phone = boundPhone(id)
        val first = json(client.login(name, client = "app_sr", device = phone).bodyAsText())
        val access = first["access_token"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
        if (access != null) assertEquals(HttpStatusCode.NoContent, client.change(access, temp, "Admin-Phone-7731", phone).status)
        val pw = if (access != null) "Admin-Phone-7731" else temp
        val second = json(client.login(name, pw, client = "app_sr", device = phone).bodyAsText())
        val tok = second["access_token"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
        if (tok != null) {
            val admin = client.get("/v1/admin/outlets") { bearerAuth(tok); header("X-Device-Id", phone) }
            println("CHECKER admin/outlets with the phone-client ADMIN token (no TOTP): ${admin.status}")
        }
        assertNotEquals("ok", second["status"]?.jsonPrimitive?.content, "ADMIN (an MFA role) got a full login with tokens and no TOTP: ${second["status"]}")
    }

    // ---- revocation ----

    /** docs/21 s2 (route table): "revokes every full-grant family of the user except the caller's". */
    @Test
    fun aWebCallersOwnSessionSurvivesItsOwnPasswordChange() = testApplication {
        application { aronApi(wiring) }
        val (_, name) = user("DMO", mustChange = false)
        val a = client.login(name)
        val rtA = rtCookie(a)
        val accessA = json(a.bodyAsText())["access_token"]!!.jsonPrimitive.content
        val rtB = rtCookie(client.login(name))
        // The BFF forwards its own aron_rt with the call; that family is the caller's and survives.
        val changed = client.post("/v1/auth/change-password") {
            bearerAuth(accessA); contentType(ContentType.Application.Json); header("Cookie", "aron_rt=$rtA")
            setBody("""{"current_password":"$temp","new_password":"Dmo-Settled-4411"}""")
        }
        assertEquals(HttpStatusCode.NoContent, changed.status, changed.bodyAsText())
        val refB = client.post("/v1/auth/refresh") { contentType(ContentType.Application.Json); header("Cookie", "aron_rt=$rtB"); setBody("""{"grant":"full"}""") }
        assertEquals("ERR_PASSWORD_CHANGED", code(refB.bodyAsText()), "the other web session is revoked")
        val refA = client.post("/v1/auth/refresh") { contentType(ContentType.Application.Json); header("Cookie", "aron_rt=$rtA"); setBody("""{"grant":"full"}""") }
        assertEquals(HttpStatusCode.OK, refA.status, "the caller's own web family was revoked: " + refA.bodyAsText())
    }

    // ---- lockout ----

    @Test
    fun wrongCurrentPasswordsLockTheChange() = testApplication {
        application { aronApi(wiring) }
        val (_, name) = user("DMO", mustChange = false)
        val access = json(client.login(name).bodyAsText())["access_token"]!!.jsonPrimitive.content
        val attempts = count("SELECT default_value::text::bigint FROM app.cfg_key WHERE key = 'cfg.auth.lockout_attempts'").toInt()
        repeat(attempts) { assertEquals(HttpStatusCode.Unauthorized, client.change(access, "Wrong-current-$it", "Dmo-New-Pass-77").status) }
        val r = client.change(access, temp, "Dmo-New-Pass-77")
        assertEquals("ERR_AUTH_ACCOUNT_LOCKED", code(r.bodyAsText()), r.bodyAsText())
    }

    // ---- policy edges ----

    @Test
    fun webExactlyTwelveIsAcceptedAndElevenIsNot() = testApplication {
        application { aronApi(wiring) }
        val (_, name) = user("DMO")
        val t = json(client.login(name).bodyAsText())["password_change_token"]!!.jsonPrimitive.content
        assertEquals(listOf("too_short"), client.change(t, temp, "Abcdefghij1").errs())
        assertEquals(listOf("invalid_value"), client.change(t, temp, "Abcdefghij12 ").errs())
        assertTrue("missing_upper" in client.change(t, temp, "আমারসোনারবাংলাabc1").errs(), "Bangla letters have no case")
        assertEquals(HttpStatusCode.OK, client.change(t, temp, "Abcdefghij12").status)
    }

    @Test
    fun fieldRoleDenyListCatchesProductNameAndUsername() = testApplication {
        application { aronApi(wiring) }
        val (_, name) = user("SR", mustChange = false)
        val access = json(client.login(name).bodyAsText())["access_token"]!!.jsonPrimitive.content
        assertEquals(listOf("too_common"), client.change(access, temp, "ARON-field").errs(), "product name, any case")
        assertEquals(listOf("too_common"), client.change(access, temp, "x" + name.uppercase() + "-9").errs(), "the username, any case")
    }

    /** docs/21 s4: the field deny-list is the top 10,000 common passwords; "password1" is on every such list. */
    @Test
    fun fieldRoleDenyListCatchesPassword1() = testApplication {
        application { aronApi(wiring) }
        val (_, name) = user("SR", mustChange = false)
        val access = json(client.login(name).bodyAsText())["access_token"]!!.jsonPrimitive.content
        val r = client.change(access, temp, "password1")
        assertEquals(HttpStatusCode.BadRequest, r.status, "password1 is accepted for a field role")
        assertEquals(listOf("too_common"), r.errs())
    }

    @Test
    fun aTemporaryPasswordJustResetIsNotHeldByTheMinimumAge() = testApplication {
        application { aronApi(wiring) }
        val (_, name) = user("DMO", mustChange = true, changedAgo = Duration.ofMinutes(5))
        val t = json(client.login(name).bodyAsText())["password_change_token"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.OK, client.change(t, temp, "Fresh-Reset-2027").status)
        val access = json(client.login(name, "Fresh-Reset-2027").bodyAsText())["access_token"]!!.jsonPrimitive.content
        assertTrue("min_age" in client.change(access, "Fresh-Reset-2027", "Fresh-Again-2028").errs())
    }

    @Test
    fun aSettledPasswordChangedLessThan24hAgoIsHeld() = testApplication {
        application { aronApi(wiring) }
        val (_, name) = user("DMO", mustChange = false, changedAgo = Duration.ofHours(23).plusMinutes(59))
        val access = json(client.login(name).bodyAsText())["access_token"]!!.jsonPrimitive.content
        assertTrue("min_age" in client.change(access, temp, "Held-Change-2027").errs())
        now.updateAndGet { it.plus(Duration.ofMinutes(2)) }
        val access2 = json(client.login(name).bodyAsText())["access_token"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.NoContent, client.change(access2, temp, "Held-Change-2027").status)
    }

    // ---- concurrency ----

    @Test
    fun concurrentChangesSucceedOnce() = testApplication {
        application { aronApi(wiring) }
        val (id, name) = user("DMO", mustChange = false)
        val access = json(client.login(name).bodyAsText())["access_token"]!!.jsonPrimitive.content
        val results = coroutineScope {
            listOf("Concurrent-One-11", "Concurrent-Two-22", "Concurrent-Three-33").map { p -> async { p to client.change(access, temp, p) } }.awaitAll()
        }
        val ok = results.filter { it.second.status == HttpStatusCode.NoContent }
        assertEquals(1, ok.size, results.map { it.second.status }.toString())
        val stored = fresh.db.jdbi.withHandle<String, Exception> { h -> h.createQuery("SELECT password_hash FROM app.app_user WHERE id = $id").mapTo(String::class.java).one() }
        assertTrue(hasher.verify(stored, ok.single().first))
    }

    // ---- secrets and shape ----

    @Test
    fun noPasswordEchoedInErrorsAndShapesMatchTheContract() = testApplication {
        application { aronApi(wiring) }
        val (_, name) = user("DMO")
        val t = json(client.login(name).bodyAsText())["password_change_token"]!!.jsonPrimitive.content
        val secret = "Secret-Value-9876"
        val bad = client.post("/v1/auth/change-password") {
            bearerAuth(t); contentType(ContentType.Application.Json)
            setBody("""{"current_password":"$temp","new_password":"$secret","extra":"x"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        val bt = bad.bodyAsText()
        assertFalse(secret in bt || temp in bt, bt)
        val pol = client.change(t, temp, "nolowerdigits")
        assertFalse("nolowerdigits" in pol.bodyAsText() || temp in pol.bodyAsText(), pol.bodyAsText())
        val wrong = client.change(t, "Not-the-current-1", "Good-Password-99")
        assertFalse("Not-the-current-1" in wrong.bodyAsText(), wrong.bodyAsText())

        val ok = client.change(t, temp, "Good-Password-99")
        assertEquals(HttpStatusCode.OK, ok.status)
        val allowed = setOf("status", "access_token", "access_expires_at", "refresh_token", "refresh_expires_at", "upload_refresh_token",
            "bind_token", "mfa_token", "password_change_token", "user", "scope", "device", "config_version", "server_time", "min_app_version_code")
        val body = json(ok.bodyAsText())
        assertTrue(allowed.containsAll(body.keys), (body.keys - allowed).toString())
        assertFalse("argon2" in ok.bodyAsText())

        val (_, f) = user("SR", mustChange = false)
        val access = json(client.login(f).bodyAsText())["access_token"]!!.jsonPrimitive.content
        val nc = client.change(access, temp, "mango river 7")
        assertEquals(HttpStatusCode.NoContent, nc.status)
        assertEquals("", nc.bodyAsText())
    }
}
