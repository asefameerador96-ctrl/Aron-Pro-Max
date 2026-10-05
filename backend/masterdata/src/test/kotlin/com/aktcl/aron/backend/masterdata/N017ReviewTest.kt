package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.DayPlan
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.RegistryDefaults
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Checker review of N-017 (SqlRoutePlanner). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class N017ReviewTest {
    private lateinit var fresh: FreshDb
    private val sunday = LocalDate.parse("2026-10-04")

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.wing (id, code, name) OVERRIDING SYSTEM VALUE VALUES (1, 'W1', 'Wing')")
            h.execute("INSERT INTO app.division (id, code, name, wing_id) OVERRIDING SYSTEM VALUE VALUES (2, 'D2', 'Division', 1)")
            h.execute("INSERT INTO app.territory (id, code, name, division_id) OVERRIDING SYSTEM VALUE VALUES (3, 'T3', 'Territory', 2)")
            h.execute("INSERT INTO app.zone (id, code, name, territory_id) OVERRIDING SYSTEM VALUE VALUES (4, 'Z4', 'Zone', 3)")
            h.execute("INSERT INTO app.route (id, code, name, zone_id, kind, visit_kind, visit_days_mask) OVERRIDING SYSTEM VALUE VALUES (11, 'R-DAILY', 'Daily', 4, 'sr', 'daily', 127)")
            for (u in listOf(601L, 602L, 603L)) h.execute("INSERT INTO app.app_user (id, username, full_name, role, must_change_password) OVERRIDING SYSTEM VALUE VALUES ($u, 'sr$u', 'SR $u', 'SR', false)")
            h.execute("INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from) VALUES (11, 601, 'primary', '2026-01-01')")
        }
    }

    @AfterAll
    fun tearDown() = fresh.close()

    /**
     * The geography snapshot is cached for 60 s, but routes and assignments are read live. A zone (and its route)
     * created inside that window makes routesFor throw NoSuchElementException (getValue on the stale zone map), so
     * the SR's bundle would fail with a 500 for up to a minute, e.g. during a cutover import.
     */
    @Test
    fun aRouteInAZoneCreatedAfterTheGeoSnapshotDoesNotCrashThePlanner() {
        val geo = GeoRepository(fresh.db)
        val planner = SqlRoutePlanner(fresh.db, geo, RegistryDefaults())
        assertEquals(1, planner.routesFor(601, sunday).size) // warms the geo cache
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.zone (id, code, name, territory_id) OVERRIDING SYSTEM VALUE VALUES (40, 'Z40', 'New zone', 3)")
            h.execute("INSERT INTO app.route (id, code, name, zone_id, kind, visit_kind, visit_days_mask) OVERRIDING SYSTEM VALUE VALUES (41, 'R-NEW', 'New', 40, 'sr', 'daily', 127)")
            h.execute("INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from) VALUES (41, 602, 'primary', '2026-01-01')")
        }
        val routes = runCatching { planner.routesFor(602, sunday) }
        assertTrue(routes.isSuccess, "planner failed: ${routes.exceptionOrNull()}")
        assertEquals(listOf(true), routes.getOrThrow().map { it.plannedToday })
    }

    /**
     * visit_kind and visit_days_mask come from different rows: COALESCE(p.visit_kind, r.visit_kind) falls back to the
     * route's kind when the effective route_planned row has a null kind, so a Daily route re-planned to Sat/Mon/Wed
     * (mask 21, kind null) is reported as visit_kind "daily" with mask 21, which the contract (daily = 127) and the
     * schema check app.visit_kind_matches both forbid.
     */
    @Test
    fun visitKindAndMaskComeFromTheSameEffectiveRow() {
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.route (id, code, name, zone_id, kind, visit_kind, visit_days_mask) OVERRIDING SYSTEM VALUE VALUES (21, 'R-REPLAN', 'Replan', 4, 'sr', 'daily', 127)")
            h.execute("INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from) VALUES (21, 603, 'primary', '2026-01-01')")
            h.execute("INSERT INTO app.route_planned (route_id, visit_kind, visit_days_mask, valid_from) VALUES (21, NULL, 21, '2026-10-10')")
        }
        val planner = SqlRoutePlanner(fresh.db, GeoRepository(fresh.db), RegistryDefaults())
        val r = planner.routesFor(603, LocalDate.parse("2026-10-12")).single()
        assertEquals(21, r.visitDaysMask)
        val kind = r.visitKind
        assertTrue(kind == null || DayPlan.consistent(DayPlan.VisitKind.entries.first { it.wire == kind }, r.visitDaysMask),
            "visit_kind $kind does not match mask ${r.visitDaysMask}")
    }
}
