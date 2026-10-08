package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.AccessTokenVerifier
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.PlatformContext
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.platform.ScopeVersionLookup
import com.aktcl.aron.backend.platform.installAronPlatform
import com.aktcl.aron.contract.Role
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** F-SYS-005 on real PostgreSQL 16: the SQL reach and outlet query against the oracle, and the HTTP list. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScopeDbTest {
    private lateinit var fresh: FreshDb
    private val world = ScopeWorld(20261005)
    private lateinit var geo: GeoRepository
    private lateinit var resolver: SqlReachResolver
    private val tsoId = 9_001L
    private val srId = 9_002L
    private lateinit var tsoTerritory: Pair<Long, Set<Long>>

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        WorldLoader.load(fresh, world)
        geo = GeoRepository(fresh.db)
        resolver = SqlReachResolver(fresh.db, geo)
        val territory = world.territoryDivision.keys.first()
        tsoTerritory = territory to world.zoneTerritory.filterValues { it == territory }.keys
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.app_user (id, username, full_name, role, must_change_password) OVERRIDING SYSTEM VALUE VALUES ($tsoId, 'tso9001', 'TSO', 'TSO', false)")
            h.execute("INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from) VALUES ($tsoId, 'territory', $territory, '2026-01-01')")
            h.execute("INSERT INTO app.app_user (id, username, full_name, role, must_change_password) OVERRIDING SYSTEM VALUE VALUES ($srId, 'sr9002', 'SR', 'SR', false)")
        }
    }

    @AfterAll
    fun tearDown() = fresh.close()

    @Test
    fun scopeLeakHarnessOverTheSqlPathFindsZeroCrossScopeRowsIn200RandomisedQueries() {
        val sql = SqlOutletReach(fresh.db, geo)
        var refused = 0
        val leaks = world.run(200) { u, date, sel ->
            val reach = resolver.reach(u.id, u.role, 1, date)
            outcomeOf { sql.ids(reach, sel) }.also { if (it is ScopeWorld.Outcome.Refused) refused++ }
        }
        assertEquals(emptyList(), leaks)
        assertTrue(refused > 0)
    }

    private fun app(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = testApplication {
        val config = RegistryDefaults()
        val guard = AuthGuardDeps(AccessTokenVerifier(TestTokens.keys), ScopeVersionLookup { 1 }, config)
        application {
            installAronPlatform(PlatformContext(config = config, generation = { "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10" }))
            routing { route("/v1") { outletRoutes(OutletsDeps(fresh.db, geo, resolver, guard)) } }
        }
        block()
    }

    @Test
    fun aTsoTokenReturnsOnlyOutletsOfItsTerritoryAndClientScopeIdsAreIgnored() = app {
        val (territory, zones) = tsoTerritory
        val expected = world.outlets.filter { it.zoneId in zones }.map { it.id }.toSet()
        val otherZones = world.zoneTerritory.keys - zones
        // Paging through everything, with scope ids a client might try to send.
        val seen = mutableSetOf<Long>()
        var cursor: String? = null
        do {
            val url = "/v1/admin/outlets?limit=7&zone_ids=${otherZones.joinToString(",")}&scope_ids=${otherZones.first()}&territory_id=${world.territoryDivision.keys.last()}" +
                (cursor?.let { "&cursor=$it" } ?: "")
            val r = client.get(url) { bearerAuth(TestTokens.web(tsoId, Role.TSO)) }
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            val b = Json.parseToJsonElement(r.bodyAsText()).jsonObject
            b["items"]!!.jsonArray.forEach { o ->
                val zone = o.jsonObject["zone_id"]!!.jsonPrimitive.long
                assertTrue(zone in zones, "outlet outside territory $territory: zone $zone")
                assertEquals(JsonNull, o.jsonObject["contact_number"], "no pii claim, no phone number")
                assertTrue(seen.add(o.jsonObject["id"]!!.jsonPrimitive.long), "no duplicates across pages")
            }
            cursor = b["next_cursor"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content }
        } while (cursor != null)
        assertEquals(expected, seen)
    }

    @Test
    fun narrowingOutsideTheReachIsRefusedAndInsideNarrows() = app {
        val (_, zones) = tsoTerritory
        val token = TestTokens.web(tsoId, Role.TSO)
        val outside = (world.zoneTerritory.keys - zones).first()
        val r = client.get("/v1/admin/outlets?zone_id=$outside") { bearerAuth(token) }
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertTrue(r.bodyAsText().contains("ERR_OUT_OF_SCOPE"))
        val inside = zones.first()
        val ok = Json.parseToJsonElement(client.get("/v1/admin/outlets?zone_id=$inside&limit=500") { bearerAuth(token) }.bodyAsText()).jsonObject
        assertEquals(world.outlets.count { it.zoneId == inside }, ok["items"]!!.jsonArray.size)
        val outsideRoute = world.routeZone.entries.first { it.value !in zones }.key
        assertEquals(HttpStatusCode.Forbidden, client.get("/v1/admin/outlets?route_id=$outsideRoute") { bearerAuth(token) }.status)
    }

    @Test
    fun fieldRolesAreRefusedAndPiiNeedsTheClaim() = app {
        assertEquals(HttpStatusCode.Forbidden, client.get("/v1/admin/outlets") { bearerAuth(TestTokens.web(srId, Role.SR)) }.status)
        val b = Json.parseToJsonElement(client.get("/v1/admin/outlets?limit=1") { bearerAuth(TestTokens.web(tsoId, Role.TSO, pii = true)) }.bodyAsText()).jsonObject
        assertEquals("01711000000", b["items"]!!.jsonArray[0].jsonObject["contact_number"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/admin/outlets").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/admin/outlets?limit=0") { bearerAuth(TestTokens.web(tsoId, Role.TSO)) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/admin/outlets?cursor=%21%21") { bearerAuth(TestTokens.web(tsoId, Role.TSO)) }.status)
    }

    /**
     * F-API-010, GET /v1/outlets (contract listOutletsInReach): an SR reads exactly the outlets of its assigned route, a
     * TSO those of its territory, and every item equals the seeded row; `contact_number` only with the pii claim.
     */
    @Test
    fun theFieldReadServesEveryRoleTheSeededRowsWithThePiiGate() = app {
        val route = world.routeZone.keys.first()
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from, reason) SELECT $route, $srId, 'cover', DATE '2026-01-01', 'test' WHERE NOT EXISTS (SELECT 1 FROM app.route_assignment WHERE user_id = $srId)")
        }
        try {
            suspend fun items(token: String): List<kotlinx.serialization.json.JsonObject> {
                val r = client.get("/v1/outlets?limit=500") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
                return Json.parseToJsonElement(r.bodyAsText()).jsonObject["items"]!!.jsonArray.map { it.jsonObject }
            }
            fun dbRows(where: String) = fresh.db.jdbi.withHandle<Map<Long, Map<String, Any?>>, Exception> { h ->
                h.createQuery("SELECT id, code, name, zone_id, route_id, contact_number, status FROM app.outlet WHERE $where").mapToMap().list().associateBy { (it["id"] as Number).toLong() }
            }
            val srRows = dbRows("route_id = $route")
            val sr = items(TestTokens.web(srId, Role.SR))
            assertEquals(srRows.keys, sr.map { it["id"]!!.jsonPrimitive.long }.toSet(), "the SR's route only")
            val (_, zones) = tsoTerritory
            val tsoRows = dbRows("zone_id IN (${zones.joinToString(",")})")
            val tso = items(TestTokens.web(tsoId, Role.TSO, pii = true))
            assertEquals(tsoRows.keys, tso.map { it["id"]!!.jsonPrimitive.long }.toSet())
            for (o in sr + tso) {
                val row = (srRows + tsoRows).getValue(o["id"]!!.jsonPrimitive.long)
                assertEquals(row["code"], o["code"]!!.jsonPrimitive.content); assertEquals(row["name"], o["name"]!!.jsonPrimitive.content)
                assertEquals((row["zone_id"] as Number).toLong(), o["zone_id"]!!.jsonPrimitive.long)
                assertEquals((row["route_id"] as Number?)?.toLong(), (o["route_id"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is JsonNull }?.long)
                assertEquals(row["status"], o["status"]!!.jsonPrimitive.content)
            }
            assertTrue(sr.all { it["contact_number"] == JsonNull }, "no pii claim, no phone number")
            assertTrue(tso.isNotEmpty() && tso.all { o -> o["contact_number"]!!.jsonPrimitive.content == tsoRows.getValue(o["id"]!!.jsonPrimitive.long)["contact_number"] })
            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/outlets").status)
        } finally {
            fresh.db.jdbi.useHandle<Exception> { h -> h.execute("DELETE FROM app.route_assignment WHERE user_id = $srId") }
        }
    }

    @Test
    fun reachFollowsTheBusinessDate() {
        val r = resolver.reach(tsoId, Role.TSO, 1, LocalDate.parse("2025-12-31"))
        assertTrue(r.zoneIds.isEmpty(), "scope starts 2026-01-01")
    }
}
