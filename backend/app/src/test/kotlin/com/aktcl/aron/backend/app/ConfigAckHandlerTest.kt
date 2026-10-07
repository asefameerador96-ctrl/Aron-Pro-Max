package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.IngestRecord
import com.aktcl.aron.backend.platform.RecordHandler
import com.aktcl.aron.backend.platform.RecordRefusal
import com.aktcl.aron.backend.platform.Settings
import com.aktcl.aron.contract.RecordOutcomeCode
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The ingest extension point (F-API-006 family): a registered [RecordHandler] can refuse a record before it is
 * stored, runs its side effect once on first store (never on a duplicate or a replay), and a handler that throws
 * rolls the record back and acks it rejected(server_error) retryable, with the rest of the batch going on.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigAckHandlerTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"
    private val clock = AronClock { Instant.parse("2027-01-03T04:00:00Z") }
    private val day = "2027-01-03"
    private val seq = AtomicInteger(0)
    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        fresh.db.jdbi.useHandle<Exception> { h -> h.createUpdate("UPDATE app.app_user SET password_hash = :h WHERE pilot").bind("h", PasswordHasher().hash(password)).execute() }
        val keyFile = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        wiring = Wiring.production(
            Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to keyFile.absolutePath)),
            clock,
        )
    }

    @AfterAll
    fun tearDown() { wiring.database?.close(); fresh.close() }

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun uuid() = UUID.randomUUID().toString()
    private fun count(sql: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }

    private suspend fun HttpClient.login(): String {
        val r = post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
            setBody("""{"username":"sr1001","password":"$password","client":"app_sr","device_uuid":"$devPhone"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content
    }

    private fun ack(key: String, version: Long = 1, cu: String = uuid()) = buildJsonObject {
        val n = seq.incrementAndGet()
        put("type", "config_ack"); put("client_uuid", cu); put("family_uuid", uuid()); put("rank", 0); put("schema_version", 1)
        put("business_date", day); put("captured_at", "2027-01-03T03:%02d:00.000Z".format(n)); put("captured_elapsed_ms", 18330000 + n); put("boot_count", 412)
        put("clock_offset_ms", 0); put("captured_offline", false); put("bundle_version", "$day:3"); put("bundle_stale", false); put("config_version", 1)
        put("payload", buildJsonObject { put("applied_at", "2027-01-03T03:%02d:00.000Z".format(n)); put("config_version", version); put("keys", JsonArray(listOf(JsonPrimitive(key)))) })
    }

    private fun batch(records: List<JsonObject>, batchUuid: String = uuid()) = buildJsonObject {
        put("batch_uuid", batchUuid); put("device_uuid", devPhone); put("schema_version", 1); put("app_version", "1.0.9+9")
        put("trigger", "manual"); put("sent_at_device", "2027-01-03T04:00:00.000Z"); put("pending_rows", 0)
        put("time_anchors", JsonArray(emptyList())); put("device_counts", buildJsonObject {})
        put("records", JsonArray(records))
    }.toString()

    private suspend fun HttpClient.send(token: String, body: String): List<JsonObject> {
        val r = post("/v1/sync/batch") {
            bearerAuth(token); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9")
            header("Content-Encoding", "gzip")
            setBody(io.ktor.http.content.ByteArrayContent(ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(body.toByteArray()) } }.toByteArray(), ContentType.Application.Json))
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json(r.bodyAsText())["acks"]!!.jsonArray.map { it.jsonObject }
    }

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content

    private fun applied(): Long? = fresh.db.jdbi.withHandle<Long?, Exception> { h -> h.createQuery("SELECT config_version_applied FROM app.device WHERE device_uuid = CAST(:u AS uuid)").bind("u", devPhone).map { rs, _ -> rs.getObject(1) as Long? }.one() }

    @Test
    fun anAckMovesTheDeviceForwardNeverBackwardAndAnUnknownVersionIsParked() = testApplication {
        application { aronApi(wiring) }
        val token = client.login()
        val top = count("SELECT max(config_version) FROM app.cfg_version")
        val r1 = client.send(token, batch(listOf(ack("cfg.a", top))))
        assertEquals("accepted", r1[0].s("status"), r1.toString())
        assertEquals(top, applied())
        // A late ack of an older version is stored but does not lower the device's applied version.
        val old = client.send(token, batch(listOf(ack("cfg.b", 1))))
        assertEquals("accepted", old[0].s("status")); assertEquals(top, applied())
        // A version the server never committed is refused, retryable, and nothing is stored for it.
        val unk = ack("cfg.c", top + 1000); val u = client.send(token, batch(listOf(unk)))
        assertEquals("rejected", u[0].s("status")); assertEquals("config_version_unknown", u[0].s("code")); assertEquals("true", u[0].s("retryable"))
        assertEquals(0, count("SELECT count(*) FROM app.cfg_ack WHERE client_uuid = '${unk.s("client_uuid")}'"))
        assertEquals(top, applied())
        // The reach view counts the device as acked at the version it reported.
        assertEquals(1, count("SELECT count(*) FROM app.cfg_ack a JOIN app.device d ON d.id = a.device_id WHERE d.device_uuid = CAST('$devPhone' AS uuid) AND a.acked_config_version >= $top"))
    }
}
