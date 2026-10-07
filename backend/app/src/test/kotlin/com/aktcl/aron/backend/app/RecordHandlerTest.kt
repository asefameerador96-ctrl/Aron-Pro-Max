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
class RecordHandlerTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"
    private val clock = AronClock { Instant.parse("2027-01-03T04:00:00Z") }
    private val day = "2027-01-03"
    private val seq = AtomicInteger(0)
    private val afterCalls = ConcurrentHashMap<String, AtomicInteger>()
    private val checkCalls = ConcurrentHashMap<String, AtomicInteger>()

    private val handler = object : RecordHandler {
        override val types = setOf("config_ack")
        override fun check(h: Handle, rec: IngestRecord): RecordRefusal? {
            checkCalls.getOrPut(rec.clientUuid) { AtomicInteger() }.incrementAndGet()
            val keys = rec.payload["keys"] as JsonArray
            return if (JsonPrimitive("cfg.refuse") in keys) RecordRefusal(RecordOutcomeCode.SCHEMA_INVALID, "refused by handler") else null
        }
        override fun afterStored(h: Handle, rec: IngestRecord, serverId: Long?) {
            require(serverId != null && rec.userId > 0 && rec.businessDate.toString() == day)
            if (JsonPrimitive("cfg.boom") in (rec.payload["keys"] as JsonArray)) error("handler failed")
            afterCalls.getOrPut(rec.clientUuid) { AtomicInteger() }.incrementAndGet()
        }
    }

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
            clock, extraRecordHandlers = listOf(handler),
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

    private fun ack(key: String, cu: String = uuid()) = buildJsonObject {
        val n = seq.incrementAndGet()
        put("type", "config_ack"); put("client_uuid", cu); put("family_uuid", uuid()); put("rank", 0); put("schema_version", 1)
        put("business_date", day); put("captured_at", "2027-01-03T03:%02d:00.000Z".format(n)); put("captured_elapsed_ms", 18330000 + n); put("boot_count", 412)
        put("clock_offset_ms", 0); put("captured_offline", false); put("bundle_version", "$day:3"); put("bundle_stale", false); put("config_version", 1)
        put("payload", buildJsonObject { put("applied_at", "2027-01-03T03:%02d:00.000Z".format(n)); put("config_version", 1); put("keys", JsonArray(listOf(JsonPrimitive(key)))) })
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

    @Test
    fun aHandlerChecksBeforeStoreRunsOnceAfterStoreAndAFailureIsRetryable() = testApplication {
        application { aronApi(wiring) }
        val token = client.login()
        val ok = ack("cfg.ok"); val refused = ack("cfg.refuse"); val boom = ack("cfg.boom"); val ok2 = ack("cfg.ok2")
        val ids = listOf(ok, refused, boom, ok2).map { it.s("client_uuid")!! }
        val body = batch(listOf(ok, refused, boom, ok2))

        val first = client.send(token, body)
        assertEquals(listOf("accepted", "rejected", "rejected", "accepted"), first.map { it.s("status") }, first.toString())
        assertEquals("schema_invalid", first[1].s("code"))
        assertEquals("server_error", first[2].s("code")); assertEquals("true", first[2].s("retryable"))
        assertEquals(1, afterCalls[ids[0]]?.get()); assertEquals(1, afterCalls[ids[3]]?.get())
        assertEquals(null, afterCalls[ids[1]], "a refused record never reaches afterStored")
        // Nothing of the refused or failed record is stored; the accepted ones are.
        assertEquals(2, count("SELECT count(*) FROM app.cfg_ack WHERE client_uuid IN ('${ids.joinToString("','")}')"))
        assertEquals(0, count("SELECT count(*) FROM app.cfg_ack WHERE client_uuid IN ('${ids[1]}', '${ids[2]}')"))
        // config_ack: the payload's config_version is the acked one (it shares its name with the envelope stamp).
        assertEquals(1, count("SELECT count(*) FROM app.cfg_ack WHERE client_uuid = '${ids[0]}' AND acked_config_version = 1 AND config_version = 1 AND keys = '{cfg.ok}'"))

        // Replay of the same batch and the same rows under a new batch: no second side effect, same answers.
        assertEquals(first, client.send(token, body))
        val again = client.send(token, batch(listOf(ok, refused, ok2)))
        assertEquals(listOf("duplicate", "rejected", "duplicate"), again.map { it.s("status") })
        assertEquals(1, afterCalls[ids[0]]?.get()); assertEquals(1, afterCalls[ids[3]]?.get())
        assertEquals(1, checkCalls[ids[1]]?.get(), "a final rejection is answered from the registry, not re-checked")
        assertEquals(1, count("SELECT count(*) FROM app.ingest_registry WHERE client_uuid = '${ids[1]}' AND status = 'rejected'"))
    }
}
