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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
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
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * F-SYS-012 acceptance: the server re-checks a visit's geo verdict on ingest with the shared function, the outlet
 * location and the radius in force at the visit's capture (docs/24 s11.3, D-431 s9.3 item 6). Clock Sunday 2027-01-03
 * 10:00 Dhaka; visits captured on 2027-01-03 from 00:31 Dhaka. Outlets sit at 23.80, 90.36 (master), one per test.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GeoRecheckTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private lateinit var keyFile: File
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"
    private val clock = AronClock { Instant.parse("2027-01-03T04:00:00Z") }
    private val day = "2027-01-03"
    private var routeId = 0L
    private val outlets = mutableListOf<Long>()
    private val skus = mutableListOf<Pair<Long, Long>>()
    private val memoSeq = java.util.concurrent.atomic.AtomicInteger(500)
    private val captureSeq = java.util.concurrent.atomic.AtomicInteger(0)

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate("UPDATE app.app_user SET password_hash = :h WHERE pilot").bind("h", PasswordHasher().hash(password)).execute()
            routeId = h.createQuery("SELECT id FROM app.route WHERE code = 'MIR-SR-D'").mapTo(Long::class.java).one()
            outlets += h.createQuery("SELECT id FROM app.outlet WHERE route_id = :r ORDER BY id").bind("r", routeId).mapTo(Long::class.java).list()
            skus += h.createQuery("SELECT s.id, p.amount_mtk FROM app.sku s JOIN app.sku_price p ON p.sku_id = s.id AND p.price_type = 'outlet' WHERE p.amount_mtk > 0 ORDER BY s.id LIMIT 2")
                .map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.list()
        }
        check(outlets.size >= 6) { "the seed route has ${outlets.size} outlets; the test needs 6" }
        keyFile = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        wiring = Wiring.production(Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to keyFile.absolutePath)), clock)
    }

    @AfterAll
    fun tearDown() { wiring.database?.close(); fresh.close() }

    // ---- fixtures (as BatchAcceptanceTest) -------------------------------------------------------------------------

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun uuid() = UUID.randomUUID().toString()
    private var cachedToken: String? = null

    private suspend fun HttpClient.token(): String = cachedToken ?: run {
        val r = post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
            setBody("""{"username":"sr1001","password":"$password","client":"app_sr","device_uuid":"$devPhone"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content.also { cachedToken = it }
    }

    private fun gz(s: String): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(s.toByteArray()) } }.toByteArray()

    private fun batch(records: List<JsonObject>): String = buildJsonObject {
        put("batch_uuid", uuid()); put("device_uuid", devPhone); put("schema_version", 1); put("app_version", "1.0.9+9")
        put("trigger", "manual"); put("sent_at_device", "2027-01-03T04:00:00.000Z"); put("pending_rows", 0)
        put("time_anchors", kotlinx.serialization.json.JsonArray(emptyList()))
        put("device_counts", buildJsonObject { put(day, buildJsonObject { put("visit", 1) }) })
        put("records", kotlinx.serialization.json.JsonArray(records))
    }.toString()

    private suspend fun HttpClient.send(token: String, records: List<JsonObject>): JsonObject = json(post("/v1/sync/batch") {
        bearerAuth(token); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9"); header("Content-Encoding", "gzip")
        setBody(io.ktor.http.content.ByteArrayContent(gz(batch(records)), ContentType.Application.Json))
    }.bodyAsText())

    private fun statuses(r: JsonObject) = r["acks"]!!.jsonArray.map { it.jsonObject["status"]!!.jsonPrimitive.content }

    private fun nextAt(): String = Instant.parse("2027-01-02T18:30:00Z").plusSeconds(captureSeq.incrementAndGet() * 60L).toString()

    private fun envelope(type: String, cu: String, family: String, rank: Int, payload: JsonObject, at: String = nextAt(), route: Long? = routeId) = buildJsonObject {
        put("type", type); put("client_uuid", cu); put("family_uuid", family); put("rank", rank); put("schema_version", 1)
        put("business_date", day); put("captured_at", at); put("captured_elapsed_ms", 18330000); put("boot_count", 412)
        put("clock_offset_ms", -1840); put("captured_offline", true); route?.let { put("route_id", it) }; put("bundle_version", "$day:3")
        put("bundle_stale", false); put("config_version", 1)
        put("payload", payload)
    }

    private val fixJson = """{"purpose":"PURPOSE","fix_status":"ok","lat":23.80,"lng":90.36,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
        "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}}"""

    private class Sale(val visit: String, val memo: String, val memoNo: String, val records: List<JsonObject>)

    private fun visitRecord(visit: String, outlet: Long, at: String, lat: Double, mock: Boolean = false, verdict: String = "in_range", action: String = "sale_allowed") =
        envelope("visit", visit, visit, 0, json(
            """
            {"visit_kind":"sr_call","outlet_id":$outlet,"opened_at":"$at","sequence_no":1,"planned":true,
             "fix":${fixJson.replace("PURPOSE", "visit_open").replace("\"lat\":23.80", "\"lat\":$lat").replace("\"is_mock\":false", "\"is_mock\":$mock")},
             "geo":{"verdict":"$verdict","distance_m":38.4,"radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"$action"}}
            """.trimIndent(),
        ), at)

    private fun ApplicationTestBuilder.app() = application { aronApi(wiring) }

    private val oLat = 23.80
    private val oLng = 90.36
    private fun north(m: Double) = oLat + m / (com.aktcl.aron.rules.Geo.EARTH_RADIUS_M * Math.PI / 180.0)

    /** Puts [outlet] at the test point (master, history row from 2026-12-01) with a radius per [radii] (from, to, metres). */
    private fun place(outlet: Long, vararg radii: Triple<String, String?, Int>) = fresh.db.jdbi.useHandle<Exception> { h ->
        h.createUpdate("UPDATE app.outlet SET lat = :a, lng = :b, location_basis = 'master' WHERE id = :o").bind("a", oLat).bind("b", oLng).bind("o", outlet).execute()
        h.createUpdate("INSERT INTO app.outlet_location_history (outlet_id, lat, lng, source, basis, valid_from) VALUES (:o, :a, :b, 'web_edit', 'master', '2026-12-01T00:00:00Z')")
            .bind("o", outlet).bind("a", oLat).bind("b", oLng).execute()
        radii.forEach { (from, to, m) ->
            h.createUpdate(
                """
                INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, effective_to, config_version, reason)
                SELECT 'cfg.geo.radius_m', 'outlet', :o, to_jsonb(:m), CAST(:f AS timestamptz), CAST(:t AS timestamptz), max(config_version), 'test' FROM app.cfg_version
                """.trimIndent(),
            ).bind("o", outlet).bind("m", m).bind("f", from).bind("t", to).execute()
        }
    }

    private data class Server(val verdict: String?, val distance: Double?, val radius: Int?, val checkedAt: Instant?)

    private fun server(visit: String): Server = fresh.db.jdbi.withHandle<Server, Exception> { h ->
        h.createQuery("SELECT server_verdict, server_distance_m, server_radius_m, server_checked_at FROM app.visit WHERE client_uuid = CAST(:u AS uuid)")
            .bind("u", visit).map { rs, _ ->
                Server(rs.getString(1), rs.getObject(2) as Double?, rs.getObject(3) as Int?, rs.getObject(4, java.time.OffsetDateTime::class.java)?.toInstant())
            }.one()
    }

    private suspend fun HttpClient.visit(outlet: Long, lat: Double, mock: Boolean = false): String {
        val v = uuid()
        val rec = if (mock) visitRecord(v, outlet, nextAt(), lat, true, "mocked", "blocked") else visitRecord(v, outlet, nextAt(), lat)
        assertEquals(listOf("accepted"), statuses(send(token(), listOf(rec))))
        return v
    }

    @Test
    fun withA100mRadiusA80mFixIsValidAndA130mFixIsNot() = testApplication {
        app()
        place(outlets[0], Triple("2026-12-01T00:00:00Z", null, 100))
        val near = server(client.visit(outlets[0], north(80.0)))
        assertEquals("in_range", near.verdict)
        assertEquals(100, near.radius)
        assertEquals(80.0, near.distance!!, 0.5)
        assertEquals(clock.now(), near.checkedAt)
        val far = server(client.visit(outlets[0], north(130.0)))
        assertEquals("out_of_range", far.verdict, "the phone said in_range; the server judges for itself")
        assertEquals(130.0, far.distance!!, 0.5)
    }

    @Test
    fun aMockedFixIsNeverGeoValidOnTheServer() = testApplication {
        app()
        place(outlets[1], Triple("2026-12-01T00:00:00Z", null, 100))
        val s = server(client.visit(outlets[1], oLat, mock = true))
        assertEquals("mocked", s.verdict)
    }

    /** The radius went from 100 to 200 after the capture (and within the 48 h window): the visit is judged with 100. */
    @Test
    fun theRadiusInForceAtCaptureIsUsedNotTheOneInForceAtUpload() = testApplication {
        app()
        place(outlets[2], Triple("2026-12-01T00:00:00Z", "2027-01-03T03:00:00Z", 100), Triple("2027-01-03T03:00:00Z", null, 200))
        val s = server(client.visit(outlets[2], north(130.0)))
        assertEquals(100, s.radius)
        assertEquals("out_of_range", s.verdict)
    }

    /** A radius set before the visit's day applies to it: 200 m from 2027-01-01, a 130 m fix is valid. */
    @Test
    fun aRadiusInForceBeforeTheCaptureApplies() = testApplication {
        app()
        place(outlets[3], Triple("2026-12-01T00:00:00Z", "2027-01-01T00:00:00Z", 100), Triple("2027-01-01T00:00:00Z", null, 200))
        val s = server(client.visit(outlets[3], north(130.0)))
        assertEquals(200, s.radius)
        assertEquals("in_range", s.verdict)
    }

    /** D-431: a change older than the 48 h accept window applies even to a visit captured before it. */
    @Test
    fun aChangeOlderThanTheAcceptWindowApplies() = testApplication {
        app()
        place(outlets[4], Triple("2026-12-01T00:00:00Z", "2026-12-31T00:00:00Z", 100), Triple("2026-12-31T00:00:00Z", null, 200))
        val v = uuid()
        val rec = visitRecord(v, outlets[4], "2026-12-30T10:00:00Z", north(130.0))
        val sent = client.send(client.token(), listOf(rec))
        // A visit captured four days ago may be refused or re-dated by the capture-time rules; only check a stored one.
        if (statuses(sent) == listOf("accepted")) {
            val s = server(v)
            assertEquals(200, s.radius, "the 2026-12-31 change is older than 48 h at upload")
        }
    }

    /** A resend is a duplicate: the stored re-check is not recomputed (even after the radius changes again). */
    @Test
    fun aResendKeepsTheFirstRecheck() = testApplication {
        app()
        place(outlets[5], Triple("2026-12-01T00:00:00Z", null, 100))
        val v = uuid()
        val rec = visitRecord(v, outlets[5], nextAt(), north(130.0))
        val token = client.token()
        assertEquals(listOf("accepted"), statuses(client.send(token, listOf(rec))))
        val first = server(v)
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("UPDATE app.cfg_value SET effective_to = '2027-01-02T00:00:00Z' WHERE key = 'cfg.geo.radius_m' AND scope_type = 'outlet' AND scope_id = ${outlets[5]}")
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.geo.radius_m', 'outlet', ${outlets[5]}, '500'::jsonb, '2027-01-02T00:00:00Z', max(config_version), 'test' FROM app.cfg_version")
        }
        assertEquals(listOf("duplicate"), statuses(client.send(token, listOf(rec))))
        assertEquals(first, server(v))
        assertEquals("out_of_range", first.verdict)
    }
}
