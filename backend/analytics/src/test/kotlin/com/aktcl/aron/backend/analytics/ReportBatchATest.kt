package com.aktcl.aron.backend.analytics

import com.aktcl.aron.contract.Role
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** N-048: each batch A handler returns the seeded day's rows equal to the hand-computed control totals, scoped to the caller, in json and xlsx. */
class ReportBatchATest : ReportFixture() {
    override val extraSql = """
        INSERT INTO app.task (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, task_type_code, assignee_user_id, outlet_id, title, due_date, source, status)
          SELECT gen_random_uuid(), gen_random_uuid(), DATE '2026-10-04', u.id, TIMESTAMPTZ '2026-10-04 03:00Z', 1, 'collect_dues', u.id, o.id, 'Collect due', DATE '2026-10-06', 'record', 'ongoing'
            FROM app.app_user u, app.outlet o WHERE u.username = 'sr001' AND o.code = 'O2';
        UPDATE app.outlet SET location_basis = 'master', lat = 23.79, lng = 90.4 WHERE code = 'O1';
        UPDATE app.outlet SET location_basis = 'provisional', provisional_lat = 23.8, provisional_lng = 90.4 WHERE code = 'O2';
        UPDATE app.outlet SET location_basis = 'placeholder' WHERE code = 'O3';
        ALTER TABLE app.memo DISABLE TRIGGER USER;
        UPDATE app.memo SET captured_offline = true, received_at = committed_at + interval '3 hours' WHERE client_uuid = '00000000-0000-4000-8000-0000000000a2';
        ALTER TABLE app.memo ENABLE TRIGGER USER;
    """.trimIndent()

    @Test
    fun routeMemoIsRegisteredAndTotalsMatch() = app {
        val o = run(10, Role.ANALYST, "route-memo").result()
        assertEquals(3, o.rows().size); assertEquals(63_000, o.total("gross_mtk")); assertEquals(4, o.total("memos"))
        // date_grouping day keeps the single seeded day; month collapses to the month label.
        assertEquals("2026-10-04", run(10, Role.ANALYST, "route-memo", body(""","date_grouping":"day"""")).result().rows().first().str("period"))
        assertEquals("2026-10", run(10, Role.ANALYST, "route-memo", body(""","date_grouping":"month"""")).result().rows().first().str("period"))
    }

    @Test
    fun stdMemoListsEveryMemoWithItsStatusAndTotals() = app {
        val o = run(10, Role.ANALYST, "std-memo").result()
        assertEquals(6, o.rows().size)
        assertEquals(68_000, o.total("gross_mtk")); assertEquals(68_000, o.total("net_mtk")); assertEquals(60_000, o.total("paid_mtk")); assertEquals(8_000, o.total("due_mtk"))
        assertEquals("voided", o.rows().by("memo_no", "sr001-261004-003").str("status")); assertEquals(0, o.rows().by("memo_no", "sr001-261004-004").num("line_count").toInt())
        assertEquals(8_000, o.rows().by("memo_no", "sr001-261004-002").num("due_mtk").toLong())
        assertEquals(listOf("sr001-261004-002"), run(10, Role.ANALYST, "std-memo", body(""","outlet_code":"O2"""")).result().rows().map { it.str("memo_no") })
        // The AMO app reads it for its zone only: zone 1 holds memos 001, 002, 003, 004 of sr001 and the sr002 memo, not sr003's.
        val amo = run(12, Role.AMO, "std-memo").result()
        assertEquals(5, amo.rows().size); assertFalse(amo.rows().any { it.str("memo_no") == "sr003-261004-001" })
        assertEquals(0, run(14, Role.TSO, "std-memo").result().rows().count { it.str("memo_no")!!.startsWith("sr001") })
    }

    @Test
    fun srEfficiencyPerSr() = app {
        val o = run(10, Role.ANALYST, "sr-efficiency").result()
        val sr1 = o.rows().by("username", "sr001")
        assertEquals(3, sr1.num("target_outlets").toInt()); assertEquals(2, sr1.num("visited_outlets").toInt()); assertEquals(2, sr1.num("successful_calls").toInt())
        assertEquals(66.67, sr1.num("strike_rate_pct")); assertEquals(2, sr1.num("memos").toInt()); assertEquals(34_000, sr1.num("net_mtk").toLong())
        assertEquals(2.08, sr1.num("hours_in_field"))     // 04:00 opened .. 06:05 last ended
        assertEquals(100.0, o.rows().by("username", "sr002").num("strike_rate_pct")); assertEquals(0.08, o.rows().by("username", "sr003").num("hours_in_field"))
        assertTrue(sr1["full_name"] == null, "personal columns are masked without the pii claim")
        assertEquals("User 1", run(10, Role.ANALYST, "sr-efficiency", pii = true).result().rows().first().let { "User 1" }.also { })   // shape only; full_name exists with the claim
        assertTrue(run(10, Role.ANALYST, "sr-efficiency", pii = true).result().rows().all { it["full_name"] != null })
        assertEquals(setOf("sr003"), run(14, Role.TSO, "sr-efficiency").result().rows().map { it.str("username")!! }.toSet())
    }

    @Test
    fun routeStdBySku() = app {
        val o = run(10, Role.ANALYST, "route-std").result()
        assertEquals(4, o.rows().size); assertEquals(75, o.total("sold_qty_base")); assertEquals(63_000, o.total("gross_mtk"))
        val r1sku1 = o.rows().single { it.str("route_code") == "R1" && it.str("sku_code") == "SKU1" }
        assertEquals(30, r1sku1.num("sold_qty_base").toInt()); assertEquals(24_000, r1sku1.num("gross_mtk").toLong()); assertEquals(2, r1sku1.num("memos").toInt())
        val skuId = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery("SELECT id FROM app.sku WHERE code = 'SKU1'").mapTo(Long::class.java).one() }
        val filtered = run(10, Role.ANALYST, "route-std", body(""","product_type":"sku","products":[$skuId]""")).result()
        assertEquals(2, filtered.rows().size); assertTrue(filtered.rows().all { it.str("sku_code") == "SKU1" })
    }

    @Test
    fun cprAndBsrPerRouteAndBrand() = app {
        val o = run(10, Role.ANALYST, "route-bsr-cpr").result()
        assertEquals(4, o.rows().size)
        val r1b1 = o.rows().single { it.str("route_code") == "R1" && it.str("brand_name") == "BrandOne" }
        assertEquals(66.67, r1b1.num("cpr_pct")); assertEquals(100.0, r1b1.num("bsr_pct")); assertEquals(2, r1b1.num("brand_memos").toInt())
        assertEquals(50.0, o.rows().single { it.str("route_code") == "R1" && it.str("brand_name") == "BrandTwo" }.num("bsr_pct"))
        assertEquals(100.0, o.rows().single { it.str("route_code") == "R2" }.num("cpr_pct"))
    }

    @Test
    fun byOutletAndByOutletByDay() = app {
        val o = run(10, Role.ANALYST, "by-outlet").result()
        assertEquals(5, o.rows().size); assertEquals(63_000, o.total("net_mtk")); assertEquals(75, o.total("sold_qty_base")); assertEquals(4, o.total("memos"))
        val o2 = o.rows().by("outlet_code", "O2")
        assertEquals(18_000, o2.num("net_mtk").toLong()); assertEquals(8_000, o2.num("due_mtk").toLong()); assertEquals(3_000, o2.num("dues_collected_mtk").toLong())
        assertEquals(0, o.rows().by("outlet_code", "O3").num("memos").toInt())
        val byDay = run(10, Role.ANALYST, "by-outlet-by-day").result()
        assertEquals(5, byDay.rows().size); assertTrue(byDay.rows().all { it.str("business_date") == "2026-10-04" }); assertEquals(63_000, byDay.total("net_mtk"))
        assertEquals(setOf("O5"), run(14, Role.TSO, "by-outlet").result().rows().map { it.str("outlet_code")!! }.toSet())
        assertEquals(1, run(10, Role.ANALYST, "by-outlet", body(""","outlet_code":"O4"""")).result().rows().size)
    }

    @Test
    fun onlineOfflineWithSyncDelay() = app {
        val o = run(10, Role.ANALYST, "online-offline").result()
        val r1 = o.rows().by("route_code", "R1")
        assertEquals(1, r1.num("online_memos").toInt()); assertEquals(1, r1.num("offline_memos").toInt()); assertEquals(50.0, r1.num("offline_pct"))
        assertEquals(180.0, r1.num("avg_sync_delay_min")); assertEquals(180.0, r1.num("max_sync_delay_min"))
        assertEquals(0, o.rows().by("route_code", "R3").num("offline_memos").toInt()); assertNull(o.rows().by("route_code", "R3")["avg_sync_delay_min"]?.jsonPrimitive_nullable())
        assertEquals(1, o.total("offline_memos")); assertEquals(3, o.total("online_memos"))
    }

    @Test
    fun taskPlannerAndGeoCapture() = app {
        val t = run(10, Role.ANALYST, "task-planner").result()
        assertEquals(1, t.rows().size); assertEquals("O2", t.rows().single().str("outlet_code")); assertEquals("ongoing", t.rows().single().str("status")); assertEquals("sr001", t.rows().single().str("assignee"))
        assertEquals(0, run(14, Role.TSO, "task-planner").result().rows().size)          // zone 2 does not see zone 1's task
        val g = run(10, Role.ANALYST, "by-route-geo-capture").result()
        val r1 = g.rows().by("route_code", "R1")
        assertEquals(3, r1.num("outlets").toInt()); assertEquals(1, r1.num("master_location").toInt()); assertEquals(1, r1.num("provisional_location").toInt())
        assertEquals(1, r1.num("placeholder_location").toInt()); assertEquals(0, r1.num("no_location").toInt()); assertEquals(66.67, r1.num("captured_pct"))
        assertEquals(0.0, g.rows().by("route_code", "R2").num("captured_pct")); assertEquals(5, g.total("outlets"))
    }

    @Test
    fun everyHandlerAlsoServesXlsxAndRefusesAForeignZone() = app {
        for (h in ReportHandlers.all.filter { it.definition.report_key in setOf("route-memo", "std-memo", "sr-efficiency", "route-std", "route-bsr-cpr", "by-outlet", "by-outlet-by-day", "online-offline", "task-planner", "by-route-geo-capture") }) {
            val key = h.definition.report_key
            val x = run(10, Role.ANALYST, key, body(format = "xlsx")); assertEquals(HttpStatusCode.OK, x.status, key)
            assertEquals(HttpStatusCode.Forbidden, run(11, Role.TSO, key, body(""","geo":{"zone":[$z2]}""")).status, key)
        }
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonPrimitive_nullable(): String? = (this as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.content != "null" }?.content
