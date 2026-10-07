package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.IngestRecord
import com.aktcl.aron.backend.platform.RecordHandler
import com.aktcl.aron.contract.ConfigScopeType
import com.aktcl.aron.rules.FixInput
import com.aktcl.aron.rules.GeoPolicy
import com.aktcl.aron.rules.GeoVerdicts
import com.aktcl.aron.rules.MockPolicy
import com.aktcl.aron.rules.NoLocationPolicy
import com.aktcl.aron.rules.OutletGeo
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jdbi.v3.core.Handle
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * F-SYS-012: the server re-check of a visit's geo verdict on ingest (docs/24 s11.3). The same shared function as the
 * phone ([GeoVerdicts.verdict]) runs against the outlet location and the radius, accuracy and policy **in force at the
 * visit's capture** (D-431, s9.3 item 6): config changed within `cfg.sys.config_accept_window_h` before the upload is
 * ignored, an older change applies. The result is stored beside the phone's verdict (`server_verdict`,
 * `server_distance_m`, `server_radius_m`, `server_max_accuracy_m`, `server_checked_at`); the visit is never refused for it
 * (the sale happened; the record is evidence). A mismatch is a risk signal the worker derives (`GEO_DEVICE_SERVER_MISMATCH`).
 *
 * Runs once, on first store, inside the record's savepoint; the update is guarded by `server_checked_at IS NULL`.
 */
class GeoRecheckHandler : RecordHandler {
    override val types: Set<String> = setOf("visit")

    override fun afterStored(h: Handle, rec: IngestRecord, serverId: Long?) {
        val v = h.createQuery(
            """
            SELECT v.captured_at, v.outlet_id, v.fix_status, v.fix_lat, v.fix_lng, v.fix_accuracy_m, v.fix_is_mock, u.role
            FROM app.visit v JOIN app.app_user u ON u.id = v.user_id
            WHERE v.client_uuid = CAST(:u AS uuid) AND v.business_date = :bd AND v.server_checked_at IS NULL
            """.trimIndent(),
        ).bind("u", rec.clientUuid).bind("bd", rec.businessDate).map { rs, _ ->
            VisitFix(
                rs.getObject("captured_at", OffsetDateTime::class.java).toInstant(), rs.getLong("outlet_id"), rs.getString("fix_status"),
                rs.getObject("fix_lat") as Double?, rs.getObject("fix_lng") as Double?, rs.getObject("fix_accuracy_m") as Double?,
                rs.getObject("fix_is_mock") as Boolean?, rs.getString("role"),
            )
        }.findOne().orElse(null) ?: return
        val r = recheck(h, v, rec.receivedAt)
        h.createUpdate(
            """
            UPDATE app.visit SET server_verdict = :sv, server_distance_m = :sd, server_radius_m = :sr, server_max_accuracy_m = :sa, server_checked_at = :at
            WHERE client_uuid = CAST(:u AS uuid) AND business_date = :bd AND server_checked_at IS NULL
            """.trimIndent(),
        ).bind("sv", r.verdict).bind("sd", r.distanceM).bind("sr", r.radiusM).bind("sa", r.maxAccuracyM)
            .bind("at", OffsetDateTime.ofInstant(rec.receivedAt, ZoneOffset.UTC)).bind("u", rec.clientUuid).bind("bd", rec.businessDate).execute()
    }

    internal data class VisitFix(
        val capturedAt: Instant, val outletId: Long, val fixStatus: String?, val lat: Double?, val lng: Double?,
        val accuracyM: Double?, val isMock: Boolean?, val role: String,
    )

    data class Result(val verdict: String, val distanceM: Double?, val radiusM: Int, val maxAccuracyM: Int)

    internal fun recheck(h: Handle, v: VisitFix, receivedAt: Instant): Result {
        val global = ScopedConfig.Chain.of()
        // D-431: judge against the values in force at capture, unless the change is older than the accept window.
        val windowH = ScopedConfig.load(h, receivedAt, receivedAt, setOf(WINDOW_KEY)).int(WINDOW_KEY, global, 48).coerceIn(0, 168)
        val evalAt = maxOf(v.capturedAt, receivedAt.minusSeconds(windowH * 3600L)).let { if (it.isAfter(receivedAt)) receivedAt else it }
        val cfg = ScopedConfig.load(h, evalAt, evalAt, KEYS)
        val o = h.createQuery(
            """
            SELECT o.zone_id, o.route_id, z.territory_id, t.division_id, d.wing_id, g.ordinal AS geo_ord,
                   o.location_basis, o.lat, o.lng, o.provisional_lat, o.provisional_lng,
                   (SELECT ordinal FROM app.role_def WHERE role = :role) AS role_ord,
                   hl.lat AS h_lat, hl.lng AS h_lng, hl.basis AS h_basis
            FROM app.outlet o
            JOIN app.zone z ON z.id = o.zone_id JOIN app.territory t ON t.id = z.territory_id JOIN app.division d ON d.id = t.division_id
            LEFT JOIN app.geo_class_def g ON g.geo_class = o.geo_class
            LEFT JOIN LATERAL (SELECT lat, lng, basis FROM app.outlet_location_history
                               WHERE outlet_id = o.id AND valid_from <= :at ORDER BY valid_from DESC, id DESC LIMIT 1) hl ON true
            WHERE o.id = :o
            """.trimIndent(),
        ).bind("o", v.outletId).bind("role", v.role).bind("at", OffsetDateTime.ofInstant(evalAt, ZoneOffset.UTC)).mapToMap().one()
        fun long(k: String) = (o[k] as Number?)?.toLong()
        fun dbl(k: String) = (o[k] as Number?)?.toDouble()
        val chain = ScopedConfig.Chain.of(
            ConfigScopeType.ROLE to long("role_ord"), ConfigScopeType.WING to long("wing_id"), ConfigScopeType.DIVISION to long("division_id"),
            ConfigScopeType.TERRITORY to long("territory_id"), ConfigScopeType.GEO_CLASS to long("geo_ord"),
            ConfigScopeType.ZONE to long("zone_id"), ConfigScopeType.ROUTE to long("route_id"), ConfigScopeType.OUTLET to v.outletId,
        )
        // Same resolution and clamps as the bundle (BundleService.outlets), so phone and server agree on unchanged config.
        val minR = cfg.int("cfg.geo.radius_min_m", global, 20).coerceIn(10, 5000)
        val maxR = cfg.int("cfg.geo.radius_max_m", global, 2000).coerceIn(minR, 5000)
        val radius = cfg.int("cfg.geo.radius_m", chain, 100).coerceIn(minR, maxR).coerceIn(10, 5000)
        val maxAcc = cfg.int("cfg.geo.max_accuracy_m", chain, 100).coerceIn(10, 1000)
        val mock = cfg.value("cfg.geo.mock_policy", chain)?.let { e -> MockPolicy.entries.firstOrNull { it.wire == e.jsonPrimitive.content } } ?: MockPolicy.BLOCK_SALE
        val noLoc = cfg.value("cfg.geo.no_location_policy", chain)?.let { e -> NoLocationPolicy.entries.firstOrNull { it.wire == e.jsonPrimitive.content } }
            ?: NoLocationPolicy.FORCE_SALE_REQUIRED
        val tolerant = cfg.value("cfg.geo.accuracy_tolerant", global)?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } ?: false
        // The outlet location at capture: the latest history row by then; with none, the outlet row (seed or import).
        val (oLat, oLng, usable) = when {
            dbl("h_lat") != null -> Triple(dbl("h_lat")!!, dbl("h_lng")!!, true)
            o["location_basis"] == "master" && dbl("lat") != null && dbl("lng") != null -> Triple(dbl("lat")!!, dbl("lng")!!, true)
            o["location_basis"] == "provisional" -> {
                val lat = dbl("provisional_lat") ?: dbl("lat")
                val lng = dbl("provisional_lng") ?: dbl("lng")
                if (lat != null && lng != null) Triple(lat, lng, true) else Triple(0.0, 0.0, false)
            }
            else -> Triple(0.0, 0.0, false)
        }
        val fix = FixInput(v.fixStatus == "ok" && v.lat != null && v.lng != null, v.lat ?: 0.0, v.lng ?: 0.0, v.accuracyM, v.isMock == true)
        // Refreshes are spent by the time a visit is stored; the action is the phone's and is not re-judged.
        val res = GeoVerdicts.verdict(fix, OutletGeo(usable, oLat, oLng), radius, maxAcc, GeoPolicy(mock, noLoc, tolerant, 0, 0))
        return Result(res.verdict.wire, res.distanceM, radius, maxAcc)
    }

    companion object {
        private const val WINDOW_KEY = "cfg.sys.config_accept_window_h"
        private val KEYS = setOf(
            "cfg.geo.radius_m", "cfg.geo.radius_min_m", "cfg.geo.radius_max_m", "cfg.geo.max_accuracy_m",
            "cfg.geo.mock_policy", "cfg.geo.no_location_policy", "cfg.geo.accuracy_tolerant",
        )
    }
}
