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
    val FRESHNESS_TABLES = listOf("agg_daily_route_sku", "agg_daily_route_variant", "agg_daily_outlet_sku", "agg_daily_route", "agg_daily_zone", "route_last_position", "fact_visit")

    /** True when every table and column of the request exists. Cheap catalog query; not cached because test databases differ. */
    fun ready(h: Handle): Boolean {
        val wanted = columns.entries.flatMap { (t, cs) -> cs.map { t to it } }
        val found = h.createQuery(
            "SELECT table_name || '.' || column_name FROM information_schema.columns WHERE table_schema = 'dw' AND table_name = ANY(:t)",
        ).bindArray("t", String::class.java, columns.keys.toList()).mapTo(String::class.java).list().toSet()
        if (!wanted.all { (t, c) -> "$t.$c" in found }) return false
        return h.createQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'dw' AND table_name = ANY(:t)")
            .bindArray("t", String::class.java, tables).mapTo(Long::class.java).one() == tables.size.toLong()
    }

    /** Dimension columns the filters need: route class, canonical active house, product codes. */
    fun refreshDimensions(h: Handle) {
        h.execute(
            """
            UPDATE dw.dim_geo g SET
              route_class = coalesce(to_jsonb(r)->>'route_class', 'normal'),
              house_id    = (SELECT min(h2.id) FROM app.house h2 WHERE h2.territory_id = hs.territory_id AND lower(h2.name) = lower(hs.name)
                                AND coalesce(to_jsonb(h2)->>'status', 'active') = 'active'),
              house_name  = hs.name
              FROM app.route r JOIN app.zone z ON z.id = r.zone_id LEFT JOIN app.house hs ON hs.id = z.house_id
             WHERE g.route_id = r.id
               AND (g.route_class, g.house_id, g.house_name) IS DISTINCT FROM
                   (coalesce(to_jsonb(r)->>'route_class', 'normal'),
                    (SELECT min(h2.id) FROM app.house h2 WHERE h2.territory_id = hs.territory_id AND lower(h2.name) = lower(hs.name)
                        AND coalesce(to_jsonb(h2)->>'status', 'active') = 'active'),
                    hs.name)
            """,
        )
        h.execute(
            """
            UPDATE dw.dim_product p SET segment_code = sg.code, brand_code = b.code, variant_code = v.code
              FROM app.sku s JOIN app.product_node v ON v.id = s.variant_id JOIN app.product_node b ON b.id = v.parent_id
              JOIN app.product_node sg ON sg.id = b.parent_id
             WHERE p.sku_id = s.id
               AND (p.segment_code, p.brand_code, p.variant_code) IS DISTINCT FROM (sg.code, b.code, v.code)
            """,
        )
    }

    private const val ACTIVE_MEMO = "m.status = 'active' AND m.line_count > 0 AND m.voided_at IS NULL"

    /** Route-day part: outlet × SKU sales, the route's latest position, call counts, sale times, download time, visit geometry. */
    fun rebuildRouteDay(h: Handle, routeId: Long, date: LocalDate, wm: Long) {
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
         WHERE z.business_date < :today AND z.final_submitted_at IS NULL
           AND EXISTS (SELECT 1 FROM dw.agg_daily_route a JOIN dw.dim_geo g ON g.route_id = a.route_id
                        WHERE a.zone_id = z.zone_id AND a.business_date = z.business_date AND a.planned AND NOT a.exception_approved AND g.route_class = 'normal')
        """,
    ).bind("today", today).execute()

    /** The summary tables are current as of [at]: written when the worker found nothing left to project or rebuild. */
    fun touchFreshness(h: Handle, at: Instant) {
        val wm = h.createQuery("SELECT coalesce(max(id), 0) FROM app.domain_event").mapTo(Long::class.java).one()
        for (t in FRESHNESS_TABLES) {
            h.createUpdate(
                "INSERT INTO dw.summary_freshness (table_name, last_updated_at, last_event_id) VALUES (:t, :at, :wm) " +
                    "ON CONFLICT (table_name) DO UPDATE SET last_updated_at = excluded.last_updated_at, last_event_id = excluded.last_event_id",
            ).bind("t", t).bind("at", at).bind("wm", wm).execute()
        }
    }
}
