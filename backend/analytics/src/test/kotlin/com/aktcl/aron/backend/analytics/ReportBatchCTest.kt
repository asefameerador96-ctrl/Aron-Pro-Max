package com.aktcl.aron.backend.analytics

import com.aktcl.aron.contract.Role
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals

/** N-052 (batch C without the deferred programme reports): QC reports equal the hand-computed totals, scoped, in json and xlsx. */
class ReportBatchCTest : ReportFixture() {
    override val extraSql = """
        INSERT INTO app.qc_entry (visit_client_uuid, business_date, user_id, route_id, outlet_id)
          SELECT '00000000-0000-4000-8000-000000000001', DATE '2026-10-04', u.id, r.id, o.id FROM app.app_user u, app.route r, app.outlet o WHERE u.username = 'sr001' AND r.code = 'R1' AND o.code = 'O1';
        INSERT INTO app.qc_entry_line (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, qc_entry_id, visit_client_uuid, memo_client_uuid, applied_to_memo, sku_id, fault_type_code, fault_group, qty_base, unit_price_mtk, settlement_mtk)
          SELECT gen_random_uuid(), gen_random_uuid(), DATE '2026-10-04', u.id, r.id, TIMESTAMPTZ '2026-10-04 04:20Z', 1, (SELECT id FROM app.qc_entry), '00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-0000000000a1', true, s.id, l.fault, l.grp, l.q, 800, l.q * 800
            FROM app.app_user u, app.route r, app.sku s, (VALUES ('SKU1', 'broken', 'MFC', 3), ('SKU1', 'stale', 'MKT', 2), ('SKU2', 'broken', 'MFC', 1)) l(sku, fault, grp, q) WHERE u.username = 'sr001' AND r.code = 'R1' AND s.code = l.sku;
    """.trimIndent()

    @Test
    fun qcReportsMatchTheHandComputedTotals() = app {
        val q = run(10, Role.ANALYST, "qc-report").result()
        assertEquals(3, q.rows().size); assertEquals(6, q.total("qty_base")); assertEquals(4_800, q.total("settlement_mtk"))
        assertEquals("O1", q.rows().first().str("outlet_code")); assertEquals("sr001", q.rows().first().str("username"))
        val s = run(10, Role.ANALYST, "settlement").result()
        assertEquals(3, s.rows().size)
        val skuBroken = s.rows().single { it.str("sku_code") == "SKU1" && it.str("fault_type_code") == "broken" }
        assertEquals(3, skuBroken.num("qty_base").toInt()); assertEquals(2_400, skuBroken.num("settlement_mtk").toLong())
        assertEquals(4_800, s.total("settlement_mtk"))
        val r = run(10, Role.ANALYST, "route-qc").result()
        assertEquals(setOf("MFC" to 4, "MKT" to 2), r.rows().map { it.str("fault_group")!! to it.num("qty_base").toInt() }.toSet())
        assertEquals(3_200, r.rows().by("fault_group", "MFC").num("settlement_mtk").toLong())
        // Scope: zone 2's TSO sees none of zone 1's QC; zone 1's TSO sees all of it; a SKU selector narrows.
        assertEquals(0, run(14, Role.TSO, "qc-report").result().rows().size)
        assertEquals(3, run(11, Role.TSO, "qc-report").result().rows().size)
        val sku2 = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery("SELECT id FROM app.sku WHERE code = 'SKU2'").mapTo(Long::class.java).one() }
        assertEquals(1, run(10, Role.ANALYST, "qc-report", body(""","product_type":"sku","products":[$sku2]""")).result().rows().size)
        for (key in listOf("qc-report", "settlement", "route-qc")) {
            assertEquals(HttpStatusCode.OK, run(10, Role.ANALYST, key, body(format = "xlsx")).status, key)
            assertEquals(HttpStatusCode.Forbidden, run(11, Role.TSO, key, body(""","geo":{"zone":[$z2]}""")).status, key)
        }
    }
}
