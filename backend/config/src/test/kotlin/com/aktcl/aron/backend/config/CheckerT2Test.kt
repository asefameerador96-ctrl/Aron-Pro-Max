package com.aktcl.aron.backend.config

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.contract.Role
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Independent checker (T2) refutation probes for F-API-058..064. Each test asserts the CORRECT behaviour; a failure is a defect. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CheckerT2Test {
    private lateinit var env: SeededConfigDb
    private val clock = TestClock()
    private lateinit var svc: ConfigService
    private lateinit var tools: ConfigTools
    private lateinit var geo: ConfigGeoReports
    private lateinit var perms: ConfigPermissions
    private var outlet = 0L
    private var zone = 0L

    @BeforeAll fun up() {
        env = SeededConfigDb()
        val r = ConfigResolver(env.fresh.db, clock)
        svc = ConfigService(env.fresh.db, r, clock)
        tools = ConfigTools(env.fresh.db, svc, r, clock)
        geo = ConfigGeoReports(env.fresh.db, svc, r, clock)
        perms = ConfigPermissions(env.fresh.db, svc, clock)
        outlet = env.one("SELECT id FROM app.outlet ORDER BY id LIMIT 1")!!.toLong()
        zone = env.one("SELECT zone_id FROM app.outlet WHERE id = $outlet")!!.toLong()
    }
    @AfterAll fun down() = env.close()

    private fun visit(distance: Double, verdict: String, daysAgo: Int = 0) {
        val sr = env.ids.getValue("sr1001")
        env.fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate(
                "INSERT INTO app.visit (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, visit_kind, outlet_id, opened_at, sequence_no, planned, verdict, radius_m_used, max_accuracy_m_used, location_basis, geo_action, distance_m) " +
                    "VALUES (CAST(:u AS uuid), CAST(:f AS uuid), app.dhaka_date(now()) - :ago, :sr, now(), 0, 'sr_call', :o, now(), 1, true, :v, 100, 100, 'master', 'sale_allowed', :d)",
            ).bind("u", UUID.randomUUID().toString()).bind("f", UUID.randomUUID().toString()).bind("ago", daysAgo).bind("sr", sr).bind("o", outlet).bind("v", verdict).bind("d", distance).execute()
        }
    }

    @Test fun whatIfDaysOneDoesNotReachBackTwoBusinessDates() {
        visit(500.0, "out_of_range", daysAgo = 1)
        val r = tools.whatIf("outlet", outlet, 600, 1)
        assertEquals(0, r.visits_evaluated, "days=1 must cover today only (or at most 24 h), not yesterday too")
    }

    @Test fun calibrationHistogramSumsToVisits() {
        visit(7000.0, "out_of_range")
        val row = geo.calibration("zone", zone).rows.single { true }
        assertEquals(row.visits, row.histogram.sumOf { it.visits }, "a visit beyond the last bucket is silently dropped from the histogram")
    }

    @Test fun blastRadiusDevicesEqualReachTargetedForSuspendedDevice() {
        env.fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.device SET status = 'suspended'") }
        val b = tools.blastRadius("global", null).devices
        val v = svc.currentVersion()
        val reach = tools.reach(v, null).devices_targeted
        env.fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.device SET status = 'active'") }
        assertEquals(reach, b, "blast devices (bindings) vs reach devices_targeted (active devices)")
    }

    @Test fun blastRadiusRoleScopeUsersMatchDevicesBound() {
        val ord = env.one("SELECT ordinal FROM app.role_def WHERE role = 'SR'")!!.toLong()
        val b = tools.blastRadius("role", ord)
        assertTrue(b.devices == 0 || b.users > 0, "role scope reports ${b.devices} devices but ${b.users} users")
    }

    @Test fun permissionCreateOnlyDoesNotReadBackAsEdit() {
        val super1 = env.principal("superadmin1001", Role.SUPERADMIN)
        val ch = perms.write(super1, "SUPPORT", RolePermissionsWrite(listOf(MenuPermissionDto("reports.sales", listOf("create"))), "checker probe create only"), null)
        val approver = env.principal("superadmin1002", Role.SUPERADMIN)
        svc.decide(approver, ch.change_id, ConfigDecisionIn("approve", null), null)
        val m = perms.matrix().roles.single { it.role == "SUPPORT" }.menus.single { it.menu_id == "reports.sales" }
        assertEquals(listOf("create"), m.actions, "granting create must not also grant edit")
    }

    @Test fun densityRowsHonourRouteScope() {
        val route = env.one("SELECT id FROM app.route ORDER BY id LIMIT 1")!!.toLong()
        val rep = geo.density("route", route)
        val inRoute = env.one("SELECT count(*) FROM app.outlet WHERE route_id = $route AND status='active' AND lat IS NOT NULL")!!.toInt()
        assertEquals(inRoute, rep.rows.sumOf { it.outlets }, "route-scoped density counts the whole zone, not the route")
    }
}
