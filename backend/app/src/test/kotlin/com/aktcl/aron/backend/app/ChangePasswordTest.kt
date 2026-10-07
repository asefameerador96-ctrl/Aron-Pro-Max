package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.JdbiPasswordStore
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * F-API-004, F-SYS-004 and contract v1.2 R15/R18 through the production wiring: the policy by role, the 24 h minimum
 * age, no reuse of the current (and, once app.password_history exists, the last 10) passwords, an Argon2id hash, the
 * web `password_change_token` flow (aud aron-pwchange, accepted only by change-password, single use, 200 LoginResponse
 * with the refresh token only in Set-Cookie, TOTP after the change) and the revocation of the full-grant families.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChangePasswordTest {
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

    /** A new user with a temporary (or settled) password; distinct names keep the fixed-clock limiters apart. */
    private fun user(role: String, mustChange: Boolean = true, password: String = temp): Pair<Long, String> {
        val name = role.lowercase() + "u" + seq.incrementAndGet()
        val id = fresh.db.jdbi.withHandle<Long, Exception> { h ->
            h.createQuery("INSERT INTO app.app_user (username, full_name, role, password_hash, must_change_password, password_changed_at) VALUES (:u, 'Test', :r, :p, :m, :at) RETURNING id")
                .bind("u", name).bind("r", role).bind("p", hasher.hash(password)).bind("m", mustChange).bind("at", java.time.OffsetDateTime.ofInstant(now.get().minus(Duration.ofDays(3)), java.time.ZoneOffset.UTC))
                .mapTo(Long::class.java).one()
        }
        return id to name
    }

    private suspend fun HttpClient.webLogin(name: String, password: String = temp): HttpResponse = post("/v1/auth/login") {
        contentType(ContentType.Application.Json); setBody("""{"username":"$name","password":"$password","client":"web"}""")
    }

    private suspend fun HttpClient.change(token: String, current: String, new: String): HttpResponse = post("/v1/auth/change-password") {
        bearerAuth(token); contentType(ContentType.Application.Json)
        setBody("""{"current_password":"$current","new_password":"$new"}""")
    }

    private suspend fun HttpResponse.problemCodes(): List<String> =
        json(bodyAsText())["errors"]!!.jsonArray.map { it.jsonObject["code"]!!.jsonPrimitive.content }

    @Test
    fun aWebTemporaryPasswordGetsAPasswordChangeTokenThatOnlyChangePasswordAccepts() = testApplication {
        application { aronApi(wiring) }
        val (id, name) = user("DMO")
        val r = client.webLogin(name)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val b = json(r.bodyAsText())
        assertEquals("password_change_required", b["status"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, b["access_token"], "R15: no access token")
        assertEquals(JsonNull, b["refresh_token"])
        val pw = b["password_change_token"]!!.jsonPrimitive.content
        assertTrue(r.headers.getAll("Set-Cookie").orEmpty().none { it.startsWith("aron_rt=") }, "no refresh grant before the change")
        // aud aron-pwchange (R18), accepted by nothing else.
        val aud = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(pw.split('.')[1]))).jsonObject["aud"]!!
        assertTrue("aron-pwchange" in aud.toString(), aud.toString())
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/me") { bearerAuth(pw) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/admin/outlets") { bearerAuth(pw) }.status)

        val c = client.change(pw, temp, "Settled-Pass-42")
        assertEquals(HttpStatusCode.OK, c.status, c.bodyAsText())
        val cb = json(c.bodyAsText())
        assertEquals("ok", cb["status"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, cb["refresh_token"], "web: the refresh token travels only in Set-Cookie")
        assertTrue(c.headers.getAll("Set-Cookie")!!.any { it.startsWith("aron_rt=") && "HttpOnly" in it })
        assertEquals(HttpStatusCode.OK, client.get("/v1/me") { bearerAuth(cb["access_token"]!!.jsonPrimitive.content) }.status, "the new access token works at once")
        // Argon2id hash stored, temporary flag cleared.
        assertEquals(1, count("SELECT count(*) FROM app.app_user WHERE id = $id AND NOT must_change_password AND password_hash LIKE '\$argon2id\$%'"))
        // Single use: the token is spent once the temporary password is gone.
        assertEquals(HttpStatusCode.Unauthorized, client.change(pw, "Settled-Pass-42", "Another-Pass-43").status)
        assertEquals("ok", json(client.webLogin(name, "Settled-Pass-42").bodyAsText())["status"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.Unauthorized, client.webLogin(name).status, "the temporary password no longer works")
    }

    @Test
    fun anMfaRoleChangesThePasswordFirstThenGetsTheTotpStep() = testApplication {
        application { aronApi(wiring) }
        val (_, name) = user("ADMIN")
        val b = json(client.webLogin(name).bodyAsText())
        assertEquals("password_change_required", b["status"]!!.jsonPrimitive.content, "R15: the password change comes before TOTP")
        val c = json(client.change(b["password_change_token"]!!.jsonPrimitive.content, temp, "Admin-Settled-77").bodyAsText())
        assertEquals("mfa_required", c["status"]!!.jsonPrimitive.content, c.toString())
        assertNotNull(c["mfa_token"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, c["access_token"])
    }

    @Test
    fun thePolicyDependsOnTheRole() = testApplication {
        application { aronApi(wiring) }
        // Web roles: 12+ with lower, upper and a digit (the role-scoped cfg.auth.password_min_len is 12).
        val (_, web) = user("WM")
        val token = json(client.webLogin(web).bodyAsText())["password_change_token"]!!.jsonPrimitive.content
        val short = client.change(token, temp, "Short-1a")
        assertEquals(HttpStatusCode.BadRequest, short.status)
        assertEquals("ERR_AUTH_PASSWORD_POLICY", json(short.bodyAsText())["code"]!!.jsonPrimitive.content)
        assertTrue("too_short" in short.problemCodes())
        assertEquals(listOf("missing_upper", "missing_digit"), client.change(token, temp, "alllowercaseletters").problemCodes())
        assertEquals(listOf("reused"), client.change(token, temp, temp).problemCodes(), "the current password is one of the last 10")
        assertEquals(HttpStatusCode.Unauthorized, client.change(token, "Wrong-current-1", "Good-Password-9").status)
        assertEquals(HttpStatusCode.OK, client.change(token, temp, "Good-Password-9").status)

        // Field roles: 8+ and the deny-list (product names, common passwords, the username); no case rules.
        val (sid, field) = user("SR", mustChange = false)
        val access = json(client.webLogin(field).bodyAsText()).let { it["access_token"]?.jsonPrimitive?.content ?: error(it.toString()) }
        assertEquals(listOf("too_common"), client.change(access, temp, "aron2027x").problemCodes())
        assertEquals(listOf("too_common"), client.change(access, temp, "x${field}-river").problemCodes())
        assertEquals(listOf("too_common"), client.change(access, temp, "password").problemCodes())
        assertEquals(HttpStatusCode.NoContent, client.change(access, temp, "mango river 7").status, "an ordinary access token answers 204")
        // At most one change in 24 h, then allowed again.
        val again = client.change(access, "mango river 7", "blue tiger 88")
        assertTrue("min_age" in again.problemCodes(), again.bodyAsText())
        assertEquals(1, count("SELECT count(*) FROM app.app_user WHERE id = $sid AND password_changed_at = '${now.get()}'"))
    }

    @Test
    fun aChangeRevokesTheOtherFullGrantsAndKeepsUploadGrants() = testApplication {
        application { aronApi(wiring) }
        val (id, name) = user("TSO", mustChange = false)
        val first = client.webLogin(name)
        val rt = first.headers.getAll("Set-Cookie")!!.single { it.startsWith("aron_rt=") }.substringAfter("aron_rt=").substringBefore(';')
        val access = json(first.bodyAsText())["access_token"]!!.jsonPrimitive.content
        client.webLogin(name) // a second web session
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.refresh_family (user_id, device_uuid, client, grant_kind, sliding_expires_at, absolute_expires_at) VALUES ($id, gen_random_uuid(), 'app_tso', 'upload', now() + interval '7 days', now() + interval '3650 days')")
        }
        assertEquals(2, count("SELECT count(*) FROM app.refresh_family WHERE user_id = $id AND grant_kind = 'full' AND revoked_at IS NULL"))
        assertEquals(HttpStatusCode.NoContent, client.change(access, temp, "new pass word 5").status)
        assertEquals(0, count("SELECT count(*) FROM app.refresh_family WHERE user_id = $id AND grant_kind = 'full' AND revoked_at IS NULL"))
        assertEquals(2, count("SELECT count(*) FROM app.refresh_family WHERE user_id = $id AND grant_kind = 'full' AND revoke_reason = 'password_changed'"))
        assertEquals(1, count("SELECT count(*) FROM app.refresh_family WHERE user_id = $id AND grant_kind = 'upload' AND revoked_at IS NULL"), "D24-57: upload grants survive")
        val refreshed = client.post("/v1/auth/refresh") { contentType(ContentType.Application.Json); header("Cookie", "aron_rt=$rt"); setBody("""{"grant":"full"}""") }
        assertEquals("ERR_PASSWORD_CHANGED", json(refreshed.bodyAsText())["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun theStoreKeepsTheCallerPhonesOwnFamily() {
        val (id, _) = user("SR", mustChange = false)
        val device = fresh.db.jdbi.withHandle<Long?, Exception> { h -> h.createQuery("SELECT id FROM app.device ORDER BY id LIMIT 1").mapTo(Long::class.java).findOne().orElse(null) }
        assumeTrue(device != null, "needs a device row")
        fresh.db.jdbi.useHandle<Exception> { h ->
            for ((client, dev) in listOf("app_sr" to device, "web" to null)) {
                h.createUpdate("INSERT INTO app.refresh_family (user_id, device_id, client, grant_kind, sliding_expires_at, absolute_expires_at) VALUES (:u, :d, :c, 'full', now() + interval '30 days', now() + interval '90 days')")
                    .bind("u", id).bind("d", dev).bind("c", client).execute()
            }
        }
        val store = JdbiPasswordStore(fresh.db)
        val hash = store.state(id, 9)!!.currentHash
        assertTrue(store.change(id, hash, hasher.hash("phone pass 12"), now.get(), device))
        assertEquals(1, count("SELECT count(*) FROM app.refresh_family WHERE user_id = $id AND device_id = $device AND revoked_at IS NULL"))
        assertEquals(1, count("SELECT count(*) FROM app.refresh_family WHERE user_id = $id AND device_id IS NULL AND revoke_reason = 'password_changed'"))
        // A concurrent change that saw the old hash loses.
        assertTrue(!store.change(id, hash, hasher.hash("other pass 13"), now.get(), device))
    }

    /** Not one of the last 10 (needs app.password_history, docs/requests/backend-core-password-history.md). */
    @Test
    fun noneOfTheLastTenPasswords() = testApplication {
        val hasTable = count("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'app' AND c.relname = 'password_history'") == 1L
        assumeTrue(hasTable, "app.password_history is not migrated yet")
        application { aronApi(wiring) }
        val (_, name) = user("SR", mustChange = false)
        suspend fun access(pw: String) = json(client.webLogin(name, pw).bodyAsText())["access_token"]!!.jsonPrimitive.content
        var current = temp
        val used = (1..10).map { "history pass $it" }
        for (p in used) {
            now.updateAndGet { it.plus(Duration.ofHours(25)) } // past the minimum age; a fresh token each time
            assertEquals(HttpStatusCode.NoContent, client.change(access(current), current, p).status)
            current = p
        }
        now.updateAndGet { it.plus(Duration.ofHours(25)) }
        assertEquals(listOf("reused"), client.change(access(current), current, used[1]).problemCodes(), "the 9th-last password")
        assertEquals(HttpStatusCode.NoContent, client.change(access(current), current, temp).status, "the 11th-last is allowed again")
    }
}
