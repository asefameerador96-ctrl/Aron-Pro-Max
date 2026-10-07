package com.aktcl.aron.backend.config

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** F-API-058 (what-if), F-API-059 (blast radius), F-API-062 reads (version detail), F-API-063 (reach and pending). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigToolsTest {
    private lateinit var env: SeededConfigDb
    private val clock = TestClock()
    private lateinit var svc: ConfigService
    private lateinit var tools: ConfigTools
    private var outlet = 0L
    private var zone = 0L

    @BeforeAll fun setUp() {
        env = SeededConfigDb()
        val resolver = ConfigResolver(env.fresh.db, clock)
        svc = ConfigService(env.fresh.db, resolver, clock)
        tools = ConfigTools(env.fresh.db, svc, resolver, clock)
        outlet = env.one("SELECT id FROM app.outlet ORDER BY id LIMIT 1")!!.toLong()
        zone = env.one("SELECT zone_id FROM app.outlet WHERE id = $outlet")!!.toLong()
    }
    @AfterAll fun tearDown() = env.close()

    private fun visit(distance: Double, verdict: String, radius: Int = 100) {
        val sr = env.ids.getValue("sr1001")
        env.fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate(
                "INSERT INTO app.visit (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, visit_kind, outlet_id, opened_at, sequence_no, planned, verdict, radius_m_used, max_accuracy_m_used, location_basis, geo_action, distance_m) " +
                    "VALUES (CAST(:u AS uuid), CAST(:f AS uuid), app.dhaka_date(now()), :sr, now(), 0, 'sr_call', :o, now(), 1, true, :v, :r, 100, 'master', 'sale_allowed', :d)",
            ).bind("u", UUID.randomUUID().toString()).bind("f", UUID.randomUUID().toString()).bind("sr", sr).bind("o", outlet).bind("v", verdict).bind("r", radius).bind("d", distance).execute()
        }
    }

    @Test
    fun whatIfCountsVisitsWhoseVerdictWouldChange() {
        visit(60.0, "in_range"); visit(90.0, "in_range"); visit(130.0, "out_of_range"); visit(400.0, "out_of_range")
        val r = tools.whatIf("zone", zone, 150, 30)
        assertEquals(4, r.visits_evaluated); assertEquals(1, r.to_valid); assertEquals(0, r.to_invalid); assertEquals(3, r.unchanged)
        val tight = tools.whatIf("outlet", outlet, 50, 30)
        assertEquals(2, tight.to_invalid); assertEquals(0, tight.to_valid)
        assertEquals(0, tools.whatIf("global", null, 150, 1).let { it.visits_evaluated - 4 })
        assertFailsWith<ApiProblem> { tools.whatIf("zone", zone, 5, 30) }
        assertFailsWith<ApiProblem> { tools.whatIf("zone", 987654, 100, 30) }
    }

    @Test
    fun blastRadiusCountsTheScopeAndMatchesTheCommittedChange() {
        val b = tools.blastRadius("zone", zone)
        assertTrue(b.zones == 1 && b.outlets > 0 && b.routes > 0 && b.users >= 1)
        val g = tools.blastRadius(null, null)
        assertTrue(g.outlets >= b.outlets && g.zones >= 1)
        val c = svc.create(env.principal("admin1001", Role.ADMIN), ConfigChangeRequestIn("Check the blast numbers", false, listOf(ConfigChangeItemIn("cfg.geo.fix_timeout_s", "zone", zone, JsonPrimitive(12)))), null, null)
        assertEquals(tools.blastRadius("zone", zone).let { Triple(it.zones, it.routes, it.outlets) }, c.blast_radius.let { Triple(it.zones, it.routes, it.outlets) })
        assertEquals(tools.blastRadius("zone", zone).devices, c.blast_radius.devices)
    }

    @Test
    fun versionDetailListsTheRowsOfThatVersionAndUnknownIs404() {
        val c = svc.create(env.principal("admin1001", Role.ADMIN), ConfigChangeRequestIn("Version detail check", false, listOf(ConfigChangeItemIn("cfg.geo.refresh_max", "global", 0, JsonPrimitive(4)))), null, null)
        val d = tools.versionDetail(c.config_version!!)
        assertEquals(c.change_id, d.change_id); assertEquals(listOf("cfg.geo.refresh_max"), d.values.map { it.key })
        assertEquals(ProblemCode.ERR_NOT_FOUND, assertFailsWith<ApiProblem> { tools.versionDetail(99999) }.code)
    }

    @Test
    fun reachCountsTargetedAppliedAckedAndPendingDevices() {
        val sr = env.ids.getValue("sr1001")
        val c = svc.create(env.principal("admin1001", Role.ADMIN), ConfigChangeRequestIn("Reach check change", false, listOf(ConfigChangeItemIn("cfg.geo.refresh_max", "global", 0, JsonPrimitive(5)))), null, null)
        val v = c.config_version!!
        val before = tools.reach(v, null)
        assertTrue(before.devices_targeted >= 1); assertEquals(before.devices_targeted, before.devices_applied + before.devices_pending)
        val dev = env.fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT device_id FROM app.device_binding WHERE user_id = :u AND status = 'active'").bind("u", sr).mapTo(Long::class.java).findOne().orElse(-1L) }
        if (dev > 0) {
            env.fresh.db.jdbi.useHandle<Exception> { h -> h.createUpdate("UPDATE app.device SET config_version_applied = :v WHERE id = :d").bind("v", v).bind("d", dev).execute() }
            val after = tools.reach(v, null)
            assertEquals(before.devices_applied + 1, after.devices_applied)
            assertTrue(tools.pending(v, null, 50, null).items.none { it.device_id == dev })
        }
        assertEquals(tools.reach(v, null).devices_pending, tools.pending(v, null, 500, null).items.size)
        assertEquals(ProblemCode.ERR_NOT_FOUND, assertFailsWith<ApiProblem> { tools.reach(99999, null) }.code)
    }
}
