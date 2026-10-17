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
import kotlinx.serialization.json.JsonNull
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * F-AMO-044 zone-wide AMO bundle on the seed through the production wiring: the AMO carries the outlets of every route of
 * the zone (paged above the threshold), the `supervisor` section is derived from the token's reach, an SR gets neither, a
 * zone the AMO does not hold never leaks in, and the pages of a version stay servable while sales move the snapshot.
 * Fixed clock: Sunday 2027-01-03 08:00 Dhaka.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AmoZoneBundleTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"
    private val amoPhone = UUID.randomUUID().toString()
    private val clock = AronClock { Instant.parse("2027-01-03T02:00:00Z") }
    private var version = ""
    private val extraOutlets = 700

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate("UPDATE app.app_user SET password_hash = :h WHERE pilot").bind("h", PasswordHasher().hash(password)).execute()
            h.execute("UPDATE app.app_user SET phone = '01712345678' WHERE username = 'sr1001'")
            h.execute(
                """
                INSERT INTO app.device (device_uuid, flavour, app_package, status, device_owner, lockdown_level, public_key_jwk, public_key_thumbprint, app_signing_cert_sha256, zone_id)
                SELECT CAST('$amoPhone' AS uuid), 'amo', 'com.aktcl.aron.amo', 'active', false, 'dev', '{"kty":"placeholder"}', 'amo-$amoPhone', decode(repeat('00', 32), 'hex'), z.id
                FROM app.zone z WHERE z.code = 'Z-MIR'
                """.trimIndent(),
            )
            h.execute("INSERT INTO app.device_binding (device_id, user_id, bind_ordinal, bound_via) SELECT d.id, u.id, 0, 'support' FROM app.device d, app.app_user u WHERE d.device_uuid = '$amoPhone' AND u.username = 'amo1001'")
            // 700 more outlets across the zone's routes: above the lowest threshold the server allows (500).
            h.execute(
                """
                INSERT INTO app.outlet (code, name, owner_name, address, zone_id, route_id, cluster_id, channel, geo_class, lat, lng,
                                        location_basis, location_confirmed, location_accuracy_m, outlet_kind, price_type, visit_sequence)
                SELECT format('ZW-%s', n), format('Zone wide %s', n), 'Owner', 'Road', o.zone_id, o.route_id, o.cluster_id, o.channel, o.geo_class,
                       o.lat, o.lng, o.location_basis, true, 8, 'retail', 'outlet', NULL
                FROM generate_series(1, $extraOutlets) n
                JOIN LATERAL (SELECT * FROM app.outlet WHERE route_id = (SELECT id FROM app.route WHERE code = (ARRAY['MIR-SR-D','MIR-SR-3F','MIR-SR-2F'])[1 + n % 3]) ORDER BY id LIMIT 1) o ON true
                """.trimIndent(),
            )
            // Another zone with a route and an outlet the AMO must never see.
            h.execute("INSERT INTO app.zone (code, name, territory_id) SELECT 'Z-OTH', 'Other zone', territory_id FROM app.zone WHERE code = 'Z-MIR'")
            h.execute("INSERT INTO app.cluster (zone_id, name, cluster_type) SELECT id, 'Other market', 'market' FROM app.zone WHERE code = 'Z-OTH'")
            h.execute("INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask, sequence_no) SELECT 'OTH-SR-1', 'Other route', id, 'sr', 'daily', 127, 1 FROM app.zone WHERE code = 'Z-OTH'")
            h.execute(
                """
                INSERT INTO app.outlet (code, name, owner_name, address, zone_id, route_id, cluster_id, channel, geo_class, lat, lng,
                                        location_basis, location_confirmed, location_accuracy_m, outlet_kind, price_type, visit_sequence)
                SELECT 'OTH-1', 'Other zone store', 'Owner', 'Road', z.id, r.id, c.id, o.channel, o.geo_class, o.lat, o.lng, o.location_basis, true, 8, 'retail', 'outlet', NULL
                FROM app.zone z JOIN app.route r ON r.zone_id = z.id JOIN app.cluster c ON c.zone_id = z.id, (SELECT * FROM app.outlet ORDER BY id LIMIT 1) o WHERE z.code = 'Z-OTH'
                """.trimIndent(),
            )
            // A pending outlet request inside the zone and one in the other zone (the second must not appear).
            for ((zone, route, tag) in listOf(Triple("Z-MIR", "MIR-SR-D", "in"), Triple("Z-OTH", "OTH-SR-1", "out"))) {
                h.execute(
                    """
                    INSERT INTO app.outlet_change_request (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, request_type, route_id, proposed, note)
                    SELECT gen_random_uuid(), gen_random_uuid(), DATE '2027-01-03', (SELECT id FROM app.app_user WHERE username = 'sr1001'), TIMESTAMPTZ '2027-01-03 01:00+00',
                           (SELECT max(config_version) FROM app.cfg_version), 'new', r.id, '{"name":"$tag"}'::jsonb, '$tag'
                    FROM app.route r WHERE r.code = '$route'
                    """.trimIndent(),
                )
            }
        }
        for ((k, v) in listOf("cfg.bundle.page_threshold_rows" to 500, "cfg.bundle.page_rows" to 200)) fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.cfg_version (config_version, kind, committed_by, summary) SELECT max(config_version) + 1, 'change', (SELECT id FROM app.app_user WHERE username = 'aron.system'), 'amo zone' FROM app.cfg_version")
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT '$k', 'global', 0, '$v'::jsonb, now() - interval '1 day', max(config_version), 'amo zone' FROM app.cfg_version")
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

    private suspend fun HttpClient.amoBundle(token: String): JsonObject {
        val r = call(token, amoPhone, "/v1/sync/bundle")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json(r.bodyAsText()).also { version = it["meta"]!!.jsonObject["bundle_version"]!!.jsonPrimitive.content }
    }

    private suspend fun HttpClient.allPages(token: String, v: String, pages: Int): List<JsonObject> = (1..pages).flatMap { page ->
        val r = call(token, amoPhone, "/v1/sync/bundle/page?bundle_version=$v&section=outlets&page=$page")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        json(r.bodyAsText())["rows"]!!.jsonArray.map { it.jsonObject }
    }

    @Test
    @Order(1)
    fun theAmoBundleCarriesEveryOutletOfTheZoneAndNothingOfAnotherZone() = testApplication {
        application { aronApi(wiring) }
        val token = client.login("amo1001", "app_amo", amoPhone)
        val b = client.amoBundle(token)
        val zoneOutlets = ids("SELECT id FROM app.outlet WHERE zone_id = (SELECT id FROM app.zone WHERE code = 'Z-MIR') AND status = 'active'")
        assertTrue(zoneOutlets.size > 700, "fixture: ${zoneOutlets.size}")
        val paged = b["meta"]!!.jsonObject["paged_sections"]!!.jsonArray.map { it.jsonObject }.single { it["section"]!!.jsonPrimitive.content == "outlets" }
        assertEquals(zoneOutlets.size, paged["rows"]!!.jsonPrimitive.int)
        val rows = client.allPages(token, version, paged["pages"]!!.jsonPrimitive.int)
        assertEquals(zoneOutlets.toSet(), rows.map { it["outlet_id"]!!.jsonPrimitive.long }.toSet(), "every outlet of the zone, once")
        assertEquals(zoneOutlets.size, rows.size)
        val other = ids("SELECT id FROM app.outlet WHERE code = 'OTH-1'").single()
        assertFalse(other in rows.map { it["outlet_id"]!!.jsonPrimitive.long }, "an outlet of another zone must not travel")
        // The zone's routes are all in the bundle, none counted as the AMO's target; no open memos ride along.
        val routes = b["routes"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf("MIR-SR-D", "MIR-SR-3F", "MIR-SR-2F"), routes.map { it["route"]!!.jsonObject["code"]!!.jsonPrimitive.content }.toSet())
        assertTrue(routes.all { !it["planned_today"]!!.jsonPrimitive.content.toBoolean() && it["target_outlets"]!!.jsonPrimitive.int == 0 && it["open_memos"]!!.jsonArray.isEmpty() })
    }

    @Test
    @Order(2)
    fun theSupervisorSectionIsScopedToTheZone() = testApplication {
        application { aronApi(wiring) }
        val token = client.login("amo1001", "app_amo", amoPhone)
        val s = client.amoBundle(token)["supervisor"]!!.jsonObject
        val zone = ids("SELECT id FROM app.zone WHERE code = 'Z-MIR'").single()
        assertEquals(listOf(zone), s["zone_ids"]!!.jsonArray.map { it.jsonPrimitive.long })
        assertEquals(ids("SELECT id FROM app.route WHERE zone_id = $zone AND status = 'active' ORDER BY id").toSet(), s["route_ids"]!!.jsonArray.map { it.jsonPrimitive.long }.toSet())
        // Team: the zone's SR, phone masked, no AMO himself.
        val team = s["team"]!!.jsonArray.map { it.jsonObject }
        val sr = team.single { it["username"]!!.jsonPrimitive.content == "sr1001" }
        val phone = sr["phone_masked"]!!.jsonPrimitive.content
        assertEquals("017*****678", phone)
        assertFalse(team.any { it["username"]!!.jsonPrimitive.content == "amo1001" }, team.toString())
        // Pending requests: the zone's only.
        val requests = s["pending_outlet_requests"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, requests.size, requests.toString())
        assertEquals("pending", requests.single()["status"]!!.jsonPrimitive.content)
        assertEquals(ids("SELECT id FROM app.route WHERE code = 'MIR-SR-D'").single(), requests.single()["route_id"]!!.jsonPrimitive.long)
        // Risk signals: present (a list) for the AMO.
        assertTrue(s["risk_signals"] is JsonArray)
    }

    @Test
    @Order(3)
    fun aFieldRepGetsNeitherTheZoneRoutesNorASupervisorSection() = testApplication {
        application { aronApi(wiring) }
        val token = client.login("sr1001", "app_sr", devPhone)
        val r = client.call(token, devPhone, "/v1/sync/bundle")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val b = json(r.bodyAsText())
        assertTrue(b["supervisor"] == null || b["supervisor"] is JsonNull)
        val mine = ids("SELECT route_id FROM app.route_assignment WHERE user_id = (SELECT id FROM app.app_user WHERE username = 'sr1001')").toSet()
        assertEquals(mine, b["routes"]!!.jsonArray.map { it.jsonObject["route_id"]!!.jsonPrimitive.long }.toSet())
    }

    @Test
    @Order(4)
    fun aPageOfAVersionStaysServableWhenTheSnapshotMovesOnAndTheNewBundleHasANewVersion() = testApplication {
        application { aronApi(wiring) }
        val token = client.login("amo1001", "app_amo", amoPhone)
        client.amoBundle(token)
        val old = version
        // A sale elsewhere in the zone changes the content (an outlet is renamed): the bundle moves to a new version.
        fresh.db.jdbi.useHandle<Exception> { it.execute("UPDATE app.outlet SET name = name || ' x' WHERE code = 'ZW-1'") }
        val page = client.call(token, amoPhone, "/v1/sync/bundle/page?bundle_version=$old&section=outlets&page=1")
        assertEquals(HttpStatusCode.OK, page.status, "the cached version still serves its pages: " + page.bodyAsText())
        client.amoBundle(token)
        assertNotEquals(old, version)
        // A version nobody built is gone.
        assertEquals(HttpStatusCode.Gone, client.call(token, amoPhone, "/v1/sync/bundle/page?bundle_version=2027-01-03:99999999&section=outlets&page=1").status)
    }

    @Test
    @Order(5)
    fun aCachedPageIsNotServedToAnotherUser() = testApplication {
        application { aronApi(wiring) }
        val amoToken = client.login("amo1001", "app_amo", amoPhone)
        client.amoBundle(amoToken)
        val v = version
        val srToken = client.login("sr1001", "app_sr", devPhone)
        // Version numbers are per user and can coincide: the SR gets a page only when the version is the SR's own.
        val srVersion = json(client.call(srToken, devPhone, "/v1/sync/bundle").bodyAsText())["meta"]!!.jsonObject["bundle_version"]!!.jsonPrimitive.content
        val r = client.call(srToken, devPhone, "/v1/sync/bundle/page?bundle_version=$v&section=outlets&page=1")
        if (srVersion == v) {
            assertEquals(HttpStatusCode.OK, r.status)
            val sr = ids("SELECT route_id FROM app.route_assignment WHERE user_id = (SELECT id FROM app.app_user WHERE username = 'sr1001')").toSet()
            assertTrue(json(r.bodyAsText())["rows"]!!.jsonArray.all { it.jsonObject["route_id"]!!.jsonPrimitive.long in sr }, "the SR's own page")
        } else {
            assertEquals(HttpStatusCode.Gone, r.status, "an SR must not get the AMO's cached page")
        }
    }
}
