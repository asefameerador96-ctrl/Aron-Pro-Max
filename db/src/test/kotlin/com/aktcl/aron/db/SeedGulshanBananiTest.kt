package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * db/seed/06_dev_gulshan_banani.sql (docs/34 s4.4, D-SEED-02): on the dev profile (the seed without 04, as the dev
 * deploy loads it) sr1001 works three Friday-aware Gulshan and Banani routes with 60 outlets plus the owner's three test
 * outlets on GUL-SR-1 (D-NIGHT-SEED-OUTLETS-01), targets and holidays for the three months from the seed's today;
 * on the test profile (every file, as the harnesses load it) the file changes nothing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeedGulshanBananiTest {
    private lateinit var dev: TestDatabase

    private fun loadDevProfile(c: Connection) {
        val files = SeedLoader.files().filterNot { it.name.startsWith("04_") }
        val dir = File(System.getProperty("java.io.tmpdir"), "aron-devseed-" + System.nanoTime()).apply { mkdirs() }
        try {
            files.forEach { it.copyTo(File(dir, it.name)) }
            SeedLoader.load(c, dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @BeforeAll
    fun setUp() {
        dev = TestPostgres.createDatabase().migrated()
        dev.connect().use { loadDevProfile(it) }
    }

    @AfterAll
    fun tearDown() = dev.close()

    private val gul = "('GUL-SR-2', 'GUL-SR-1', 'BAN-SR-11')"

    @Test
    fun theSrWorksThreeFridayAwareRoutesWithSixtyOutletsAndEveryChannel() = dev.connect().use { c ->
        assertEquals("H-GUL|T-DHK-N", c.scalar("SELECT h.code || '|' || t.code FROM app.house h JOIN app.territory t ON t.id = h.territory_id WHERE h.code = 'H-GUL'"))
        assertEquals(
            "BAN-SR-11|Z-BAN|36|20,GUL-SR-1|Z-GUL|18|23,GUL-SR-2|Z-GUL|9|20",
            c.scalar("SELECT string_agg(x, ',' ORDER BY x) FROM (SELECT r.code || '|' || z.code || '|' || r.visit_days_mask || '|' || count(o.id) AS x FROM app.route r JOIN app.zone z ON z.id = r.zone_id JOIN app.outlet o ON o.route_id = r.id WHERE r.code IN $gul GROUP BY r.code, z.code, r.visit_days_mask) t"),
        )
        // Saturday to Thursday covered exactly once, Friday (bit 6) never.
        assertEquals("63", c.scalar("SELECT sum(visit_days_mask) FROM app.route WHERE code IN $gul"))
        assertEquals("Astha,DCC,GT,HoReCa,MT,RCC", c.scalar("SELECT string_agg(DISTINCT o.channel, ',') FROM app.outlet o JOIN app.route r ON r.id = o.route_id WHERE r.code IN $gul"))
        // Open primaries of sr1001: the three new routes only; the Mirpur fixture routes are inactive.
        assertEquals(
            "BAN-SR-11,GUL-SR-1,GUL-SR-2",
            c.scalar("SELECT string_agg(r.code, ',' ORDER BY r.code) FROM app.route_assignment a JOIN app.route r ON r.id = a.route_id JOIN app.app_user u ON u.id = a.user_id WHERE u.username = 'sr1001' AND a.kind = 'primary' AND a.valid_to IS NULL"),
        )
        assertEquals("inactive", c.scalar("SELECT string_agg(DISTINCT status, ',') FROM app.route WHERE code LIKE 'MIR-SR-%'"))
        assertEquals("Z-GUL", c.scalar("SELECT z.code FROM app.app_user u JOIN app.zone z ON z.id = u.home_zone_id WHERE u.username = 'sr1001'"))
        assertEquals("Z-BAN,Z-GUL", c.scalar("SELECT string_agg(z.code, ',' ORDER BY z.code) FROM app.user_scope s JOIN app.zone z ON z.id = s.node_id JOIN app.app_user u ON u.id = s.user_id WHERE u.username = 'amo1001' AND s.node_type = 'zone' AND s.valid_to IS NULL"))
        // Every outlet has its pin, placement and location history inside Gulshan/Banani.
        assertEquals("63|63|63|t", c.scalar("SELECT count(*) || '|' || count(DISTINCT h.outlet_id) || '|' || count(DISTINCT p.outlet_id) || '|' || bool_and(o.lat BETWEEN 23.77 AND 23.80 AND o.lng BETWEEN 90.40 AND 90.43)::text::char FROM app.outlet o JOIN app.route r ON r.id = o.route_id LEFT JOIN app.outlet_location_history h ON h.outlet_id = o.id LEFT JOIN app.outlet_placement_history p ON p.outlet_id = o.id WHERE r.code IN $gul"))
    }

    @Test
    fun theOwnersThreeTestOutletsAreOnGulSr1AfterTheSeededOnes() = dev.connect().use { c ->
        // D-NIGHT-SEED-OUTLETS-01: exact pins, active retail GT at the outlet price, visited after GUL1-001..020, with history rows.
        assertEquals(
            "GUL1-T01|Asef Home|23.788347|90.402357|21;GUL1-T02|Asef Office|23.791886|90.42065|22;GUL1-T03|Asef Head Office|23.795805|90.418034|23",
            c.scalar("SELECT string_agg(o.code || '|' || o.name || '|' || o.lat || '|' || o.lng || '|' || o.visit_sequence, ';' ORDER BY o.code) FROM app.outlet o JOIN app.route r ON r.id = o.route_id WHERE r.code = 'GUL-SR-1' AND o.code LIKE 'GUL1-T%'"),
        )
        assertEquals(
            "3|active|retail|outlet|GT|Z-GUL|Gulshan 1 Circle|master|t|3|3",
            c.scalar("SELECT count(*) || '|' || string_agg(DISTINCT o.status, ',') || '|' || string_agg(DISTINCT o.outlet_kind, ',') || '|' || string_agg(DISTINCT o.price_type, ',') || '|' || string_agg(DISTINCT o.channel, ',') || '|' || string_agg(DISTINCT z.code, ',') || '|' || string_agg(DISTINCT cl.name, ',') || '|' || string_agg(DISTINCT o.location_basis, ',') || '|' || bool_and(o.location_confirmed)::text::char || '|' || count(DISTINCT p.outlet_id) || '|' || count(DISTINCT h.outlet_id) FROM app.outlet o JOIN app.zone z ON z.id = o.zone_id JOIN app.cluster cl ON cl.id = o.cluster_id LEFT JOIN app.outlet_placement_history p ON p.outlet_id = o.id AND p.valid_to IS NULL LEFT JOIN app.outlet_location_history h ON h.outlet_id = o.id WHERE o.code LIKE 'GUL1-T%'"),
        )
        assertEquals("20", c.scalar("SELECT max(o.visit_sequence) FROM app.outlet o WHERE o.code LIKE 'GUL1-0%'"))
        // The geofence radius comes from config like every other outlet's: no outlet-scope override names them.
        assertEquals("0", c.scalar("SELECT count(*) FROM app.cfg_value v JOIN app.outlet o ON o.id = v.scope_id WHERE v.scope_type = 'outlet' AND o.code LIKE 'GUL1-T%'"))
    }

    @Test
    fun targetsAndTheCalendarFollowTheSeedsToday() = dev.connect().use { c ->
        // D-DB-SEED-ROLLING-01: today's month and the next two, never fixed months.
        val months = c.scalar("SELECT string_agg(to_char(date_trunc('month', app.dhaka_date(now())) + k * interval '1 month', 'YYYY-MM-DD'), ',' ORDER BY k) FROM generate_series(0, 2) k")
        assertEquals(months, c.scalar("SELECT string_agg(DISTINCT month::text, ',' ORDER BY month::text) FROM app.route_monthly_target"))
        assertEquals(months, c.scalar("SELECT string_agg(month::text, ',' ORDER BY month) FROM app.route_target_batch b JOIN LATERAL (SELECT DISTINCT month FROM app.route_monthly_target t WHERE t.batch_uuid = b.batch_uuid) m ON true"))
        // Per route and month: about 200,000 cigarette sticks, 30,000 bidi sticks, 500 lighters and 300 dozen matches, in whole packs.
        val first = c.column("SELECT s.category_code || '=' || sum(t.qty_base) FROM app.route_monthly_target t JOIN app.sku s ON s.id = t.sku_id JOIN app.route r ON r.id = t.route_id WHERE r.code = 'GUL-SR-2' AND t.month = date_trunc('month', app.dhaka_date(now()))::date GROUP BY s.category_code ORDER BY s.category_code")
        val totals = first.associate { it!!.substringBefore('=') to it.substringAfter('=').toLong() }
        for ((cat, want) in mapOf("cigarette" to 200_000L, "bidi" to 30_000L, "lighter" to 500L, "match" to 300L)) {
            assertEquals(true, kotlin.math.abs(totals.getValue(cat) - want) <= want / 10, "$cat ${totals[cat]}")
        }
        assertEquals("0", c.scalar("SELECT count(*) FROM app.route_monthly_target t JOIN app.sku s ON s.id = t.sku_id WHERE t.qty_base % s.base_per_pack <> 0"))
        // Holidays: Victory Day and Christmas Day of every December in the window; Durga Puja only on its 2026 date (owner row O).
        assertEquals(
            c.scalar("SELECT count(*) * 2 FROM generate_series(0, 2) k WHERE extract(month FROM date_trunc('month', app.dhaka_date(now())) + k * interval '1 month') = 12"),
            c.scalar("SELECT count(*) FROM app.calendar_holiday WHERE scope_type = 'global' AND name_en IN ('Victory Day', 'Christmas Day') AND to_char(date, 'MM-DD') IN ('12-16', '12-25') AND date >= date_trunc('month', app.dhaka_date(now())) AND date < date_trunc('month', app.dhaka_date(now())) + interval '3 months'"),
        )
        assertEquals("2026-10-21", c.scalar("SELECT string_agg(date::text, ',') FROM app.calendar_holiday WHERE scope_type = 'global' AND name_en LIKE 'Durga Puja%'"))
        // The month view counts those holidays out: a working day is a declared selling day, or neither a Friday nor a day off.
        assertEquals(
            c.scalar("SELECT string_agg(m || '=' || (SELECT count(*) FROM generate_series(m, (m + interval '1 month - 1 day')::date, interval '1 day') g(d) WHERE EXISTS (SELECT 1 FROM app.calendar_holiday h WHERE h.date = g.d::date AND h.scope_type = 'global' AND h.selling_day AND h.revoked_at IS NULL) OR (extract(isodow FROM g.d) <> 5 AND NOT EXISTS (SELECT 1 FROM app.calendar_holiday h WHERE h.date = g.d::date AND h.scope_type = 'global' AND NOT h.selling_day AND h.revoked_at IS NULL))), ',' ORDER BY m) FROM (SELECT (date_trunc('month', app.dhaka_date(now())) + k * interval '1 month')::date AS m FROM generate_series(0, 2) k) t"),
            c.scalar("SELECT string_agg(month || '=' || working_days, ',' ORDER BY month) FROM dw.v_calendar_month WHERE month >= date_trunc('month', app.dhaka_date(now()))::date AND month < (date_trunc('month', app.dhaka_date(now())) + interval '3 months')::date"),
        )
        // Pinned where the window holds them (Durga Puja on 2026-10-21; Victory Day 2026-12-16; Christmas 2026-12-25 is a Friday).
        val pinned = mapOf("2026-10-01" to "25", "2026-11-01" to "26", "2026-12-01" to "26")
        for (m in months!!.split(',').filter { it in pinned }) {
            assertEquals(pinned[m], c.scalar("SELECT working_days FROM dw.v_calendar_month WHERE month = DATE '$m'"), m)
        }
    }

    @Test
    fun theGulshanBananiGeographyAndTheOwnersTestOutletsAreTestData() = dev.connect().use { c ->
        // V0116 (D-DB-DATA-ORIGIN-01): the dev geography is test data, the closed Mirpur fixture seed, the test accounts test.
        assertEquals("test", c.scalar("SELECT string_agg(DISTINCT data_origin::text, ',') FROM app.route WHERE code IN $gul"))
        assertEquals("63|test", c.scalar("SELECT count(*) || '|' || string_agg(DISTINCT o.data_origin::text, ',') FROM app.outlet o JOIN app.zone z ON z.id = o.zone_id WHERE z.code IN ('Z-GUL', 'Z-BAN')"))
        assertEquals("GUL1-T01:test,GUL1-T02:test,GUL1-T03:test", c.scalar("SELECT string_agg(code || ':' || data_origin::text, ',' ORDER BY code) FROM app.outlet WHERE code LIKE 'GUL1-T%'"))
        assertEquals("seed", c.scalar("SELECT string_agg(DISTINCT data_origin::text, ',') FROM app.route WHERE code LIKE 'MIR-SR-%'"))
        assertEquals("test", c.scalar("SELECT data_origin::text FROM app.app_user WHERE username = 'sr1001'"))
    }

    @Test
    fun aSecondDevRunChangesNothing() {
        fun count(c: Connection) = c.scalar("SELECT (SELECT count(*) FROM app.outlet) || '|' || (SELECT count(*) FROM app.route_assignment) || '|' || (SELECT count(*) FROM app.route_monthly_target) || '|' || (SELECT count(*) FROM app.route_target_batch) || '|' || (SELECT count(*) FROM app.user_scope) || '|' || (SELECT count(*) FROM app.calendar_holiday)")
        dev.connect().use { c ->
            val before = count(c)
            loadDevProfile(c)
            assertEquals(before, count(c))
        }
    }

    @Test
    fun theTestProfileKeepsTheMirpurFixture() = TestPostgres.createDatabase().migrated().use { test ->
        test.connect().use { c ->
            SeedLoader.load(c)
            assertEquals("0", c.scalar("SELECT count(*) FROM app.zone WHERE code IN ('Z-GUL', 'Z-BAN')"))
            assertEquals("MIR-SR-2F,MIR-SR-3F,MIR-SR-D", c.scalar("SELECT string_agg(r.code, ',' ORDER BY r.code) FROM app.route_assignment a JOIN app.route r ON r.id = a.route_id JOIN app.app_user u ON u.id = a.user_id WHERE u.username = 'sr1001' AND a.valid_to IS NULL"))
        }
    }
}
