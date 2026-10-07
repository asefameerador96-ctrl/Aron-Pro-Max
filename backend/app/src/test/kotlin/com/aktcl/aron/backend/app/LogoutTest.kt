package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Settings
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
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
import kotlin.test.assertTrue

/**
 * F-API-031 through the production wiring: POST /v1/auth/logout revokes the full refresh grant at once (scope
 * session), the upload-only grant with scope upload (sent after the last ACK), both with all; a token of another user
 * is ignored, and a web logout also clears the aron_rt cookie.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LogoutTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val clock = AronClock { Instant.parse("2027-01-03T04:00:00Z") }
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"

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
    private val sr = "(SELECT id FROM app.app_user WHERE username = 'sr1001')"

    private suspend fun HttpClient.phoneLogin(): JsonObject {
        val r = post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
            setBody("""{"username":"sr1001","password":"$password","client":"app_sr","device_uuid":"$devPhone"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json(r.bodyAsText())
    }

    private suspend fun HttpClient.logout(token: String, scope: String, refresh: String? = null, phone: Boolean = true, cookie: String? = null): HttpResponse =
        post("/v1/auth/logout") {
            bearerAuth(token); if (phone) header("X-Device-Id", devPhone); cookie?.let { header("Cookie", "aron_rt=$it") }
            contentType(ContentType.Application.Json)
            setBody(if (refresh == null) """{"scope":"$scope"}""" else """{"scope":"$scope","refresh_token":"$refresh"}""")
        }

    private suspend fun HttpClient.refresh(token: String, grant: String): HttpResponse = post("/v1/auth/refresh") {
        header("X-Device-Id", devPhone); contentType(ContentType.Application.Json)
        setBody("""{"grant":"$grant","refresh_token":"$token","device_uuid":"$devPhone"}""")
    }

    @Test
    fun sessionRevokesTheFullGrantAtOnceAndUploadTheUploadGrant() = testApplication {
        application { aronApi(wiring) }
        val b = client.phoneLogin()
        val access = b["access_token"]!!.jsonPrimitive.content
        val upload = b["upload_refresh_token"]!!.jsonPrimitive.content
        assertEquals(1, count("SELECT count(*) FROM app.refresh_family WHERE user_id = $sr AND grant_kind = 'full' AND revoked_at IS NULL"))

        assertEquals(HttpStatusCode.NoContent, client.logout(access, "session").status)
        assertEquals(0, count("SELECT count(*) FROM app.refresh_family WHERE user_id = $sr AND grant_kind = 'full' AND revoked_at IS NULL"), "the full grant ends at once")
        assertEquals(1, count("SELECT count(*) FROM app.refresh_family WHERE user_id = $sr AND grant_kind = 'upload' AND revoked_at IS NULL"), "D24-57: uploads keep going")
        assertEquals(HttpStatusCode.Unauthorized, client.refresh(b["refresh_token"]!!.jsonPrimitive.content, "full").status)
        // The upload grant still mints an upload token, which may call logout itself.
        val up = client.refresh(upload, "upload")
        assertEquals(HttpStatusCode.OK, up.status, up.bodyAsText())
        val uploadAccess = json(up.bodyAsText())["access_token"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.NoContent, client.logout(uploadAccess, "session").status, "idempotent")
        assertEquals(HttpStatusCode.NoContent, client.logout(uploadAccess, "upload").status)
        assertEquals(0, count("SELECT count(*) FROM app.refresh_family WHERE user_id = $sr AND revoked_at IS NULL"))
        assertEquals(2, count("SELECT count(*) FROM app.refresh_family WHERE user_id = $sr AND revoke_reason = 'logout'"))
    }

    @Test
    fun aWebLogoutRevokesItsCookieFamilyAndIgnoresAnotherUsersToken() = testApplication {
        application { aronApi(wiring) }
        val phone = client.phoneLogin()
        val r = client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"dmo9001","password":"$password","client":"web"}""") }
        val rt = r.headers.getAll("Set-Cookie")!!.single { it.startsWith("aron_rt=") }.substringAfter("aron_rt=").substringBefore(';')
        val access = json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content
        // Another user's refresh token in the body is ignored: the SR's phone session survives.
        val out = client.logout(access, "all", refresh = phone["refresh_token"]!!.jsonPrimitive.content, phone = false, cookie = rt)
        assertEquals(HttpStatusCode.NoContent, out.status, out.bodyAsText())
        assertTrue(out.headers.getAll("Set-Cookie")!!.any { it.startsWith("aron_rt=;") && "Max-Age=0" in it })
        assertEquals(1, count("SELECT count(*) FROM app.refresh_family WHERE user_id = $sr AND grant_kind = 'full' AND revoked_at IS NULL"))
        // The caller's own cookie family is revoked, and a second logout changes nothing.
        assertEquals(0, count("SELECT count(*) FROM app.refresh_family WHERE user_id = (SELECT id FROM app.app_user WHERE username = 'dmo9001') AND revoked_at IS NULL"))
        assertEquals(HttpStatusCode.NoContent, client.logout(access, "session", phone = false, cookie = rt).status)
        assertEquals(HttpStatusCode.BadRequest, client.logout(access, "everything", phone = false).status)
    }
}
