package com.aktcl.aron.backend.app

import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import kotlin.test.Test

/**
 * The data-flow trace on the other two days of sr1001's week (D-SEED-02: GUL-SR-2 is Saturday and Tuesday, GUL-SR-1 Sunday and
 * Wednesday, BAN-SR-11 Monday and Thursday; Friday is nobody's day). The same dev-profile world as [DataFlowTraceTest], a clock at
 * the date, and one selling day on the route planned that day: bundle -> sale in packets -> upload (out of order, duplicate, replay)
 * -> server count -> warehouse -> national dashboard. Every figure must equal the device truth; a difference fails the test.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
abstract class DataFlowTraceOtherDay(day: String, private val weekday: String, private val routeCode: String, private val otherRoutes: List<String>) : DataFlowTraceSupport(day) {
    override val traceName get() = "data-flow-trace-$weekday"
    private lateinit var allRecords: List<ObjectNode>

    @Test
    @Order(1)
    fun bundleHasOnlyTheRoutePlannedToday() {
        h("1 $weekday $day: the bundle plans $routeCode only")
        prepareSrDay()
        val planned = bundle.path("routes").filter { it.path("planned_today").asBoolean() }.map { it.path("route").path("code").asText("?") }
        hop("routes planned today in the $weekday bundle", "planned" to planned, "expected" to listOf(routeCode))
        val bundled = bundle.path("routes").map { it.path("route").path("code").asText("?") }.toSet()
        hop("the other routes of the SR are in the bundle but not planned", "other routes" to otherRoutes.filter { it in bundled }.sorted(), "expected" to otherRoutes.sorted())
        hop("outlets on the planned route with a pin", "with pin" to route.path("outlets").count { it.path("lat").isNumber && it.path("lng").isNumber }, "outlets" to route.path("outlets").size())
        hop("the first bundle logs every bundled route-day in, planned or not (D-BC-PREVIEW-01 relies on it)", "logged in" to sql("SELECT string_agg(r.code, ',' ORDER BY r.code) FROM app.route_day d JOIN app.route r ON r.id = d.route_id WHERE d.business_date = DATE '$day' AND d.logged_in_at IS NOT NULL AND r.code IN ('GUL-SR-1','GUL-SR-2','BAN-SR-11')"), "expected" to "BAN-SR-11,GUL-SR-1,GUL-SR-2")
    }

    @Test
    @Order(2)
    fun theDayOnThePhoneAndTheUpload() {
        h("2 $weekday: sale in packets, upload out of order, duplicate, replay")
        val cig20 = pick("cigarette", 20); val cig10 = pick("cigarette", 10); val bidi = pick("bidi"); val lighter = pick("lighter"); val match = pick("match")
        val codes = route.path("outlets").map { it.path("code").asText() }.sorted().take(4)
        check(codes.size == 4) { "the route needs four outlets" }
        sr.memos += Memo(outletByCode(codes[0]), codes[0], listOf(Line(cig20, 2), Line(cig10, 3), Line(bidi, 1), Line(lighter, 1), Line(match, 4)), paidShare = 100, minute = 0)
        sr.memos += Memo(outletByCode(codes[1]), codes[1], listOf(Line(cig20, 4), Line(bidi, 2)), paidShare = 50, minute = 40)
        sr.memos += Memo(outletByCode(codes[2]), codes[2], listOf(Line(cig10, 2)), paidShare = 100, voided = true, minute = 80)
        sr.memos += Memo(outletByCode(codes[3]), codes[3], emptyList(), paidShare = 0, minute = 120)
        val active = sr.memos.filter { it.lines.isNotEmpty() && !it.voided }
        val recs = mutableListOf<ObjectNode>()
        sr.memos.forEachIndexed { i, m -> val (r, a) = radiusOf(m.outlet); recs += recordsOf(sr, m, gulPins.getValue(m.outlet), r, a, i + 1) }
        val credit = sr.memos[1]
        val collected = minOf(credit.due, 1_500L)
        val cu = uuid()
        recs += envelope(sr, "due_collection", cu, cu, 0, 100, json.readTree(
            """{"outlet_id":${credit.outlet},"against_memo_client_uuid":"${credit.memo}","against_memo_no":"${recs.first { it.path("client_uuid").asText() == credit.memo }.path("payload").path("memo_no").asText()}",
                "against_memo_business_date":"$day","amount_mtk":$collected,"is_full_settlement":${collected >= credit.due},"outstanding_before_mtk":${credit.due},"payment_mode":"cash"}"""))
        allRecords = recs
        recs.groupingBy { it.path("type").asText() }.eachCount().forEach { (t, n) -> sr.counts[t] = n }
        note("device counts per type", sr.counts)
        val shuffled = send(sr, recs.shuffled(java.util.Random(11)), uuid())
        hop("whole day shuffled: rejected rows are retryable parent_missing only", "codes" to shuffled.body!!.path("acks").filter { it.path("status").asText() == "rejected" }.map { it.path("code").asText() + "/" + it.path("retryable").asText() }.distinct().filter { it != "parent_missing/true" }, "expected" to emptyList<String>())
        val inOrder = send(sr, recs, uuid())
        hop("the day in order leaves no rejected or quarantined row", "bad rows" to acks(inOrder).count { it == "rejected" || it == "quarantined" }, "expected" to 0)
        val again = send(sr, recs, uuid())
        hop("the whole day again under a new batch_uuid: every row duplicate", "statuses" to acks(again).distinct(), "expected" to listOf("duplicate"))
        val bu = uuid()
        val first = send(sr, recs, bu); val replay = send(sr, recs, bu)
        hop("the same batch_uuid again is a replay with the same acks", "replayed+same" to ((replay.body?.path("replayed")?.asText() ?: "?") + "/" + (replay.body?.path("acks") == first.body?.path("acks"))), "expected" to "true/true")
        fun count(table: String) = sqlLong("SELECT count(*) FROM $table t JOIN app.app_user u ON u.id = t.user_id WHERE u.username = 'sr1001' AND t.business_date = DATE '$day'")
        for ((t, tbl) in listOf("visit" to "app.visit", "memo" to "app.memo", "memo_line" to "app.memo_line", "due_collection" to "app.due_collection", "memo_void" to "app.memo_void"))
            hop("$t rows: device vs $tbl", "device" to sr.counts[t], "server" to count(tbl))
        val expGross = active.sumOf { it.gross }
        val soldBase = sortedMapOf<String, Long>(); for (m in active) for (l in m.lines) soldBase.merge(l.sku.id.toString(), l.qtyBase, Long::plus)
        val totals = send(sr, recs, uuid()).body!!.path("server_totals").single { it.path("business_date").asText() == day }
        val money = totals.path("money")
        hop("active memos: device vs server_totals", "device" to active.size, "server" to money.path("active_memo_count").asInt())
        hop("gross mtk", "device" to expGross, "server" to money.path("gross_mtk").asLong())
        hop("paid mtk", "device" to active.sumOf { it.paid }, "server" to money.path("paid_mtk").asLong())
        hop("due mtk", "device" to active.sumOf { it.due }, "server" to money.path("due_mtk").asLong())
        hop("due collected mtk", "device" to collected, "server" to money.path("due_collected_mtk").asLong())
        hop("sold qty (base units) by sku", "device" to soldBase.toString(), "server" to money.path("sold_qty_base_by_sku").fields().asSequence().associate { it.key to it.value.asLong() }.toSortedMap().toString())
        sr.truthGross = expGross; sr.truthSold = soldBase
    }

    @Test
    @Order(3)
    fun warehouseAndDashboardEqualTheDevice() {
        h("3 $weekday: warehouse and national dashboard (base units)")
        runWorker()
        val active = sr.memos.filter { it.lines.isNotEmpty() && !it.voided }
        val ar = rows("SELECT * FROM dw.agg_daily_route WHERE business_date = DATE '$day' AND route_id = $gulRouteId").singleOrNull() ?: error("no dw.agg_daily_route row for $routeCode")
        hop("agg_daily_route active memos", "dw" to ar["active_memo_count"], "device" to active.size)
        hop("agg_daily_route gross mtk", "dw" to ar["gross_mtk"], "device" to sr.truthGross)
        hop("agg_daily_route paid mtk", "dw" to ar["paid_mtk"], "device" to active.sumOf { it.paid })
        hop("agg_daily_route due mtk", "dw" to ar["due_mtk"], "device" to active.sumOf { it.due })
        hop("agg_daily_route visits", "dw" to ar["visits"], "device" to sr.memos.size)
        hop("agg_daily_route successful calls", "dw" to ar["successful_calls"], "device" to active.size)
        hop("the sr1001 routes with sales on $day", "routes" to sql("SELECT string_agg(r.code, ',') FROM dw.agg_daily_route a JOIN app.route r ON r.id = a.route_id WHERE a.business_date = DATE '$day' AND a.active_memo_count > 0"), "expected" to routeCode)
        val expCat = sortedMapOf<String, Long>(); for (m in active) for (l in m.lines) expCat.merge(l.sku.category, l.qtyBase, Long::plus)
        val cat = rows("SELECT p.category_code AS cat, sum(a.sold_qty_base) AS qty FROM dw.v_daily_sku a JOIN dw.dim_product p ON p.sku_id = a.sku_id WHERE a.business_date = DATE '$day' AND a.route_id = $gulRouteId GROUP BY 1")
        hop("dw.v_daily_sku by category (sticks, pieces, dozens)", "dw" to cat.associate { it["cat"].toString() to (it["qty"] as Number).toLong() }.toSortedMap().toString(), "device" to expCat.toString())
        val sales = jsonOf(postJson("/v1/dashboards/national/sales", login("superadmin1001", "web", null), """{"date":"$day"}"""))
        hop("national sales cards (SUPERADMIN) by category, base units", "dashboard" to sales.path("cards").associate { it.path("category_code").asText() to it.path("total_sales").asLong() }.filterValues { it != 0L }.toSortedMap().toString(), "device" to expCat.toString())
        val cpr = jsonOf(postJson("/v1/dashboards/national/cpr", login("superadmin1001", "web", null), """{"date":"$day"}"""))
        hop("national CPR successful calls", "dashboard" to cpr.path("successful_calls").asInt(), "device" to active.size, hard = false)
    }
}

class DataFlowTraceSundayTest : DataFlowTraceOtherDay("2026-10-25", "sunday", "GUL-SR-1", listOf("BAN-SR-11", "GUL-SR-2"))
class DataFlowTraceMondayTest : DataFlowTraceOtherDay("2026-10-26", "monday", "BAN-SR-11", listOf("GUL-SR-1", "GUL-SR-2"))
