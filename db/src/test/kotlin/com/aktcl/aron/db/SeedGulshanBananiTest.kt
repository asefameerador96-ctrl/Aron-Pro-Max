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
 * deploy loads it) sr1001 works three Friday-aware Gulshan and Banani routes with 60 outlets, targets and holidays;
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
            "BAN-SR-11|Z-BAN|36|20,GUL-SR-1|Z-GUL|18|20,GUL-SR-2|Z-GUL|9|20",
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
        assertEquals("60|60|60|t", c.scalar("SELECT count(*) || '|' || count(DISTINCT h.outlet_id) || '|' || count(DISTINCT p.outlet_id) || '|' || bool_and(o.lat BETWEEN 23.77 AND 23.80 AND o.lng BETWEEN 90.40 AND 90.43)::text::char FROM app.outlet o JOIN app.route r ON r.id = o.route_id LEFT JOIN app.outlet_location_history h ON h.outlet_id = o.id LEFT JOIN app.outlet_placement_history p ON p.outlet_id = o.id WHERE r.code IN $gul"))
    }

    @Test
    fun targetsAndTheQuarterCalendarAreLoaded() = dev.connect().use { c ->
        // Per route and month: about 200,000 cigarette sticks, 30,000 bidi sticks, 500 lighters and 300 dozen matches, in whole packs.
        val oct = c.column("SELECT s.category_code || '=' || sum(t.qty_base) FROM app.route_monthly_target t JOIN app.sku s ON s.id = t.sku_id JOIN app.route r ON r.id = t.route_id WHERE r.code = 'GUL-SR-2' AND t.month = DATE '2026-10-01' GROUP BY s.category_code ORDER BY s.category_code")
        val totals = oct.associate { it!!.substringBefore('=') to it.substringAfter('=').toLong() }
        for ((cat, want) in mapOf("cigarette" to 200_000L, "bidi" to 30_000L, "lighter" to 500L, "match" to 300L)) {
            assertEquals(true, kotlin.math.abs(totals.getValue(cat) - want) <= want / 10, "$cat ${totals[cat]}")
        }
        assertEquals("0", c.scalar("SELECT count(*) FROM app.route_monthly_target t JOIN app.sku s ON s.id = t.sku_id WHERE t.qty_base % s.base_per_pack <> 0"))
        assertEquals("3", c.scalar("SELECT count(DISTINCT month) FROM app.route_monthly_target"))
        assertEquals("2026-10-01=25,2026-11-01=26,2026-12-01=26", c.scalar("SELECT string_agg(month || '=' || working_days, ',' ORDER BY month) FROM dw.v_calendar_month WHERE month BETWEEN DATE '2026-10-01' AND DATE '2026-12-01'"))
    }

    @Test
    fun aSecondDevRunChangesNothing() {
        fun count(c: Connection) = c.scalar("SELECT (SELECT count(*) FROM app.outlet) || '|' || (SELECT count(*) FROM app.route_assignment) || '|' || (SELECT count(*) FROM app.route_monthly_target) || '|' || (SELECT count(*) FROM app.user_scope) || '|' || (SELECT count(*) FROM app.calendar_holiday)")
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
