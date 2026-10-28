package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.contract.ProblemCode
import org.jdbi.v3.core.Handle
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Haversine distance in metres on the WGS-84 mean radius (the geofence rule of docs/05, brief s5.4). */
internal fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6_371_008.8
    val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
    val a = sin((p2 - p1) / 2).pow(2) + cos(p1) * cos(p2) * sin(Math.toRadians(lng2 - lng1) / 2).pow(2)
    return 2 * r * asin(sqrt(a))
}

private fun round2(v: Double) = BigDecimal(v).setScale(2, RoundingMode.HALF_UP).toDouble()

/** Pin colour of a route from its geo-fencing %: red below 70, amber 70 to 90 inclusive, green above 90; no call yet = green (brief s5.2). */
internal fun pinColor(valid: Int, completed: Int): String = when {
    completed <= 0 -> "green"
    valid * 100 < completed * 70 -> "red"
    valid * 100 <= completed * 90 -> "amber"
    else -> "green"
}

/** The three map surfaces of the national dashboard: FF Geo pins and pop-up, the route map, the sale position (brief s5.2 to s5.4). */
fun NationalDashboardService.ffGeoPins(caller: NationalCaller, req: FfGeoPinsRequest): FfGeoPins {
    denyBrandUser(caller)
    val f = req.filter; val date = dateOf(f); val sql = NationalSql(caller, f)
    if (req.colors.any { it !in setOf("red", "amber", "green") }) throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad colors")
    return read { h ->
        ensureReady(h)
        val all = sql.bind(h.createQuery(
            """
            SELECT p.route_id, p.lat, p.lng, p.captured_at, coalesce(a.completed_calls, 0) AS done, coalesce(a.completed_geo_valid_calls, 0) AS ok
              FROM dw.route_last_position p ${AsWasSql.geo("g", "p.route_id", "p.business_date", "JOIN", lo = ":d", hi = ":d")}
              LEFT JOIN dw.agg_daily_route a ON a.route_id = p.route_id AND a.business_date = p.business_date
             WHERE p.business_date = :d ${sql.geo} ORDER BY p.route_id
            """,
        ).bind("d", date)).map { rs, _ ->
            val done = rs.getInt("done"); val ok = rs.getInt("ok")
            FfGeoPin(rs.getLong("route_id"), rs.getDouble("lat"), rs.getDouble("lng"), pinColor(ok, done), pctBp(ok.toLong(), done.toLong()), rs.getTimestamp("captured_at").toInstant().toString())
        }.list()
        val without = sql.bind(h.createQuery(
            """
            SELECT count(*) FROM dw.agg_daily_route a ${AsWasSql.geo("g", "a.route_id", "a.business_date", "JOIN", lo = ":d", hi = ":d")}
             WHERE a.business_date = :d AND a.planned AND NOT a.exception_approved AND g.route_class = 'normal' AND g.route_kind = 'sr' ${sql.geo}
               AND NOT EXISTS (SELECT 1 FROM dw.route_last_position p WHERE p.route_id = a.route_id AND p.business_date = a.business_date)
            """,
        ).bind("d", date)).mapTo(Long::class.java).one().toInt()
        val counts = ColorCounts(all.count { it.color == "red" }, all.count { it.color == "amber" }, all.count { it.color == "green" })
        val shown = if (req.colors.isEmpty()) all else all.filter { it.color in req.colors }
        val m = meta(h, date, listOf("route_last_position", "agg_daily_route"))
        FfGeoPins(m.date, m.till, m.asOf, m.last, shown, counts, without)
    }
}

private fun notFound(): ApiProblem = ApiProblem(ProblemCode.ERR_NOT_FOUND, "not found")

private fun place(rs: ResultSet, id: String, code: String, name: String) = PlaceRef(rs.getLong(id), rs.getString(code), rs.getString(name))

private class RouteDay(val targetOutlets: Int, val calls: Int, val geoOk: Int, val firstSale: Instant?, val lastSale: Instant?)

private class RouteHead(val route: PlaceRef, val territory: PlaceRef, val zone: PlaceRef, val userId: Long?)

/** The route's header when it is inside the caller's scope; 404 (no existence leak) otherwise. */
private fun NationalDashboardService.routeHead(h: Handle, caller: NationalCaller, routeId: Long, date: LocalDate): RouteHead {
    val sql = NationalSql(caller, NationalDashboardFilter())
    return sql.bind(h.createQuery(
        """
        SELECT g.route_id, g.route_code, g.route_name, g.territory_id, t.code AS tcode, g.territory_name, g.zone_id, z.code AS zcode, g.zone_name,
               coalesce(lp.user_id, rd.acting_user_id, rd.assigned_user_id) AS uid
          FROM dw.dim_geo g JOIN app.territory t ON t.id = g.territory_id JOIN app.zone z ON z.id = g.zone_id
          LEFT JOIN dw.route_last_position lp ON lp.route_id = g.route_id AND lp.business_date = :d
          LEFT JOIN app.route_day rd ON rd.route_id = g.route_id AND rd.business_date = :d
         WHERE g.route_id = :r ${sql.geo}
        """,
    ).bind("r", routeId).bind("d", date)).map { rs, _ ->
        RouteHead(PlaceRef(rs.getLong("route_id"), rs.getString("route_code"), rs.getString("route_name")), place(rs, "territory_id", "tcode", "territory_name"),
            place(rs, "zone_id", "zcode", "zone_name"), rs.getObject("uid") as Long?)
    }.findOne().orElseThrow { notFound() }
}

private fun userName(h: Handle, userId: Long?): String? =
    userId?.let { h.createQuery("SELECT full_name FROM app.app_user WHERE id = :u").bind("u", it).mapTo(String::class.java).findOne().orElse(null) }

fun NationalDashboardService.ffGeoRoutePopup(caller: NationalCaller, routeId: Long, dateParam: String?): FfGeoRoutePopup {
    denyBrandUser(caller)
    val date = dateOf(dateParam)
    return read { h ->
        ensureReady(h)
        val head = routeHead(h, caller, routeId, date)
        val pos = h.createQuery("SELECT captured_at FROM dw.route_last_position WHERE route_id = :r AND business_date = :d").bind("r", routeId).bind("d", date)
            .map { rs, _ -> rs.getTimestamp(1).toInstant().toString() }.findOne().orElse(null)
        val (done, ok) = h.createQuery("SELECT completed_calls, completed_geo_valid_calls FROM dw.agg_daily_route WHERE route_id = :r AND business_date = :d")
            .bind("r", routeId).bind("d", date).map { rs, _ -> rs.getInt(1) to rs.getInt(2) }.findOne().orElse(0 to 0)
        val geo = pctBp(ok.toLong(), done.toLong())
        val m = meta(h, date, listOf("route_last_position", "agg_daily_route"))
        // contact: the SR's phone is PII; it is returned only through the step-up read path (docs/34 s5 D-AUTHZ-PII), so null here.
        FfGeoRoutePopup(m.date, m.till, m.asOf, m.last, head.route, userName(h, head.userId), null, head.territory, head.zone, pos, geo?.let { 10_000 - it })
    }
}

fun NationalDashboardService.routeMap(caller: NationalCaller, routeId: Long, dateParam: String?): RouteMap {
    denyBrandUser(caller)
    val date = dateOf(dateParam)
    return read { h ->
        ensureReady(h)
        val head = routeHead(h, caller, routeId, date)
        // Positions in time order: the path the map draws and the length of Distance Covered (docs/34 D-DIST-01: metres along the track).
        val fixes = h.createQuery("SELECT lat, lng, captured_at FROM dw.fact_geo_fix WHERE route_id = :r AND business_date = :d AND lat IS NOT NULL ORDER BY captured_at, source_client_uuid, slot")
            .bind("r", routeId).bind("d", date).map { rs, _ -> MapPosition(rs.getDouble(1), rs.getDouble(2), rs.getTimestamp(3).toInstant().toString()) }.list()
        var metres = 0.0
        for (i in 1 until fixes.size) metres += haversineM(fixes[i - 1].lat, fixes[i - 1].lng, fixes[i].lat, fixes[i].lng)
        val step = (fixes.size + 2_999) / 3_000
        val path = if (step <= 1) fixes else fixes.filterIndexed { i, _ -> i % step == 0 || i == fixes.lastIndex }

        data class V(val outletId: Long, val at: Instant, val inRange: Boolean, val lat: Double?, val lng: Double?)
        val visits = h.createQuery(
            """
            SELECT outlet_id, coalesce(ended_at, opened_at) AS at, coalesce(server_verdict, device_verdict) = 'in_range' AS in_range, fix_lat, fix_lng, opened_at
              FROM dw.fact_visit WHERE route_id = :r AND business_date = :d AND completed ORDER BY opened_at, visit_client_uuid
            """,
        ).bind("r", routeId).bind("d", date).map { rs, _ ->
            V(rs.getLong("outlet_id"), rs.getTimestamp("at").toInstant(), rs.getBoolean("in_range"), rs.getObject("fix_lat") as Double?, rs.getObject("fix_lng") as Double?)
        }.list()
        val latest = visits.groupBy { it.outletId }.mapValues { it.value.last() }
        val first = visits.firstOrNull { it.lat != null }
        val sales = h.createQuery(
            """
            SELECT s.outlet_id, p.category_code, p.base_unit, sum(s.sold_qty_base) FROM dw.agg_daily_outlet_sku s ${AsWasSql.product("p", "s.sku_id", "s.business_date", "JOIN", lo = ":d", hi = ":d")}
             WHERE s.route_id = :r AND s.business_date = :d GROUP BY 1, 2, 3
            """,
        ).bind("r", routeId).bind("d", date).map { rs, _ -> rs.getLong(1) to CategoryQty(rs.getString(2), rs.getString(3), rs.getLong(4)) }.list()
            .groupBy({ it.first }, { it.second })
        val outlets = h.createQuery(
            """
            SELECT o.id, o.code, o.name, c.name AS cluster, coalesce(o.lat, o.provisional_lat) AS lat, coalesce(o.lng, o.provisional_lng) AS lng
              FROM app.outlet o LEFT JOIN app.cluster c ON c.id = o.cluster_id
             WHERE (o.route_id = :r AND o.status = 'active') OR o.id IN (SELECT outlet_id FROM dw.fact_visit WHERE route_id = :r AND business_date = :d AND completed)
             ORDER BY o.code
            """,
        ).bind("r", routeId).bind("d", date).map { rs, _ ->
            val oid = rs.getLong("id"); val v = latest[oid]
            val lat = rs.getObject("lat") as Double?
            if (lat == null) null else RouteMapOutlet(
                outlet_id = oid, code = rs.getString("code"), name = rs.getString("name"), contact = null, cluster_name = rs.getString("cluster"),
                lat = lat, lng = rs.getDouble("lng"), state = when { v == null -> "not_called"; v.inRange -> "geo_validated"; else -> "photo_validation" },
                call_time = v?.at?.toString(), sales = sales[oid] ?: emptyList(),
            )
        }.list().filterNotNull()

        val a = h.createQuery(
            "SELECT target_outlets, successful_calls, completed_geo_valid_calls, first_sale_at, last_sale_at FROM dw.agg_daily_route WHERE route_id = :r AND business_date = :d",
        ).bind("r", routeId).bind("d", date).map { rs, _ ->
            RouteDay(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getTimestamp(4)?.toInstant(), rs.getTimestamp(5)?.toInstant())
        }.findOne().orElse(RouteDay(0, 0, 0, null, null))
        val targetOutlets = a.targetOutlets; val calls = a.calls; val geoOk = a.geoOk
        val firstSale = a.firstSale; val lastSale = a.lastSale
        val total = if (firstSale != null && lastSale != null) java.time.Duration.between(firstSale, lastSale).seconds else null

        val month = YearMonth.from(date); val wd = WorkingCalendar.workingDays(h, month, weekendDays())
        val targets = h.createQuery(
            """
            SELECT p.category_code, sum(t.qty_base) FROM app.route_monthly_target t ${AsWasSql.product("p", "t.sku_id", "CAST(:d AS date)", lo = ":d", hi = ":d")}
             WHERE t.month = :m AND t.route_id = :r GROUP BY 1
            """,
        ).bind("m", month.atDay(1)).bind("r", routeId).bind("d", date).map { rs, _ -> rs.getString(1) to rs.getBigDecimal(2) }.list().toMap()
        val sold = h.createQuery(
            "SELECT p.category_code, p.base_unit, sum(s.sold_qty_base) FROM dw.agg_daily_route_sku s ${AsWasSql.product("p", "s.sku_id", "s.business_date", "JOIN", lo = ":d", hi = ":d")} WHERE s.route_id = :r AND s.business_date = :d GROUP BY 1, 2",
        ).bind("r", routeId).bind("d", date).map { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getLong(3)) }.list().associateBy { it.first }
        val cats = h.createQuery("SELECT DISTINCT category_code, base_unit FROM dw.dim_product").map { rs, _ -> rs.getString(1) to rs.getString(2) }.list()
            .sortedBy { CATEGORY_ORDER.indexOf(it.first).let { i -> if (i < 0) 99 else i } }
        val tva = cats.map { (cat, unit) ->
            val s = sold[cat]?.third ?: 0L
            val daily = targets[cat]?.takeIf { wd > 0 }?.divide(BigDecimal(wd), 3, RoundingMode.HALF_UP)
            TargetVsAchievement(cat, unit, daily?.toPlainString(), s, daily?.takeIf { it.signum() > 0 }?.let { ratioBp(BigDecimal(s), it) })
        }
        val lastPlace = h.createQuery(
            """
            SELECT c.name FROM dw.fact_memo fm JOIN app.outlet o ON o.id = fm.outlet_id LEFT JOIN app.cluster c ON c.id = o.cluster_id
             WHERE fm.route_id = :r AND fm.business_date = :d AND fm.status = 'active' AND fm.line_count > 0 ORDER BY fm.committed_at DESC, fm.memo_client_uuid LIMIT 1
            """,
        ).bind("r", routeId).bind("d", date).mapTo(String::class.java).findOne().orElse(null)
        val m = meta(h, date, listOf("agg_daily_route", "agg_daily_outlet_sku", "route_last_position", "fact_visit"))
        RouteMap(
            m.date, m.till, m.asOf, m.last, head.route, userName(h, head.userId), head.territory, head.zone,
            sale_start = first?.let { MapPosition(it.lat!!, it.lng!!, it.at.toString()) },
            sr_last_position = fixes.lastOrNull(), outlets = outlets, path = path, target_vs_achievement = tva, last_sale_place = lastPlace,
            cpr = RouteMapCpr(targetOutlets, calls, geoOk, pctBp(calls.toLong(), targetOutlets.toLong())),
            time_card = RouteMapTimeCard(firstSale?.toString(), lastSale?.toString(), total, if (total != null && calls > 0) total / calls else null, round2(metres)),
        )
    }
}

fun NationalDashboardService.salePosition(caller: NationalCaller, outletId: Long, dateParam: String?): SalePosition {
    denyBrandUser(caller)
    val date = dateOf(dateParam)
    return read { h ->
        ensureReady(h)
        val sql = NationalSql(caller, NationalDashboardFilter())
        data class Head(val code: String, val blat: Double?, val blng: Double?, val route: PlaceRef, val zone: PlaceRef)
        val hd = sql.bind(h.createQuery(
            """
            SELECT o.code, coalesce(o.lat, o.provisional_lat) AS blat, coalesce(o.lng, o.provisional_lng) AS blng, g.route_id, g.route_code, g.route_name,
                   g.zone_id, z.code AS zcode, g.zone_name
              FROM app.outlet o JOIN dw.dim_geo g ON g.route_id = o.route_id JOIN app.zone z ON z.id = g.zone_id WHERE o.id = :o ${sql.geo}
            """,
        ).bind("o", outletId)).map { rs, _ ->
            Head(rs.getString("code"), rs.getObject("blat") as Double?, rs.getObject("blng") as Double?, place(rs, "route_id", "route_code", "route_name"), place(rs, "zone_id", "zcode", "zone_name"))
        }.findOne().orElseThrow { notFound() }

        data class Vis(val uuid: java.util.UUID, val radius: Int?, val oLat: Double?, val oLng: Double?, val lat: Double?, val lng: Double?, val dist: Double?, val inRange: Boolean, val photo: java.util.UUID?)
        val visits = h.createQuery(
            """
            SELECT visit_client_uuid, radius_m, outlet_lat, outlet_lng, fix_lat, fix_lng, distance_m, coalesce(server_verdict, device_verdict) = 'in_range' AS in_range, force_photo_uuid
              FROM dw.fact_visit WHERE outlet_id = :o AND business_date = :d AND NOT voided AND visit_kind = 'sr_call' AND route_id = :rt ORDER BY opened_at
            """,
        ).bind("o", outletId).bind("d", date).bind("rt", hd.route.id).map { rs, _ ->
            Vis(rs.getObject(1, java.util.UUID::class.java), (rs.getObject(2) as Number?)?.toInt(), rs.getObject(3) as Double?, rs.getObject(4) as Double?, rs.getObject(5) as Double?,
                rs.getObject(6) as Double?, rs.getObject(7) as Double?, rs.getBoolean(8), rs.getObject(9, java.util.UUID::class.java))
        }.list().associateBy { it.uuid }
        // Detail page for one outlet and day: the memo versions are read from the memo table to tell new, repeat and edited sales apart.
        data class Mem(val uuid: java.util.UUID, val visit: java.util.UUID, val at: Instant, val edits: Boolean)
        val memos = h.createQuery(
            "SELECT client_uuid, visit_client_uuid, committed_at, supersedes_client_uuid IS NOT NULL FROM app.memo WHERE outlet_id = :o AND business_date = :d AND route_id = :rt AND status = 'active' AND voided_at IS NULL AND line_count > 0 ORDER BY committed_at, client_uuid",
        ).bind("o", outletId).bind("d", date).bind("rt", hd.route.id).map { rs, _ -> Mem(rs.getObject(1, java.util.UUID::class.java), rs.getObject(2, java.util.UUID::class.java), rs.getTimestamp(3).toInstant(), rs.getBoolean(4)) }.list()
        val editFix = h.createQuery("SELECT source_client_uuid, lat, lng FROM dw.fact_geo_fix WHERE slot = 'edit_fix' AND business_date = :d AND lat IS NOT NULL AND source_client_uuid = ANY(:u)")
            .bind("d", date).bindArray("u", java.util.UUID::class.java, memos.map { it.uuid }).map { rs, _ -> rs.getObject(1, java.util.UUID::class.java) to (rs.getDouble(2) to rs.getDouble(3)) }.list().toMap()
        var seenNew = false
        val out = memos.mapNotNull { m ->
            val v = visits[m.visit]
            val radius = v?.radius ?: 50
            val edit = editFix[m.uuid]
            val pos = edit ?: (v?.lat?.let { la -> v.lng?.let { ln -> la to ln } }) ?: return@mapNotNull null
            val bLat = v?.oLat ?: hd.blat; val bLng = v?.oLng ?: hd.blng
            // An edit carries its own position, so its distance and verdict are recomputed; otherwise the call's stored values are used.
            val dist = if (edit == null && v?.dist != null) v.dist else if (bLat != null && bLng != null) haversineM(bLat, bLng, pos.first, pos.second) else 0.0
            val within = if (edit == null && v != null) v.inRange else dist <= radius
            val kind = when { m.edits -> "edited"; seenNew -> "repeat"; else -> { seenNew = true; "new" } }
            SalePositionSale(m.uuid.toString(), kind, pos.first, pos.second, m.at.toString(), round2(dist), within, if (within) "hit" else "miss", v?.photo?.toString())
        }
        val base = hd.blat?.let { SalePositionBase(it, hd.blng!!) } ?: visits.values.firstNotNullOfOrNull { v -> v.oLat?.let { SalePositionBase(it, v.oLng!!) } } ?: throw notFound()
        val radius = visits.values.firstNotNullOfOrNull { it.radius } ?: 50
        val m = meta(h, date, listOf("fact_visit"))
        SalePosition(m.date, m.till, m.asOf, m.last, hd.zone, hd.route, hd.code, radius, base, out.lastOrNull()?.distance_m, out)
    }
}
