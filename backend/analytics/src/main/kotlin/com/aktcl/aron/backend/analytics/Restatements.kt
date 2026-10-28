package com.aktcl.aron.backend.analytics

import org.jdbi.v3.core.Handle
import java.time.LocalDate

/**
 * The restatement log (data-architecture review High 2, docs/16 s8.6-s8.7): when a rebuild changes the figures of a route-day or zone-day of a
 * CLOSED business date (before today, Asia/Dhaka), one append-only row in `dw.agg_restatement_log` keeps the old and the new figures, the delta
 * and why. Figures are counts and integer milli-taka only (never a quantity summed across categories, qa-1). Grains written: `route`, `zone`.
 */
internal object Restatements {
    private const val ROUTE_FIGURES = """jsonb_build_object('active_memo_count', active_memo_count, 'gross_mtk', gross_mtk, 'net_mtk', net_mtk, 'paid_mtk', paid_mtk,
        'due_mtk', due_mtk, 'visits', visits, 'dues_collected_mtk', dues_collected_mtk)"""
    private const val ZONE_FIGURES = """jsonb_build_object('active_memo_count', active_memo_count, 'gross_mtk', gross_mtk, 'net_mtk', net_mtk,
        'visits', visits, 'dues_collected_mtk', dues_collected_mtk, 'target_routes', target_routes, 'logged_in_routes', logged_in_routes)"""

    /** The log's `reason` for a dirty key's reason text, and the audited rebuild it names (`rebuild:<uuid>`). */
    fun reasonOf(dirtyReason: String?): Pair<String, java.util.UUID?> {
        val r = dirtyReason ?: return "late_batch" to null
        if (r.startsWith("rebuild:")) return "rebuild" to runCatching { java.util.UUID.fromString(r.removePrefix("rebuild:")) }.getOrNull()
        return when (r) {
            "rebuild", "rebuild_once" -> "rebuild"
            "reconcile" -> "reconcile"
            "memo.voided" -> "memo_voided"
            "memo.superseded" -> "memo_superseded"
            else -> "late_batch"
        } to null
    }

    /** Runs [block] (a rebuild of one key) and, for a closed date whose figures it changed, appends the restatement row. */
    fun <T> around(h: Handle, grain: String, key: Long, date: LocalDate, today: LocalDate, dirtyReason: String?, block: () -> T): T {
        if (!date.isBefore(today)) return block()
        val old = figures(h, grain, key, date)
        val result = block()
        val new = figures(h, grain, key, date)
        if (old != new) {
            val (reason, rebuildId) = reasonOf(dirtyReason)
            h.createUpdate(
                """
                WITH o AS (SELECT coalesce(CAST(:old AS jsonb), '{}'::jsonb) AS j), n AS (SELECT coalesce(CAST(:new AS jsonb), '{}'::jsonb) AS j)
                INSERT INTO dw.agg_restatement_log (business_date, grain, key1, reason, old_row, new_row, delta, rebuild_id)
                SELECT :d, :g, :k, :reason, o.j, n.j,
                       (SELECT coalesce(jsonb_object_agg(ks.k, coalesce((n.j ->> ks.k)::bigint, 0) - coalesce((o.j ->> ks.k)::bigint, 0)), '{}'::jsonb)
                          FROM (SELECT jsonb_object_keys(o.j) AS k UNION SELECT jsonb_object_keys(n.j)) ks), :rid
                  FROM o, n
                """,
            ).bind("old", old).bind("new", new).bind("d", date).bind("g", grain).bind("k", key).bind("reason", reason).bind("rid", rebuildId).execute()
        }
        return result
    }

    private fun figures(h: Handle, grain: String, key: Long, date: LocalDate): String? = when (grain) {
        "route" -> h.createQuery("SELECT $ROUTE_FIGURES::text FROM dw.agg_daily_route WHERE route_id = :k AND business_date = :d")
        "zone" -> h.createQuery("SELECT $ZONE_FIGURES::text FROM dw.agg_daily_zone WHERE zone_id = :k AND business_date = :d")
        else -> error("restatement grain $grain")
    }.bind("k", key).bind("d", date).mapTo(String::class.java).findOne().orElse(null)
}
