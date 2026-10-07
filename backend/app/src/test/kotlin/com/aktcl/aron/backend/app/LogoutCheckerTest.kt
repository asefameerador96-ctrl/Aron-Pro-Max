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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Independent checker for F-API-031 (POST /v1/auth/logout). Setup mirrors LogoutTest. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LogoutCheckerTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val clock = AronClock { Instant.parse("2027-01-03T04:00:00Z") }
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"
    private val devPhone2 = "00000000-0000-4000-8000-000000000002"
    private val unknownPhone = "00000000-0000-4000-8000-0000000000ff"

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate("UPDATE app.app_user SET password_hash = :h, must_change_password = false WHERE pilot").bind("h", PasswordHasher().hash(password)).execute()
            h.createUpdate("INSERT INTO app.app_user (username, full_name, role, password_hash, must_change_password) VALUES ('dmo9001', 'DMO', 'DMO', :h, false)").bind("h", PasswordHasher().hash(password)).execute()
            h.createUpdate("INSERT INTO app.app_user (username, full_name, role, password_hash, must_change_password) VALUES ('dmo9002', 'DMO2', 'DMO', :h, true)").bind("h", PasswordHasher().hash(password)).execute()
            h.createUpdate("INSERT INTO app.app_user (username, full_name, role, password_hash, must_change_password) VALUES ('sr9002', 'SR2', 'SR', :h, false)").bind("h", PasswordHasher().hash(password)).execute()
            h.execute(
                """INSERT INTO app.device (device_uuid, flavour, app_package, status, device_owner, lockdown_level, trust_level, public_key_jwk,
                                           public_key_thumbprint, attestation_summary, app_signing_cert_sha256, zone_id, device_info, app_version)
                   SELECT '$devPhone2', flavour, app_package, status, device_owner, lockdown_level, trust_level, public_key_jwk,
                          'seed-dev-device-0002', attestation_summary, app_signing_cert_sha256, zone_id, device_info, app_version
                     FROM app.device WHERE device_uuid = '$devPhone'""",
            )
        }
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
    private fun uid(name: String) = "(SELECT id FROM app.app_user WHERE username = '$name')"
    private fun did(uuid: String) = "(SELECT id FROM app.device WHERE device_uuid = '$uuid')"
    private fun open(familyId: Long) = count("SELECT count(*) FROM app.refresh_family WHERE id = $familyId AND revoked_at IS NULL") == 1L
    private fun openOf(user: String) = count("SELECT count(*) FROM app.refresh_family WHERE user_id = ${uid(user)} AND revoked_at IS NULL")

    private fun family(user: String, deviceIdSql: String?, deviceUuid: String?, client: String, grant: String): Long =
        fresh.db.jdbi.withHandle<Long, Exception> { h ->
            h.createQuery(
                """INSERT INTO app.refresh_family (user_id, device_id, device_uuid, client, grant_kind, sliding_expires_at, absolute_expires_at)
                   VALUES (${uid(user)}, ${deviceIdSql ?: "NULL"}, ${deviceUuid?.let { "'$it'" } ?: "NULL"}, '$client', '$grant',
                           '2030-01-01T00:00:00Z', '2030-01-01T00:00:00Z') RETURNING id""",
            ).mapTo(Long::class.java).one()
        }

    private fun revokeAllOpen() = fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.refresh_family SET revoked_at = now(), revoke_reason = 'expired' WHERE revoked_at IS NULL") }

    private suspend fun HttpClient.phoneLogin(user: String = "sr1001", device: String = devPhone): JsonObject {
        val r = post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
            setBody("""{"username":"$user","password":"$password","client":"app_sr","device_uuid":"$device"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json(r.bodyAsText())
    }

    private suspend fun HttpClient.webLogin(user: String): HttpResponse =
        post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$password","client":"web"}""") }

    private suspend fun HttpClient.logoutRaw(token: String, body: String, deviceHeader: String? = devPhone, ct: ContentType = ContentType.Application.Json, cookie: String? = null): HttpResponse =
        post("/v1/auth/logout") {
            bearerAuth(token); deviceHeader?.let { header("X-Device-Id", it) }; cookie?.let { header("Cookie", "aron_rt=$it") }
            contentType(ct); setBody(body)
        }

    private suspend fun HttpClient.refresh(token: String, grant: String, device: String = devPhone): HttpResponse = post("/v1/auth/refresh") {
        header("X-Device-Id", device); contentType(ContentType.Application.Json)
        setBody("""{"grant":"$grant","refresh_token":"$token","device_uuid":"$device"}""")
    }

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    /** A phone logout (scope=all) reaches only the caller's families on the caller's phone. */
    @Test
    fun phoneLogoutAllReachesOnlyTheCallersFamiliesOnThisPhone() = testApplication {
        application { aronApi(wiring) }
        revokeAllOpen()
        val b = client.phoneLogin()
        val otherUserSamePhoneFull = family("amo1001", did(devPhone), devPhone, "app_sr", "full")
        val otherUserSamePhoneUpload = family("amo1001", did(devPhone), devPhone, "app_sr", "upload")
        val otherUserUuidOnly = family("amo1001", null, devPhone, "app_sr", "upload")
        val sameUserOtherPhoneFull = family("sr1001", did(devPhone2), devPhone2, "app_sr", "full")
        val sameUserOtherPhoneUpload = family("sr1001", did(devPhone2), null, "app_sr", "upload")
        val sameUserWeb = family("sr1001", null, null, "web", "full")
        val sameUserUuidOther = family("sr1001", null, unknownPhone, "app_sr", "full")
        val sameUserUuidOnlyThisPhone = family("sr1001", null, devPhone, "app_sr", "full")
        val sameUserUuidOnlyThisPhoneUpload = family("sr1001", null, devPhone, "app_sr", "upload")

        assertEquals(HttpStatusCode.NoContent, client.logoutRaw(b.s("access_token"), """{"scope":"all"}""").status)

        assertEquals(0, count("SELECT count(*) FROM app.refresh_family WHERE user_id = ${uid("sr1001")} AND device_id = ${did(devPhone)} AND revoked_at IS NULL"), "own phone families revoked")
        assertFalse(open(sameUserUuidOnlyThisPhone), "a family bound by device_uuid only (no device row) is this phone's")
        assertFalse(open(sameUserUuidOnlyThisPhoneUpload))
        for ((name, id) in listOf(
            "another user on the same phone (full)" to otherUserSamePhoneFull,
            "another user on the same phone (upload)" to otherUserSamePhoneUpload,
            "another user, uuid-only family" to otherUserUuidOnly,
            "same user, another phone (full)" to sameUserOtherPhoneFull,
            "same user, another phone (upload)" to sameUserOtherPhoneUpload,
            "same user, web" to sameUserWeb,
            "same user, uuid of another phone" to sameUserUuidOther,
        )) assertTrue(open(id), "$name must survive")
    }

    /** scope decides the grant whatever token calls: upload never touches full, session never touches upload. */
    @Test
    fun scopeDecidesTheGrantNotTheCallingToken() = testApplication {
        application { aronApi(wiring) }
        revokeAllOpen()
        val b = client.phoneLogin()
        val upRt = b.s("upload_refresh_token")
        // Full-grant access token, scope=session, presenting the UPLOAD refresh token in the body: upload must survive.
        assertEquals(HttpStatusCode.NoContent, client.logoutRaw(b.s("access_token"), """{"scope":"session","refresh_token":"$upRt"}""").status)
        assertEquals(1, count("SELECT count(*) FROM app.refresh_family WHERE user_id = ${uid("sr1001")} AND grant_kind = 'upload' AND revoked_at IS NULL"))

        revokeAllOpen()
        val c = client.phoneLogin()
        val up = client.refresh(c.s("upload_refresh_token"), "upload")
        assertEquals(HttpStatusCode.OK, up.status, up.bodyAsText())
        val upAccess = json(up.bodyAsText()).s("access_token")
        // Upload access token, scope=upload, presenting the FULL refresh token: the full grant must survive.
        assertEquals(HttpStatusCode.NoContent, client.logoutRaw(upAccess, """{"scope":"upload","refresh_token":"${c.s("refresh_token")}"}""").status)
        assertEquals(1, count("SELECT count(*) FROM app.refresh_family WHERE user_id = ${uid("sr1001")} AND grant_kind = 'full' AND revoked_at IS NULL"))
        assertEquals(0, count("SELECT count(*) FROM app.refresh_family WHERE user_id = ${uid("sr1001")} AND grant_kind = 'upload' AND revoked_at IS NULL"))
        // The upload access token keeps working for sync until it expires (stateless JWT), but its grant cannot refresh.
        assertEquals(HttpStatusCode.Unauthorized, client.refresh(json(up.bodyAsText()).s("refresh_token"), "upload").status)
    }

    /** After scope=session, the rotated child and the replaced parent (inside the 60 s grace) are both dead. */
    @Test
    fun sessionLogoutKillsTheRotatedChildAndTheGraceReplay() = testApplication {
        application { aronApi(wiring) }
        revokeAllOpen()
        val b = client.phoneLogin()
        val parent = b.s("refresh_token")
        val r1 = client.refresh(parent, "full")
        assertEquals(HttpStatusCode.OK, r1.status, r1.bodyAsText())
        val child = json(r1.bodyAsText())
        assertEquals(HttpStatusCode.NoContent, client.logoutRaw(child.s("access_token"), """{"scope":"session"}""").status)
        assertEquals(HttpStatusCode.Unauthorized, client.refresh(child.s("refresh_token"), "full").status, "rotated child")
        assertEquals(HttpStatusCode.Unauthorized, client.refresh(parent, "full").status, "parent replay inside the grace")
        assertEquals(1, count("SELECT count(*) FROM app.refresh_family WHERE id = (SELECT max(id) FROM app.refresh_family WHERE user_id = ${uid("sr1001")} AND grant_kind = 'full') AND revoke_reason = 'logout'"))
        assertEquals(1, openOf("sr1001"), "upload grant survives (D24-57)")
    }

    /** States what holds: an access token is a stateless JWT, so it keeps working after logout until it expires. */
    @Test
    fun accessTokenStillWorksAfterLogoutUntilExpiry() = testApplication {
        application { aronApi(wiring) }
        revokeAllOpen()
        val b = client.phoneLogin()
        val access = b.s("access_token")
        assertEquals(HttpStatusCode.NoContent, client.logoutRaw(access, """{"scope":"all"}""").status)
        val me = client.get("/v1/me") { bearerAuth(access); header("X-Device-Id", devPhone) }
        assertEquals(HttpStatusCode.OK, me.status, "documented: the access token outlives logout (60-70 min phone, 15 min web)")
        // And it can call logout again (idempotent 204).
        assertEquals(HttpStatusCode.NoContent, client.logoutRaw(access, """{"scope":"all"}""").status)
    }

    /** bind_token, mfa_token and password_change_token are refused (docs/24 s3.2 audience table) and revoke nothing. */
    @Test
    fun bindMfaAndPasswordChangeTokensAreRefused() = testApplication {
        application { aronApi(wiring) }
        revokeAllOpen()
        val keep = client.phoneLogin()
        // bind_required: sr9002 on a phone bound to sr1001.
        val bind = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
            setBody("""{"username":"sr9002","password":"$password","client":"app_sr","device_uuid":"$devPhone"}""")
        }
        val bj = json(bind.bodyAsText())
        assertEquals("bind_required", bj.s("status"), bind.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, client.logoutRaw(bj.s("bind_token"), """{"scope":"all"}""").status)
        val mfa = json(client.webLogin("admin1001").bodyAsText())
        assertEquals("mfa_required", mfa.s("status"))
        assertEquals(HttpStatusCode.Unauthorized, client.logoutRaw(mfa.s("mfa_token"), """{"scope":"all"}""", deviceHeader = null).status)
        val pw = json(client.webLogin("dmo9002").bodyAsText())
        assertEquals("password_change_required", pw.s("status"))
        assertEquals(HttpStatusCode.Unauthorized, client.logoutRaw(pw.s("password_change_token"), """{"scope":"all"}""", deviceHeader = null).status)
        assertEquals(2, openOf("sr1001"), "nothing revoked: ${keep.size}")
    }

    /** A phone token needs X-Device-Id equal to its dvu; a mismatch or a missing header revokes nothing. */
    @Test
    fun deviceHeaderMismatchIsRefusedAndRevokesNothing() = testApplication {
        application { aronApi(wiring) }
        revokeAllOpen()
        val b = client.phoneLogin()
        val a = b.s("access_token")
        val mis = client.logoutRaw(a, """{"scope":"all"}""", deviceHeader = devPhone2)
        assertTrue(mis.status == HttpStatusCode.Unauthorized || mis.status == HttpStatusCode.Forbidden, "${mis.status} ${mis.bodyAsText()}")
        assertTrue("ERR_DEVICE_PROOF_INVALID" in mis.bodyAsText())
        val none = client.logoutRaw(a, """{"scope":"all"}""", deviceHeader = null)
        assertTrue(none.status == HttpStatusCode.Unauthorized || none.status == HttpStatusCode.Forbidden, "${none.status}")
        assertEquals(HttpStatusCode.NoContent, client.logoutRaw(a, """{"scope":"all"}""", deviceHeader = devPhone.uppercase()).status, "header compared case-insensitively")
        assertEquals(0, openOf("sr1001"))
    }

    /** Bad bodies are 400/415 before anything is revoked, and no error echoes a token. */
    @Test
    fun badBodiesAreRefusedWithoutRevokingOrEchoingTokens() = testApplication {
        application { aronApi(wiring) }
        revokeAllOpen()
        val b = client.phoneLogin()
        val a = b.s("access_token")
        val rt = b.s("refresh_token")
        val bad = listOf(
            """{"scope":"session\u0000"}""",
            """{"scope":"sess\u0000ion"}""",
            """{"scope":"session","refresh_token":"${rt.dropLast(1)}\u0000"}""",
            """{"scope":"session","extra":1}""",
            """{"scope":"session","refresh_token":"${rt.take(42)}"}""",
            """{"scope":"session","refresh_token":"${rt + "x".repeat(65 - rt.length)}"}""",
            """{"scope":"session","refresh_token":42}""",
            """{"scope":1}""",
            """{"scope":null}""",
            """{"scope":"SESSION"}""",
            """{}""",
            """""",
            """[]""",
            """{"scope":"session","scope":"upload"}""",
            """{"scope":"session","refresh_token":"$rt""",
        )
        for (body in bad) {
            val r = client.logoutRaw(a, body)
            assertEquals(HttpStatusCode.BadRequest, r.status, "body $body -> ${r.bodyAsText()}")
            assertFalse(rt.take(20) in r.bodyAsText(), "token echoed for $body")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, client.logoutRaw(a, """{"scope":"session"}""", ct = ContentType.Text.Plain).status)
        assertEquals(2, openOf("sr1001"), "nothing revoked by a refused body")
        // Explicit null refresh_token is allowed by the schema.
        assertEquals(HttpStatusCode.NoContent, client.logoutRaw(a, """{"scope":"upload","refresh_token":null}""").status)
        assertEquals(1, openOf("sr1001"))
    }

    /** Web: the cleared cookie carries the same Path and attributes as the one login set; phones get no cookie. */
    @Test
    fun webCookieClearMatchesTheLoginCookie() = testApplication {
        application { aronApi(wiring) }
        revokeAllOpen()
        val r = client.webLogin("dmo9001")
        val set = r.headers.getAll("Set-Cookie")!!.single { it.startsWith("aron_rt=") }
        val rt = set.substringAfter("aron_rt=").substringBefore(';')
        val access = json(r.bodyAsText()).s("access_token")
        val out = client.logoutRaw(access, """{"scope":"session"}""", deviceHeader = null, cookie = rt)
        assertEquals(HttpStatusCode.NoContent, out.status)
        val cleared = out.headers.getAll("Set-Cookie")!!.single { it.startsWith("aron_rt=") }
        fun attrs(c: String) = c.split(';').drop(1).map { it.trim() }.filterNot { it.startsWith("Max-Age") || it.startsWith("Expires") }.toSet()
        assertEquals(attrs(set), attrs(cleared), "clear must match the set cookie's Path/Domain/flags")
        assertTrue("Max-Age=0" in cleared)
        assertEquals(0, openOf("dmo9001"))
        val phone = client.phoneLogin()
        val p = client.logoutRaw(phone.s("access_token"), """{"scope":"session"}""")
        assertNull(p.headers.getAll("Set-Cookie")?.firstOrNull { it.startsWith("aron_rt=") }, "phones get no cookie")
    }

    /**
     * Web logout with neither the aron_rt cookie nor a body token: the session's family cannot be found from the access
     * token (no family claim), so 204 is answered and the full grant stays open. States what holds.
     */
    @Test
    fun webLogoutWithoutCookieRevokesNothing() = testApplication {
        application { aronApi(wiring) }
        revokeAllOpen()
        val r = client.webLogin("dmo9001")
        val access = json(r.bodyAsText()).s("access_token")
        assertNotNull(access)
        val out = client.logoutRaw(access, """{"scope":"session"}""", deviceHeader = null)
        assertEquals(HttpStatusCode.NoContent, out.status)
        assertEquals(1, openOf("dmo9001"), "documented gap: the web family survives a cookie-less logout")
    }
}
