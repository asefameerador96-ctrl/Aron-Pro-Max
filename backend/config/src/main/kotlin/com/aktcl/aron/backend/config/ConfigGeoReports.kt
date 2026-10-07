package com.aktcl.aron.backend.config

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate
import java.time.ZoneId

@Serializable data class NodeRefDto(val type: String, val id: Long, val code: String?, val name: String?)
@Serializable data class DensityRow(val node: NodeRefDto, val outlets: Int, val median_neighbours: List<Double>, val density_index_pct: Double?)
@Serializable data class DensityReport(val as_of: String, val radii_m: List<Int>, val rows: List<DensityRow>)
@Serializable data class HistBucket(val upper_m: Int, val visits: Int)
@Serializable data class CalibrationRow(val territory_id: Long, val geo_class: String?, val visits: Int, val histogram: List<HistBucket>, val force_sale_pct: Double?, val suggested_radius_m: Int?)
@Serializable data class CalibrationReport(val as_of: String, val rows: List<CalibrationRow>)

/**
 * Outlet density and geofence calibration from stored data (row F-API-060). Density is per zone: for each located outlet the
 * neighbours within each of `cfg.geo.density_neighbour_radii_m`, the median per radius, and the index = share of outlets with at
 * least one neighbour within the second radius (the first when only one). Calibration is per territory and geo class over the last
 * 90 business days of visits: distance histogram, force-sale share and, from `cfg.geo.calibration_min_visits` visits, the
 * 90th-percentile distance (rounded up to 5 m, clamped 10..5000) as the suggested radius.
 */
class ConfigGeoReports(private val db: Database, private val service: ConfigService, private val resolver: ConfigResolver, private val clock: AronClock = AronClock.SYSTEM) {
    private val global = listOf(ScopeNode("global", 0))
    private val bounds = listOf(25, 50, 75, 100, 150, 200, 300, 500, 1000, 5000, 1_000_000)

    private fun scope(type: String?, id: Long?): Pair<String, Long> {
        val t = type ?: "global"; val i = id ?: 0L
        if (t !in Precedence.rank || (t == "global") != (i == 0L) || !resolver.nodeExists(t, i)) throw ApiProblem(ProblemCode.ERR_VALIDATION, "unknown scope node")
        return t to i
    }

    /** Predicate on outlet `o`: route and outlet scopes are exact; every other level is its set of zones. */
    private fun outletScope(t: String) = when (t) { "route" -> "o.route_id = :id"; "outlet" -> "o.id = :id"; else -> "o.zone_id IN (SELECT z.id FROM app.zone z WHERE ${service.zoneFilterSql(t)})" }

    private fun meters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0; val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val a = Math.sin((p2 - p1) / 2).let { it * it } + Math.cos(p1) * Math.cos(p2) * Math.sin(Math.toRadians(lng2 - lng1) / 2).let { it * it }
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)))
    }

    fun density(type: String?, id: Long?): DensityReport {
        val (t, i) = scope(type, id)
        val radii = ((resolver.resolve("cfg.geo.density_neighbour_radii_m", global, clock.now()).value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: listOf(25, 50, 100, 150, 300)).sorted()
        val byZone = db.jdbi.withHandle<Map<Long, List<Pair<Double, Double>>>, Exception> { h ->
            h.createQuery("SELECT o.zone_id, o.lat, o.lng FROM app.outlet o WHERE o.status = 'active' AND o.lat IS NOT NULL AND o.lng IS NOT NULL AND ${outletScope(t)} ORDER BY o.zone_id, o.id")
                .also { q -> if (t != "global") q.bind("id", i) }.map { rs, _ -> rs.getLong(1) to (rs.getDouble(2) to rs.getDouble(3)) }.list().groupBy({ it.first }, { it.second })
        }
        val names = db.jdbi.withHandle<Map<Long, Pair<String, String>>, Exception> { h -> h.createQuery("SELECT id, code, name FROM app.zone").map { rs, _ -> rs.getLong(1) to (rs.getString(2) to rs.getString(3)) }.list().toMap() }
        val idx = if (radii.size > 1) 1 else 0
        val rows = byZone.entries.sortedBy { it.key }.take(5000).map { (zone, pts) ->
            val counts = Array(pts.size) { IntArray(radii.size) }
            for (a in pts.indices) for (b in a + 1 until pts.size) {
                val d = meters(pts[a].first, pts[a].second, pts[b].first, pts[b].second)
                for (r in radii.indices) if (d <= radii[r]) { counts[a][r]++; counts[b][r]++ }
            }
            val medians = radii.indices.map { r -> val s = counts.map { it[r] }.sorted(); if (s.isEmpty()) 0.0 else if (s.size % 2 == 1) s[s.size / 2].toDouble() else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0 }
            val pct = if (pts.isEmpty()) null else Math.round(counts.count { it[idx] > 0 } * 1000.0 / pts.size) / 10.0
            DensityRow(NodeRefDto("zone", zone, names[zone]?.first, names[zone]?.second), pts.size, medians, pct)
        }
        return DensityReport(clock.now().wire(), radii, rows)
    }

    fun calibration(type: String?, id: Long?): CalibrationReport {
        val (t, i) = scope(type, id)
        val minVisits = (resolver.resolve("cfg.geo.calibration_min_visits", global, clock.now()).value as? JsonPrimitive)?.intOrNull ?: 500
        val since = LocalDate.ofInstant(clock.now(), ZoneId.of("Asia/Dhaka")).minusDays(90)
        data class V(val territory: Long, val geoClass: String?, val d: Double, val force: Boolean)
        val visits = db.jdbi.withHandle<List<V>, Exception> { h ->
            h.createQuery(
                "SELECT z.territory_id, o.geo_class, COALESCE(v.server_distance_m, v.distance_m) AS d, v.geo_action = 'force_sale' AS f FROM app.visit v JOIN app.outlet o ON o.id = v.outlet_id JOIN app.zone z ON z.id = o.zone_id " +
                    "WHERE v.business_date >= :since AND v.voided_at IS NULL AND v.verdict IN ('in_range','out_of_range') AND COALESCE(v.server_distance_m, v.distance_m) IS NOT NULL " +
                    "AND ${outletScope(t)}",
            ).bind("since", since).also { q -> if (t != "global") q.bind("id", i) }.map { rs, _ -> V(rs.getLong(1), rs.getString(2), rs.getDouble(3), rs.getBoolean(4)) }.list()
        }
        val rows = visits.groupBy { it.territory to it.geoClass }.entries.sortedWith(compareBy({ it.key.first }, { it.key.second ?: "" })).take(5000).map { (k, vs) ->
            val hist = bounds.mapIndexed { n, ub -> HistBucket(ub, vs.count { it.d <= ub && (n == 0 || it.d > bounds[n - 1]) }) }
            val sorted = vs.map { it.d }.sorted()
            val suggested = if (vs.size >= minVisits) Math.ceil(sorted[minOf(sorted.size - 1, Math.ceil(sorted.size * 0.9).toInt() - 1)] / 5.0).toInt() * 5 else null
            CalibrationRow(k.first, k.second, vs.size, hist, Math.round(vs.count { it.force } * 1000.0 / vs.size) / 10.0, suggested?.coerceIn(10, 5000))
        }
        return CalibrationReport(clock.now().wire(), rows)
    }
}
