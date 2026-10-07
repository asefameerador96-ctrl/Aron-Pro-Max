package com.aktcl.aron.backend.analytics

/** Report handlers, batch A (N-048). Each reads `dw` (aggregates and facts); scope comes from [ReportContext]. */

/** `route-memo`: memo counts and value by route and period. */
object RouteMemoReport : ReportHandler {
    override val definition = definition(
        "route-memo", "Route-wise Memo", "sales", "route x period",
        listOf(
            col("route_code", "Route code", "string"), col("route_name", "Route", "string"), col("period", "Period", "string"),
            col("memos", "Memos", "integer"), col("gross_mtk", "Gross", "mtk", unit = "mtk"), col("net_mtk", "Net", "mtk", unit = "mtk"),
            col("paid_mtk", "Paid", "mtk", unit = "mtk"), col("due_mtk", "Due", "mtk", unit = "mtk"),
        ),
        listOf("period", "date_grouping", "geo"),
    )

    override fun spec(ctx: ReportContext) = SqlSpec(
        """
        SELECT g.route_code, g.route_name, ${periodExpr(ctx, "a.business_date")} AS period, sum(a.active_memo_count)::bigint AS memos,
               sum(a.gross_mtk)::bigint AS gross_mtk, sum(a.net_mtk)::bigint AS net_mtk, sum(a.paid_mtk)::bigint AS paid_mtk, sum(a.due_mtk)::bigint AS due_mtk
          FROM dw.agg_daily_route a JOIN dw.dim_geo g ON g.route_id = a.route_id
         WHERE ${ctx.dateClause("a.business_date")} AND ${ctx.zoneClause("a.zone_id")} AND ${ctx.routeClause("a.route_id")}
         GROUP BY 1, 2, 3
        """,
        totalColumns = listOf("memos", "gross_mtk", "net_mtk", "paid_mtk", "due_mtk"),
    )
}


/** Product selectors of ReportQuery (`category`, `product_type` + `products`) as a clause on a `dw.dim_product` alias. Ids are bound, never interpolated. */
internal fun productClause(ctx: ReportContext, p: String): Pair<String, Map<String, Any?>> {
    val parts = mutableListOf<String>(); val binds = mutableMapOf<String, Any?>()
    if (ctx.query.category.isNotEmpty()) { parts += "$p.category_id = ANY(:cats)"; binds["cats"] = ctx.query.category.toTypedArray() }
    if (ctx.query.products.isNotEmpty()) {
        val col = when (ctx.query.product_type ?: "sku") { "category" -> "category_id"; "brand" -> "brand_id"; "variant" -> "variant_id"; else -> "sku_id" }
        parts += "$p.$col = ANY(:prods)"; binds["prods"] = ctx.query.products.toTypedArray()
    }
    return (parts.joinToString(" AND ").ifEmpty { "true" }) to binds
}

/** `std-memo`: one row per active sales memo (status active, at least one line): the same population as every total on the dashboards. Voids and number gaps are in `memo-number-gaps`. */
object StdMemoReport : ReportHandler {
    override val roles = WEB_ROLES + com.aktcl.aron.contract.Role.AMO   // the AMO app reads it for its zone (F-API-017b)
    override val definition = definition(
        "std-memo", "STD Memo", "sales", "memo",
        listOf(
            col("business_date", "Date", "date"), col("memo_no", "Memo no", "string"), col("status", "Status", "string"), col("route_code", "Route", "string"),
            col("outlet_code", "Outlet code", "string"), col("outlet_name", "Outlet", "string"), col("committed_at", "Time", "timestamp"), col("line_count", "Lines", "integer"), col("lines", "SKU lines", "string"),
            col("gross_mtk", "Gross", "mtk", unit = "mtk"), col("offer_discount_mtk", "Offer discount", "mtk", unit = "mtk"), col("drp_discount_mtk", "DRP discount", "mtk", unit = "mtk"),
            col("qc_deduction_mtk", "QC deduction", "mtk", unit = "mtk"), col("net_mtk", "Net", "mtk", unit = "mtk"), col("paid_mtk", "Paid", "mtk", unit = "mtk"), col("due_mtk", "Due", "mtk", unit = "mtk"),
        ),
        listOf("period", "geo", "outlet_code"),
    )

    override fun spec(ctx: ReportContext) = SqlSpec(
        """
        SELECT m.business_date, m.memo_no, m.status, g.route_code, o.outlet_code, o.outlet_name, m.committed_at, m.line_count::int AS line_count,
               (SELECT string_agg(s.code || ' x' || ml.qty_base, ', ' ORDER BY ml.line_no) FROM app.memo_line ml JOIN app.sku s ON s.id = ml.sku_id
                 WHERE ml.memo_client_uuid = m.memo_client_uuid AND ml.business_date = m.business_date AND ml.voided_at IS NULL) AS lines, m.gross_mtk,
               m.offer_discount_mtk, m.drp_discount_mtk, m.qc_deduction_mtk, m.net_mtk, m.paid_mtk, m.due_mtk
          FROM dw.fact_memo m JOIN dw.dim_outlet o ON o.outlet_id = m.outlet_id LEFT JOIN dw.dim_geo g ON g.route_id = m.route_id
         WHERE ${ctx.dateClause("m.business_date")} AND ${ctx.zoneClause("m.zone_id")} AND ${ctx.routeClause("m.route_id")}
           AND m.status = 'active' AND m.line_count > 0 AND (CAST(:ocode AS text) IS NULL OR o.outlet_code = :ocode)
        """,
        mapOf("ocode" to ctx.query.outlet_code?.takeIf { it.isNotBlank() }),
        listOf("gross_mtk", "offer_discount_mtk", "drp_discount_mtk", "qc_deduction_mtk", "net_mtk", "paid_mtk", "due_mtk"),
    )
}

/** `sr-efficiency`: per SR and route, over the period (hours in field = first visit opened to last visit ended, summed over days). */
object SrEfficiencyReport : ReportHandler {
    override val definition = definition(
        "sr-efficiency", "SR Efficiency", "field_force", "SR x route",
        listOf(
            col("username", "SR", "string"), col("full_name", "Name", "string", pii = true), col("route_code", "Route", "string"), col("target_outlets", "Target outlets", "integer"),
            col("visited_outlets", "Visited", "integer"), col("successful_calls", "Successful calls", "integer"), col("strike_rate_pct", "Strike rate %", "pct", unit = "%"),
            col("memos", "Memos", "integer"), col("net_mtk", "Net", "mtk", unit = "mtk"), col("hours_in_field", "Hours in field", "decimal", unit = "h"),
        ),
        listOf("period", "geo", "field_force_type"),
    )

    override fun spec(ctx: ReportContext) = SqlSpec(
        """
        WITH hrs AS (SELECT route_id, business_date, extract(epoch FROM max(coalesce(ended_at, opened_at)) - min(opened_at)) / 3600.0 AS h
                       FROM dw.fact_visit WHERE ${ctx.dateClause("business_date")} AND NOT voided AND visit_kind = 'sr_call' GROUP BY 1, 2)
        SELECT u.username, u.full_name, g.route_code,
               coalesce(sum(a.target_outlets) FILTER (WHERE a.planned AND NOT a.exception_approved), 0)::int AS target_outlets,
               coalesce(sum(a.visited_outlets), 0)::int AS visited_outlets, coalesce(sum(a.successful_calls), 0)::int AS successful_calls,
               round(100.0 * sum(a.successful_calls) FILTER (WHERE a.planned AND NOT a.exception_approved) / nullif(sum(a.target_outlets) FILTER (WHERE a.planned AND NOT a.exception_approved), 0), 2) AS strike_rate_pct,
               coalesce(sum(a.active_memo_count), 0)::int AS memos, coalesce(sum(a.net_mtk), 0)::bigint AS net_mtk, round(coalesce(sum(hrs.h), 0)::numeric, 2) AS hours_in_field
          FROM dw.agg_daily_route a JOIN dw.dim_geo g ON g.route_id = a.route_id
          JOIN app.route_day rd ON rd.route_id = a.route_id AND rd.business_date = a.business_date
          JOIN app.app_user u ON u.id = coalesce(rd.acting_user_id, rd.assigned_user_id)
          LEFT JOIN hrs ON hrs.route_id = a.route_id AND hrs.business_date = a.business_date
         WHERE ${ctx.dateClause("a.business_date")} AND ${ctx.zoneClause("a.zone_id")} AND ${ctx.routeClause("a.route_id")}
           AND (:fft = 'all' OR u.role = upper(:fft))
         GROUP BY u.id, u.username, u.full_name, g.route_code
        """,
        mapOf("fft" to ctx.query.field_force_type),
        totalColumns = listOf("target_outlets", "visited_outlets", "successful_calls", "memos", "net_mtk"),
    )
}

/** `route-std`: sales by route and SKU for the period. The monthly target column is out of the build (docs/27: target reports deferred). */
object RouteStdReport : ReportHandler {
    override val definition = definition(
        "route-std", "Route-wise STD", "sales", "route x SKU",
        listOf(
            col("route_code", "Route", "string"), col("route_name", "Route name", "string"), col("sku_code", "SKU", "string"), col("sku_name", "SKU name", "string"),
            col("base_unit", "Unit", "string"), col("sold_qty_base", "Sold", "integer"), col("free_qty_base", "Free", "integer"), col("gross_mtk", "Gross", "mtk", unit = "mtk"), col("memos", "Memos", "integer"),
        ),
        listOf("period", "geo", "category", "product_type", "products"),
    )

    override fun spec(ctx: ReportContext): SqlSpec {
        val (prod, binds) = productClause(ctx, "p")
        return SqlSpec(
            """
            SELECT g.route_code, g.route_name, p.sku_code, p.short_name AS sku_name, p.base_unit, sum(a.sold_qty_base)::bigint AS sold_qty_base,
                   sum(a.free_qty_base)::bigint AS free_qty_base, sum(a.gross_mtk)::bigint AS gross_mtk, sum(a.memo_count)::int AS memos
              FROM dw.agg_daily_route_sku a JOIN dw.dim_product p ON p.sku_id = a.sku_id JOIN dw.dim_geo g ON g.route_id = a.route_id
             WHERE ${ctx.dateClause("a.business_date")} AND ${ctx.zoneClause("g.zone_id")} AND ${ctx.routeClause("a.route_id")} AND $prod
             GROUP BY 1, 2, 3, 4, 5
            """,
            binds, listOf("sold_qty_base", "free_qty_base", "gross_mtk"),
        )
    }
}

/** `route-bsr-cpr`: call productivity (CPR) per route and brand strike (BSR), docs/24 s12.4. */
object RouteBsrCprReport : ReportHandler {
    override val definition = definition(
        "route-bsr-cpr", "CPR and BSR", "sales", "route x brand",
        listOf(
            col("route_code", "Route", "string"), col("route_name", "Route name", "string"), col("brand_name", "Brand", "string"), col("target_outlets", "Target outlets", "integer"),
            col("successful_calls", "Successful calls", "integer"), col("cpr_pct", "CPR %", "pct", unit = "%"), col("active_memos", "Active memos", "integer"),
            col("brand_memos", "Memos with brand", "integer"), col("bsr_pct", "BSR %", "pct", unit = "%"),
        ),
        listOf("period", "geo", "category", "product_type", "products"),
    )

    override fun spec(ctx: ReportContext): SqlSpec {
        val (prod, prodBinds) = productClause(ctx, "pp")
        // A brand qualifies when it has a product inside the selected categories / products.
        val brandFilter = if (prod == "true") "" else "AND EXISTS (SELECT 1 FROM dw.dim_product pp WHERE pp.brand_id = b.brand_id AND $prod)"
        return SqlSpec(
            """
            WITH rt AS (SELECT route_id, sum(target_outlets) FILTER (WHERE planned AND NOT exception_approved) AS t, sum(successful_calls) AS sc, sum(active_memo_count) AS memos
                          FROM dw.agg_daily_route WHERE ${ctx.dateClause("business_date")} AND ${ctx.zoneClause("zone_id")} AND ${ctx.routeClause("route_id")} GROUP BY route_id),
            br AS (SELECT b.route_id, b.brand_id, sum(b.memo_count) AS bm FROM dw.agg_daily_route_brand b
                    WHERE ${ctx.dateClause("b.business_date")} $brandFilter GROUP BY 1, 2)
            SELECT g.route_code, g.route_name, p.brand_name, coalesce(rt.t, 0)::int AS target_outlets, rt.sc::int AS successful_calls,
                   round(100.0 * rt.sc / nullif(rt.t, 0), 2) AS cpr_pct, rt.memos::int AS active_memos, br.bm::int AS brand_memos,
                   round(100.0 * br.bm / nullif(rt.memos, 0), 2) AS bsr_pct
              FROM rt JOIN br ON br.route_id = rt.route_id JOIN dw.dim_geo g ON g.route_id = rt.route_id
              JOIN LATERAL (SELECT brand_name FROM dw.dim_product WHERE brand_id = br.brand_id ORDER BY brand_name LIMIT 1) p ON true
            """,
            prodBinds,
        )
    }
}

private val OUTLET_COLS = listOf(
    col("outlet_code", "Outlet code", "string"), col("outlet_name", "Outlet", "string"), col("route_code", "Route", "string"), col("channel", "Channel", "string"),
    col("memos", "Memos", "integer"), col("sold_qty_base", "Sold", "integer"), col("net_mtk", "Net", "mtk", unit = "mtk"), col("due_mtk", "Due", "mtk", unit = "mtk"),
    col("dues_collected_mtk", "Dues collected", "mtk", unit = "mtk"),
)

/**
 * Outlet rows are built from the records of the routes in the caller's scope (memo and visit facts, the memo lines, the due collections), never from
 * `dw.agg_daily_outlet`, which is one row per outlet across all routes and would carry the money of routes the caller cannot see.
 */
private fun outletSpec(ctx: ReportContext, byDay: Boolean): SqlSpec {
    val status = when (ctx.query.active_status) { "active" -> "AND o.status = 'active'"; "inactive" -> "AND o.status <> 'active'"; else -> "" }
    val sub = if (ctx.query.sub_channels.isEmpty()) "" else "AND o.sub_channel_id = ANY(:subs)"
    val d = if (byDay) "business_date" else "NULL::date"
    return SqlSpec(
        """
        WITH memo AS (SELECT m.outlet_id, $d AS d, count(*) AS n, sum(m.net_mtk) AS net, sum(m.due_mtk) AS due FROM dw.fact_memo m
                       WHERE ${ctx.dateClause("m.business_date")} AND ${ctx.zoneClause("m.zone_id")} AND ${ctx.routeClause("m.route_id")} AND m.status = 'active' AND m.line_count > 0
                       GROUP BY 1, 2),
        qty AS (SELECT m.outlet_id, ${if (byDay) "m.business_date" else "NULL::date"} AS d, sum(ml.qty_base) AS q FROM app.memo_line ml
                  JOIN dw.fact_memo m ON m.memo_client_uuid = ml.memo_client_uuid AND m.business_date = ml.business_date
                 WHERE ${ctx.dateClause("m.business_date")} AND ${ctx.zoneClause("m.zone_id")} AND ${ctx.routeClause("m.route_id")} AND m.status = 'active' AND m.line_count > 0
                   AND ml.voided_at IS NULL AND ml.line_kind = 'sale' GROUP BY 1, 2),
        dues AS (SELECT c.outlet_id, ${if (byDay) "c.business_date" else "NULL::date"} AS d, sum(c.amount_mtk) AS amt FROM app.due_collection c JOIN dw.dim_geo g ON g.route_id = c.route_id
                  WHERE ${ctx.dateClause("c.business_date")} AND ${ctx.zoneClause("g.zone_id")} AND ${ctx.routeClause("c.route_id")} AND c.voided_at IS NULL GROUP BY 1, 2),
        vis AS (SELECT v.outlet_id, ${if (byDay) "v.business_date" else "NULL::date"} AS d FROM dw.fact_visit v
                 WHERE ${ctx.dateClause("v.business_date")} AND ${ctx.zoneClause("v.zone_id")} AND ${ctx.routeClause("v.route_id")} AND NOT v.voided AND v.visit_kind = 'sr_call' GROUP BY 1, 2),
        keys AS (SELECT outlet_id, d FROM memo UNION SELECT outlet_id, d FROM dues UNION SELECT outlet_id, d FROM vis)
        SELECT ${if (byDay) "k.d AS business_date, " else ""}o.outlet_code, o.outlet_name, g.route_code, o.channel, coalesce(m.n, 0)::int AS memos, coalesce(q.q, 0)::bigint AS sold_qty_base,
               coalesce(m.net, 0)::bigint AS net_mtk, coalesce(m.due, 0)::bigint AS due_mtk, coalesce(u.amt, 0)::bigint AS dues_collected_mtk
          FROM keys k JOIN dw.dim_outlet o ON o.outlet_id = k.outlet_id LEFT JOIN dw.dim_geo g ON g.route_id = o.route_id
          LEFT JOIN memo m ON m.outlet_id = k.outlet_id AND m.d IS NOT DISTINCT FROM k.d LEFT JOIN qty q ON q.outlet_id = k.outlet_id AND q.d IS NOT DISTINCT FROM k.d
          LEFT JOIN dues u ON u.outlet_id = k.outlet_id AND u.d IS NOT DISTINCT FROM k.d
         WHERE (CAST(:ocode AS text) IS NULL OR o.outlet_code = :ocode) $status $sub
        """,
        mapOf("ocode" to ctx.query.outlet_code?.takeIf { it.isNotBlank() }) + (if (sub.isEmpty()) emptyMap() else mapOf("subs" to ctx.query.sub_channels.toTypedArray())),
        listOf("memos", "sold_qty_base", "net_mtk", "due_mtk", "dues_collected_mtk"),
    )
}

/** `by-outlet`: sales by outlet for the period. */
object ByOutletReport : ReportHandler {
    override val definition = definition("by-outlet", "By Outlet", "sales", "outlet", OUTLET_COLS, listOf("period", "geo", "outlet_code", "active_status", "sub_channels"))
    override fun spec(ctx: ReportContext) = outletSpec(ctx, false)
}

/** `by-outlet-by-day`: the same by outlet and day. */
object ByOutletByDayReport : ReportHandler {
    override val definition = definition("by-outlet-by-day", "By Outlet By Day", "sales", "outlet x day", listOf(col("business_date", "Date", "date")) + OUTLET_COLS, listOf("period", "geo", "outlet_code", "active_status", "sub_channels"))
    override fun spec(ctx: ReportContext) = outletSpec(ctx, true)
}

/** `online-offline`: memos captured offline versus online per route and period, with the delay until the server received them. */
object OnlineOfflineReport : ReportHandler {
    override val definition = definition(
        "online-offline", "Online / Offline", "ops", "route x period",
        listOf(
            col("route_code", "Route", "string"), col("period", "Period", "string"), col("online_memos", "Online memos", "integer"), col("offline_memos", "Offline memos", "integer"),
            col("offline_pct", "Offline %", "pct", unit = "%"), col("avg_sync_delay_min", "Average sync delay (min)", "decimal", unit = "min"), col("max_sync_delay_min", "Longest sync delay (min)", "decimal", unit = "min"),
        ),
        listOf("period", "date_grouping", "geo"),
    )

    override fun spec(ctx: ReportContext) = SqlSpec(
        """
        SELECT g.route_code, ${periodExpr(ctx, "m.business_date")} AS period, count(*) FILTER (WHERE NOT m.captured_offline)::int AS online_memos,
               count(*) FILTER (WHERE m.captured_offline)::int AS offline_memos, round(100.0 * count(*) FILTER (WHERE m.captured_offline) / count(*), 2) AS offline_pct,
               round((avg(extract(epoch FROM m.received_at - m.committed_at)) FILTER (WHERE m.captured_offline) / 60.0)::numeric, 2) AS avg_sync_delay_min,
               round((max(extract(epoch FROM m.received_at - m.committed_at)) FILTER (WHERE m.captured_offline) / 60.0)::numeric, 2) AS max_sync_delay_min
          FROM dw.fact_memo m JOIN dw.dim_geo g ON g.route_id = m.route_id
         WHERE ${ctx.dateClause("m.business_date")} AND ${ctx.zoneClause("m.zone_id")} AND ${ctx.routeClause("m.route_id")} AND m.status = 'active' AND m.line_count > 0
         GROUP BY 1, 2
        """,
        totalColumns = listOf("online_memos", "offline_memos"),
    )
}

/** `task-planner`: tasks assigned and their state in the period, for the outlets in reach. */
object TaskPlannerReport : ReportHandler {
    override val definition = definition(
        "task-planner", "Task Planner", "field_force", "task",
        listOf(
            col("business_date", "Date", "date"), col("task_type_code", "Type", "string"), col("title", "Task", "string"), col("assignee", "Assignee", "string"),
            col("outlet_code", "Outlet code", "string"), col("outlet_name", "Outlet", "string"), col("due_date", "Due", "date"), col("status", "Status", "string"),
            col("resolved_at", "Resolved at", "timestamp"),
        ),
        listOf("period", "geo", "outlet_code"),
    )

    override fun spec(ctx: ReportContext) = SqlSpec(
        """
        SELECT t.business_date, t.task_type_code, t.title, u.username AS assignee, o.outlet_code, o.outlet_name, t.due_date, t.status,
               CASE WHEN t.status = 'completed' THEN t.status_changed_at END AS resolved_at
          FROM app.task t JOIN dw.dim_outlet o ON o.outlet_id = t.outlet_id LEFT JOIN app.app_user u ON u.id = t.assignee_user_id
         WHERE t.voided_at IS NULL AND ${ctx.dateClause("t.business_date")} AND ${ctx.zoneClause("o.zone_id")} AND ${ctx.routeClause("coalesce(o.route_id, -1)")}
           AND (CAST(:ocode AS text) IS NULL OR o.outlet_code = :ocode)
        """,
        mapOf("ocode" to ctx.query.outlet_code?.takeIf { it.isNotBlank() }),
    )
}

/** `by-route-geo-capture`: outlets per route with and without a captured location (placeholders and provisional pins counted apart). */
object ByRouteGeoCaptureReport : ReportHandler {
    override val definition = definition(
        "by-route-geo-capture", "By-Route Geo Capture", "outlet", "route",
        listOf(
            col("route_code", "Route", "string"), col("route_name", "Route name", "string"), col("outlets", "Outlets", "integer"), col("master_location", "With master location", "integer"),
            col("provisional_location", "Provisional", "integer"), col("placeholder_location", "Placeholder", "integer"), col("no_location", "No location", "integer"),
            col("confirmed", "Confirmed", "integer"), col("captured_pct", "Captured %", "pct", unit = "%"),
        ),
        listOf("geo", "active_status"),
    )

    override fun spec(ctx: ReportContext): SqlSpec {
        val status = when (ctx.query.active_status) { "active" -> "AND o.status = 'active'"; "inactive" -> "AND o.status <> 'active'"; else -> "" }
        return SqlSpec(
            """
            SELECT g.route_code, g.route_name, count(*)::int AS outlets, count(*) FILTER (WHERE o.location_basis = 'master')::int AS master_location,
                   count(*) FILTER (WHERE o.location_basis = 'provisional')::int AS provisional_location, count(*) FILTER (WHERE o.location_basis = 'placeholder')::int AS placeholder_location,
                   count(*) FILTER (WHERE o.location_basis = 'none')::int AS no_location, count(*) FILTER (WHERE o.location_confirmed)::int AS confirmed,
                   round(100.0 * count(*) FILTER (WHERE o.location_basis IN ('master', 'provisional')) / count(*), 2) AS captured_pct
              FROM app.outlet o JOIN dw.dim_geo g ON g.route_id = o.route_id
             WHERE ${ctx.zoneClause("o.zone_id")} AND ${ctx.routeClause("o.route_id")} AND o.merged_into_id IS NULL $status
             GROUP BY 1, 2
            """,
            totalColumns = listOf("outlets", "master_location", "provisional_location", "placeholder_location", "no_location", "confirmed"),
        )
    }
}

/** Every handler registered in this build; the batch rows append theirs (docs/27: no programme reports). */
object ReportHandlers {
    val all: List<ReportHandler> = listOf(RouteMemoReport, StdMemoReport, SrEfficiencyReport, RouteStdReport, RouteBsrCprReport, ByOutletReport, ByOutletByDayReport, OnlineOfflineReport, TaskPlannerReport, ByRouteGeoCaptureReport, MemoNumberGapsReport, SuspiciousLocationReport,
        DataEntryLogReport, FinalSubmitLogReport, FinalSubmitStatusReport, GigoReport, DssReport, DsRrsReport, TsoTopSheetReport, DailyTrackingReport, LeaderboardReport,
        AmoCallReport, SrOutletsReport, DiscountReport, FreeSampleReport, SalesSummaryReport,
        QcReport, SettlementReport, RouteQcReport)
}
