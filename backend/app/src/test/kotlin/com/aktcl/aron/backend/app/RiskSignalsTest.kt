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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
import kotlin.test.assertNull

/**
 * F-SYS-057 risk signals through the production wiring on the seed (Sunday 2027-01-03 10:00 Dhaka): supervisors list
 * the signals in their reach (the signal's zone, else its route's, else its user's home zone), field reps get 403; a
 * review is an append-only event idempotent by review_uuid, sets the status, and an out-of-reach signal is 404.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RiskSignalsTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"
    private val clock = AronClock { Instant.parse("2027-01-03T04:00:00Z") }
    private val ids = HashMap<String, Long>()
    private val tokens = HashMap<String, String>()

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate("UPDATE app.app_user SET password_hash = :h WHERE pilot").bind("h", PasswordHasher().hash(password)).execute()
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.auth.mfa_required_roles', 'global', 0, '[]'::jsonb, now() - interval '1 day', max(config_version), 'test: no MFA' FROM app.cfg_version")
            h.execute("INSERT INTO app.territory (code, name, division_id) SELECT 'T-OTHER', 'Other', division_id FROM app.territory WHERE code = 'T-DHK-N'")
            h.execute("INSERT INTO app.zone (code, name, territory_id) SELECT 'Z-OTHER', 'Other zone', id FROM app.territory WHERE code = 'T-OTHER'")
            fun signal(name: String, code: String, zoneSql: String, userSql: String, date: String = "2027-01-03") {
                ids[name] = h.createQuery(
                    """
                    INSERT INTO app.risk_signal (code, severity, business_date, subject_type, subject_id, user_id, zone_id, score, evidence, config_version)
                    VALUES ('$code', 3, DATE '$date', 'device', '$name', $userSql, $zoneSql, 40, '{"n": 1}'::jsonb, 1) RETURNING id
                    """.trimIndent(),
                ).mapTo(Long::class.java).one()
            }
            signal("mir", "GEO_TELEPORT", "(SELECT id FROM app.zone WHERE code = 'Z-MIR')", "NULL")
            signal("other", "GEO_TELEPORT", "(SELECT id FROM app.zone WHERE code = 'Z-OTHER')", "NULL")
            // No zone, no route: it belongs to its user's home zone (sr1001, Mirpur).
            signal("home", "DEVICE_INTEGRITY_FAIL", "NULL", "(SELECT id FROM app.app_user WHERE username = 'sr1001')", "2027-01-02")
        }
        val keyFile = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        wiring = Wiring.production(Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to keyFile.absolutePath)), clock)
    }

    @AfterAll
    fun tearDown() { wiring.securityStore?.close(); wiring.database?.close(); fresh.close() }

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private suspend fun HttpClient.token(user: String): String = tokens.getOrPut(user) {
        val body = if (user == "sr1001") """{"username":"sr1001","password":"$password","client":"app_sr","device_uuid":"$devPhone"}"""
        else """{"username":"$user","password":"$password","client":"web"}"""
        val r = post("/v1/auth/login") { contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9"); setBody(body) }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content
    }

    private suspend fun HttpClient.list(user: String, query: String = ""): HttpResponse =
        get("/v1/risk-signals$query") { bearerAuth(token(user)); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9") }

    private suspend fun HttpClient.review(user: String, id: Long, uuid: String, action: String): HttpResponse =
        post("/v1/risk-signals/$id/review") {
            bearerAuth(token(user)); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9"); contentType(ContentType.Application.Json)
            setBody("""{"review_uuid":"$uuid","action":"$action","note":"checked with the TSO"}""")
        }

    private fun subjects(r: String) = json(r)["items"]!!.jsonArray.map { it.jsonObject["subject_id"]!!.jsonPrimitive.content }

    @Test
    fun supervisorsSeeOnlySignalsInTheirReachAndFieldRepsNone() = testApplication {
        application { aronApi(wiring) }
        val amo = client.list("amo1001")
        assertEquals(HttpStatusCode.OK, amo.status, amo.bodyAsText())
        assertEquals(listOf("mir", "home"), subjects(amo.bodyAsText()), "newest first; never the other zone's signal")
        assertEquals(listOf("other", "mir", "home"), subjects(client.list("admin1001").bodyAsText()), "national sees all (same date: newest id first)")
        assertEquals(listOf("home"), subjects(client.list("amo1001", "?code=DEVICE_INTEGRITY_FAIL").bodyAsText()))
        // Paging: one per page, the cursor reaches the second and ends.
        val p1 = json(client.list("amo1001", "?limit=1").bodyAsText())
        assertEquals(listOf("mir"), p1["items"]!!.jsonArray.map { it.jsonObject["subject_id"]!!.jsonPrimitive.content })
        val p2 = json(client.list("amo1001", "?limit=1&cursor=${p1["next_cursor"]!!.jsonPrimitive.content}").bodyAsText())
        assertEquals(listOf("home"), p2["items"]!!.jsonArray.map { it.jsonObject["subject_id"]!!.jsonPrimitive.content })
        assertEquals(kotlinx.serialization.json.JsonNull, p2["next_cursor"])
        val otherZone = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery("SELECT id FROM app.zone WHERE code = 'Z-OTHER'").mapTo(Long::class.java).one() }
        assertEquals(HttpStatusCode.Forbidden, client.list("amo1001", "?zone_id=$otherZone").status)
        assertEquals(HttpStatusCode.Forbidden, client.list("sr1001").status, "field reps never see risk signals")
        assertEquals(HttpStatusCode.BadRequest, client.list("amo1001", "?code=NOPE").status)
    }

    @Test
    fun aReviewIsAnIdempotentEventAndOutOfReachIsNotFound() = testApplication {
        application { aronApi(wiring) }
        val id = ids.getValue("mir")
        val uuid = UUID.randomUUID().toString()
        val r = client.review("amo1001", id, uuid, "confirmed")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val b = json(r.bodyAsText())
        assertEquals("confirmed", b["status"]!!.jsonPrimitive.content)
        assertEquals("confirmed", b["last_review"]!!.jsonObject["action"]!!.jsonPrimitive.content)
        // The same review again: unchanged, one event.
        assertEquals(HttpStatusCode.OK, client.review("amo1001", id, uuid, "confirmed").status)
        val events = { fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery("SELECT count(*) FROM app.risk_signal_review WHERE signal_id = $id").mapTo(Long::class.java).one() } }
        assertEquals(1, events())
        // The uuid reused for another action is a conflict; a new review appends and moves the status, nothing is lost.
        assertEquals(HttpStatusCode.Conflict, client.review("amo1001", id, uuid, "dismissed").status)
        assertEquals("dismissed", json(client.review("amo1001", id, UUID.randomUUID().toString(), "dismissed").bodyAsText())["status"]!!.jsonPrimitive.content)
        assertEquals(2, events())
        // Out of reach: 404, nothing written; a field rep: 403.
        assertEquals(HttpStatusCode.NotFound, client.review("amo1001", ids.getValue("other"), UUID.randomUUID().toString(), "dismissed").status)
        assertEquals(0L, fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery("SELECT count(*) FROM app.risk_signal_review WHERE signal_id = ${ids.getValue("other")}").mapTo(Long::class.java).one() })
        assertEquals(HttpStatusCode.Forbidden, client.review("sr1001", id, UUID.randomUUID().toString(), "dismissed").status)
        assertNull(json(client.list("admin1001", "?status=open").bodyAsText())["items"]!!.jsonArray.firstOrNull { it.jsonObject["signal_id"]!!.jsonPrimitive.long == id })
    }

    @Test
    fun aFieldRepsSyncedReviewIsQuarantinedOutOfReachAndMovesNothing() = testApplication {
        application { aronApi(wiring) }
        val id = ids.getValue("home")
        val cu = UUID.randomUUID().toString()
        val rec = """{"type":"risk_review","client_uuid":"$cu","family_uuid":"$cu","rank":0,"schema_version":1,"business_date":"2027-01-03",
            "captured_at":"2027-01-03T03:50:00.000Z","captured_elapsed_ms":1000,"boot_count":1,"clock_offset_ms":0,"captured_offline":false,"config_version":1,
            "payload":{"signal_id":$id,"action":"dismissed","note":null}}"""
        val batchUuid = UUID.randomUUID().toString()
        val body = """{"batch_uuid":"$batchUuid","device_uuid":"$devPhone","schema_version":1,"app_version":"1.0.9+9","trigger":"manual",
            "sent_at_device":"2027-01-03T04:00:00.000Z","pending_rows":0,"time_anchors":[],"device_counts":{},"records":[$rec]}"""
        val gz = java.io.ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write(body.toByteArray()) } }.toByteArray()
        val r = client.post("/v1/sync/batch") {
            bearerAuth(client.token("sr1001")); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9")
            header("Content-Encoding", "gzip"); setBody(io.ktor.http.content.ByteArrayContent(gz, ContentType.Application.Json))
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val ack = json(r.bodyAsText())["acks"]!!.jsonArray.single().jsonObject
        assertEquals("quarantined:scope_out_of_reach", ack["status"]!!.jsonPrimitive.content + ":" + ack["code"]!!.jsonPrimitive.content)
        assertEquals("open", fresh.db.jdbi.withHandle<String, Exception> { it.createQuery("SELECT status FROM app.risk_signal WHERE id = $id").mapTo(String::class.java).one() })
    }
}
