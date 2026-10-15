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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T1 checker for F-AMO-044 (commit 140e2aa): defects the lane's AmoZoneBundleTest does not cover. Same seed and wiring.
 * Fixed clock: Sunday 2027-01-03 08:00 Dhaka.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AmoZoneBundleCheckerTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"
    private val amoPhone = UUID.randomUUID().toString()
    private val tsoPhone = UUID.randomUUID().toString()
    private val clock = AronClock { Instant.parse("2027-01-03T02:00:00Z") }

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate("UPDATE app.app_user SET password_hash = :h WHERE pilot").bind("h", PasswordHasher().hash(password)).execute()
            for ((uuid, flavour, user) in listOf(Triple(amoPhone, "amo", "amo1001"), Triple(tsoPhone, "tso", "tso1001"))) {
                h.execute(
                    """
                    INSERT INTO app.device (device_uuid, flavour, app_package, status, device_owner, lockdown_level, public_key_jwk, public_key_thumbprint, app_signing_cert_sha256, zone_id)
                    SELECT CAST('$uuid' AS uuid), '$flavour', 'com.aktcl.aron.$flavour', 'active', false, 'dev', '{"kty":"placeholder"}', '$flavour-$uuid', decode(repeat('00', 32), 'hex'), z.id
                    FROM app.zone z WHERE z.code = 'Z-MIR'
                    """.trimIndent(),
                )
                h.execute("INSERT INTO app.device_binding (device_id, user_id, bind_ordinal, bound_via) SELECT d.id, u.id, 0, 'support' FROM app.device d, app.app_user u WHERE d.device_uuid = '$uuid' AND u.username = '$user'")
            }
            // Another zone (another territory, so neither the AMO nor the TSO holds it).
            h.execute("INSERT INTO app.territory (code, name, division_id) SELECT 'T-OTH', 'Other territory', division_id FROM app.territory WHERE code = 'T-DHK-N'")
            h.execute("INSERT INTO app.zone (code, name, territory_id) SELECT 'Z-OTH', 'Other zone', id FROM app.territory WHERE code = 'T-OTH'")
            h.execute("INSERT INTO app.cluster (zone_id, name, cluster_type) SELECT id, 'Other market', 'market' FROM app.zone WHERE code = 'Z-OTH'")
            // An outlet of the other zone that sits on a Mirpur route (a route moved zones keeps its outlets' zone_id: nothing
            // re-stamps app.outlet.zone_id). The canonical outlet reach (o.zone_id in zones OR o.route_id in own routes) excludes it.
            h.execute(
                """
                INSERT INTO app.outlet (code, name, owner_name, address, zone_id, route_id, cluster_id, channel, geo_class, lat, lng,
                                        location_basis, location_confirmed, location_accuracy_m, outlet_kind, price_type, visit_sequence)
                SELECT 'OTH-ON-MIR', 'Other zone on Mirpur route', 'Owner', 'Road', z.id, (SELECT id FROM app.route WHERE code = 'MIR-SR-D'), c.id,
                       o.channel, o.geo_class, o.lat, o.lng, o.location_basis, true, 8, 'retail', 'outlet', NULL
                FROM app.zone z JOIN app.cluster c ON c.zone_id = z.id, (SELECT * FROM app.outlet ORDER BY id LIMIT 1) o WHERE z.code = 'Z-OTH'
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
    private fun ids(sql: String): List<Long> = fresh.db.jdbi.withHandle<List<Long>, Exception> { it.createQuery(sql).mapTo(Long::class.java).list() }

    private suspend fun HttpClient.login(user: String, client: String, device: String): String {
        val r = post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
            setBody("""{"username":"$user","password":"$password","client":"$client","device_uuid":"$device"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content
    }

    private suspend fun HttpClient.call(token: String, device: String, path: String): HttpResponse =
        get(path) { bearerAuth(token); header("X-Device-Id", device); header("X-App-Version", "1.0.9+9") }

    private suspend fun HttpClient.bundle(user: String, client: String, device: String): JsonObject {
        val token = login(user, client, device)
        val r = call(token, device, "/v1/sync/bundle")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json(r.bodyAsText())
    }

    private val mirRoutes = "SELECT id FROM app.route WHERE code LIKE 'MIR-SR-%'"

    /** Passes: the AMO's fetch creates, freezes or logs in no route-day of a zone route (not in `plans`). */
    @Test
    @Order(1)
    fun theAmoBundleTouchesNoRouteDayOfTheZone() = testApplication {
        application { aronApi(wiring) }
        client.bundle("amo1001", "app_amo", amoPhone)
        assertEquals(0L, ids("SELECT count(*) FROM app.route_day WHERE route_id IN ($mirRoutes)").single(), "no route-day for zone routes from an AMO fetch")
    }

    /**
     * DEFECT: DECISIONS D-REP-09 and the lane test say zone routes ride with `target_outlets: 0`, but routeSnapshots uses
     * `day?.target ?: r.targetOutlets`, so once the SR fetched (target frozen on app.route_day) the AMO gets the SR's target.
     */
    @Test
    @Order(2)
    fun zoneRoutesCarryNoTargetEvenAfterTheSrFroze() = testApplication {
        application { aronApi(wiring) }
        client.bundle("sr1001", "app_sr", devPhone)
        val frozen = ids("SELECT coalesce(sum(target_outlets), 0) FROM app.route_day WHERE route_id IN ($mirRoutes)").single()
        assertTrue(frozen > 0, "fixture: the SR's fetch froze a target ($frozen)")
        val routes = client.bundle("amo1001", "app_amo", amoPhone)["routes"]!!.jsonArray.map { it.jsonObject }
        val zone = routes.filter { it["route"]!!.jsonObject["code"]!!.jsonPrimitive.content.startsWith("MIR-SR-") }
        assertEquals(3, zone.size)
        assertEquals(listOf(0, 0, 0), zone.map { it["target_outlets"]!!.jsonPrimitive.int }, "zone routes must not carry the SR's frozen target to the AMO")
    }

    /** DEFECT (contract): SupervisorSection.risk_signals is `type: array` (not nullable); ResponseJson has explicitNulls = true, so the TSO gets `null`. */
    @Test
    @Order(3)
    fun theTsoSupervisorSectionHasNoNullRiskSignals() = testApplication {
        application { aronApi(wiring) }
        val s = client.bundle("tso1001", "app_tso", tsoPhone)["supervisor"]!!.jsonObject
        assertTrue(!s.containsKey("risk_signals") || s["risk_signals"] is JsonArray, "risk_signals must be absent or an array, got ${s["risk_signals"]}")
    }

    /** DEFECT (spec s4.10 "AMO and TSO: team members and their routes"): the TSO's team and route_ids come from its own plans only, so a TSO holding no route gets an empty team. */
    @Test
    @Order(4)
    fun theTsoTeamListsTheFieldRepsOfItsReach() = testApplication {
        application { aronApi(wiring) }
        val s = client.bundle("tso1001", "app_tso", tsoPhone)["supervisor"]!!.jsonObject
        val team = s["team"]!!.jsonArray.map { it.jsonObject["username"]!!.jsonPrimitive.content }
        assertTrue("sr1001" in team, "the TSO of Dhaka North must see the Mirpur SR in its team: $team")
    }

    /**
     * DEFECT (scope rule mismatch): zone outlets are selected by `o.route_id` of a zone route, not by the outlet's own zone,
     * so an outlet whose zone_id is outside the AMO's reach travels when it sits on a route of the zone; the canonical reach
     * (SqlOutletReach / reachPredicate: o.zone_id in zones OR o.route_id in own routes) would refuse it.
     */
    @Test
    @Order(5)
    fun anOutletOfAnotherZoneNeverTravelsToTheAmo() = testApplication {
        application { aronApi(wiring) }
        val b = client.bundle("amo1001", "app_amo", amoPhone)
        val other = ids("SELECT id FROM app.outlet WHERE code = 'OTH-ON-MIR'").single()
        val carried = b["routes"]!!.jsonArray.flatMap { r -> r.jsonObject["outlets"]!!.jsonArray.map { it.jsonObject["outlet_id"]!!.jsonPrimitive.long } }
        assertTrue(b["meta"]!!.jsonObject["paged_sections"]!!.jsonArray.isEmpty(), "fixture: not paged")
        assertFalse(other in carried, "outlet $other (zone Z-OTH) must not reach the Mirpur AMO")
    }
}
