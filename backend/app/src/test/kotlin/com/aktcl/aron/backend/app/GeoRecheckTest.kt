package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Settings
import com.aktcl.aron.backend.sync.GeoRecheck
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
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * F-SYS-012 through POST /v1/sync/batch on the seed: the server re-checks every visit with the shared verdict function
 * against the outlet pin and the radius in force for the visit's business date, stores `server_verdict` beside the
 * phone's, and never lets a mocked fix be in range, whatever the phone claims.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GeoRecheckTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val now = Instant.parse("2027-01-03T06:00:00Z")
    private val clock = AronClock { now }
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"
    private val day = "2027-01-03"
    private val seq = AtomicInteger(0)
    private var r1 = 0L
    private var outletA = 0L // radius 100 on the day, 200 from the next day
    private var outletB = 0L // pin moved after the visits
    private val pinLat = 23.80
    private val pinLng = 90.36
    private var token: String? = null

    /** Metres to degrees of latitude on the same sphere as the haversine (exact along a meridian). */
    private fun north(m: Double) = pinLat + m / (Geo.EARTH_RADIUS_M * PI / 180.0)

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
            val outlets = h.createQuery("SELECT id FROM app.outlet WHERE route_id = :r AND status = 'active' ORDER BY id LIMIT 2").bind("r", r1).mapTo(Long::class.java).list()
            outletA = outlets[0]; outletB = outlets[1]
            for (o in outlets) {
                h.execute("UPDATE app.outlet SET lat = ?, lng = ?, location_basis = 'master' WHERE id = ?", pinLat, pinLng, o)
                h.execute("INSERT INTO app.outlet_location_history (outlet_id, lat, lng, source, valid_from) VALUES (?, ?, ?, 'web_edit', TIMESTAMPTZ '2027-01-01 00:00:00+00')", o, pinLat, pinLng)
            }
            // outletB's pin moves 1 km north at 05:00Z, after every visit of the day was captured (03:xx).
            h.execute("INSERT INTO app.outlet_location_history (outlet_id, lat, lng, source, valid_from) VALUES (?, ?, ?, 'web_edit', TIMESTAMPTZ '2027-01-03 05:00:00+00')", outletB, north(1000.0), pinLng)
        }
        // outletA: 100 m for the whole of 2027-01-03 (Dhaka), 200 m from 2027-01-04 00:00 Dhaka (= 2027-01-03T18:00Z).
        commitCfg("cfg.geo.radius_m", "'outlet'", "$outletA", "100", "TIMESTAMPTZ '2027-01-01 00:00:00+00'", "TIMESTAMPTZ '2027-01-03 18:00:00+00'")
        commitCfg("cfg.geo.radius_m", "'outlet'", "$outletA", "200", "TIMESTAMPTZ '2027-01-03 18:00:00+00'", null)
        commitCfg("cfg.geo.radius_m", "'outlet'", "$outletB", "100", "TIMESTAMPTZ '2027-01-01 00:00:00+00'", null)
        val key = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        wiring = Wiring.production(Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to key.absolutePath)), clock)
    }

    @AfterAll
    fun tearDown() { wiring.database?.close(); fresh.close() }

    private fun commitCfg(key: String, scopeTypeSql: String, scopeIdSql: String, valueJson: String, fromSql: String, toSql: String?) {
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute(
                """
                INSERT INTO app.cfg_version (config_version, kind, committed_by, summary)
                SELECT max(config_version) + 1, 'change', (SELECT id FROM app.app_user WHERE username = 'aron.system'), 'geo recheck test' FROM app.cfg_version
                """.trimIndent(),
            )
            h.execute(
                """
                INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, effective_to, config_version, reason)
                SELECT '$key', $scopeTypeSql, $scopeIdSql, '$valueJson'::jsonb, $fromSql, ${toSql ?: "NULL"}, max(config_version), 'geo recheck test'
                FROM app.cfg_version
                """.trimIndent(),
            )
        }
    }

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun uuid() = UUID.randomUUID().toString()

    private data class Server(val verdict: String?, val distance: Double?, val radius: Int?, val checkedAt: Instant?)

    private fun server(clientUuid: String): Server = fresh.db.jdbi.withHandle<Server, Exception> { h ->
        h.createQuery("SELECT server_verdict, server_distance_m, server_radius_m, server_checked_at FROM app.visit WHERE client_uuid = CAST(:c AS uuid)")
            .bind("c", clientUuid).map { rs, _ ->
                Server(rs.getString(1), rs.getObject(2) as Double?, rs.getObject(3) as Int?, rs.getObject(4, java.time.OffsetDateTime::class.java)?.toInstant())
            }.one()
    }

    private suspend fun HttpClient.login(): String = token ?: run {
        val r = post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
            setBody("""{"username":"sr1001","password":"$password","client":"app_sr","device_uuid":"$devPhone"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content.also { token = it }
    }

    /** A visit whose fix is [metresNorth] of the pin; the phone's own verdict is whatever the caller claims. */
    private fun visit(
        outlet: Long, metresNorth: Double, isMock: Boolean = false, phoneVerdict: String = "in_range", action: String = "sale_allowed",
        capturedAt: String? = null,
    ): JsonObject {
        val n = seq.incrementAndGet()
        val cu = uuid()
        return buildJsonObject {
            put("type", "visit"); put("client_uuid", cu); put("family_uuid", cu); put("rank", 0); put("schema_version", 1)
            put("business_date", day); put("captured_at", capturedAt ?: "2027-01-03T03:%02d:%02d.000Z".format(n / 60, n % 60))
            put("captured_elapsed_ms", 18330000 + n); put("boot_count", 412)
            put("clock_offset_ms", 0); put("captured_offline", true); put("route_id", r1); put("bundle_version", "$day:3")
            put("bundle_stale", false); put("config_version", 1)
            put("payload", json(
                """
                {"visit_kind":"sr_call","outlet_id":$outlet,"opened_at":"2027-01-03T03:41:00.120Z","sequence_no":$n,"planned":true,
                 "fix":{"purpose":"visit_open","fix_status":"ok","lat":${north(metresNorth)},"lng":$pinLng,"accuracy_m":12.0,"provider":"fused","is_mock":$isMock,"reused":false,
                        "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}},
                 "geo":{"verdict":"$phoneVerdict","distance_m":$metresNorth,"radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"$action"}}
                """.trimIndent(),
            ))
        }
    }

    private fun uuidOf(rec: JsonObject) = rec["client_uuid"]!!.jsonPrimitive.content

    private suspend fun HttpClient.send(records: List<JsonObject>, batchUuid: String = uuid(), want: String = "accepted"): JsonObject {
        val body = buildJsonObject {
            put("batch_uuid", batchUuid); put("device_uuid", devPhone); put("schema_version", 1); put("app_version", "1.0.9+9")
            put("trigger", "manual"); put("sent_at_device", "2027-01-03T04:00:00.000Z"); put("pending_rows", 0)
            put("time_anchors", JsonArray(emptyList())); put("device_counts", buildJsonObject {}); put("records", JsonArray(records))
        }.toString()
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(body.toByteArray()) } }.toByteArray()
        val t = login()
        val r = post("/v1/sync/batch") {
            bearerAuth(t); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9"); header("Content-Encoding", "gzip")
            setBody(io.ktor.http.content.ByteArrayContent(gz, ContentType.Application.Json))
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val b = json(r.bodyAsText())
        b["acks"]!!.jsonArray.forEach { a -> assertEquals(want, a.jsonObject["status"]!!.jsonPrimitive.content, a.toString()) }
        return b
    }

    @Test
    fun eightyMetresIsInRangeAndOneHundredThirtyIsNotAtOneHundredMetres() = testApplication {
        application { aronApi(wiring) }
        val in80 = visit(outletA, 80.0)
        // The phone claims in range at 130 m (an old bundle, or a tampered client): the server disagrees and keeps both.
        val out130 = visit(outletA, 130.0)
        client.send(listOf(in80, out130))
        val a = server(uuidOf(in80))
        assertEquals("in_range", a.verdict)
        assertEquals(100, a.radius, "the radius in force on the visit's business date, not the next day's 200")
        assertEquals(80.0, a.distance!!, 0.01)
        assertEquals(now, a.checkedAt)
        val b = server(uuidOf(out130))
        assertEquals("out_of_range", b.verdict, "130 m from the pin with a 100 m radius")
        assertEquals(130.0, b.distance!!, 0.01)
        // The phone's verdict is kept as it came.
        assertEquals("in_range", fresh.db.jdbi.withHandle<String, Exception> { h -> h.createQuery("SELECT verdict FROM app.visit WHERE client_uuid = CAST('${uuidOf(out130)}' AS uuid)").mapTo(String::class.java).one() })
    }

    @Test
    fun aMockedFixIsNeverInRangeWhateverThePhoneClaims() = testApplication {
        application { aronApi(wiring) }
        // At the pin itself, flagged mock, but the phone claims in_range / sale_allowed (a hooked client).
        val lying = visit(outletA, 0.0, isMock = true)
        // The phone said mocked although the fix's flag is false: the server keeps it mocked too.
        val saidMocked = visit(outletA, 0.0, isMock = false, phoneVerdict = "mocked", action = "force_sale")
        client.send(listOf(lying, saidMocked))
        assertEquals("mocked", server(uuidOf(lying)).verdict)
        assertEquals("mocked", server(uuidOf(saidMocked)).verdict)
        assertEquals(0L, fresh.db.jdbi.withHandle<Long, Exception> { h ->
            h.createQuery("SELECT count(*) FROM app.visit WHERE (fix_is_mock OR verdict = 'mocked') AND server_verdict = 'in_range'").mapTo(Long::class.java).one()
        }, "mock invariant over every stored visit")
    }

    @Test
    fun theOutletPinInForceAtCaptureIsUsedNotALaterMove() = testApplication {
        application { aronApi(wiring) }
        // outletB's pin moved 1 km north at 05:00Z; a visit captured at 03:xx next to the old pin is in range.
        val v = visit(outletB, 50.0)
        client.send(listOf(v))
        assertEquals("in_range", server(uuidOf(v)).verdict)
        assertEquals(50.0, server(uuidOf(v)).distance!!, 0.01)
    }

    @Test
    fun aPhoneClockOutsideTheBusinessDateIsJudgedOnThatDate() {
        val bd = LocalDate.parse(day)
        // Dhaka 2027-01-03 runs from 2027-01-02T18:00Z to 2027-01-03T18:00Z.
        assertEquals(Instant.parse("2027-01-02T18:00:00Z"), GeoRecheck.judgedAt(Instant.parse("2027-01-01T09:00:00Z"), bd))
        assertEquals(Instant.parse("2027-01-03T17:59:59.999Z"), GeoRecheck.judgedAt(Instant.parse("2027-01-03T20:00:00Z"), bd))
        assertEquals(Instant.parse("2027-01-03T03:00:00Z"), GeoRecheck.judgedAt(Instant.parse("2027-01-03T03:00:00Z"), bd))
    }

    @Test
    fun aReplayedVisitChangesNothingAndTheSweepFillsAMissingVerdict() = testApplication {
        application { aronApi(wiring) }
        val v = visit(outletA, 130.0)
        val batch = uuid()
        client.send(listOf(v), batch)
        val first = server(uuidOf(v))
        assertEquals("out_of_range", first.verdict)
        // Same batch again (replay) and the same record in a new batch (duplicate): nothing changes.
        client.send(listOf(v), batch) // replay of the stored response: the ack stays accepted
        client.send(listOf(v), want = "duplicate")
        assertEquals(first, server(uuidOf(v)))
        assertEquals(1L, fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT count(*) FROM app.visit WHERE client_uuid = CAST('${uuidOf(v)}' AS uuid)").mapTo(Long::class.java).one() })

        // A verdict lost to a failure is filled by the worker's sweep.
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("UPDATE app.visit SET server_verdict = NULL, server_distance_m = NULL, server_radius_m = NULL, server_max_accuracy_m = NULL, server_checked_at = NULL WHERE client_uuid = CAST(? AS uuid)", uuidOf(v))
        }
        assertNull(server(uuidOf(v)).verdict)
        val swept = fresh.db.jdbi.inTransaction<Int, Exception> { h -> GeoRecheck.sweep(h, LocalDate.parse(day), 7, 1000, now) }
        assertNotEquals(0, swept)
        assertEquals("out_of_range", server(uuidOf(v)).verdict)
        assertEquals(0L, fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT count(*) FROM app.visit WHERE server_verdict IS NULL").mapTo(Long::class.java).one() })
    }
}
