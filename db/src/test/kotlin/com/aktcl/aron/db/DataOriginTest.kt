package com.aktcl.aron.db

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * V0116 data origin (data-architecture review 2026-10-25 High 3, D-DB-DATA-ORIGIN-01): masters carry data_origin, every
 * registry capture table stamps it at insert from the row's users, outlet, route and parent, only the owner's restamp
 * changes it, and dw facts, aggregates and every dw.v_* view over captured or master rows carry it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataOriginTest {
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

    private fun Connection.refused(sql: String): String = refusal(sql).sqlState

    private fun Connection.refusal(sql: String): SQLException {
        exec("SAVEPOINT r")
        val e = assertFailsWith<SQLException>(sql) { exec(sql) }
        exec("ROLLBACK TO SAVEPOINT r")
        return e
    }

    private val day = "DATE '2026-10-05'"
    private fun user(name: String) = "(SELECT id FROM app.app_user WHERE username = '$name')"
    private fun outlet(code: String) = "(SELECT id FROM app.outlet WHERE code = '$code')"
    private fun route(code: String) = "(SELECT id FROM app.route WHERE code = '$code')"

    /** A real (production) SR, route and outlet, made inside the test transaction. */
    private fun Connection.realWorld() {
        exec("INSERT INTO app.app_user (username, full_name, role, designation, employee_code, locale, home_zone_id, pilot, must_change_password) " +
            "SELECT 'realsr', 'Real SR', 'SR', 'Sales Representative', 'R-1', 'bn', id, false, false FROM app.zone WHERE code = 'Z-MIR'")
        exec("INSERT INTO app.route (code, name, display_label, zone_id, kind, visit_kind, visit_days_mask, sequence_no) " +
            "SELECT 'REAL-1', 'Real route', 'Real', id, 'sr', 'daily', 127, 9 FROM app.zone WHERE code = 'Z-MIR'")
        exec("INSERT INTO app.outlet (code, name, name_bn, owner_name, contact_number, address, zone_id, route_id, cluster_id, channel, geo_class, lat, lng, " +
            "location_basis, location_confirmed, location_accuracy_m, outlet_kind, price_type, visit_sequence) " +
            "SELECT 'REAL-O1', 'Real shop', 'দোকান', 'Owner', '01700000001', 'Road 1', o.zone_id, ${route("REAL-1")}, o.cluster_id, o.channel, o.geo_class, o.lat, o.lng, " +
            "o.location_basis, o.location_confirmed, o.location_accuracy_m, o.outlet_kind, o.price_type, 99 FROM app.outlet o WHERE o.id = (SELECT min(id) FROM app.outlet)")
    }

    private fun memo(uuid: String, no: String, userName: String, outletCode: String, routeCode: String?, extraCols: String = "", extraVals: String = "") =
        "INSERT INTO app.memo (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, visit_client_uuid, " +
            "outlet_id, memo_no, memo_kind, committed_at, price_list_date, price_type, gross_mtk, offer_discount_mtk, drp_discount_mtk, " +
            "qc_deduction_mtk, round_adj_mtk, net_mtk, paid_mtk, due_mtk, is_credit, line_count, discount_line_count, qc_line_count$extraCols) " +
            "VALUES ('$uuid', '$uuid', $day, ${user(userName)}, ${routeCode?.let { route(it) } ?: "NULL"}, now(), 0, gen_random_uuid(), " +
            "${outlet(outletCode)}, '$userName-261005-$no', 'sale', now(), $day, 'outlet', 1000, 0, 0, 0, 0, 1000, 1000, 0, false, 1, 0, 0$extraVals)"

    private fun line(memo: String, userName: String, routeCode: String?) =
        "INSERT INTO app.memo_line (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, memo_client_uuid, " +
            "line_no, sku_id, line_kind, qty_entered, unit_entered, pack_factor, qty_base, price_type, price_valid_from, base_price_mtk, " +
            "price_per_qty, gross_mtk) SELECT gen_random_uuid(), gen_random_uuid(), $day, ${user(userName)}, ${routeCode?.let { route(it) } ?: "NULL"}, now(), 0, '$memo', " +
            "1, min(id), 'sale', 1, 'stick', 1, 1, 'outlet', '2026-01-01', 1000, 1, 1000 FROM app.sku WHERE category_code = 'cigarette'"

    private fun Connection.origin(table: String, uuidCol: String, uuid: String) =
        scalar("SELECT data_origin::text FROM $table WHERE $uuidCol = '$uuid'")

    @Test
    fun mastersDefaultToProductionAndTheSeedTagsItsOwnRows() = db.connect().use { c ->
        for (t in listOf("outlet", "route", "app_user")) {
            assertEquals(
                "data_origin|app|data_origin|NO|'production'::app.data_origin",
                c.scalar("SELECT concat_ws('|', column_name, udt_schema, udt_name, is_nullable, column_default) FROM information_schema.columns WHERE table_schema = 'app' AND table_name = '$t' AND column_name = 'data_origin'"),
            )
        }
        assertEquals("production,pilot,test,seed", c.scalar("SELECT string_agg(e::text, ',' ORDER BY n) FROM unnest(enum_range(NULL::app.data_origin)) WITH ORDINALITY u(e, n)"))
        // Test profile: the seven test accounts are test, the built-in aron.system account (V0006) production, the Mirpur
        // fixture's routes and outlets seed.
        assertEquals("production:aron.system|test:7", c.scalar("SELECT string_agg(o, '|' ORDER BY o) FROM (SELECT data_origin || ':' || CASE WHEN data_origin = 'test' THEN count(*)::text ELSE string_agg(username, ',') END o FROM app.app_user GROUP BY data_origin) t"))
        assertEquals("seed", c.scalar("SELECT string_agg(DISTINCT data_origin::text, ',') FROM app.route WHERE code LIKE 'MIR-SR-%'"))
        assertEquals("seed", c.scalar("SELECT string_agg(DISTINCT o.data_origin::text, ',') FROM app.outlet o JOIN app.zone z ON z.id = o.zone_id WHERE z.code = 'Z-MIR'"))
        // The tagging is classification, not an edit: the seeded masters keep version 1 (admin If-Match, bundle deltas).
        assertEquals("1", c.scalar("SELECT string_agg(DISTINCT version::text, ',') FROM (SELECT version FROM app.route UNION ALL SELECT version FROM app.outlet UNION ALL SELECT version FROM app.app_user WHERE username = 'sr1001') v"))
        c.tx {
            exec("UPDATE app.route SET name = name || ' x' WHERE code = 'MIR-SR-D'")
            assertEquals("2", scalar("SELECT version FROM app.route WHERE code = 'MIR-SR-D'"), "a real edit still bumps")
        }
    }

    @Test
    fun aCaptureRowTakesTheMostSyntheticOfItsUserOutletRouteAndParent() = db.connect().use { c ->
        c.tx {
            realWorld()
            assertEquals("production|production|production", scalar("SELECT concat_ws('|', (SELECT data_origin FROM app.app_user WHERE username = 'realsr'), (SELECT data_origin FROM app.route WHERE code = 'REAL-1'), (SELECT data_origin FROM app.outlet WHERE code = 'REAL-O1'))"))
            val real = "0d000000-0000-4000-8000-000000000001"
            val atTest = "0d000000-0000-4000-8000-000000000002"
            val bySeedUser = "0d000000-0000-4000-8000-000000000003"
            val forged = "0d000000-0000-4000-8000-000000000004"
            exec(memo(real, "001", "realsr", "REAL-O1", "REAL-1"))
            // The owner's test outlet on a real route, sold by a real SR: test.
            exec("UPDATE app.outlet SET data_origin = 'test' WHERE code = 'REAL-O1'")
            exec(memo(atTest, "002", "realsr", "REAL-O1", "REAL-1"))
            exec("UPDATE app.outlet SET data_origin = 'production' WHERE code = 'REAL-O1'")
            // A test account at a real outlet: test.
            exec(memo(bySeedUser, "003", "sr1001", "REAL-O1", "REAL-1"))
            // A phone payload that names data_origin is overwritten (the sync writer copies payload keys into columns).
            exec(memo(forged, "004", "sr1001", "REAL-O1", "REAL-1", ", data_origin", ", 'production'"))
            assertEquals("production", origin("app.memo", "client_uuid", real))
            assertEquals("test", origin("app.memo", "client_uuid", atTest))
            assertEquals("test", origin("app.memo", "client_uuid", bySeedUser))
            assertEquals("test", origin("app.memo", "client_uuid", forged))
            // A line has no outlet: it takes its memo's origin even when its own user and route are real.
            exec(line(atTest, "realsr", "REAL-1"))
            exec(line(real, "realsr", "REAL-1"))
            assertEquals("test", origin("app.memo_line", "memo_client_uuid", atTest))
            assertEquals("production", origin("app.memo_line", "memo_client_uuid", real))
            // The Mirpur fixture (seed) wins over a test account: seed sorts last.
            val fixture = "0d000000-0000-4000-8000-000000000005"
            val fixtureOutlet = scalar("SELECT code FROM app.outlet WHERE zone_id = (SELECT id FROM app.zone WHERE code = 'Z-MIR') ORDER BY id LIMIT 1")!!
            exec(memo(fixture, "005", "sr1001", fixtureOutlet, "MIR-SR-D"))
            assertEquals("seed", origin("app.memo", "client_uuid", fixture))
        }
    }

    @Test
    fun onlyTheOwnersRestampChangesAStampedOrigin() = db.connect().use { c ->
        c.tx {
            realWorld()
            val m = "0d000000-0000-4000-8000-000000000011"
            exec(memo(m, "011", "realsr", "REAL-O1", "REAL-1"))
            exec(line(m, "realsr", "REAL-1"))
            // The API role may UPDATE these tables (status moves); the origin guard still refuses the column.
            assertEquals("true|true", scalar("SELECT has_table_privilege('api_rw', 'app.memo', 'UPDATE') || '|' || has_table_privilege('api_rw', 'app.memo_line', 'UPDATE')"))
            exec("SET LOCAL ROLE api_rw")
            for (t in listOf("memo" to "client_uuid", "memo_line" to "memo_client_uuid")) {
                val e = refusal("UPDATE app.${t.first} SET data_origin = 'test' WHERE ${t.second} = '$m'")
                assertEquals("42501", e.sqlState)
                assertTrue("restamp_data_origin" in e.message.orEmpty(), e.message)
            }
            exec("RESET ROLE")
            // The outlet turns out to be a test outlet: the restamp moves the memo and its line, nothing else changes.
            exec("UPDATE app.outlet SET data_origin = 'test' WHERE code = 'REAL-O1'")
            assertEquals("production", origin("app.memo", "client_uuid", m))
            // The API role may execute every app function (V0014 grants), yet its restamp changes nothing: it is refused
            // (table privileges stop it first; where they would not, the origin guard proved above does).
            exec("SET LOCAL ROLE api_rw")
            assertEquals("42501", refused("SELECT app.restamp_data_origin(NULL)"))
            exec("RESET ROLE")
            assertEquals("2", scalar("SELECT app.restamp_data_origin(NULL)"))
            assertEquals("test", origin("app.memo", "client_uuid", m))
            assertEquals("test", origin("app.memo_line", "memo_client_uuid", m))
            assertEquals("0", scalar("SELECT app.restamp_data_origin(NULL)"), "a second restamp changes nothing")
            // The write-once and append-only guards still refuse every other change.
            assertEquals("42501", refused("UPDATE app.memo SET gross_mtk = 2000 WHERE client_uuid = '$m'"))
            assertEquals("42501", refused("DELETE FROM app.memo_line WHERE memo_client_uuid = '$m'"))
        }
    }

    @Test
    fun theRegistryCoversEveryTransactionTableWithStampAndGuard() = db.connect().use { c ->
        // Every app table of retention class transaction (data dictionary) is stamped, except two with no row owner.
        val transaction = c.column(
            "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'app' AND c.relkind IN ('r', 'p') " +
                "AND NOT c.relispartition AND obj_description(c.oid) ~ 'retention: transaction' ORDER BY 1",
        ).filterNotNull().toSet()
        val registry = c.column("SELECT table_name FROM app.data_origin_capture ORDER BY 1").filterNotNull().toSet()
        assertEquals(setOf("entry_unlock", "media_upload"), transaction - registry, "transaction tables without data_origin")
        assertTrue(registry.containsAll(setOf("geo_fix", "geo_breadcrumb", "memo", "memo_line", "visit", "attendance_event", "due_collection", "stock_movement")))
        for (t in registry) {
            assertEquals("data_origin", c.scalar("SELECT udt_name FROM information_schema.columns WHERE table_schema = 'app' AND table_name = '$t' AND column_name = 'data_origin'"), t)
            assertEquals(
                "${t}_guard_origin,${t}_stamp_origin",
                c.scalar("SELECT string_agg(tgname, ',' ORDER BY tgname) FROM pg_trigger WHERE tgrelid = 'app.$t'::regclass AND tgname LIKE '%\\_origin' AND NOT tgisinternal"),
                t,
            )
        }
        // The write-once guards list data_origin; the append-only guards were replaced.
        assertEquals("0", c.scalar("SELECT count(*) FROM pg_trigger t JOIN app.data_origin_capture r ON t.tgrelid = ('app.' || r.table_name)::regclass WHERE t.tgfoid = 'app.guard_synced_row'::regproc AND t.tgparentid = 0 AND pg_get_triggerdef(t.oid) NOT LIKE '%''data_origin'')'"))
        assertEquals("0", c.scalar("SELECT count(*) FROM pg_trigger t JOIN app.data_origin_capture r ON t.tgrelid = ('app.' || r.table_name)::regclass WHERE t.tgfoid = 'app.deny_mutation'::regproc"))
        assertEquals("due_ledger,loyalty_ledger,qc_summary_entry_line", c.scalar("SELECT string_agg(r.table_name, ',' ORDER BY 1) FROM pg_trigger t JOIN app.data_origin_capture r ON t.tgrelid = ('app.' || r.table_name)::regclass WHERE t.tgfoid = 'app.deny_mutation_except_origin'::regproc AND t.tgparentid = 0"))
    }

    @Test
    fun dwFactsAndAggregatesCarryTheOriginAndCountsReconcileByOrigin() = db.connect().use { c ->
        c.tx {
            realWorld()
            val real = "0d000000-0000-4000-8000-000000000021"
            val test = "0d000000-0000-4000-8000-000000000022"
            exec(memo(real, "021", "realsr", "REAL-O1", "REAL-1"))
            exec(memo(test, "022", "sr1001", "REAL-O1", "REAL-1"))
            // The projector's own insert (explicit columns, no data_origin): the trigger fills it from app.memo.
            exec(
                "INSERT INTO dw.fact_memo (business_date, memo_client_uuid, memo_no, user_id, route_id, outlet_id, status, committed_at, line_count, gross_mtk, offer_discount_mtk, " +
                    "drp_discount_mtk, qc_deduction_mtk, net_mtk, paid_mtk, due_mtk, is_credit, captured_offline, received_at, last_event_id) " +
                    "SELECT business_date, client_uuid, memo_no, user_id, route_id, outlet_id, 'active', now(), 1, 1000, 0, 0, 0, 1000, 1000, 0, false, false, now(), 1 FROM app.memo WHERE client_uuid IN ('$real', '$test')",
            )
            assertEquals(
                scalar("SELECT string_agg(data_origin || ':' || n, ',' ORDER BY data_origin) FROM (SELECT data_origin, count(*) n FROM app.memo WHERE route_id = ${route("REAL-1")} GROUP BY 1) t"),
                scalar("SELECT string_agg(data_origin || ':' || n, ',' ORDER BY data_origin) FROM (SELECT data_origin, count(*) n FROM dw.fact_memo WHERE route_id = ${route("REAL-1")} GROUP BY 1) t"),
            )
            assertEquals("production:1,test:1", scalar("SELECT string_agg(data_origin || ':' || n, ',' ORDER BY data_origin) FROM (SELECT data_origin, count(*) n FROM dw.fact_memo WHERE route_id = ${route("REAL-1")} GROUP BY 1) t"))
            // A real route's day with one test sale in it is not production; a real route-day without one is.
            exec("INSERT INTO dw.agg_daily_route (business_date, route_id, zone_id) SELECT $day, id, zone_id FROM app.route WHERE code = 'REAL-1'")
            exec("INSERT INTO dw.agg_daily_route (business_date, route_id, zone_id) SELECT $day + 1, id, zone_id FROM app.route WHERE code = 'REAL-1'")
            assertEquals("test", scalar("SELECT data_origin::text FROM dw.v_daily_route WHERE route_id = ${route("REAL-1")} AND business_date = $day"))
            assertEquals("production", scalar("SELECT data_origin::text FROM dw.v_daily_route WHERE route_id = ${route("REAL-1")} AND business_date = $day + 1"))
            exec("INSERT INTO dw.agg_daily_outlet (business_date, outlet_id, route_id) SELECT $day + 1, id, route_id FROM app.outlet WHERE code = 'REAL-O1'")
            assertEquals("production", scalar("SELECT data_origin::text FROM dw.v_daily_outlet WHERE outlet_id = ${outlet("REAL-O1")}"))
            // Per-user view: the test account's day is test, the real SR's day production.
            assertEquals("production", scalar("SELECT data_origin::text FROM dw.v_daily_sr WHERE user_id = ${user("realsr")} AND business_date = $day"))
            assertEquals("test", scalar("SELECT data_origin::text FROM dw.v_daily_sr WHERE user_id = ${user("sr1001")} AND business_date = $day"))
            // Dimensions follow their master on every write; a re-tag alone opens no SCD2 version (the _version tables
            // do not keep data_origin), so the facts' user_key does not move.
            exec("INSERT INTO dw.dim_user (user_id, role, status, pilot) SELECT id, role, status, pilot FROM app.app_user WHERE username IN ('realsr', 'sr1001')")
            assertEquals("production,test", scalar("SELECT string_agg(data_origin::text, ',' ORDER BY data_origin) FROM dw.dim_user WHERE user_id IN (${user("realsr")}, ${user("sr1001")})"))
            exec("UPDATE app.app_user SET data_origin = 'pilot' WHERE username = 'realsr'")
            exec("UPDATE dw.dim_user SET updated_at = now() WHERE user_id = ${user("realsr")}")
            assertEquals("pilot|1", scalar("SELECT d.data_origin || '|' || (SELECT count(*) FROM dw.dim_user_version v WHERE v.user_id = d.user_id) FROM dw.dim_user d WHERE d.user_id = ${user("realsr")}"))
            exec("UPDATE dw.dim_user SET designation = 'Senior SR' WHERE user_id = ${user("realsr")}")
            assertEquals("1", scalar("SELECT count(*) FROM dw.dim_user_version WHERE user_id = ${user("realsr")} AND valid_to IS NULL"), "a real change still versions (same-day rewrite)")
            // A fact copies its memo's frozen origin: re-tagging the user without a restamp moves neither.
            exec("UPDATE dw.fact_memo SET updated_at = now() WHERE memo_client_uuid = '$real'")
            assertEquals("production|production", scalar("SELECT (SELECT data_origin FROM app.memo WHERE client_uuid = '$real') || '|' || (SELECT data_origin FROM dw.fact_memo WHERE memo_client_uuid = '$real')"))
        }
    }

    @Test
    fun everyStableViewOverCapturedOrMasterRowsEndsWithDataOrigin() = db.connect().use { c ->
        val views = c.column("SELECT table_name FROM information_schema.views WHERE table_schema = 'dw' AND table_name LIKE 'v\\_%' ORDER BY 1").filterNotNull()
        val reference = setOf("v_calendar", "v_calendar_month", "v_working_day")
        for (v in views) {
            val last = c.scalar("SELECT column_name FROM information_schema.columns WHERE table_schema = 'dw' AND table_name = '$v' ORDER BY ordinal_position DESC LIMIT 1")
            if (v in reference) assertTrue(last != "data_origin", v) else assertEquals("data_origin", last, v)
        }
        assertEquals("true", c.scalar("SELECT has_table_privilege('web_ro', 'dw.v_daily_route', 'SELECT')::text"))
    }

    @Test
    fun theMigrationFillsRowsCapturedBeforeIt() {
        val old = TestPostgres.createDatabase()
        try {
            Flyway.configure().configuration(old.flyway().configuration).target("115").load().migrate()
            old.connect().use { c ->
                SeedLoader.load(c)
                c.exec(memo("0d000000-0000-4000-8000-000000000031", "031", "sr1001", c.scalar("SELECT min(code) FROM app.outlet")!!, "MIR-SR-D"))
                c.exec(line("0d000000-0000-4000-8000-000000000031", "sr1001", "MIR-SR-D"))
            }
            old.flyway().migrate()
            old.connect().use { c ->
                for (t in c.column("SELECT table_name FROM app.data_origin_capture").filterNotNull()) {
                    assertEquals("0", c.scalar("SELECT count(*) FROM app.$t WHERE data_origin IS NULL"), t)
                }
                // Before the seed re-run the masters are all production, so the old memo is production; the seed's
                // restamp (db/seed/08) then moves it to seed with its fixture outlet.
                assertEquals("production", c.origin("app.memo", "client_uuid", "0d000000-0000-4000-8000-000000000031"))
                SeedLoader.load(c)
                assertEquals("seed", c.origin("app.memo", "client_uuid", "0d000000-0000-4000-8000-000000000031"))
                assertEquals("seed", c.origin("app.memo_line", "memo_client_uuid", "0d000000-0000-4000-8000-000000000031"))
            }
        } finally {
            old.close()
        }
    }
}
