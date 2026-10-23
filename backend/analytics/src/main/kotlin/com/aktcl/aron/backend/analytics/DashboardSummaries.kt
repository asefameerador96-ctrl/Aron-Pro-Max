package com.aktcl.aron.backend.analytics

import org.jdbi.v3.core.Handle
import java.time.Instant
import java.time.LocalDate

/**
 * The national dashboard's summary tables (docs/34 s2, docs/ui-reference/web/dashboard-brief.md s6, request
 * docs/requests/db-backend-reports-dashboard-summaries.md). They are filled by the same route-day and zone-day rebuilds as
 * the rest of `dw`, so they are idempotent, rebuildable and follow the outbox within one worker pass.
 *
 * The db lane owns the DDL. Until its migration is on a database, [ready] is false and every function here does nothing,
 * so the older aggregates keep working on any schema.
 */
internal object DashboardSummaries {
    private val columns = mapOf(
        "dim_geo" to listOf("route_class", "house_id", "house_name"),
        "dim_product" to listOf("segment_code", "brand_code", "variant_code"),
        "agg_daily_route" to listOf("downloaded_at", "completed_calls", "completed_geo_valid_calls", "first_sale_at", "last_sale_at"),
        "agg_daily_zone" to listOf("final_submitted_at", "auto_submitted"),
        "fact_visit" to listOf("radius_m", "outlet_lat", "outlet_lng", "fix_lat", "fix_lng", "force_photo_uuid", "completed"),
    )
    private val tables = listOf("agg_daily_outlet_sku", "agg_daily_route_variant", "route_last_position", "summary_freshness")

    /** Summary tables a card response may name in `last_updated_at`. */
    val FRESHNESS_TABLES = listOf("agg_daily_route_sku", "agg_daily_route_variant", "agg_daily_route_segment", "agg_daily_route_brand", "agg_daily_outlet_sku", "agg_daily_route", "agg_daily_zone", "route_last_position", "fact_visit")

    /**
     * True when `dw.agg_daily_route.checked_in_at` exists (db migration V0108). Probed on its own so that the worker and the cards keep working on
     * either side of that migration: without the column nothing is filled and the login card keeps the older rule (download only).
     */
    fun hasCheckedIn(h: Handle): Boolean = h.createQuery(
        "SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'dw' AND table_name = 'agg_daily_route' AND column_name = 'checked_in_at')",
    ).mapTo(Boolean::class.java).one()

    /**
     * The one meaning of "Successful Login" (owner brief s3.4, D-REPORTS-LOGIN-01): the SR logged in AND downloaded the bundle AND checked in. SQL over
     * `dw.agg_daily_route` columns, [alias] is the table alias ("" for none). Where the summary columns do not exist yet (before db's V0093 to V0108) the
     * older rule holds (any state past `not_started`), so the worker and the cards work on either side of those migrations.
     */
    fun successfulLoginSql(h: Handle, alias: String = "a"): String {
        val p = if (alias.isEmpty()) "" else "$alias."
        if (!ready(h)) return "${p}day_state <> 'not_started'"
        return "${p}logged_in_at IS NOT NULL AND ${p}downloaded_at IS NOT NULL" + if (hasCheckedIn(h)) " AND ${p}checked_in_at IS NOT NULL" else ""
    }

    /** [successfulLoginSql] as it stood at the instant bound to `:[param]`: every stamp present and at or before it (never null). */
    fun successfulLoginAtSql(h: Handle, alias: String, param: String): String {
        val stamps = if (!ready(h)) listOf("logged_in_at") else listOf("logged_in_at", "downloaded_at") + if (hasCheckedIn(h)) listOf("checked_in_at") else emptyList()
        return "coalesce(" + stamps.joinToString(" AND ") { "$alias.$it <= :$param" } + ", false)"
    }

    /** True when every table and column of the request exists. Cheap catalog query; not cached because test databases differ. */
    fun ready(h: Handle): Boolean {
        val wanted = columns.entries.flatMap { (t, cs) -> cs.map { t to it } }
        val found = h.createQuery(
            "SELECT table_name || '.' || column_name FROM information_schema.columns WHERE table_schema = 'dw' AND table_name = ANY(:t)",
        ).bindArray("t", String::class.java, columns.keys.toList()).mapTo(String::class.java).list().toSet()
        if (!wanted.all { (t, c) -> "$t.$c" in found }) return false
        // The source of the route class must exist too: without it every route would silently count as normal (checker finding 8).
        val routeClass = h.createQuery("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'app' AND table_name = 'route' AND column_name = 'route_class'").mapTo(Long::class.java).one()
        if (routeClass == 0L) return false
        return h.createQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'dw' AND table_name = ANY(:t)")
            .bindArray("t", String::class.java, tables).mapTo(Long::class.java).one() == tables.size.toLong()
    }

    /** Dimension columns the filters need: route class, canonical active house, product codes. */
    fun refreshDimensions(h: Handle) {
        h.execute(
            """
            UPDATE dw.dim_geo g SET
              route_class = r.route_class,
              house_id    = (SELECT min(h2.id) FROM app.house h2 WHERE h2.territory_id = hs.territory_id AND lower(h2.name) = lower(hs.name)
                                AND coalesce(to_jsonb(h2)->>'status', 'active') = 'active'),
              house_name  = hs.name
              FROM app.route r JOIN app.zone z ON z.id = r.zone_id LEFT JOIN app.house hs ON hs.id = z.house_id
             WHERE g.route_id = r.id
               AND (g.route_class, g.house_id, g.house_name) IS DISTINCT FROM
                   (r.route_class,
                    (SELECT min(h2.id) FROM app.house h2 WHERE h2.territory_id = hs.territory_id AND lower(h2.name) = lower(hs.name)
                        AND coalesce(to_jsonb(h2)->>'status', 'active') = 'active'),
                    hs.name)
            """,
        )
        h.execute(
            """
            UPDATE dw.dim_product p SET segment_code = coalesce(sg.code, 'n' || sg.id), brand_code = coalesce(b.code, 'n' || b.id), variant_code = coalesce(v.code, 'n' || v.id)
              FROM app.sku s JOIN app.product_node v ON v.id = s.variant_id JOIN app.product_node b ON b.id = v.parent_id
              JOIN app.product_node sg ON sg.id = b.parent_id
             WHERE p.sku_id = s.id
               AND (p.segment_code, p.brand_code, p.variant_code) IS DISTINCT FROM (coalesce(sg.code, 'n' || sg.id), coalesce(b.code, 'n' || b.id), coalesce(v.code, 'n' || v.id))
            """,
        )
    }

    private const val ACTIVE_MEMO = "m.status = 'active' AND m.line_count > 0 AND m.voided_at IS NULL"

    /** Route-day part: outlet × SKU sales, the route's latest position, call counts, sale times, download time, visit geometry. */
    fun rebuildRouteDay(h: Handle, routeId: Long, date: LocalDate, wm: Long) {
        // A voided fix leaves the map path and the distance (the generic fact rebuild only upserts).
        h.createUpdate(
            """
            DELETE FROM dw.fact_geo_fix f WHERE f.route_id = :r AND f.business_date = :d
               AND NOT EXISTS (SELECT 1 FROM app.geo_fix g WHERE g.source_client_uuid = f.source_client_uuid AND g.slot = f.slot
                                  AND g.business_date = f.business_date AND g.voided_at IS NULL)
            """,
        ).bind("r", routeId).bind("d", date).execute()
        // Target outlets are frozen at the first bundle; a route nobody downloaded yet counts its active outlets.
        h.createUpdate(
            """
            UPDATE dw.agg_daily_route a SET target_outlets = (SELECT count(*) FROM app.outlet o WHERE o.route_id = :r AND o.status = 'active')
             WHERE a.route_id = :r AND a.business_date = :d AND a.target_outlets = 0
               AND NOT EXISTS (SELECT 1 FROM app.route_day rd WHERE rd.route_id = :r AND rd.business_date = :d AND rd.target_frozen_at IS NOT NULL)
            """,
        ).bind("r", routeId).bind("d", date).execute()
        h.createUpdate("DELETE FROM dw.agg_daily_outlet_sku WHERE route_id = :r AND business_date = :d").bind("r", routeId).bind("d", date).execute()
        h.createUpdate(
            """
            INSERT INTO dw.agg_daily_outlet_sku (business_date, route_id, outlet_id, sku_id, sold_qty_base, memo_count, last_event_id, updated_at)
            SELECT :d, :r, m.outlet_id, ml.sku_id, coalesce(sum(ml.qty_base), 0), count(DISTINCT m.client_uuid), :wm, now()
              FROM app.memo_line ml JOIN app.memo m ON m.client_uuid = ml.memo_client_uuid AND m.business_date = ml.business_date
             WHERE m.route_id = :r AND m.business_date = :d AND $ACTIVE_MEMO AND ml.voided_at IS NULL AND ml.line_kind = 'sale'
             GROUP BY m.outlet_id, ml.sku_id
            """,
        ).bind("r", routeId).bind("d", date).bind("wm", wm).execute()

        h.createUpdate("DELETE FROM dw.agg_daily_route_variant WHERE route_id = :r AND business_date = :d").bind("r", routeId).bind("d", date).execute()
        h.createUpdate(
            """
            INSERT INTO dw.agg_daily_route_variant (business_date, route_id, variant_id, memo_count, sold_qty_base, last_event_id, updated_at)
            SELECT :d, :r, s.variant_id, count(DISTINCT m.client_uuid), coalesce(sum(ml.qty_base), 0), :wm, now()
              FROM app.memo_line ml JOIN app.memo m ON m.client_uuid = ml.memo_client_uuid AND m.business_date = ml.business_date
              JOIN app.sku s ON s.id = ml.sku_id
             WHERE m.route_id = :r AND m.business_date = :d AND $ACTIVE_MEMO AND ml.voided_at IS NULL AND ml.line_kind = 'sale'
             GROUP BY s.variant_id
            """,
        ).bind("r", routeId).bind("d", date).bind("wm", wm).execute()

        h.createUpdate("DELETE FROM dw.route_last_position WHERE route_id = :r AND business_date = :d").bind("r", routeId).bind("d", date).execute()
        h.createUpdate(
            """
            INSERT INTO dw.route_last_position (business_date, route_id, user_id, lat, lng, captured_at, last_event_id, updated_at)
            SELECT :d, :r, user_id, lat, lng, captured_at, :wm, now()
              FROM app.geo_fix WHERE route_id = :r AND business_date = :d AND lat IS NOT NULL AND voided_at IS NULL
             ORDER BY captured_at DESC, id DESC LIMIT 1
            """,
        ).bind("r", routeId).bind("d", date).bind("wm", wm).execute()

        h.createUpdate(
            """
            WITH done AS (SELECT * FROM app.visit v WHERE v.route_id = :r AND v.business_date = :d AND v.visit_kind = 'sr_call' AND v.voided_at IS NULL
                             AND v.outcome_code IS NOT NULL AND v.outcome_code NOT IN ('abandoned', 'not_reached')),
            memo AS (SELECT min(m.committed_at) AS f, max(m.committed_at) AS l FROM app.memo m WHERE m.route_id = :r AND m.business_date = :d AND $ACTIVE_MEMO),
            rd AS (SELECT CASE WHEN bundle_count > 0 THEN coalesce(target_frozen_at, last_bundle_at) END AS dl FROM app.route_day WHERE route_id = :r AND business_date = :d)
            UPDATE dw.agg_daily_route a SET
              completed_calls = (SELECT count(*) FROM done),
              completed_geo_valid_calls = (SELECT count(*) FROM done WHERE coalesce(server_verdict, verdict) = 'in_range'),
              first_sale_at = (SELECT f FROM memo), last_sale_at = (SELECT l FROM memo), downloaded_at = (SELECT dl FROM rd)
             WHERE a.route_id = :r AND a.business_date = :d
            """,
        ).bind("r", routeId).bind("d", date).execute()

        // The first non-voided attendance check-in of the route's SR that day: the event's own route, else (no route on the event) a user assigned
        // to the route that day. The same rule as the V0108 backfill, so rebuilt and backfilled days agree.
        if (hasCheckedIn(h)) h.createUpdate(
            """
            WITH ci AS (SELECT min(e.captured_at) AS at FROM app.attendance_event e
                         WHERE e.kind = 'check_in' AND e.voided_at IS NULL AND e.business_date = :d
                           AND (e.route_id = :r OR (e.route_id IS NULL AND e.user_id IN (
                                  SELECT ra.user_id FROM app.route_assignment ra
                                   WHERE ra.route_id = :r AND ra.valid_from <= :d AND (ra.valid_to IS NULL OR ra.valid_to > :d)))))
            UPDATE dw.agg_daily_route a SET checked_in_at = (SELECT at FROM ci) WHERE a.route_id = :r AND a.business_date = :d
            """,
        ).bind("r", routeId).bind("d", date).execute()

        h.createUpdate(
            """
            UPDATE dw.fact_visit f SET radius_m = v.radius_m_used, outlet_lat = v.outlet_lat, outlet_lng = v.outlet_lng, fix_lat = v.fix_lat,
                   fix_lng = v.fix_lng, force_photo_uuid = v.force_photo_uuid,
                   completed = (v.visit_kind = 'sr_call' AND v.voided_at IS NULL AND v.outcome_code IS NOT NULL AND v.outcome_code NOT IN ('abandoned', 'not_reached'))
              FROM app.visit v
             WHERE f.visit_client_uuid = v.client_uuid AND f.business_date = v.business_date AND v.route_id = :r AND v.business_date = :d
            """,
        ).bind("r", routeId).bind("d", date).execute()
    }

    /**
     * Zone-day part: the Final Submit time (first submit of the zone-day wins; a reopened one no longer counts). A zone that
     * the midnight job auto-submitted keeps that time until a real submit replaces it.
     */
    fun rebuildZoneDay(h: Handle, zoneId: Long, date: LocalDate) {
        h.createUpdate(
            """
            UPDATE dw.agg_daily_zone z SET
              final_submitted_at = coalesce(
                (SELECT min(f.submitted_at) FROM app.final_submit f WHERE f.zone_id = :z AND f.business_date = :d AND f.reopened_at IS NULL),
                CASE WHEN z.auto_submitted THEN z.final_submitted_at END),
              auto_submitted = NOT EXISTS (SELECT 1 FROM app.final_submit f WHERE f.zone_id = :z AND f.business_date = :d AND f.reopened_at IS NULL) AND z.auto_submitted
             WHERE z.zone_id = :z AND z.business_date = :d
            """,
        ).bind("z", zoneId).bind("d", date).execute()
    }

    /**
     * The midnight auto-submit (brief s3.3): a service zone (it had a normal planned route) of a day that has ended and
     * that no TSO submitted counts as submitted at 00:00 of the next Dhaka day. Summary only: `app.final_submit` is not written.
     */
    fun autoSubmit(h: Handle, today: LocalDate): Int = h.createUpdate(
        """
        UPDATE dw.agg_daily_zone z SET final_submitted_at = ((z.business_date + 1)::timestamp AT TIME ZONE 'Asia/Dhaka'), auto_submitted = true
         WHERE z.business_date < :today AND z.business_date >= :today - 7 AND z.final_submitted_at IS NULL
           AND NOT EXISTS (SELECT 1 FROM app.final_submit f WHERE f.zone_id = z.zone_id AND f.business_date = z.business_date AND f.reopened_at IS NULL)
           AND EXISTS (SELECT 1 FROM dw.agg_daily_route a JOIN dw.dim_geo g ON g.route_id = a.route_id
                        WHERE a.zone_id = z.zone_id AND a.business_date = z.business_date AND a.planned AND NOT a.exception_approved AND g.route_class = 'normal' AND g.route_kind = 'sr')
        """,
    ).bind("today", today).execute()

    /**
     * Route-days the outbox does not announce: planned ones with no aggregate row yet (nobody opened the app) and ones whose positions
     * or check-ins arrived since [since] (neither emits an event). Marks them dirty; the next drain rebuilds them.
     */
    fun dirtyStale(h: Handle, since: Instant, today: LocalDate): Int = h.createQuery(
        """
        SELECT DISTINCT route_id, business_date FROM (
          SELECT rd.route_id, rd.business_date FROM app.route_day rd
           WHERE rd.business_date = :today AND rd.planned
             AND NOT EXISTS (SELECT 1 FROM dw.agg_daily_route a WHERE a.route_id = rd.route_id AND a.business_date = rd.business_date)
          UNION ALL
          SELECT g.route_id, g.business_date FROM app.geo_fix g WHERE g.received_at > :since AND g.route_id IS NOT NULL AND g.business_date >= :today - 1
          UNION ALL
          -- A check-in emits no event either: the route it names, else the routes its user is assigned to that day (the V0108 rule); a late sync can land days after the date, so the last 7 days.
          SELECT e.route_id, e.business_date FROM app.attendance_event e
           WHERE e.received_at > :since AND e.kind = 'check_in' AND e.route_id IS NOT NULL AND e.business_date >= :today - 7
          UNION ALL
          SELECT ra.route_id, e.business_date FROM app.attendance_event e
            JOIN app.route_assignment ra ON ra.user_id = e.user_id AND ra.valid_from <= e.business_date AND (ra.valid_to IS NULL OR ra.valid_to > e.business_date)
           WHERE e.received_at > :since AND e.kind = 'check_in' AND e.route_id IS NULL AND e.business_date >= :today - 7
        ) s
        """,
    ).bind("today", today).bind("since", since).map { rs, _ -> rs.getLong(1) to rs.getObject(2, LocalDate::class.java) }.list()
        .onEach { (r, d) -> h.execute("SELECT app.mark_dirty('route_day_agg', ?, ?, 'stale_scan')", r, d) }.size

    /** The summary tables are current as of the time given: see [touchFreshness]. */
    fun touchFreshness(h: Handle, now: Instant) {
        // Current as of now, or as of the oldest change still waiting (or stuck) in the rebuild queue: the page never claims more than is true.
        val pending = h.createQuery("SELECT min(last_dirtied_at) FROM app.dirty_key WHERE kind IN ('route_day_agg', 'zone_day_agg')")
            .map { rs, _ -> rs.getTimestamp(1)?.toInstant() }.one()
        val at = if (pending != null && pending.isBefore(now)) pending else now
        val wm = h.createQuery("SELECT coalesce(max(id), 0) FROM app.domain_event").mapTo(Long::class.java).one()
        for (t in FRESHNESS_TABLES) {
            h.createUpdate(
                "INSERT INTO dw.summary_freshness (table_name, last_updated_at, last_event_id) VALUES (:t, :at, :wm) " +
                    "ON CONFLICT (table_name) DO UPDATE SET last_updated_at = excluded.last_updated_at, last_event_id = excluded.last_event_id",
            ).bind("t", t).bind("at", at).bind("wm", wm).execute()
        }
    }
}
