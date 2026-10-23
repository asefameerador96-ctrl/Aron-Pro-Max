package com.aktcl.aron.backend.app

import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Two readings the Saturday trace does not reach (docs/24 s7.3 and s7.4, D-TARGET-MIN-01):
 *  1. the rounding branches: SKUs priced per more than one base unit (`per_base_qty` 2 and 3, so a line divides half-up, including an exact
 *     tie), memos whose unrounded sum rounds down, up and on the .5 tie to the paisa, `round_adj_mtk` carried per memo; every figure from
 *     the phone to server_totals and the warehouse must equal the device;
 *  2. the monthly target read over three months: October, November (every target doubled) and December (the lighter targets removed), and the
 *     boundary day between two months; the sales cards' daily target is the month's total over the working days of that month.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DataFlowTraceMoneyTargetsTest : DataFlowTraceSupport() {
    override val traceName get() = "data-flow-trace-money"

    @Test
    @Order(1)
    fun roundingBranchesFromThePhoneToTheWarehouse() {
        h("1 Rounding: per_base_qty > 1 lines (half-up), memo net half-up to the paisa, round_adj")
        fun skuId(category: String, pack: Int? = null) = sqlLong("SELECT min(id) FROM app.sku WHERE category_code = '$category' AND status = 'active'" + (pack?.let { " AND base_per_pack = $it" } ?: ""))
        val lighterId = skuId("lighter"); val matchId = skuId("match"); val cig10Id = skuId("cigarette", 10)
        db { hd ->
            hd.execute("UPDATE app.sku_price SET amount_mtk = 1001, per_base_qty = 2 WHERE sku_id = $lighterId AND price_type = 'outlet' AND valid_to IS NULL")
            hd.execute("UPDATE app.sku_price SET amount_mtk = 1005, per_base_qty = 1 WHERE sku_id = $matchId AND price_type = 'outlet' AND valid_to IS NULL")
            hd.execute("UPDATE app.sku_price SET amount_mtk = 1001, per_base_qty = 3 WHERE sku_id = $cig10Id AND price_type = 'outlet' AND valid_to IS NULL")
        }
        prepareSrDay()
        val lighter = pick("lighter"); val match = pick("match"); val cig10 = pick("cigarette", 10)
        hop("the bundle serves the edited prices (price per N base units)", "lighter/match/cigarette-10" to "${lighter.id}:${lighter.price}/${lighter.perBase} ${match.id}:${match.price}/${match.perBase} ${cig10.id}:${cig10.price}/${cig10.perBase}",
            "expected" to "$lighterId:1001/2 $matchId:1005/1 $cig10Id:1001/3")
        val codes = route.path("outlets").map { it.path("code").asText() }.sorted().take(4)
        sr.memos += Memo(outletByCode(codes[0]), codes[0], listOf(Line(lighter, 1)), paidShare = 100, minute = 0)
        sr.memos += Memo(outletByCode(codes[1]), codes[1], listOf(Line(match, 1)), paidShare = 50, minute = 30)
        sr.memos += Memo(outletByCode(codes[2]), codes[2], listOf(Line(cig10, 1)), paidShare = 100, minute = 60)
        sr.memos += Memo(outletByCode(codes[3]), codes[3], listOf(Line(cig10, 2), Line(match, 1)), paidShare = 0, minute = 90)
        for (m in sr.memos) note("${m.outletCode}", "lines ${m.lines.map { it.gross }} gross ${m.gross} net ${m.net} round_adj ${m.roundAdj} paid ${m.paid} due ${m.due}")
        val tieLine = lighter.perBase == 2L && Line(lighter, 1).qtyBase % 2 == 1L
        hop("a line divides on an exact .5 (qty_base x price / per_base_qty): half-up", "tie line" to tieLine, "expected" to true)
        hop("round_adj covers a round-down, a round-up and the .5 tie of the paisa", "adjustments" to sr.memos.map { it.roundAdj }.sorted(), "expected" to listOf(-3L, 2L, 3L, 5L))

        val recs = mutableListOf<ObjectNode>()
        sr.memos.forEachIndexed { i, m -> val (r, a) = radiusOf(m.outlet); recs += recordsOf(sr, m, gulPins.getValue(m.outlet), r, a, i + 1) }
        recs.groupingBy { it.path("type").asText() }.eachCount().forEach { (t, n) -> sr.counts[t] = n }
        val up = send(sr, recs, uuid())
        hop("the day is accepted (every memo's stated net and round_adj match the server's own arithmetic)", "statuses" to acks(up).distinct(), "expected" to listOf("accepted"))
        hop("the same day again: duplicate", "statuses" to acks(send(sr, recs, uuid())).distinct(), "expected" to listOf("duplicate"))

        val stored = rows("SELECT m.net_mtk, m.round_adj_mtk, m.gross_mtk, m.paid_mtk, m.due_mtk FROM app.memo m WHERE m.client_uuid = ANY(ARRAY[${sr.memos.joinToString(",") { "'${it.memo}'::uuid" }}])")
        hop("stored memos: net, round_adj, gross, paid, due summed", "server" to listOf("net_mtk", "round_adj_mtk", "gross_mtk", "paid_mtk", "due_mtk").map { c -> stored.sumOf { (it[c] as Number).toLong() } },
            "device" to listOf(sr.memos.sumOf { it.net }, sr.memos.sumOf { it.roundAdj }, sr.memos.sumOf { it.gross }, sr.memos.sumOf { it.paid }, sr.memos.sumOf { it.due }))
        val storedLines = rows("SELECT ml.gross_mtk, ml.qty_base FROM app.memo_line ml WHERE ml.memo_client_uuid = ANY(ARRAY[${sr.memos.joinToString(",") { "'${it.memo}'::uuid" }}]) ORDER BY ml.gross_mtk, ml.qty_base")
        hop("stored line gross (milli-taka, half-up per line) and base quantity", "server" to storedLines.map { "${it["gross_mtk"]}/${it["qty_base"]}" },
            "device" to sr.memos.flatMap { it.lines }.sortedWith(compareBy({ it.gross }, { it.qtyBase })).map { "${it.gross}/${it.qtyBase}" })
        val totals = send(sr, recs, uuid()).body!!.path("server_totals").single { it.path("business_date").asText() == day }.path("money")
        hop("server_totals gross / net / paid / due", "server" to listOf("gross_mtk", "net_mtk", "paid_mtk", "due_mtk").map { totals.path(it).asLong() },
            "device" to listOf(sr.memos.sumOf { it.gross }, sr.memos.sumOf { it.net }, sr.memos.sumOf { it.paid }, sr.memos.sumOf { it.due }))
        runWorker()
        val ar = rows("SELECT gross_mtk, net_mtk, paid_mtk, due_mtk FROM dw.agg_daily_route WHERE business_date = DATE '$day' AND route_id = $gulRouteId").single()
        hop("dw.agg_daily_route gross / net / paid / due", "dw" to listOf("gross_mtk", "net_mtk", "paid_mtk", "due_mtk").map { (ar[it] as Number).toLong() },
            "device" to listOf(sr.memos.sumOf { it.gross }, sr.memos.sumOf { it.net }, sr.memos.sumOf { it.paid }, sr.memos.sumOf { it.due }))
        hop("dw.agg_daily_outlet net summed over the route", "dw" to sqlLong("SELECT coalesce(sum(net_mtk), 0) FROM dw.agg_daily_outlet WHERE business_date = DATE '$day' AND route_id = $gulRouteId"), "device" to sr.memos.sumOf { it.net })
    }

    @Test
    @Order(2)
    fun monthlyTargetsReadOverThreeMonths() {
        h("2 Monthly targets over October, November (doubled) and December (lighters removed): daily target = month total / working days")
        val routes = "(SELECT id FROM app.route WHERE code IN ('GUL-SR-2','GUL-SR-1','BAN-SR-11'))"
        db { hd ->
            hd.execute("UPDATE app.route_monthly_target SET qty_base = qty_base * 2 WHERE month = DATE '2026-11-01' AND route_id IN $routes")
            hd.execute("DELETE FROM app.route_monthly_target t USING app.sku s WHERE s.id = t.sku_id AND s.category_code = 'lighter' AND t.month = DATE '2026-12-01'")
        }
        val sup = login("superadmin1001", "web", null)
        fun workingDays(month: String): Long = sqlLong(
            """
            SELECT count(*) FROM generate_series(DATE '$month-01', (DATE '$month-01' + interval '1 month - 1 day')::date, interval '1 day') g(d)
             LEFT JOIN app.calendar_holiday c ON c.date = g.d::date AND c.scope_type = 'global' AND c.revoked_at IS NULL
             WHERE (extract(isodow FROM g.d) <> 5 AND coalesce(c.kind, '') NOT IN ('holiday', 'emergency_off')) OR c.kind = 'makeup_day'
            """.trimIndent(),
        )
        for ((month, dates) in listOf("2026-10" to listOf("2026-10-24", "2026-10-31"), "2026-11" to listOf("2026-11-01", "2026-11-14"), "2026-12" to listOf("2026-12-14"))) {
            val wd = workingDays(month)
            val totals = rows("SELECT s.category_code AS cat, sum(t.qty_base) AS total, count(DISTINCT t.route_id) AS routes FROM app.route_monthly_target t JOIN app.sku s ON s.id = t.sku_id WHERE t.month = DATE '$month-01' AND t.route_id IN $routes GROUP BY 1")
                .associate { it["cat"].toString() to ((it["total"] as Number).toLong() to (it["routes"] as Number).toInt()) }
            for (date in dates) {
                val sales = jsonOf(postJson("/v1/dashboards/national/sales", sup, """{"date":"$date"}"""))
                hop("$date working days of the month", "dashboard" to sales.path("working_days").asInt(), "calendar" to wd.toInt())
                val cards = sales.path("cards").associateBy { it.path("category_code").asText() }
                assertTrue(cards.keys.containsAll(listOf("cigarette", "bidi", "lighter", "match")), "four category cards")
                val got = cards.mapValues { (_, c) -> c.path("daily_target").takeIf { !it.isNull }?.asText()?.let { BigDecimal(it) } }.toSortedMap()
                val exp = cards.keys.associateWith { cat -> totals[cat]?.let { BigDecimal(it.first).divide(BigDecimal(wd), 3, RoundingMode.HALF_UP) } }.toSortedMap()
                hop("$date daily target per category (month total / working days)", "dashboard" to got.mapValues { it.value?.toPlainString() }, "expected" to exp.mapValues { it.value?.toPlainString() })
                hop("$date routes with a target per category", "dashboard" to cards.mapValues { it.value.path("target_routes").asInt() }.toSortedMap(), "expected" to cards.keys.associateWith { totals[it]?.second ?: 0 }.toSortedMap())
            }
        }
    }
}
