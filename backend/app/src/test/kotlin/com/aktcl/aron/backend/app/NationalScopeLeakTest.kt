package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.analytics.Aggregator
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jdbi.v3.core.Handle
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D-ROLES-02 rows 3 and 4 over the PRODUCTION wiring (docs/34 s1, D-SCOPE-TREE-01):
 * - row 4: `GET /v1/dashboards/national/filter-options` (the scope tree) lists strictly the caller's reach for TSO (territory),
 *   TSO (house node), DMO, WM, NATIONAL, BRAND and SUPERADMIN: exactly the reached zones whose house is active, and only their
 *   territories, divisions and wings; an inactive house and its zones never appear; another wing's tree never appears.
 * - row 3: a BRAND user's product cards count its brands only (read live from `app.user_brand_scope`), a crafted
 *   `brand_codes` / `variant_codes` / `segment_codes` filter naming another brand cannot widen them, no other brand's name is
 *   listed, the cards without a brand dimension answer 403, and a revoked brand stops at the next call.
 * Expected sets are computed here in SQL from `app.user_scope` and the geography, independently of the reach resolver.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NationalScopeLeakTest {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private lateinit var world: ApiWorld
    private val day = ApiWorld.BUSINESS_DATE
    private var ownBrand = 0L; private var ownBrandCode = ""; private var otherBrandCode = ""; private var otherBrandName = ""
    private var otherVariantCode = ""; private var otherSegmentCode = ""
    private val brandUser = "qabrand1001"

    @BeforeAll
    fun setUp() {
        world = ApiWorld(repoRoot, mirrors = listOf(ApiWorld.OTHER))
        world.fresh.db.jdbi.useHandle<Exception> { h -> seed(h) }
    }

    @AfterAll
    fun tearDown() { world.close() }

    private fun seed(h: Handle) {
        // A second wing no W-CEN caller may see, and an inactive house (with its own zone and route) under the seed's territory T-DHK-N.
        h.execute(
            """
            INSERT INTO app.wing (code, name, name_bn) VALUES ('W-XTS', 'Xts Wing', 'এক্স উইং');
            INSERT INTO app.division (code, name, name_bn, wing_id) SELECT 'D-XTS', 'Xts Division', 'এক্স বিভাগ', id FROM app.wing WHERE code = 'W-XTS';
            INSERT INTO app.territory (code, name, name_bn, division_id) SELECT 'T-XTS', 'Xts North', 'এক্স উত্তর', id FROM app.division WHERE code = 'D-XTS';
            INSERT INTO app.house (code, name, territory_id) SELECT 'H-XTS', 'Xts House', id FROM app.territory WHERE code = 'T-XTS';
            INSERT INTO app.zone (code, name, name_bn, territory_id, house_id, dep_name)
              SELECT 'Z-XTS', 'Xts', 'এক্স', t.id, hs.id, 'Xts DEP' FROM app.territory t, app.house hs WHERE t.code = 'T-XTS' AND hs.code = 'H-XTS';
            INSERT INTO app.house (code, name, territory_id, status) SELECT 'H-OLD', 'Old Closed House', id, 'inactive' FROM app.territory WHERE code = 'T-DHK-N';
            INSERT INTO app.zone (code, name, name_bn, territory_id, house_id, dep_name)
              SELECT 'Z-OLD', 'Old', 'পুরাতন', t.id, hs.id, 'Old DEP' FROM app.territory t, app.house hs WHERE t.code = 'T-DHK-N' AND hs.code = 'H-OLD';
            INSERT INTO app.route (code, name, display_label, zone_id, kind, visit_kind, visit_days_mask, sequence_no)
              SELECT r.code, r.code, 'Daily', z.id, 'sr', 'daily', 127, 1 FROM (VALUES ('XTS-SR-D', 'Z-XTS'), ('OLD-SR-D', 'Z-OLD')) AS r(code, zone) JOIN app.zone z ON z.code = r.zone;
            """.trimIndent(),
        )
        // The levels db/seed lacks: WM over W-CEN (both seeded divisions), NATIONAL, BRAND, and a TSO scoped by its house node.
        for ((u, role, node) in listOf(
            Triple("qawm1001", "WM", "'wing', (SELECT id FROM app.wing WHERE code = 'W-CEN')"),
            Triple("qanational1001", "NATIONAL", "'national', 0"),
            Triple(brandUser, "BRAND", "'national', 0"),
            Triple("qahousetso1001", "TSO", "'house', (SELECT id FROM app.house WHERE code = 'H-MIR')"),
        )) {
            h.execute(
                "INSERT INTO app.app_user (username, full_name, role, designation, employee_code, locale, home_zone_id, pilot, must_change_password, password_hash) " +
                    "SELECT '$u', 'QA $role', '$role', 'QA', upper('$u'), 'en', home_zone_id, true, false, password_hash FROM app.app_user WHERE username = 'admin1001'",
            )
            h.execute("INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from, reason) SELECT id, $node, DATE '2026-01-01', 'qa national scope' FROM app.app_user WHERE username = '$u'")
        }
        Aggregator.refreshDimensions(h)   // also fills the house and code columns the filters read
        // Two cigarette brands, one SKU each, both sold on the seeded route MIR-SR-D today: the BRAND user holds the first only.
        fun one(sql: String) = h.createQuery(sql).mapTo(String::class.java).one()
        val skus = h.createQuery(
            "SELECT DISTINCT ON (p.brand_id) p.sku_id FROM dw.dim_product p WHERE p.category_code = 'cigarette' ORDER BY p.brand_id, p.sku_id LIMIT 2",
        ).mapTo(Long::class.java).list()
        check(skus.size == 2) { "the product seed needs two cigarette brands" }
        ownBrand = one("SELECT brand_id FROM dw.dim_product WHERE sku_id = ${skus[0]}").toLong()
        ownBrandCode = one("SELECT brand_code FROM dw.dim_product WHERE sku_id = ${skus[0]}")
        otherBrandCode = one("SELECT brand_code FROM dw.dim_product WHERE sku_id = ${skus[1]}")
        otherBrandName = one("SELECT brand_name FROM dw.dim_product WHERE sku_id = ${skus[1]}")
        otherVariantCode = one("SELECT variant_code FROM dw.dim_product WHERE sku_id = ${skus[1]}")
        otherSegmentCode = one("SELECT segment_code FROM dw.dim_product WHERE sku_id = ${skus[1]}")
        h.execute(
            "INSERT INTO dw.agg_daily_route_sku (business_date, route_id, sku_id, sold_qty_base, memo_count) " +
                "SELECT DATE '$day', r.id, s.sku, s.qty, 1 FROM app.route r, (VALUES (${skus[0]}, 100), (${skus[1]}, 7)) AS s(sku, qty) WHERE r.code = 'MIR-SR-D'",
        )
        h.execute("INSERT INTO app.user_brand_scope (user_id, brand_id) SELECT id, $ownBrand FROM app.app_user WHERE username = '$brandUser'")
    }

    /** The zones [user]'s scope rows reach (all of them, whatever their house), from SQL alone. */
    private fun reachedZones(user: String): Set<Long> = world.fresh.db.jdbi.withHandle<Set<Long>, Exception> { h ->
        h.createQuery(
            """
            SELECT z.id FROM app.zone z JOIN app.territory t ON t.id = z.territory_id JOIN app.division d ON d.id = t.division_id
              JOIN app.user_scope s ON s.user_id = (SELECT id FROM app.app_user WHERE username = :u) AND s.valid_from <= DATE '$day'
                AND (s.valid_to IS NULL OR s.valid_to > DATE '$day')
             WHERE s.node_type = 'national' OR (s.node_type = 'wing' AND d.wing_id = s.node_id) OR (s.node_type = 'division' AND d.id = s.node_id)
                OR (s.node_type = 'territory' AND t.id = s.node_id) OR (s.node_type = 'house' AND z.house_id = s.node_id) OR (s.node_type = 'zone' AND z.id = s.node_id)
            """,
        ).bind("u", user).mapTo(Long::class.java).set()
    }

    private fun ids(sql: String, zones: Set<Long>): Set<Long> = world.fresh.db.jdbi.withHandle<Set<Long>, Exception> { h ->
        h.createQuery(sql).bindArray("z", Long::class.javaObjectType, zones.toList().ifEmpty { listOf(-1L) }).mapTo(Long::class.java).set()
    }

    private suspend fun HttpClient.token(user: String): String = world.login(this, ApiWorld.Caller(user, "web", null)) ?: error("no token for $user")
    private suspend fun HttpClient.read(t: String, path: String): HttpResponse = get("/v1$path") { bearerAuth(t); header("X-App-Version", ApiWorld.APP_VERSION) }
    private suspend fun HttpClient.send(t: String, path: String, body: String): HttpResponse =
        post("/v1$path") { bearerAuth(t); header("X-App-Version", ApiWorld.APP_VERSION); contentType(ContentType.Application.Json); setBody(body) }
    private suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.idsOf(k: String): Set<Long> = getValue(k).jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.long }.toSet()
    private fun JsonObject.codesOf(k: String): Set<String> = getValue(k).jsonArray.map { it.jsonObject.getValue("code").jsonPrimitive.content }.toSet()

    @Test
    fun theFilterOptionsTreeIsStrictlyTheCallersReachWithActiveHousesOnly() = testApplication {
        application { aronApi(world.wiring) }
        val users = listOf("tso1001", "qahousetso1001", "dmo1001", "qawm1001", "qanational1001", brandUser, "superadmin1001")
        val inactiveHouseZone = ids("SELECT id FROM app.zone WHERE code = 'Z-OLD' AND cardinality(:z) > 0", setOf(0L)).single()
        val otherWingZone = ids("SELECT id FROM app.zone WHERE code = 'Z-XTS' AND cardinality(:z) > 0", setOf(0L)).single()
        for (u in users) {
            val r = client.read(client.token(u), "/dashboards/national/filter-options")
            assertEquals(HttpStatusCode.OK, r.status, "$u: ${r.bodyAsText().take(300)}")
            val o = r.obj()
            val reached = reachedZones(u)
            // Zones with a dashboard row (a route) the caller reaches, minus those of an inactive house.
            val routed = ids("SELECT DISTINCT zone_id FROM dw.dim_geo WHERE zone_id = ANY(:z)", reached)
            val expectedZones = ids("SELECT z.id FROM app.zone z LEFT JOIN app.house hs ON hs.id = z.house_id WHERE z.id = ANY(:z) AND (z.house_id IS NULL OR hs.status = 'active')", routed)
            assertTrue(expectedZones.isNotEmpty(), "$u reaches no zone: the fixture is wrong")
            assertEquals(expectedZones, o.idsOf("zones"), "$u: zones")
            assertFalse(inactiveHouseZone in o.idsOf("zones"), "$u: a zone of an inactive house is listed")
            assertEquals(ids("SELECT DISTINCT territory_id FROM app.zone WHERE id = ANY(:z)", routed), o.idsOf("territories"), "$u: territories")
            assertEquals(ids("SELECT DISTINCT t.division_id FROM app.zone z JOIN app.territory t ON t.id = z.territory_id WHERE z.id = ANY(:z)", routed), o.idsOf("divisions"), "$u: divisions")
            assertEquals(
                ids("SELECT DISTINCT d.wing_id FROM app.zone z JOIN app.territory t ON t.id = z.territory_id JOIN app.division d ON d.id = t.division_id WHERE z.id = ANY(:z)", routed),
                o.idsOf("wings"), "$u: wings",
            )
            val houses = o.idsOf("houses")
            assertEquals(ids("SELECT DISTINCT hs.id FROM app.zone z JOIN app.house hs ON hs.id = z.house_id WHERE z.id = ANY(:z) AND hs.status = 'active'", routed), houses, "$u: houses")
            assertTrue(ids("SELECT id FROM app.house WHERE status <> 'active' AND cardinality(:z) > 0", setOf(0L)).none { it in houses }, "$u: an inactive house is listed")
            val national = u in setOf("qanational1001", brandUser, "superadmin1001")
            assertEquals(national, otherWingZone in o.idsOf("zones"), "$u: the other wing's zone")
        }
        // The house-scoped TSO sees exactly the zones of its house, the territory TSO the whole territory (both seeded mirrors stay apart).
        assertEquals(ids("SELECT z.id FROM app.zone z JOIN app.house hs ON hs.id = z.house_id WHERE hs.code = 'H-MIR' AND cardinality(:z) > 0", setOf(0L)), reachedZones("qahousetso1001"))
    }

    /** D-AUTHZ-GEO-CLAIM-01 (qa backend-authz-qa-1 s2): every web token carries `geo`, the caller's scope as level + ids. */
    @Test
    fun webTokensCarryTheGeoClaimOfTheCallersScope() = testApplication {
        application { aronApi(world.wiring) }
        fun one(sql: String) = world.fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery(sql).mapTo(Long::class.java).one() }
        val expected = mapOf(
            "tso1001" to mapOf("territory" to listOf(one("SELECT id FROM app.territory WHERE code = 'T-DHK-N'"))),
            "qahousetso1001" to mapOf("house" to listOf(one("SELECT id FROM app.house WHERE code = 'H-MIR'"))),
            "dmo1001" to mapOf("division" to listOf(one("SELECT id FROM app.division WHERE code = 'D-DHK'"))),
            "qawm1001" to mapOf("wing" to listOf(one("SELECT id FROM app.wing WHERE code = 'W-CEN'"))),
            "qanational1001" to mapOf("national" to emptyList()), brandUser to mapOf("national" to emptyList()),
            "superadmin1001" to mapOf("national" to emptyList()), "admin1001" to mapOf("national" to emptyList()),
        )
        for ((u, geo) in expected) {
            val claims = com.nimbusds.jwt.SignedJWT.parse(client.token(u)).jwtClaimsSet
            assertEquals(geo, claims.getClaim("geo"), u)
        }
    }

    @Test
    fun aBrandUserSeesItsBrandsOnlyAndACraftedFilterCannotWidenIt() = testApplication {
        application { aronApi(world.wiring) }
        val brand = client.token(brandUser); val sup = client.token("superadmin1001")
        fun cigarettes(o: JsonObject): Long =
            o.getValue("cards").jsonArray.map { it.jsonObject }.singleOrNull { it.getValue("category_code").jsonPrimitive.content == "cigarette" }
                ?.getValue("total_sales")?.jsonPrimitive?.long ?: 0L
        suspend fun sales(t: String, extra: String = ""): JsonObject {
            val r = client.send(t, "/dashboards/national/sales", """{"date":"$day"$extra}"""); assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText().take(300)); return r.obj()
        }
        assertEquals(107, cigarettes(sales(sup)))                                                     // the data is there: both brands
        assertEquals(100, cigarettes(sales(brand)))                                                   // the BRAND user: its brand only
        assertEquals(0, cigarettes(sales(brand, ""","brand_codes":["$otherBrandCode"]""")))           // asking for the other brand selects nothing
        assertEquals(100, cigarettes(sales(brand, ""","brand_codes":["$ownBrandCode","$otherBrandCode"]""")))
        assertEquals(0, cigarettes(sales(brand, ""","variant_codes":["$otherVariantCode"]""")))
        assertTrue(cigarettes(sales(brand, ""","segment_codes":["$otherSegmentCode"]""")) <= 100)
        // Filter options and breakdowns name its brand only.
        val opts = client.read(brand, "/dashboards/national/filter-options").obj()
        assertEquals(setOf(ownBrandCode), opts.codesOf("brands"))
        assertTrue(opts.getValue("variants").jsonArray.all { it.jsonObject.getValue("brand_code").jsonPrimitive.content == ownBrandCode })
        assertFalse(client.read(brand, "/dashboards/national/filter-options").bodyAsText().contains(otherBrandName))
        for (dim in listOf("brand", "variant")) for (measure in listOf("std", "memo")) {
            val r = client.send(brand, "/dashboards/national/breakdown", """{"filter":{"date":"$day","brand_codes":["$otherBrandCode","$ownBrandCode"]},"measure":"$measure","dimension":"$dim"}""")
            assertEquals(HttpStatusCode.OK, r.status, "$dim/$measure: ${r.bodyAsText().take(300)}")
            assertFalse(r.bodyAsText().contains(otherBrandName), "$dim/$measure names the other brand")
            if (dim == "brand") assertTrue(r.obj().getValue("items").jsonArray.all { it.jsonObject.getValue("code").jsonPrimitive.content == ownBrandCode }, "$dim/$measure")
        }
        // A memo count per segment would include other brands' memos: refused for a BRAND caller (fails closed).
        assertEquals(HttpStatusCode.BadRequest, client.send(brand, "/dashboards/national/breakdown", """{"filter":{"date":"$day"},"measure":"memo","dimension":"segment"}""").status)
        for (p in listOf("channel-std", "channel-calls")) {
            val r = client.send(brand, "/dashboards/national/$p", """{"date":"$day"}"""); assertEquals(HttpStatusCode.OK, r.status, p)
            assertFalse(r.bodyAsText().contains(otherBrandName), p)
        }
        // Cards and pages without a brand dimension stay refused.
        for (p in listOf("cpr", "final-submit", "login-submit", "geo-fencing")) assertEquals(HttpStatusCode.Forbidden, client.send(brand, "/dashboards/national/$p", """{"date":"$day"}""").status, p)
        assertEquals(HttpStatusCode.Forbidden, client.send(brand, "/dashboards/national/ff-geo/pins", """{"filter":{"date":"$day"}}""").status)
        assertEquals(HttpStatusCode.Forbidden, client.read(brand, "/dashboards/route-map/${world.ids.getValue("routes")}").status)
        assertEquals(HttpStatusCode.Forbidden, client.read(brand, "/dashboards/sale-position/${world.ids.getValue("outlets")}").status)
        // A revoked brand stops at the next call, whatever the token says (read live, fail closed with no brand left).
        world.fresh.db.jdbi.useHandle<Exception> { h -> h.execute("DELETE FROM app.user_brand_scope WHERE user_id = (SELECT id FROM app.app_user WHERE username = '$brandUser')") }
        try {
            assertEquals(HttpStatusCode.Forbidden, client.send(brand, "/dashboards/national/sales", """{"date":"$day"}""").status)
            assertEquals(HttpStatusCode.Forbidden, client.read(brand, "/dashboards/national/filter-options").status)
        } finally {
            world.fresh.db.jdbi.useHandle<Exception> { h -> h.execute("INSERT INTO app.user_brand_scope (user_id, brand_id) SELECT id, $ownBrand FROM app.app_user WHERE username = '$brandUser'") }
        }
    }
}
