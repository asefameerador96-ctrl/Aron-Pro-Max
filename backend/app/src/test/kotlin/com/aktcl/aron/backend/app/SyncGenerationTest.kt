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
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * F-API-070 GET /v1/sync/generation (contract getServerGeneration, docs/24 s4.8): the current generation row in wire
 * form, equal to the `X-Server-Generation` header; after a failover row is minted the statement names it with
 * `lost_after_utc`. Seed, sr1001, real routing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncGenerationTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val now = Instant.parse("2027-01-03T06:00:00Z")
    private val clock = AronClock { now }
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"
    private var token: String? = null

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate("UPDATE app.app_user SET password_hash = :h WHERE pilot").bind("h", PasswordHasher().hash(password)).execute()
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

    private suspend fun HttpClient.login(): String = token ?: run {
        val r = post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
            setBody("""{"username":"sr1001","password":"$password","client":"app_sr","device_uuid":"$devPhone"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content.also { token = it }
    }

    private suspend fun HttpClient.generation(): Pair<JsonObject, String?> {
        val r = get("/v1/sync/generation") { bearerAuth(login()); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9") }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json(r.bodyAsText()) to r.headers["X-Server-Generation"]
    }

    @Test
    fun theStatementNamesTheCurrentGenerationAndAFailover() = testApplication {
        application { aronApi(wiring) }
        val (g, header) = client.generation()
        assertEquals("initial", g["kind"]!!.jsonPrimitive.content, g.toString())
        assertEquals(header, g["generation"]!!.jsonPrimitive.content)
        assertEquals(setOf("generation", "kind", "restore_point_utc", "lost_after_utc", "minted_at"), g.keys)
        val minted = UUID.randomUUID().toString()
        fresh.db.jdbi.useTransaction<Exception> { h ->
            h.execute("UPDATE app.server_generation SET is_current = false WHERE is_current")
            h.execute(
                "INSERT INTO app.server_generation (generation, kind, started_at, lost_after_utc) VALUES (CAST(? AS uuid), 'failover', TIMESTAMPTZ '2027-01-03 05:00:00+00', TIMESTAMPTZ '2027-01-03 04:59:30+00')",
                minted,
            )
        }
        val (f, _) = client.generation()
        assertEquals(minted, f["generation"]!!.jsonPrimitive.content)
        assertEquals("failover", f["kind"]!!.jsonPrimitive.content)
        assertEquals("2027-01-03T04:59:30.000Z", f["lost_after_utc"]!!.jsonPrimitive.content)
        assertEquals("2027-01-03T05:00:00.000Z", f["minted_at"]!!.jsonPrimitive.content)
    }

    @Test
    fun withoutATokenTheStatementIsRefused() = testApplication {
        application { aronApi(wiring) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/sync/generation").status)
    }
}
