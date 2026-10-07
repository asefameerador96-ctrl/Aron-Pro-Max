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
        assertRejected("23514", "INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask) SELECT 'R-2', 'x', id, 'sr', 'daily', 63 FROM app.zone")
        assertRejected("23514", "INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask) SELECT 'R-2', 'x', id, 'sr', '3f', 43 FROM app.zone")
        tx {
            it.exec(
                "INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask) SELECT 'R-2', 'x', id, 'sr', '3f', 21 FROM app.zone;" +
                    "INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask) SELECT 'R-3', 'y', id, 'sr', '2f', 34 FROM app.zone",
            )
        }
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
            INSERT INTO app.cfg_version (config_version, kind, committed_by, summary)
              SELECT 1000, 'change', id, 't' FROM app.app_user WHERE username = 'aron.system';   -- a free version above the migrations' own
            INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason)
              VALUES ('cfg.geo.radius_m', 'zone', 7, '120', '2026-10-01T00:00Z', 1000, 'test');
        """.trimIndent()
        assertRejected(
            "23P01",
            insert + "\nINSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) " +
                "VALUES ('cfg.geo.radius_m', 'zone', 7, '150', '2026-10-05T00:00Z', 1000, 'test');",
        )
        assertRejected("42501", "$insert\nUPDATE app.cfg_value SET value = '130';")
        assertRejected("42501", "$insert\nDELETE FROM app.cfg_value;")
        assertRejected("23514", "INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) VALUES ('cfg.nope.key', 'global', 0, '1', now(), 1, 't')")
        // cfg.geo.radius_min_m allows the global level only (s9.5)
        assertRejected("23514", "INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) VALUES ('cfg.geo.radius_min_m', 'zone', 7, '30', now(), 1, 't')")
        tx { c ->
            c.exec("$insert\nUPDATE app.cfg_value SET effective_to = '2026-10-05T00:00Z';")
            c.exec("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) VALUES ('cfg.geo.radius_m', 'zone', 7, '150', '2026-10-05T00:00Z', 2, 'test')")
        }
    }

    @Test
    fun registryAndVersionsFitTheContractShapes() = db.connect().use { c ->
        // ConfigBounds allows only these members (additionalProperties: false); min and max are numbers.
        assertEquals("0", c.scalar("SELECT count(*) FROM app.cfg_key, jsonb_object_keys(bounds) k WHERE k NOT IN ('min','max','enum','max_items','dynamic_min','dynamic_max')"))
        assertEquals("0", c.scalar("SELECT count(*) FROM app.cfg_key, jsonb_each(bounds) e WHERE e.key IN ('min','max') AND jsonb_typeof(e.value) <> 'number'"))
        // ConfigVersion.kind values and committed_by (a required Id).
        assertEquals("0", c.scalar("SELECT count(*) FROM app.cfg_version WHERE kind NOT IN ('change','revert','rollback','schedule_apply','expiry','content') OR committed_by IS NULL"))
    }

    @Test
    fun webRolesGetTheirOwnPasswordLengthAndTokenLifetime() = db.connect().use { c ->
        val sql = "SELECT v.value::text FROM app.cfg_value v JOIN app.role_def r ON r.ordinal = v.scope_id AND v.scope_type = 'role' " +
            "WHERE v.key = ? AND r.role = ? AND v.effective_to IS NULL"
        assertEquals("12", c.scalar(sql, "cfg.auth.password_min_len", "ADMIN"))
        assertEquals("15", c.scalar(sql, "cfg.auth.access_ttl_min", "DMO"))
        assertEquals(emptyList(), c.column(sql, "cfg.auth.password_min_len", "SR"))
        assertEquals(emptyList(), c.column(sql, "cfg.auth.password_min_len", "TSO"))
        assertEquals("8", c.scalar("SELECT default_value::text FROM app.cfg_key WHERE key = 'cfg.auth.password_min_len'"))
    }

    @Test
    fun skusMatchTheContractCodeAndHangOffAVariant() {
        assertRejected(
            "23514",
            "INSERT INTO app.product_node (level, name) VALUES ('category', 'C9');" +
                "INSERT INTO app.sku (code, variant_id, category_code, name, short_name, base_unit, base_per_pack, entry_unit_default) " +
                "SELECT 'C9-10S', id, 'cigarette', 'x', 'x', 'stick', 10, 'stick' FROM app.product_node WHERE name = 'C9'",
        )
        val tree = "INSERT INTO app.product_node (level, name) VALUES ('category', 'C8');" +
            "INSERT INTO app.product_node (level, parent_id, name) SELECT 'segment', id, 'S8' FROM app.product_node WHERE name = 'C8';" +
            "INSERT INTO app.product_node (level, parent_id, name) SELECT 'brand', id, 'B8' FROM app.product_node WHERE name = 'S8';" +
            "INSERT INTO app.product_node (level, parent_id, name) SELECT 'variant', id, 'V8' FROM app.product_node WHERE name = 'B8';"
        val sku = "INSERT INTO app.sku (code, variant_id, category_code, name, short_name, base_unit, base_per_pack, entry_unit_default) " +
            "SELECT '%s', id, 'cigarette', 'x', 'x', 'stick', 10, 'stick' FROM app.product_node WHERE name = 'V8'"
        assertRejected("23514", tree + sku.format("A B"))
        tx { it.exec(tree + sku.format("MaxDB-20S_20HL")) }
    }

    @Test
    fun aVersionCanNeverGoBackwards() = tx { c ->
        c.exec("UPDATE app.route SET name = 'a' WHERE code = 'R-1'; UPDATE app.route SET name = 'b' WHERE code = 'R-1'")
        val v = c.scalar("SELECT version FROM app.route WHERE code = 'R-1'")!!.toInt()
        c.exec("UPDATE app.route SET version = 1 WHERE code = 'R-1'")
        assertEquals(v + 1, c.scalar("SELECT version FROM app.route WHERE code = 'R-1'")!!.toInt())
    }

    @Test
    fun ensurePartitionsReRoutesDefaultRowsEvenOnAppendOnlyTables() = tx { c ->
        c.exec(
            """
            CREATE TABLE app.t_ledger (id bigint GENERATED ALWAYS AS IDENTITY, business_date date NOT NULL, note text,
                                       PRIMARY KEY (id, business_date)) PARTITION BY RANGE (business_date);
            CREATE TRIGGER ao BEFORE UPDATE OR DELETE ON app.t_ledger FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();
            DELETE FROM app.partition_policy;
            INSERT INTO app.partition_policy (parent) VALUES ('app.t_ledger');
            """.trimIndent(),
        )
        assertEquals("1", c.scalar("SELECT app.ensure_partitions('2026-10-01', '2026-10-01')"))
        c.exec("INSERT INTO app.t_ledger (business_date, note) VALUES ('2026-10-09', 'oct'), ('2026-12-15', 'dec'), ('2027-03-02', 'mar')")
        assertEquals("2", c.scalar("SELECT count(*) FROM app.t_ledger_default"))
        assertEquals("2", c.scalar("SELECT app.ensure_partitions('2026-11-01', '2026-12-01')"))
        assertEquals("dec", c.scalar("SELECT note FROM app.t_ledger_y2026m12"))
        assertEquals("mar", c.scalar("SELECT note FROM app.t_ledger_default"))
        assertEquals(listOf("oct", "dec", "mar"), c.column("SELECT note FROM app.t_ledger ORDER BY business_date"))
        assertEquals("0", c.scalar("SELECT app.ensure_partitions('2026-10-01', '2026-12-01')"))   // idempotent
        c.exec("SAVEPOINT s")
        val e = assertFailsWith<SQLException> { c.exec("DELETE FROM app.t_ledger") }
        assertEquals("42501", e.sqlState)                                                        // still append-only
        c.exec("ROLLBACK TO SAVEPOINT s")
    }

    @Test
    fun oneBlockedParentNeverStopsTheOthersAndIsRecorded() = tx { c ->
        c.exec(
            """
            DELETE FROM app.partition_policy;
            CREATE TABLE app.t_memo (id bigint GENERATED ALWAYS AS IDENTITY, business_date date NOT NULL, PRIMARY KEY (id, business_date))
              PARTITION BY RANGE (business_date);
            CREATE TABLE app.t_line (id serial PRIMARY KEY, memo_id bigint, business_date date,
              FOREIGN KEY (memo_id, business_date) REFERENCES app.t_memo (id, business_date));
            CREATE TABLE app.u_other (id bigint GENERATED ALWAYS AS IDENTITY, business_date date NOT NULL, PRIMARY KEY (id, business_date))
              PARTITION BY RANGE (business_date);
            INSERT INTO app.partition_policy (parent) VALUES ('app.t_memo'), ('app.u_other');
            """.trimIndent(),
        )
        assertEquals("2", c.scalar("SELECT app.ensure_partitions('2026-10-01', '2026-10-01')"))
        c.exec("INSERT INTO app.t_memo (business_date) VALUES ('2026-12-15'); INSERT INTO app.t_line (memo_id, business_date) SELECT id, business_date FROM app.t_memo")
        // t_memo cannot re-route its default rows (a foreign key points at them); u_other still gets its months.
        assertEquals("2", c.scalar("SELECT app.ensure_partitions('2026-11-01', '2026-12-01')"))
        assertEquals("app.u_other_y2026m12", c.scalar("SELECT to_regclass('app.u_other_y2026m12')::text"))
        assertEquals("app.t_memo_default", c.scalar("SELECT tableoid::regclass::text FROM app.t_memo"))
        assertTrue(c.scalar("SELECT last_error FROM app.partition_policy WHERE parent = 'app.t_memo'")!!.isNotBlank())
        assertEquals(null, c.scalar("SELECT last_error FROM app.partition_policy WHERE parent = 'app.u_other'"))
    }

    @Test
    fun noForeignKeyPointsAtAMaintainedPartitionedTable() = db.connect().use { c ->
        assertEquals(
            emptyList(),
            c.column("SELECT conname FROM pg_constraint WHERE contype = 'f' AND confrelid::regclass::text IN (SELECT parent FROM app.partition_policy)"),
        )
    }

    @Test
    fun registryHoldsEveryKeyOfDocs24Section95() {
        val spec = File(System.getProperty("aron.migrations")).parentFile.parentFile.resolve("docs/24-build-spec.md").readLines()
        val start = spec.indexOfFirst { it.startsWith("### 9.5") }
        val end = spec.indexOfFirst { it.startsWith("Keys of `docs/19` not listed") }
        val specKeys = spec.subList(start, end).mapNotNull { Regex("^\\| `(cfg\\.[^`]+)`").find(it)?.groupValues?.get(1) }.toSet()
        val dbKeys = db.connect().use { it.column("SELECT key FROM app.cfg_key") }.filterNotNull().toSet()
        assertTrue(specKeys.size >= 226, "s9.5 of contract v1.1 names 226 keys, parsed ${specKeys.size}")
        // Keys the lead added by ruling after s9.5 was written (docs/requests/android-print-integration.md, 2026-10-07; V0024).
        val ruled = setOf("cfg.print.confirm_after_print", "cfg.memo.reprint_watermark", "cfg.sale.require_printer_before_sale", "cfg.support.public_key_spki",
            "cfg.pii.list_rows_per_hour", "cfg.pii.export_rows_per_day",
            // docs/21 s4 names the password policy keys (V0040, docs/requests/backend-core-password-history.md).
            "cfg.auth.password_history_depth", "cfg.auth.password_min_age_h", "cfg.auth.password_denylist_enabled")
        assertEquals(specKeys + ruled, dbKeys)
        db.connect().use { c ->
            assertEquals("100", c.scalar("SELECT default_value::text FROM app.cfg_key WHERE key = 'cfg.geo.radius_m'"))
            assertEquals("\"block_sale\"", c.scalar("SELECT default_value::text FROM app.cfg_key WHERE key = 'cfg.geo.mock_policy'"))
            assertEquals("true", c.scalar("SELECT default_value::text FROM app.cfg_key WHERE key = 'cfg.device.require_enrolled'"))
            assertEquals("100", c.scalar("SELECT default_value->>'GEO_MOCK' FROM app.cfg_key WHERE key = 'cfg.geo.integrity_weight'"))
            assertEquals("40", c.scalar("SELECT default_value->>'GEO_OUT_OF_BOUNDS' FROM app.cfg_key WHERE key = 'cfg.geo.integrity_weight'"))
            assertEquals("2000", c.scalar("SELECT default_value::text FROM app.cfg_key WHERE key = 'cfg.loyalty.cash_rate_mtk_per_point'"))
            assertTrue(c.scalar("SELECT requires_ack FROM app.cfg_key WHERE key = 'cfg.geo.radius_m'") == "t")
        }
    }
}
