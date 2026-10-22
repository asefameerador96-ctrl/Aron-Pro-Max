package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** V0087-V0104 (owner directive 2026-10-22, docs/34 s1, s2, s4.2, s5): one test per migration. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OwnerDirectiveV0087Test {
    private lateinit var db: TestDatabase

    @BeforeAll
    fun setUp() {
        db = TestPostgres.createDatabase().migrated()
        db.connect().use { SeedLoader.load(it) }
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

    private fun user(name: String) = "(SELECT id FROM app.app_user WHERE username = '$name')"
    private val superadmin = user("superadmin1001")
    private fun newUser(c: Connection, name: String, role: String) =
        c.exec("INSERT INTO app.app_user (username, full_name, role) VALUES ('$name', 'QA $role', '$role')")

    private fun can(c: Connection, role: String, table: String, priv: String) =
        c.scalar("SELECT has_table_privilege('$role', '$table', '$priv')::text")

    @Test
    fun v0087NationalAndBrandReplaceTopForUsers() = db.connect().use { c ->
        assertEquals("NATIONAL|11|f,BRAND|12|f", c.scalar("SELECT string_agg(role || '|' || ordinal || '|' || field_role::text::char, ',' ORDER BY ordinal) FROM app.role_def WHERE ordinal > 10"))
        assertEquals("t|t", c.scalar("SELECT string_agg(convalidated::text::char, '|' ORDER BY conname) FROM pg_constraint WHERE conname IN ('role_def_role_check', 'app_user_no_top')"))
        // TOP stays a role code (audit history) but no user may hold it.
        assertEquals("1|0", c.scalar("SELECT (SELECT count(*) FROM app.role_def WHERE role = 'TOP') || '|' || (SELECT count(*) FROM app.app_user WHERE role = 'TOP')"))
        // NATIONAL inherits TOP's role values; BRAND gets the two web-role defaults.
        val roleValues = { r: String -> c.scalar("SELECT string_agg(v.key || '=' || v.value::text, ',' ORDER BY v.key) FROM app.cfg_value v JOIN app.role_def d ON d.ordinal = v.scope_id WHERE v.scope_type = 'role' AND d.role = '$r' AND v.superseded_in_version IS NULL") }
        assertEquals(roleValues("TOP"), roleValues("NATIONAL"))
        assertEquals("cfg.auth.access_ttl_min=15,cfg.auth.password_min_len=12", roleValues("BRAND"))
        val menu = "(SELECT default_value FROM app.cfg_key WHERE key = 'cfg.web.menu_by_role')"
        assertEquals("t", c.scalar("SELECT ($menu -> 'NATIONAL' = $menu -> 'TOP')::text::char"))
        assertEquals("dashboard/dashboard,reports/reports", c.scalar("SELECT string_agg(e ->> 'menu' || '/' || (e ->> 'page'), ',') FROM jsonb_array_elements($menu -> 'BRAND') e"))
        c.tx {
            newUser(this, "qanational1", "NATIONAL"); newUser(this, "qabrand1", "BRAND")
            assertEquals("23514", refused("INSERT INTO app.app_user (username, full_name, role) VALUES ('qatop9', 'QA TOP', 'TOP')"))
        }
    }

    @Test
    fun v0087MovesTopUsersAndRoleListsOnAPopulatedDatabase() {
        // Migrate to V0086, add a TOP user and a role list naming TOP, then run the rest: the user is NATIONAL with a
        // new scope_version, and the list names NATIONAL too (MFA never loosens).
        TestPostgres.createDatabase().use { other ->
            other.flyway().let { org.flywaydb.core.Flyway.configure().configuration(it.configuration).target("86").load() }.migrate()
            other.connect().use { c ->
                c.exec("INSERT INTO app.app_user (username, full_name, role) VALUES ('qatop1', 'QA TOP', 'TOP')")
                val sys = "(SELECT id FROM app.app_user WHERE username = 'aron.system')"
                c.exec("INSERT INTO app.cfg_version (config_version, kind, committed_by, summary, max_risk_class) SELECT max(config_version) + 1, 'change', $sys, 'qa', 1 FROM app.cfg_version")
                c.exec("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, created_by, reason) SELECT 'cfg.auth.mfa_required_roles', 'global', 0, '[\"ADMIN\", \"SUPERADMIN\", \"TOP\"]'::jsonb, now() - interval '1 day', max(config_version), $sys, 'qa' FROM app.cfg_version")
            }
            other.flyway().migrate()
            other.connect().use { c ->
                assertEquals("NATIONAL|2", c.scalar("SELECT role || '|' || scope_version FROM app.app_user WHERE username = 'qatop1'"))
                assertEquals("[\"ADMIN\", \"SUPERADMIN\", \"TOP\", \"NATIONAL\"]", c.scalar("SELECT value::text FROM app.cfg_value WHERE key = 'cfg.auth.mfa_required_roles' AND superseded_in_version IS NULL"))
            }
        }
    }

    @Test
    fun v0089ModuleGrantsHoldFifteenModules() = db.connect().use { c ->
        // The seeded admin holds w on every module (seed 01, as V0089 does for admins present when it runs).
        assertEquals("15|w", c.scalar("SELECT count(*) || '|' || string_agg(DISTINCT access, ',') FROM app.user_module_grant WHERE user_id = ${user("admin1001")}"))
        c.tx {
            exec("UPDATE app.user_module_grant SET access = 'r', granted_by = $superadmin, granted_at = now() WHERE user_id = ${user("admin1001")} AND module = 'mdm'")
            assertEquals("23505", refused("INSERT INTO app.user_module_grant (user_id, module, access) VALUES (${user("admin1001")}, 'mdm', 'r')"))
            assertEquals("23514", refused("INSERT INTO app.user_module_grant (user_id, module, access) VALUES (${user("support1001")}, 'routes', 'x')"))
            assertEquals("23514", refused("INSERT INTO app.user_module_grant (user_id, module, access) VALUES (${user("support1001")}, 'astha', 'r')"))
            exec("DELETE FROM app.user_module_grant WHERE user_id = ${user("admin1001")} AND module = 'mdm'")
        }
        assertEquals("true|true", listOf("SELECT", "DELETE").joinToString("|") { can(c, "api_rw", "app.user_module_grant", it)!! })
        assertEquals("true", can(c, "auth_rw", "app.user_module_grant", "SELECT"))
        assertEquals("false", can(c, "web_ro", "app.user_module_grant", "SELECT"))
    }

    @Test
    fun v0090BrandScopeNamesBrandNodesOnly() = db.connect().use { c ->
        c.tx {
            newUser(this, "qabrand2", "BRAND")
            val maxim = "(SELECT id FROM app.product_node WHERE level = 'brand' AND name = 'Maxim')"
            exec("INSERT INTO app.user_brand_scope (user_id, brand_id, granted_by) VALUES (${user("qabrand2")}, $maxim, $superadmin)")
            assertEquals("23505", refused("INSERT INTO app.user_brand_scope (user_id, brand_id, granted_by) VALUES (${user("qabrand2")}, $maxim, $superadmin)"))
            val variant = "(SELECT min(id) FROM app.product_node WHERE level = 'variant')"
            assertEquals("23514", refused("INSERT INTO app.user_brand_scope (user_id, brand_id, granted_by) VALUES (${user("qabrand2")}, $variant, $superadmin)"))
        }
        assertEquals("true|true", listOf("api_rw" to "DELETE", "auth_rw" to "SELECT").joinToString("|") { (r, p) -> can(c, r, "app.user_brand_scope", p)!! })
    }

    @Test
    fun v0091UserScopeTakesAHouse() = db.connect().use { c ->
        assertEquals("t", c.scalar("SELECT convalidated::text::char FROM pg_constraint WHERE conname = 'user_scope_node_type_check'"))
        c.tx {
            exec("INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from) VALUES (${user("dmo1001")}, 'house', (SELECT min(id) FROM app.house), DATE '2026-10-01')")
            assertEquals("23514", refused("INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from) VALUES (${user("dmo1001")}, 'cluster', 1, DATE '2026-10-01')"))
        }
    }

    @Test
    fun v0093OneActiveHousePerTerritoryAndName() = db.connect().use { c ->
        c.tx {
            assertEquals("t", scalar("SELECT bool_and(is_active = (status = 'active'))::text::char FROM app.house"))
            val terr = "(SELECT territory_id FROM app.house ORDER BY id LIMIT 1)"
            exec("INSERT INTO app.house (code, name, territory_id) VALUES ('H-QA-1', 'QA House', $terr)")
            assertEquals("23505", refused("INSERT INTO app.house (code, name, territory_id) VALUES ('H-QA-2', '  qa house ', $terr)"))
            exec("INSERT INTO app.house (code, name, territory_id, status) VALUES ('H-QA-3', 'QA HOUSE', $terr, 'inactive')")
            assertEquals("f", scalar("SELECT is_active::text::char FROM app.house WHERE code = 'H-QA-3'"))
            assertEquals("428C9", refused("UPDATE app.house SET is_active = true WHERE code = 'H-QA-3'"))
        }
        assertEquals("2", c.scalar("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'dw' AND table_name IN ('dim_geo', 'dim_geo_version') AND column_name = 'house_id'"))
    }

    @Test
    fun v0093TheOneOffDedupeKeepsTheBusiestActiveHouse() {
        // Re-run the de-duplication statements of V0093 on duplicates made after the migration, as on a database
        // that held them before it (the unique index is dropped inside the transaction to let them exist).
        val sql = java.io.File(System.getProperty("aron.migrations")).listFiles()!!.single { it.name.startsWith("V0093__") }.readText()
        val dedupe = sql.substring(sql.indexOf("CREATE TEMP TABLE house_dup"), sql.indexOf("-- squawk-ignore require-concurrent-index-creation"))
        db.connect().use { c ->
            c.tx {
                exec("DROP INDEX app.house_active_name_once")
                val terr = "(SELECT territory_id FROM app.house ORDER BY id LIMIT 1)"
                exec("INSERT INTO app.house (code, name, territory_id) VALUES ('H-D1', 'Dup House', $terr), ('H-D2', 'dup house ', $terr), ('H-D3', 'DUP HOUSE', $terr)")
                exec("INSERT INTO app.house (code, name, territory_id, status) VALUES ('H-D0', 'Dup House', $terr, 'inactive')")
                val z = "(SELECT id FROM app.zone ORDER BY id LIMIT 1)"
                exec("UPDATE app.zone SET house_id = (SELECT id FROM app.house WHERE code = 'H-D2') WHERE id = $z")
                exec(dedupe)
                assertEquals("H-D0|f,H-D1|f,H-D2|t,H-D3|f", scalar("SELECT string_agg(code || '|' || is_active::text::char, ',' ORDER BY code) FROM app.house WHERE code LIKE 'H-D_'"))
                assertEquals("H-D2", scalar("SELECT h.code FROM app.zone z JOIN app.house h ON h.id = z.house_id WHERE z.id = $z"))
            }
        }
    }

    @Test
    fun v0094RouteClassDefaultsToNormal() = db.connect().use { c ->
        assertEquals("normal", c.scalar("SELECT string_agg(DISTINCT route_class, ',') FROM app.route"))
        assertEquals("t", c.scalar("SELECT convalidated::text::char FROM pg_constraint WHERE conname = 'route_route_class_check'"))
        c.tx {
            exec("UPDATE app.route SET route_class = 'dummy_br' WHERE code = (SELECT min(code) FROM app.route)")
            assertEquals("23514", refused("UPDATE app.route SET route_class = 'dummy' WHERE code = (SELECT min(code) FROM app.route)"))
        }
    }

    @Test
    fun v0096TargetsAreOneRowPerRouteSkuMonthWithABatch() = db.connect().use { c ->
        c.tx {
            val route = "(SELECT min(id) FROM app.route)"
            val sku = "(SELECT id FROM app.sku WHERE code = 'MaxR-20S')"
            val b = UUID.randomUUID()
            exec("INSERT INTO app.route_target_batch (batch_uuid, content_sha256, row_count, committed_by) VALUES ('$b', sha256('x'::bytea), 1, $superadmin)")
            assertEquals("23505", refused("INSERT INTO app.route_target_batch (batch_uuid, content_sha256, row_count, committed_by) VALUES ('$b', sha256('y'::bytea), 1, $superadmin)"))
            assertEquals("23514", refused("INSERT INTO app.route_target_batch (batch_uuid, content_sha256, row_count, committed_by) VALUES (gen_random_uuid(), sha256('y'::bytea), 0, $superadmin)"))
            exec("INSERT INTO app.route_monthly_target (route_id, sku_id, month, qty_base, batch_uuid, updated_by) VALUES ($route, $sku, DATE '2026-10-01', 26000, '$b', $superadmin)")
            assertEquals("23505", refused("INSERT INTO app.route_monthly_target (route_id, sku_id, month, qty_base, batch_uuid) VALUES ($route, $sku, DATE '2026-10-01', 1, '$b')"))
            assertEquals("23514", refused("INSERT INTO app.route_monthly_target (route_id, sku_id, month, qty_base, batch_uuid) VALUES ($route, $sku, DATE '2026-11-02', 1, '$b')"))
            assertEquals("23514", refused("INSERT INTO app.route_monthly_target (route_id, sku_id, month, qty_base, batch_uuid) VALUES ($route, $sku, DATE '2026-11-01', -1, '$b')"))
            exec("UPDATE app.route_monthly_target SET qty_base = 52000, updated_at = now() WHERE route_id = $route AND sku_id = $sku")
            // V0097: October has 26 working days (31 days, 5 Fridays, no national holiday in the test data).
            assertEquals("52000|26|2000.0000000000000000|cigarette", scalar("SELECT monthly_qty_base || '|' || working_days || '|' || daily_qty_base || '|' || category_code FROM dw.v_route_monthly_target"))
        }
    }

    @Test
    fun v0097WorkingDaysSkipFridaysAndHolidaysAndCountMakeUpDays() = db.connect().use { c ->
        c.tx {
            exec("DELETE FROM app.calendar_holiday WHERE date BETWEEN DATE '2026-12-01' AND DATE '2026-12-31'")
            assertEquals("31|4|0|27", scalar("SELECT days || '|' || fridays || '|' || holidays || '|' || working_days FROM dw.v_calendar_month WHERE month = DATE '2026-12-01'"))
            exec("INSERT INTO app.calendar_holiday (date, scope_type, kind, selling_day, name_en) VALUES (DATE '2026-12-16', 'global', 'holiday', false, 'Victory Day')")
            exec("INSERT INTO app.calendar_holiday (date, scope_type, kind, selling_day, name_en) VALUES (DATE '2026-12-25', 'global', 'makeup_day', true, 'Make-up day')")
            exec("INSERT INTO app.calendar_holiday (date, scope_type, scope_id, kind, selling_day, name_en) VALUES (DATE '2026-12-17', 'zone', 1, 'holiday', false, 'Zone off day')")
            exec("INSERT INTO app.calendar_holiday (date, scope_type, kind, selling_day, name_en, revoked_at) VALUES (DATE '2026-12-20', 'global', 'holiday', false, 'Revoked', now())")
            // 16 Dec off, 25 Dec (a Friday) worked, the zone holiday and the revoked one do not count nationally.
            assertEquals("31|4|1|27", scalar("SELECT days || '|' || fridays || '|' || holidays || '|' || working_days FROM dw.v_calendar_month WHERE month = DATE '2026-12-01'"))
            assertEquals("t|f|Victory Day", scalar("SELECT is_holiday::text::char || '|' || is_working_day::text::char || '|' || holiday_name FROM dw.v_calendar WHERE business_date = DATE '2026-12-16'"))
            assertEquals("t|t|t", scalar("SELECT is_friday::text::char || '|' || is_makeup_day::text::char || '|' || is_working_day::text::char FROM dw.v_calendar WHERE business_date = DATE '2026-12-25'"))
            assertEquals("27", scalar("SELECT count(*) FROM dw.v_working_day WHERE working AND day BETWEEN DATE '2026-12-01' AND DATE '2026-12-31'"))
        }
        for (v in listOf("dw.v_calendar", "dw.v_calendar_month", "dw.v_working_day", "dw.v_route_monthly_target", "dw.v_final_submit_log")) {
            assertEquals("true|true", listOf("web_ro", "bi_reader").joinToString("|") { can(c, it, v, "SELECT")!! }, v)
        }
    }

    @Test
    fun v0098DimProductCarriesTheNodeCodes() = db.connect().use { c ->
        assertEquals("6", c.scalar("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'dw' AND table_name IN ('dim_product', 'dim_product_version') AND column_name IN ('segment_code', 'brand_code', 'variant_code')"))
    }

    @Test
    fun v0099SummaryTablesHaveTheRequestedShapes() = db.connect().use { c ->
        c.tx {
            exec("INSERT INTO dw.agg_daily_route (business_date, route_id, zone_id, downloaded_at, completed_calls, completed_geo_valid_calls, first_sale_at, last_sale_at) " +
                "VALUES (DATE '2026-10-05', 1, 1, now() - interval '3 hours', 12, 11, now() - interval '2 hours', now())")
            exec("INSERT INTO dw.agg_daily_zone (business_date, zone_id, territory_id, final_submitted_at, auto_submitted) VALUES (DATE '2026-10-05', 1, 1, now(), true)")
            // B1 is range-partitioned: a row lands in its month's partition.
            exec("INSERT INTO dw.agg_daily_outlet_sku (business_date, outlet_id, route_id, sku_id, sold_qty_base, memo_count) VALUES (DATE '2026-10-05', 1, 1, 1, 200, 1)")
            assertEquals("dw.agg_daily_outlet_sku_y2026m10", scalar("SELECT tableoid::regclass::text FROM dw.agg_daily_outlet_sku"))
            assertEquals("23505", refused("INSERT INTO dw.agg_daily_outlet_sku (business_date, outlet_id, route_id, sku_id) VALUES (DATE '2026-10-05', 1, 1, 1)"))
            exec("INSERT INTO dw.agg_daily_route_variant (business_date, route_id, variant_id, memo_count) VALUES (DATE '2026-10-05', 1, 1, 3)")
            exec("INSERT INTO dw.route_last_position (business_date, route_id, user_id, lat, lng, captured_at, last_event_id) VALUES (DATE '2026-10-05', 1, 1, 23.7925, 90.4078, now(), 7)")
            exec("INSERT INTO dw.summary_freshness (table_name, last_updated_at, last_event_id) VALUES ('agg_daily_route', now(), 42)")
        }
        assertEquals("1", c.scalar("SELECT count(*) FROM app.partition_policy WHERE parent = 'dw.agg_daily_outlet_sku'"))
        for (t in listOf("dw.agg_daily_outlet_sku", "dw.agg_daily_route_variant", "dw.route_last_position", "dw.summary_freshness")) {
            assertEquals("true|false", listOf("worker_rw", "web_ro").joinToString("|") { can(c, it, t, if (it == "worker_rw") "INSERT" else "SELECT")!! }, t)
        }
        // Positions stay out of every stable view.
        assertEquals("0", c.scalar("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'dw' AND table_name LIKE 'v\\_%' AND column_name IN ('lat', 'lng', 'fix_lat', 'fix_lng')"))
    }

    @Test
    fun v0100CompletedIsWrittenAndWithinFenceIsGenerated() = db.connect().use { c ->
        c.tx {
            fun visit(device: String, server: String?, completed: Boolean) {
                exec("INSERT INTO dw.fact_visit (business_date, visit_client_uuid, user_id, outlet_id, visit_kind, opened_at, planned, last_event_id, geo_action, device_verdict, server_verdict, completed, radius_m, outlet_lat, outlet_lng, fix_lat, fix_lng) " +
                    "VALUES (DATE '2026-10-05', gen_random_uuid(), 1, 1, 'sr_call', now(), true, 1, 'sale_allowed', '$device', ${server?.let { "'$it'" } ?: "NULL"}, $completed, 50, 23.79, 90.40, 23.7901, 90.4001)")
            }
            visit("in_range", null, true)
            visit("in_range", "out_of_range", true)
            visit("out_of_range", "in_range", false)
            assertEquals("tt,ft,tf", scalar("SELECT string_agg(within_fence::text::char || completed::text::char, ',' ORDER BY ctid) FROM dw.fact_visit"))
            assertEquals("428C9", refused("UPDATE dw.fact_visit SET within_fence = true"))
            exec("INSERT INTO dw.fact_visit (business_date, visit_client_uuid, user_id, outlet_id, visit_kind, opened_at, planned, last_event_id, geo_action, device_verdict) VALUES (DATE '2026-10-05', gen_random_uuid(), 1, 1, 'sr_call', now(), true, 1, 'sale_allowed', 'no_fix')")
            assertEquals("1", scalar("SELECT count(*) FROM dw.fact_visit WHERE NOT completed AND radius_m IS NULL"))
        }
    }

    @Test
    fun v0101TheFirstFinalSubmitWins() = db.connect().use { c ->
        c.tx {
            val zone = "(SELECT min(id) FROM app.zone)"
            val tso = user("tso1001")
            exec("INSERT INTO app.final_submit (client_uuid, zone_id, business_date, submitted_by, submitted_at, via) VALUES (gen_random_uuid(), $zone, DATE '2026-10-05', $tso, TIMESTAMPTZ '2026-10-05 15:00Z', 'app')")
            exec("UPDATE app.final_submit SET reopened_at = TIMESTAMPTZ '2026-10-05 16:00Z', reopened_by = $superadmin WHERE business_date = DATE '2026-10-05'")
            exec("INSERT INTO app.final_submit (client_uuid, zone_id, business_date, submitted_by, submitted_at, via) VALUES (gen_random_uuid(), $zone, DATE '2026-10-05', $superadmin, TIMESTAMPTZ '2026-10-05 17:00Z', 'web')")
            assertEquals("2026-10-05 15:00:00+00|app|2|f", scalar("SELECT first_submitted_at || '|' || first_via || '|' || submits || '|' || reopened_now::text::char FROM dw.v_final_submit_log WHERE business_date = DATE '2026-10-05'"))
            assertEquals("t", scalar("SELECT (first_submitted_by = $tso)::text::char FROM dw.v_final_submit_log WHERE business_date = DATE '2026-10-05'"))
        }
    }

    @Test
    fun v0102FactoryResetStartsPendingAndNeedsASecondPerson() = db.connect().use { c ->
        c.tx {
            val dev = "(SELECT min(id) FROM app.device)"
            val admin = user("admin1001")
            val sig = "'" + "A".repeat(86) + "'"
            fun insert(type: String, state: String, extra: String = "", extraVal: String = "") =
                "INSERT INTO app.device_command (command_id, device_id, type, state, reason, request_sha256, sig, created_by, expires_at$extra) " +
                    "VALUES (gen_random_uuid(), $dev, '$type', '$state', 'qa test', sha256('x'::bytea), ${if (state == "pending_approval") "NULL" else sig}, $admin, now() + interval '1 day'$extraVal)"
            exec(insert("lock", "queued"))
            assertEquals("23514", refused(insert("factory_reset", "queued")))
            assertEquals("23514", refused(insert("lock", "sent")))
            // The checker's bypass: an approval cannot ride in with the request.
            assertEquals("23514", refused(insert("factory_reset", "pending_approval", ", approved_by, approved_at", ", ${user("sr1001")}, now()")))
            assertEquals("23514", refused("INSERT INTO app.device_command (command_id, device_id, type, state, reason, request_sha256, sig, created_by, expires_at) VALUES (gen_random_uuid(), $dev, 'lock', 'queued', 'qa test', sha256('x'::bytea), $sig, $admin, now() + interval '73 hours')"))
            assertEquals("23514", refused("INSERT INTO app.device_command (command_id, device_id, type, state, reason, request_sha256, created_by, expires_at) VALUES (gen_random_uuid(), $dev, 'lock', 'queued', 'qa test', sha256('x'::bytea), $admin, now() + interval '1 day')"))
            exec(insert("factory_reset", "pending_approval"))
            val reset = "(SELECT command_id FROM app.device_command WHERE type = 'factory_reset')"
            assertEquals("23514", refused("UPDATE app.device_command SET state = 'queued', sig = $sig WHERE command_id = $reset"))
            assertEquals("23514", refused("UPDATE app.device_command SET state = 'queued', sig = $sig, approved_by = $admin, approved_at = now() WHERE command_id = $reset"))
            exec("UPDATE app.device_command SET state = 'queued', sig = $sig, approved_by = $superadmin, approved_at = now() WHERE command_id = $reset")
            assertEquals("23514", refused("UPDATE app.device_command SET state = 'acked' WHERE command_id = $reset"))
            exec("UPDATE app.device_command SET state = 'acked', acked_at = now(), result = '{\"ok\": true}' WHERE command_id = $reset")
            exec("INSERT INTO app.mdm_policy (name, doc) VALUES ('Field default', '{\"allowed_packages\": []}')")
            assertEquals("23505", refused("INSERT INTO app.mdm_policy (name, doc) VALUES ('field DEFAULT', '{}')"))
            assertEquals("23514", refused("UPDATE app.device SET mdm_policy_id = (SELECT id FROM app.mdm_policy) WHERE id = $dev"))
            exec("UPDATE app.device SET mdm_policy_id = (SELECT id FROM app.mdm_policy), mdm_policy_version = 1 WHERE id = $dev")
        }
        assertEquals("t|t|t", c.scalar("SELECT string_agg(convalidated::text::char, '|' ORDER BY conname) FROM pg_constraint WHERE conname IN ('device_mdm_policy_fkey', 'device_mdm_policy_version_check', 'device_mdm_policy_pair')"))
        assertEquals("true|true|false", listOf("SELECT", "UPDATE", "DELETE").joinToString("|") { can(c, "api_rw", "app.device_command", it)!! })
        assertEquals("true|false", listOf("type", "result").joinToString("|") { c.scalar("SELECT has_column_privilege('support_ro', 'app.device_command', '$it', 'SELECT')::text")!! })
        assertEquals("true|0", c.scalar("SELECT has_table_privilege('web_ro', 'dw.v_device_command', 'SELECT')::text || '|' || (SELECT count(*) FROM information_schema.columns WHERE table_schema = 'dw' AND table_name = 'v_device_command' AND column_name IN ('sig', 'result'))"))
    }

    @Test
    fun v0104PackUnitsAndTheLooseUnitKey() = db.connect().use { c ->
        assertEquals(
            "bidi|pack|12,25;cigarette|pack|10,20;lighter|pack|25;match|dozen|1",
            c.scalar("SELECT string_agg(x, ';' ORDER BY x) FROM (SELECT category_code || '|' || string_agg(DISTINCT entry_unit_default, ',') || '|' || string_agg(DISTINCT base_per_pack::text, ',' ) AS x FROM app.sku GROUP BY category_code) t"),
        )
        assertEquals("{\"bidi\": \"pack\", \"match\": \"dozen\", \"lighter\": \"pack\", \"cigarette\": \"pack\"}", c.scalar("SELECT default_value::text FROM app.cfg_key WHERE key = 'cfg.sale.qty_entry_unit'"))
        assertEquals("false|both|2", c.scalar("SELECT default_value::text || '|' || delivery || '|' || risk_class FROM app.cfg_key WHERE key = 'cfg.sale.allow_loose_base_units'"))
        // A packet price is the base price times the pack size, exact in milli-taka (MaxR-20S: 9.200 Tk a stick, 184 Tk a packet).
        assertEquals("184000", c.scalar("SELECT p.amount_mtk * s.base_per_pack / p.per_base_qty FROM app.sku s JOIN app.sku_price p ON p.sku_id = s.id AND p.price_type = 'outlet' WHERE s.code = 'MaxR-20S'"))
    }

    @Test
    fun outletChannelIsAlreadyMandatory() = db.connect().use { c ->
        // docs/34 s2 asks for outlet.channel NOT NULL with the six channels: V0004 has had both since Day 1, no backfill.
        assertEquals("NO", c.scalar("SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'app' AND table_name = 'outlet' AND column_name = 'channel'"))
        c.tx { assertEquals("23514", refused("UPDATE app.outlet SET channel = 'Other' WHERE id = (SELECT min(id) FROM app.outlet)")) }
    }
}
