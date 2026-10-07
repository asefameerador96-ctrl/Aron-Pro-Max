package com.aktcl.aron.backend.analytics

/** Report handlers, batch C (N-052). No Target, Astha, Campaign Gift, Diamond League or Superstar handlers: docs/27. */

/**
 * `memo-number-gaps` (F-SYS-069, F-API-017a): numbers missing from a user's series of a business date (docs/24 s7.5). Within each device block
 * (`bind_ordinal x 500 + n`, or the overflow range from 5001) every number from the block's first to its highest seen must be a memo of any
 * status (voided and superseded numbers are never reused, so they are not gaps) or a `sale_abort` record that burned it. What is left is
 * `missing`; a number a `sale_abort` explains is `sale_aborted`. Computed on read from the stored records of the caller's scope.
 */
object MemoNumberGapsReport : ReportHandler {
    override val definition = definition(
        "memo-number-gaps", "Memo Number Gaps", "ops", "user x day x number",
        listOf(
            col("username", "User", "string"), col("business_date", "Date", "date"), col("device_ordinal", "Phone block", "integer"), col("seq", "Number", "integer"),
            col("memo_no", "Memo no", "string"), col("explanation", "Explanation", "string"), col("abort_reason", "Abort reason", "string"),
        ),
        listOf("period", "geo"),
    )

    override fun spec(ctx: ReportContext) = SqlSpec(
        """
        WITH seen AS (
          SELECT m.user_id, m.business_date, split_part(m.memo_no, '-', 3)::int AS seq, 'memo' AS src, NULL::text AS reason FROM app.memo m JOIN dw.dim_geo g ON g.route_id = m.route_id
           WHERE ${ctx.dateClause("m.business_date")} AND ${ctx.zoneClause("g.zone_id")} AND ${ctx.routeClause("m.route_id")} AND m.voided_at IS NULL
          UNION ALL
          SELECT a.user_id, a.business_date, split_part(a.memo_no, '-', 3)::int, 'abort', a.reason FROM app.sale_abort a JOIN dw.dim_geo g ON g.route_id = a.route_id
           WHERE ${ctx.dateClause("a.business_date")} AND ${ctx.zoneClause("g.zone_id")} AND ${ctx.routeClause("a.route_id")} AND a.voided_at IS NULL AND a.memo_no IS NOT NULL),
        blocked AS (SELECT *, CASE WHEN seq <= 5000 THEN ((seq - 1) / 500) * 500 ELSE 5000 + ((seq - 5001) / 1000) * 1000 END AS base FROM seen),
        tops AS (SELECT user_id, business_date, base, max(seq) AS top FROM blocked GROUP BY 1, 2, 3),
        wanted AS (SELECT t.user_id, t.business_date, t.base, s AS seq FROM tops t CROSS JOIN LATERAL generate_series(t.base + 1, t.top) AS s)
        SELECT u.username, w.business_date, CASE WHEN w.base < 5000 THEN w.base / 500 ELSE (w.base - 5000) / 1000 END AS device_ordinal, w.seq,
               u.username || '-' || to_char(w.business_date, 'YYMMDD') || '-' || lpad(w.seq::text, 3, '0') AS memo_no,
               CASE WHEN ab.seq IS NOT NULL THEN 'sale_aborted' ELSE 'missing' END AS explanation, ab.reason AS abort_reason
          FROM wanted w JOIN app.app_user u ON u.id = w.user_id
          LEFT JOIN LATERAL (SELECT b.seq, b.reason FROM blocked b WHERE b.user_id = w.user_id AND b.business_date = w.business_date AND b.seq = w.seq AND b.src = 'abort' LIMIT 1) ab ON true
         WHERE NOT EXISTS (SELECT 1 FROM blocked b WHERE b.user_id = w.user_id AND b.business_date = w.business_date AND b.seq = w.seq AND b.src = 'memo')
        """,
    )
}

/** `suspicious-location` (F-API-034): risk signals in reach with their review events and the user-day total that decides "suspicious" (s11.4). */
object SuspiciousLocationReport : ReportHandler {
    override val definition = definition(
        "suspicious-location", "Suspicious Locations", "geo", "risk signal",
        listOf(
            col("business_date", "Date", "date"), col("username", "User", "string"), col("route_code", "Route", "string"), col("code", "Signal", "string"),
            col("severity", "Severity", "integer"), col("score", "Score", "decimal"), col("status", "Status", "string"), col("subject_type", "Subject", "string"),
            col("outlet_code", "Outlet", "string"), col("user_day_score", "User-day score", "decimal"), col("suspicious", "Suspicious user-day", "bool"),
            col("reviews", "Reviews", "integer"), col("last_review_action", "Last review", "string"), col("last_review_note", "Review note", "string"),
            col("last_reviewed_at", "Reviewed at", "timestamp"),
        ),
        listOf("period", "geo"),
    )

    // Signals are scoped by their own zone/route; a user-level signal without either is shown to national callers only.
    override fun spec(ctx: ReportContext) = SqlSpec(
        """
        WITH sig AS (
          SELECT s.*, sum(s.score) FILTER (WHERE s.status IN ('open', 'confirmed')) OVER (PARTITION BY s.user_id, s.business_date) AS day_score
            FROM app.risk_signal s WHERE ${ctx.dateClause("s.business_date")})
        SELECT s.business_date, u.username, g.route_code, s.code, s.severity::int AS severity, s.score, s.status, s.subject_type, o.outlet_code,
               coalesce(s.day_score, 0) AS user_day_score, coalesce(s.day_score, 0) >= :threshold AS suspicious,
               (SELECT count(*) FROM app.risk_signal_review r WHERE r.signal_id = s.id AND r.voided_at IS NULL)::int AS reviews,
               lr.action AS last_review_action, lr.note AS last_review_note, lr.captured_at AS last_reviewed_at
          FROM sig s LEFT JOIN app.app_user u ON u.id = s.user_id LEFT JOIN dw.dim_geo g ON g.route_id = s.route_id
          LEFT JOIN app.visit v ON s.subject_type = 'visit' AND v.client_uuid::text = s.subject_id AND v.business_date = s.business_date LEFT JOIN dw.dim_outlet o ON o.outlet_id = v.outlet_id
          LEFT JOIN LATERAL (SELECT r.action, r.note, r.captured_at FROM app.risk_signal_review r WHERE r.signal_id = s.id AND r.voided_at IS NULL ORDER BY r.captured_at DESC, r.id DESC LIMIT 1) lr ON true
         WHERE ${ctx.zoneClause("coalesce(s.zone_id, g.zone_id)")} AND ${ctx.routeClause("coalesce(s.route_id, -1)")}
        """,
        mapOf("threshold" to Aggregator.DEFAULT_SUSPICIOUS_THRESHOLD),
    )
}
