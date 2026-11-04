package com.aktcl.aron.backend.analytics

import com.aktcl.aron.rules.Percent
import com.aktcl.aron.rules.Qty
import com.aktcl.aron.rules.Targets
import com.aktcl.aron.rules.UiLocale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sales cards of screenshot 6 (build contract s4.2, D-TSO-MAN-08 and 10, contract 1.10.0 `NationalSalesCard`, lead ruling
 * R-F-13) through the national SQL on tso_dashboard_day.sql: `monthly_target_qty` T, sold quantity A of sale lines only and the
 * working days W at the scope's lowest common node; the ring `achievement_pct_bp` = round_half_up(10000 A W / T) computed once from
 * the integers, null when W = 0 or T is null or 0. The phone's text comes from the same integers through the F3 primitives.
 * Also the Login / Bikroy Joma percentages of screenshot 7 (D-TSO-MAN-07).
 */
class NationalExactPctTest : TsoDayFixture() {
    private val en = UiLocale.EN

    private fun cards(uid: Long) = national().sales(caller(uid), all).let { s -> s.working_days to s.cards.associateBy { it.category_code } }

    @Test
    fun theFixturesOfSection42ThroughSql() {
        val (w, c) = cards(32)                                            // the territory: W = 25 (October less 5 Fridays and the 21st)
        assertEquals(25, w)
        // Cigarette A = 40, T = 42000: "40.0", "1.7 K", "2.4%" (the 30 free and DRP sticks never count).
        val cig = c.getValue("cigarette")
        assertEquals(40L, cig.total_sales); assertEquals(42_000L, cig.monthly_target_qty); assertEquals(2, cig.target_routes)
        assertEquals("1680.000", cig.daily_target); assertEquals("1640.000", cig.remaining); assertEquals(238L, cig.achievement_pct_bp)
        assertEquals(listOf("40.0", "1.7 K", "2.4%"), listOf(Qty.text(cig.total_sales, en), Qty.text(cig.monthly_target_qty, w.toLong(), en),
            Percent.text(Targets.dailyRingTenths(cig.total_sales, cig.monthly_target_qty!!, w.toLong()), 1, en)))
        // Bidi A = 2991, T = 26000: "3.0 K", "1.0 K", "287.6%" (uncapped).
        val bidi = c.getValue("bidi")
        assertEquals(2991L, bidi.total_sales); assertEquals(26_000L, bidi.monthly_target_qty); assertEquals("1040.000", bidi.daily_target)
        assertEquals("0.000", bidi.remaining); assertEquals(28_760L, bidi.achievement_pct_bp)
        assertEquals(listOf("3.0 K", "1.0 K", "287.6%"), listOf(Qty.text(bidi.total_sales, en), Qty.text(bidi.monthly_target_qty, w.toLong(), en),
            Percent.text(Targets.dailyRingTenths(bidi.total_sales, bidi.monthly_target_qty!!, w.toLong()), 1, en)))
        // Lighter A = 0, T = 100: "0.0", "4.0", "0.0%". Match A = 0, T = 175: "7.0" (R-F-20).
        val lighter = c.getValue("lighter")
        assertEquals(listOf("0.0", "4.0", "0.0%"), listOf(Qty.text(lighter.total_sales, en), Qty.text(lighter.monthly_target_qty, w.toLong(), en),
            Percent.text(Targets.dailyRingTenths(lighter.total_sales, lighter.monthly_target_qty!!, w.toLong()), 1, en)))
        assertEquals(0L, lighter.achievement_pct_bp); assertEquals("4.000", lighter.daily_target)
        val match = c.getValue("match")
        assertEquals("7.0", Qty.text(match.monthly_target_qty, w.toLong(), en)); assertEquals("7.000", match.daily_target); assertEquals(0L, match.achievement_pct_bp)
        assertEquals(listOf("cigarette", "bidi", "lighter", "match"), national().sales(caller(32), all).cards.map { it.category_code }, "the brief's order")
    }

    @Test
    fun theRingIsExactFromTheIntegersNeverFromTheRoundedDailyTarget() {
        // Exact Zone: T = 7, A = 1, W = 24 (its zone holiday on the 13th). Daily target 7 / 24 = 0.2916.. prints "0.292"; the ring is
        // round_half_up(10000 x 1 x 24 / 7) = 34286 bp, while 1 / 0.292 would give 34247.
        val (w, c) = cards(37)
        assertEquals(24, w)
        val cig = c.getValue("cigarette")
        assertEquals(1L, cig.total_sales); assertEquals(7L, cig.monthly_target_qty)
        assertEquals("0.292", cig.daily_target); assertEquals("0.000", cig.remaining)
        assertEquals(34_286L, cig.achievement_pct_bp)
        assertNotEquals(34_247L, cig.achievement_pct_bp)
        assertEquals("342.9%", Percent.text(Targets.dailyRingTenths(1, 7, 24), 1, en))
        // The zone holiday of Banani - Test counts at that zone only: W 24 there, the daily target 42000 / 24 = 1750.
        val (wz, cz) = cards(33)
        assertEquals(24, wz); assertEquals("1750.000", cz.getValue("cigarette").daily_target); assertEquals(229L, cz.getValue("cigarette").achievement_pct_bp)
        assertEquals(25, national().sales(caller(31), all).working_days, "the national node is global: a zone holiday never moves it")
    }

    @Test
    fun aNodeWithoutAWorkingDayPrintsADashNeverADivision() {
        // R-F-13: every non-Friday of October declared a holiday in Exact Zone: W = 0 there; T is still sent, nothing is divided by W.
        sql("""
            INSERT INTO app.calendar_holiday (date, scope_type, scope_id, kind, selling_day, name_en)
            SELECT d::date, 'zone', ${id("ZX")}, 'holiday', false, 'B2 W = 0' FROM generate_series(DATE '2026-10-01', DATE '2026-10-31', interval '1 day') d
             WHERE extract(isodow FROM d) <> 5 AND d::date <> DATE '2026-10-13'
        """.trimIndent())
        val (w, c) = cards(37)
        assertEquals(0, w)
        val cig = c.getValue("cigarette")
        assertEquals(7L, cig.monthly_target_qty); assertEquals(1L, cig.total_sales)
        assertNull(cig.daily_target); assertNull(cig.remaining); assertNull(cig.achievement_pct_bp)
        assertEquals(Percent.DASH, Qty.text(cig.monthly_target_qty, w.toLong(), en)); assertEquals(Percent.DASH, Percent.text(Targets.dailyRingTenths(1, 7, 0), 1, en))
    }

    @Test
    fun aScopeWithoutATargetHasNoTargetFigures() {
        val (w, c) = cards(34)                                            // Gulshan - Test: no October target on R7, R8, R9
        assertEquals(25, w)
        for (card in c.values) {
            assertNull(card.monthly_target_qty, card.category_code); assertNull(card.daily_target); assertNull(card.remaining); assertNull(card.achievement_pct_bp)
            assertEquals(0, card.target_routes)
        }
        assertEquals(Percent.DASH, Qty.text(null, en))
        assertEquals(2991L - 2990L, c.getValue("bidi").total_sales, "the excused route's one stick: Total Sales counts every route")
    }

    @Test
    fun loginAndBikroyJomaOfScreenshot7() {
        val ls = national().loginSubmit(caller(32), all)
        assertEquals(listOf(5, 2, 1), listOf(ls.target_routes, ls.successful_login, ls.submitted))
        assertEquals(4000, ls.login_pct_bp); assertEquals(2000, ls.submit_of_target_pct_bp); assertEquals(5000, ls.submit_pct_bp)
        assertEquals(listOf("40%", "20%"), listOf(Percent.wholeText(2, 5, en), Percent.wholeText(1, 5, en)))
        val none = national().loginSubmit(caller(37), all)                 // Exact Zone plans no route: no target route, no percentage
        assertEquals(0, none.target_routes); assertNull(none.submit_of_target_pct_bp); assertNull(none.login_pct_bp)
    }

    @Test
    fun cprAndGeoOfScreenshots6And7() {
        val cpr = national().cpr(caller(32), all)
        assertEquals(119 to 3, cpr.target_outlets to cpr.successful_calls); assertEquals(252, cpr.cpr_pct_bp)
        assertEquals("2.5%", Percent.tenthsText(3, 119, en))
        val geo = national().geoFencing(caller(32), all)
        assertEquals(4 to 4, geo.total_calls to geo.geo_validated, "the abandoned call and the calls of the AMO, manual, dummy and excused routes are left out")
        assertEquals(10_000, geo.geo_pct_bp); assertEquals("100.0%", Percent.tenthsText(4, 4, en))
    }

    /** The pie label of the By Channel STD card: `<bp 2dp>% <Channel> Channel` (s4.2), printed from the server's integers. */
    private fun pieLabels(r: NationalChannelStd): List<String> =
        r.channels.filter { it.value > 0 }.map { "${Percent.text(it.share_pct_bp?.toLong(), 2, en)} ${it.channel} Channel" }

    @Test
    fun byChannelStdOfOneChannelIsOneHundredPercent() {
        // s4.2 "one channel -> 100.00% Astha Channel": Exact Zone sells its 1 cigarette stick at OX, an Astha outlet.
        val one = national().channelStd(caller(37), all)
        assertEquals(1L, one.total); assertEquals("stick", one.unit)
        assertEquals(listOf(Triple("Astha", 1L, 10_000)), one.channels.filter { it.value > 0 }.map { Triple(it.channel, it.value, it.share_pct_bp) })
        assertEquals(listOf("100.00% Astha Channel"), pieLabels(one))
        assertEquals(CHANNELS, one.channels.map { it.channel }, "every channel in the brief's order")
        assertTrue(one.channels.filter { it.value == 0L }.all { it.share_pct_bp == 0 }, "the other channels are 0, never null, beside a sale")
        // Territory Apsis: GT 30 sticks (O1 20, and the AMO, manual and dummy routes' 5 + 3 + 2: STD counts every route) and Astha 10.
        val two = national().channelStd(caller(32), all)
        assertEquals(40L, two.total)
        assertEquals(listOf("GT" to 7_500, "Astha" to 2_500), two.channels.filter { it.value > 0 }.map { it.channel to it.share_pct_bp })
        assertEquals(listOf("75.00% GT Channel", "25.00% Astha Channel"), pieLabels(two))
    }

    @Test
    fun cprOf29Over1186IsTwoPointFourNeverTwoPointFive() {
        // s4.2 "29/1186 -> 2.4% (never 2.5)" through the real worker and the national SQL. Gulshan - Test's route set is R8 and R9
        // (R7 is excused): R8 now plans 1176 target outlets (R9 10, so 1186) and sells at 29 new outlets (29 successful calls).
        val r8 = id("R8")
        sql("UPDATE app.route_day SET target_outlets = 1176 WHERE business_date = DATE '2026-10-25' AND route_id = $r8")
        sql(
            """
            INSERT INTO app.outlet (code, name, owner_name, zone_id, route_id, cluster_id, channel)
            SELECT 'OC' || lpad(i::text, 2, '0'), 'Outlet OC' || i, 'Owner', r.zone_id, r.id, (SELECT c.id FROM app.cluster c WHERE c.zone_id = r.zone_id), 'GT'
              FROM generate_series(1, 29) i CROSS JOIN app.route r WHERE r.id = $r8
            """.trimIndent(),
        )
        sql(
            """
            INSERT INTO app.visit (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, visit_kind, outlet_id, opened_at,
                                   sequence_no, planned, fix_is_mock, verdict, radius_m_used, max_accuracy_m_used, location_basis, geo_action,
                                   close_client_uuid, outcome_code, ended_at)
            SELECT CAST('00000000-0000-4000-8000-0000000c1' || lpad(i::text, 3, '0') AS uuid), CAST('00000000-0000-4000-8000-0000000c1' || lpad(i::text, 3, '0') AS uuid),
                   DATE '2026-10-25', u.id, $r8, TIMESTAMPTZ '2026-10-25 03:00Z' + i * interval '5 minutes', 1, 'sr_call', o.id,
                   TIMESTAMPTZ '2026-10-25 03:00Z' + i * interval '5 minutes', i, true, false, 'in_range', 50, 100, 'master', 'sale_allowed',
                   gen_random_uuid(), 'sold', TIMESTAMPTZ '2026-10-25 03:03Z' + i * interval '5 minutes'
              FROM generate_series(1, 29) i JOIN app.outlet o ON o.code = 'OC' || lpad(i::text, 2, '0') CROSS JOIN app.app_user u WHERE u.username = 'sr334008'
            """.trimIndent(),
        )
        sql(
            """
            INSERT INTO app.memo (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, visit_client_uuid, outlet_id, memo_no,
                                  memo_kind, committed_at, price_list_date, price_type, gross_mtk, offer_discount_mtk, drp_discount_mtk, qc_deduction_mtk, round_adj_mtk,
                                  net_mtk, paid_mtk, due_mtk, is_credit, line_count, discount_line_count, qc_line_count, status, received_at)
            SELECT v.m, v.m, v.business_date, v.user_id, v.route_id, v.opened_at + interval '1 minute', 1, v.client_uuid, v.outlet_id,
                   'sr334008-261025-' || lpad(v.sequence_no::text, 3, '0'), 'sale', v.opened_at + interval '1 minute', DATE '2026-10-01', 'outlet',
                   6250, 0, 0, 0, 0, 6250, 6250, 0, false, 1, 0, 0, 'active', v.opened_at + interval '1 hour'
              FROM (SELECT x.*, CAST('00000000-0000-4000-8000-0000000c2' || lpad(x.sequence_no::text, 3, '0') AS uuid) AS m
                      FROM app.visit x WHERE x.route_id = $r8 AND x.business_date = DATE '2026-10-25') v
            """.trimIndent(),
        )
        sql(
            """
            INSERT INTO app.memo_line (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, memo_client_uuid, line_no, sku_id,
                                       line_kind, qty_entered, unit_entered, pack_factor, qty_base, price_type, price_valid_from, base_price_mtk, price_per_qty, gross_mtk)
            SELECT gen_random_uuid(), m.client_uuid, m.business_date, m.user_id, m.route_id, m.captured_at, 1, m.client_uuid, 1, s.id, 'sale', 1, s.base_unit, 1, 1, 'outlet',
                   DATE '2026-10-01', 6250, 1, 6250
              FROM app.memo m CROSS JOIN app.sku s WHERE m.route_id = $r8 AND m.business_date = DATE '2026-10-25' AND s.code = 'MR20'
            """.trimIndent(),
        )
        rebuild()
        val cpr = national().cpr(caller(34), all)
        assertEquals(1186 to 29, cpr.target_outlets to cpr.successful_calls)
        assertEquals(245, cpr.cpr_pct_bp)
        // The ring and the figure print tenths from the integers once: 29 / 1186 = 2.445.. -> "2.4%". Rounding the 245 bp again would give 2.5.
        assertEquals("2.4%", Percent.text(Percent.tenths(cpr.successful_calls.toLong(), cpr.target_outlets.toLong()), 1, en))
        assertEquals("2.4%", Percent.tenthsText(29, 1186, en))
        assertNotEquals("2.5%", Percent.tenthsText(cpr.successful_calls.toLong(), cpr.target_outlets.toLong(), en))
        assertEquals(25L, (cpr.cpr_pct_bp!! + 5L) / 10, "the double-rounding trap the phone must not take")
        // The summary reads the same route set (D-TSO-MAN-09).
        assertEquals(1186 to 29, summary(34).kpis.let { it.target_outlets to it.successful_calls })
    }
}
