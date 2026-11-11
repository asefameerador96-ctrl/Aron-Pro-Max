package com.aktcl.aron.backend.analytics

import com.aktcl.aron.contract.Role
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** N-051 (batch B) and F-API-013 / F-API-017b: every handler returns the seeded day's rows equal to hand-computed control totals, scoped to the caller. */
class ReportBatchBTest : ReportFixture() {
    override val successfulLogin = false   // the gigo tests script the attendance events; the tests that count logins call grantLogin()
    override val extraSql = """
INSERT INTO app.app_user (username, full_name, role, must_change_password) VALUES ('amo001', 'AMO One', 'AMO', false);
INSERT INTO app.attendance_event (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, kind, fix_status, fix_lat, fix_lng, fix_accuracy_m, fix_is_mock, address_display)
  SELECT gen_random_uuid(), gen_random_uuid(), DATE '2026-10-04', u.id, r.id, e.at, 1, e.kind, 'ok', 23.78, 90.41, 8, false, 'Banani'
    FROM app.app_user u, app.route r, (VALUES ('check_in', TIMESTAMPTZ '2026-10-04 03:00Z'), ('check_out', TIMESTAMPTZ '2026-10-04 11:00Z')) e(kind, at) WHERE u.username = 'sr001' AND r.code = 'R1';
INSERT INTO app.device (device_uuid, flavour, app_package, status, device_owner, lockdown_level, public_key_jwk, public_key_thumbprint, app_signing_cert_sha256, app_version)
  VALUES (gen_random_uuid(), 'sr', 'com.aktcl.aron.sr', 'active', true, 'dev', '{}'::jsonb, 'thumb-x', decode(repeat('ab', 32), 'hex'), '1.0.0+1');
INSERT INTO app.sync_batch (device_id, batch_uuid, user_id, fingerprint, record_count, received_at, expires_at)
  SELECT (SELECT id FROM app.device LIMIT 1), gen_random_uuid(), u.id, decode(repeat('cd', 32), 'hex'), b.n, b.at, now() + interval '1 day'
    FROM app.app_user u, (VALUES ('sr001', 10, TIMESTAMPTZ '2026-10-04 05:00Z'), ('sr001', 5, TIMESTAMPTZ '2026-10-04 06:00Z'), ('sr002', 3, TIMESTAMPTZ '2026-10-04 05:30Z')) b(un, n, at) WHERE u.username = b.un;
SELECT pg_temp.line('00000000-0000-4000-8000-0000000000a1', 2, 'SKU2', 2, 0, 'free_sample');
INSERT INTO app.memo_discount (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, memo_client_uuid, kind, sku_id, qty_base, value_mtk, line_no)
  SELECT gen_random_uuid(), gen_random_uuid(), DATE '2026-10-04', m.user_id, m.route_id, m.captured_at, 1, m.client_uuid, 'drp', s.id, 1, 500, 1 FROM app.memo m, app.sku s WHERE m.client_uuid = '00000000-0000-4000-8000-0000000000a1' AND s.code = 'SKU1';
INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from) SELECT r.id, u.id, 'primary', DATE '2026-01-01' FROM app.route r, app.app_user u WHERE r.code = 'R1' AND u.username = 'sr001';
INSERT INTO app.due_ledger (outlet_id, business_date, entry_kind, amount_mtk, memo_client_uuid, memo_no, user_id, source_client_uuid)
  SELECT o.id, DATE '2026-10-04', k.kind, k.amt, '00000000-0000-4000-8000-0000000000a2', 'sr001-261004-002', u.id, gen_random_uuid() FROM app.outlet o, app.app_user u, (VALUES ('memo_due', 8000), ('collection', -3000)) k(kind, amt) WHERE o.code = 'O2' AND u.username = 'sr001';
SELECT pg_temp.visit('00000000-0000-4000-8000-0000000000c9', 'amo001', 'R1', 'O1', 1, TIMESTAMPTZ '2026-10-04 09:00Z', 'in_range', 'sale_allowed', false, 'closed');
ALTER TABLE app.visit DISABLE TRIGGER USER;
UPDATE app.visit SET visit_kind = 'amo_control_call', planned = false WHERE client_uuid = '00000000-0000-4000-8000-0000000000c9';
ALTER TABLE app.visit ENABLE TRIGGER USER;
    """.trimIndent()

    @Test
    fun dataEntryLogAndFinalSubmitReports() = app {
        grantLogin()
        val d = run(10, Role.ANALYST, "data-entry-log").result()
        assertEquals(3, d.rows().size)
        assertEquals(2, d.rows().by("username", "sr001").num("uploads").toInt()); assertEquals(15, d.rows().by("username", "sr001").num("records").toInt())
        assertEquals(1, d.rows().by("username", "sr002").num("uploads").toInt()); assertEquals(0, d.rows().by("username", "sr003").num("uploads").toInt())
        assertEquals(18, d.total("records"))
        val f = run(10, Role.ANALYST, "final-submit-log").result()
        assertEquals(1, f.rows().size); assertEquals("R3", f.rows().single().str("route_code")); assertEquals("Zone2", f.rows().single().str("zone_name")); assertEquals(1, f.rows().single().num("submit_cycle").toInt())
        val s = run(10, Role.ANALYST, "final-submit-status").result()
        val z1 = s.rows().by("zone_name", "Zone1")
        assertEquals(2, z1.num("target_routes").toInt()); assertEquals(1, z1.num("in_progress").toInt()); assertEquals(1, z1.num("sales_submitted").toInt()); assertEquals("false", z1.str("zone_final"))
        // One Final Submit formula (R-W-03, D-TSO-MAN-53): R3 is final_submitted by route state, but with no live app.final_submit row its zone is NOT final.
        assertEquals("false", s.rows().by("zone_name", "Zone2").str("zone_final"))
        fresh.dataSource.connection.use { c ->
            c.createStatement().use {
                it.execute(
                    "INSERT INTO app.final_submit (client_uuid, zone_id, business_date, submitted_by, submitted_at, via) " +
                        "SELECT '00000000-0000-4000-8000-0000000c2f02', $z2, DATE '2026-10-04', u.id, TIMESTAMPTZ '2026-10-04 11:00Z', 'web' FROM app.app_user u WHERE u.username = 'sr001'",
                )
            }
        }
        AggregationWorker(fresh.db).also { w -> w.requestRebuild(day); w.runUntilIdle() }
        val live = run(10, Role.ANALYST, "final-submit-status").result()
        assertEquals("true", live.rows().by("zone_name", "Zone2").str("zone_final"))
        assertEquals("false", live.rows().by("zone_name", "Zone1").str("zone_final"))
        assertEquals(setOf("R3"), run(14, Role.TSO, "final-submit-log").result().rows().map { it.str("route_code")!! }.toSet())
        assertEquals(1, run(14, Role.TSO, "final-submit-status").result().rows().size)
    }

    @Test
    fun gigoAndAmoCall() = app {
        val g = run(10, Role.ANALYST, "gigo").result().rows().single()
        assertEquals("sr001", g.str("username")); assertEquals(8.0, g.num("hours")); assertEquals("ok", g.str("check_in_fix")); assertEquals("false", g.str("any_mock")); assertEquals(null, g.str("address"))   // the SR's location is masked without the pii claim
        assertEquals("Banani", run(10, Role.ANALYST, "gigo", pii = true).result().rows().single().str("address"))
        assertEquals(0, run(14, Role.TSO, "gigo").result().rows().size)
        val a = run(10, Role.ANALYST, "amo-call").result().rows().single()
        assertEquals("amo001", a.str("amo")); assertEquals("O1", a.str("outlet_code")); assertEquals("amo_control_call", a.str("visit_kind"))
        assertEquals(0, run(14, Role.TSO, "amo-call").result().rows().size)
        assertEquals(1, run(11, Role.TSO, "amo-call").result().rows().size)
    }

    @Test
    fun dssDsRrsAndTopSheet() = app {
        grantLogin()
        val d = run(10, Role.ANALYST, "dss").result()
        val z1 = d.rows().by("zone_name", "Zone1")
        assertEquals(2, z1.num("target_routes").toInt()); assertEquals(2, z1.num("logged_in_routes").toInt()); assertEquals(1, z1.num("sales_submitted_routes").toInt())
        assertEquals(4, z1.num("visits").toInt()); assertEquals(3, z1.num("successful_calls").toInt()); assertEquals(3, z1.num("memos").toInt())
        assertEquals(39_000, z1.num("net_mtk").toLong()); assertEquals(3_000, z1.num("dues_collected_mtk").toLong())
        assertEquals(63_000, d.total("net_mtk")); assertEquals(4, d.total("memos"))
        val r = run(10, Role.ANALYST, "ds-rrs").result()
        val z1s1 = r.rows().single { it.str("zone_name") == "Zone1" && it.str("sku_code") == "SKU1" }
        assertEquals(100, z1s1.num("issued_qty_base").toInt()); assertEquals(30, z1s1.num("sold_qty_base").toInt()); assertEquals(10, z1s1.num("returned_qty_base").toInt()); assertEquals(60, z1s1.num("closing_qty_base").toInt())
        val z1s2 = r.rows().single { it.str("zone_name") == "Zone1" && it.str("sku_code") == "SKU2" }
        assertEquals(15, z1s2.num("sold_qty_base").toInt()); assertEquals(2, z1s2.num("free_qty_base").toInt()); assertEquals(-17, z1s2.num("closing_qty_base").toInt())
        val t = run(10, Role.ANALYST, "tso-top-sheet").result()
        assertEquals(3, t.rows().size)
        val r1 = t.rows().by("route_code", "R1")
        assertEquals(66.67, r1.num("strike_rate_pct")); assertEquals(33.33, r1.num("geo_valid_pct")); assertEquals(34_000, r1.num("net_mtk").toLong()); assertEquals(100.0, t.rows().by("route_code", "R2").num("geo_valid_pct"))
        assertEquals(setOf("R3"), run(14, Role.TSO, "tso-top-sheet").result().rows().map { it.str("route_code")!! }.toSet())
    }

    @Test
    fun dailyTrackingReportAndLeaderboard() = app {
        grantLogin()
        val d = run(10, Role.ANALYST, "daily-tracking").result()
        assertEquals("below_80", d.rows().by("route_code", "R1").str("bucket")); assertEquals("ge_100", d.rows().by("route_code", "R2").str("bucket")); assertEquals("ge_100", d.rows().by("route_code", "R3").str("bucket"))
        val l = run(10, Role.ANALYST, "leaderboard").result().rows()
        assertEquals(listOf("sr001" to 1, "sr003" to 2, "sr002" to 3), l.map { it.str("name")!! to it.num("rank").toInt() })
        assertEquals(listOf("Zone1" to 1, "Zone2" to 2), run(10, Role.ANALYST, "leaderboard", body(""","location":"zone"""")).result().rows().map { it.str("name")!! to it.num("rank").toInt() })
        assertEquals(listOf("R1", "R3", "R2"), run(10, Role.ANALYST, "leaderboard", body(""","location":"route"""")).result().rows().map { it.str("name")!! })
        assertEquals(listOf("sr001", "sr002"), run(11, Role.TSO, "leaderboard").result().rows().map { it.str("name")!! })    // zone 1's ranking holds only its own SRs
    }

    @Test
    fun srOutletsDiscountFreeSampleAndSalesSummary() = app {
        val o = run(10, Role.ANALYST, "sr-outlets").result()
        assertEquals(5, o.rows().size)
        val o2 = o.rows().by("outlet_code", "O2")
        assertEquals("R1", o2.str("route_code")); assertEquals("sr001", o2.str("sr")); assertEquals("2026-10-04", o2.str("last_visit_date")); assertEquals(5_000, o2.num("outstanding_mtk").toLong())   // 8 000 due less 3 000 collected
        assertEquals(setOf("O5"), run(14, Role.TSO, "sr-outlets").result().rows().map { it.str("outlet_code")!! }.toSet())
        val dsc = run(10, Role.ANALYST, "discount").result()
        assertEquals(1, dsc.rows().size); assertEquals("drp", dsc.rows().single().str("kind")); assertEquals(500, dsc.total("value_mtk"))
        assertEquals(0, run(14, Role.TSO, "discount").result().rows().size)
        val fs = run(10, Role.ANALYST, "free-sample").result().rows().single()
        assertEquals("R1", fs.str("route_code")); assertEquals("SKU2", fs.str("sku_code")); assertEquals(2, fs.num("qty_base").toInt()); assertEquals(1, fs.num("memos").toInt())
        val ss = run(10, Role.ANALYST, "sales-summary").result()
        assertEquals(4, ss.rows().size)
        val r1 = ss.rows().filter { it.str("route_code") == "R1" }
        assertEquals(2, r1.size); assertEquals(66.67, r1.first().num("cpr_pct")); assertEquals(34_000, r1.first().num("net_mtk").toLong())
        assertEquals(100.0, r1.single { it.str("brand_name") == "BrandOne" }.num("bsr_pct")); assertEquals(50.0, r1.single { it.str("brand_name") == "BrandTwo" }.num("bsr_pct"))
        val b1 = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery("SELECT id FROM app.product_node WHERE name = 'BrandOne'").mapTo(Long::class.java).one() }
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("SET session_replication_role = replica"); h.execute("DELETE FROM app.product_node WHERE id = $b1"); h.execute("SET session_replication_role = DEFAULT") }
        assertEquals(1, run(10, Role.ANALYST, "sales-summary").result().rows().count { it.str("brand_name") == "n$b1" && it.str("route_code") == "R1" }, "a brand without a node row shows n<id>, as LEFT-01")
        // The AMO's app reads std-memo and sales-summary for its zone (F-API-017b); the other batch B reports are not for AMO.
        assertEquals(HttpStatusCode.OK, run(12, Role.AMO, "sales-summary").status)
        assertEquals(3, run(12, Role.AMO, "sales-summary").result().rows().size)           // zone 1: R1 two brands, R2 one
        assertEquals(HttpStatusCode.Forbidden, run(12, Role.AMO, "dss").status)
    }

    /**
     * Contract 1.11.2 (D-AMO-FND-16, D-17): memos per category on the route over the report window, each memo once per category, only over the route-days the
     * report itself includes (same dates, zone and route scope per day). Explicit expected numbers (hand-computed below); an independent SQL count over the
     * app tables and `app.route_zone_on` is kept only as a cross-check.
     *
     * Fixture (sr001 sells on R1, SKU1/SKU2 cigarette, SKU3 bidi, SKU4 lighter, SKU5 match; "sale" lines unless stated). R1 is in Z1 until 2026-10-06 and moves to Z2 on 2026-10-07.
     *   10-03 z0 (R1, Z1): cig + bidi + match + lighter                     -> before every window used below
     *   10-04 a1 (seed): SKU1 sale, SKU2 free_sample, + SKU2 sale (2nd cigarette line), SKU3 bidi, SKU4 lighter -> cig, bidi, lighter, ONCE each
     *   10-04 a2 (seed): SKU1 + SKU2 sale (two cigarette lines) + SKU3 bidi FREE_SAMPLE only -> cig once; bidi NOT counted (not a sale line)
     *   10-04 a3 (seed): voided memo, SKU2 sale -> counted nowhere; a6 (seed): active, no lines -> counted nowhere
     *   10-04 a7 (R1): SKU5 match sale -> match
     *   10-05 b1 (R1): SKU1 + SKU2 sale -> cig;   10-06 b2 (R1): SKU3 + SKU4 sale -> bidi, lighter
     *   10-07 b3 (R1, Z2): SKU1 + SKU5 + SKU3 -> cig, match, bidi;  10-08 b4 (R1, Z2): SKU1 -> cig
     *   10-09 f1 (R1, Z2): SKU3 free_sample only -> an active memo with no sale line: a route-day the report includes, all four columns 0
     *   10-20 b5 (R1, Z2): SKU4 sale -> lighter
     *   R2 (Z1): a4 SKU2 sale (cig 1);  R3 (Z2): a5 SKU1 sale (cig 1)
     * R1 window 10-04..10-06 (all Z1 days): cig {a1,a2,b1}=3, bidi {a1,b2}=2, match {a7}=1, lighter {a1,b2}=2; memos (active, line_count>0) {a1,a2,a7,b1,b2}=5.
     * R1 window 10-04..10-31 national: cig {a1,a2,b1,b3,b4}=5, bidi {a1,b2,b3}=3, match {a7,b3}=2, lighter {a1,b2,b5}=3; memos 5+{b3,b4,f1,b5}=9.
     * Zone-scoped to Z1 over 10-04..10-31: R1 only has its Z1 days (10-04..10-06): the same 3/2/1/2 and 5 memos. Z2 TSO over the same window: R1 days 10-07+: cig {b3,b4}=2,
     * bidi {b3}=1, match {b3}=1, lighter {b5}=1, memos {b3,b4,f1,b5}=4. Without the route-day restriction of CTE `cm`, Z1 would show 5/3/2/3 (all dates of the route).
     */
    @Test
    fun salesSummaryCarriesTheRoutesMemosPerCategoryOverTheWindow() = app {
        val v = "00000000-0000-4000-8000-000000000001"
        fun memo(id: String, route: String, outlet: String, no: Int, date: String, lines: List<Triple<String, String, Int>>) = listOf(
            """INSERT INTO app.memo (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, visit_client_uuid, outlet_id, memo_no,
                 memo_kind, committed_at, price_list_date, price_type, gross_mtk, offer_discount_mtk, drp_discount_mtk, qc_deduction_mtk, round_adj_mtk,
                 net_mtk, paid_mtk, due_mtk, is_credit, line_count, discount_line_count, qc_line_count, status, voided_by_client_uuid, received_at)
               SELECT '$id', '$id', DATE '$date', u.id, r.id, TIMESTAMPTZ '$date 05:00Z', 1, '$v', o.id, 'sr001-${date.substring(2).replace("-", "")}-${900 + no}', 'sale', TIMESTAMPTZ '$date 05:00Z', DATE '2026-10-01', 'outlet',
                      ${lines.sumOf { it.third * 800 }}, 0, 0, 0, 0, ${lines.sumOf { it.third * 800 }}, ${lines.sumOf { it.third * 800 }}, 0, false, ${lines.size}, 0, 0, 'active', NULL, TIMESTAMPTZ '$date 06:00Z'
                 FROM app.app_user u, app.route r, app.outlet o WHERE u.username = 'sr001' AND r.code = '$route' AND o.code = '$outlet'""",
        ) + lines.mapIndexed { i, l -> line(id, date, 1 + i, l.first, l.second, l.third) }
        sql(
            // new SKUs: bidi, lighter, match (the fixture has only two cigarette SKUs)
            """INSERT INTO app.sku (code, variant_id, category_code, name, short_name, base_unit, base_per_pack, entry_unit_default)
               SELECT t.c, v.id, t.cat, t.c, t.c, t.u, 10, t.u FROM app.product_node v, (VALUES ('SKU3', 'bidi', 'stick'), ('SKU4', 'lighter', 'piece'), ('SKU5', 'match', 'dozen')) t(c, cat, u) WHERE v.code = 'V1'""",
        )
        fresh.db.jdbi.useHandle<Exception> { Aggregator.refreshDimensions(it) }
        val a1 = "00000000-0000-4000-8000-0000000000a1"; val a2 = "00000000-0000-4000-8000-0000000000a2"
        sql(
            line(a1, "2026-10-04", 3, "SKU2", "sale", 5), line(a1, "2026-10-04", 4, "SKU3", "sale", 5), line(a1, "2026-10-04", 5, "SKU4", "sale", 1),
            line(a2, "2026-10-04", 3, "SKU3", "free_sample", 2),
            *memo("00000000-0000-4000-8000-0000000000f0", "R1", "O1", 0, "2026-10-03", listOf(Triple("SKU1", "sale", 1), Triple("SKU3", "sale", 1), Triple("SKU5", "sale", 1), Triple("SKU4", "sale", 1))).toTypedArray(),
            *memo("00000000-0000-4000-8000-0000000000a7", "R1", "O1", 1, "2026-10-04", listOf(Triple("SKU5", "sale", 1))).toTypedArray(),
            *memo("00000000-0000-4000-8000-0000000000b1", "R1", "O1", 2, "2026-10-05", listOf(Triple("SKU1", "sale", 3), Triple("SKU2", "sale", 2))).toTypedArray(),
            *memo("00000000-0000-4000-8000-0000000000b2", "R1", "O1", 3, "2026-10-06", listOf(Triple("SKU3", "sale", 4), Triple("SKU4", "sale", 1))).toTypedArray(),
            *memo("00000000-0000-4000-8000-0000000000b3", "R1", "O1", 4, "2026-10-07", listOf(Triple("SKU1", "sale", 1), Triple("SKU5", "sale", 1), Triple("SKU3", "sale", 1))).toTypedArray(),
            *memo("00000000-0000-4000-8000-0000000000b4", "R1", "O1", 5, "2026-10-08", listOf(Triple("SKU1", "sale", 2))).toTypedArray(),
            *memo("00000000-0000-4000-8000-0000000000f1", "R1", "O1", 6, "2026-10-09", listOf(Triple("SKU3", "free_sample", 1))).toTypedArray(),
            *memo("00000000-0000-4000-8000-0000000000b5", "R1", "O1", 7, "2026-10-20", listOf(Triple("SKU4", "sale", 1))).toTypedArray(),
        )
        // R1 moves from Z1 to Z2 on 2026-10-07 (route_zone_history; the dw rows stamp the zone of each day). Triggers off only for this edit of master data.
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("SET session_replication_role = replica")
            h.execute("UPDATE app.route SET zone_id = $z2 WHERE code = 'R1'")
            h.execute("UPDATE app.route_zone_history SET valid_to = DATE '2026-10-07' WHERE valid_to IS NULL AND route_id = (SELECT id FROM app.route WHERE code = 'R1')")
            h.execute("INSERT INTO app.route_zone_history (route_id, zone_id, valid_from) SELECT id, $z2, DATE '2026-10-07' FROM app.route WHERE code = 'R1'")
            h.execute("SET session_replication_role = DEFAULT")
        }
        AggregationWorker(fresh.db).also { w ->
            for (d in listOf("2026-10-03", "2026-10-04", "2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08", "2026-10-09", "2026-10-20")) w.requestRebuild(java.time.LocalDate.parse(d))
            w.runUntilIdle()
        }
        fun win(from: String, to: String) = """{"period":{"from":"$from","to":"$to"},"output":{"format":"json","page_size":500}}"""
        val cats = listOf("cigarette", "bidi", "match", "lighter")
        /** [cig, bidi, match, lighter, memos] repeated on every row of the route; fails when the rows disagree. */
        fun counts(rows: List<JsonObject>, route: String): List<Int> {
            val mine = rows.filter { it.str("route_code") == route }
            assertTrue(mine.isNotEmpty(), "$route has rows")
            val all = mine.map { r -> cats.map { r.num("memos_$it").toInt() } + r.num("memos").toInt() }.distinct()
            assertEquals(1, all.size, "$route: every row of the route repeats the same counts: $all")
            return all.single()
        }
        /** Independent cross-check over the app tables: distinct active memos with a live sale line of the category, on the days the route was in the zone. */
        fun oracle(from: String, to: String, zone: Long?, route: String): List<Int> = fresh.db.jdbi.withHandle<List<Int>, Exception> { h ->
            val m = h.createQuery(
                """SELECT s.category_code, count(DISTINCT m.client_uuid)::int FROM app.memo m JOIN app.memo_line l ON l.memo_client_uuid = m.client_uuid AND l.business_date = m.business_date
                     JOIN app.sku s ON s.id = l.sku_id JOIN app.route r ON r.id = m.route_id
                    WHERE r.code = :rc AND m.business_date BETWEEN :f AND :t AND l.line_kind = 'sale' AND l.voided_at IS NULL AND m.status = 'active' AND m.voided_at IS NULL
                      AND (CAST(:z AS bigint) IS NULL OR coalesce(app.route_zone_on(m.route_id, m.business_date), r.zone_id) = :z) GROUP BY 1""",
            ).bind("rc", route).bind("f", java.time.LocalDate.parse(from)).bind("t", java.time.LocalDate.parse(to)).bind("z", zone)
                .map { rs, _ -> rs.getString(1) to rs.getInt(2) }.list().toMap()
            cats.map { m[it] ?: 0 }
        }

        // (a) + (b) national, window 10-04..10-06: a1 is cig + bidi + lighter once each (two cigarette lines, one memo); a2's bidi is a free sample; z0 (10-03), b3..b5 (10-07+) are outside
        val w1 = run(10, Role.ANALYST, "sales-summary", win("2026-10-04", "2026-10-06")).result().rows()
        assertEquals(listOf(3, 2, 1, 2, 5), counts(w1, "R1"))                        // cig {a1,a2,b1}, bidi {a1,b2}, match {a7}, lighter {a1,b2}, memos 5
        assertEquals(listOf(3, 2, 1, 2), oracle("2026-10-04", "2026-10-06", null, "R1"))
        assertEquals(listOf(1, 0, 0, 0, 1), counts(w1, "R2")); assertEquals(listOf(1, 0, 0, 0, 1), counts(w1, "R3"))   // R2 a4, R3 a5: one cigarette memo each
        // (a) the whole month, national: every memo once per category, the later-date memos now inside
        val w2 = run(10, Role.ANALYST, "sales-summary", win("2026-10-04", "2026-10-31")).result().rows()
        assertEquals(listOf(5, 3, 2, 3, 9), counts(w2, "R1"))                        // cig {a1,a2,b1,b3,b4}, bidi {a1,b2,b3}, match {a7,b3}, lighter {a1,b2,b5}, memos 9 (f1 has no sale line)
        assertEquals(listOf(5, 3, 2, 3), oracle("2026-10-04", "2026-10-31", null, "R1"))
        // (d) D-17: Z1's TSO over the whole month sees R1 only for its Z1 days (10-04..10-06): 3/2/1/2 and 5 memos, consistent with `memos` of the same row; the Z2 days are not counted
        val z1Rows = run(11, Role.TSO, "sales-summary", win("2026-10-04", "2026-10-31")).result().rows()
        assertEquals(listOf(3, 2, 1, 2, 5), counts(z1Rows, "R1"))
        assertEquals(listOf(3, 2, 1, 2), oracle("2026-10-04", "2026-10-31", z1, "R1"))
        assertTrue(z1Rows.none { it.str("route_code") == "R3" })
        // ... and Z2's TSO sees the other days of R1: cig {b3,b4}, bidi {b3}, match {b3}, lighter {b5}, memos {b3,b4,f1,b5} = 4
        val z2Rows = run(14, Role.TSO, "sales-summary", win("2026-10-04", "2026-10-31")).result().rows()
        assertEquals(listOf(2, 1, 1, 1, 4), counts(z2Rows, "R1"))
        assertEquals(listOf(2, 1, 1, 1), oracle("2026-10-04", "2026-10-31", z2, "R1"))
        assertEquals(listOf(1, 0, 0, 0, 1), counts(z2Rows, "R3"))
        // (c) a day whose only memo has no sale line (a free sample): the route-day is included (memos = 1) and all four columns are 0
        val noSale = run(10, Role.ANALYST, "sales-summary", win("2026-10-09", "2026-10-09")).result().rows()
        assertEquals(listOf(0, 0, 0, 0, 1), counts(noSale, "R1"))
        // (c) a window with no route-day at all (November): the report is driven by the route-day aggregate, so there is no row
        assertEquals(0, run(10, Role.ANALYST, "sales-summary", win("2026-11-01", "2026-11-30")).result().rows().size)
    }

    private fun line(memo: String, date: String, no: Int, sku: String, kind: String, qty: Int) =
        """INSERT INTO app.memo_line (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, memo_client_uuid, line_no, sku_id,
             line_kind, qty_entered, unit_entered, pack_factor, qty_base, price_type, price_valid_from, base_price_mtk, price_per_qty, gross_mtk)
           SELECT gen_random_uuid(), '$memo', DATE '$date', m.user_id, m.route_id, m.captured_at, 1, '$memo', $no, s.id, '$kind', $qty, s.base_unit, 1, $qty, 'outlet',
                  DATE '2026-10-01', 800, 1, ${qty * 800} FROM app.memo m, app.sku s WHERE m.client_uuid = '$memo' AND m.business_date = DATE '$date' AND s.code = '$sku'"""

    /** Contract 1.11.0 (D-AMO-FND-09): target columns per route x brand and per route; null when the route has no target; value = quantity x outlet price, half up. */
    @Test
    fun salesSummaryTargetColumnsAreNullWithoutATargetAndPriceTheTargetQuantity() = app {
        val before = run(10, Role.ANALYST, "sales-summary").result().rows()
        assertTrue(before.all { it.str("target_qty_base") == null && it.str("route_target_qty_base") == null && it.str("target_value_mtk") == null }, "no target row: every target column is null")
        sql(
            "INSERT INTO app.route_target_batch (batch_uuid, content_sha256, row_count, committed_by) SELECT '00000000-0000-4000-8000-0000000000b1', decode(repeat('ab', 32), 'hex'), 3, id FROM app.app_user WHERE username = 'sr001'",
            // R1: SKU1 (BrandOne) 1000 sticks priced 800 mtk each, SKU2 (BrandTwo) 500 unpriced; a target in another month and one on R3 (zone 2) must not leak in
            """INSERT INTO app.route_monthly_target (route_id, sku_id, month, qty_base, batch_uuid)
               SELECT r.id, s.id, t.m, t.q, '00000000-0000-4000-8000-0000000000b1'
                 FROM (VALUES ('R1', 'SKU1', DATE '2026-10-01', 1000), ('R1', 'SKU2', DATE '2026-10-01', 500), ('R1', 'SKU1', DATE '2026-09-01', 7), ('R3', 'SKU1', DATE '2026-10-01', 40)) t(rc, sc, m, q)
                 JOIN app.route r ON r.code = t.rc JOIN app.sku s ON s.code = t.sc""",
            "INSERT INTO app.sku_price (sku_id, price_type, amount_mtk, per_base_qty, valid_from) SELECT id, 'outlet', 800, 1, DATE '2026-01-01' FROM app.sku WHERE code = 'SKU1'",
        )
        val r1 = run(10, Role.ANALYST, "sales-summary").result().rows().filter { it.str("route_code") == "R1" }
        val one = r1.single { it.str("brand_name") == "BrandOne" }; val two = r1.single { it.str("brand_name") == "BrandTwo" }
        assertEquals(1000L to 800_000L, one.num("target_qty_base").toLong() to one.num("target_value_mtk").toLong())
        assertEquals(500L, two.num("target_qty_base").toLong()); assertEquals(null, two.str("target_value_mtk"), "an unpriced target SKU adds no value")
        assertTrue(r1.all { it.num("route_target_qty_base").toLong() == 1500L && it.num("route_target_value_mtk").toLong() == 800_000L }, "the route's whole target repeats on each row")
        val r2 = run(10, Role.ANALYST, "sales-summary").result().rows().filter { it.str("route_code") == "R2" }
        assertTrue(r2.isNotEmpty() && r2.all { it.str("target_qty_base") == null && it.str("route_target_qty_base") == null }, "a route with no target stays null")
        // zone 2's route R3 carries its own target; an AMO of zone 1 never sees it
        assertEquals(40L, run(10, Role.ANALYST, "sales-summary").result().rows().first { it.str("route_code") == "R3" }.num("route_target_qty_base").toLong())
        assertTrue(run(12, Role.AMO, "sales-summary").result().rows().none { it.str("route_code") == "R3" })
        assertEquals(1500L, run(12, Role.AMO, "sales-summary").result().rows().first { it.str("route_code") == "R1" }.num("route_target_qty_base").toLong())
    }

    /** Contract 1.11.1 (D-AMO-MAN-18): the AMO never matches routes by name, so every per-route report it may read carries `route_id` beside `route_code`. */
    @Test
    fun everyAmoReadableRouteReportCarriesRouteIdBesideRouteCode() = app {
        val amoRouteReports = ReportHandlers.all.filter { Role.AMO in it.roles && it.definition.columns.any { c -> c.key == "route_code" } }
        assertTrue(amoRouteReports.map { it.definition.report_key }.containsAll(listOf("route-memo", "route-std", "std-memo", "sales-summary")), "the AMO's route reports: ${amoRouteReports.map { it.definition.report_key }}")
        for (h in amoRouteReports) assertTrue(h.definition.columns.any { it.key == "route_id" }, "${h.definition.report_key} has route_code but no route_id")
        val r1 = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery("SELECT id FROM app.route WHERE code = 'R1'").mapTo(Long::class.java).one() }
        for (key in listOf("sales-summary", "route-memo", "route-std", "std-memo")) {
            val rows = run(12, Role.AMO, key).result().rows().filter { it.str("route_code") == "R1" }
            assertTrue(rows.isNotEmpty() && rows.all { it.num("route_id").toLong() == r1 }, "$key: route_id of R1 is $r1: $rows")
        }
    }

    @Test
    fun everyBatchBHandlerServesXlsxAndRefusesAForeignZone() = app {
        for (key in listOf("data-entry-log", "final-submit-log", "final-submit-status", "gigo", "dss", "ds-rrs", "tso-top-sheet", "daily-tracking", "leaderboard", "amo-call", "sr-outlets", "discount", "free-sample", "sales-summary")) {
            assertEquals(HttpStatusCode.OK, run(10, Role.ANALYST, key, body(format = "xlsx")).status, key)
            assertEquals(HttpStatusCode.Forbidden, run(11, Role.TSO, key, body(""","geo":{"zone":[$z2]}""")).status, key)
        }
        assertTrue(ReportHandlers.all.map { it.definition.report_key }.toSet().size == ReportHandlers.all.size)
    }

    // ---------------- independent checker (N-051 / F-API-013) ----------------

    private fun sql(vararg stmts: String) = fresh.db.jdbi.useHandle<Exception> { h -> stmts.forEach { h.execute(it) } }
    private fun range(extra: String = "") = """{"period":{"from":"2026-10-04","to":"2026-10-05"},"output":{"format":"json","page_size":500}$extra}"""

    /** sr001 covers R3 too (acting user): the day had 18 uploaded records, but sync_batch is joined by user only, so sr001's 15 are counted on both route-days and zone 2's TSO sees zone 1's uploads. */
    @Test
    fun checker_dataEntryLogDoesNotDoubleCountACoveringUsersUploads() = app {
        sql("UPDATE app.route_day SET acting_user_id = (SELECT id FROM app.app_user WHERE username = 'sr001') WHERE route_id = (SELECT id FROM app.route WHERE code = 'R3')")
        assertEquals(18, run(10, Role.ANALYST, "data-entry-log").result().total("records"))
    }

    /** `discount` declares the category / product_type / products filters but its SQL ignores them: a SKU2-only query still returns the SKU1 DRP line. */
    @Test
    fun checker_discountHonoursItsDeclaredProductFilter() = app {
        val sku2 = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery("SELECT id FROM app.sku WHERE code = 'SKU2'").mapTo(Long::class.java).one() }
        assertEquals(0, run(10, Role.ANALYST, "discount", body(""","product_type":"sku","products":[$sku2]""")).result().rows().size)
    }

    /** Zone names are not unique (only codes are); leaderboard by zone groups by name and merges two zones into one rank. */
    @Test
    fun checker_leaderboardKeepsSameNamedZonesApart() = app {
        sql("UPDATE app.zone SET name = 'Zone1' WHERE code = 'Z2'")
        fresh.db.jdbi.useHandle<Exception> { Aggregator.refreshDimensions(it) }
        assertEquals(2, run(10, Role.ANALYST, "leaderboard", body(""","location":"zone"""")).result().rows().size)
    }

    /** Same root cause in DSS: GROUP BY zone_name sums two zones into one row. */
    @Test
    fun checker_dssKeepsSameNamedZonesApart() = app {
        sql("UPDATE app.zone SET name = 'Zone1' WHERE code = 'Z2'")
        fresh.db.jdbi.useHandle<Exception> { Aggregator.refreshDimensions(it) }
        assertEquals(2, run(10, Role.ANALYST, "dss").result().rows().size)
    }

    /** A check-out recorded without a route splits the user-day into two rows (grouped by route_code), so hours is null on both. */
    @Test
    fun checker_gigoCheckOutWithoutRouteStaysOnTheUserDayRow() = app {
        sql("""INSERT INTO app.attendance_event (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, kind, fix_status, fix_lat, fix_lng, fix_accuracy_m, fix_is_mock, address_display)
  SELECT gen_random_uuid(), gen_random_uuid(), DATE '2026-10-04', u.id, e.r, e.at, 1, e.kind, 'ok', 23.78, 90.41, 8, false, 'Gulshan'
    FROM app.app_user u, (VALUES ('check_in', TIMESTAMPTZ '2026-10-04 03:00Z', (SELECT id FROM app.route WHERE code = 'R2')), ('check_out', TIMESTAMPTZ '2026-10-04 11:00Z', NULL::bigint)) e(kind, at, r) WHERE u.username = 'sr002'""")
        val rows = run(10, Role.ANALYST, "gigo").result().rows().filter { it.str("username") == "sr002" }
        assertEquals(1, rows.size); assertEquals(8.0, rows.single().num("hours"))
    }

    /** Check-in 22:00 Dhaka, check-out 01:00 Dhaka next day (business_date is the Dhaka date of capture): two rows, no hours. */
    @Test
    fun checker_gigoHoursAcrossMidnight() = app {
        sql("""INSERT INTO app.attendance_event (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, kind, fix_status, fix_lat, fix_lng, fix_accuracy_m, fix_is_mock, address_display)
  SELECT gen_random_uuid(), gen_random_uuid(), e.d, u.id, r.id, e.at, 1, e.kind, 'ok', 23.78, 90.41, 8, false, 'Gulshan'
    FROM app.app_user u, app.route r, (VALUES ('check_in', TIMESTAMPTZ '2026-10-04 16:00Z', DATE '2026-10-04'), ('check_out', TIMESTAMPTZ '2026-10-04 19:00Z', DATE '2026-10-05')) e(kind, at, d) WHERE u.username = 'sr002' AND r.code = 'R2'""")
        val rows = run(10, Role.ANALYST, "gigo", range()).result().rows().filter { it.str("username") == "sr002" }
        assertEquals(1, rows.size); assertEquals(3.0, rows.single().num("hours"))
    }
}
