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

/** Every handler registered in this build; the batch rows append theirs (docs/27: no programme reports). */
object ReportHandlers {
    val all: List<ReportHandler> = listOf(RouteMemoReport)
}
