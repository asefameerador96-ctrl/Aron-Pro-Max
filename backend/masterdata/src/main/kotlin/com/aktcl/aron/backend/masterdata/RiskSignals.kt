package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.ServerConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** One embedded location fix of a user-day (a row of `app.geo_fix` that carries coordinates). */
data class FixPoint(
    val id: Long, val userId: Long, val routeId: Long?, val capturedAt: Instant, val lat: Double, val lng: Double, val accuracyM: Double?,
    val isMock: Boolean, val purpose: String, val sourceType: String, val sourceUuid: String,
)

/** A signal a rule found (before it is stored). */
data class RiskHit(val code: String, val severity: Int, val subjectType: String, val subjectId: String, val userId: Long, val routeId: Long?, val evidence: JsonObject)

/** Thresholds of `cfg.geo.*` read at evaluation time; the evidence records the config version used (docs/24 s11.4). */
data class GeoThresholds(
    val maxSpeedKmh: Int, val teleportMinM: Int, val minFixesForJitter: Int, val jitterThresholdM: Int,
    val routePointPct: Int, val routePointRadiusM: Int, val routePointMinVisits: Int, val mismatchM: Int,
) {
    companion object {
        fun from(c: ServerConfig) = GeoThresholds(
            c.int("cfg.geo.max_speed_kmh"), c.int("cfg.geo.teleport_min_distance_m"), c.int("cfg.geo.min_fixes_for_jitter"), c.int("cfg.geo.jitter_threshold_m"),
            c.int("cfg.geo.route_single_point_pct"), c.int("cfg.geo.route_single_point_radius_m"), c.int("cfg.geo.route_single_point_min_visits"), c.int("cfg.geo.device_server_mismatch_m"),
        )
    }
}

/** The pure plausibility rules over the fixes of one user-day or route-day (F-SYS-013); no database, so fixtures replay them. */
object RiskRules {
    fun haversineM(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(bLat - aLat); val dLng = Math.toRadians(bLng - aLng)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }

    /** GEO_MOCK: any fix reported as mocked. */
    fun mock(fixes: List<FixPoint>): RiskHit? {
        val m = fixes.filter { it.isMock }
        if (m.isEmpty()) return null
        return RiskHit("GEO_MOCK", 4, "user", m[0].userId.toString(), m[0].userId, m[0].routeId, buildJsonObject {
            put("mock_fixes", m.size); put("first_source", m[0].sourceUuid)
        })
    }

    /** GEO_TELEPORT: two consecutive fixes at least [GeoThresholds.teleportMinM] apart whose implied speed exceeds the limit. */
    fun teleport(fixes: List<FixPoint>, t: GeoThresholds): RiskHit? {
        val s = fixes.sortedBy { it.capturedAt }
        val hits = mutableListOf<JsonObject>()
        for (i in 1 until s.size) {
            val a = s[i - 1]; val b = s[i]
            val d = haversineM(a.lat, a.lng, b.lat, b.lng)
            if (d < t.teleportMinM) continue
            val secs = (b.capturedAt.toEpochMilli() - a.capturedAt.toEpochMilli()) / 1000.0
            val kmh = if (secs <= 0) Double.POSITIVE_INFINITY else d / secs * 3.6
            if (kmh > t.maxSpeedKmh) hits += buildJsonObject { put("from", a.sourceUuid); put("to", b.sourceUuid); put("distance_m", Math.round(d)); put("seconds", Math.round(secs)); put("kmh", if (kmh.isInfinite()) -1 else Math.round(kmh)) }
        }
        if (hits.isEmpty()) return null
        val f = s[0]
        return RiskHit("GEO_TELEPORT", 3, "user", f.userId.toString(), f.userId, f.routeId, buildJsonObject { put("pairs", hits.size); put("first", hits[0]) })
    }

    /** GEO_ZERO_JITTER: among enough fixes, three or more pairs at least 60 s apart that differ by less than the threshold with identical accuracy. */
    fun zeroJitter(fixes: List<FixPoint>, t: GeoThresholds): RiskHit? {
        val s = fixes.filter { it.accuracyM != null }.sortedBy { it.capturedAt }
        if (s.size < t.minFixesForJitter) return null
        var pairs = 0
        for (i in 1 until s.size) {
            val a = s[i - 1]; val b = s[i]
            if (b.capturedAt.epochSecond - a.capturedAt.epochSecond < 60) continue
            if (a.accuracyM == b.accuracyM && haversineM(a.lat, a.lng, b.lat, b.lng) < t.jitterThresholdM) pairs++
        }
        if (pairs < 3) return null
        val f = s[0]
        return RiskHit("GEO_ZERO_JITTER", 3, "user", f.userId.toString(), f.userId, f.routeId, buildJsonObject { put("fixes", s.size); put("still_pairs", pairs) })
    }

    /** GEO_ROUTE_SINGLE_POINT: most visit-open fixes of a route-day lie within the radius of their median point. */
    fun routeSinglePoint(visitFixes: List<FixPoint>, t: GeoThresholds): RiskHit? {
        if (visitFixes.size < t.routePointMinVisits) return null
        val medLat = visitFixes.map { it.lat }.sorted().let { it[it.size / 2] }
        val medLng = visitFixes.map { it.lng }.sorted().let { it[it.size / 2] }
        val near = visitFixes.count { haversineM(it.lat, it.lng, medLat, medLng) <= t.routePointRadiusM }
        val pct = near * 100.0 / visitFixes.size
        if (pct < t.routePointPct) return null
        val f = visitFixes[0]
        val route = f.routeId ?: return null
        return RiskHit("GEO_ROUTE_SINGLE_POINT", 3, "route", route.toString(), f.userId, route, buildJsonObject { put("visits", visitFixes.size); put("within_radius", near); put("pct", Math.round(pct)) })
    }
}

/**
 * F-SYS-013: computes the server-side anti-spoofing signals of one business date from stored fixes and visits and upserts them
 * into `app.risk_signal` (one row per code, subject and date, so re-evaluation is idempotent). The SR never sees these; the
 * phone's own mock warning is the only visible one. A re-evaluation keeps a reviewer's status and only refreshes score, evidence
 * and the config version; a signal whose data was later voided is left for review rather than deleted.
 * `GEO_DEVICE_SERVER_MISMATCH` reads the visit's `server_verdict`, which the server geo re-check (F-SYS-012) fills.
 */
class RiskSignalEvaluator(private val db: Database, private val config: ServerConfig) {
    fun evaluateDay(date: LocalDate): Int = db.jdbi.inTransaction<Int, Exception> { h -> evaluate(h, date) }

    internal fun evaluate(h: Handle, date: LocalDate): Int {
        val t = GeoThresholds.from(config)
        val weights = (config.value("cfg.geo.integrity_weight") as JsonObject).mapValues { (it.value as JsonPrimitive).doubleOrNull ?: 0.0 }
        val version = config.configVersion()
        val fixes = h.createQuery(
            "SELECT id, user_id, route_id, captured_at, lat, lng, accuracy_m, is_mock, purpose, source_type, source_client_uuid FROM app.geo_fix " +
                "WHERE business_date = :d AND voided_at IS NULL AND fix_status = 'ok' AND lat IS NOT NULL ORDER BY user_id, captured_at",
        ).bind("d", date).map { rs, _ ->
            FixPoint(
                rs.getLong("id"), rs.getLong("user_id"), rs.getObject("route_id") as Long?, rs.getTimestamp("captured_at").toInstant(), rs.getDouble("lat"), rs.getDouble("lng"),
                rs.getObject("accuracy_m") as Double?, rs.getBoolean("is_mock"), rs.getString("purpose"), rs.getString("source_type"), rs.getString("source_client_uuid"),
            )
        }.list()
        val hits = mutableListOf<RiskHit>()
        fixes.groupBy { it.userId }.values.forEach { u ->
            RiskRules.mock(u)?.let(hits::add); RiskRules.teleport(u, t)?.let(hits::add); RiskRules.zeroJitter(u, t)?.let(hits::add)
        }
        fixes.filter { it.purpose == "visit_open" && it.routeId != null }.groupBy { it.routeId }.values.forEach { r -> RiskRules.routeSinglePoint(r, t)?.let(hits::add) }
        hits += mismatches(h, date, t)
        for (hit in hits) store(h, hit, date, weights[hit.code] ?: 0.0, version)
        return hits.size
    }

    private fun mismatches(h: Handle, date: LocalDate, t: GeoThresholds): List<RiskHit> = h.createQuery(
        "SELECT client_uuid, user_id, route_id, verdict, server_verdict, distance_m, server_distance_m FROM app.visit WHERE business_date = :d AND voided_at IS NULL AND server_verdict IS NOT NULL " +
            "AND (server_verdict <> verdict OR (distance_m IS NOT NULL AND server_distance_m IS NOT NULL AND abs(distance_m - server_distance_m) > :m))",
    ).bind("d", date).bind("m", t.mismatchM).map { rs, _ ->
        RiskHit("GEO_DEVICE_SERVER_MISMATCH", 2, "visit", rs.getString("client_uuid"), rs.getLong("user_id"), rs.getObject("route_id") as Long?, buildJsonObject {
            put("device_verdict", rs.getString("verdict")); put("server_verdict", rs.getString("server_verdict"))
            put("device_distance_m", rs.getObject("distance_m") as Double?); put("server_distance_m", rs.getObject("server_distance_m") as Double?)
        })
    }.list()

    private fun store(h: Handle, hit: RiskHit, date: LocalDate, score: Double, version: Long) {
        h.createUpdate(
            "INSERT INTO app.risk_signal (code, severity, business_date, subject_type, subject_id, user_id, route_id, score, evidence, config_version) " +
                "VALUES (:c, :sev, :d, :st, :sid, :u, :r, :score, CAST(:ev AS jsonb), :cv) " +
                "ON CONFLICT (code, subject_type, subject_id, business_date) DO UPDATE SET score = EXCLUDED.score, evidence = EXCLUDED.evidence, config_version = EXCLUDED.config_version, updated_at = now()",
        ).bind("c", hit.code).bind("sev", hit.severity).bind("d", date).bind("st", hit.subjectType).bind("sid", hit.subjectId).bind("u", hit.userId).bind("r", hit.routeId)
            .bind("score", score).bind("ev", hit.evidence.toString()).bind("cv", version).execute()
    }
}

/** The worker job: evaluates today's and yesterday's Dhaka dates every [everyMin] minutes (a late upload dirties its own date). */
class RiskSignalJob(private val evaluator: RiskSignalEvaluator, private val clock: AronClock = AronClock.SYSTEM, private val everyMin: Long = 15) {
    private val log = LoggerFactory.getLogger("aron.risk")

    fun start() {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "risk-signals").apply { isDaemon = true } }.scheduleWithFixedDelay({
            runCatching {
                val today = AdminSupport.today(clock)
                evaluator.evaluateDay(today); evaluator.evaluateDay(today.minusDays(1))
            }.onFailure { log.error("risk signal evaluation failed", it) }
        }, 1, everyMin, TimeUnit.MINUTES)
    }
}
