package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F-SYS-015 acceptance on real PostgreSQL 16: the seeded day (`seed_day.sql`, control totals hand-computed in the
 * comments) is aggregated by the dirty-key worker; a second run, a rebuild and a late batch give the numbers the
 * hand calculation says; a key dirtied during its own rebuild stays queued; a crashed worker's claim is taken over.
 */
class AggregationTest {
    private val day = LocalDate.parse("2026-10-04")
    private lateinit var fresh: FreshDb
    private lateinit var conn: Connection   // one session: the seed's pg_temp helper functions live in it

    @BeforeEach
    fun setUp() {
        fresh = FreshDb.create()
        conn = fresh.dataSource.connection
        conn.createStatement().use { it.execute(resource("seed_day.sql")) }
        conn.createStatement().use { it.execute(resource("login_extra.sql")) }   // Successful Login: download and check-in of the seeded routes
        // The projector learns of every record from the outbox; the ingest path (backend-core) writes these rows.
        for ((uuid, type) in listOf(
            "00000000-0000-4000-8000-0000000000a1" to "memo.created", "00000000-0000-4000-8000-0000000000a2" to "memo.created",
            "00000000-0000-4000-8000-0000000000a4" to "memo.created", "00000000-0000-4000-8000-0000000000a5" to "memo.created",
            "00000000-0000-4000-8000-000000000003" to "visit.closed",
        )) event(type, uuid)
    }

    @AfterEach
    fun tearDown() { conn.close(); fresh.close() }

    private fun resource(n: String) = checkNotNull(javaClass.classLoader.getResourceAsStream(n)).bufferedReader().readText()

    private fun event(type: String, sourceUuid: String, date: String = "2026-10-04", aggregateId: String = sourceUuid, payload: String = "{}") = conn.prepareStatement(
        "INSERT INTO app.domain_event (event_type, aggregate_type, aggregate_id, business_date, source_client_uuid, payload) VALUES (?, split_part(?, '.', 1), ?, ?::date, ?::uuid, ?::jsonb)",
    ).use { it.setString(1, type); it.setString(2, type); it.setString(3, aggregateId); it.setString(4, date); it.setString(5, sourceUuid); it.setString(6, payload); it.executeUpdate() }

    private fun worker(clock: AronClock = AronClock.SYSTEM, id: String = "w1") = AggregationWorker(fresh.db, clock, id)

    private fun long(sql: String): Long = conn.createStatement().use { s -> s.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } }
    private fun route(code: String, col: String) = long("SELECT $col FROM dw.agg_daily_route a JOIN app.route r ON r.id = a.route_id WHERE r.code = '$code' AND business_date = '2026-10-04'")
    private fun zone(code: String, col: String) = long("SELECT $col FROM dw.agg_daily_zone a JOIN app.zone z ON z.id = a.zone_id WHERE z.code = '$code' AND business_date = '2026-10-04'")

    /** Every aggregate and fact row as comparable text, without the two bookkeeping columns that move on each run. */
    private fun snapshot(): String = conn.createStatement().use { s ->
        val tables = listOf("agg_daily_route", "agg_daily_route_sku", "agg_daily_route_brand", "agg_daily_zone", "agg_hourly_zone", "agg_daily_outlet", "fact_visit", "fact_memo")
        tables.joinToString("\n") { t ->
            s.executeQuery("SELECT coalesce(string_agg((to_jsonb(x) - 'updated_at' - 'last_event_id')::text, '|' ORDER BY (to_jsonb(x) - 'updated_at' - 'last_event_id')::text), '') FROM dw.$t x")
                .use { rs -> rs.next(); t + "=" + rs.getString(1) }
        }
    }

    @Test
    fun seededDayEqualsTheHandComputedControlTotals() {
        worker().runUntilIdle()
        // R1: visits O1 (in range), O2 (forced), O3 (mocked, abandoned); memos 16 000 + 18 000 active, one voided, one zero-sale.
        assertEquals(3, route("R1", "visits")); assertEquals(2, route("R1", "visited_outlets")); assertEquals(2, route("R1", "successful_calls"))
        assertEquals(1, route("R1", "geo_valid_visits")); assertEquals(1, route("R1", "force_sale_visits")); assertEquals(1, route("R1", "mock_visits"))
        assertEquals(2, route("R1", "active_memo_count")); assertEquals(34_000, route("R1", "gross_mtk")); assertEquals(34_000, route("R1", "net_mtk"))
        assertEquals(26_000, route("R1", "paid_mtk")); assertEquals(8_000, route("R1", "due_mtk")); assertEquals(3_000, route("R1", "dues_collected_mtk"))
        assertEquals(3, route("R1", "target_outlets"))
        assertEquals(1, route("R2", "visits")); assertEquals(5_000, route("R2", "net_mtk"))
        assertEquals(24_000, route("R3", "net_mtk"))
        // Zone 1 = R1 + R2, zone 2 = R3.
        assertEquals(2, zone("Z1", "target_routes")); assertEquals(2, zone("Z1", "logged_in_routes")); assertEquals(1, zone("Z1", "sales_submitted_routes"))
        assertEquals(4, zone("Z1", "target_outlets")); assertEquals(3, zone("Z1", "visited_outlets")); assertEquals(4, zone("Z1", "visits"))
        assertEquals(2, zone("Z1", "geo_valid_visits")); assertEquals(3, zone("Z1", "active_memo_count")); assertEquals(39_000, zone("Z1", "gross_mtk"))
        assertEquals(0, long("SELECT final_submitted::int FROM dw.agg_daily_zone a JOIN app.zone z ON z.id = a.zone_id WHERE z.code = 'Z1'"))
        assertEquals(1, long("SELECT final_submitted::int FROM dw.agg_daily_zone a JOIN app.zone z ON z.id = a.zone_id WHERE z.code = 'Z2'"))
        // SKU and brand rows of R1: SKU1 30 sold (2 memos, 24 000), 100 issued, 10 returned; SKU2 10 sold (1 memo, 10 000). The voided memo's SKU2 line is out.
        val sku = "SELECT %s FROM dw.agg_daily_route_sku a JOIN app.sku s ON s.id = a.sku_id JOIN app.route r ON r.id = a.route_id WHERE r.code = 'R1' AND s.code = '%s'"
        assertEquals(30, long(sku.format("sold_qty_base", "SKU1"))); assertEquals(100, long(sku.format("issued_qty_base", "SKU1")))
        assertEquals(10, long(sku.format("returned_qty_base", "SKU1"))); assertEquals(24_000, long(sku.format("gross_mtk", "SKU1")))
        assertEquals(10, long(sku.format("sold_qty_base", "SKU2"))); assertEquals(1, long(sku.format("memo_count", "SKU2")))
        val brand = "SELECT %s FROM dw.agg_daily_route_brand a JOIN app.product_node b ON b.id = a.brand_id JOIN app.route r ON r.id = a.route_id WHERE r.code = 'R1' AND b.code = '%s'"
        assertEquals(2, long(brand.format("memo_count", "B1"))); assertEquals(1, long(brand.format("memo_count", "B2")))
        // Outlet O2: one active memo of 18 000 with 8 000 due and 3 000 collected.
        val outlet = "SELECT %s FROM dw.agg_daily_outlet a JOIN app.outlet o ON o.id = a.outlet_id WHERE o.code = 'O2'"
        assertEquals(18_000, long(outlet.format("net_mtk"))); assertEquals(8_000, long(outlet.format("due_mtk"))); assertEquals(3_000, long(outlet.format("dues_collected_mtk")))
        // Facts: the voided memo is kept, marked voided; the Dhaka hour of the 04:00Z visit is 10.
        assertEquals(1, long("SELECT count(*) FROM dw.fact_memo WHERE status = 'voided'"))
        assertEquals(2, long("SELECT visits FROM dw.agg_hourly_zone a JOIN app.zone z ON z.id = a.zone_id WHERE z.code = 'Z1' AND hour_of_day = 10"))
        // The queue is empty and the consumer is at the last event.
        assertEquals(0, long("SELECT count(*) FROM app.dirty_key"))
        assertEquals(long("SELECT max(id) FROM app.domain_event"), long("SELECT last_event_id FROM app.event_consumer WHERE consumer = 'dw-projector'"))
    }

    @Test
    fun runningTwiceAndRebuildingGiveIdenticalNumbers() {
        val w = worker()
        w.runUntilIdle()
        val first = snapshot()
        assertTrue(first.contains("agg_daily_route={"))
        w.runUntilIdle()
        assertEquals(first, snapshot())
        assertEquals(3, w.requestRebuild(day))        // R1, R2, R3 have records that day
        w.runUntilIdle()
        assertEquals(first, snapshot())
        // Even a wiped aggregate is recomputed from the source rows to the same numbers.
        conn.createStatement().use { it.execute("TRUNCATE dw.agg_daily_route, dw.agg_daily_route_sku, dw.agg_daily_route_brand, dw.agg_daily_zone, dw.agg_hourly_zone, dw.agg_daily_outlet, dw.fact_visit, dw.fact_memo") }
        w.requestRebuild(day)
        w.runUntilIdle()
        assertEquals(first, snapshot())
    }

    @Test
    fun aLateBatchReaggregatesItsOwnBusinessDateOnly() {
        val w = worker()
        w.runUntilIdle()
        val before = snapshot()
        // A phone syncs hours later: one more 3 000 memo of R2 for the same business date.
        conn.createStatement().use {
            it.execute(
                "SELECT pg_temp.memo('00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-000000000004', 'sr002', 'R2', 'O4', 'sr002-261004-002', TIMESTAMPTZ '2026-10-04 09:00Z', 3000, 3000, 1, 'active');" +
                    "SELECT pg_temp.line('00000000-0000-4000-8000-0000000000b1', 1, 'SKU2', 3, 1000, 'sale')",
            )
        }
        event("memo.created", "00000000-0000-4000-8000-0000000000b1")
        assertEquals(1, w.project())
        // Only R2 of that date is queued, then its zone.
        assertEquals(listOf("route_day_agg"), conn.createStatement().use { s -> s.executeQuery("SELECT kind FROM app.dirty_key").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } })
        w.runUntilIdle()
        assertEquals(8_000, route("R2", "net_mtk")); assertEquals(2, route("R2", "active_memo_count")); assertEquals(1, route("R2", "successful_calls"))
        assertEquals(42_000, zone("Z1", "gross_mtk")); assertEquals(24_000, zone("Z2", "gross_mtk")); assertEquals(34_000, route("R1", "net_mtk"))
        assertTrue(snapshot() != before)
        assertEquals(0, long("SELECT count(*) FROM dw.agg_daily_route WHERE business_date <> '2026-10-04'"))
    }

    /** Data-architecture review finding 6: a void stored on the next business date rebuilds the memo's own day too. */
    @Test
    fun aVoidOnTheNextBusinessDateRebuildsTheSalesDayToo() {
        val w = worker()
        w.runUntilIdle()
        assertEquals(34_000, route("R1", "net_mtk"))
        val zoneBefore = zone("Z1", "gross_mtk")
        val void = "00000000-0000-4000-8000-0000000000e1"
        // The void record (memo_void, dated 2026-10-05 after midnight) ends memo a1 of 2026-10-04 (16 000 on R1).
        conn.createStatement().use {
            it.execute("UPDATE app.memo SET status = 'voided', status_changed_at = now(), voided_by_client_uuid = '$void' WHERE client_uuid = '00000000-0000-4000-8000-0000000000a1'")
        }
        // As DomainEventProducer writes it: aggregate the voided memo, payload the void row's own route (here another one).
        val other = long("SELECT id FROM app.route WHERE code = 'R2'")
        event("memo.voided", void, date = "2026-10-05", aggregateId = "00000000-0000-4000-8000-0000000000a1", payload = """{"route_id": $other}""")
        w.runUntilIdle()
        assertEquals(18_000, route("R1", "net_mtk"), "the sale's day is rebuilt without the voided memo")
        assertEquals(zoneBefore - 16_000, zone("Z1", "gross_mtk"), "and its zone-day")
    }

    /** Finding 6, the edit half: an edit stored the next day rebuilds the edited memo's day (the old memo no longer counts). */
    @Test
    fun anEditOnTheNextBusinessDateRebuildsTheEditedMemosDay() {
        val w = worker()
        w.runUntilIdle()
        assertEquals(34_000, route("R1", "net_mtk"))
        val edit = "00000000-0000-4000-8000-0000000000e2"
        val old = "00000000-0000-4000-8000-0000000000a2"   // 18 000 gross on R1, 2026-10-04
        conn.createStatement().use {
            val cols = it.executeQuery("SELECT string_agg(column_name, ', ' ORDER BY ordinal_position) FROM information_schema.columns WHERE table_schema = 'app' AND table_name = 'memo' AND column_name <> 'id' AND is_generated = 'NEVER'")
                .use { rs -> rs.next(); rs.getString(1) }.split(", ")
            val repl = mapOf("client_uuid" to "'$edit'::uuid", "family_uuid" to "'$edit'::uuid", "business_date" to "DATE '2026-10-05'", "memo_no" to "'sr001-261005-001'",
                "supersedes_client_uuid" to "'$old'::uuid", "edit_reason_code" to "'wrong_quantity'", "external_ref" to "NULL", "sig" to "NULL")
            it.execute("INSERT INTO app.memo (${cols.joinToString()}) SELECT ${cols.joinToString { c -> repl[c] ?: c }} FROM app.memo WHERE client_uuid = '$old'")
            it.execute("UPDATE app.memo SET status = 'superseded', status_changed_at = now(), superseded_by_client_uuid = '$edit' WHERE client_uuid = '$old'")
        }
        event("memo.created", edit, date = "2026-10-05")
        w.runUntilIdle()
        assertEquals(16_000, route("R1", "net_mtk"), "the edited memo's day no longer counts the superseded memo")
    }

    @Test
    fun aKeyDirtiedDuringItsRebuildStaysQueued() {
        val w = worker()
        w.project()
        var fired = false
        w.beforeRelease = { kind, subject, date ->
            if (kind == "route_day_agg" && !fired) {
                fired = true
                fresh.dataSource.connection.use { c -> c.createStatement().use { it.execute("SELECT app.mark_dirty('route_day_agg', $subject, '$date', 'late_row')") } }
            }
        }
        w.drain()
        // One route-day key was re-dirtied between claim and release: it is still queued, with a higher count and no claim.
        assertEquals(1, long("SELECT count(*) FROM app.dirty_key WHERE kind = 'route_day_agg'"))
        assertEquals(2, long("SELECT dirty_count FROM app.dirty_key WHERE kind = 'route_day_agg'"))
        assertEquals(0, long("SELECT count(*) FROM app.dirty_key WHERE kind = 'route_day_agg' AND claimed_at IS NOT NULL"))
        w.beforeRelease = { _, _, _ -> }
        w.runUntilIdle()
        assertEquals(0, long("SELECT count(*) FROM app.dirty_key"))
    }

    @Test
    fun aCrashedWorkersClaimIsTakenOverAfterTheLease() {
        val t0 = Instant.parse("2026-10-04T12:00:00Z")
        val rid = long("SELECT id FROM app.route WHERE code = 'R3'")
        conn.createStatement().use { it.execute("SELECT app.mark_dirty('route_day_agg', $rid, '2026-10-04', 'x'); UPDATE app.dirty_key SET claimed_at = '${t0}', claimed_by = 'dead'") }
        val fresher = worker(AronClock { t0.plusSeconds(30) }, "w2")
        assertEquals(0, fresher.drain())                       // still inside the lease: left alone
        val later = worker(AronClock { t0.plusSeconds(600) }, "w3")
        assertEquals(2, later.drain())                         // lease expired: taken over (the route-day, then its zone-day)
        assertEquals(24_000, route("R3", "net_mtk"))
    }

    @Test
    fun theProjectorReadsEachEventOnceAndIgnoresUnknownRecords() {
        val w = worker()
        assertEquals(5, w.project())
        assertEquals(0, w.project())
        event("memo.created", "00000000-0000-4000-8000-ffffffffffff")   // a record the source tables do not hold
        assertEquals(1, w.project())
        assertEquals(3, long("SELECT count(*) FROM app.dirty_key"))      // unknown record: no key; R1, R2, R3 only
        w.runUntilIdle()
        assertEquals(34_000, route("R1", "net_mtk"))
    }

    // ---------- checker (F-SYS-015 refutation) ----------

    /** Outbox gap: an event whose id was allocated before a later one but committed after it is skipped for good. */
    @Test
    fun checker_anEventCommittedOutOfIdOrderIsNotLost() {
        val w = worker()
        w.runUntilIdle()
        conn.createStatement().use {
            it.execute(
                "SELECT pg_temp.memo('00000000-0000-4000-8000-0000000000c1', '00000000-0000-4000-8000-000000000004', 'sr002', 'R2', 'O4', 'sr002-261004-009', TIMESTAMPTZ '2026-10-04 09:00Z', 3000, 3000, 1, 'active');" +
                    "SELECT pg_temp.line('00000000-0000-4000-8000-0000000000c1', 1, 'SKU2', 3, 1000, 'sale')",
            )
        }
        // Ingest transaction A takes an event id but has not committed yet; transaction B takes a higher id and commits.
        val slow = fresh.dataSource.connection
        slow.autoCommit = false
        slow.prepareStatement(
            "INSERT INTO app.domain_event (event_type, aggregate_type, aggregate_id, business_date, source_client_uuid) VALUES ('memo.created','memo','c1','2026-10-04','00000000-0000-4000-8000-0000000000c1')",
        ).use { it.executeUpdate() }
        event("visit.closed", "00000000-0000-4000-8000-000000000005")   // R3, higher id, committed first
        w.runUntilIdle()
        slow.commit(); slow.close()
        w.runUntilIdle()
        assertEquals(8_000, route("R2", "net_mtk"), "the memo of the slower ingest transaction never reached agg_daily_route")
    }

    /** s11.4: a user-day is suspicious only when its signal weights sum to >= cfg.geo.suspicious_score_threshold (50). */
    @Test
    fun checker_aSingleLowWeightSignalDoesNotMakeTheUserDaySuspicious() {
        conn.createStatement().use {
            it.execute(
                "INSERT INTO app.risk_signal (code, severity, business_date, subject_type, subject_id, user_id, route_id, zone_id, score, config_version) " +
                    "SELECT 'GEO_PERFECT_ACCURACY', 2, DATE '2026-10-04', 'user', u.id::text, u.id, r.id, r.zone_id, 20, 1 " +
                    "FROM app.app_user u, app.route r WHERE u.username = 'sr001' AND r.code = 'R1'",
            )
        }
        worker().runUntilIdle()
        assertEquals(0, route("R1", "suspicious_visits"), "score 20 < threshold 50, yet all 3 R1 visits are counted suspicious")
        assertEquals(0, zone("Z1", "suspicious_user_days"))
    }

    /** A route moved to another zone: a re-aggregated past day lands in the new zone but stays in the old zone too. */
    @Test
    fun checker_aRouteMovedBetweenZonesIsNotCountedTwice() {
        val w = worker()
        w.runUntilIdle()
        conn.createStatement().use { it.execute("UPDATE app.route SET zone_id = (SELECT id FROM app.zone WHERE code = 'Z2') WHERE code = 'R2'") }
        event("memo.created", "00000000-0000-4000-8000-0000000000a4")   // any late record of R2 for 2026-10-04
        w.runUntilIdle()
        val total = long("SELECT sum(gross_mtk) FROM dw.agg_daily_route WHERE business_date = '2026-10-04'")
        assertEquals(total, long("SELECT sum(gross_mtk) FROM dw.agg_daily_zone WHERE business_date = '2026-10-04'"), "zone totals double-count R2")
    }

    /** An outlet with records on two routes the same day: agg_daily_outlet keeps only the route rebuilt last. */
    @Test
    fun checker_anOutletSoldFromTwoRoutesKeepsBothRoutesNumbers() {
        conn.createStatement().use {
            it.execute(
                "SELECT pg_temp.memo('00000000-0000-4000-8000-0000000000c2', '00000000-0000-4000-8000-000000000001', 'sr001', 'R1', 'O4', 'sr001-261004-009', TIMESTAMPTZ '2026-10-04 08:30Z', 2000, 2000, 1, 'active');" +
                    "SELECT pg_temp.line('00000000-0000-4000-8000-0000000000c2', 1, 'SKU2', 2, 1000, 'sale')",
            )
        }
        event("memo.created", "00000000-0000-4000-8000-0000000000c2")
        worker().runUntilIdle()
        assertEquals(7_000, long("SELECT net_mtk FROM dw.agg_daily_outlet a JOIN app.outlet o ON o.id = a.outlet_id WHERE o.code = 'O4'"))
    }
    /** V0109: a day mixing all four categories keeps each quantity in its own base unit; a voided line, a non-sale line and a voided memo stay out. */
    @Test
    fun aMixedCategoryDayFillsTheFourPerCategoryColumns() {
        conn.createStatement().use {
            it.execute(
                """
                INSERT INTO app.sku (code, variant_id, category_code, name, short_name, base_unit, base_per_pack, entry_unit_default)
                  SELECT 'SKU3', id, 'lighter', 'Lighter', 'L1', 'piece', 1, 'pack' FROM app.product_node WHERE code = 'V1'
                  UNION ALL SELECT 'SKU4', id, 'match', 'Matches', 'M1', 'dozen', 1, 'dozen' FROM app.product_node WHERE code = 'V1'
                  UNION ALL SELECT 'SKU5', id, 'bidi', 'Bidi', 'B1', 'stick', 25, 'pack' FROM app.product_node WHERE code = 'V1';
                SELECT pg_temp.memo('00000000-0000-4000-8000-0000000000c3', '00000000-0000-4000-8000-000000000001', 'sr001', 'R1', 'O4', 'sr001-261004-010', TIMESTAMPTZ '2026-10-04 08:40Z', 6000, 6000, 4, 'active');
                SELECT pg_temp.line('00000000-0000-4000-8000-0000000000c3', 1, 'SKU2', 7, 1000, 'sale');
                SELECT pg_temp.line('00000000-0000-4000-8000-0000000000c3', 2, 'SKU3', 50, 100, 'sale');
                SELECT pg_temp.line('00000000-0000-4000-8000-0000000000c3', 3, 'SKU4', 3, 100, 'sale');
                SELECT pg_temp.line('00000000-0000-4000-8000-0000000000c3', 4, 'SKU5', 25, 100, 'sale');
                -- Stay out: a voided sale line, a free (non-sale) line, and a voided memo with a bidi line.
                SELECT pg_temp.line('00000000-0000-4000-8000-0000000000c3', 5, 'SKU3', 99, 100, 'sale');
                ALTER TABLE app.memo_line DISABLE TRIGGER memo_line_immutable;
                UPDATE app.memo_line SET voided_at = now() WHERE memo_client_uuid = '00000000-0000-4000-8000-0000000000c3' AND line_no = 5;
                ALTER TABLE app.memo_line ENABLE TRIGGER memo_line_immutable;
                SELECT pg_temp.line('00000000-0000-4000-8000-0000000000c3', 6, 'SKU4', 40, 100, 'promo_free');
                SELECT pg_temp.memo('00000000-0000-4000-8000-0000000000c4', '00000000-0000-4000-8000-000000000001', 'sr001', 'R1', 'O4', 'sr001-261004-011', TIMESTAMPTZ '2026-10-04 08:50Z', 900, 900, 1, 'voided');
                SELECT pg_temp.line('00000000-0000-4000-8000-0000000000c4', 1, 'SKU5', 9, 100, 'sale');
                """.trimIndent(),
            )
        }
        event("memo.created", "00000000-0000-4000-8000-0000000000c3"); event("memo.created", "00000000-0000-4000-8000-0000000000c4")
        worker().runUntilIdle()
        val o = "SELECT %s FROM dw.agg_daily_outlet a JOIN app.outlet o ON o.id = a.outlet_id WHERE o.code = 'O4'"
        // O4 already has 5 sticks of SKU2 (memo a4) from the seed.
        assertEquals(12, long(o.format("cigarette_sold_sticks"))); assertEquals(25, long(o.format("bidi_sold_sticks")))
        assertEquals(50, long(o.format("lighter_sold_pieces"))); assertEquals(3, long(o.format("match_sold_dozens")))
        // The stable view exposes the four columns and no mixed one.
        assertEquals(0, long("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'dw' AND table_name = 'v_daily_outlet' AND column_name = 'sold_qty_base'"))
        assertEquals(50, long("SELECT lighter_sold_pieces FROM dw.v_daily_outlet WHERE outlet_id = (SELECT id FROM app.outlet WHERE code = 'O4')"))
        // O3 only has a voided memo: all four stay 0.
        assertEquals(0, long("SELECT cigarette_sold_sticks + bidi_sold_sticks + lighter_sold_pieces + match_sold_dozens FROM dw.agg_daily_outlet WHERE outlet_id = (SELECT id FROM app.outlet WHERE code = 'O3')"))
        // A forced rebuild recomputes the row and gives the same numbers (no accumulation).
        val w = worker(); w.requestRebuild(day); w.runUntilIdle()
        assertEquals(12, long(o.format("cigarette_sold_sticks"))); assertEquals(25, long(o.format("bidi_sold_sticks")))
        assertEquals(50, long(o.format("lighter_sold_pieces"))); assertEquals(3, long(o.format("match_sold_dozens")))
    }
}
