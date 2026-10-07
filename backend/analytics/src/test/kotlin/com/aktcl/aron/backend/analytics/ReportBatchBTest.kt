package com.aktcl.aron.backend.analytics

import com.aktcl.aron.contract.Role
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** N-051 (batch B) and F-API-013 / F-API-017b: every handler returns the seeded day's rows equal to hand-computed control totals, scoped to the caller. */
class ReportBatchBTest : ReportFixture() {
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
        assertEquals("true", s.rows().by("zone_name", "Zone2").str("zone_final"))
        assertEquals(setOf("R3"), run(14, Role.TSO, "final-submit-log").result().rows().map { it.str("route_code")!! }.toSet())
        assertEquals(1, run(14, Role.TSO, "final-submit-status").result().rows().size)
    }

    @Test
    fun gigoAndAmoCall() = app {
        val g = run(10, Role.ANALYST, "gigo").result().rows().single()
        assertEquals("sr001", g.str("username")); assertEquals(8.0, g.num("hours")); assertEquals("ok", g.str("check_in_fix")); assertEquals("false", g.str("any_mock")); assertEquals("Banani", g.str("address"))
        assertEquals(0, run(14, Role.TSO, "gigo").result().rows().size)
        val a = run(10, Role.ANALYST, "amo-call").result().rows().single()
        assertEquals("amo001", a.str("amo")); assertEquals("O1", a.str("outlet_code")); assertEquals("amo_control_call", a.str("visit_kind"))
        assertEquals(0, run(14, Role.TSO, "amo-call").result().rows().size)
        assertEquals(1, run(11, Role.TSO, "amo-call").result().rows().size)
    }

    @Test
    fun dssDsRrsAndTopSheet() = app {
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
        // The AMO's app reads std-memo and sales-summary for its zone (F-API-017b); the other batch B reports are not for AMO.
        assertEquals(HttpStatusCode.OK, run(12, Role.AMO, "sales-summary").status)
        assertEquals(3, run(12, Role.AMO, "sales-summary").result().rows().size)           // zone 1: R1 two brands, R2 one
        assertEquals(HttpStatusCode.Forbidden, run(12, Role.AMO, "dss").status)
    }

    @Test
    fun everyBatchBHandlerServesXlsxAndRefusesAForeignZone() = app {
        for (key in listOf("data-entry-log", "final-submit-log", "final-submit-status", "gigo", "dss", "ds-rrs", "tso-top-sheet", "daily-tracking", "leaderboard", "amo-call", "sr-outlets", "discount", "free-sample", "sales-summary")) {
            assertEquals(HttpStatusCode.OK, run(10, Role.ANALYST, key, body(format = "xlsx")).status, key)
            assertEquals(HttpStatusCode.Forbidden, run(11, Role.TSO, key, body(""","geo":{"zone":[$z2]}""")).status, key)
        }
        assertTrue(ReportHandlers.all.map { it.definition.report_key }.toSet().size == ReportHandlers.all.size)
    }
}
