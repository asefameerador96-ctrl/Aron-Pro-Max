package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Settings
import com.aktcl.aron.rules.Geo
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * F-SYS-090 (D-584) end to end: with `cfg.calendar.window_unit = working_days` (registered here as the db request asks;
 * BC-74) the backdate window counts selling days, so after a ten-day break the last working evening's rows are accepted,
 * not quarantined; the calendar ceiling (registry retention 45 - 30 = 15 days) still quarantines an older row.
 * Fixed clock Sunday 2027-01-03; global holidays 2026-12-24..2027-01-02.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkingDayWindowTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val now = Instant.parse("2027-01-03T06:00:00Z")
    private val clock = AronClock { now }
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"
    private var r1 = 0L
    private var outlet = 0L
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
            val sr = h.createQuery("SELECT id FROM app.app_user WHERE username = 'sr1001'").mapTo(Long::class.java).one()
            r1 = h.createQuery("SELECT min(route_id) FROM app.route_assignment WHERE user_id = :u").bind("u", sr).mapTo(Long::class.java).one()
            outlet = h.createQuery("SELECT id FROM app.outlet WHERE route_id = :r AND status = 'active' ORDER BY id LIMIT 1").bind("r", r1).mapTo(Long::class.java).one()
            h.execute(
                """
                INSERT INTO app.cfg_key (key, area, kind, value_type, default_value, bounds, bounds_rule, scope_levels, risk_class, risk_rule,
                                         effect, delivery, requires_ack, future_dated_only, editor_permission, description_en)
                VALUES ('cfg.calendar.window_unit', 'calendar', 'S', 'enum', '"calendar"'::jsonb, '{"enum": ["calendar", "working_days"]}'::jsonb, NULL,
                        ARRAY['global']::text[], 2, NULL, 'B', 'both', false, false, 'cfg.edit.ops', 'Working-day windows (F-SYS-090)')
                """.trimIndent(),
            )
            h.execute(
                """
                INSERT INTO app.cfg_version (config_version, kind, committed_by, summary)
                SELECT max(config_version) + 1, 'change', (SELECT id FROM app.app_user WHERE username = 'aron.system'), 'working days' FROM app.cfg_version
                """.trimIndent(),
            )
            h.execute(
                """
                INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason)
                SELECT 'cfg.calendar.window_unit', 'global', 0, '"working_days"'::jsonb, now() - interval '1 day', max(config_version), 'test'
                FROM app.cfg_version
                """.trimIndent(),
            )
            h.execute(
                """
                INSERT INTO app.calendar_holiday (date, scope_type, scope_id, kind, selling_day, name_en)
                SELECT d::date, 'global', 0, 'holiday', false, 'Long break' FROM generate_series(DATE '2026-12-24', DATE '2027-01-02', interval '1 day') d
                """.trimIndent(),
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

    private suspend fun HttpClient.login(): String = token ?: run {
        val r = post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
            setBody("""{"username":"sr1001","password":"$password","client":"app_sr","device_uuid":"$devPhone"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content.also { token = it }
    }

    private fun visitOn(date: String): JsonObject {
        val cu = UUID.randomUUID().toString()
        return buildJsonObject {
            put("type", "visit"); put("client_uuid", cu); put("family_uuid", cu); put("rank", 0); put("schema_version", 1)
            put("business_date", date); put("captured_at", "${date}T05:00:00.000Z")
            put("captured_elapsed_ms", 18330000); put("boot_count", 412)
            put("clock_offset_ms", 0); put("captured_offline", true); put("route_id", r1); put("bundle_version", "$date:3")
            put("bundle_stale", false); put("config_version", 1)
            put("payload", json(
                """
                {"visit_kind":"sr_call","outlet_id":$outlet,"opened_at":"${date}T05:00:00.000Z","sequence_no":1,"planned":true,
                 "fix":{"purpose":"visit_open","fix_status":"ok","lat":23.80,"lng":90.36,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
                        "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}},
                 "geo":{"verdict":"in_range","distance_m":10.0,"radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"sale_allowed"}}
                """.trimIndent(),
            ))
        }
    }

    private suspend fun HttpClient.status(record: JsonObject): String {
        val body = buildJsonObject {
            put("batch_uuid", UUID.randomUUID().toString()); put("device_uuid", devPhone); put("schema_version", 1); put("app_version", "1.0.9+9")
            put("trigger", "manual"); put("sent_at_device", "2027-01-03T05:30:00.000Z"); put("pending_rows", 0)
            put("time_anchors", JsonArray(emptyList())); put("device_counts", buildJsonObject {}); put("records", JsonArray(listOf(record)))
        }.toString()
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(body.toByteArray()) } }.toByteArray()
        val t = login()
        val r = post("/v1/sync/batch") {
            bearerAuth(t); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9"); header("Content-Encoding", "gzip")
            setBody(io.ktor.http.content.ByteArrayContent(gz, ContentType.Application.Json))
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val a = json(r.bodyAsText())["acks"]!!.jsonArray.single().jsonObject
        return a["status"]!!.jsonPrimitive.content + (a["code"]?.let { c -> (c as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.let { ":" + it.content } } ?: "")
    }

    @Test
    fun theLastWorkingEveningBeforeALongBreakIsInsideTheWindow() = testApplication {
        application { aronApi(wiring) }
        // 2026-12-23 is 11 calendar days back (outside the 7-day calendar rule) but 1 working day back.
        assertEquals("accepted", client.status(visitOn("2026-12-23")))
        // 2026-12-18 is 16 calendar days back: beyond the ceiling (15), quarantined as before.
        assertEquals("quarantined:business_date_out_of_window", client.status(visitOn("2026-12-18")))
    }
}
