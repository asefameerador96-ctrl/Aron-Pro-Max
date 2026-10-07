package com.aktcl.aron.backend.sync

import com.aktcl.aron.contract.ConfigScopeType
import com.aktcl.aron.rules.FixInput
import com.aktcl.aron.rules.GeoPolicy
import com.aktcl.aron.rules.GeoVerdict
import com.aktcl.aron.rules.GeoVerdicts
import com.aktcl.aron.rules.MockPolicy
import com.aktcl.aron.rules.NoLocationPolicy
import com.aktcl.aron.rules.OutletGeo
import org.jdbi.v3.core.Handle
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Server geo re-check (F-SYS-012, docs/24 s11.3): the stored visit is judged again with the same function the phone
 * uses ([GeoVerdicts.verdict]), against the outlet pin and the radius **in force at the visit's capture**, and the
 * result is stored beside the phone's verdict (`server_verdict`, `server_distance_m`, `server_radius_m`,
 * `server_max_accuracy_m`, `server_checked_at`). The dashboards count geo-valid calls from `server_verdict = in_range`.
 *
 * - The instant is `captured_at`, clamped into the visit's Asia/Dhaka business date, so a wrong phone clock can never
 *   pick a radius of another day (the row's acceptance: "the radius resolved for the visit's business date").
 * - A mocked fix is never in range: `fix_is_mock`, or a phone verdict of `mocked`, both count as mocked (s11.2 row 2,
 *   docs/21 mock invariant).
 * - The radius is resolved per outlet over the same chain and clamps as the bundle (outlet > route > zone > geo_class >
 *   territory > division > wing > role > global; `radius_min_m..radius_max_m`; 10..5000), with the cfg rows valid at
 *   the instant; `max_accuracy_m` likewise (10..1000); `accuracy_tolerant` at global scope.
 * - The outlet pin is the newest `outlet_location_history` row valid at the instant. An outlet with no history at all
 *   (seeded or migrated master data) uses its current pin; one whose every history row is later has no recorded pin
 *   at capture and is judged `no_outlet_location` (conservative: the phone's own outlet coordinates are never trusted;
 *   the missing pre-edit pin is routed in docs/requests/backend-core-outlet-pin-history.md). A pin cleared (basis none
 *   or placeholder) before the capture is no usable location.
 * - A visit is never refused for its geo verdict (s11.3): the caller runs this in its own savepoint and a failure
 *   leaves `server_verdict` NULL, which [sweep] fills later.
 */
object GeoRecheck {
    private val log = LoggerFactory.getLogger(GeoRecheck::class.java)
    private val DHAKA: ZoneId = ZoneId.of("Asia/Dhaka")

    val KEYS = listOf("cfg.geo.radius_m", "cfg.geo.max_accuracy_m", "cfg.geo.radius_min_m", "cfg.geo.radius_max_m", "cfg.geo.accuracy_tolerant")

    data class Result(val verdict: GeoVerdict, val distanceM: Double?, val radiusM: Int, val maxAccuracyM: Int, val at: Instant)

    private data class VisitRow(
        val clientUuid: String, val businessDate: LocalDate, val capturedAt: Instant, val outletId: Long, val role: String,
        val fixStatus: String?, val lat: Double?, val lng: Double?, val accuracyM: Double?, val isMock: Boolean?, val deviceVerdict: String,
    )

    /** The instant the visit is judged at: `captured_at` inside [start of business date, end of business date). */
    fun judgedAt(capturedAt: Instant, businessDate: LocalDate): Instant {
        val start = businessDate.atStartOfDay(DHAKA).toInstant()
        val lastMs = businessDate.plusDays(1).atStartOfDay(DHAKA).toInstant().minusMillis(1)
        return when {
            capturedAt.isBefore(start) -> start
            capturedAt.isAfter(lastMs) -> lastMs
            else -> capturedAt
        }
    }

    /** Re-checks one stored visit and writes the server columns; null when the visit is not there. */
    fun recheck(h: Handle, visitClientUuid: String, businessDate: LocalDate, now: Instant): Result? {
        val v = h.createQuery(
            """
            SELECT v.client_uuid::text AS cu, v.business_date, v.captured_at, v.outlet_id, u.role, v.fix_status, v.fix_lat, v.fix_lng,
                   v.fix_accuracy_m, v.fix_is_mock, v.verdict
            FROM app.visit v JOIN app.app_user u ON u.id = v.user_id
            WHERE v.client_uuid = CAST(:c AS uuid) AND v.business_date = :d
            """.trimIndent(),
        ).bind("c", visitClientUuid).bind("d", businessDate).map { rs, _ ->
            VisitRow(
                rs.getString("cu"), rs.getObject("business_date", LocalDate::class.java),
                rs.getObject("captured_at", OffsetDateTime::class.java).toInstant(), rs.getLong("outlet_id"), rs.getString("role"),
                rs.getString("fix_status"), rs.getObject("fix_lat") as Double?, rs.getObject("fix_lng") as Double?,
                rs.getObject("fix_accuracy_m") as Double?, rs.getObject("fix_is_mock") as Boolean?, rs.getString("verdict"),
            )
        }.findOne().orElse(null) ?: return null
        val r = judge(h, v)
        h.createUpdate(
            """
            UPDATE app.visit SET server_verdict = :sv, server_distance_m = :sd, server_radius_m = :sr, server_max_accuracy_m = :sa, server_checked_at = :now
            WHERE client_uuid = CAST(:c AS uuid) AND business_date = :d
            """.trimIndent(),
        ).bind("sv", r.verdict.wire).bind("sd", r.distanceM).bind("sr", r.radiusM).bind("sa", r.maxAccuracyM)
            .bind("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).bind("c", v.clientUuid).bind("d", v.businessDate).execute()
        return r
    }

    private fun judge(h: Handle, v: VisitRow): Result {
        val at = judgedAt(v.capturedAt, v.businessDate)
        val o = h.createQuery(
            """
            SELECT o.lat, o.lng, o.provisional_lat, o.provisional_lng, o.location_basis, o.route_id, o.zone_id, z.territory_id, t.division_id, d.wing_id,
                   g.ordinal AS geo_ord, o.updated_at <= :t AS unchanged_since, (SELECT ordinal FROM app.role_def WHERE role = :role) AS role_ord,
                   (SELECT count(*) FROM app.outlet_location_history x WHERE x.outlet_id = o.id) AS hist_n
            FROM app.outlet o
            JOIN app.zone z ON z.id = o.zone_id JOIN app.territory t ON t.id = z.territory_id JOIN app.division d ON d.id = t.division_id
            LEFT JOIN app.geo_class_def g ON g.geo_class = o.geo_class
            WHERE o.id = :o
            """.trimIndent(),
        ).bind("o", v.outletId).bind("role", v.role).bind("t", OffsetDateTime.ofInstant(at, ZoneOffset.UTC)).mapToMap().one()
        fun long(k: String) = (o[k] as Number?)?.toLong()
        fun dbl(k: String) = (o[k] as Number?)?.toDouble()

        // A pin cleared (basis none or placeholder) before the capture: no usable location, whatever the history says
        // (clearing writes no history row; checker finding 2026-10-07). Changed after the capture: history decides.
        val clearedBefore = o["location_basis"] in setOf("none", "placeholder") && o["unchanged_since"] == true
        val pin: OutletGeo = if (clearedBefore) OutletGeo(false, 0.0, 0.0) else if (long("hist_n")!! == 0L) {
            // No history: the current pin (a migrated or seeded outlet), by its basis.
            when (o["location_basis"] as String) {
                "master", "provisional" -> {
                    val prov = o["location_basis"] == "provisional"
                    val lat = (if (prov) dbl("provisional_lat") else dbl("lat")) ?: dbl("lat") ?: dbl("provisional_lat")
                    val lng = (if (prov) dbl("provisional_lng") else dbl("lng")) ?: dbl("lng") ?: dbl("provisional_lng")
                    if (lat != null && lng != null) OutletGeo(true, lat, lng) else OutletGeo(false, 0.0, 0.0)
                }
                else -> OutletGeo(false, 0.0, 0.0)
            }
        } else {
            h.createQuery("SELECT lat, lng FROM app.outlet_location_history WHERE outlet_id = :o AND valid_from <= :t ORDER BY valid_from DESC, id DESC LIMIT 1")
                .bind("o", v.outletId).bind("t", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .map { rs, _ -> OutletGeo(true, rs.getDouble(1), rs.getDouble(2)) }.findOne().orElse(OutletGeo(false, 0.0, 0.0))
        }

        val cfg = ScopedConfig.loadKeysAt(h, KEYS, at)
        val global = ScopedConfig.Chain.of()
        val chain = ScopedConfig.Chain.of(
            ConfigScopeType.ROLE to long("role_ord"), ConfigScopeType.WING to long("wing_id"), ConfigScopeType.DIVISION to long("division_id"),
            ConfigScopeType.TERRITORY to long("territory_id"), ConfigScopeType.GEO_CLASS to long("geo_ord"),
            ConfigScopeType.ZONE to long("zone_id"), ConfigScopeType.ROUTE to long("route_id"), ConfigScopeType.OUTLET to v.outletId,
        )
        // Same clamps as BundleService.outlets, so phone and server agree whenever nothing changed in between.
        val minR = cfg.int("cfg.geo.radius_min_m", global, 20).coerceIn(10, 5000)
        val maxR = cfg.int("cfg.geo.radius_max_m", global, 2000).coerceIn(minR, 5000)
        val radius = cfg.int("cfg.geo.radius_m", chain, 100).coerceIn(minR, maxR).coerceIn(10, 5000)
        val maxAcc = cfg.int("cfg.geo.max_accuracy_m", chain, 100).coerceIn(10, 1000)
        val tolerant = cfg.bool("cfg.geo.accuracy_tolerant", global, false)

        val mocked = v.isMock == true || v.deviceVerdict == GeoVerdict.MOCKED.wire
        val fixOk = v.fixStatus == "ok" && v.lat != null && v.lng != null
        val fix = FixInput(fixOk, v.lat ?: 0.0, v.lng ?: 0.0, v.accuracyM, mocked)
        // The policies change only the action, never the verdict; the server stores the verdict alone.
        val policy = GeoPolicy(MockPolicy.WARN_REP, NoLocationPolicy.FORCE_SALE_REQUIRED, tolerant, refreshCount = 0, refreshMax = 0)
        val res = GeoVerdicts.verdict(fix, pin, radius, maxAcc, policy)
        check(!mocked || res.verdict != GeoVerdict.IN_RANGE) { "a mocked fix is never in range" }
        return Result(res.verdict, res.distanceM?.takeIf { it.isFinite() && it >= 0 }, radius, maxAcc, at)
    }

    /**
     * Fills `server_verdict` for visits of the last [days] business dates that have none (a re-check that failed in
     * ingest, or a visit written outside the batch); at most [limit] per call, each in its own savepoint.
     */
    fun sweep(h: Handle, today: LocalDate, days: Int, limit: Int, now: Instant): Int {
        val todo = h.createQuery(
            // Random order: a row whose re-check always fails can never fill every batch and starve the rest.
            "SELECT client_uuid::text, business_date, route_id FROM app.visit WHERE business_date BETWEEN :from AND :to AND server_verdict IS NULL AND voided_at IS NULL ORDER BY random() LIMIT :n FOR UPDATE SKIP LOCKED",
        ).bind("from", today.minusDays(days.toLong())).bind("to", today).bind("n", limit)
            .map { rs, _ -> Triple(rs.getString(1), rs.getObject(2, LocalDate::class.java), rs.getObject(3) as Long?) }.list()
        var n = 0
        for ((cu, bd, route) in todo) {
            h.savepoint("geo_sweep")
            try {
                if (recheck(h, cu, bd, now) != null) {
                    n++
                    // No domain event comes from a sweep: re-project the route-day so the dashboards see the verdict.
                    route?.let { h.execute("SELECT app.mark_dirty('route_day_agg', ?, ?, 'geo_recheck')", it, bd) }
                }
                h.release("geo_sweep")
            } catch (e: Exception) {
                h.rollbackToSavepoint("geo_sweep")
                log.error("geo re-check failed client_uuid=$cu business_date=$bd", e)
            }
        }
        return n
    }
}
