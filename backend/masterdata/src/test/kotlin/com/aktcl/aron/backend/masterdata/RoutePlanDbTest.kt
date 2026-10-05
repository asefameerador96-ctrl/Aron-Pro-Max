package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.RegistryDefaults
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** N-017 acceptance on real PostgreSQL 16 with Daily, 3F (Sun/Tue/Thu) and 2F (Mon/Thu) routes of one SR. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoutePlanDbTest {
    private lateinit var fresh: FreshDb
    private lateinit var planner: SqlRoutePlanner
    private val sr = 501L
    private val sunday = LocalDate.parse("2026-10-04")
    private val friday = LocalDate.parse("2026-10-09")

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.wing (id, code, name) OVERRIDING SYSTEM VALUE VALUES (1, 'W1', 'Wing')")
            h.execute("INSERT INTO app.division (id, code, name, wing_id) OVERRIDING SYSTEM VALUE VALUES (2, 'D2', 'Division', 1)")
            h.execute("INSERT INTO app.territory (id, code, name, division_id) OVERRIDING SYSTEM VALUE VALUES (3, 'T3', 'Territory', 2)")
            h.execute("INSERT INTO app.zone (id, code, name, territory_id) OVERRIDING SYSTEM VALUE VALUES (4, 'Z4', 'Zone', 3)")
            h.execute("INSERT INTO app.cluster (id, zone_id, name) OVERRIDING SYSTEM VALUE VALUES (5, 4, 'Cluster')")
            h.execute("INSERT INTO app.route (id, code, name, zone_id, kind, visit_kind, visit_days_mask, display_label) OVERRIDING SYSTEM VALUE VALUES " +
                "(11, 'R-DAILY', 'Banani Daily', 4, 'sr', 'daily', 127, 'Daily'), " +
                "(12, 'R-3F', 'Banani 3F', 4, 'sr', '3f', 42, '(Sun, Tue, Thu)'), " +
                "(13, 'R-2F', 'Banani 2F', 4, 'sr', '2f', 36, '(Mon, Thu)')")
            h.execute("INSERT INTO app.app_user (id, username, full_name, role, must_change_password) OVERRIDING SYSTEM VALUE VALUES ($sr, 'sr334001', 'Testing Banani', 'SR', false)")
            for (r in 11..13) h.execute("INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from) VALUES ($r, $sr, 'primary', '2026-01-01')")
            for (i in 1..60) {
                val route = 11 + (i - 1) / 20
                h.execute("INSERT INTO app.outlet (code, name, owner_name, zone_id, route_id, cluster_id, channel, lat, lng, location_basis) VALUES " +
                    "('O$i', 'Outlet $i', 'Owner', 4, $route, 5, 'GT', 23.79 + $i * 0.0001, 90.40, 'master')")
            }
        }
        planner = SqlRoutePlanner(fresh.db, GeoRepository(fresh.db), RegistryDefaults())
    }

    @AfterAll
    fun tearDown() = fresh.close()

    private fun planned(d: LocalDate) = planner.routesFor(sr, d).filter { it.plannedToday }.map { it.code }

    @Test
    fun onASeededSundayTheDailyAnd3fRoutesArePlannedAndThe2fIsNot() {
        assertEquals(DayOfWeek.SUNDAY, sunday.dayOfWeek)
        assertEquals(listOf("R-DAILY", "R-3F"), planned(sunday))
        val all = planner.routesFor(sr, sunday)
        assertEquals(3, all.size, "every assigned route is in the bundle, planned or not")
        assertEquals(listOf("daily", "3f", "2f"), all.map { it.visitKind })
    }

    @Test
    fun onFridayTheWeekendNoneIsPlanned() {
        assertEquals(DayOfWeek.FRIDAY, friday.dayOfWeek)
        assertEquals(emptyList(), planned(friday))
    }

    @Test
    fun theTargetOutletCountEqualsThePlannedRoutesOutlets() {
        assertEquals(40, planner.targetOutlets(sr, sunday))
        assertEquals(0, planner.targetOutlets(sr, friday))
        assertEquals(60, planner.targetOutlets(sr, LocalDate.parse("2026-10-08")), "Thursday plans all three")
    }

    @Test
    fun aHolidayAndAFutureVisitDayChangeComeFromTheDatabase() {
        val monday = LocalDate.parse("2026-10-12")
        assertEquals(listOf("R-DAILY", "R-2F"), planned(monday))
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.calendar_holiday (date, scope_type, scope_id, kind, selling_day, name_en) VALUES ('2026-10-12', 'territory', 3, 'holiday', false, 'Puja')")
            // From 2026-10-18 the 3F route moves to Sat/Mon/Wed (mask 21).
            h.execute("INSERT INTO app.route_planned (route_id, visit_kind, visit_days_mask, valid_from) VALUES (12, '3f', 21, '2026-10-18')")
        }
        assertEquals(emptyList(), planned(monday))
        assertEquals(listOf("R-DAILY", "R-2F"), planned(LocalDate.parse("2026-10-05")), "the past and other days are unchanged")
        assertEquals(listOf("R-DAILY", "R-3F", "R-2F"), planned(LocalDate.parse("2026-10-19")), "Monday after the change")
        assertEquals(listOf("R-DAILY", "R-2F"), planned(LocalDate.parse("2026-10-22")), "Thursday after the change: 3F no longer visits")
    }
}
