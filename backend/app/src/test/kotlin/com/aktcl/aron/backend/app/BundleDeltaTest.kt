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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * GET /v1/sync/delta and GET /v1/sync/bundle/page on the seed through the production wiring (F-API-005, F-SYS-007,
 * docs/24 s4.10; BC-71): 304 while the snapshot is the same, only the changed rows after a change (upsert and delete by
 * key), 409 for another date's cursor, 410 for an old, unknown or undiffable cursor, and paging above the threshold.
 * Fixed clock: Sunday 2027-01-03 08:00 Dhaka.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class BundleDeltaTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"
    private val now = Instant.parse("2027-01-03T02:00:00Z")
    private val clock = AronClock { now }
    private var cursor = ""
    private var version = ""

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        val hash = PasswordHasher().hash(password)
        fresh.db.jdbi.useHandle<Exception> { h -> h.createUpdate("UPDATE app.app_user SET password_hash = :h WHERE pilot").bind("h", hash).execute() }
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

    private suspend fun HttpClient.token(): String {
        val r = post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
            setBody("""{"username":"sr1001","password":"$password","client":"app_sr","device_uuid":"$devPhone"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content
    }

    private suspend fun HttpClient.call(token: String, path: String): HttpResponse = get(path) { bearerAuth(token); header("X-Device-Id", devPhone) }

    private suspend fun HttpClient.freshBundle(token: String): JsonObject {
        val r = call(token, "/v1/sync/bundle")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val b = json(r.bodyAsText())
        cursor = b["meta"]!!.jsonObject["cursor"]!!.jsonPrimitive.content
        version = b["meta"]!!.jsonObject["bundle_version"]!!.jsonPrimitive.content
        return b
    }

    private fun cursorOf(date: String, seq: Long, at: Instant) = Base64.getUrlEncoder().withoutPadding().encodeToString("c1|$date|$seq|${at.toEpochMilli()}".toByteArray())

    private fun firstOutlet(): Long = fresh.db.jdbi.withHandle<Long, Exception> { h ->
        h.createQuery("SELECT o.id FROM app.outlet o JOIN app.route r ON r.id = o.route_id WHERE r.code = 'MIR-SR-D' AND o.status = 'active' ORDER BY o.id LIMIT 1").mapTo(Long::class.java).one()
    }

    @Test
    @Order(1)
    fun sameSnapshotIs304AndAChangedOutletIsTheOnlyUpsert() = testApplication {
        application { aronApi(wiring) }
        val token = client.token()
        client.freshBundle(token)
        assertEquals(HttpStatusCode.NotModified, client.call(token, "/v1/sync/delta?since=$cursor").status)

        val id = firstOutlet()
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.outlet SET name = 'Renamed Store' WHERE id = ?", id) }
        val r = client.call(token, "/v1/sync/delta?since=$cursor&for=2027-01-03")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val d = json(r.bodyAsText())
        val meta = d["meta"]!!.jsonObject
        assertEquals(cursor, meta["base_cursor"]!!.jsonPrimitive.content)
        assertNotEquals(cursor, meta["cursor"]!!.jsonPrimitive.content)
        assertNotEquals(version, meta["bundle_version"]!!.jsonPrimitive.content)
        assertEquals("2027-01-03", meta["valid_for_business_date"]!!.jsonPrimitive.content)
        val sections = d["sections"]!!.jsonObject
        assertEquals(setOf("outlets"), sections.keys, "only the outlet section changed: $sections")
        val up = sections["outlets"]!!.jsonObject["upsert"]!!.jsonArray
        assertEquals(1, up.size)
        assertEquals(id, up[0].jsonObject["outlet_id"]!!.jsonPrimitive.long)
        assertEquals("Renamed Store", up[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(0, sections["outlets"]!!.jsonObject["delete"]!!.jsonArray.size)
        assertEquals(0, d["routes_added"]!!.jsonArray.size)
        assertEquals(0, d["routes_removed"]!!.jsonArray.size)

        // The new cursor names the new snapshot: nothing more to send.
        cursor = meta["cursor"]!!.jsonPrimitive.content
        version = meta["bundle_version"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.NotModified, client.call(token, "/v1/sync/delta?since=$cursor").status)
    }

    @Test
    @Order(2)
    fun aClosedOutletIsADeleteByKey() = testApplication {
        application { aronApi(wiring) }
        val token = client.token()
        val id = firstOutlet()
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.outlet SET status = 'closed' WHERE id = ?", id) }
        val r = client.call(token, "/v1/sync/delta?since=$cursor")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val outlets = json(r.bodyAsText())["sections"]!!.jsonObject["outlets"]!!.jsonObject
        assertEquals(listOf(id), outlets["delete"]!!.jsonArray.map { it.jsonPrimitive.long })
        assertEquals(0, outlets["upsert"]!!.jsonArray.size)
        cursor = json(r.bodyAsText())["meta"]!!.jsonObject["cursor"]!!.jsonPrimitive.content
    }

    @Test
    @Order(3)
    fun badOtherDateOldAndUnknownCursors() = testApplication {
        application { aronApi(wiring) }
        val token = client.token()
        assertEquals(HttpStatusCode.BadRequest, client.call(token, "/v1/sync/delta?since=not*a*cursor").status)
        assertEquals(HttpStatusCode.BadRequest, client.call(token, "/v1/sync/delta").status)
        assertEquals(HttpStatusCode.BadRequest, client.call(token, "/v1/sync/delta?since=" + Base64.getUrlEncoder().withoutPadding().encodeToString("x|y|z|w".toByteArray())).status)
        val yesterday = client.call(token, "/v1/sync/delta?since=" + cursorOf("2027-01-02", 1, now.minusSeconds(86_400)))
        assertEquals(HttpStatusCode.Conflict, yesterday.status)
        assertTrue("ERR_BUNDLE_NEW_BUSINESS_DATE" in yesterday.bodyAsText())
        // A cursor older than cfg.bundle.delta_max_age_h (72 h) on the same date cannot exist in practice; the age check runs first.
        val old = client.call(token, "/v1/sync/delta?since=" + cursorOf("2027-01-03", 1, now.minusSeconds(73 * 3600)))
        assertEquals(HttpStatusCode.Gone, old.status)
        assertTrue("ERR_BUNDLE_CURSOR_EXPIRED" in old.bodyAsText())
        // A snapshot this replica never served cannot be diffed: fetch the full bundle.
        assertEquals(HttpStatusCode.Gone, client.call(token, "/v1/sync/delta?since=" + cursorOf("2027-01-03", 999_999, now)).status)
        // A cursor of today with for = another date.
        assertEquals(HttpStatusCode.Conflict, client.call(token, "/v1/sync/delta?since=$cursor&for=2027-01-04").status)
    }

    @Test
    @Order(4)
    fun aChangeADeltaCannotCarryIs410() = testApplication {
        application { aronApi(wiring) }
        val token = client.token()
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.app_user SET full_name = 'Another Name' WHERE username = 'sr1001'") }
        assertEquals(HttpStatusCode.Gone, client.call(token, "/v1/sync/delta?since=$cursor").status)
    }

    @Test
    @Order(5)
    fun pagesOnlyForPagedSectionsOfTheCurrentSnapshot() = testApplication {
        application { aronApi(wiring) }
        val token = client.token()
        val b = client.freshBundle(token)
        assertEquals(0, b["meta"]!!.jsonObject["paged_sections"]!!.jsonArray.size)
        assertEquals(HttpStatusCode.NotFound, client.call(token, "/v1/sync/bundle/page?bundle_version=$version&section=outlets&page=1").status)
        assertEquals(HttpStatusCode.Gone, client.call(token, "/v1/sync/bundle/page?bundle_version=2027-01-03:999999&section=outlets&page=1").status)
        assertEquals(HttpStatusCode.Gone, client.call(token, "/v1/sync/bundle/page?bundle_version=2027-01-02:1&section=outlets&page=1").status)
        assertEquals(HttpStatusCode.BadRequest, client.call(token, "/v1/sync/bundle/page?bundle_version=$version&section=nope&page=1").status)
        assertEquals(HttpStatusCode.BadRequest, client.call(token, "/v1/sync/bundle/page?bundle_version=$version&section=outlets&page=0").status)
        assertEquals(HttpStatusCode.BadRequest, client.call(token, "/v1/sync/bundle/page?bundle_version=bad&section=outlets&page=1").status)
    }

    @Test
    @Order(6)
    fun outletsAboveTheThresholdArePagedAndThePagesHoldEveryRowOnce() = testApplication {
        application { aronApi(wiring) }
        // 2,100 more outlets on the Daily route: above cfg.bundle.page_threshold_rows (2,000), pages of cfg.bundle.page_rows (1,000).
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute(
                """
                INSERT INTO app.outlet (code, name, owner_name, address, zone_id, route_id, cluster_id, channel, geo_class, lat, lng,
                                        location_basis, location_confirmed, location_accuracy_m, outlet_kind, price_type, visit_sequence)
                SELECT format('PG-%s', n), format('Paged %s', n), 'Owner', 'Road', o.zone_id, o.route_id, o.cluster_id, o.channel, o.geo_class,
                       o.lat, o.lng, o.location_basis, true, 8, 'retail', 'outlet', NULL
                FROM generate_series(1, 2100) n, (SELECT * FROM app.outlet WHERE id = (SELECT min(o2.id) FROM app.outlet o2 JOIN app.route r ON r.id = o2.route_id WHERE r.code = 'MIR-SR-D' AND o2.status = 'active')) o
                """.trimIndent(),
            )
        }
        val token = client.token()
        val b = client.freshBundle(token)
        val paged = b["meta"]!!.jsonObject["paged_sections"]!!.jsonArray.map { it.jsonObject }
        val outlets = paged.single { it["section"]!!.jsonPrimitive.content == "outlets" }
        val total = outlets["rows"]!!.jsonPrimitive.int
        assertTrue(total > 2100, "rows = $total")
        assertEquals((total + 999) / 1000, outlets["pages"]!!.jsonPrimitive.int)
        assertTrue(b["routes"]!!.jsonArray.all { it.jsonObject["outlets"]!!.jsonArray.isEmpty() }, "paged rows are not in the bundle")
        val ids = ArrayList<Long>()
        for (page in 1..outlets["pages"]!!.jsonPrimitive.int) {
            val r = client.call(token, "/v1/sync/bundle/page?bundle_version=$version&section=outlets&page=$page")
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            val p = json(r.bodyAsText())
            assertEquals(page, p["page"]!!.jsonPrimitive.int)
            assertEquals(version, p["bundle_version"]!!.jsonPrimitive.content)
            ids += p["rows"]!!.jsonArray.map { it.jsonObject["outlet_id"]!!.jsonPrimitive.long }
        }
        assertEquals(total, ids.size)
        assertEquals(total, ids.toSet().size)
        assertEquals(HttpStatusCode.NotFound, client.call(token, "/v1/sync/bundle/page?bundle_version=$version&section=outlets&page=${outlets["pages"]!!.jsonPrimitive.int + 1}").status)
    }

    @Test
    @Order(7)
    fun anOutletMovedToAnotherOfTheUsersRoutesIsOneUpsertWithItsNewRoute() = testApplication {
        application { aronApi(wiring) }
        val token = client.token()
        client.freshBundle(token)
        val id = firstOutlet()
        val to = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT id FROM app.route WHERE code = 'MIR-SR-3F'").mapTo(Long::class.java).one() }
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.outlet SET route_id = ? WHERE id = ?", to, id) }
        val r = client.call(token, "/v1/sync/delta?since=$cursor")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val d = json(r.bodyAsText())
        val outlets = d["sections"]!!.jsonObject["outlets"]!!.jsonObject
        val up = outlets["upsert"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(id), up.map { it["outlet_id"]!!.jsonPrimitive.long })
        assertEquals(to, up.single()["route_id"]!!.jsonPrimitive.long)
        assertEquals(0, outlets["delete"]!!.jsonArray.size, "a moved outlet is never deleted")
        assertEquals(0, d["routes_added"]!!.jsonArray.size)
        assertEquals(0, d["routes_removed"]!!.jsonArray.size)
    }
}
