package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Row N-005: the rules schema v1a enforces in the database itself (docs/24 s3.3, s7, s8.4, s9.1, s12.1). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchemaV1aTest {
    private lateinit var db: TestDatabase

    @BeforeAll
    fun setUp() {
        db = TestPostgres.createDatabase().migrated()
        db.connect().use { c ->
            c.exec(
                """
                INSERT INTO app.wing (code, name) VALUES ('W1', 'Wing 1');
                INSERT INTO app.division (code, name, wing_id) VALUES ('D1', 'Division 1', (SELECT id FROM app.wing WHERE code = 'W1'));
                INSERT INTO app.territory (code, name, division_id) VALUES ('T1', 'Territory 1', (SELECT id FROM app.division WHERE code = 'D1'));
                INSERT INTO app.zone (code, name, territory_id) VALUES ('Z1', 'Zone 1', (SELECT id FROM app.territory WHERE code = 'T1'));
                INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask)
                  VALUES ('R-1', 'Route 1', (SELECT id FROM app.zone WHERE code = 'Z1'), 'sr', 'daily', 127);
                INSERT INTO app.app_user (username, full_name, role) VALUES ('sr0001', 'SR One', 'SR'), ('sr0002', 'SR Two', 'SR');
                """.trimIndent(),
            )
        }
    }

    @AfterAll
    fun tearDown() = db.close()

    private fun <T> tx(block: (Connection) -> T): T = db.connect().use { c ->
        c.autoCommit = false
        try { block(c) } finally { c.rollback() }
    }

    private fun assertRejected(sqlState: String, sql: String) {
        val e = assertFailsWith<SQLException> { tx { it.exec(sql) } }
        assertEquals(sqlState, e.sqlState, e.message)
    }

    @Test
    fun businessDateIsTheDhakaDateAndMoneyRoundsHalfAwayFromZero() = db.connect().use { c ->
        assertEquals("2026-10-05", c.scalar("SELECT app.dhaka_date('2026-10-04 18:30:00+00')"))
        assertEquals("2026-10-04", c.scalar("SELECT app.dhaka_date('2026-10-04 17:59:59+00')"))
        assertEquals("158700", c.scalar("SELECT app.div_half_up(20 * 7935, 1)"))
        assertEquals("1", c.scalar("SELECT app.div_half_up(5, 10)"))
        assertEquals("-1", c.scalar("SELECT app.div_half_up(-5, 10)"))
        assertEquals("0", c.scalar("SELECT app.div_half_up(4, 10)"))
        assertEquals("11642", c.scalar("SELECT app.div_half_up(116416, 10)"))
    }

    @Test
    fun onePrimaryAssignmentPerRouteAndDate() {
        assertRejected(
            "23P01",
            """
            INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from)
              SELECT r.id, u.id, 'primary', '2026-10-01' FROM app.route r, app.app_user u WHERE r.code = 'R-1' AND u.username = 'sr0001';
            INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from, valid_to)
              SELECT r.id, u.id, 'primary', '2026-10-05', '2026-10-06' FROM app.route r, app.app_user u WHERE r.code = 'R-1' AND u.username = 'sr0002';
            """.trimIndent(),
        )
        // A cover on the same day and a primary that starts when the first one ends are both fine.
        tx { c ->
            c.exec(
                """
                INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from, valid_to)
                  SELECT r.id, u.id, 'primary', '2026-10-01', '2026-10-05' FROM app.route r, app.app_user u WHERE r.code = 'R-1' AND u.username = 'sr0001';
                INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from)
                  SELECT r.id, u.id, 'primary', '2026-10-05' FROM app.route r, app.app_user u WHERE r.code = 'R-1' AND u.username = 'sr0002';
                INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from, valid_to)
                  SELECT r.id, u.id, 'cover', '2026-10-05', '2026-10-06' FROM app.route r, app.app_user u WHERE r.code = 'R-1' AND u.username = 'sr0001';
                """.trimIndent(),
            )
        }
    }

    @Test
    fun routeVisitKindAndMaskAreChecked() {
        assertRejected("23514", "INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask) SELECT 'R-2', 'x', id, 'sr', '4f', 21 FROM app.zone")
        assertRejected("23514", "INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask) SELECT 'R-2', 'x', id, 'sr', '3f', 128 FROM app.zone")
        assertRejected("23505", "INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask) SELECT 'R-1', 'dup', id, 'sr', '2f', 34 FROM app.zone")
    }

    @Test
    fun usernamesAreUniqueIgnoringCaseAndMemoRolesHaveNoHyphen() {
        assertRejected("23505", "INSERT INTO app.app_user (username, full_name, role) VALUES ('SR0001', 'Dup', 'TSO')")
        assertRejected("23514", "INSERT INTO app.app_user (username, full_name, role) VALUES ('sr-0009', 'Bad', 'SR')")
        tx { it.exec("INSERT INTO app.app_user (username, full_name, role) VALUES ('tso.dhaka-1', 'Web name', 'TSO')") }
    }

    @Test
    fun masterRowsBumpTheirVersionOnUpdate() = tx { c ->
        val before = c.scalar("SELECT version FROM app.route WHERE code = 'R-1'")!!.toInt()
        c.exec("UPDATE app.route SET name = 'Renamed' WHERE code = 'R-1'")
        assertEquals((before + 1).toString(), c.scalar("SELECT version FROM app.route WHERE code = 'R-1'"))
    }

    @Test
    fun productTreeParentsHaveTheRightLevel() {
        assertRejected(
            "23514",
            """
            INSERT INTO app.product_node (level, name) VALUES ('category', 'Cigarette');
            INSERT INTO app.product_node (level, parent_id, name) SELECT 'brand', id, 'Maxim' FROM app.product_node WHERE name = 'Cigarette';
            """.trimIndent(),
        )
    }

    @Test
    fun pricesDoNotOverlapForOneSkuAndPriceType() {
        assertRejected(
            "23P01",
            """
            INSERT INTO app.product_node (level, name) VALUES ('category', 'C');
            INSERT INTO app.product_node (level, parent_id, name) SELECT 'segment', id, 'S' FROM app.product_node WHERE name = 'C';
            INSERT INTO app.product_node (level, parent_id, name) SELECT 'brand', id, 'B' FROM app.product_node WHERE name = 'S';
            INSERT INTO app.product_node (level, parent_id, name) SELECT 'variant', id, 'V' FROM app.product_node WHERE name = 'B';
            INSERT INTO app.sku (code, variant_id, category_code, name, short_name, base_unit, base_per_pack, entry_unit_default)
              SELECT 'X-10S', id, 'cigarette', 'X', 'X', 'stick', 10, 'stick' FROM app.product_node WHERE name = 'V';
            INSERT INTO app.sku_price (sku_id, price_type, amount_mtk, valid_from) SELECT id, 'outlet', 9200, '2026-01-01' FROM app.sku;
            INSERT INTO app.sku_price (sku_id, price_type, amount_mtk, valid_from) SELECT id, 'outlet', 9300, '2026-11-01' FROM app.sku;
            """.trimIndent(),
        )
    }

    @Test
    fun configValuesNeverOverlapAndAreClosedNotEdited() {
        val insert = """
            INSERT INTO app.cfg_version (config_version, kind, summary) VALUES (1, 'change', 't');
            INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason)
              VALUES ('cfg.geo.radius_m', 'zone', 7, '120', '2026-10-01T00:00Z', 1, 'test');
        """.trimIndent()
        assertRejected(
            "23P01",
            insert + "\nINSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) " +
                "VALUES ('cfg.geo.radius_m', 'zone', 7, '150', '2026-10-05T00:00Z', 1, 'test');",
        )
        assertRejected("42501", "$insert\nUPDATE app.cfg_value SET value = '130';")
        assertRejected("42501", "$insert\nDELETE FROM app.cfg_value;")
        assertRejected("23503", "INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) VALUES ('cfg.nope.key', 'global', 0, '1', now(), 0, 't')")
        tx { c ->
            c.exec("$insert\nUPDATE app.cfg_value SET effective_to = '2026-10-05T00:00Z';")
            c.exec("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) VALUES ('cfg.geo.radius_m', 'zone', 7, '150', '2026-10-05T00:00Z', 1, 'test')")
        }
    }

    @Test
    fun registryHoldsEveryKeyOfDocs24Section95() {
        val spec = File(System.getProperty("aron.migrations")).parentFile.parentFile.resolve("docs/24-build-spec.md").readLines()
        val start = spec.indexOfFirst { it.startsWith("### 9.5") }
        val end = spec.indexOfFirst { it.startsWith("Keys of `docs/19` not listed") }
        val specKeys = spec.subList(start, end).mapNotNull { Regex("^\\| `(cfg\\.[^`]+)`").find(it)?.groupValues?.get(1) }.toSet()
        val dbKeys = db.connect().use { it.column("SELECT key FROM app.cfg_key") }.filterNotNull().toSet()
        assertEquals(172, specKeys.size)
        assertEquals(specKeys, dbKeys)
        db.connect().use { c ->
            assertEquals("100", c.scalar("SELECT default_value::text FROM app.cfg_key WHERE key = 'cfg.geo.radius_m'"))
            assertEquals("\"block_sale\"", c.scalar("SELECT default_value::text FROM app.cfg_key WHERE key = 'cfg.geo.mock_policy'"))
            assertEquals("true", c.scalar("SELECT default_value::text FROM app.cfg_key WHERE key = 'cfg.device.require_enrolled'"))
            assertEquals("100", c.scalar("SELECT default_value->>'GEO_MOCK' FROM app.cfg_key WHERE key = 'cfg.geo.integrity_weight'"))
            assertTrue(c.scalar("SELECT requires_ack FROM app.cfg_key WHERE key = 'cfg.geo.radius_m'") == "t")
        }
    }
}
