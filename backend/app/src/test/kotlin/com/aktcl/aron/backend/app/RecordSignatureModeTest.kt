package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.DeviceProof
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Settings
import com.aktcl.aron.backend.sync.Jcs
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * android-core-backend-record-signature-mode.md (F-SYS-072, docs/19 `cfg.sec.record_signature_mode` off < record <
 * enforce): a header record from an enrolled phone with a missing or bad `sig` is accepted in off and record (record
 * raises DEVICE_INTEGRITY_FAIL with the reason) and quarantined only in enforce. With no value configured the mode is
 * record, so a build that does not sign yet never loses a sale. `sig` is not part of the registry hash.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RecordSignatureModeTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private lateinit var keys: KeyPair
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"
    private val day = "2027-01-03"
    private val now = AtomicReference(Instant.parse("2027-01-03T04:00:00Z"))
    private val seq = AtomicInteger(0)
    private val modes = AtomicInteger(0)
    private var routeId = 0L
    private var outletId = 0L
    private var token: String? = null

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val jwk = ECKey.Builder(Curve.P_256, keys.public as ECPublicKey).build()
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate("UPDATE app.app_user SET password_hash = :h WHERE pilot").bind("h", PasswordHasher().hash(password)).execute()
            // The dev phone is enrolled with a real key: every header record is subject to the signature mode.
            h.createUpdate("UPDATE app.device SET public_key_jwk = CAST(:k AS jsonb), public_key_thumbprint = :t WHERE device_uuid = CAST(:u AS uuid)")
                .bind("k", jwk.toJSONString()).bind("t", jwk.computeThumbprint().toString()).bind("u", devPhone).execute()
            routeId = h.createQuery("SELECT id FROM app.route WHERE code = 'MIR-SR-D'").mapTo(Long::class.java).one()
            outletId = h.createQuery("SELECT min(id) FROM app.outlet WHERE route_id = :r").bind("r", routeId).mapTo(Long::class.java).one()
        }
        val keyFile = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        wiring = Wiring.production(Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to keyFile.absolutePath)), AronClock { now.get() })
    }

    @AfterAll
    fun tearDown() { wiring.database?.close(); fresh.close() }

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun count(sql: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }

    /** Sets the mode the way the portal would; the key is registered here until db adds it (docs/requests). */
    private fun mode(value: String) {
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute(
                """
                INSERT INTO app.cfg_key (key, area, kind, value_type, default_value, bounds, bounds_rule, scope_levels, risk_class, risk_rule,
                                         effect, delivery, requires_ack, future_dated_only, editor_permission, description_en)
                VALUES ('cfg.sec.record_signature_mode', 'device', 'S', 'enum', '"record"'::jsonb, '{"enum": ["off", "record", "enforce"]}'::jsonb, NULL,
                        ARRAY['global']::text[], 3, NULL, 'B', 'both', false, false, 'cfg.edit.security', 'Record signature mode (docs/19)')
                ON CONFLICT (key) DO NOTHING
                """.trimIndent(),
            )
            h.execute("INSERT INTO app.cfg_version (config_version, kind, committed_by, summary) SELECT max(config_version) + 1, 'change', (SELECT id FROM app.app_user WHERE username = 'aron.system'), 'test sig mode' FROM app.cfg_version")
            val n = modes.incrementAndGet()
            // One valid row per key and scope: the previous value ends where this one starts.
            h.execute("UPDATE app.cfg_value SET effective_to = now() - interval '1 hour' + interval '1 second' * $n WHERE key = 'cfg.sec.record_signature_mode' AND effective_to IS NULL")
            h.execute(
                "INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) " +
                    "SELECT 'cfg.sec.record_signature_mode', 'global', 0, '\"$value\"'::jsonb, now() - interval '1 hour' + interval '1 second' * $n, max(config_version), 'test' FROM app.cfg_version",
            )
        }
        now.updateAndGet { it.plusSeconds(31) } // DbServerConfig caches 30 s by the injected clock
    }

    private fun sign(msg: String): String {
        val s = Signature.getInstance("SHA256withECDSAinP1363Format").apply { initSign(keys.private); update(msg.toByteArray()) }.sign()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s)
    }

    /** One visit, a signed header type; each call is new content (captured minute and outlet open time differ). */
    private fun visit(): JsonObject {
        val n = seq.incrementAndGet()
        val at = "2027-01-03T03:%02d:00.120Z".format(n)
        val payload = Json.parseToJsonElement(
            """
            {"visit_kind":"sr_call","outlet_id":$outletId,"opened_at":"$at","sequence_no":$n,"planned":true,
             "fix":{"purpose":"visit_open","fix_status":"ok","lat":23.80,"lng":90.36,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
                    "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}},
             "geo":{"verdict":"in_range","distance_m":38.4,"radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"sale_allowed"}}
            """.trimIndent(),
        ).jsonObject
        val cu = UUID.randomUUID().toString()
        return buildJsonObject {
            put("type", "visit"); put("client_uuid", cu); put("family_uuid", cu); put("rank", 0); put("schema_version", 1)
            put("business_date", day); put("captured_at", at); put("captured_elapsed_ms", 18330000 + n); put("boot_count", 412)
            put("clock_offset_ms", -1840); put("captured_offline", true); put("route_id", routeId); put("bundle_version", "$day:3")
            put("bundle_stale", false); put("config_version", 1)
            put("payload", payload)
        }
    }

    /** The phone's record signature: aron-sig-v1, type, client_uuid, hex sha256 of JCS(record without sig). */
    private fun signed(r: JsonObject, valid: Boolean = true): JsonObject {
        val msg = listOf("aron-sig-v1", "visit", r["client_uuid"]!!.jsonPrimitive.content, DeviceProof.sha256Hex(Jcs.canonicalize(r).toByteArray())).joinToString("\n")
        return JsonObject(r + ("sig" to JsonPrimitive(sign(if (valid) msg else msg + "x"))))
    }

    private suspend fun HttpClient.send(records: List<JsonObject>): List<JsonObject> {
        val t = token ?: run {
            val r = post("/v1/auth/login") {
                contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
                setBody("""{"username":"sr1001","password":"$password","client":"app_sr","device_uuid":"$devPhone"}""")
            }
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content.also { token = it }
        }
        val batchUuid = UUID.randomUUID().toString()
        val body = buildJsonObject {
            put("batch_uuid", batchUuid); put("device_uuid", devPhone); put("schema_version", 1); put("app_version", "1.0.9+9")
            put("trigger", "manual"); put("sent_at_device", "2027-01-03T04:00:00.000Z"); put("pending_rows", 0)
            put("time_anchors", kotlinx.serialization.json.JsonArray(emptyList()))
            put("device_counts", buildJsonObject { put(day, buildJsonObject { put("visit", records.size) }) })
            put("records", kotlinx.serialization.json.JsonArray(records))
        }.toString()
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(body.toByteArray()) } }.toByteArray()
        val proof = sign(listOf("aron-proof-v1", "batch", devPhone, DeviceProof.sha256Hex(gz), batchUuid, "1").joinToString("\n"))
        val r = post("/v1/sync/batch") {
            bearerAuth(t); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9"); header("X-Device-Proof", proof)
            header("Content-Encoding", "gzip"); setBody(io.ktor.http.content.ByteArrayContent(gz, ContentType.Application.Json))
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json(r.bodyAsText())["acks"]!!.jsonArray.map { it.jsonObject }
    }

    private fun List<JsonObject>.outcomes() = map { it["status"]!!.jsonPrimitive.content + ":" + (it["code"]?.takeUnless { c -> c is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content ?: "-") }

    private fun signals(reason: String) = count(
        "SELECT count(*) FROM app.risk_signal WHERE code = 'DEVICE_INTEGRITY_FAIL' AND subject_type = 'device' AND subject_id = '$devPhone' AND evidence->>'reason' = '$reason'",
    )

    @Test
    @Order(1)
    fun withNoModeConfiguredAnUnsignedSaleIsAcceptedAndFlagged() = testApplication {
        application { aronApi(wiring) }
        assertEquals(listOf("accepted:-"), client.send(listOf(visit())).outcomes())
        assertEquals(1, signals("record_signature_missing"))
    }

    @Test
    @Order(2)
    fun offSkipsTheCheck() = testApplication {
        application { aronApi(wiring) }
        mode("off")
        fresh.db.jdbi.useHandle<Exception> { it.execute("DELETE FROM app.risk_signal WHERE code = 'DEVICE_INTEGRITY_FAIL'") }
        assertEquals(listOf("accepted:-", "accepted:-"), client.send(listOf(visit(), signed(visit(), valid = false))).outcomes())
        assertEquals(0, count("SELECT count(*) FROM app.risk_signal WHERE code = 'DEVICE_INTEGRITY_FAIL'"))
    }

    @Test
    @Order(3)
    fun recordAcceptsAMissingOrBadSignatureAndFlagsThePhone() = testApplication {
        application { aronApi(wiring) }
        mode("record")
        assertEquals(listOf("accepted:-", "accepted:-"), client.send(listOf(signed(visit(), valid = false), visit())).outcomes())
        // One signal per phone and business date; its evidence names the first reason seen.
        assertEquals(1, signals("record_signature_invalid"))
    }

    @Test
    @Order(4)
    fun enforceQuarantinesAMissingOrBadSignatureAndAcceptsAGoodOne() = testApplication {
        application { aronApi(wiring) }
        mode("enforce")
        val acks = client.send(listOf(visit(), signed(visit(), valid = false), signed(visit())))
        assertEquals(listOf("quarantined:device_integrity_failed", "quarantined:device_integrity_failed", "accepted:-"), acks.outcomes())
    }

    @Test
    @Order(5)
    fun aRowFirstSentUnsignedAndThenSignedIsTheSameRecordNotAPayloadConflict() = testApplication {
        application { aronApi(wiring) }
        mode("record")
        val v = visit()
        assertEquals(listOf("accepted:-"), client.send(listOf(v)).outcomes())
        assertEquals(listOf("duplicate:-"), client.send(listOf(signed(v))).outcomes())
        assertEquals(1, count("SELECT count(*) FROM app.visit WHERE client_uuid = '${v["client_uuid"]!!.jsonPrimitive.content}'"))
    }

    @Test
    @Order(6)
    fun aSaleQuarantinedUnderEnforceIsStoredOnceTheModeIsBackToRecord() = testApplication {
        application { aronApi(wiring) }
        mode("enforce")
        val v = visit()
        assertEquals(listOf("quarantined:device_integrity_failed"), client.send(listOf(v)).outcomes())
        assertEquals(listOf("quarantined:device_integrity_failed"), client.send(listOf(v)).outcomes(), "still enforce: terminal")
        mode("record")
        assertEquals(listOf("accepted:-"), client.send(listOf(v)).outcomes())
        val cu = v["client_uuid"]!!.jsonPrimitive.content
        assertEquals(1, count("SELECT count(*) FROM app.visit WHERE client_uuid = '$cu'"))
        assertEquals(0, count("SELECT count(*) FROM app.sync_quarantine WHERE client_uuid = '$cu' AND status = 'open'"))
        assertEquals(listOf("duplicate:-"), client.send(listOf(v)).outcomes())
    }

    @Test
    @Order(7)
    fun aRowParkedUnderTheOldFullHashIsStoredAndMarkedAcceptedOnResend() = testApplication {
        application { aronApi(wiring) }
        mode("record")
        val v = signed(visit())
        val cu = v["client_uuid"]!!.jsonPrimitive.content
        // As the registry held it before BC-53: parked, keyed by the hash of the whole envelope (sig included).
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate(
                """
                INSERT INTO app.ingest_registry (client_uuid, record_type, payload_sha256, family_uuid, status, outcome_code, business_date, user_id, device_id, first_batch_uuid, received_at, last_seen_at)
                SELECT CAST(:c AS uuid), 'visit', :h, CAST(:c AS uuid), 'parked', 'server_error', DATE '$day', u.id, d.id, gen_random_uuid(), now(), now()
                FROM app.app_user u, app.device d WHERE u.username = 'sr1001' AND d.device_uuid = CAST('$devPhone' AS uuid)
                """.trimIndent(),
            ).bind("c", cu).bind("h", Jcs.sha256(v)).execute()
        }
        assertEquals(listOf("accepted:-"), client.send(listOf(v)).outcomes())
        assertEquals("accepted", fresh.db.jdbi.withHandle<String, Exception> { h -> h.createQuery("SELECT status FROM app.ingest_registry WHERE client_uuid = CAST('$cu' AS uuid)").mapTo(String::class.java).one() })
        assertEquals(listOf("duplicate:-"), client.send(listOf(v)).outcomes(), "a third send is a duplicate, never a second insert")
    }
}
