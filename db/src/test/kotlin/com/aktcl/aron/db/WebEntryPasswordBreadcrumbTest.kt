package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * V0039: entry unlocks, route-day web entry with lines, web QC summary (docs/requests/backend-admin-web-entry-tables.md).
 * V0040: password history and its policy keys (docs/requests/backend-core-password-history.md).
 * V0041: geo_breadcrumb range-partitioned by business_date (docs/requests/backend-core-breadcrumb-partitioning.md).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebEntryPasswordBreadcrumbTest {
    private lateinit var db: TestDatabase

    @BeforeAll
    fun setUp() {
        db = TestPostgres.createDatabase().migrated()
        db.connect().use {
            it.exec(
                """
                INSERT INTO app.wing (code, name) VALUES ('W1', 'Wing 1');
                INSERT INTO app.division (code, name, wing_id) VALUES ('D1', 'Division 1', (SELECT id FROM app.wing WHERE code = 'W1'));
                INSERT INTO app.territory (code, name, division_id) VALUES ('T1', 'Territory 1', (SELECT id FROM app.division WHERE code = 'D1'));
                INSERT INTO app.zone (code, name, territory_id) VALUES ('Z1', 'Zone 1', (SELECT id FROM app.territory WHERE code = 'T1'));
                INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask)
                  VALUES ('R-1', 'Route 1', (SELECT id FROM app.zone WHERE code = 'Z1'), 'sr', 'daily', 127);
                INSERT INTO app.product_node (level, name) VALUES ('category', 'C');
                INSERT INTO app.product_node (level, parent_id, name) SELECT 'segment', id, 'S' FROM app.product_node WHERE name = 'C';
                INSERT INTO app.product_node (level, parent_id, name) SELECT 'brand', id, 'B' FROM app.product_node WHERE name = 'S';
                INSERT INTO app.product_node (level, parent_id, name) SELECT 'variant', id, 'V' FROM app.product_node WHERE name = 'B';
                INSERT INTO app.sku (code, variant_id, category_code, name, short_name, base_unit, base_per_pack, entry_unit_default)
                  SELECT 'X-10S', id, 'cigarette', 'X', 'X', 'stick', 10, 'stick' FROM app.product_node WHERE name = 'V';
                INSERT INTO app.app_user (username, full_name, role) VALUES ('tso9001', 'TSO', 'TSO'), ('adm9001', 'Admin', 'ADMIN'), ('sr9001', 'SR', 'SR');
                """.trimIndent(),
            )
        }
    }

    @AfterAll
    fun tearDown() = db.close()

    private fun Connection.tx(block: Connection.() -> Unit) {
        autoCommit = false
        try { block() } finally { rollback(); autoCommit = true }
    }

    private fun Connection.refused(sql: String): String {
        exec("SAVEPOINT r")
        val e = assertFailsWith<SQLException>(sql) { exec(sql) }
        exec("ROLLBACK TO SAVEPOINT r")
        return e.sqlState
    }

    private fun uid(name: String) = "(SELECT id FROM app.app_user WHERE username = '$name')"
    private val route = "(SELECT id FROM app.route WHERE code = 'R-1')"
    private val zone = "(SELECT id FROM app.zone WHERE code = 'Z1')"
    private val sku = "(SELECT id FROM app.sku WHERE code = 'X-10S')"

    private fun entry(uuid: String, supersedes: String? = null, reason: String? = null) =
        "INSERT INTO app.web_entry_route_day (client_uuid, supersedes_client_uuid, route_id, business_date, successful_calls, target_outlets, change_reason, entered_by) " +
            "VALUES ('$uuid', ${supersedes?.let { "'$it'" } ?: "NULL"}, $route, '2026-10-06', 30, 40, ${reason?.let { "'$it'" } ?: "NULL"}, ${uid("tso9001")})"

    private fun line(entryUuid: String, date: String = "2026-10-06", issue: Int = 200, ret: Int = 20) =
        "INSERT INTO app.web_entry_line (entry_client_uuid, route_id, business_date, sku_id, issue_qty_base, return_qty_base, memo_count) " +
            "VALUES ('$entryUuid', $route, '$date', $sku, $issue, $ret, 12)"

    private val e1 = "00000000-0000-4000-8000-000000000001"
    private val e2 = "00000000-0000-4000-8000-000000000002"

    @Test
    fun anEntryUnlockOnlyExpiresOnceAndIsNeverDeleted() = db.connect().use { c ->
        c.tx {
            exec(
                "INSERT INTO app.entry_unlock (scope_type, scope_id, from_date, to_date, reason, expires_at, created_by) " +
                    "VALUES ('route', $route, '2026-10-01', '2026-10-05', 'late paper memos from the depot', '2026-10-08T06:00:00Z', ${uid("adm9001")})",
            )
            assertEquals("23514", refused("INSERT INTO app.entry_unlock (scope_type, scope_id, from_date, to_date, reason, expires_at, created_by) VALUES ('zone', 1, '2026-10-05', '2026-10-01', 'dates the wrong way', now(), ${uid("adm9001")})"))
            assertEquals("23514", refused("INSERT INTO app.entry_unlock (scope_type, scope_id, from_date, to_date, reason, expires_at, created_by) VALUES ('house', 1, '2026-10-01', '2026-10-01', 'not a scope we know', now(), ${uid("adm9001")})"))
            assertEquals("42501", refused("UPDATE app.entry_unlock SET to_date = '2026-10-06'"))
            assertEquals("23514", refused("UPDATE app.entry_unlock SET expired_at = now()"))
            exec("UPDATE app.entry_unlock SET expired_at = '2026-10-07T08:00:00Z', expired_by = ${uid("adm9001")}")
            assertEquals("2", scalar("SELECT version FROM app.entry_unlock"))
            assertEquals("42501", refused("UPDATE app.entry_unlock SET expired_at = '2026-10-07T09:00:00Z'"))
            assertEquals("42501", refused("DELETE FROM app.entry_unlock"))
        }
    }

    @Test
    fun aReSaveClosesTheOldEntryAndOnlyOneEntryIsLivePerRouteDay() = db.connect().use { c ->
        c.tx {
            exec(entry(e1))
            exec(line(e1))
            assertEquals("23505", refused(entry(e1)))                                          // idempotent by client_uuid
            assertEquals("23505", refused(entry(e2)))                                          // a second live entry
            assertEquals("23514", refused(entry(e2, supersedes = e1)))                         // a re-save needs a reason
            exec("UPDATE app.web_entry_route_day SET replaced_at = now(), replaced_by = ${uid("adm9001")} WHERE client_uuid = '$e1'")
            exec(entry(e2, supersedes = e1, reason = "corrected the return quantity"))
            assertEquals("23503", refused(line(e2, date = "2026-10-05")))                     // the line's day is the entry's
            exec(line(e2))
            assertEquals("$e2", scalar("SELECT client_uuid FROM app.web_entry_route_day WHERE replaced_at IS NULL AND voided_at IS NULL"))
            assertEquals("42501", refused("UPDATE app.web_entry_route_day SET replaced_at = replaced_at + interval '1 hour' WHERE client_uuid = '$e1'"))
            assertEquals("42501", refused("UPDATE app.web_entry_route_day SET successful_calls = 31 WHERE client_uuid = '$e2'"))
            assertEquals("42501", refused("DELETE FROM app.web_entry_route_day"))
            assertEquals("23505", refused(line(e2)))                                           // one line per SKU
            assertEquals("23514", refused(line(e2, issue = 10, ret = 20)))                     // return above issue
            assertEquals("42501", refused("UPDATE app.web_entry_line SET issue_qty_base = 1"))
        }
    }

    @Test
    fun dataVoidVoidsTheWebTablesByRouteAndDate() = db.connect().use { c ->
        c.tx {
            exec(entry(e1))
            exec(line(e1))
            exec(
                "INSERT INTO app.qc_summary_entry (client_uuid, qc_source, zone_id, route_id, business_date, entered_by) " +
                    "VALUES ('$e2', 'market', $zone, $route, '2026-10-06', ${uid("tso9001")})",
            )
            // The statement DataVoidApi runs per table.
            for (t in listOf("web_entry_route_day", "web_entry_line", "qc_summary_entry")) {
                assertEquals(
                    1,
                    column("UPDATE app.$t SET voided_at = now() WHERE route_id = $route AND business_date = '2026-10-06' AND voided_at IS NULL RETURNING client_uuid").size,
                    t,
                )
            }
            assertEquals("42501", refused("UPDATE app.web_entry_line SET voided_at = now() + interval '1 hour'"))
            exec(entry("00000000-0000-4000-8000-000000000003"))                          // a voided entry is not live
        }
    }

    @Test
    fun qcSummaryRulesFollowTheContract() = db.connect().use { c ->
        c.tx {
            val ins = "INSERT INTO app.qc_summary_entry (client_uuid, qc_source, zone_id, route_id, business_date, reason, entered_by) VALUES "
            assertEquals("23514", refused("$ins (gen_random_uuid(), 'market', $zone, NULL, '2026-10-06', NULL, ${uid("tso9001")})"))
            assertEquals("23514", refused("$ins (gen_random_uuid(), 'warehouse', $zone, NULL, '2026-10-06', NULL, ${uid("tso9001")})"))
            exec("$ins ('$e1', 'warehouse', $zone, NULL, '2026-10-06', 'monthly warehouse count', ${uid("tso9001")})")
            exec("INSERT INTO app.qc_summary_entry_line (entry_client_uuid, sku_id, fault_type_code, qty_base) VALUES ('$e1', $sku, 'torn_pack', 40)")
            assertEquals("23514", refused("INSERT INTO app.qc_summary_entry_line (entry_client_uuid, sku_id, fault_type_code, qty_base) VALUES ('$e1', $sku, 'Torn Pack', 1)"))
            assertEquals("42501", refused("UPDATE app.qc_summary_entry_line SET qty_base = 41"))
            assertEquals("42501", refused("DELETE FROM app.qc_summary_entry_line"))
        }
    }

    @Test
    fun passwordHistoryIsAppendOnlyAndHiddenFromTheWorker() = db.connect().use { c ->
        c.tx {
            exec("INSERT INTO app.password_history (user_id, password_hash, changed_at) VALUES (${uid("adm9001")}, '\$argon2id\$v=19\$m=65536,t=3,p=1\$c2FsdA\$aGFzaA', '2026-10-06T10:00:00Z')")
            assertEquals("42501", refused("UPDATE app.password_history SET password_hash = 'x'"))
            exec("SET LOCAL ROLE auth_rw")
            assertEquals("1", scalar("SELECT count(*) FROM app.password_history"))
            exec("DELETE FROM app.password_history WHERE changed_at < '2026-01-01'")      // prune beyond the depth
            exec("RESET ROLE")
            exec("SET LOCAL ROLE worker_rw")
            assertEquals("42501", refused("SELECT count(*) FROM app.password_history"))
            exec("RESET ROLE")
            assertEquals(
                listOf("cfg.auth.password_denylist_enabled|true|{global,role}", "cfg.auth.password_history_depth|10|{global}", "cfg.auth.password_min_age_h|24|{global}"),
                column("SELECT key || '|' || default_value::text || '|' || scope_levels::text FROM app.cfg_key WHERE key LIKE 'cfg.auth.password\\_%' AND key <> 'cfg.auth.password_min_len' ORDER BY key"),
            )
        }
    }

    @Test
    fun breadcrumbsArePartitionedByBusinessDateAndKeepTheirUuidOnce() = db.connect().use { c ->
        // The checker's acceptance (docs/requests/backend-core-breadcrumb-partitioning.md).
        assertEquals("1", c.scalar("SELECT count(*) FROM pg_partitioned_table pt JOIN pg_class k ON k.oid = pt.partrelid JOIN pg_namespace n ON n.oid = k.relnamespace WHERE n.nspname = 'app' AND k.relname = 'geo_breadcrumb'"))
        assertEquals("1", c.scalar("SELECT count(*) FROM app.partition_policy WHERE parent = 'app.geo_breadcrumb'"))
        c.tx {
            val ins = "INSERT INTO app.geo_breadcrumb (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, fix_status, fix_lat, fix_lng, fix_accuracy_m, fix_is_mock) " +
                "SELECT '$e1', '$e1', '%s', ${uid("sr9001")}, '2026-10-06T04:00:00Z', 0, 'ok', 23.8, 90.36, 12, false"
            exec("SET LOCAL ROLE api_rw")
            assertEquals(1, column(ins.format("2026-10-06") + " RETURNING id").size)     // the generic writer's shape
            exec("RESET ROLE")
            assertEquals("app.geo_breadcrumb_y2026m10", scalar("SELECT tableoid::regclass::text FROM app.geo_breadcrumb"))
            assertEquals("23505", refused(ins.format("2026-10-06")))
            assertEquals("23505", refused(ins.format("2026-11-02")))
            assertEquals("42501", refused("UPDATE app.geo_breadcrumb SET fix_lat = 23.9"))
            exec("UPDATE app.geo_breadcrumb SET voided_at = now()")
        }
    }
}
