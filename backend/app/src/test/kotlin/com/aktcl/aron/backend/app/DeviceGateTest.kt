package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.DeviceProof
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Settings
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
 * N-027 device gate on attendance and sales ingest (docs/24 s4.5, s10.4, D24-17/18), on the seed through the production
 * wiring. With `cfg.device.require_enrolled` off (the dev database) a phone that is not enrolled has its attendance and
 * sales accepted and flagged; with it on they are quarantined `device_not_enrolled` with a supervisor flag (a keyless
 * phone cannot prove its batch, so every non-telemetry record of it is held), and a resend after the gate clears stores
 * each record once under the same uuid, user and device. With `cfg.device.require_integrity` on, an enrolled phone
 * without a Play Integrity pass has its attendance and sales quarantined `device_integrity_failed`; with it off only a
 * genuine fail is flagged. Clock: Sunday 2027-01-03 10:00 Dhaka.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DeviceGateTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private lateinit var keys: KeyPair
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"
    private val day = "2027-01-03"
    private val now = AtomicReference(Instant.parse("2027-01-03T04:00:00Z"))
    private val seq = AtomicInteger(0)
    private var routeId = 0L
    private var outletId = 0L
    private val skus = mutableListOf<Pair<Long, Long>>()
    private var token: String? = null
    private var proofKey: KeyPair? = null

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate("UPDATE app.app_user SET password_hash = :h WHERE pilot").bind("h", PasswordHasher().hash(password)).execute()
            routeId = h.createQuery("SELECT id FROM app.route WHERE code = 'MIR-SR-D'").mapTo(Long::class.java).one()
            outletId = h.createQuery("SELECT min(id) FROM app.outlet WHERE route_id = :r").bind("r", routeId).mapTo(Long::class.java).one()
            skus += h.createQuery("SELECT s.id, p.amount_mtk FROM app.sku s JOIN app.sku_price p ON p.sku_id = s.id AND p.price_type = 'outlet' WHERE p.amount_mtk > 0 ORDER BY s.id LIMIT 2")
                .map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.list()
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
    private fun uuid() = UUID.randomUUID().toString()
    private fun count(sql: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }
    private fun cu(r: JsonObject) = r["client_uuid"]!!.jsonPrimitive.content

    /** A global switch the way the portal sets it: the old row ends where the new one starts; the config cache is skipped. */
    private fun set(key: String, value: Boolean) {
        // One transaction: now() is the same instant for the end of the old row and the start of the new one.
        fresh.db.jdbi.useTransaction<Exception> { h ->
            h.execute("INSERT INTO app.cfg_version (config_version, kind, committed_by, summary) SELECT max(config_version) + 1, 'change', (SELECT id FROM app.app_user WHERE username = 'aron.system'), 'test gate' FROM app.cfg_version")
            h.execute("UPDATE app.cfg_value SET effective_to = now() WHERE key = '$key' AND scope_type = 'global' AND effective_to IS NULL")
            h.execute(
                "INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) " +
                    "SELECT '$key', 'global', 0, '$value'::jsonb, now(), max(config_version), 'test' FROM app.cfg_version",
            )
        }
        now.updateAndGet { it.plusSeconds(31) } // DbServerConfig caches 30 s by the injected clock
    }

    private fun envelope(type: String, cu: String, family: String, rank: Int, payload: JsonObject, date: String = day, route: Long? = routeId): JsonObject {
        val n = seq.incrementAndGet()
        val at = Instant.parse("${date}T00:00:00Z").minusSeconds(6 * 3600).plusSeconds(3600L + n * 60L).toString()
        return buildJsonObject {
            put("type", type); put("client_uuid", cu); put("family_uuid", family); put("rank", rank); put("schema_version", 1)
            put("business_date", date); put("captured_at", at); put("captured_elapsed_ms", 18330000 + n); put("boot_count", 412)
            put("clock_offset_ms", -1840); put("captured_offline", true); route?.let { put("route_id", it) }; put("bundle_version", "$date:3")
            put("bundle_stale", false); put("config_version", 1)
            put("payload", payload)
        }
    }

    private fun attendance(kind: String, date: String = day) = uuid().let {
        val purpose = if (kind == "check_in") "attendance_in" else "attendance_out"
        envelope("attendance_event", it, it, 0, json(
            """
            {"kind":"$kind","fix":{"purpose":"$purpose","fix_status":"ok","lat":23.80,"lng":90.36,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
             "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}}}
            """.trimIndent(),
        ), date, route = null)
    }

    /** A sale family: visit, memo with two lines, visit close (the arithmetic reproduces). */
    private fun sale(): List<JsonObject> {
        val visit = uuid(); val memo = uuid(); val n = seq.incrementAndGet()
        val (s1, p1) = skus[0]; val (s2, p2) = skus[1]
        val g1 = 20 * p1; val g2 = 10 * p2; val gross = g1 + g2
        val visitPayload = json(
            """
            {"visit_kind":"sr_call","outlet_id":$outletId,"opened_at":"2027-01-03T03:41:00.120Z","sequence_no":$n,"planned":true,
             "fix":{"purpose":"visit_open","fix_status":"ok","lat":23.80,"lng":90.36,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
                    "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}},
             "geo":{"verdict":"in_range","distance_m":38.4,"radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"sale_allowed"}}
            """.trimIndent(),
        )
        val memoPayload = json(
            """
            {"visit_client_uuid":"$visit","outlet_id":$outletId,"memo_no":"sr1001-270103-${200 + n}","memo_kind":"sale","committed_at":"2027-01-03T03:43:00.120Z",
             "price_list_date":"$day","price_type":"outlet","gross_mtk":$gross,"offer_discount_mtk":0,"drp_discount_mtk":0,"qc_deduction_mtk":0,
             "round_adj_mtk":0,"net_mtk":$gross,"paid_mtk":$gross,"due_mtk":0,"is_credit":false,"line_count":2,"discount_line_count":0,"qc_line_count":0,
             "offer_version_ids":[],"rounding_mode":"half_up_paisa"}
            """.trimIndent(),
        )
        fun line(no: Int, sku: Long, qty: Int, price: Long, g: Long) = json(
            """
            {"memo_client_uuid":"$memo","line_no":$no,"sku_id":$sku,"line_kind":"sale","qty_entered":$qty,"unit_entered":"stick","pack_factor":1,"qty_base":$qty,
             "price_type":"outlet","price_valid_from":"2026-01-01","base_price_mtk":$price,"price_per_qty":1,"gross_mtk":$g}
            """.trimIndent(),
        )
        val close = json("""{"visit_client_uuid":"$visit","outcome_code":"sold","call_declined":false,"ended_at":"2027-01-03T03:43:10.000Z","is_zero_sale":false}""")
        val family = listOf(
            envelope("visit", visit, visit, 0, visitPayload),
            envelope("memo", memo, visit, 1, memoPayload),
            envelope("memo_line", uuid(), visit, 2, line(1, s1, 20, p1, g1)),
            envelope("memo_line", uuid(), visit, 2, line(2, s2, 10, p2, g2)),
            envelope("visit_close", uuid(), visit, 1, close),
        )
        // One sale minute per family (s4.5 content fingerprint), the memo committed in it.
        val at = family[0]["captured_at"]!!
        return family.map { JsonObject(it + ("captured_at" to at)) }.map { r ->
            if (r["type"]!!.jsonPrimitive.content == "memo") JsonObject(r + ("payload" to JsonObject(r["payload"]!!.jsonObject + ("committed_at" to at)))) else r
        }
    }

    private fun sign(k: KeyPair, msg: String): String {
        val s = Signature.getInstance("SHA256withECDSAinP1363Format").apply { initSign(k.private); update(msg.toByteArray()) }.sign()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s)
    }

    private suspend fun HttpClient.send(records: List<JsonObject>): List<String> {
        val t = token ?: run {
            val r = post("/v1/auth/login") {
                contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
                setBody("""{"username":"sr1001","password":"$password","client":"app_sr","device_uuid":"$devPhone"}""")
            }
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content.also { token = it }
        }
        val batchUuid = uuid()
        val body = buildJsonObject {
            put("batch_uuid", batchUuid); put("device_uuid", devPhone); put("schema_version", 1); put("app_version", "1.0.9+9")
            put("trigger", "manual"); put("sent_at_device", "2027-01-03T04:00:00.000Z"); put("pending_rows", 0)
            put("time_anchors", kotlinx.serialization.json.JsonArray(emptyList()))
            put("device_counts", buildJsonObject { put(day, buildJsonObject { put("visit", 1) }) })
            put("records", kotlinx.serialization.json.JsonArray(records))
        }.toString()
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(body.toByteArray()) } }.toByteArray()
        val r = post("/v1/sync/batch") {
            bearerAuth(t); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9")
            proofKey?.let { header("X-Device-Proof", sign(it, listOf("aron-proof-v1", "batch", devPhone, DeviceProof.sha256Hex(gz), batchUuid, "1").joinToString("\n"))) }
            header("Content-Encoding", "gzip"); setBody(io.ktor.http.content.ByteArrayContent(gz, ContentType.Application.Json))
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json(r.bodyAsText())["acks"]!!.jsonArray.map { a ->
            val o = a.jsonObject
            o["status"]!!.jsonPrimitive.content + ":" + (o["code"]?.takeUnless { c -> c is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content ?: "-")
        }
    }

    /** The device's flag for [day] (one per device and business date). */
    private fun flag(): Triple<Long, String?, Int>? = fresh.db.jdbi.withHandle<Triple<Long, String?, Int>?, Exception> { h ->
        h.createQuery("SELECT score::bigint, evidence->>'gate', severity FROM app.risk_signal WHERE code = 'DEVICE_INTEGRITY_FAIL' AND subject_type = 'device' AND subject_id = '$devPhone' AND business_date = DATE '$day'")
            .map { rs, _ -> Triple(rs.getLong(1), rs.getString(2), rs.getInt(3)) }.findOne().orElse(null)
    }

    private fun clearFlags() = fresh.db.jdbi.useHandle<Exception> { it.execute("DELETE FROM app.risk_signal WHERE code = 'DEVICE_INTEGRITY_FAIL'") }

    @Test
    @Order(1)
    fun withTheGateOffAPhoneThatIsNotEnrolledIsAcceptedAndFlagged() = testApplication {
        application { aronApi(wiring) }
        // The dev database: require_enrolled false; the seed phone has a placeholder key and no enrolment.
        assertEquals(List(5) { "accepted:-" } + "accepted:-", client.send(sale() + attendance("check_in")))
        assertEquals(Triple(30L, "device_not_enrolled", 4), flag())
    }

    @Test
    @Order(2)
    fun withTheGateOnEveryRecordOfAKeylessPhoneIsHeldAndAResendAfterTheGateClearsStoresItOnce() = testApplication {
        application { aronApi(wiring) }
        clearFlags()
        set("cfg.device.require_enrolled", true)
        val records = sale() + attendance("check_out")
        assertEquals(List(6) { "quarantined:device_not_enrolled" }, client.send(records), "held, never rejected")
        assertEquals(Triple(30L, "device_not_enrolled", 4), flag())
        val memo = cu(records[1])
        assertEquals(0, count("SELECT count(*) FROM app.memo WHERE client_uuid = '$memo'"))
        assertEquals(6, count("SELECT count(*) FROM app.sync_quarantine WHERE code = 'device_not_enrolled' AND status = 'open'"))
        assertEquals(List(6) { "quarantined:device_not_enrolled" }, client.send(records), "still on: held again, nothing stored")
        assertEquals(0, count("SELECT count(*) FROM app.memo WHERE client_uuid = '$memo'"))

        set("cfg.device.require_enrolled", false)
        assertEquals(List(6) { "accepted:-" }, client.send(records), "released on the resend")
        assertEquals(1, count("SELECT count(*) FROM app.memo WHERE client_uuid = '$memo'"))
        assertEquals(2, count("SELECT count(*) FROM app.memo_line WHERE memo_client_uuid = '$memo'"))
        assertEquals(0, count("SELECT count(*) FROM app.sync_quarantine WHERE code = 'device_not_enrolled' AND status = 'open'"))
        // The registry row of every released record is accepted under the same uuid, user and device (no daily digest re-send).
        val ids = records.joinToString(",") { "'${cu(it)}'" }
        assertEquals(6, count(
            """
            SELECT count(*) FROM app.ingest_registry r JOIN app.app_user u ON u.id = r.user_id JOIN app.device d ON d.id = r.device_id
             WHERE r.client_uuid IN ($ids) AND r.status = 'accepted' AND u.username = 'sr1001' AND d.device_uuid = '$devPhone'
            """.trimIndent(),
        ))
        assertEquals(List(6) { "duplicate:-" }, client.send(records))
        assertEquals(1, count("SELECT count(*) FROM app.memo WHERE client_uuid = '$memo'"))
    }

    @Test
    @Order(3)
    fun withIntegrityRequiredAnEnrolledPhoneWithoutAPassHasItsAttendanceAndSalesHeld() = testApplication {
        application { aronApi(wiring) }
        clearFlags()
        // Enrol the dev phone for real: a usable key and an enrolment token.
        val jwk = ECKey.Builder(Curve.P_256, keys.public as ECPublicKey).build()
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute(
                """
                INSERT INTO app.enrolment_token (token_sha256, token_prefix, flavour, lockdown_level, max_uses, used_count, expires_at, created_by)
                SELECT decode(repeat('ab', 32), 'hex'), 'abcdef', 'sr', 'dev', 1, 1, now() + interval '1 hour', id FROM app.app_user WHERE username = 'aron.system'
                """.trimIndent(),
            )
            h.createUpdate(
                "UPDATE app.device SET public_key_jwk = CAST(:k AS jsonb), public_key_thumbprint = :t, enrolment_token_id = (SELECT max(id) FROM app.enrolment_token) WHERE device_uuid = CAST(:u AS uuid)",
            ).bind("k", jwk.toJSONString()).bind("t", jwk.computeThumbprint().toString()).bind("u", devPhone).execute()
        }
        proofKey = keys
        set("cfg.device.require_enrolled", true)
        set("cfg.device.require_integrity", true)
        val yesterday = "2027-01-02"
        val records = sale() + attendance("check_in", yesterday)
        // Verdict unevaluated: the memo (its lines with it) and the check-in are held; the visit and its close are not gated.
        val acks = client.send(records)
        assertEquals(listOf("accepted:-", "quarantined:device_integrity_failed", "quarantined:device_integrity_failed", "quarantined:device_integrity_failed", "accepted:-", "quarantined:device_integrity_failed"), acks)
        assertEquals(Triple(30L, "play_integrity_unevaluated", 4), flag())

        // The phone passes Play Integrity; the resend stores the sale and the check-in once.
        fresh.db.jdbi.useHandle<Exception> { it.execute("UPDATE app.device SET integrity_verdict = 'pass', integrity_checked_at = now() WHERE device_uuid = '$devPhone'") }
        assertEquals(listOf("duplicate:-", "accepted:-", "accepted:-", "accepted:-", "duplicate:-", "accepted:-"), client.send(records))
        assertEquals(1, count("SELECT count(*) FROM app.memo WHERE client_uuid = '${cu(records[1])}'"))
        assertEquals(1, count("SELECT count(*) FROM app.attendance_event WHERE client_uuid = '${cu(records[5])}'"))

        // Integrity not required: a genuine fail is accepted and flagged; an unevaluated phone is not flagged.
        set("cfg.device.require_integrity", false)
        clearFlags()
        fresh.db.jdbi.useHandle<Exception> { it.execute("UPDATE app.device SET integrity_verdict = 'unevaluated' WHERE device_uuid = '$devPhone'") }
        assertEquals(listOf("accepted:-"), client.send(listOf(attendance("check_out", yesterday))))
        assertEquals(null, flag()?.second, "only the unsigned-record flag, no gate flag")
        fresh.db.jdbi.useHandle<Exception> { it.execute("UPDATE app.device SET integrity_verdict = 'fail' WHERE device_uuid = '$devPhone'") }
        assertEquals(List(5) { "accepted:-" }, client.send(sale()))
        assertEquals(Triple(30L, "play_integrity_fail", 4), flag())
    }
}
