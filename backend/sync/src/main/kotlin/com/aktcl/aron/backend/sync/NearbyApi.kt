package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.rules.BusinessDate
import com.aktcl.aron.rules.Geo
import io.ktor.http.Parameters
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.cos

/** Contract NearbyOutlet. */
@Serializable
data class NearbyOutletDto(
    val outlet_id: Long, val code: String, val name: String, val lat: Double, val lng: Double, val distance_m: Double, val route_id: Long,
    val cluster_name: String?, val channel: String?, val contact_number: String?,
)

/** Contract NearbyOutlets. */
@Serializable
data class NearbyOutletsDto(val as_of: String, val radius_m: Int, val truncated: Boolean, val items: List<NearbyOutletDto>)

class NearbyDeps(val db: Database, val config: ServerConfig, val reach: ReachResolver, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM)

/**
 * GET /v1/outlets/nearby (contract getNearbyOutlets, F-API-019, D24-77): active outlets with confirmed coordinates
 * and a route, inside the circle and inside the caller's reach on today's Dhaka date (from the token, never the body),
 * nearest first, at most `cfg.tso.periphery_max_markers` with `truncated` when more exist. `radius_m` must be one of
 * `cfg.tso.periphery_radius_options_m`; `zone_id` narrows and must be inside the reach (else 403). Contact numbers only
 * with the `pii` claim. A bounding box on lat/lng picks the candidates; the distance is the shared haversine.
 */
fun Route.nearbyRoutes(d: NearbyDeps) {
    authenticated(d.guard) {
        get("/outlets/nearby") {
            val q = NearbyQuery.parse(call.request.queryParameters, d.config)
            call.respond(withContext(Dispatchers.IO) { nearby(d, call.principal, q) })
        }
    }
}

internal data class NearbyQuery(val lat: Double, val lng: Double, val radiusM: Int, val zoneId: Long?, val maxMarkers: Int) {
    companion object {
        private fun bad(field: String, code: String = "invalid_value"): Nothing =
            throw ApiProblem(ProblemCode.ERR_VALIDATION, "invalid $field", errors = listOf(FieldError("query.$field", code)))

        fun parse(q: Parameters, config: ServerConfig): NearbyQuery {
            val lat = (q["lat"] ?: bad("lat", "required")).toDoubleOrNull()?.takeIf { it.isFinite() && it in 20.0..27.0 } ?: bad("lat", "out_of_range")
            val lng = (q["lng"] ?: bad("lng", "required")).toDoubleOrNull()?.takeIf { it.isFinite() && it in 88.0..93.0 } ?: bad("lng", "out_of_range")
            val radius = (q["radius_m"] ?: bad("radius_m", "required")).toIntOrNull()?.takeIf { it in 10..5000 } ?: bad("radius_m", "out_of_range")
            val options = runCatching { config.value("cfg.tso.periphery_radius_options_m").jsonArray.map { it.jsonPrimitive.int } }.getOrNull() ?: listOf(50, 100, 300)
            if (radius !in options) bad("radius_m", "not_allowed")
            val zone = q["zone_id"]?.let { it.toLongOrNull()?.takeIf { v -> v > 0 } ?: bad("zone_id") }
            val max = runCatching { config.int("cfg.tso.periphery_max_markers") }.getOrDefault(300).coerceIn(50, 2000)
            return NearbyQuery(lat, lng, radius, zone, max)
        }
    }
}

internal fun nearby(d: NearbyDeps, p: AronPrincipal, q: NearbyQuery): NearbyOutletsDto {
    val now = d.clock.now()
    val reach = d.reach.reach(p.userId, p.role, p.scopeVersion, BusinessDate.of(now.toEpochMilli()).toJavaLocalDate())
    if (q.zoneId != null && !reach.coversZone(q.zoneId)) throw ApiProblem(ProblemCode.ERR_OUT_OF_SCOPE, "zone_id is outside your reach")
    val dLat = q.radiusM / 111_320.0
    val dLng = q.radiusM / (111_320.0 * cos(Math.toRadians(q.lat)))
    val rows = d.db.readJdbi.withHandle<List<NearbyOutletDto>, Exception> { h ->
        h.createQuery(
            """
            SELECT o.id, o.code, o.name, o.lat, o.lng, o.route_id, o.zone_id, c.name AS cluster_name, o.channel, o.contact_number
              FROM app.outlet o LEFT JOIN app.cluster c ON c.id = o.cluster_id
             WHERE o.status = 'active' AND o.location_confirmed AND o.route_id IS NOT NULL
               AND o.lat BETWEEN :lat0 AND :lat1 AND o.lng BETWEEN :lng0 AND :lng1
               AND (CAST(:zone AS bigint) IS NULL OR o.zone_id = :zone)
            """.trimIndent(),
        ).bind("lat0", q.lat - dLat).bind("lat1", q.lat + dLat).bind("lng0", q.lng - dLng).bind("lng1", q.lng + dLng).bind("zone", q.zoneId)
            .map { rs, _ ->
                val routeId = rs.getLong("route_id"); val zoneId = rs.getLong("zone_id")
                // The same rule ingest applies to an outlet (own routes only for field reps, else zone or route).
                val visible = if (reach.ownRecordsOnly) routeId in reach.routeIds else reach.coversZone(zoneId) || routeId in reach.routeIds
                if (!visible) return@map null
                val lat = rs.getDouble("lat"); val lng = rs.getDouble("lng")
                val dist = Geo.haversineM(q.lat, q.lng, lat, lng)
                if (dist > q.radiusM) return@map null
                NearbyOutletDto(
                    rs.getLong("id"), rs.getString("code"), rs.getString("name"), lat, lng, Math.round(dist * 10) / 10.0, routeId,
                    rs.getString("cluster_name"), rs.getString("channel"), if (p.pii) rs.getString("contact_number") else null,
                )
            }.list().filterNotNull()
    }.sortedWith(compareBy<NearbyOutletDto> { it.distance_m }.thenBy { it.outlet_id })
    return NearbyOutletsDto(now.wire(), q.radiusM, rows.size > q.maxMarkers, rows.take(q.maxMarkers))
}
