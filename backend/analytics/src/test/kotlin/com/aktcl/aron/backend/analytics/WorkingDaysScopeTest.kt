package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachNode
import com.aktcl.aron.backend.platform.WorkingCalendarRepo
import com.aktcl.aron.contract.Role
import com.aktcl.aron.rules.NodePath
import kotlinx.datetime.DayOfWeek
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The working days at a node (D-TSO-MAN-08, CR-6 "global-only holidays"): the weekend resolved at the node
 * (`cfg.calendar.weekend_days`: division row, else wing row, else global) and the declarations on the node's path, counted by
 * the one WorkingDays. October 2026 has five Fridays (2, 9, 16, 23, 30), five Saturdays and four Sundays.
 * Geography: wing W1 (division D1: territory T1 with zones Z1, Z2; division D2 with Friday + Saturday weekend: territory T2 with
 * zone Z3, a territory holiday on Tuesday the 13th), wing W2 with a Sunday weekend (division D3, territory T3, zone Z4).
 * Test 1 builds the build contract's s4.2 "Working days" fixture step by step; test 2 reads the national cards on its end state.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class WorkingDaysScopeTest {
    private lateinit var fresh: FreshDb
    private val now = Instant.parse("2026-10-25T06:00:00Z")
    private val october = YearMonth.of(2026, 10)
    private val day = LocalDate.parse("2026-10-25")
    private val ids = mutableMapOf<String, Long>()

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        sql(
            """
            INSERT INTO app.wing (code, name) VALUES ('W1', 'Wing One'), ('W2', 'Wing Two');
            INSERT INTO app.division (code, name, wing_id) SELECT d.c, d.c, w.id FROM (VALUES ('D1', 'W1'), ('D2', 'W1'), ('D3', 'W2')) d(c, wc) JOIN app.wing w ON w.code = d.wc;
            INSERT INTO app.territory (code, name, division_id) SELECT t.c, t.c, d.id FROM (VALUES ('T1', 'D1'), ('T2', 'D2'), ('T3', 'D3')) t(c, dc) JOIN app.division d ON d.code = t.dc;
            INSERT INTO app.zone (code, name, territory_id) SELECT z.c, z.c, t.id FROM (VALUES ('Z1', 'T1'), ('Z2', 'T1'), ('Z3', 'T2'), ('Z4', 'T3')) z(c, tc) JOIN app.territory t ON t.code = z.tc;
            INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask)
              SELECT 'R' || substr(z.code, 2), 'Route ' || z.code, z.id, 'sr', 'daily', 127 FROM app.zone z;
            INSERT INTO app.app_user (username, full_name, role, must_change_password) VALUES ('wdadmin', 'WD Admin', 'ADMIN', false);
            INSERT INTO app.product_node (level, code, name) VALUES ('category', 'CIG', 'Cigarette');
            INSERT INTO app.product_node (level, code, name, parent_id) SELECT 'segment', 'SG', 'Seg', id FROM app.product_node WHERE code = 'CIG';
            INSERT INTO app.product_node (level, code, name, parent_id) SELECT 'brand', 'BR', 'Brand', id FROM app.product_node WHERE code = 'SG';
            INSERT INTO app.product_node (level, code, name, parent_id) SELECT 'variant', 'VA', 'Variant', id FROM app.product_node WHERE code = 'BR';
            INSERT INTO app.sku (code, variant_id, category_code, name, short_name, base_unit, base_per_pack, entry_unit_default)
              SELECT 'SKU1', id, 'cigarette', 'Sku One', 'S1', 'stick', 20, 'stick' FROM app.product_node WHERE code = 'VA';
            INSERT INTO app.route_target_batch (batch_uuid, content_sha256, row_count, committed_by)
              SELECT '00000000-0000-4000-8000-0000000000d8', decode(repeat('d8', 32), 'hex'), 1, id FROM app.app_user WHERE username = 'wdadmin';
            INSERT INTO app.route_monthly_target (route_id, sku_id, month, qty_base, batch_uuid)
              SELECT r.id, s.id, DATE '2026-10-01', 2500, '00000000-0000-4000-8000-0000000000d8' FROM app.route r, app.sku s WHERE r.code = 'R1' AND s.code = 'SKU1';
            INSERT INTO app.calendar_holiday (date, scope_type, scope_id, kind, selling_day, name_en)
              SELECT DATE '2026-10-13', 'territory', id, 'holiday', false, 'T2 holiday' FROM app.territory WHERE code = 'T2';
            INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, effective_to, config_version, reason)
              SELECT 'cfg.calendar.weekend_days', 'division', d.id, '[5, 6]'::jsonb, TIMESTAMPTZ '2026-01-01 00:00Z', NULL, (SELECT max(config_version) FROM app.cfg_version), 'b2 test'
                FROM app.division d WHERE d.code = 'D2';
            INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, effective_to, config_version, reason)
              SELECT 'cfg.calendar.weekend_days', 'wing', w.id, '[7]'::jsonb, TIMESTAMPTZ '2026-01-01 00:00Z', NULL, (SELECT max(config_version) FROM app.cfg_version), 'b2 test'
                FROM app.wing w WHERE w.code = 'W2';
            -- A wing row that is no longer valid at the instant must not count.
            INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, effective_to, config_version, reason)
              SELECT 'cfg.calendar.weekend_days', 'wing', w.id, '[1, 2, 3]'::jsonb, TIMESTAMPTZ '2025-01-01 00:00Z', TIMESTAMPTZ '2025-12-31 00:00Z', (SELECT max(config_version) FROM app.cfg_version), 'b2 test'
                FROM app.wing w WHERE w.code = 'W1';
            """,
        )
        fresh.db.jdbi.useHandle<Exception> { h ->
            // As NationalDashboardTest: the master rows were written today, after the business date; without their history the
            // as-was joins of the cards read the current geography and products.
            h.execute("DO $$ DECLARE t text; BEGIN FOR t IN SELECT history_table FROM app.master_history_map LOOP EXECUTE format('DELETE FROM app.%I', t); END LOOP; END $$")
            Aggregator.refreshDimensions(h)
            for (t in listOf("wing", "division", "territory", "zone", "route")) h.createQuery("SELECT code, id FROM app.$t").map { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().forEach { (k, v) -> ids[k] = v }
        }
    }

    @AfterAll
    fun tearDown() = fresh.close()

    private fun sql(s: String) = fresh.dataSource.connection.use { c -> c.createStatement().use { it.execute(s) } }

    private fun holiday(date: String, scope: String = "global", code: String? = null, kind: String = "holiday") =
        sql("INSERT INTO app.calendar_holiday (date, scope_type, scope_id, kind, selling_day, name_en) VALUES ('$date', '$scope', ${code?.let { ids.getValue(it) } ?: 0}, '$kind', ${kind == "makeup_day"}, 'B2')")

    /** W of October at the node [level] [code] (or the global node). */
    private fun w(level: String = "national", code: String? = null): Int = fresh.db.jdbi.withHandle<Int, Exception> { h ->
        val path = WorkingCalendarRepo.pathOf(h, level, code?.let { ids.getValue(it) } ?: 0)!!
        WorkingCalendarRepo.load(h, october, now).workingDays(path)
    }

    @Test
    @Order(1)
    fun theWorkingDaysFixtureOfTheBuildContractStepByStep() {
        // s4.2: Oct 2026: 31 - 5 Fridays = 26.
        assertEquals(listOf(26, 26, 26), listOf(w(), w("territory", "T1"), w("zone", "Z1")))
        // A global holiday on Wednesday the 21st: 25 everywhere.
        holiday("2026-10-21")
        assertEquals(listOf(25, 25, 25), listOf(w(), w("territory", "T1"), w("zone", "Z1")))
        // Plus a zone holiday on Monday the 12th: zone 24, its territory 25, its sibling zone 25, global 25 (CR-6: no longer global-only).
        holiday("2026-10-12", "zone", "Z1")
        assertEquals(listOf(25, 25, 24, 25), listOf(w(), w("territory", "T1"), w("zone", "Z1"), w("zone", "Z2")))
        // Friday the 30th declared selling (global make-up day): 26 (zone 25).
        holiday("2026-10-30", kind = "makeup_day")
        assertEquals(listOf(26, 26, 25), listOf(w(), w("territory", "T1"), w("zone", "Z1")))
        // The most specific declaration on the path decides: a zone make-up day on the global holiday works in that zone only.
        holiday("2026-10-21", "zone", "Z2", kind = "makeup_day")
        assertEquals(listOf(26, 26, 27, 25), listOf(w(), w("territory", "T1"), w("zone", "Z2"), w("zone", "Z1")))
        // The weekend at the node: D2's row (Friday and Saturday) for T2 and Z3: 31 - 5 - 5 = 21, - the 21st = 20, + the 30th = 21,
        // - T2's holiday on the 13th = 20. W2's row (Sunday): 31 - 4 = 27, - the 21st = 26; the make-up Friday was a working day already.
        assertEquals(listOf(20, 20, 26, 26), listOf(w("territory", "T2"), w("zone", "Z3"), w("zone", "Z4"), w("wing", "W2")))
        // A division of W1 without a row of its own takes the global Friday (W1's expired row does not count).
        fresh.db.jdbi.useHandle<Exception> { h ->
            val cals = WorkingCalendarRepo.load(h, october, now)
            assertEquals(setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY), cals.weekendAt(WorkingCalendarRepo.pathOf(h, "zone", ids.getValue("Z3"))!!))
            assertEquals(setOf(DayOfWeek.SUNDAY), cals.weekendAt(WorkingCalendarRepo.pathOf(h, "zone", ids.getValue("Z4"))!!))
            assertEquals(setOf(DayOfWeek.FRIDAY), cals.weekendAt(WorkingCalendarRepo.pathOf(h, "division", ids.getValue("D1"))!!))
            assertEquals(setOf(DayOfWeek.FRIDAY), cals.weekendAt(NodePath.GLOBAL))
            // The territory node does not see its zones' declarations; the global node sees no scoped one.
            assertEquals(NodePath(ids.getValue("W1"), ids.getValue("D1"), ids.getValue("T1")), WorkingCalendarRepo.pathOf(h, "territory", ids.getValue("T1")))
        }
        // The legacy global count of NationalDashboard agrees with the global node.
        assertEquals(26, fresh.db.jdbi.withHandle<Int, Exception> { WorkingCalendar.workingDays(it, october) })
    }

    @Test
    @Order(2)
    fun theNationalCardsCountAtTheLowestCommonNodeOfTheirScope() {
        val svc = NationalDashboardService(fresh.db, AronClock { now })
        val national = NationalCaller(Reach(1, Role.ANALYST, day, true, emptySet(), emptySet(), false, emptyList()))
        fun tso(vararg z: String) = NationalCaller(Reach(2, Role.TSO, day, false, z.map { ids.getValue(it) }.toSet(), emptySet(), false, listOf(ReachNode("zone", ids.getValue(z.first())))))
        fun wd(c: NationalCaller, f: NationalDashboardFilter = NationalDashboardFilter(date = "2026-10-25")) = svc.sales(c, f).working_days
        val all = NationalDashboardFilter(date = "2026-10-25")
        // National without a geography filter: the global node (scoped declarations never reach it), whatever zones exist.
        assertEquals(26, wd(national))
        // One zone gives the zone; one territory the territory; zones of two divisions their wing; a wing with one zone that zone.
        assertEquals(25, wd(national, all.copy(zone_ids = listOf(ids.getValue("Z1")))))
        assertEquals(27, wd(national, all.copy(zone_ids = listOf(ids.getValue("Z2")))))
        assertEquals(26, wd(national, all.copy(territory_ids = listOf(ids.getValue("T1")))))
        assertEquals(20, wd(national, all.copy(zone_ids = listOf(ids.getValue("Z3")))))
        assertEquals(26, wd(national, all.copy(zone_ids = listOf(ids.getValue("Z1"), ids.getValue("Z3")))))
        assertEquals(26, wd(national, all.copy(wing_ids = listOf(ids.getValue("W2")))))
        // The token's reach is part of the scope: a TSO of one zone counts its zone without any filter.
        assertEquals(25, wd(tso("Z1"))); assertEquals(26, wd(tso("Z1", "Z2"))); assertEquals(20, wd(tso("Z3")))
        // The daily target follows: R1's 2 500 over Z1's 25 days = 100 a day.
        assertEquals("100.000", svc.sales(tso("Z1"), all).cards.single { it.category_code == "cigarette" }.daily_target)
        assertEquals("100.000", svc.routeMap(national, ids.getValue("R1"), "2026-10-25").target_vs_achievement.single { it.category_code == "cigarette" }.daily_target,
            "the route map counts at the route's zone")
    }
}
