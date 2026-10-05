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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Checker review of F-SYS-005 (GET /v1/admin/outlets). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FSys005ReviewTest {
    private lateinit var fresh: FreshDb
    private val world = ScopeWorld(20261005)
    private lateinit var geo: GeoRepository
    private lateinit var resolver: SqlReachResolver
    private val tsoId = 9_101L
    private lateinit var zones: Set<Long>

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        WorldLoader.load(fresh, world)
        geo = GeoRepository(fresh.db)
        resolver = SqlReachResolver(fresh.db, geo)
        val territory = world.territoryDivision.keys.first()
        zones = world.zoneTerritory.filterValues { it == territory }.keys
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.app_user (id, username, full_name, role, must_change_password) OVERRIDING SYSTEM VALUE VALUES ($tsoId, 'tso9101', 'TSO', 'TSO', false)")
            h.execute("INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from) VALUES ($tsoId, 'territory', $territory, '2026-01-01')")
        }
    }

    @AfterAll
    fun tearDown() = fresh.close()

    private fun app(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = testApplication {
        val config = RegistryDefaults()
        val guard = AuthGuardDeps(AccessTokenVerifier(TestTokens.keys), ScopeVersionLookup { 1 }, config)
        application {
            installAronPlatform(PlatformContext(config = config, generation = { "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10" }))
            routing { route("/v1") { outletRoutes(OutletsDeps(fresh.db, geo, resolver, guard)) } }
        }
        block()
    }

    private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    /** A tampered keyed cursor (no '.' in the timestamp part) is a client error, not a retryable 500 (s4.7). */
    @Test
    fun aTamperedKeyedCursorIs400Not500() = app {
        val r = client.get("/v1/admin/outlets?updated_since=2026-01-01T00:00:00Z&cursor=${b64("123|5")}") { bearerAuth(TestTokens.web(tsoId, Role.TSO)) }
        assertEquals(HttpStatusCode.BadRequest, r.status, r.bodyAsText())
    }

    /** A cursor whose epoch seconds are outside Instant's range is a client error too. */
    @Test
    fun aCursorWithAnOutOfRangeInstantIs400Not500() = app {
        val r = client.get("/v1/admin/outlets?updated_since=2026-01-01T00:00:00Z&cursor=${b64("99999999999999999.0|5")}") { bearerAuth(TestTokens.web(tsoId, Role.TSO)) }
        assertEquals(HttpStatusCode.BadRequest, r.status, r.bodyAsText())
    }

    /** updated_since that PostgreSQL cannot represent is a validation error, not a 500. */
    @Test
    fun anOutOfRangeUpdatedSinceIs400Not500() = app {
        val r = client.get("/v1/admin/outlets?updated_since=%2B300000-01-01T00:00:00Z") { bearerAuth(TestTokens.web(tsoId, Role.TSO)) }
        assertEquals(HttpStatusCode.BadRequest, r.status, r.bodyAsText())
    }

    /**
     * Reach drops assigned routes that are missing from the 60 s geography snapshot, and then caches that reach for
     * 5 minutes under the new scope_version: a route created and assigned (sv bumped) right after the snapshot is
     * outside the SR's reach, so its records would be refused/quarantined as out of scope on ingest.
     */
    @Test
    fun aRouteCreatedAndAssignedAfterTheGeoSnapshotIsInTheReachOfTheNewScopeVersion() {
        val g = GeoRepository(fresh.db)
        val res = SqlReachResolver(fresh.db, g)
        val srId = 9_102L
        val zone = zones.first()
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.app_user (id, username, full_name, role, must_change_password) OVERRIDING SYSTEM VALUE VALUES ($srId, 'sr9102', 'SR', 'SR', false)")
        }
        val d = java.time.LocalDate.parse("2026-10-05")
        assertTrue(res.reach(srId, Role.SR, 1, d).routeIds.isEmpty()) // warms the geo snapshot
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.route (id, code, name, zone_id, kind, visit_kind, visit_days_mask) OVERRIDING SYSTEM VALUE VALUES (77777, 'R77777', 'New', $zone, 'sr', 'daily', 127)")
            h.execute("INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from) VALUES (77777, $srId, 'primary', '2026-01-01')")
            h.execute("UPDATE app.app_user SET scope_version = scope_version + 1 WHERE id = $srId")
        }
        assertEquals(setOf(77777L), res.reach(srId, Role.SR, 2, d).routeIds)
    }

    /**
     * A cluster is a geo node under a zone; the builder's own rule (DECISIONS: "a node whose subtree has no overlap
     * with the reach is 403") and s3.5 ("a node outside the reach is 403") say a cluster of another zone is refused,
     * while a zone of another territory is refused. Today the cluster answers 200 with an empty page.
     */
    @Test
    fun aClusterOfAnotherZoneIsRefusedLikeAZoneOfAnotherZone() = app {
        val token = TestTokens.web(tsoId, Role.TSO)
        val otherZone = (world.zoneTerritory.keys - zones).first() // WorldLoader gives each zone a cluster with the zone's id
        assertEquals(HttpStatusCode.Forbidden, client.get("/v1/admin/outlets?zone_id=$otherZone") { bearerAuth(token) }.status)
        val r = client.get("/v1/admin/outlets?cluster_id=$otherZone") { bearerAuth(token) }
        assertEquals(HttpStatusCode.Forbidden, r.status, r.bodyAsText())
        assertTrue(r.bodyAsText().contains("ERR_OUT_OF_SCOPE"))
    }
}
