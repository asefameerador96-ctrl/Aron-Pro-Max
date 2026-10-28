package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.rules.BusinessDate
import kotlinx.datetime.toJavaLocalDate
import org.slf4j.LoggerFactory
import java.time.LocalDate

/**
 * The nightly reconcile (data-architecture review High 2, docs/16 s8.6): for the last [windowDays] closed business dates it recomputes the figures of
 * every route-day from the source rows (`app.memo`, `app.visit`) and of every zone-day from its route-days, compares them with the aggregates and
 * writes `dw.agg_reconcile`: ONE open finding per (date, grain, key, metric); a finding found again bumps `consecutive_runs`, a difference that is
 * gone is closed (`resolved_at`). A finding re-enqueues its key for a rebuild (reason `reconcile`, so the restatement log says why) and, once it
 * survives a second run, counts as an alert (logged at ERROR). Keys that are queued for a rebuild right now are skipped: they are about to change.
 * Metrics: `memo_count`, `gross_mtk`, `net_mtk`, `visit_count` (a quantity is never summed across categories, so `qty_base` is not compared).
 */
class Reconciler(private val db: Database, private val clock: AronClock = AronClock.SYSTEM) {
    private val log = LoggerFactory.getLogger("aron.analytics.reconcile")

    data class Result(val checked: Int, val findings: Int, val newFindings: Int, val reEnqueued: Int, val resolved: Int, val alerts: Int)

    private data class Diff(val date: LocalDate, val grain: String, val key: Long, val metric: String, val source: Long, val agg: Long)

    fun run(windowDays: Int = 7): Result {
        val today = BusinessDate.of(clock.now().toEpochMilli()).toJavaLocalDate()
        val from = today.minusDays(windowDays.toLong()); val to = today.minusDays(1)
        val now = clock.now()
        return db.jdbi.inTransaction<Result, Exception> { h ->
            val diffs = routeDiffs(h, from, to) + zoneDiffs(h, from, to)
            var neu = 0; var enq = 0; var alerts = 0
            for (d in diffs) {
                val runs = h.createQuery(
                    """
                    INSERT INTO dw.agg_reconcile (run_at, business_date, grain, key_id, metric, source_value, agg_value, re_enqueued)
                    VALUES (:now, :d, :g, :k, :m, :s, :a, true)
                    ON CONFLICT (business_date, grain, key_id, metric) WHERE resolved_at IS NULL
                    DO UPDATE SET run_at = :now, source_value = excluded.source_value, agg_value = excluded.agg_value,
                                  consecutive_runs = dw.agg_reconcile.consecutive_runs + 1, re_enqueued = true
                    RETURNING consecutive_runs
                    """,
                ).bind("now", now).bind("d", d.date).bind("g", d.grain).bind("k", d.key).bind("m", d.metric).bind("s", d.source).bind("a", d.agg)
                    .mapTo(Int::class.java).one()
                if (runs == 1) neu++
                if (runs >= 2) { alerts++; log.error("reconcile: {} {} key {} on {} differs for {} runs (source {}, aggregate {})", d.grain, d.metric, d.key, d.date, runs, d.source, d.agg) }
                h.execute("SELECT app.mark_dirty(?, ?, ?, 'reconcile')", if (d.grain == "route") "route_day_agg" else "zone_day_agg", d.key, d.date); enq++
            }
            // Close the findings whose difference is gone (a key still queued stays open: its rebuild has not run yet).
            val still = diffs.map { Triple(it.date, it.grain, it.key) to it.metric }.toSet()
            val queued = h.createQuery("SELECT kind, subject_id, business_date FROM app.dirty_key WHERE kind IN ('route_day_agg', 'zone_day_agg') AND dead_at IS NULL AND business_date BETWEEN :f AND :t")
                .bind("f", from).bind("t", to).map { rs, _ -> Triple(rs.getObject(3, LocalDate::class.java), if (rs.getString(1) == "route_day_agg") "route" else "zone", rs.getLong(2)) }.list().toSet()
            val open = h.createQuery("SELECT id, business_date, grain, key_id, metric FROM dw.agg_reconcile WHERE resolved_at IS NULL AND business_date BETWEEN :f AND :t")
                .bind("f", from).bind("t", to).map { rs, _ -> Triple(rs.getLong(1), Triple(rs.getObject(2, LocalDate::class.java), rs.getString(3), rs.getLong(4)), rs.getString(5)) }.list()
            var resolved = 0
            for ((id, key, metric) in open) if ((key to metric) !in still && key !in queued) {
                resolved += h.createUpdate("UPDATE dw.agg_reconcile SET resolved_at = :now WHERE id = :id AND resolved_at IS NULL").bind("now", now).bind("id", id).execute()
            }
            val checked = h.createQuery("SELECT (SELECT count(*) FROM dw.agg_daily_route WHERE business_date BETWEEN :f AND :t) + (SELECT count(*) FROM dw.agg_daily_zone WHERE business_date BETWEEN :f AND :t)")
                .bind("f", from).bind("t", to).mapTo(Int::class.java).one()
            Result(checked, diffs.size, neu, enq, resolved, alerts)
        }
    }

    /** Route-days: source memos and visits against `dw.agg_daily_route`. */
    private fun routeDiffs(h: org.jdbi.v3.core.Handle, from: LocalDate, to: LocalDate): List<Diff> = h.createQuery(
        """
        WITH src AS (SELECT m.route_id, m.business_date, count(*) AS memos, coalesce(sum(m.gross_mtk), 0) AS gross, coalesce(sum(m.net_mtk), 0) AS net
                       FROM app.memo m WHERE m.business_date BETWEEN :f AND :t AND m.route_id IS NOT NULL
                        AND m.status = 'active' AND m.line_count > 0 AND m.voided_at IS NULL GROUP BY 1, 2),
        vis AS (SELECT route_id, business_date, count(*) AS n FROM app.visit WHERE business_date BETWEEN :f AND :t AND route_id IS NOT NULL
                   AND visit_kind = 'sr_call' AND voided_at IS NULL GROUP BY 1, 2),
        keys AS (SELECT route_id, business_date FROM src UNION SELECT route_id, business_date FROM vis
                 UNION SELECT route_id, business_date FROM dw.agg_daily_route WHERE business_date BETWEEN :f AND :t)
        SELECT k.business_date, k.route_id, v.metric, v.sv, v.av
          FROM keys k LEFT JOIN src s USING (route_id, business_date) LEFT JOIN vis w USING (route_id, business_date)
          LEFT JOIN dw.agg_daily_route a USING (route_id, business_date)
          CROSS JOIN LATERAL (VALUES ('memo_count', coalesce(s.memos, 0), coalesce(a.active_memo_count, 0)::bigint),
                                     ('gross_mtk', coalesce(s.gross, 0), coalesce(a.gross_mtk, 0)),
                                     ('net_mtk', coalesce(s.net, 0), coalesce(a.net_mtk, 0)),
                                     ('visit_count', coalesce(w.n, 0), coalesce(a.visits, 0)::bigint)) v(metric, sv, av)
         WHERE v.sv <> v.av
           AND NOT EXISTS (SELECT 1 FROM app.dirty_key d WHERE d.kind = 'route_day_agg' AND d.subject_id = k.route_id AND d.business_date = k.business_date AND d.dead_at IS NULL)
        """,
    ).bind("f", from).bind("t", to).map { rs, _ -> Diff(rs.getObject(1, LocalDate::class.java), "route", rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getLong(5)) }.list()

    /** Zone-days: the sum of the zone's route-days against `dw.agg_daily_zone`. */
    private fun zoneDiffs(h: org.jdbi.v3.core.Handle, from: LocalDate, to: LocalDate): List<Diff> = h.createQuery(
        """
        WITH src AS (SELECT zone_id, business_date, sum(active_memo_count) AS memos, sum(gross_mtk) AS gross, sum(net_mtk) AS net, sum(visits) AS vis
                       FROM dw.agg_daily_route WHERE business_date BETWEEN :f AND :t GROUP BY 1, 2),
        keys AS (SELECT zone_id, business_date FROM src UNION SELECT zone_id, business_date FROM dw.agg_daily_zone WHERE business_date BETWEEN :f AND :t)
        SELECT k.business_date, k.zone_id, v.metric, v.sv, v.av
          FROM keys k LEFT JOIN src s USING (zone_id, business_date) LEFT JOIN dw.agg_daily_zone a USING (zone_id, business_date)
          CROSS JOIN LATERAL (VALUES ('memo_count', coalesce(s.memos, 0)::bigint, coalesce(a.active_memo_count, 0)::bigint),
                                     ('gross_mtk', coalesce(s.gross, 0)::bigint, coalesce(a.gross_mtk, 0)),
                                     ('net_mtk', coalesce(s.net, 0)::bigint, coalesce(a.net_mtk, 0)),
                                     ('visit_count', coalesce(s.vis, 0)::bigint, coalesce(a.visits, 0)::bigint)) v(metric, sv, av)
         WHERE v.sv <> v.av
           AND NOT EXISTS (SELECT 1 FROM app.dirty_key d WHERE d.kind = 'zone_day_agg' AND d.subject_id = k.zone_id AND d.business_date = k.business_date AND d.dead_at IS NULL)
           AND NOT EXISTS (SELECT 1 FROM app.dirty_key d JOIN dw.agg_daily_route r ON r.route_id = d.subject_id AND r.business_date = d.business_date
                            WHERE d.kind = 'route_day_agg' AND d.dead_at IS NULL AND d.business_date = k.business_date AND r.zone_id = k.zone_id)
        """,
    ).bind("f", from).bind("t", to).map { rs, _ -> Diff(rs.getObject(1, LocalDate::class.java), "zone", rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getLong(5)) }.list()
}
