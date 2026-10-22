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

/** V0087-V0101 (owner directive 2026-10-22, docs/34 s1, s2, s4.2, s5): one test per migration. */
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
    fun v0087NationalAndBrandAreWebRolesWithTheWebDefaults() = db.connect().use { c ->
        assertEquals("NATIONAL|11|f,BRAND|12|f", c.scalar("SELECT string_agg(role || '|' || ordinal || '|' || field_role::text::char, ',' ORDER BY ordinal) FROM app.role_def WHERE ordinal > 10"))
        assertEquals("t", c.scalar("SELECT convalidated::text::char FROM pg_constraint WHERE conname = 'role_def_role_check'"))
        // TOP stays until backend-authz moves its users (expand only).
        assertEquals("1", c.scalar("SELECT count(*) FROM app.role_def WHERE role = 'TOP'"))
        for (r in listOf("NATIONAL", "BRAND")) {
            assertEquals(
                "cfg.auth.access_ttl_min=15,cfg.auth.password_min_len=12",
                c.scalar("SELECT string_agg(v.key || '=' || v.value::text, ',' ORDER BY v.key) FROM app.cfg_value v JOIN app.role_def d ON d.ordinal = v.scope_id WHERE v.scope_type = 'role' AND d.role = '$r'"),
            )
        }
        val menu = "(SELECT default_value FROM app.cfg_key WHERE key = 'cfg.web.menu_by_role')"
        assertEquals("t", c.scalar("SELECT ($menu -> 'NATIONAL' = $menu -> 'TOP')::text::char"))
        val brand = c.column("SELECT e ->> 'menu' || '/' || (e ->> 'page') FROM jsonb_array_elements($menu -> 'BRAND') e")
        assertTrue("dashboard/dashboard" in brand && "reports/reports" in brand && "credentials/change_password" in brand, brand.toString())
        assertTrue(brand.none { it!!.startsWith("master/") || it.startsWith("data_entry/") || it == "reports/by_route_geo_capture" }, brand.toString())
        c.tx { newUser(this, "qanational1", "NATIONAL"); newUser(this, "qabrand1", "BRAND") }
    }

    @Test
    fun v0089ModuleGrantsAreForAdminsOnly() = db.connect().use { c ->
        assertEquals("11", c.scalar("SELECT count(*) FROM app.portal_module"))
        c.tx {
            exec("INSERT INTO app.user_module_grant (user_id, module, access, granted_by) VALUES (${user("admin1001")}, 'mdm', 'w', $superadmin)")
            exec("UPDATE app.user_module_grant SET access = 'r' WHERE module = 'mdm'")
            assertEquals("23505", refused("INSERT INTO app.user_module_grant (user_id, module, access, granted_by) VALUES (${user("admin1001")}, 'mdm', 'r', $superadmin)"))
            assertEquals("23514", refused("INSERT INTO app.user_module_grant (user_id, module, access, granted_by) VALUES (${user("admin1001")}, 'routes', 'x', $superadmin)"))
            assertEquals("23503", refused("INSERT INTO app.user_module_grant (user_id, module, access, granted_by) VALUES (${user("admin1001")}, 'astha', 'r', $superadmin)"))
            for (u in listOf("superadmin1001", "tso1001", "sr1001")) {
                assertEquals("23514", refused("INSERT INTO app.user_module_grant (user_id, module, access, granted_by) VALUES (${user(u)}, 'reports', 'r', $superadmin)"), u)
            }
            exec("DELETE FROM app.user_module_grant WHERE module = 'mdm'")
        }
        assertEquals("true|true", listOf("SELECT", "DELETE").joinToString("|") { can(c, "api_rw", "app.user_module_grant", it)!! })
        assertEquals("true|true", listOf("app.user_module_grant", "app.portal_module").joinToString("|") { can(c, "auth_rw", it, "SELECT")!! })
        assertEquals("false", can(c, "web_ro", "app.user_module_grant", "SELECT"))
    }

    @Test
    fun v0090BrandScopeNamesBrandNodesOfBrandUsers() = db.connect().use { c ->
        c.tx {
            newUser(this, "qabrand2", "BRAND")
            val maxim = "(SELECT id FROM app.product_node WHERE level = 'brand' AND name = 'Maxim')"
            exec("INSERT INTO app.user_brand_scope (user_id, brand_id, granted_by) VALUES (${user("qabrand2")}, $maxim, $superadmin)")
            assertEquals("23505", refused("INSERT INTO app.user_brand_scope (user_id, brand_id, granted_by) VALUES (${user("qabrand2")}, $maxim, $superadmin)"))
            val variant = "(SELECT min(id) FROM app.product_node WHERE level = 'variant')"
            assertEquals("23514", refused("INSERT INTO app.user_brand_scope (user_id, brand_id, granted_by) VALUES (${user("qabrand2")}, $variant, $superadmin)"))
            assertEquals("23514", refused("INSERT INTO app.user_brand_scope (user_id, brand_id, granted_by) VALUES (${user("admin1001")}, $maxim, $superadmin)"))
        }
        assertEquals("true|true", listOf("api_rw" to "DELETE", "auth_rw" to "SELECT").joinToString("|") { (r, p) -> can(c, r, "app.user_brand_scope", p)!! })
    }

    @Test
    fun v0091OneActiveHousePerTerritoryAndName() = db.connect().use { c ->
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
    fun v0091TheOneOffDedupeKeepsTheBusiestActiveHouse() {
        // Re-run the de-duplication statements of V0091 on duplicates made after the migration, as on a database
        // that held them before it (the unique index is dropped inside the transaction to let them exist).
        val sql = java.io.File(System.getProperty("aron.migrations")).listFiles()!!.single { it.name.startsWith("V0091__") }.readText()
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
    fun v0092RouteTypeDefaultsToNormal() = db.connect().use { c ->
        assertEquals("normal", c.scalar("SELECT string_agg(DISTINCT route_type, ',') FROM app.route"))
        assertEquals("t", c.scalar("SELECT convalidated::text::char FROM pg_constraint WHERE conname = 'route_route_type_check'"))
        c.tx {
            exec("UPDATE app.route SET route_type = 'dummy_br' WHERE code = (SELECT min(code) FROM app.route)")
            assertEquals("23514", refused("UPDATE app.route SET route_type = 'dummy' WHERE code = (SELECT min(code) FROM app.route)"))
        }
    }

    @Test
    fun v0094TargetsKeepHistoryAndOneLiveRow() = db.connect().use { c ->
        c.tx {
            val route = "(SELECT min(id) FROM app.route)"
            val sku = "(SELECT id FROM app.sku WHERE code = 'MaxR-20S')"
            fun upload(month: String): String {
                val u = UUID.randomUUID()
                exec("INSERT INTO app.route_monthly_target_upload (upload_uuid, month, source, row_count, created_by) VALUES ('$u', DATE '$month', 'excel', 1, $superadmin)")
                return "(SELECT id FROM app.route_monthly_target_upload WHERE upload_uuid = '$u')"
            }
            val u1 = upload("2026-10-01")
            exec("INSERT INTO app.route_monthly_target (month, route_id, sku_id, qty_base, upload_id) VALUES (DATE '2026-10-01', $route, $sku, 26000, $u1)")
            assertEquals("23505", refused("INSERT INTO app.route_monthly_target (month, route_id, sku_id, qty_base, upload_id) VALUES (DATE '2026-10-01', $route, $sku, 1, $u1)"))
            assertEquals("23514", refused("INSERT INTO app.route_monthly_target (month, route_id, sku_id, qty_base, upload_id) VALUES (DATE '2026-11-01', $route, $sku, 1, $u1)"))
            assertEquals("23514", refused("INSERT INTO app.route_monthly_target (month, route_id, sku_id, qty_base, upload_id) VALUES (DATE '2026-10-01', $route, $sku, -1, $u1)"))
            val u2 = upload("2026-10-01")
            exec("UPDATE app.route_monthly_target SET superseded_by_upload_id = $u2 WHERE upload_id = $u1")
            exec("INSERT INTO app.route_monthly_target (month, route_id, sku_id, qty_base, upload_id) VALUES (DATE '2026-10-01', $route, $sku, 52000, $u2)")
            assertEquals("42501", refused("UPDATE app.route_monthly_target SET qty_base = 1 WHERE upload_id = $u2"))
            assertEquals("42501", refused("UPDATE app.route_monthly_target SET superseded_by_upload_id = $u2 WHERE upload_id = $u1"))
            assertEquals("42501", refused("DELETE FROM app.route_monthly_target"))
            assertEquals("42501", refused("UPDATE app.route_monthly_target_upload SET row_count = 2"))
            assertEquals("23505", refused("INSERT INTO app.route_monthly_target_upload (upload_uuid, month, source, row_count, created_by) SELECT upload_uuid, month, source, 1, created_by FROM app.route_monthly_target_upload LIMIT 1"))
            // V0095: the live row with October's 26 working days (31 days, 5 Fridays, no national holiday in the test data).
            assertEquals("52000|26|2000.0000000000000000|cigarette", scalar("SELECT monthly_qty_base || '|' || working_days || '|' || daily_qty_base || '|' || category_code FROM dw.v_route_monthly_target"))
        }
    }

    @Test
    fun v0095WorkingDaysSkipFridaysAndHolidaysAndCountMakeUpDays() = db.connect().use { c ->
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
        }
        for (v in listOf("dw.v_calendar", "dw.v_calendar_month", "dw.v_route_monthly_target", "dw.v_final_submit_log")) {
            assertEquals("true|true", listOf("web_ro", "bi_reader").joinToString("|") { can(c, it, v, "SELECT")!! }, v)
        }
    }

    @Test
    fun v0096SummaryTablesAndRanges() = db.connect().use { c ->
        c.tx {
            exec("INSERT INTO dw.agg_daily_route (business_date, route_id, zone_id, completed_calls, first_sale_at, last_sale_at, distance_covered_m, last_position_at, last_position_lat, last_position_lng) " +
                "VALUES (DATE '2026-10-05', 1, 1, 12, now() - interval '2 hours', now(), 1196.97, now(), 23.7925, 90.4078)")
            assertEquals("23514", refused("INSERT INTO dw.agg_daily_route (business_date, route_id, zone_id, last_position_lat) VALUES (DATE '2026-10-06', 1, 1, 23.79)"))
            assertEquals("23514", refused("INSERT INTO dw.agg_daily_route (business_date, route_id, zone_id, first_sale_at, last_sale_at) VALUES (DATE '2026-10-06', 1, 1, now(), now() - interval '1 hour')"))
            exec("INSERT INTO dw.agg_daily_outlet_category (business_date, outlet_id, category_code, route_id, sold_qty_base, memo_count) VALUES (DATE '2026-10-05', 1, 'cigarette', 1, 200, 1)")
            assertEquals("23514", refused("INSERT INTO dw.agg_daily_outlet_category (business_date, outlet_id, category_code) VALUES (DATE '2026-10-05', 1, 'tea')"))
            exec("INSERT INTO dw.agg_daily_route_variant (business_date, route_id, variant_id, memo_count) VALUES (DATE '2026-10-05', 1, 1, 3)")
            exec("INSERT INTO dw.summary_watermark (summary, last_event_id) VALUES ('agg_daily_route', 42)")
        }
        assertEquals("t", c.scalar("SELECT convalidated::text::char FROM pg_constraint WHERE conname = 'agg_daily_route_v0096_ranges'"))
        for (t in listOf("dw.agg_daily_outlet_category", "dw.agg_daily_route_variant", "dw.summary_watermark")) {
            assertEquals("true|false", listOf("worker_rw", "web_ro").joinToString("|") { can(c, it, t, if (it == "worker_rw") "INSERT" else "SELECT")!! }, t)
        }
        // The position stays out of every stable view.
        assertEquals("0", c.scalar("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'dw' AND table_name LIKE 'v\\_%' AND column_name IN ('last_position_lat', 'last_position_lng', 'fix_lat', 'fix_lng')"))
    }

    @Test
    fun v0098WithinFenceAndCompletedAreGenerated() = db.connect().use { c ->
        c.tx {
            fun visit(device: String, server: String?, outcome: String?, voided: Boolean) {
                exec("INSERT INTO dw.fact_visit (business_date, visit_client_uuid, user_id, outlet_id, visit_kind, opened_at, planned, last_event_id, geo_action, device_verdict, server_verdict, outcome_code, voided, fix_lat, fix_lng) " +
                    "VALUES (DATE '2026-10-05', gen_random_uuid(), 1, 1, 'sr_call', now(), true, 1, 'sale_allowed', '$device', ${server?.let { "'$it'" } ?: "NULL"}, ${outcome?.let { "'$it'" } ?: "NULL"}, $voided, 23.79, 90.40)")
            }
            visit("in_range", null, "sold", false)                 // t t
            visit("in_range", "out_of_range", "zero_sale_stock_ok", false) // f t
            visit("out_of_range", "in_range", "abandoned", false)  // t f
            visit("in_range", null, null, false)                   // t f (open)
            visit("in_range", null, "sold", true)                  // t f (voided)
            assertEquals("tt,ft,tf,tf,tf", scalar("SELECT string_agg(within_fence::text::char || completed::text::char, ',' ORDER BY ctid) FROM dw.fact_visit"))
            assertEquals("428C9", refused("UPDATE dw.fact_visit SET completed = true"))
        }
    }

    @Test
    fun v0099TheFirstFinalSubmitWins() = db.connect().use { c ->
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
    fun v0100FactoryResetNeedsASecondPersonAndStatusMovesForward() = db.connect().use { c ->
        c.tx {
            val dev = "(SELECT min(id) FROM app.device)"
            val admin = user("admin1001")
            fun cmd(type: String, status: String): String {
                val u = UUID.randomUUID()
                exec("INSERT INTO app.device_command (command_uuid, device_id, type, status, requested_by, expires_at) VALUES ('$u', $dev, '$type', '$status', $admin, now() + interval '1 day')")
                return "'$u'"
            }
            val lock = cmd("lock", "queued")
            assertEquals("23514", refused("INSERT INTO app.device_command (command_uuid, device_id, type, status, requested_by, expires_at) VALUES (gen_random_uuid(), $dev, 'factory_reset', 'queued', $admin, now() + interval '1 day')"))
            assertEquals("23514", refused("INSERT INTO app.device_command (command_uuid, device_id, type, status, requested_by, expires_at) VALUES (gen_random_uuid(), $dev, 'lock', 'sent', $admin, now() + interval '1 day')"))
            assertEquals("23514", refused("INSERT INTO app.device_command (command_uuid, device_id, type, status, requested_by, expires_at) VALUES (gen_random_uuid(), $dev, 'lock', 'queued', $admin, now() + interval '31 days')"))
            exec("UPDATE app.device_command SET status = 'sent', sent_at = now(), sent_via = 'fcm' WHERE command_uuid = $lock")
            exec("UPDATE app.device_command SET status = 'acked', acked_at = now() WHERE command_uuid = $lock")
            exec("UPDATE app.device_command SET status = 'done', completed_at = now() WHERE command_uuid = $lock")
            assertEquals("42501", refused("UPDATE app.device_command SET error = 'x' WHERE command_uuid = $lock"))
            val reset = cmd("factory_reset", "pending_approval")
            assertEquals("23514", refused("UPDATE app.device_command SET status = 'queued', approved_by = $admin, approved_at = now() WHERE command_uuid = $reset"))
            assertEquals("23514", refused("UPDATE app.device_command SET status = 'queued' WHERE command_uuid = $reset"))
            exec("UPDATE app.device_command SET status = 'queued', approved_by = $superadmin, approved_at = now() WHERE command_uuid = $reset")
            assertEquals("42501", refused("UPDATE app.device_command SET approved_by = ${user("support1001")} WHERE command_uuid = $reset"))
            assertEquals("23514", refused("UPDATE app.device_command SET status = 'done' WHERE command_uuid = $reset"))
            assertEquals("42501", refused("UPDATE app.device_command SET params = '{\"x\": 1}' WHERE command_uuid = $reset"))
            exec("UPDATE app.device_command SET status = 'cancelled', cancelled_by = $superadmin WHERE command_uuid = $reset")
            assertEquals("42501", refused("DELETE FROM app.device_command"))
        }
        assertEquals("true|true", listOf("api_rw", "support_ro").joinToString("|") { can(c, it, "app.device_command", "SELECT")!! })
        assertEquals("false", can(c, "api_rw", "app.device_command", "DELETE"))
    }

    @Test
    fun v0101PackUnitsAndTheLooseUnitKey() = db.connect().use { c ->
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
