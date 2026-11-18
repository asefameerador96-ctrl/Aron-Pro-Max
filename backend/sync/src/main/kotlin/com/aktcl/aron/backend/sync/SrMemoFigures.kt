package com.aktcl.aron.backend.sync

import com.aktcl.aron.rules.BusinessDate
import kotlinx.datetime.toJavaLocalDate
import org.jdbi.v3.core.Handle
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The own-route figures the phone merges with its local memos (achievement, KPI volumes and `by_pack`, Star STD, campaign STD and memo), read straight from the
 * accepted memo tables (`app.memo`, `app.memo_line`) for ONE route and window, never from the lagging `dw.agg_*` aggregates (rule A of the no-clock design,
 * D-TKPI-12; the phone rule is `docs/requests/sr-programmes-phone-rule-b.md`).
 *
 * Filters are exactly the aggregator's (`Aggregator.ACTIVE_MEMO` and its line filters): memo `status = 'active' AND line_count > 0 AND voided_at IS NULL`, line
 * `voided_at IS NULL`; a sold quantity counts sale lines only; a memo counts once per SKU when it has any non-voided line of it (like `agg_daily_route_sku.memo_count`,
 * free lines included) and once per brand or outlet when it has a SALE line of it (like `agg_daily_route_brand.memo_count`). A superseded memo has another status and
 * is out; an edit is the new memo only.
 *
 * ONE HIERARCHY for brand sums: the CURRENT product tree (sku -> variant -> brand), the same tree the targets (`route_monthly_target` joined to the brand) and the
 * phone's own memos use, so a brand's target, server figure and local additions are all on one side of a move. The dashboards keep the as-was brand
 * (`dw.v_dim_product_history`), so the two differ for a SKU whose brand changed inside the window, and only then.
 *
 * The window is `[from, through]` by `business_date`; callers pass `through = bundle date - 1`: today is never in a server figure. Everything is one query per
 * call, so a caller that holds a REPEATABLE READ transaction (the bundle builder does) reads one snapshot.
 */
object SrMemoFigures {
    /** One (memo, SKU) pair of a route window. */
    class Line(val memo: UUID, val skuId: Long, val saleQty: Long, val saleLines: Int)

    /** The memos a route window holds. [asOf] = the newest `received_at` among the memos read (informational; the epoch when none): never used in arithmetic. */
    class Figures(val lines: List<Line>, val asOf: Instant) {
        /** sku -> (sold base quantity, distinct memos with any line of the SKU). */
        fun bySku(): Map<Long, Pair<Long, Int>> {
            val qty = HashMap<Long, Long>(); val memos = HashMap<Long, Int>()
            for (l in lines) { qty.merge(l.skuId, l.saleQty, Long::plus); memos.merge(l.skuId, 1, Int::plus) } // one line per (memo, sku)
            return qty.keys.sorted().associateWith { qty.getValue(it) to memos.getValue(it) }
        }
        /** Sold base quantity of the SKUs the filter accepts. */
        fun sold(accept: (Long) -> Boolean): Long = lines.filter { accept(it.skuId) }.sumOf { it.saleQty }
        companion object { val EMPTY = Figures(emptyList(), Instant.EPOCH) }
    }

    /** The master data the Kotlin grouping needs, read once per bundle build (the current tree). */
    class Dim(val skus: Map<Long, Sku>, val cigaretteBrands: Set<Long>)
    class Sku(val brandId: Long?, val basePerPack: Int, val category: String?)

    fun dim(h: Handle): Dim {
        val skus = h.createQuery(
            """
            SELECT s.id, b.id, s.base_per_pack, s.category_code FROM app.sku s
              LEFT JOIN app.product_node v ON v.id = s.variant_id LEFT JOIN app.product_node b ON b.id = v.parent_id
            """,
        ).map { rs, _ -> rs.getLong(1) to Sku(rs.getObject(2) as Long?, rs.getInt(3), rs.getString(4)) }.list().toMap()
        val cig = skus.values.filter { it.category == "cigarette" }.mapNotNull { it.brandId }.toSet()
        return Dim(skus, cig)
    }

    /**
     * THE LAST CLOSED DAY at build time: a figure is complete through `through_date`, so it must be a day that has ENDED when the bundle is built. A bundle for the bundle date D normally
     * cuts at D - 1; a bundle built AHEAD (the next working day's pre-fetch, taken while today is still open) must not claim D - 1 when D - 1 is today or later, so it cuts at
     * [today (Dhaka) - 1] (B1 of the T1 review): `min(date - 1, today - 1)`. A bundle is never for a day before today, so this is today - 1 whenever the bundle date is not earlier.
     */
    fun through(date: LocalDate, now: Instant): LocalDate =
        minOf(date.minusDays(1), BusinessDate.of(now.toEpochMilli()).toJavaLocalDate().minusDays(1))

    const val ACTIVE_MEMO = "m.status = 'active' AND m.line_count > 0 AND m.voided_at IS NULL"

    /** The figures of each route for `[from, through]`; a route with no memo is absent. An empty window (`through < from`) reads nothing. */
    fun forRoutes(h: Handle, routeIds: Collection<Long>, from: LocalDate, through: LocalDate): Map<Long, Figures> {
        if (routeIds.isEmpty() || through.isBefore(from)) return emptyMap()
        class Row(val route: Long, val line: Line, val at: Instant)
        val rows = h.createQuery(
            """
            SELECT m.route_id, m.client_uuid, ml.sku_id,
                   coalesce(sum(ml.qty_base) FILTER (WHERE ml.line_kind = 'sale'), 0), count(*) FILTER (WHERE ml.line_kind = 'sale'), max(m.received_at)
              FROM app.memo m JOIN app.memo_line ml ON ml.memo_client_uuid = m.client_uuid AND ml.business_date = m.business_date
             WHERE m.route_id = ANY(:r) AND m.business_date BETWEEN :f AND :t AND $ACTIVE_MEMO AND ml.voided_at IS NULL
             GROUP BY m.route_id, m.client_uuid, ml.sku_id
            """,
        ).bindArray("r", Long::class.javaObjectType, routeIds.toList()).bind("f", from).bind("t", through)
            .map { rs, _ -> Row(rs.getLong(1), Line(rs.getObject(2, UUID::class.java), rs.getLong(3), rs.getLong(4), rs.getInt(5)), rs.getTimestamp(6).toInstant()) }.list()
        return rows.groupBy { it.route }.mapValues { (_, l) -> Figures(l.map { it.line }, l.maxOf { it.at }) }
    }

    /** One campaign-figure row: the SALE lines of one (outlet, SKU) pair, per memo. */
    class OutletLine(val outlet: Long, val skuId: Long, val memo: UUID, val qty: Long)

    /**
     * The sale lines of the given (outlet, SKU) pairs for `[from, through]`, by outlet (every route's memos at the outlet count: the campaign target is the outlet's),
     * and the newest `received_at` among them per outlet. Only the pairs asked for are read, so a figure can never count a SKU the table does not show.
     */
    fun forOutletPairs(h: Handle, pairs: List<Pair<Long, Long>>, from: LocalDate, through: LocalDate): Pair<List<OutletLine>, Map<Long, Instant>> {
        if (pairs.isEmpty() || through.isBefore(from)) return emptyList<OutletLine>() to emptyMap()
        val ats = HashMap<Long, Instant>()
        val rows = h.createQuery(
            """
            SELECT m.outlet_id, ml.sku_id, m.client_uuid, sum(ml.qty_base), max(m.received_at)
              FROM app.memo m JOIN app.memo_line ml ON ml.memo_client_uuid = m.client_uuid AND ml.business_date = m.business_date
              JOIN unnest(CAST(:po AS bigint[]), CAST(:pk AS bigint[])) AS t(outlet_id, sku_id) ON t.outlet_id = m.outlet_id AND t.sku_id = ml.sku_id
             WHERE m.outlet_id = ANY(:o) AND m.business_date BETWEEN :f AND :t AND $ACTIVE_MEMO AND ml.voided_at IS NULL AND ml.line_kind = 'sale'
             GROUP BY m.outlet_id, ml.sku_id, m.client_uuid
            """,
        ).bindArray("po", Long::class.javaObjectType, pairs.map { it.first }).bindArray("pk", Long::class.javaObjectType, pairs.map { it.second })
            .bindArray("o", Long::class.javaObjectType, pairs.map { it.first }.distinct()).bind("f", from).bind("t", through)
            .map { rs, _ -> OutletLine(rs.getLong(1), rs.getLong(2), rs.getObject(3, UUID::class.java), rs.getLong(4)).also { l -> ats.merge(l.outlet, rs.getTimestamp(5).toInstant()) { a, b -> if (a.isAfter(b)) a else b } } }.list()
        return rows to ats
    }
}
