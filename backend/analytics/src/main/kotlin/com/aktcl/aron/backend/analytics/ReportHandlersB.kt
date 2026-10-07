package com.aktcl.aron.backend.analytics

import com.aktcl.aron.contract.Role

/** Report handlers, batch B (N-051) and the AMO app's sales summary (F-API-017b). Programme reports (Target, Astha, gifts, leagues) stay out: docs/27. */

private const val USER_OF_DAY = "coalesce(rd.acting_user_id, rd.assigned_user_id)"

/** `data-entry-log`: per route-day, when the route's bundle was frozen (first download), the login, first and last upload and the number of uploads. */
object DataEntryLogReport : ReportHandler {
    override val definition = definition(
        "data-entry-log", "Data Entry Log", "ops", "route-day",
        listOf(
            col("business_date", "Date", "date"), col("route_code", "Route", "string"), col("username", "SR", "string"), col("bundle_downloaded_at", "Bundle downloaded", "timestamp"),
            col("logged_in_at", "Logged in", "timestamp"), col("in_field_at", "First upload", "timestamp"), col("last_batch_at", "Last upload", "timestamp"), col("synced_at", "Synced", "timestamp"),
            col("uploads", "Uploads", "integer"), col("records", "Records", "integer"),
        ),
        listOf("period", "geo"),
    )

    override fun spec(ctx: ReportContext) = SqlSpec(
        """
        SELECT rd.business_date, g.route_code, u.username, rd.target_frozen_at AS bundle_downloaded_at, rd.logged_in_at, rd.in_field_at, rd.last_batch_at, rd.synced_at,
               (SELECT count(*) FROM app.sync_batch sb WHERE sb.user_id = u.id AND (sb.received_at AT TIME ZONE 'Asia/Dhaka')::date = rd.business_date)::int AS uploads,
               (SELECT coalesce(sum(sb.record_count), 0) FROM app.sync_batch sb WHERE sb.user_id = u.id AND (sb.received_at AT TIME ZONE 'Asia/Dhaka')::date = rd.business_date)::int AS records
          FROM app.route_day rd JOIN dw.dim_geo g ON g.route_id = rd.route_id JOIN app.app_user u ON u.id = $USER_OF_DAY
         WHERE ${ctx.dateClause("rd.business_date")} AND rd.planned AND ${ctx.zoneClause("g.zone_id")} AND ${ctx.routeClause("rd.route_id")}
        """,
        totalColumns = listOf("uploads", "records"),
    )
}

/** `final-submit-log`: every Final Submit with its cycle and the rows that arrived after it. */
object FinalSubmitLogReport : ReportHandler {
    override val definition = definition(
        "final-submit-log", "Final Submit Log", "day_control", "route-day",
        listOf(
            col("business_date", "Date", "date"), col("zone_name", "Zone", "string"), col("route_code", "Route", "string"), col("username", "SR", "string"),
            col("sales_submitted_at", "Sales submitted", "timestamp"), col("final_submitted_at", "Final submitted", "timestamp"), col("submit_cycle", "Cycle", "integer"),
            col("late_rows_after_final", "Late rows", "integer"), col("submit_voided", "Voided", "bool"), col("submit_count_mismatch", "Count mismatch", "bool"),
        ),
        listOf("period", "geo"),
    )

    override fun spec(ctx: ReportContext) = SqlSpec(
        """
        SELECT rd.business_date, g.zone_name, g.route_code, u.username, rd.sales_submitted_at, rd.final_submitted_at, rd.submit_cycle, rd.late_rows_after_final, rd.submit_voided, rd.submit_count_mismatch
          FROM app.route_day rd JOIN dw.dim_geo g ON g.route_id = rd.route_id LEFT JOIN app.app_user u ON u.id = $USER_OF_DAY
         WHERE ${ctx.dateClause("rd.business_date")} AND rd.final_submitted_at IS NOT NULL AND ${ctx.zoneClause("g.zone_id")} AND ${ctx.routeClause("rd.route_id")}
        """,
        totalColumns = listOf("late_rows_after_final"),
    )
}

/** `final-submit-status`: zone-days final-submitted or not, with the number of target routes in each state. */
object FinalSubmitStatusReport : ReportHandler {
    override val definition = definition(
        "final-submit-status", "Final Submit Status", "day_control", "zone-day",
        listOf(
            col("business_date", "Date", "date"), col("zone_name", "Zone", "string"), col("target_routes", "Target routes", "integer"), col("not_started", "Not logged in", "integer"),
            col("in_progress", "In progress", "integer"), col("sales_submitted", "Sales submitted", "integer"), col("final_submitted", "Final submitted", "integer"), col("zone_final", "Zone final", "bool"),
        ),
        listOf("period", "geo"),
    )

    override fun spec(ctx: ReportContext) = SqlSpec(
        """
        SELECT a.business_date, g.zone_name, count(*)::int AS target_routes, count(*) FILTER (WHERE a.day_state = 'not_started')::int AS not_started,
               count(*) FILTER (WHERE a.day_state IN ('logged_in', 'in_field', 'synced', 'submit_pending_rows'))::int AS in_progress,
               count(*) FILTER (WHERE a.day_state = 'sales_submitted')::int AS sales_submitted, count(*) FILTER (WHERE a.day_state = 'final_submitted')::int AS final_submitted,
               bool_and(a.day_state = 'final_submitted') AS zone_final
          FROM dw.agg_daily_route a JOIN dw.dim_geo g ON g.route_id = a.route_id
         WHERE ${ctx.dateClause("a.business_date")} AND a.planned AND NOT a.exception_approved AND ${ctx.zoneClause("a.zone_id")} AND ${ctx.routeClause("a.route_id")}
         GROUP BY a.business_date, g.zone_id, g.zone_name
        """,
        totalColumns = listOf("target_routes", "not_started", "in_progress", "sales_submitted", "final_submitted"),
    )
}

/** `gigo`: attendance check-in and check-out per SR and day with the fix state of each and the hours between them. */
object GigoReport : ReportHandler {
    override val definition = definition(
        "gigo", "GIGO (Attendance)", "field_force", "user x day",
        listOf(
            col("business_date", "Date", "date"), col("username", "SR", "string"), col("route_code", "Route", "string"), col("check_in_at", "Check in", "timestamp"),
            col("check_out_at", "Check out", "timestamp"), col("hours", "Hours", "decimal", unit = "h"), col("check_in_fix", "Check-in fix", "string"), col("check_out_fix", "Check-out fix", "string"),
            col("any_mock", "Mock fix", "bool"), col("address", "Address", "string"),
        ),
        listOf("period", "geo"),
    )

    override fun spec(ctx: ReportContext) = SqlSpec(
        """
        SELECT e.business_date, u.username, g.route_code,
               min(e.captured_at) FILTER (WHERE e.kind = 'check_in') AS check_in_at, max(e.captured_at) FILTER (WHERE e.kind = 'check_out') AS check_out_at,
               round(extract(epoch FROM max(e.captured_at) FILTER (WHERE e.kind = 'check_out') - min(e.captured_at) FILTER (WHERE e.kind = 'check_in'))::numeric / 3600, 2) AS hours,
               (array_agg(e.fix_status ORDER BY e.captured_at) FILTER (WHERE e.kind = 'check_in'))[1] AS check_in_fix,
               (array_agg(e.fix_status ORDER BY e.captured_at DESC) FILTER (WHERE e.kind = 'check_out'))[1] AS check_out_fix,
               bool_or(coalesce(e.fix_is_mock, false)) AS any_mock, (array_agg(e.address_display ORDER BY e.captured_at) FILTER (WHERE e.kind = 'check_in'))[1] AS address
          FROM app.attendance_event e JOIN app.app_user u ON u.id = e.user_id LEFT JOIN dw.dim_geo g ON g.route_id = e.route_id
         WHERE e.voided_at IS NULL AND ${ctx.dateClause("e.business_date")} AND ${ctx.zoneClause("g.zone_id")} AND ${ctx.routeClause("coalesce(e.route_id, -1)")}
         GROUP BY e.business_date, u.id, u.username, g.route_code
        """,
    )
}

/** `dss`: the daily sales summary per zone (and period). */
object DssReport : ReportHandler {
    override val definition = definition(
        "dss", "DSS (Daily Sales Summary)", "sales", "zone x period",
        listOf(
            col("zone_name", "Zone", "string"), col("period", "Period", "string"), col("target_routes", "Target routes", "integer"), col("logged_in_routes", "Logged in", "integer"),
            col("sales_submitted_routes", "Sales submitted", "integer"), col("visits", "Visits", "integer"), col("successful_calls", "Successful calls", "integer"),
            col("memos", "Memos", "integer"), col("gross_mtk", "Gross", "mtk", unit = "mtk"), col("net_mtk", "Net", "mtk", unit = "mtk"), col("dues_collected_mtk", "Dues collected", "mtk", unit = "mtk"),
        ),
        listOf("period", "date_grouping", "geo"),
    )

    override fun spec(ctx: ReportContext) = SqlSpec(
        """
        SELECT g.zone_name, ${periodExpr(ctx, "a.business_date")} AS period, sum(a.target_routes)::int AS target_routes, sum(a.logged_in_routes)::int AS logged_in_routes,
               sum(a.sales_submitted_routes)::int AS sales_submitted_routes, sum(a.visits)::int AS visits, sum(a.successful_calls)::int AS successful_calls, sum(a.active_memo_count)::int AS memos,
               sum(a.gross_mtk)::bigint AS gross_mtk, sum(a.net_mtk)::bigint AS net_mtk, sum(a.dues_collected_mtk)::bigint AS dues_collected_mtk
          FROM dw.agg_daily_zone a JOIN (SELECT DISTINCT zone_id, zone_name FROM dw.dim_geo) g ON g.zone_id = a.zone_id
         WHERE ${ctx.dateClause("a.business_date")} AND ${ctx.zoneClause("a.zone_id")}
         GROUP BY 1, 2
        """,
        totalColumns = listOf("target_routes", "logged_in_routes", "sales_submitted_routes", "visits", "successful_calls", "memos", "gross_mtk", "net_mtk", "dues_collected_mtk"),
    )
}

/** `ds-rrs`: stock lifted, retail sold, free and returned by SKU and zone, with the closing stock of the SRs (distributor stock needs the indent portal, Phase 2). */
object DsRrsReport : ReportHandler {
    override val definition = definition(
        "ds-rrs", "DS-RRS", "sales", "zone x SKU",
        listOf(
            col("zone_name", "Zone", "string"), col("sku_code", "SKU", "string"), col("sku_name", "SKU name", "string"), col("base_unit", "Unit", "string"), col("issued_qty_base", "Stock lifted", "integer"),
            col("sold_qty_base", "Retail sold", "integer"), col("free_qty_base", "Free", "integer"), col("returned_qty_base", "Returned", "integer"), col("closing_qty_base", "Closing stock", "integer"),
        ),
        listOf("period", "geo", "category", "product_type", "products"),
    )

    override fun spec(ctx: ReportContext): SqlSpec {
        val (prod, binds) = productClause(ctx, "p")
        return SqlSpec(
            """
            SELECT g.zone_name, p.sku_code, p.short_name AS sku_name, p.base_unit, sum(a.issued_qty_base)::bigint AS issued_qty_base, sum(a.sold_qty_base)::bigint AS sold_qty_base,
                   sum(a.free_qty_base)::bigint AS free_qty_base, sum(a.returned_qty_base)::bigint AS returned_qty_base,
                   sum(a.issued_qty_base - a.sold_qty_base - a.free_qty_base - a.returned_qty_base)::bigint AS closing_qty_base
              FROM dw.agg_daily_route_sku a JOIN dw.dim_geo g ON g.route_id = a.route_id JOIN dw.dim_product p ON p.sku_id = a.sku_id
             WHERE ${ctx.dateClause("a.business_date")} AND ${ctx.zoneClause("g.zone_id")} AND ${ctx.routeClause("a.route_id")} AND $prod
             GROUP BY 1, 2, 3, 4
            """,
            binds, listOf("issued_qty_base", "sold_qty_base", "free_qty_base", "returned_qty_base", "closing_qty_base"),
        )
    }
}

/** `tso-top-sheet`: the TSO's sheet, one row per route-day of the zone. */
object TsoTopSheetReport : ReportHandler {
    override val definition = definition(
        "tso-top-sheet", "TSO Top Sheet", "day_control", "route-day",
        listOf(
            col("business_date", "Date", "date"), col("zone_name", "Zone", "string"), col("route_code", "Route", "string"), col("username", "SR", "string"), col("state", "State", "string"),
            col("target_outlets", "Target outlets", "integer"), col("visited_outlets", "Visited", "integer"), col("successful_calls", "Successful calls", "integer"), col("strike_rate_pct", "Strike rate %", "pct", unit = "%"),
            col("memos", "Memos", "integer"), col("net_mtk", "Net", "mtk", unit = "mtk"), col("geo_valid_pct", "Geo valid %", "pct", unit = "%"),
        ),
        listOf("period", "geo"),
    )

    override fun spec(ctx: ReportContext) = SqlSpec(
        """
        SELECT a.business_date, g.zone_name, g.route_code, u.username, a.day_state AS state, a.target_outlets, a.visited_outlets, a.successful_calls,
               round(100.0 * a.successful_calls / nullif(a.target_outlets, 0), 2) AS strike_rate_pct, a.active_memo_count AS memos, a.net_mtk,
               round(100.0 * a.geo_valid_visits / nullif(a.visits, 0), 2) AS geo_valid_pct
          FROM dw.agg_daily_route a JOIN dw.dim_geo g ON g.route_id = a.route_id
          LEFT JOIN app.route_day rd ON rd.route_id = a.route_id AND rd.business_date = a.business_date LEFT JOIN app.app_user u ON u.id = $USER_OF_DAY
         WHERE ${ctx.dateClause("a.business_date")} AND (a.planned OR a.exception_approved) AND ${ctx.zoneClause("a.zone_id")} AND ${ctx.routeClause("a.route_id")}
        """,
        totalColumns = listOf("target_outlets", "visited_outlets", "successful_calls", "memos", "net_mtk"),
    )
}

/** `daily-tracking`: the Daily Tracking buckets as a report (same rule as the dashboard: call productivity = successful calls / target outlets). */
object DailyTrackingReport : ReportHandler {
    override val definition = definition(
        "daily-tracking", "Daily Tracking", "day_control", "route-day",
        listOf(
            col("business_date", "Date", "date"), col("zone_name", "Zone", "string"), col("route_code", "Route", "string"), col("username", "SR", "string"), col("state", "State", "string"),
            col("target_outlets", "Target outlets", "integer"), col("successful_calls", "Successful calls", "integer"), col("achievement_pct", "Achievement %", "pct", unit = "%"), col("bucket", "Bucket", "string"),
        ),
        listOf("period", "geo"),
    )

    override fun spec(ctx: ReportContext) = SqlSpec(
        """
        WITH r AS (
          SELECT a.business_date, g.zone_name, g.route_code, u.username, a.day_state AS state, a.target_outlets, a.successful_calls, a.exception_approved,
                 round(100.0 * a.successful_calls / nullif(a.target_outlets, 0), 2) AS achievement_pct
            FROM dw.agg_daily_route a JOIN dw.dim_geo g ON g.route_id = a.route_id
            LEFT JOIN app.route_day rd ON rd.route_id = a.route_id AND rd.business_date = a.business_date LEFT JOIN app.app_user u ON u.id = $USER_OF_DAY
           WHERE ${ctx.dateClause("a.business_date")} AND (a.planned OR a.exception_approved) AND ${ctx.zoneClause("a.zone_id")} AND ${ctx.routeClause("a.route_id")})
        SELECT business_date, zone_name, route_code, username, state, target_outlets, successful_calls, achievement_pct,
               CASE WHEN exception_approved THEN 'exception' WHEN state = 'not_started' THEN 'not_logged_in' WHEN achievement_pct IS NULL THEN 'below_80'
                    WHEN achievement_pct >= 100 THEN 'ge_100' WHEN achievement_pct >= 90 THEN 'from_90' WHEN achievement_pct >= 80 THEN 'from_80' ELSE 'below_80' END AS bucket
          FROM r
        """,
    )
}

/**
 * `leaderboard`: ranking by net sales (the target-based achievement of the paper report belongs to the deferred target programme, docs/27).
 * `location` = user (default, an SR), route or zone chooses what is ranked.
 */
object LeaderboardReport : ReportHandler {
    override val definition = definition(
        "leaderboard", "Leaderboard", "field_force", "SR, route or zone",
        listOf(
            col("rank", "Rank", "integer"), col("name", "Name", "string"), col("net_mtk", "Net", "mtk", unit = "mtk"), col("memos", "Memos", "integer"),
            col("successful_calls", "Successful calls", "integer"), col("strike_rate_pct", "Strike rate %", "pct", unit = "%"),
        ),
        listOf("period", "geo", "location"),
    )

    override fun spec(ctx: ReportContext): SqlSpec {
        val key = when (ctx.query.location) { "zone" -> "g.zone_name"; "route" -> "g.route_code"; else -> "u.username" }
        val join = if (ctx.query.location == "zone" || ctx.query.location == "route") "" else "JOIN app.route_day rd ON rd.route_id = a.route_id AND rd.business_date = a.business_date JOIN app.app_user u ON u.id = $USER_OF_DAY"
        return SqlSpec(
            """
            SELECT rank() OVER (ORDER BY sum(a.net_mtk) DESC)::int AS rank, $key AS name, sum(a.net_mtk)::bigint AS net_mtk, sum(a.active_memo_count)::int AS memos,
                   sum(a.successful_calls)::int AS successful_calls,
                   round(100.0 * sum(a.successful_calls) / nullif(sum(a.target_outlets) FILTER (WHERE a.planned AND NOT a.exception_approved), 0), 2) AS strike_rate_pct
              FROM dw.agg_daily_route a JOIN dw.dim_geo g ON g.route_id = a.route_id $join
             WHERE ${ctx.dateClause("a.business_date")} AND ${ctx.zoneClause("a.zone_id")} AND ${ctx.routeClause("a.route_id")}
             GROUP BY $key
            """,
        )
    }
}

/** `amo-call`: the AMO's control and joint calls with the assessment score. */
object AmoCallReport : ReportHandler {
    override val definition = definition(
        "amo-call", "AMO Call Report", "field_force", "AMO call",
        listOf(
            col("business_date", "Date", "date"), col("amo", "AMO", "string"), col("route_code", "Route", "string"), col("outlet_code", "Outlet code", "string"), col("outlet_name", "Outlet", "string"),
            col("visit_kind", "Call kind", "string"), col("opened_at", "Time", "timestamp"), col("assessed_sr", "Assessed SR", "string"), col("total_score", "Score", "integer"), col("max_score", "Maximum", "integer"),
            col("score_pct", "Score %", "pct", unit = "%"),
        ),
        listOf("period", "geo", "field_force_type"),
    )

    override fun spec(ctx: ReportContext) = SqlSpec(
        """
        SELECT v.business_date, am.username AS amo, g.route_code, o.outlet_code, o.outlet_name, v.visit_kind, v.opened_at, sr.username AS assessed_sr, ca.total_score, ca.max_score,
               round(100.0 * ca.total_score / nullif(ca.max_score, 0), 2) AS score_pct
          FROM dw.fact_visit v JOIN app.app_user am ON am.id = v.user_id JOIN dw.dim_outlet o ON o.outlet_id = v.outlet_id LEFT JOIN dw.dim_geo g ON g.route_id = v.route_id
          LEFT JOIN app.call_assessment ca ON ca.visit_client_uuid = v.visit_client_uuid AND ca.voided_at IS NULL LEFT JOIN app.app_user sr ON sr.id = ca.assessed_user_id
         WHERE ${ctx.dateClause("v.business_date")} AND v.visit_kind IN ('amo_control_call', 'amo_joint_call') AND NOT v.voided AND ${ctx.zoneClause("v.zone_id")} AND ${ctx.routeClause("coalesce(v.route_id, -1)")}
        """,
    )
}

/** `sr-outlets`: the outlets of the routes in scope with the SR on the route, the last visit and the outstanding dues at the end of the period. */
object SrOutletsReport : ReportHandler {
    override val definition = definition(
        "sr-outlets", "SR Outlets", "outlet", "outlet",
        listOf(
            col("route_code", "Route", "string"), col("sr", "SR", "string"), col("outlet_code", "Outlet code", "string"), col("outlet_name", "Outlet", "string"), col("channel", "Channel", "string"),
            col("status", "Status", "string"), col("last_visit_date", "Last visit", "date"), col("outstanding_mtk", "Outstanding dues", "mtk", unit = "mtk"),
        ),
        listOf("period", "geo", "outlet_code", "active_status"),
    )

    override fun spec(ctx: ReportContext): SqlSpec {
        val status = when (ctx.query.active_status) { "active" -> "AND o.status = 'active'"; "inactive" -> "AND o.status <> 'active'"; else -> "" }
        return SqlSpec(
            """
            SELECT g.route_code, (SELECT string_agg(DISTINCT u.username, ', ') FROM app.route_assignment ra JOIN app.app_user u ON u.id = ra.user_id
                                   WHERE ra.route_id = o.route_id AND ra.valid_from <= :to AND (ra.valid_to IS NULL OR ra.valid_to >= :to) AND ra.ended_at IS NULL) AS sr,
                   o.outlet_code, o.outlet_name, o.channel, o.status,
                   (SELECT max(v.business_date) FROM app.visit v WHERE v.outlet_id = o.outlet_id AND v.business_date <= :to AND v.visit_kind = 'sr_call' AND v.voided_at IS NULL) AS last_visit_date,
                   (SELECT coalesce(sum(l.amount_mtk), 0) FROM app.due_ledger l WHERE l.outlet_id = o.outlet_id AND l.business_date <= :to)::bigint AS outstanding_mtk
              FROM dw.dim_outlet o JOIN dw.dim_geo g ON g.route_id = o.route_id
             WHERE ${ctx.zoneClause("g.zone_id")} AND ${ctx.routeClause("o.route_id")} AND (CAST(:ocode AS text) IS NULL OR o.outlet_code = :ocode) $status
            """,
            mapOf("ocode" to ctx.query.outlet_code?.takeIf { it.isNotBlank() }),
            listOf("outstanding_mtk"),
        )
    }
}

/** `discount`: discount lines by kind, offer and SKU. Offer and DRP programmes are deferred (docs/27), so this lists nothing until a programme writes lines. */
object DiscountReport : ReportHandler {
    override val definition = definition(
        "discount", "Discount Report", "sales", "memo discount line",
        listOf(
            col("business_date", "Date", "date"), col("memo_no", "Memo no", "string"), col("route_code", "Route", "string"), col("kind", "Kind", "string"), col("offer_id", "Offer", "integer"),
            col("sku_code", "SKU", "string"), col("qty_base", "Quantity", "integer"), col("value_mtk", "Value", "mtk", unit = "mtk"),
        ),
        listOf("period", "geo", "category", "product_type", "products"),
    )

    override fun spec(ctx: ReportContext) = SqlSpec(
        """
        SELECT d.business_date, m.memo_no, g.route_code, d.kind, d.offer_id, s.code AS sku_code, d.qty_base, d.value_mtk
          FROM app.memo_discount d JOIN dw.fact_memo m ON m.memo_client_uuid = d.memo_client_uuid AND m.business_date = d.business_date JOIN dw.dim_geo g ON g.route_id = m.route_id
          LEFT JOIN app.sku s ON s.id = d.sku_id
         WHERE ${ctx.dateClause("d.business_date")} AND d.voided_at IS NULL AND m.status = 'active' AND m.line_count > 0 AND ${ctx.zoneClause("m.zone_id")} AND ${ctx.routeClause("m.route_id")}
        """,
        totalColumns = listOf("qty_base", "value_mtk"),
    )
}

/** `free-sample`: free-sample quantities (line_kind free_sample) by route and SKU. */
object FreeSampleReport : ReportHandler {
    override val definition = definition(
        "free-sample", "Free Sample", "sales", "route x SKU",
        listOf(
            col("route_code", "Route", "string"), col("route_name", "Route name", "string"), col("sku_code", "SKU", "string"), col("sku_name", "SKU name", "string"),
            col("base_unit", "Unit", "string"), col("qty_base", "Free sample quantity", "integer"), col("memos", "Memos", "integer"),
        ),
        listOf("period", "geo", "category", "product_type", "products"),
    )

    override fun spec(ctx: ReportContext): SqlSpec {
        val (prod, binds) = productClause(ctx, "p")
        return SqlSpec(
            """
            SELECT g.route_code, g.route_name, p.sku_code, p.short_name AS sku_name, p.base_unit, sum(ml.qty_base)::bigint AS qty_base, count(DISTINCT m.memo_client_uuid)::int AS memos
              FROM app.memo_line ml JOIN dw.fact_memo m ON m.memo_client_uuid = ml.memo_client_uuid AND m.business_date = ml.business_date
              JOIN dw.dim_geo g ON g.route_id = m.route_id JOIN dw.dim_product p ON p.sku_id = ml.sku_id
             WHERE ${ctx.dateClause("ml.business_date")} AND ml.line_kind = 'free_sample' AND ml.voided_at IS NULL AND m.status = 'active' AND m.line_count > 0
               AND ${ctx.zoneClause("m.zone_id")} AND ${ctx.routeClause("m.route_id")} AND $prod
             GROUP BY 1, 2, 3, 4, 5
            """,
            binds, listOf("qty_base"),
        )
    }
}

/** `sales-summary` (F-API-017b): the AMO app's Sales Summary Up To Now: a card per route (CPR, memos, net) with its brand table (memos with the brand, BSR). */
object SalesSummaryReport : ReportHandler {
    override val roles = WEB_ROLES + Role.AMO
    override val definition = definition(
        "sales-summary", "Sales Summary Up To Now", "sales", "route x brand",
        listOf(
            col("route_code", "Route", "string"), col("route_name", "Route name", "string"), col("target_outlets", "Target outlets", "integer"), col("successful_calls", "Successful calls", "integer"),
            col("cpr_pct", "CPR %", "pct", unit = "%"), col("memos", "Memos", "integer"), col("net_mtk", "Net", "mtk", unit = "mtk"), col("brand_name", "Brand", "string"),
            col("brand_memos", "Memos with brand", "integer"), col("bsr_pct", "BSR %", "pct", unit = "%"),
        ),
        listOf("period", "geo"),
    )

    override fun spec(ctx: ReportContext) = SqlSpec(
        """
        WITH rt AS (SELECT route_id, sum(target_outlets) FILTER (WHERE planned AND NOT exception_approved) AS t, sum(successful_calls) AS sc, sum(active_memo_count) AS memos, sum(net_mtk) AS net
                      FROM dw.agg_daily_route WHERE ${ctx.dateClause("business_date")} AND ${ctx.zoneClause("zone_id")} AND ${ctx.routeClause("route_id")} GROUP BY route_id),
        br AS (SELECT route_id, brand_id, sum(memo_count) AS bm FROM dw.agg_daily_route_brand WHERE ${ctx.dateClause("business_date")} GROUP BY 1, 2)
        SELECT g.route_code, g.route_name, coalesce(rt.t, 0)::int AS target_outlets, rt.sc::int AS successful_calls, round(100.0 * rt.sc / nullif(rt.t, 0), 2) AS cpr_pct,
               rt.memos::int AS memos, rt.net::bigint AS net_mtk, p.brand_name, br.bm::int AS brand_memos, round(100.0 * br.bm / nullif(rt.memos, 0), 2) AS bsr_pct
          FROM rt JOIN dw.dim_geo g ON g.route_id = rt.route_id LEFT JOIN br ON br.route_id = rt.route_id
          LEFT JOIN LATERAL (SELECT brand_name FROM dw.dim_product WHERE brand_id = br.brand_id ORDER BY brand_name LIMIT 1) p ON true
        """,
    )
}
