package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Settings
import com.aktcl.aron.rules.Geo
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F-API-019 GET /v1/outlets/nearby through the production wiring on the seed (D24-77): active outlets with confirmed
 * coordinates inside the circle and inside the caller's reach, nearest first, with the shared haversine; radius only
 * from cfg.tso.periphery_radius_options_m; a zone outside the reach is 403; no contact number without the pii claim.
 * Seed grid: MIR-D-001 at 23.8069 N, 90.3687 E, neighbours about 70 m apart.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NearbyOutletsTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"
    private val clock = AronClock { Instant.parse("2027-01-03T04:00:00Z") }
    private var otherZone = 0L
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
            // Another territory's route with an outlet 20 m from MIR-D-001: inside the circle, outside the SR's reach.
            h.execute("INSERT INTO app.territory (code, name, division_id) SELECT 'T-OTHER', 'Other', division_id FROM app.territory WHERE code = 'T-DHK-N'")
            h.execute("INSERT INTO app.zone (code, name, territory_id) SELECT 'Z-OTHER', 'Other zone', id FROM app.territory WHERE code = 'T-OTHER'")
            h.execute("INSERT INTO app.cluster (zone_id, name) SELECT id, 'Other cluster' FROM app.zone WHERE code = 'Z-OTHER'")
            h.execute("INSERT INTO app.route (code, name, display_label, zone_id, kind, visit_kind, visit_days_mask, sequence_no) SELECT 'OTH-SR-D', 'Other daily', 'Daily', id, 'sr', 'daily', 127, 1 FROM app.zone WHERE code = 'Z-OTHER'")
            h.execute(
                """
                INSERT INTO app.outlet (code, name, owner_name, zone_id, route_id, cluster_id, channel, lat, lng, location_basis, location_confirmed)
                SELECT 'OTH-1', 'Other 1', 'Owner', z.id, r.id, c.id, 'GT', 23.80708, 90.3687, 'master', true
                  FROM app.zone z JOIN app.cluster c ON c.zone_id = z.id JOIN app.route r ON r.zone_id = z.id WHERE z.code = 'Z-OTHER'
                """.trimIndent(),
            )
            // An unconfirmed and a closed outlet of the SR's own route at the centre: never served.
            h.execute("UPDATE app.outlet SET location_confirmed = false WHERE code = 'MIR-D-003'")
            h.execute("UPDATE app.outlet SET lat = 23.8069, lng = 90.3687, location_confirmed = false WHERE code = 'MIR-D-004'")
            h.execute("UPDATE app.outlet SET status = 'closed', lat = 23.8069, lng = 90.36871 WHERE code = 'MIR-D-005'")
            otherZone = h.createQuery("SELECT id FROM app.zone WHERE code = 'Z-OTHER'").mapTo(Long::class.java).one()
            // 55 confirmed outlets of the SR's route within 300 m of a far point, for the marker cap (set to 50, its minimum).
            h.execute(
                """
                INSERT INTO app.outlet (code, name, owner_name, zone_id, route_id, cluster_id, channel, lat, lng, location_basis, location_confirmed)
                SELECT 'CAP-' || n, 'Cap ' || n, 'Owner', o.zone_id, o.route_id, o.cluster_id, 'GT', 23.9 + n * 0.00001, 90.4, 'master', true
                  FROM app.outlet o, generate_series(1, 55) n WHERE o.code = 'MIR-D-001'
                """.trimIndent(),
            )
            h.execute("INSERT INTO app.cfg_version (config_version, kind, committed_by, summary) SELECT max(config_version) + 1, 'change', (SELECT id FROM app.app_user WHERE username = 'aron.system'), 'test cap' FROM app.cfg_version")
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.tso.periphery_max_markers', 'global', 0, '50'::jsonb, now() - interval '1 day', max(config_version), 'test' FROM app.cfg_version")
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

    private suspend fun HttpClient.read(path: String): HttpResponse {
        val t = token ?: run {
            val r = post("/v1/auth/login") {
                contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
                setBody("""{"username":"sr1001","password":"$password","client":"app_sr","device_uuid":"$devPhone"}""")
            }
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content.also { token = it }
        }
        return get(path) { bearerAuth(t); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9") }
    }

    /** The seeded outlets (code to lat, lng, route, cluster, channel) in the caller's reach: the expected body. */
    private fun seeded(): Map<String, List<Any?>> = fresh.db.jdbi.withHandle<Map<String, List<Any?>>, Exception> { h ->
        h.createQuery(
            """
            SELECT o.code, o.lat, o.lng, o.route_id, c.name, o.channel FROM app.outlet o JOIN app.cluster c ON c.id = o.cluster_id JOIN app.route r ON r.id = o.route_id
             WHERE r.code LIKE 'MIR-SR-%' AND o.status = 'active' AND o.location_confirmed
            """.trimIndent(),
        ).map { rs, _ -> rs.getString(1) to listOf(rs.getDouble(2), rs.getDouble(3), rs.getLong(4), rs.getString(5), rs.getString(6)) }.list().toMap()
    }

    @Test
    fun nearbyOutletsAreTheSeededOnesInsideTheCircleAndTheReachNearestFirst() = testApplication {
        application { aronApi(wiring) }
        val seed = seeded()
        for (radius in listOf(50, 100, 300)) {
            val r = client.read("/v1/outlets/nearby?lat=23.8069&lng=90.3687&radius_m=$radius")
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            val b = json(r.bodyAsText())
            assertEquals(radius.toLong(), b["radius_m"]!!.jsonPrimitive.long)
            assertEquals(false, b["truncated"]!!.jsonPrimitive.boolean)
            val items = b["items"]!!.jsonArray.map { it.jsonObject }
            val codes = items.map { it["code"]!!.jsonPrimitive.content }
            // Exactly the seeded outlets of the SR's routes within the radius: never OTH-1 (20 m away, another reach),
            // never the unconfirmed or closed ones at the centre.
            val expected = seed.filter { (_, v) -> Geo.haversineM(23.8069, 90.3687, v[0] as Double, v[1] as Double) <= radius }.keys
            assertEquals(expected, codes.toSet(), "radius $radius")
            assertTrue("OTH-1" !in codes && "MIR-D-004" !in codes && "MIR-D-005" !in codes)
            val dists = items.map { it["distance_m"]!!.jsonPrimitive.double }
            assertEquals(dists.sorted(), dists, "nearest first")
            for (o in items) {
                val v = seed.getValue(o["code"]!!.jsonPrimitive.content)
                assertEquals(v[0], o["lat"]!!.jsonPrimitive.double); assertEquals(v[1], o["lng"]!!.jsonPrimitive.double)
                assertEquals(v[2], o["route_id"]!!.jsonPrimitive.long); assertEquals(v[3], o["cluster_name"]!!.jsonPrimitive.content)
                assertEquals(v[4], o["channel"]!!.jsonPrimitive.content)
                assertEquals(JsonNull, o["contact_number"], "no pii claim: no contact number")
            }
        }
        assertEquals(listOf("MIR-D-001"), json(client.read("/v1/outlets/nearby?lat=23.8069&lng=90.3687&radius_m=50").bodyAsText())["items"]!!.jsonArray.map { it.jsonObject["code"]!!.jsonPrimitive.content })
    }

    @Test
    fun atMostTheMarkerCapNearestFirstAndTruncatedSaysMoreExist() = testApplication {
        application { aronApi(wiring) }
        val b = json(client.read("/v1/outlets/nearby?lat=23.9&lng=90.4&radius_m=300").bodyAsText())
        assertEquals(true, b["truncated"]!!.jsonPrimitive.boolean)
        assertEquals((1..50).map { "CAP-$it" }, b["items"]!!.jsonArray.map { it.jsonObject["code"]!!.jsonPrimitive.content }, "the 50 nearest")
    }

    @Test
    fun aRadiusOutsideTheOptionsOrBoundsIsRefusedAndAZoneOutsideTheReachIsForbidden() = testApplication {
        application { aronApi(wiring) }
        assertEquals(HttpStatusCode.BadRequest, client.read("/v1/outlets/nearby?lat=23.8069&lng=90.3687&radius_m=75").status)
        assertEquals(HttpStatusCode.BadRequest, client.read("/v1/outlets/nearby?lat=23.8069&lng=90.3687").status)
        assertEquals(HttpStatusCode.BadRequest, client.read("/v1/outlets/nearby?lat=19.0&lng=90.3687&radius_m=50").status)
        assertEquals(HttpStatusCode.BadRequest, client.read("/v1/outlets/nearby?lat=23.8069&lng=NaN&radius_m=50").status)
        assertEquals(HttpStatusCode.Forbidden, client.read("/v1/outlets/nearby?lat=23.8069&lng=90.3687&radius_m=50&zone_id=$otherZone").status)
    }
}
