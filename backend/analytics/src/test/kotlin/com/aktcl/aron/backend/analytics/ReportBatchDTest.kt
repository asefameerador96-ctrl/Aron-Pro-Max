package com.aktcl.aron.backend.analytics

import com.aktcl.aron.contract.Role
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** web-dashboard's gaps: retailer-list, sku-list, geofence-calibration and the qc-report `source` column; each scoped by the token's reach only. */
class ReportBatchDTest : ReportFixture() {
    override val extraSql = """
        UPDATE app.outlet SET contact_number = '01711111111', owner_name = 'Owner ' || code;
        INSERT INTO app.sku_price (sku_id, price_type, amount_mtk, per_base_qty, valid_from)
          SELECT s.id, p.t, p.a, 20, DATE '2026-01-01' FROM app.sku s, (VALUES ('outlet', 8000), ('distributor', 7000)) p(t, a) WHERE s.id = (SELECT min(id) FROM app.sku);
        UPDATE app.outlet SET status = 'closed' WHERE code = 'O5';
    """.trimIndent()

    @Test
    fun retailerListIsScopedMaskedAndFiltered() = app {
        val all = run(10, Role.ANALYST, "retailer-list").result().rows()
        assertEquals(setOf("O1", "O2", "O3", "O4", "O5"), all.map { it.str("code")!! }.toSet())
        assertFalse(all.any { it.containsKey("owner_name") || it.containsKey("phone") }, "personal columns are hidden without the claim")
        val pii = run(10, Role.ANALYST, "retailer-list", pii = true).result().rows().by("code", "O1")
        assertEquals("Owner O1", pii.str("owner_name")); assertEquals("01711111111", pii.str("phone"))
        // Scope: each TSO sees only the outlets of their own zone; a foreign zone selector is 403.
        assertEquals(setOf("O1", "O2", "O3", "O4"), run(11, Role.TSO, "retailer-list").result().rows().map { it.str("code")!! }.toSet())
        assertEquals(setOf("O5"), run(14, Role.TSO, "retailer-list").result().rows().map { it.str("code")!! }.toSet())
        assertEquals(HttpStatusCode.Forbidden, run(11, Role.TSO, "retailer-list", body(""","geo":{"zone":[$z2]}""")).status)
        assertEquals(setOf("O5"), run(10, Role.ANALYST, "retailer-list", body(""","active_status":"inactive"""")).result().rows().map { it.str("code")!! }.toSet())
    }

    @Test
    fun skuListShowsThePricesValidAtTheEndOfThePeriodAndHidesTheTradePriceWithoutTheClaim() = app {
        val rows = run(10, Role.ANALYST, "sku-list").result().rows()
        val priced = rows.single { it.str("retail_price_mtk") != null }
        assertEquals(8000, priced.num("retail_price_mtk").toLong()); assertEquals(20, priced.num("per_base_qty").toInt())
        assertFalse(priced.containsKey("trade_price_mtk"))
        assertEquals(7000, run(10, Role.ANALYST, "sku-list", pii = true).result().rows().single { it.str("retail_price_mtk") != null }.num("trade_price_mtk").toLong())
        // A period before the price existed shows no price.
        assertTrue(run(10, Role.ANALYST, "sku-list", """{"period":{"date":"2025-06-01"},"output":{"format":"json","page_size":50}}""").result().rows().all { it.str("retail_price_mtk") == null })
    }

    @Test
    fun geofenceCalibrationBinsVisitDistancesPerGeoClassAndTerritory() = app {
        data class V(val territory: String, val d: Double, val force: Boolean)
        fresh.db.jdbi.useHandle<Exception> { it.execute("UPDATE dw.fact_visit SET distance_m = (abs(hashtext(visit_client_uuid::text)) % 700)::float8") }
        val visits = fresh.db.jdbi.withHandle<List<V>, Exception> { h ->
            h.createQuery("SELECT g.territory_name, v.distance_m, v.geo_action = 'force_sale' FROM dw.fact_visit v JOIN dw.dim_geo g ON g.route_id = v.route_id WHERE v.visit_kind = 'sr_call' AND NOT v.voided AND v.distance_m IS NOT NULL")
                .map { rs, _ -> V(rs.getString(1), rs.getDouble(2), rs.getBoolean(3)) }.list()
        }
        fun band(d: Double) = when { d < 25 -> "000-025"; d < 50 -> "025-050"; d < 100 -> "050-100"; d < 200 -> "100-200"; d < 500 -> "200-500"; else -> "500+" }
        val expected = visits.groupBy { band(it.d) to it.territory }.mapValues { (_, v) -> v.size to Math.round(10000.0 * v.count { it.force } / v.size) / 100.0 }
        assertTrue(expected.isNotEmpty())
        val got = run(10, Role.ANALYST, "geofence-calibration").result().rows().associate { (it.str("distance_band_m")!! to it.str("territory")!!) to (it.num("visits").toInt() to it.num("force_sale_share")) }
        assertEquals(expected, got)
        // Scope: a TSO's histogram counts only the visits of their own zone's territory.
        val own = run(14, Role.TSO, "geofence-calibration").result().rows()
        val zone2Visits = fresh.db.jdbi.withHandle<Int, Exception> { h ->
            h.createQuery("SELECT count(*) FROM dw.fact_visit v JOIN dw.dim_geo g ON g.route_id = v.route_id WHERE v.visit_kind = 'sr_call' AND NOT v.voided AND v.distance_m IS NOT NULL AND g.zone_id = :z").bind("z", z2).mapTo(Int::class.java).one()
        }
        assertTrue(zone2Visits in 1 until visits.size, "zone 2 holds some but not all visits")
        assertEquals(zone2Visits, own.sumOf { it.num("visits").toInt() })
        assertEquals(HttpStatusCode.Forbidden, run(11, Role.TSO, "geofence-calibration", body(""","geo":{"zone":[$z2]}""")).status)
    }
}
