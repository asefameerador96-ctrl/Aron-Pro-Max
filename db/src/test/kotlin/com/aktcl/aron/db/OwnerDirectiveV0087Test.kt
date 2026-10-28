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
        assertEquals("t", c.scalar("SELECT convalidated::text::char FROM pg_constraint WHERE conname = 'role_def_role_check'"))
        // TOP stays a role code (audit history); no user may hold it (V0105, validated in V0106).
        assertEquals("1|t", c.scalar("SELECT (SELECT count(*) FROM app.role_def WHERE role = 'TOP') || '|' || (SELECT convalidated::text::char FROM pg_constraint WHERE conname = 'app_user_no_top')"))
        assertEquals("0", c.scalar("SELECT count(*) FROM app.app_user WHERE role = 'TOP'"))
        // A4 + N3 (V0107): TOP's admin permissions moved to NATIONAL; TOP holds none.
        assertEquals("0", c.scalar("SELECT count(*) FROM app.role_grant_map WHERE role = 'TOP'"))
        // NATIONAL inherits TOP's role values; BRAND gets the two web-role defaults.
        val roleValues = { r: String -> c.scalar("SELECT string_agg(v.key || '=' || v.value::text, ',' ORDER BY v.key) FROM app.cfg_value v JOIN app.role_def d ON d.ordinal = v.scope_id WHERE v.scope_type = 'role' AND d.role = '$r' AND v.superseded_in_version IS NULL") }
        assertEquals(roleValues("TOP"), roleValues("NATIONAL"))
        assertEquals("cfg.auth.access_ttl_min=15,cfg.auth.password_min_len=12", roleValues("BRAND"))
        val menu = "(SELECT default_value FROM app.cfg_key WHERE key = 'cfg.web.menu_by_role')"
        assertEquals("t", c.scalar("SELECT ($menu -> 'NATIONAL' = $menu -> 'TOP')::text::char"))
        assertEquals("dashboard/dashboard,reports/reports,credentials/change_password", c.scalar("SELECT string_agg(e ->> 'menu' || '/' || (e ->> 'page'), ',') FROM jsonb_array_elements($menu -> 'BRAND') e"))
        c.tx {
            newUser(this, "qanational1", "NATIONAL"); newUser(this, "qabrand1", "BRAND")
            assertEquals("23514", refused("INSERT INTO app.app_user (username, full_name, role) VALUES ('qatop2', 'QA TOP', 'TOP')"))
        }
    }

    @Test
    fun v0087ExtendsRoleListsOnAPopulatedDatabase() {
        // Migrate to V0086, add a TOP user and a role list naming TOP, then run the rest: the list names NATIONAL too
        // (MFA never loosens) and the user becomes NATIONAL with scope_version + 1 (V0105: its TOP token is refused).
        TestPostgres.createDatabase().use { other ->
            other.flyway().let { org.flywaydb.core.Flyway.configure().configuration(it.configuration).target("86").load() }.migrate()
            other.connect().use { c ->
                c.exec("INSERT INTO app.app_user (username, full_name, role) VALUES ('qatop1', 'QA TOP', 'TOP')")
                c.exec("INSERT INTO app.role_grant_map (role, permission) VALUES ('TOP', 'qa.top_only'), ('TOP', 'reports.export_pii')")
                val sys = "(SELECT id FROM app.app_user WHERE username = 'aron.system')"
                c.exec("INSERT INTO app.cfg_version (config_version, kind, committed_by, summary, max_risk_class) SELECT max(config_version) + 1, 'change', $sys, 'qa', 1 FROM app.cfg_version")
                c.exec("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, created_by, reason) SELECT 'cfg.auth.mfa_required_roles', 'global', 0, '[\"ADMIN\", \"SUPERADMIN\", \"TOP\"]'::jsonb, now() - interval '1 day', max(config_version), $sys, 'qa' FROM app.cfg_version")
            }
            other.flyway().migrate()
            other.connect().use { c ->
                assertEquals("NATIONAL|2", c.scalar("SELECT role || '|' || scope_version FROM app.app_user WHERE username = 'qatop1'"))
                // N3 (V0107): TOP's grants, including one only TOP had, now belong to NATIONAL; TOP holds none.
                assertEquals("0|t|t", c.scalar("SELECT (SELECT count(*) FROM app.role_grant_map WHERE role = 'TOP') || '|' || " +
                    "(EXISTS (SELECT 1 FROM app.role_grant_map WHERE role = 'NATIONAL' AND permission = 'qa.top_only'))::text::char || '|' || " +
                    "(EXISTS (SELECT 1 FROM app.role_grant_map WHERE role = 'NATIONAL' AND permission = 'reports.export_pii'))::text::char"))
                assertEquals("[\"ADMIN\", \"SUPERADMIN\", \"TOP\", \"NATIONAL\"]", c.scalar("SELECT value::text FROM app.cfg_value WHERE key = 'cfg.auth.mfa_required_roles' AND superseded_in_version IS NULL"))
            }
        }
    }

    @Test
    fun v0108CheckedInAtIsBackfilledForTheLastSevenDays() {
        // Migrate to V0107 with the seed, add route-days and check-ins, then run V0108: the event's route wins; an event
        // without a route counts for a route its user is assigned to that day; a route-day older than 7 days stays null.
        TestPostgres.createDatabase().use { other ->
            other.flyway().let { org.flywaydb.core.Flyway.configure().configuration(it.configuration).target("107").load() }.migrate()
            other.connect().use { c ->
                SeedLoader.load(c)
                val d = "(app.dhaka_date(now()) - 1)"
                val old = "(app.dhaka_date(now()) - 10)"
                val route = { code: String -> "(SELECT id FROM app.route WHERE code = '$code')" }
                for ((r, day) in listOf("MIR-SR-D" to d, "MIR-SR-3F" to d, "MIR-SR-D" to old)) {
                    c.exec("INSERT INTO dw.agg_daily_route (business_date, route_id, zone_id) SELECT $day, id, zone_id FROM app.route WHERE code = '$r'")
                }
                fun checkIn(user: String, routeId: String, day: String, at: String) = c.exec(
                    "INSERT INTO app.attendance_event (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, kind) " +
                        "SELECT gen_random_uuid(), gen_random_uuid(), $day, id, $routeId, $at, 0, 'check_in' FROM app.app_user WHERE username = '$user'")
                // sr1001 holds MIR-SR-D in the fixture: its route-less check-in counts there.
                checkIn("sr1001", "NULL", d, "($d + time '03:05') AT TIME ZONE 'Asia/Dhaka'")
                checkIn("sr1001", "NULL", old, "($old + time '03:00') AT TIME ZONE 'Asia/Dhaka'")
                // amo1001 names MIR-SR-3F on its event, earlier than sr1001 (who also holds MIR-SR-3F in the fixture).
                checkIn("amo1001", route("MIR-SR-3F"), d, "($d + time '02:10') AT TIME ZONE 'Asia/Dhaka'")
            }
            other.flyway().migrate()
            other.connect().use { c ->
                assertEquals("MIR-SR-3F|02:10,MIR-SR-D|03:05,MIR-SR-D|-", c.scalar(
                    "SELECT string_agg(r.code || '|' || coalesce(to_char(a.checked_in_at AT TIME ZONE 'Asia/Dhaka', 'HH24:MI'), '-'), ',' ORDER BY r.code, a.business_date DESC) " +
                        "FROM dw.agg_daily_route a JOIN app.route r ON r.id = a.route_id"))
            }
        }
    }

    @Test
    fun v0109SplitsTheOutletQuantityByCategoryAndTheViewLosesTheMixedColumn() {
        // Migrate to V0108 with the seed, write sales and an agg_daily_outlet row with the old mixed sum, then run V0109:
        // each category is backfilled in its own base unit; voided lines, data-voided memos and other outlets stay out.
        TestPostgres.createDatabase().use { other ->
            other.flyway().let { org.flywaydb.core.Flyway.configure().configuration(it.configuration).target("108").load() }.migrate()
            val outlet = "(SELECT min(id) FROM app.outlet)"
            val second = "(SELECT min(id) FROM app.outlet WHERE id > $outlet)"
            other.connect().use { c ->
                SeedLoader.load(c)
                fun memo(no: String, outletId: String, voided: Boolean): UUID = UUID.randomUUID().also { m ->
                    c.exec(
                        "INSERT INTO app.memo (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, visit_client_uuid, " +
                            "outlet_id, memo_no, memo_kind, committed_at, price_list_date, price_type, gross_mtk, offer_discount_mtk, drp_discount_mtk, " +
                            "qc_deduction_mtk, round_adj_mtk, net_mtk, paid_mtk, due_mtk, is_credit, line_count, discount_line_count, qc_line_count, voided_at) " +
                            "VALUES ('$m', gen_random_uuid(), '2026-10-05', ${user("sr1001")}, now(), 0, gen_random_uuid(), $outletId, 'sr1001-261005-$no', " +
                            "'sale', now(), '2026-10-05', 'outlet', 10000, 0, 0, 0, 0, 10000, 10000, 0, false, 1, 0, 0, ${if (voided) "now()" else "NULL"})",
                    )
                }
                fun line(memo: UUID, no: Int, category: String, packs: Int, factor: Int, unit: String, voided: Boolean = false) = c.exec(
                    "INSERT INTO app.memo_line (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, memo_client_uuid, " +
                        "line_no, sku_id, line_kind, qty_entered, unit_entered, pack_factor, qty_base, price_type, price_valid_from, base_price_mtk, " +
                        "price_per_qty, gross_mtk, voided_at) SELECT gen_random_uuid(), gen_random_uuid(), '2026-10-05', ${user("sr1001")}, now(), 0, '$memo', " +
                        "$no, min(id), 'sale', $packs, '$unit', $factor, ${packs * factor}, 'outlet', '2026-01-01', 1000, 1, ${packs * factor * 1000}, " +
                        "${if (voided) "now()" else "NULL"} FROM app.sku WHERE category_code = '$category'",
                )
                val a = memo("001", outlet, voided = false)
                line(a, 1, "cigarette", 2, 20, "pack")          // 40 sticks
                line(a, 2, "cigarette", 1, 20, "pack", voided = true)
                line(a, 3, "lighter", 2, 25, "pack")            // 50 pieces
                line(a, 4, "match", 3, 1, "dozen")              // 3 dozens
                val b = memo("002", outlet, voided = true)      // admin data void: out
                line(b, 1, "bidi", 1, 25, "pack")
                val other2 = memo("003", second, voided = false)
                line(other2, 1, "bidi", 2, 25, "pack")          // 50 bidi sticks on the second outlet
                for (o in listOf(outlet, second)) {
                    c.exec("INSERT INTO dw.agg_daily_outlet (business_date, outlet_id, sold_qty_base) SELECT '2026-10-05', $o, 93")
                }
                c.exec("INSERT INTO dw.agg_daily_outlet (business_date, outlet_id, sold_qty_base) SELECT '2026-10-06', $outlet, 7")
            }
            // V0109 only: V0110 drops the table's sold_qty_base (v0110DropsTheDeprecatedMixedColumn).
            other.flyway().let { org.flywaydb.core.Flyway.configure().configuration(it.configuration).target("109").load() }.migrate()
            other.connect().use { c ->
                assertEquals("2026-10-05|40|0|50|3|93,2026-10-05|0|50|0|0|93,2026-10-06|0|0|0|0|7", c.scalar(
                    "SELECT string_agg(concat_ws('|', business_date, cigarette_sold_sticks, bidi_sold_sticks, lighter_sold_pieces, match_sold_dozens, sold_qty_base), ',' " +
                        "ORDER BY business_date, outlet_id) FROM dw.agg_daily_outlet"))
                val cols = "SELECT string_agg(attname, ',' ORDER BY attnum) FROM pg_attribute WHERE attrelid = 'dw.v_daily_outlet'::regclass AND attnum > 0"
                val viewCols = c.scalar(cols)!!.split(',')
                assertTrue("sold_qty_base" !in viewCols, "the mixed-unit column is gone from the stable view")
                assertEquals(listOf("cigarette_sold_sticks", "bidi_sold_sticks", "lighter_sold_pieces", "match_sold_dozens"),
                    viewCols.subList(viewCols.indexOf("active_memo_count") + 1, viewCols.indexOf("net_mtk")))
                assertEquals("40|50|3", c.scalar("SELECT concat_ws('|', cigarette_sold_sticks, lighter_sold_pieces, match_sold_dozens) FROM dw.v_daily_outlet WHERE outlet_id = $outlet AND business_date = '2026-10-05'"))
                assertEquals("true|true|true", listOf("bi_reader", "web_ro", "worker_rw").joinToString("|") { can(c, it, "dw.v_daily_outlet", "SELECT")!! })
            }
        }
    }

    @Test
    fun v0110DropsTheDeprecatedMixedColumn() {
        // Migrate to V0109, write an outlet-day with the four columns and the old mixed sum, then run V0110: the row and
        // the four columns stay, sold_qty_base is gone from the table, the view is unchanged and keeps its grants.
        TestPostgres.createDatabase().use { other ->
            other.flyway().let { org.flywaydb.core.Flyway.configure().configuration(it.configuration).target("109").load() }.migrate()
            val outlet = "(SELECT min(id) FROM app.outlet)"
            val viewCols = "SELECT string_agg(attname, ',' ORDER BY attnum) FROM pg_attribute WHERE attrelid = 'dw.v_daily_outlet'::regclass AND attnum > 0 AND NOT attisdropped"
            val before = other.connect().use { c ->
                SeedLoader.load(c)
                c.exec(
                    "INSERT INTO dw.agg_daily_outlet (business_date, outlet_id, active_memo_count, sold_qty_base, cigarette_sold_sticks, bidi_sold_sticks, " +
                        "lighter_sold_pieces, match_sold_dozens, net_mtk) SELECT '2026-10-05', $outlet, 1, 93, 40, 0, 50, 3, 10000",
                )
                c.scalar(viewCols)
            }
            // To V0110 only: a later additive column (V0116 data_origin) is not this migration's change.
            other.flyway().let { org.flywaydb.core.Flyway.configure().configuration(it.configuration).target("110").load() }.migrate()
            other.connect().use { c ->
                assertEquals("0", c.scalar("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'dw' AND table_name = 'agg_daily_outlet' AND column_name = 'sold_qty_base'"))
                assertEquals("2026-10-05|1|40|0|50|3|10000", c.scalar(
                    "SELECT concat_ws('|', business_date, active_memo_count, cigarette_sold_sticks, bidi_sold_sticks, lighter_sold_pieces, match_sold_dozens, net_mtk) " +
                        "FROM dw.agg_daily_outlet WHERE outlet_id = $outlet"))
                assertEquals(before, c.scalar(viewCols), "dw.v_daily_outlet is unchanged by V0110")
                assertEquals("40|50|3", c.scalar("SELECT concat_ws('|', cigarette_sold_sticks, lighter_sold_pieces, match_sold_dozens) FROM dw.v_daily_outlet WHERE outlet_id = $outlet"))
                assertEquals("true|true|true", listOf("bi_reader", "web_ro", "worker_rw").joinToString("|") { can(c, it, "dw.v_daily_outlet", "SELECT")!! })
                // A writer that still names the column is refused (why V0110 rides after the Aggregator change).
                val e = assertFailsWith<SQLException> { c.exec("INSERT INTO dw.agg_daily_outlet (business_date, outlet_id, sold_qty_base) SELECT '2026-10-06', $outlet, 1") }
                assertEquals("42703", e.sqlState)
            }
        }
    }

    @Test
    fun v0111MemoFlagsViewListsFlaggedMemosWithTheirScope() = db.connect().use { c ->
        // Three memos on one outlet and route: flagged, unflagged, flagged and data-voided. Only the flagged ones show,
        // with the route's zone and its territory, division and wing; the voided one keeps voided_at for readers to filter.
        c.tx {
            val route = "(SELECT min(id) FROM app.route WHERE status = 'active')"
            val outlet = "(SELECT min(id) FROM app.outlet)"
            fun memo(no: String, flags: String, voided: Boolean, routeId: String = route): UUID = UUID.randomUUID().also { m ->
                exec(
                    "INSERT INTO app.memo (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, visit_client_uuid, " +
                        "outlet_id, memo_no, memo_kind, committed_at, price_list_date, price_type, gross_mtk, offer_discount_mtk, drp_discount_mtk, " +
                        "qc_deduction_mtk, round_adj_mtk, net_mtk, paid_mtk, due_mtk, is_credit, line_count, discount_line_count, qc_line_count, server_flags, voided_at) " +
                        "VALUES ('$m', gen_random_uuid(), '2026-10-05', ${user("sr1001")}, $routeId, now(), 0, gen_random_uuid(), $outlet, 'sr1001-261005-$no', " +
                        "'sale', now(), '2026-10-05', 'outlet', 18400, 0, 0, 0, 0, 18400, 18400, 0, false, 1, 0, 0, '$flags', ${if (voided) "now()" else "NULL"})",
                )
            }
            val flagged = memo("901", "{zero_price_line,price_mismatch}", voided = false)
            memo("902", "{}", voided = false)
            val voided = memo("903", "{arithmetic_mismatch}", voided = true)
            val scope = scalar(
                "SELECT concat_ws('|', z.id, z.territory_id, t.division_id, d.wing_id) FROM app.route r JOIN app.zone z ON z.id = r.zone_id " +
                    "JOIN app.territory t ON t.id = z.territory_id JOIN app.division d ON d.id = t.division_id WHERE r.id = $route",
            )
            assertEquals("sr1001-261005-901|$scope|18400|{zero_price_line,price_mismatch}|active|-", scalar(
                "SELECT concat_ws('|', memo_no, zone_id, territory_id, division_id, wing_id, gross_mtk, flags, status, coalesce(voided_at::text, '-')) " +
                    "FROM dw.v_memo_flags WHERE memo_client_uuid = '$flagged'"))
            assertEquals("true", scalar("SELECT (route_id = $route AND outlet_id = $outlet AND user_id = ${user("sr1001")} AND stored_at IS NOT NULL)::text FROM dw.v_memo_flags WHERE memo_client_uuid = '$flagged'"))
            assertEquals("0", scalar("SELECT count(*) FROM dw.v_memo_flags WHERE memo_no = 'sr1001-261005-902'"), "an unflagged memo is not listed")
            assertEquals("{arithmetic_mismatch}|true", scalar("SELECT flags::text || '|' || (voided_at IS NOT NULL) FROM dw.v_memo_flags WHERE memo_client_uuid = '$voided'"))
            // A memo uploaded without a route keeps the outlet's placement on its business date, not the outlet's later one:
            // place the outlet on the test route until 2026-10-20, then move it (history and outlet row) to another route.
            val other = "(SELECT min(id) FROM app.route WHERE id <> $route)"
            exec("DELETE FROM app.outlet_placement_history WHERE outlet_id = $outlet")
            exec("INSERT INTO app.outlet_placement_history (outlet_id, route_id, valid_from, valid_to) SELECT $outlet, $route, DATE '2026-01-01', DATE '2026-10-20'")
            exec("INSERT INTO app.outlet_placement_history (outlet_id, route_id, valid_from) SELECT $outlet, $other, DATE '2026-10-20'")
            exec("UPDATE app.outlet SET route_id = $other, zone_id = (SELECT zone_id FROM app.route WHERE id = $other) WHERE id = $outlet")
            val routeless = memo("904", "{zero_price_line}", voided = false, routeId = "NULL")
            assertEquals("true|true|$scope", scalar(
                "SELECT concat_ws('|', (route_id = $route)::text, (route_id <> (SELECT route_id FROM app.outlet WHERE id = $outlet))::text, zone_id, territory_id, division_id, wing_id) " +
                    "FROM dw.v_memo_flags WHERE memo_client_uuid = '$routeless'"))
            assertEquals("true", scalar("SELECT (memo_family_uuid IS NOT NULL)::text FROM dw.v_memo_flags WHERE memo_client_uuid = '$flagged'"))
        }
        val cols = c.scalar("SELECT string_agg(attname, ',' ORDER BY attnum) FROM pg_attribute WHERE attrelid = 'dw.v_memo_flags'::regclass AND attnum > 0")!!.split(',')
        assertTrue(cols.none { it in setOf("username", "full_name", "user_name", "user_code", "contact_number") }, "no names or contacts in dw: $cols")
        // V0118: bi_reader reads the view through column grants without the printed memo number (personal), so its
        // table-level check is false; ReuseSurfaceTest proves the columns it keeps.
        assertEquals("true|true|false|true", listOf("api_rw", "web_ro", "bi_reader", "worker_rw").joinToString("|") { can(c, it, "dw.v_memo_flags", "SELECT")!! })
    }

    @Test
    fun v0112SyncRejectedTakesPriceInvalidAndStillRefusesQuarantineCodes() = db.connect().use { c ->
        // The rejected-status codes of contract 1.8 (price_invalid new) plus the server-only retry_exhausted go to
        // sync_rejected; the 15 quarantined codes belong to sync_quarantine and stay refused here (V0008 split).
        val rejected = listOf(
            "scope_stale", "config_version_unknown", "parent_missing", "outlet_pending_approval", "price_list_unknown", "schema_invalid",
            "unknown_record_type", "unknown_sku", "unknown_outlet", "unknown_route", "memo_no_invalid", "edit_not_allowed", "chain_too_deep",
            "voided_by_admin", "lines_exceed_max", "qty_invalid", "price_invalid", "attendance_duplicate", "server_error", "retry_exhausted",
            "unknown_gift", "insufficient_points", "gift_photo_exists",
        )
        val quarantined = listOf(
            "arithmetic_mismatch", "memo_no_duplicate", "content_duplicate", "checkout_too_early", "payload_conflict",
            "business_date_out_of_window", "scope_out_of_reach", "no_assignment_on_date", "device_integrity_failed", "device_not_enrolled",
            "user_disabled", "device_revoked", "app_version_blocked", "after_month_close", "programme_inactive",
        )
        fun insert(code: String) = "INSERT INTO app.sync_rejected (client_uuid, record_type, code, retryable, payload_sha256, payload, user_id) " +
            "VALUES (gen_random_uuid(), 'memo', '$code', false, sha256(gen_random_uuid()::text::bytea), '{}', ${user("sr1001")})"
        c.tx {
            rejected.forEach { exec(insert(it)) }
            assertEquals("1", scalar("SELECT count(*) FROM app.sync_rejected WHERE code = 'price_invalid'"))
            (quarantined + "no_such_code").forEach { assertEquals("23514", refused(insert(it)), it) }
            // The quarantine table still takes exactly the quarantined codes, not price_invalid.
            assertEquals("23514", refused("INSERT INTO app.sync_quarantine (client_uuid, record_type, code, payload_sha256, payload, user_id, business_date) " +
                "VALUES (gen_random_uuid(), 'memo', 'price_invalid', sha256('x'::bytea), '{}', ${user("sr1001")}, '2026-10-05')"))
        }
        // V0113 validated the constraint.
        assertEquals("true", c.scalar("SELECT convalidated::text FROM pg_constraint WHERE conname = 'sync_rejected_code_check' AND conrelid = 'app.sync_rejected'::regclass"))
    }

    @Test
    fun v0114RiskSignalTakesCheckoutWithoutSubmitWeightedTwenty() = db.connect().use { c ->
        // D-NIGHT-CHECKOUT-01 (backend-core #18): subject the user, one per user-day, severity 2 on the row, weight 20 in the
        // registry default; the earlier codes and weights stay. Business date = the seed's today (D-TEST-DATE-PIN-01).
        assertEquals("20|30|100", c.scalar("SELECT concat_ws('|', default_value->>'CHECKOUT_WITHOUT_SUBMIT', default_value->>'MEMO_DUPLICATE_SUSPECT', default_value->>'GEO_MOCK') FROM app.cfg_key WHERE key = 'cfg.geo.integrity_weight'"))
        fun insert(code: String) = "INSERT INTO app.risk_signal (code, severity, business_date, subject_type, subject_id, user_id, score, config_version) " +
            "SELECT '$code', 2, app.dhaka_date(now()), 'user', u.id::text, u.id, 20, 0 FROM app.app_user u WHERE u.username = 'sr1001' " +
            "ON CONFLICT (code, subject_type, subject_id, business_date) DO NOTHING"
        c.tx {
            exec(insert("CHECKOUT_WITHOUT_SUBMIT"))
            exec(insert("CHECKOUT_WITHOUT_SUBMIT")) // the server's resend path: one signal per user-day
            exec(insert("MEMO_DUPLICATE_SUSPECT"))
            assertEquals("1", scalar("SELECT count(*) FROM app.risk_signal WHERE code = 'CHECKOUT_WITHOUT_SUBMIT' AND business_date = app.dhaka_date(now())"))
            assertEquals("23514", refused(insert("CHECKOUT_WITHOUT_SUBMT")))
        }
        // V0115 validated the constraint.
        assertEquals("true", c.scalar("SELECT convalidated::text FROM pg_constraint WHERE conname = 'risk_signal_code_check' AND conrelid = 'app.risk_signal'::regclass"))
    }

    @Test
    fun v0089ModuleGrantsHoldSixteenModules() = db.connect().use { c ->
        // The seeded admin holds w on every module (seed 01, as V0089 does for admins present when it runs; V0135 adds support).
        assertEquals("16|w", c.scalar("SELECT count(*) || '|' || string_agg(DISTINCT access, ',') FROM app.user_module_grant WHERE user_id = ${user("admin1001")}"))
        c.tx {
            exec("UPDATE app.user_module_grant SET access = 'r', granted_by = $superadmin, granted_at = now() WHERE user_id = ${user("admin1001")} AND module = 'mdm'")
            // Seed 07 (pilot admins get every module) runs again on every dev seed load: a narrowed grant stays narrowed.
            exec(java.io.File(System.getProperty("aron.seed")).resolve("07_admin_module_grants.sql").readText())
            assertEquals("16|r", scalar("SELECT count(*) || '|' || (SELECT access FROM app.user_module_grant WHERE user_id = ${user("admin1001")} AND module = 'mdm') FROM app.user_module_grant WHERE user_id = ${user("admin1001")}"))
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
    fun v0093ActiveFlagIsGeneratedAndDuplicateActiveHousesAreAllowed() = db.connect().use { c ->
        c.tx {
            assertEquals("t", scalar("SELECT bool_and(is_active = (status = 'active'))::text::char FROM app.house"))
            val terr = "(SELECT territory_id FROM app.house ORDER BY id LIMIT 1)"
            exec("INSERT INTO app.house (code, name, territory_id) VALUES ('H-QA-1', 'QA House', $terr)")
            // Lead ruling: duplicates are real data; the admin merge endpoint de-duplicates, not a unique index.
            exec("INSERT INTO app.house (code, name, territory_id) VALUES ('H-QA-2', '  qa house ', $terr)")
            assertEquals("2", scalar("SELECT count(*) FROM app.house WHERE is_active AND lower(btrim(name)) = 'qa house'"))
            assertEquals("0", scalar("SELECT count(*) FROM pg_indexes WHERE indexname = 'house_active_name_once'"))
            exec("INSERT INTO app.house (code, name, territory_id, status) VALUES ('H-QA-3', 'QA HOUSE', $terr, 'inactive')")
            assertEquals("f", scalar("SELECT is_active::text::char FROM app.house WHERE code = 'H-QA-3'"))
            assertEquals("428C9", refused("UPDATE app.house SET is_active = true WHERE code = 'H-QA-3'"))
        }
        assertEquals("2", c.scalar("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'dw' AND table_name IN ('dim_geo', 'dim_geo_version') AND column_name = 'house_id'"))
    }

    @Test
    fun v0093TheOneOffDedupeKeepsTheBusiestActiveHouse() {
        // Re-run the de-duplication statements of V0093 on duplicates made after the migration, as on a database
        // that held them before it.
        val sql = java.io.File(System.getProperty("aron.migrations")).listFiles()!!.single { it.name.startsWith("V0093__") }.readText()
        val dedupe = sql.substring(sql.indexOf("CREATE TEMP TABLE house_dup"), sql.indexOf("-- end of the one-off de-duplication"))
        db.connect().use { c ->
            c.tx {
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
            assertEquals("23503", refused("INSERT INTO app.route_monthly_target (route_id, sku_id, month, qty_base, batch_uuid) VALUES ($route, $sku, DATE '2026-12-01', 1, gen_random_uuid())"))
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
            // approved_at is the server's: a caller value is replaced.
            exec("UPDATE app.device_command SET state = 'queued', sig = $sig, approved_by = $superadmin, approved_at = TIMESTAMPTZ '2000-01-01 00:00Z' WHERE command_id = $reset")
            assertEquals("t", scalar("SELECT (approved_at > now() - interval '1 hour')::text::char FROM app.device_command WHERE command_id = $reset"))
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
    fun v0102CommandsMoveForwardOnlyAndAPendingResetCanExpire() = db.connect().use { c ->
        c.tx {
            val dev = "(SELECT min(id) FROM app.device)"
            val admin = user("admin1001")
            val sig = "'" + "B".repeat(86) + "'"
            fun cmd(id: String, type: String, state: String, by: String = admin, expires: String = "now() + interval '1 day'") = exec(
                "INSERT INTO app.device_command (command_id, device_id, type, state, reason, request_sha256, sig, created_by, created_at, expires_at) " +
                    "VALUES ('$id', $dev, '$type', '$state', 'qa test', sha256('x'::bytea), ${if (state == "pending_approval") "NULL" else sig}, $by, now() - interval '2 hours', $expires)")
            // A pending reset past its expiry: mdm's pull expires it unsigned and unapproved (checker round 3 blocker).
            val r1 = UUID.randomUUID()
            cmd("$r1", "factory_reset", "pending_approval", expires = "now() - interval '1 hour'")
            exec("UPDATE app.device_command SET state = 'expired' WHERE device_id = $dev AND state IN ('pending_approval','queued','sent') AND expires_at <= now()")
            assertEquals("expired", scalar("SELECT state FROM app.device_command WHERE command_id = '$r1'"))
            assertEquals("23514", refused("UPDATE app.device_command SET state = 'queued', sig = $sig, approved_by = $superadmin WHERE command_id = '$r1'"))
            // A rejected reset stays rejected; a reset names its requester.
            val r2 = UUID.randomUUID()
            cmd("$r2", "factory_reset", "pending_approval")
            // Round 4: the decision and the move are one step; no signature on a rejection; no delivery while pending.
            assertEquals("23514", refused("UPDATE app.device_command SET approved_by = $superadmin, sig = $sig WHERE command_id = '$r2'"))
            assertEquals("23514", refused("UPDATE app.device_command SET state = 'queued', sig = $sig WHERE command_id = '$r2'"))
            assertEquals("23514", refused("UPDATE app.device_command SET state = 'rejected', approved_by = $superadmin, sig = $sig WHERE command_id = '$r2'"))
            assertEquals("23514", refused("UPDATE app.device_command SET sent_at = now() WHERE command_id = '$r2'"))
            assertEquals("23514", refused("UPDATE app.device_command SET push_sent_at = now() WHERE command_id = '$r2'"))
            exec("UPDATE app.device_command SET state = 'rejected', approved_by = $superadmin, decision_reason = 'not this phone' WHERE command_id = '$r2'")
            assertEquals("23514", refused("UPDATE app.device_command SET state = 'queued', sig = $sig WHERE command_id = '$r2'"))
            assertEquals("23514", refused("UPDATE app.device_command SET approved_by = $admin WHERE command_id = '$r2'"))
            assertEquals("23514", refused("UPDATE app.device_command SET decision_reason = 'changed' WHERE command_id = '$r2'"))
            assertEquals("23514", refused("INSERT INTO app.device_command (command_id, device_id, type, state, reason, request_sha256, expires_at) VALUES (gen_random_uuid(), $dev, 'factory_reset', 'pending_approval', 'qa test', sha256('x'::bytea), now() + interval '1 day')"))
            // queued -> sent -> acked; never back, the request fixed, delivery and result written once.
            val l = UUID.randomUUID()
            cmd("$l", "lock", "queued")
            assertEquals("23514", refused("UPDATE app.device_command SET approved_by = $superadmin WHERE command_id = '$l'"))
            assertEquals("23514", refused("UPDATE app.device_command SET result = '{\"ok\": true}' WHERE command_id = '$l'"))
            exec("UPDATE app.device_command SET push_sent_at = now() WHERE command_id = '$l'")
            assertEquals("23514", refused("UPDATE app.device_command SET type = 'factory_reset' WHERE command_id = '$l'"))
            assertEquals("23514", refused("UPDATE app.device_command SET params = '{\"x\": 1}' WHERE command_id = '$l'"))
            assertEquals("23514", refused("UPDATE app.device_command SET expires_at = now() + interval '2 days' WHERE command_id = '$l'"))
            exec("UPDATE app.device_command SET state = 'sent', sent_at = now() WHERE command_id = '$l'")
            assertEquals("23514", refused("UPDATE app.device_command SET sent_at = now() + interval '1 minute' WHERE command_id = '$l'"))
            exec("UPDATE app.device_command SET state = 'acked', acked_at = now(), result = '{\"ok\": true}' WHERE command_id = '$l'")
            assertEquals("23514", refused("UPDATE app.device_command SET state = 'queued', acked_at = NULL, result = NULL WHERE command_id = '$l'"))
            assertEquals("23514", refused("UPDATE app.device_command SET result = '{\"ok\": false}' WHERE command_id = '$l'"))
            assertEquals("23514", refused("UPDATE app.device_command SET state = 'failed' WHERE command_id = '$l'"))
        }
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
