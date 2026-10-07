package com.aktcl.aron.backend.analytics

import org.jdbi.v3.core.Handle
import java.time.LocalDate

/**
 * Rebuilds `dw` facts and aggregates from the transaction tables (docs/24 s6.3, s12.4). Every rebuild is a pure
 * function of the source rows of one route-day or zone-day, written with upserts and delete-then-insert inside one
 * transaction, so running it twice, or after a late batch, gives the same numbers (F-SYS-015). Source rows are
 * immutable (status flips excepted), which is what makes recompute safe. Money is bigint milli-taka, quantities
 * base units, every key the Dhaka business date.
 */
object Aggregator {
    private const val ACTIVE_MEMO = "m.status = 'active' AND m.line_count > 0 AND m.voided_at IS NULL"

    /** Highest outbox event id at the time of the rebuild; stamped into `last_event_id` so a row shows how fresh it is. */
    private fun watermark(h: Handle): Long =
        h.createQuery("SELECT coalesce(max(id), 0) FROM app.domain_event").mapTo(Long::class.java).one()

    /** Full refresh of the four dimensions from master data; idempotent upserts. */
    fun refreshDimensions(h: Handle) {
        h.execute(
            """
            INSERT INTO dw.dim_geo (route_id, route_code, route_name, route_kind, visit_kind, zone_id, zone_name, territory_id, territory_name,
                                    division_id, division_name, wing_id, wing_name, status, updated_at)
            SELECT r.id, r.code, r.name, r.kind, r.visit_kind, z.id, z.name, t.id, t.name, d.id, d.name, w.id, w.name, r.status, now()
              FROM app.route r JOIN app.zone z ON z.id = r.zone_id JOIN app.territory t ON t.id = z.territory_id
              JOIN app.division d ON d.id = t.division_id JOIN app.wing w ON w.id = d.wing_id
            ON CONFLICT (route_id) DO UPDATE SET route_code = excluded.route_code, route_name = excluded.route_name, route_kind = excluded.route_kind,
              visit_kind = excluded.visit_kind, zone_id = excluded.zone_id, zone_name = excluded.zone_name, territory_id = excluded.territory_id,
              territory_name = excluded.territory_name, division_id = excluded.division_id, division_name = excluded.division_name,
              wing_id = excluded.wing_id, wing_name = excluded.wing_name, status = excluded.status, updated_at = now()
            """,
        )
        h.execute(
            """
            INSERT INTO dw.dim_product (sku_id, sku_code, short_name, base_unit, base_per_pack, variant_id, variant_name, brand_id, brand_name,
                                        segment_id, segment_name, category_id, category_name, category_code, updated_at)
            SELECT s.id, s.code, s.short_name, s.base_unit, s.base_per_pack, v.id, v.name, b.id, b.name, sg.id, sg.name, c.id, c.name,
                   coalesce(s.category_code, c.code, ''), now()
              FROM app.sku s JOIN app.product_node v ON v.id = s.variant_id JOIN app.product_node b ON b.id = v.parent_id
              JOIN app.product_node sg ON sg.id = b.parent_id JOIN app.product_node c ON c.id = sg.parent_id
            ON CONFLICT (sku_id) DO UPDATE SET sku_code = excluded.sku_code, short_name = excluded.short_name, base_unit = excluded.base_unit,
              base_per_pack = excluded.base_per_pack, variant_id = excluded.variant_id, variant_name = excluded.variant_name,
              brand_id = excluded.brand_id, brand_name = excluded.brand_name, segment_id = excluded.segment_id, segment_name = excluded.segment_name,
              category_id = excluded.category_id, category_name = excluded.category_name, category_code = excluded.category_code, updated_at = now()
            """,
        )
        h.execute(
            """
            INSERT INTO dw.dim_outlet (outlet_id, outlet_code, outlet_name, route_id, cluster_id, zone_id, channel, sub_channel_id, geo_class,
                                       outlet_kind, location_confirmed, status, updated_at)
            SELECT id, code, name, route_id, cluster_id, zone_id, channel, sub_channel_id, geo_class, outlet_kind, location_confirmed, status, now()
              FROM app.outlet
            ON CONFLICT (outlet_id) DO UPDATE SET outlet_code = excluded.outlet_code, outlet_name = excluded.outlet_name, route_id = excluded.route_id,
              cluster_id = excluded.cluster_id, zone_id = excluded.zone_id, channel = excluded.channel, sub_channel_id = excluded.sub_channel_id,
              geo_class = excluded.geo_class, outlet_kind = excluded.outlet_kind, location_confirmed = excluded.location_confirmed,
              status = excluded.status, updated_at = now()
            """,
        )
    }

    /**
     * Facts, then the route-day aggregates (route, sku, brand, outlet). Returns the zones whose zone-day must be rebuilt: the
     * route's current zone and, when the route moved zones since its last rebuild, the zone it was counted in before. Empty for an unknown route.
     */
    fun rebuildRouteDay(h: Handle, routeId: Long, date: LocalDate, suspiciousThreshold: Int = DEFAULT_SUSPICIOUS_THRESHOLD): Set<Long> {
        val zoneId = h.createQuery("SELECT zone_id FROM app.route WHERE id = :r").bind("r", routeId).mapTo(Long::class.java).findOne().orElse(null)
            ?: return emptySet()
        val previous = h.createQuery("SELECT zone_id FROM dw.agg_daily_route WHERE route_id = :r AND business_date = :d").bind("r", routeId).bind("d", date)
            .mapTo(Long::class.java).findOne().orElse(null)
        val wm = watermark(h)
        rebuildFacts(h, routeId, zoneId, date, wm)
        rebuildAggRoute(h, routeId, zoneId, date, wm, suspiciousThreshold)
        rebuildAggRouteSku(h, routeId, date, wm)
        rebuildAggRouteBrand(h, routeId, date, wm)
        rebuildAggOutlet(h, routeId, date, wm)
        rebuildAttendance(h, routeId, date, wm)
        rebuildGeoFixes(h, routeId, date, wm)
        // The phones of this route-day's users also changed their device-day (batches, rows, rejects).
        h.createUpdate(
            """
            SELECT app.mark_dirty('device_day_agg', d.device_id, :d, 'route_day_rebuilt')
              FROM (SELECT DISTINCT sb.device_id FROM app.sync_batch sb
                     WHERE (sb.received_at AT TIME ZONE 'Asia/Dhaka')::date = :d
                       AND sb.user_id IN (SELECT coalesce(rd.acting_user_id, rd.assigned_user_id) FROM app.route_day rd WHERE rd.route_id = :r AND rd.business_date = :d)) d
            """,
        ).bind("r", routeId).bind("d", date).execute()
        return setOfNotNull(zoneId, previous)
    }

    /** cfg.geo.suspicious_score_threshold default (docs/24 s9.5, s11.4). */
    const val DEFAULT_SUSPICIOUS_THRESHOLD = 50

    private fun rebuildFacts(h: Handle, routeId: Long, zoneId: Long, date: LocalDate, wm: Long) {
        h.createUpdate(
            """
            INSERT INTO dw.fact_visit (business_date, visit_client_uuid, user_id, route_id, zone_id, outlet_id, visit_kind, opened_at, ended_at, outcome_code,
                                       call_declined, device_verdict, server_verdict, geo_action, distance_m, is_mock, planned, voided, last_event_id, updated_at)
            SELECT v.business_date, v.client_uuid, v.user_id, v.route_id, :z, v.outlet_id, v.visit_kind, v.opened_at, v.ended_at, v.outcome_code,
                   v.call_declined, v.verdict, v.server_verdict, v.geo_action, v.distance_m, (v.fix_is_mock IS TRUE OR v.verdict = 'mocked'),
                   v.planned, v.voided_at IS NOT NULL, :wm, now()
              FROM app.visit v WHERE v.route_id = :r AND v.business_date = :d
            ON CONFLICT (visit_client_uuid, business_date) DO UPDATE SET user_id = excluded.user_id, route_id = excluded.route_id, zone_id = excluded.zone_id,
              outlet_id = excluded.outlet_id, visit_kind = excluded.visit_kind, opened_at = excluded.opened_at, ended_at = excluded.ended_at,
              outcome_code = excluded.outcome_code, call_declined = excluded.call_declined, device_verdict = excluded.device_verdict,
              server_verdict = excluded.server_verdict, geo_action = excluded.geo_action, distance_m = excluded.distance_m, is_mock = excluded.is_mock,
              planned = excluded.planned, voided = excluded.voided, last_event_id = excluded.last_event_id, updated_at = now()
            """,
        ).bind("r", routeId).bind("z", zoneId).bind("d", date).bind("wm", wm).execute()
        h.createUpdate(
            """
            INSERT INTO dw.fact_memo (business_date, memo_client_uuid, memo_no, user_id, route_id, zone_id, outlet_id, status, committed_at, line_count,
                                      gross_mtk, offer_discount_mtk, drp_discount_mtk, qc_deduction_mtk, net_mtk, paid_mtk, due_mtk, is_credit,
                                      captured_offline, received_at, last_event_id, updated_at)
            SELECT m.business_date, m.client_uuid, m.memo_no, m.user_id, m.route_id, :z, m.outlet_id,
                   CASE WHEN m.voided_at IS NOT NULL THEN 'voided' ELSE m.status END, m.committed_at, m.line_count,
                   m.gross_mtk, m.offer_discount_mtk, m.drp_discount_mtk, m.qc_deduction_mtk, m.net_mtk, m.paid_mtk, m.due_mtk, m.is_credit,
                   m.captured_offline, m.received_at, :wm, now()
              FROM app.memo m WHERE m.route_id = :r AND m.business_date = :d
            ON CONFLICT (memo_client_uuid, business_date) DO UPDATE SET memo_no = excluded.memo_no, user_id = excluded.user_id, route_id = excluded.route_id,
              zone_id = excluded.zone_id, outlet_id = excluded.outlet_id, status = excluded.status, committed_at = excluded.committed_at,
              line_count = excluded.line_count, gross_mtk = excluded.gross_mtk, offer_discount_mtk = excluded.offer_discount_mtk,
              drp_discount_mtk = excluded.drp_discount_mtk, qc_deduction_mtk = excluded.qc_deduction_mtk, net_mtk = excluded.net_mtk,
              paid_mtk = excluded.paid_mtk, due_mtk = excluded.due_mtk, is_credit = excluded.is_credit, captured_offline = excluded.captured_offline,
              received_at = excluded.received_at, last_event_id = excluded.last_event_id, updated_at = now()
            """,
        ).bind("r", routeId).bind("z", zoneId).bind("d", date).bind("wm", wm).execute()
    }

    private fun rebuildAggRoute(h: Handle, routeId: Long, zoneId: Long, date: LocalDate, wm: Long, threshold: Int) {
        h.createUpdate(
            """
            WITH rd AS (SELECT * FROM app.route_day WHERE route_id = :r AND business_date = :d),
            ex AS (SELECT EXISTS (SELECT 1 FROM app.day_exception e WHERE e.status = 'approved' AND e.voided_at IS NULL
                                    AND :r = ANY (e.route_ids) AND :d BETWEEN e.from_date AND e.to_date) AS approved),
            vis AS (SELECT v.* FROM app.visit v WHERE v.route_id = :r AND v.business_date = :d AND v.visit_kind = 'sr_call' AND v.voided_at IS NULL),
            susp AS (SELECT user_id FROM app.risk_signal WHERE business_date = :d AND status IN ('open','confirmed') AND user_id IS NOT NULL
                      GROUP BY user_id HAVING sum(score) >= :thr),
            memo AS (SELECT m.* FROM app.memo m WHERE m.route_id = :r AND m.business_date = :d AND $ACTIVE_MEMO),
            dues AS (SELECT coalesce(sum(amount_mtk), 0) AS amt FROM app.due_collection WHERE route_id = :r AND business_date = :d AND voided_at IS NULL)
            INSERT INTO dw.agg_daily_route (business_date, route_id, zone_id, planned, exception_approved, day_state, logged_in_at, sales_submitted_at,
                final_submitted_at, target_outlets, visited_outlets, successful_calls, visits, geo_valid_visits, force_sale_visits, mock_visits,
                suspicious_visits, active_memo_count, gross_mtk, offer_discount_mtk, drp_discount_mtk, qc_deduction_mtk, net_mtk, paid_mtk, due_mtk,
                dues_collected_mtk, late_rows_after_final, last_event_id, updated_at)
            SELECT :d, :r, :z, coalesce((SELECT planned FROM rd), false), (SELECT approved FROM ex), coalesce((SELECT state FROM rd), 'not_started'),
                   (SELECT logged_in_at FROM rd), (SELECT sales_submitted_at FROM rd), (SELECT final_submitted_at FROM rd),
                   coalesce((SELECT target_outlets FROM rd), 0),
                   (SELECT count(DISTINCT outlet_id) FROM vis WHERE outcome_code IS DISTINCT FROM 'abandoned'),
                   (SELECT count(DISTINCT outlet_id) FROM memo),
                   (SELECT count(*) FROM vis),
                   (SELECT count(*) FROM vis WHERE coalesce(server_verdict, verdict) = 'in_range'),
                   (SELECT count(*) FROM vis WHERE geo_action = 'force_sale'),
                   (SELECT count(*) FROM vis WHERE fix_is_mock IS TRUE OR verdict = 'mocked'),
                   (SELECT count(*) FROM vis WHERE user_id IN (SELECT user_id FROM susp)),
                   (SELECT count(*) FROM memo),
                   (SELECT coalesce(sum(gross_mtk), 0) FROM memo), (SELECT coalesce(sum(offer_discount_mtk), 0) FROM memo),
                   (SELECT coalesce(sum(drp_discount_mtk), 0) FROM memo), (SELECT coalesce(sum(qc_deduction_mtk), 0) FROM memo),
                   (SELECT coalesce(sum(net_mtk), 0) FROM memo), (SELECT coalesce(sum(paid_mtk), 0) FROM memo), (SELECT coalesce(sum(due_mtk), 0) FROM memo),
                   (SELECT amt FROM dues), coalesce((SELECT late_rows_after_final FROM rd), 0), :wm, now()
            ON CONFLICT (business_date, route_id) DO UPDATE SET zone_id = excluded.zone_id, planned = excluded.planned,
              exception_approved = excluded.exception_approved, day_state = excluded.day_state, logged_in_at = excluded.logged_in_at,
              sales_submitted_at = excluded.sales_submitted_at, final_submitted_at = excluded.final_submitted_at, target_outlets = excluded.target_outlets,
              visited_outlets = excluded.visited_outlets, successful_calls = excluded.successful_calls, visits = excluded.visits,
              geo_valid_visits = excluded.geo_valid_visits, force_sale_visits = excluded.force_sale_visits, mock_visits = excluded.mock_visits,
              suspicious_visits = excluded.suspicious_visits, active_memo_count = excluded.active_memo_count, gross_mtk = excluded.gross_mtk,
              offer_discount_mtk = excluded.offer_discount_mtk, drp_discount_mtk = excluded.drp_discount_mtk, qc_deduction_mtk = excluded.qc_deduction_mtk,
              net_mtk = excluded.net_mtk, paid_mtk = excluded.paid_mtk, due_mtk = excluded.due_mtk, dues_collected_mtk = excluded.dues_collected_mtk,
              late_rows_after_final = excluded.late_rows_after_final, last_event_id = excluded.last_event_id, updated_at = now()
            """,
        ).bind("r", routeId).bind("z", zoneId).bind("d", date).bind("wm", wm).bind("thr", threshold).execute()
    }

    private fun rebuildAggRouteSku(h: Handle, routeId: Long, date: LocalDate, wm: Long) {
        h.createUpdate("DELETE FROM dw.agg_daily_route_sku WHERE route_id = :r AND business_date = :d").bind("r", routeId).bind("d", date).execute()
        h.createUpdate(
            """
            WITH sales AS (
              SELECT ml.sku_id,
                     sum(ml.qty_base) FILTER (WHERE ml.line_kind = 'sale') AS sold,
                     sum(ml.qty_base) FILTER (WHERE ml.line_kind <> 'sale') AS free,
                     coalesce(sum(ml.gross_mtk) FILTER (WHERE ml.line_kind = 'sale'), 0) AS gross,
                     count(DISTINCT m.client_uuid) AS memos
                FROM app.memo_line ml JOIN app.memo m ON m.client_uuid = ml.memo_client_uuid AND m.business_date = ml.business_date
               WHERE m.route_id = :r AND m.business_date = :d AND $ACTIVE_MEMO AND ml.voided_at IS NULL GROUP BY ml.sku_id),
            stock AS (
              SELECT sku_id, sum(qty_base) FILTER (WHERE kind = 'issue') AS issued, sum(qty_base) FILTER (WHERE kind IN ('return','qc_return')) AS ret
                FROM app.stock_movement WHERE route_id = :r AND business_date = :d AND voided_at IS NULL GROUP BY sku_id)
            INSERT INTO dw.agg_daily_route_sku (business_date, route_id, sku_id, sold_qty_base, free_qty_base, issued_qty_base, returned_qty_base,
                                                gross_mtk, memo_count, last_event_id, updated_at)
            SELECT :d, :r, coalesce(s.sku_id, k.sku_id), coalesce(s.sold, 0), coalesce(s.free, 0), coalesce(k.issued, 0), coalesce(k.ret, 0),
                   coalesce(s.gross, 0), coalesce(s.memos, 0), :wm, now()
              FROM sales s FULL JOIN stock k ON k.sku_id = s.sku_id
            """,
        ).bind("r", routeId).bind("d", date).bind("wm", wm).execute()
    }

    private fun rebuildAggRouteBrand(h: Handle, routeId: Long, date: LocalDate, wm: Long) {
        h.createUpdate("DELETE FROM dw.agg_daily_route_brand WHERE route_id = :r AND business_date = :d").bind("r", routeId).bind("d", date).execute()
        h.createUpdate(
            """
            INSERT INTO dw.agg_daily_route_brand (business_date, route_id, brand_id, memo_count, sold_qty_base, gross_mtk, last_event_id, updated_at)
            SELECT :d, :r, b.id, count(DISTINCT m.client_uuid), coalesce(sum(ml.qty_base) FILTER (WHERE ml.line_kind = 'sale'), 0),
                   coalesce(sum(ml.gross_mtk) FILTER (WHERE ml.line_kind = 'sale'), 0), :wm, now()
              FROM app.memo_line ml JOIN app.memo m ON m.client_uuid = ml.memo_client_uuid AND m.business_date = ml.business_date
              JOIN app.sku s ON s.id = ml.sku_id JOIN app.product_node v ON v.id = s.variant_id JOIN app.product_node b ON b.id = v.parent_id
             WHERE m.route_id = :r AND m.business_date = :d AND $ACTIVE_MEMO AND ml.voided_at IS NULL AND ml.line_kind = 'sale'
             GROUP BY b.id
            """,
        ).bind("r", routeId).bind("d", date).bind("wm", wm).execute()
    }

    /**
     * One row per (date, outlet) across ALL routes (the key has no route): the outlets touched on this route-day are recomputed
     * from every route's records, so two routes selling to one outlet never overwrite each other. `route_id` is the outlet's own route.
     */
    private fun rebuildAggOutlet(h: Handle, routeId: Long, date: LocalDate, wm: Long) {
        h.createUpdate(
            """
            WITH keys AS (SELECT outlet_id FROM app.visit WHERE route_id = :r AND business_date = :d
                          UNION SELECT outlet_id FROM app.memo WHERE route_id = :r AND business_date = :d
                          UNION SELECT outlet_id FROM app.due_collection WHERE route_id = :r AND business_date = :d),
            vis AS (SELECT outlet_id, bool_or(outcome_code IS DISTINCT FROM 'abandoned') AS visited, bool_or(coalesce(server_verdict, verdict) = 'in_range') AS geo_valid
                      FROM app.visit WHERE business_date = :d AND visit_kind = 'sr_call' AND voided_at IS NULL AND outlet_id IN (SELECT outlet_id FROM keys) GROUP BY outlet_id),
            memo AS (SELECT m.outlet_id, count(*) AS n, coalesce(sum(m.net_mtk), 0) AS net, coalesce(sum(m.due_mtk), 0) AS due
                       FROM app.memo m WHERE m.business_date = :d AND $ACTIVE_MEMO AND m.outlet_id IN (SELECT outlet_id FROM keys) GROUP BY m.outlet_id),
            qty AS (SELECT m.outlet_id, sum(ml.qty_base) AS q
                      FROM app.memo_line ml JOIN app.memo m ON m.client_uuid = ml.memo_client_uuid AND m.business_date = ml.business_date
                     WHERE m.business_date = :d AND $ACTIVE_MEMO AND ml.voided_at IS NULL AND ml.line_kind = 'sale' AND m.outlet_id IN (SELECT outlet_id FROM keys) GROUP BY m.outlet_id),
            dues AS (SELECT outlet_id, sum(amount_mtk) AS amt FROM app.due_collection WHERE business_date = :d AND voided_at IS NULL AND outlet_id IN (SELECT outlet_id FROM keys) GROUP BY outlet_id)
            INSERT INTO dw.agg_daily_outlet (business_date, outlet_id, route_id, visited, geo_valid, active_memo_count, sold_qty_base, net_mtk, due_mtk,
                                             dues_collected_mtk, last_event_id, updated_at)
            SELECT :d, k.outlet_id, o.route_id, coalesce(v.visited, false), coalesce(v.geo_valid, false), coalesce(m.n, 0), coalesce(q.q, 0),
                   coalesce(m.net, 0), coalesce(m.due, 0), coalesce(d.amt, 0), :wm, now()
              FROM keys k JOIN app.outlet o ON o.id = k.outlet_id LEFT JOIN vis v USING (outlet_id) LEFT JOIN memo m USING (outlet_id)
              LEFT JOIN qty q USING (outlet_id) LEFT JOIN dues d USING (outlet_id)
            ON CONFLICT (business_date, outlet_id) DO UPDATE SET route_id = excluded.route_id, visited = excluded.visited, geo_valid = excluded.geo_valid,
              active_memo_count = excluded.active_memo_count, sold_qty_base = excluded.sold_qty_base, net_mtk = excluded.net_mtk, due_mtk = excluded.due_mtk,
              dues_collected_mtk = excluded.dues_collected_mtk, last_event_id = excluded.last_event_id, updated_at = now()
            """,
        ).bind("r", routeId).bind("d", date).bind("wm", wm).execute()
    }

    /** `dw.fact_attendance`: one row per user-day with the first check-in and the last check-out of the users who checked in or out on this route-day. */
    private fun rebuildAttendance(h: Handle, routeId: Long, date: LocalDate, wm: Long) {
        h.createUpdate(
            """
            INSERT INTO dw.fact_attendance (business_date, user_id, role, zone_id, check_in_at, check_in_lat, check_in_lng, check_in_accuracy_m, check_in_is_mock,
                                            check_out_at, check_out_lat, check_out_lng, check_out_accuracy_m, check_out_is_mock, last_event_id, updated_at)
            SELECT e.business_date, e.user_id, u.role, (array_agg(g.zone_id ORDER BY e.captured_at))[1],
                   min(e.captured_at) FILTER (WHERE e.kind = 'check_in'),
                   (array_agg(e.fix_lat ORDER BY e.captured_at) FILTER (WHERE e.kind = 'check_in'))[1], (array_agg(e.fix_lng ORDER BY e.captured_at) FILTER (WHERE e.kind = 'check_in'))[1],
                   (array_agg(e.fix_accuracy_m ORDER BY e.captured_at) FILTER (WHERE e.kind = 'check_in'))[1], (array_agg(e.fix_is_mock ORDER BY e.captured_at) FILTER (WHERE e.kind = 'check_in'))[1],
                   max(e.captured_at) FILTER (WHERE e.kind = 'check_out'),
                   (array_agg(e.fix_lat ORDER BY e.captured_at DESC) FILTER (WHERE e.kind = 'check_out'))[1], (array_agg(e.fix_lng ORDER BY e.captured_at DESC) FILTER (WHERE e.kind = 'check_out'))[1],
                   (array_agg(e.fix_accuracy_m ORDER BY e.captured_at DESC) FILTER (WHERE e.kind = 'check_out'))[1], (array_agg(e.fix_is_mock ORDER BY e.captured_at DESC) FILTER (WHERE e.kind = 'check_out'))[1],
                   :wm, now()
              FROM app.attendance_event e JOIN app.app_user u ON u.id = e.user_id LEFT JOIN dw.dim_geo g ON g.route_id = e.route_id
             WHERE e.business_date = :d AND e.voided_at IS NULL
               AND e.user_id IN (SELECT user_id FROM app.attendance_event WHERE route_id = :r AND business_date = :d)
             GROUP BY e.business_date, e.user_id, u.role
            ON CONFLICT (business_date, user_id) DO UPDATE SET role = excluded.role, zone_id = excluded.zone_id, check_in_at = excluded.check_in_at, check_in_lat = excluded.check_in_lat,
              check_in_lng = excluded.check_in_lng, check_in_accuracy_m = excluded.check_in_accuracy_m, check_in_is_mock = excluded.check_in_is_mock, check_out_at = excluded.check_out_at,
              check_out_lat = excluded.check_out_lat, check_out_lng = excluded.check_out_lng, check_out_accuracy_m = excluded.check_out_accuracy_m, check_out_is_mock = excluded.check_out_is_mock,
              last_event_id = excluded.last_event_id, updated_at = now()
            """,
        ).bind("r", routeId).bind("d", date).bind("wm", wm).execute()
    }

    /** `dw.fact_geo_fix`: the fixes the route-day's records carried (the risk rules and the geo dashboards read them here). */
    private fun rebuildGeoFixes(h: Handle, routeId: Long, date: LocalDate, wm: Long) {
        h.createUpdate(
            """
            INSERT INTO dw.fact_geo_fix (business_date, source_client_uuid, slot, user_id, route_id, purpose, captured_at, lat, lng, accuracy_m, provider, is_mock, satellites_used, last_event_id)
            SELECT f.business_date, f.source_client_uuid, f.slot, f.user_id, f.route_id, f.purpose, f.captured_at, f.lat, f.lng, f.accuracy_m, f.provider, f.is_mock, f.satellites_used, :wm
              FROM app.geo_fix f WHERE f.route_id = :r AND f.business_date = :d AND f.voided_at IS NULL
            ON CONFLICT (source_client_uuid, slot, business_date) DO UPDATE SET user_id = excluded.user_id, route_id = excluded.route_id, purpose = excluded.purpose, captured_at = excluded.captured_at,
              lat = excluded.lat, lng = excluded.lng, accuracy_m = excluded.accuracy_m, provider = excluded.provider, is_mock = excluded.is_mock, satellites_used = excluded.satellites_used,
              last_event_id = excluded.last_event_id
            """,
        ).bind("r", routeId).bind("d", date).bind("wm", wm).execute()
    }

    /** `dw.fact_device_day`: what one phone did on one business date (batches, rows, rejects, quarantines, the most rows it held back). */
    fun rebuildDeviceDay(h: Handle, deviceId: Long, date: LocalDate) {
        val wm = watermark(h)
        h.createUpdate(
            """
            WITH b AS (SELECT sb.* FROM app.sync_batch sb WHERE sb.device_id = :dev AND (sb.received_at AT TIME ZONE 'Asia/Dhaka')::date = :d)
            INSERT INTO dw.fact_device_day (business_date, device_id, user_id, app_version, first_contact_at, last_contact_at, batches, records, rejected, quarantined, pending_rows_max, battery_pct_min, last_event_id, updated_at)
            SELECT :d, :dev, (array_agg(b.user_id ORDER BY b.received_at DESC))[1], coalesce((array_agg(b.app_version ORDER BY b.received_at DESC) FILTER (WHERE b.app_version IS NOT NULL))[1], dv.app_version),
                   min(b.received_at), max(b.received_at), count(*)::int, coalesce(sum(b.record_count), 0)::int,
                   (SELECT count(*) FROM app.sync_rejected r WHERE r.device_id = :dev AND r.business_date = :d)::int, (SELECT count(*) FROM app.sync_quarantine q WHERE q.device_id = :dev AND q.business_date = :d)::int,
                   max(b.pending_rows), NULL, :wm, now()
              FROM b CROSS JOIN app.device dv WHERE dv.id = :dev GROUP BY dv.app_version HAVING count(*) > 0
            ON CONFLICT (business_date, device_id) DO UPDATE SET user_id = excluded.user_id, app_version = excluded.app_version, first_contact_at = excluded.first_contact_at, last_contact_at = excluded.last_contact_at,
              batches = excluded.batches, records = excluded.records, rejected = excluded.rejected, quarantined = excluded.quarantined, pending_rows_max = excluded.pending_rows_max,
              last_event_id = excluded.last_event_id, updated_at = now()
            """,
        ).bind("dev", deviceId).bind("d", date).bind("wm", wm).execute()
    }

    /** Zone-day rollup from the route-day aggregates (never from the transaction log), plus the Dhaka-hour profile from the facts. */
    fun rebuildZoneDay(h: Handle, zoneId: Long, date: LocalDate, suspiciousThreshold: Int = DEFAULT_SUSPICIOUS_THRESHOLD) {
        val wm = watermark(h)
        h.createUpdate(
            """
            WITH r AS (SELECT * FROM dw.agg_daily_route WHERE zone_id = :z AND business_date = :d),
            tgt AS (SELECT * FROM r WHERE planned AND NOT exception_approved),
            susp AS (SELECT count(*) AS n FROM (SELECT user_id FROM app.risk_signal WHERE business_date = :d AND status IN ('open','confirmed') AND user_id IS NOT NULL
                                                   GROUP BY user_id HAVING sum(score) >= :thr) s
                      WHERE s.user_id IN (SELECT user_id FROM dw.fact_visit WHERE zone_id = :z AND business_date = :d))
            INSERT INTO dw.agg_daily_zone (business_date, zone_id, territory_id, target_routes, logged_in_routes, sales_submitted_routes, final_submitted,
                target_outlets, visited_outlets, successful_calls, visits, geo_valid_visits, force_sale_visits, mock_visits, suspicious_visits,
                suspicious_user_days, active_memo_count, gross_mtk, net_mtk, dues_collected_mtk, last_event_id, updated_at)
            SELECT :d, :z, (SELECT territory_id FROM app.zone WHERE id = :z),
                   (SELECT count(*) FROM tgt),
                   (SELECT count(*) FROM tgt WHERE day_state <> 'not_started'),
                   (SELECT count(*) FROM tgt WHERE day_state IN ('sales_submitted','final_submitted')),
                   (SELECT count(*) > 0 AND bool_and(day_state = 'final_submitted') FROM tgt),
                   (SELECT coalesce(sum(target_outlets), 0) FROM tgt),
                   (SELECT coalesce(sum(visited_outlets), 0) FROM r), (SELECT coalesce(sum(successful_calls), 0) FROM r),
                   (SELECT coalesce(sum(visits), 0) FROM r), (SELECT coalesce(sum(geo_valid_visits), 0) FROM r),
                   (SELECT coalesce(sum(force_sale_visits), 0) FROM r), (SELECT coalesce(sum(mock_visits), 0) FROM r),
                   (SELECT coalesce(sum(suspicious_visits), 0) FROM r), (SELECT n FROM susp),
                   (SELECT coalesce(sum(active_memo_count), 0) FROM r), (SELECT coalesce(sum(gross_mtk), 0) FROM r),
                   (SELECT coalesce(sum(net_mtk), 0) FROM r), (SELECT coalesce(sum(dues_collected_mtk), 0) FROM r), :wm, now()
            ON CONFLICT (business_date, zone_id) DO UPDATE SET territory_id = excluded.territory_id, target_routes = excluded.target_routes,
              logged_in_routes = excluded.logged_in_routes, sales_submitted_routes = excluded.sales_submitted_routes,
              final_submitted = excluded.final_submitted, target_outlets = excluded.target_outlets, visited_outlets = excluded.visited_outlets,
              successful_calls = excluded.successful_calls, visits = excluded.visits, geo_valid_visits = excluded.geo_valid_visits,
              force_sale_visits = excluded.force_sale_visits, mock_visits = excluded.mock_visits, suspicious_visits = excluded.suspicious_visits,
              suspicious_user_days = excluded.suspicious_user_days, active_memo_count = excluded.active_memo_count, gross_mtk = excluded.gross_mtk,
              net_mtk = excluded.net_mtk, dues_collected_mtk = excluded.dues_collected_mtk, last_event_id = excluded.last_event_id, updated_at = now()
            """,
        ).bind("z", zoneId).bind("d", date).bind("wm", wm).bind("thr", suspiciousThreshold).execute()

        h.createUpdate("DELETE FROM dw.agg_hourly_zone WHERE zone_id = :z AND business_date = :d").bind("z", zoneId).bind("d", date).execute()
        h.createUpdate(
            """
            WITH vh AS (SELECT extract(hour FROM opened_at AT TIME ZONE 'Asia/Dhaka')::smallint AS hr, count(*) AS n
                          FROM dw.fact_visit WHERE zone_id = :z AND business_date = :d AND NOT voided AND visit_kind = 'sr_call' GROUP BY 1),
            mh AS (SELECT extract(hour FROM committed_at AT TIME ZONE 'Asia/Dhaka')::smallint AS hr, count(*) AS n, sum(net_mtk) AS net
                     FROM dw.fact_memo WHERE zone_id = :z AND business_date = :d AND status = 'active' AND line_count > 0 GROUP BY 1),
            rh AS (SELECT extract(hour FROM received_at AT TIME ZONE 'Asia/Dhaka')::smallint AS hr, count(*) AS n
                     FROM dw.fact_memo WHERE zone_id = :z AND business_date = :d GROUP BY 1),
            hrs AS (SELECT hr FROM vh UNION SELECT hr FROM mh UNION SELECT hr FROM rh)
            INSERT INTO dw.agg_hourly_zone (business_date, zone_id, hour_of_day, visits, active_memo_count, net_mtk, records_received, last_event_id, updated_at)
            SELECT :d, :z, hrs.hr, coalesce(vh.n, 0), coalesce(mh.n, 0), coalesce(mh.net, 0), coalesce(rh.n, 0), :wm, now()
              FROM hrs LEFT JOIN vh USING (hr) LEFT JOIN mh USING (hr) LEFT JOIN rh USING (hr)
            """,
        ).bind("z", zoneId).bind("d", date).bind("wm", wm).execute()
    }
}
