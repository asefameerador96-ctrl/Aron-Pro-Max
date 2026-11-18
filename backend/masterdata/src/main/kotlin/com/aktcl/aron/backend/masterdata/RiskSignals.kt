package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.exitIfFatal
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

    /**
     * Contract 1.20.0 (D-SV2-15): the worker rule that withdraws a `CHECKOUT_WITHOUT_SUBMIT` signal once an effective Sales Submit
     * ([effectiveSubmit]: a `day_submit` not data-voided, its route-day or supervisor-day not submit-voided) of the same user and business date is stored,
     * however it arrived (the rep's own Sales Submit after the check-out, a later cycle, an integrity-released or re-sent one). The ingest
     * raises the signal but its database role may not update `risk_signal`; the worker may (`worker_rw`: INSERT, UPDATE), so the
     * withdrawal is this rule and no grant changed. Only a still `open` signal moves: a reviewer's `reviewed`, `dismissed` or `confirmed`
     * always stands. A withdrawn signal is `dismissed` with `evidence.withdrawn_by = "day_submit"`, `withdrawn_at` and `day_submit_client_uuid` ADDED to the
     * original evidence (`day_submit_found: false` keeps saying what the ingest saw); the status set has no `withdrawn`, and the dashboards count only `open` and `confirmed`.
     * The user's route-day aggregates are marked dirty, so the dashboards' suspicious counts stop counting it (the reviewer path in `RiskSignalApi` has the same old gap and is not
     * changed here). A submit voided or superseded AFTER the withdrawal does not re-raise the signal: the day stays withdrawn; the void trail shows it.
     * Idempotent (a second run finds nothing open); returns how many moved. Looks back [lookbackDays] business dates.
     */
    fun withdrawCheckoutWithoutSubmit(today: LocalDate): Int =
        db.jdbi.inTransaction<Int, Exception> { h -> withdrawCheckoutWithoutSubmit(h, today) }

    fun withdrawCheckoutWithoutSubmit(h: Handle, today: LocalDate): Int {
        val moved = h.createQuery(
            """
            WITH found AS (
              SELECT s.id, (SELECT x.client_uuid ${effectiveSubmit("x")} AND x.user_id = s.user_id AND x.business_date = s.business_date
                             ORDER BY x.captured_at DESC, x.id DESC LIMIT 1) AS submit_uuid
                FROM app.risk_signal s
               WHERE s.code = 'CHECKOUT_WITHOUT_SUBMIT' AND s.status = 'open' AND s.subject_type = 'user' AND s.user_id IS NOT NULL
                 AND s.business_date >= :since)
            UPDATE app.risk_signal s
               SET status = 'dismissed', updated_at = now(),
                   evidence = s.evidence || jsonb_build_object('withdrawn_by', 'day_submit', 'withdrawn_at', to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), 'day_submit_client_uuid', found.submit_uuid::text)
              FROM found
             WHERE s.id = found.id AND found.submit_uuid IS NOT NULL AND s.status = 'open'
            RETURNING s.user_id, s.business_date
            """.trimIndent(),
        ).bind("since", today.minusDays(lookbackDays().toLong())).map { rs, _ -> rs.getLong(1) to rs.getObject(2, LocalDate::class.java) }.list()
        moved.forEach { (user, date) -> markUserRoutesDirty(h, user, date, "risk_signal_withdrawn") }
        return moved.size
    }

    /**
     * The mirror of the withdrawal, so the rule is complete in both directions (lead review of 2f5922ee, low 4): a REAL check-out (`closed_by = 'rep'`) of an SR or AMO whose
     * user-day has route captures in the ingest registry ([ROUTE_CAPTURE_TYPES]) but NO Sales Submit at all, and has no `CHECKOUT_WITHOUT_SUBMIT` signal yet, raises it. The ingest
     * judges only at the moment the check-out is stored, so captures first registered in a later batch were never judged; this catches them. Decisions (lead review of ee82e904):
     * (a) GRACE: a check-out received less than `cfg.day.submit_settle_timeout_min` (30) minutes before [now] is not judged yet, so a rep whose captures and submit arrive in a later batch is
     * not flagged and then withdrawn; (b) a day with ANY `day_submit` record (even one an admin later data-voided) is not raised: the rep did submit, the void is the admin's act and its
     * trail is the void log (the ingest's own judgement at check-out time still counts a voided submit as none, as before); (c) the scan looks back `cfg.sync.max_backdate_days` + 1
     * business dates only (a record cannot arrive later than the sync window, so older days cannot change); (d) the signal carries the check-out's `route_id` like the ingest raise. An existing
     * row of any status (open, withdrawn, reviewed) is left alone (`ON CONFLICT DO NOTHING`). The evidence says `raised_by: worker`. Returns how many were raised.
     */
    fun raiseCheckoutWithoutSubmit(today: LocalDate, now: Instant = Instant.now()): Int =
        db.jdbi.inTransaction<Int, Exception> { h -> raiseCheckoutWithoutSubmit(h, today, now) }

    fun raiseCheckoutWithoutSubmit(h: Handle, today: LocalDate, now: Instant = Instant.now()): Int {
        val weight = runCatching { (config.value("cfg.geo.integrity_weight") as JsonObject)["CHECKOUT_WITHOUT_SUBMIT"]?.jsonPrimitive?.content?.toDoubleOrNull() }.getOrNull() ?: 20.0
        val graceMin = runCatching { config.int("cfg.day.submit_settle_timeout_min") }.getOrDefault(30).coerceIn(5, 240)
        val raised = h.createQuery(
            """
            INSERT INTO app.risk_signal (code, severity, business_date, subject_type, subject_id, user_id, route_id, score, evidence, config_version)
            SELECT 'CHECKOUT_WITHOUT_SUBMIT', 2, e.business_date, 'user', e.user_id::text, e.user_id, e.route_id, :score,
                   jsonb_build_object('checkout_client_uuid', e.client_uuid::text, 'checked_out_at', to_char(e.captured_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                                      'day_submit_found', false, 'raised_by', 'worker'), :cv
              FROM app.attendance_event e JOIN app.app_user u ON u.id = e.user_id AND u.role IN ('SR', 'AMO')
             WHERE e.kind = 'check_out' AND e.closed_by = 'rep' AND e.voided_at IS NULL AND e.business_date >= :since AND e.received_at <= :cutoff
               AND EXISTS (SELECT 1 FROM app.ingest_registry r WHERE r.user_id = e.user_id AND r.business_date = e.business_date AND r.record_type = ANY(:types))
               AND NOT EXISTS (SELECT 1 FROM app.route_day_event x WHERE x.kind = 'day_submit' AND x.user_id = e.user_id AND x.business_date = e.business_date)
            ON CONFLICT (code, subject_type, subject_id, business_date) DO NOTHING
            RETURNING user_id, business_date
            """.trimIndent(),
        ).bind("since", today.minusDays(lookbackDays().toLong())).bind("cutoff", java.time.OffsetDateTime.ofInstant(now.minusSeconds(graceMin * 60L), java.time.ZoneOffset.UTC))
            .bind("score", weight.coerceIn(0.0, 300.0)).bind("cv", config.configVersion())
            .bindArray("types", String::class.java, ROUTE_CAPTURE_TYPES.toList()).map { rs, _ -> rs.getLong(1) to rs.getObject(2, LocalDate::class.java) }.list()
        raised.forEach { (user, date) -> markUserRoutesDirty(h, user, date, "risk_signal_raised") }
        return raised.size
    }

    /** How far back the two rules look: the sync backdate window plus one day (a record cannot reach the server later, so an older day cannot change). Default 7 + 1. */
    private fun lookbackDays(): Int = (runCatching { config.int("cfg.sync.max_backdate_days") }.getOrDefault(7) + 1).coerceIn(2, 31)

    /** The suspicious counts of the aggregates are per user and day across the user's routes: every route the user held that day (assignment or stand-in) is rebuilt. */
    private fun markUserRoutesDirty(h: Handle, userId: Long, date: LocalDate, why: String) {
        val routes = h.createQuery(
            "SELECT route_id FROM app.route_assignment WHERE user_id = :u AND valid_from <= :d AND (valid_to IS NULL OR valid_to > :d) " +
                "UNION SELECT route_id FROM app.route_day WHERE acting_user_id = :u AND business_date = :d",
        ).bind("u", userId).bind("d", date).mapTo(Long::class.java).list()
        routes.forEach { r -> h.createQuery("SELECT app.mark_dirty('route_day_agg', :r, :d, :why)").bind("r", r).bind("d", date).bind("why", why).mapToMap().list() }
    }

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

/** SQL shared by the withdrawal and the raise rule: the `FROM ... WHERE` of an effective Sales Submit under alias [a] (the caller adds the user and date predicates with AND). */
private fun effectiveSubmit(a: String) = """FROM app.route_day_event $a
                              LEFT JOIN app.route_day rd ON $a.scope = 'route_day' AND rd.route_id = $a.route_id AND rd.business_date = $a.business_date
                              LEFT JOIN app.supervisor_day sd ON $a.scope = 'supervisor_day' AND sd.user_id = $a.user_id AND sd.business_date = $a.business_date
                             WHERE $a.kind = 'day_submit' AND $a.voided_at IS NULL AND NOT coalesce(rd.submit_voided, false) AND NOT coalesce(sd.submit_voided, false)"""

/** The record types that are route captures of a day (the day-lock figure types of the ingest, `IngestService.DAY_LOCKED_TYPES`; a test in backend:app keeps the two equal). */
val ROUTE_CAPTURE_TYPES: Set<String> = setOf(
    "visit", "visit_close", "visit_skip", "sale_abort", "memo", "memo_line", "memo_discount", "qc_line", "memo_void", "due_collection",
    "stock_movement", "redemption", "redemption_line",
)

/** The worker job: evaluates today's and yesterday's Dhaka dates every [everyMin] minutes (a late upload dirties its own date). */
class RiskSignalJob(private val evaluator: RiskSignalEvaluator, private val clock: AronClock = AronClock.SYSTEM, private val everyMin: Long = 15) {
    private val log = LoggerFactory.getLogger("aron.risk")

    fun start() {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "risk-signals").apply { isDaemon = true } }.scheduleWithFixedDelay({
            val today = runCatching { AdminSupport.today(clock) }.getOrNull() ?: return@scheduleWithFixedDelay
            // Each rule in its own guard: a failure of one (a config or geo error in evaluateDay) never stops the others (lead review of 2f5922ee, low 5).
            runCatching { evaluator.evaluateDay(today); evaluator.evaluateDay(today.minusDays(1)) }
                .onFailure { it.exitIfFatal(log, "risk signals"); log.error("risk signal evaluation failed", it) }
            runCatching { evaluator.withdrawCheckoutWithoutSubmit(today).let { if (it > 0) log.info("checkout-without-submit signals withdrawn: {}", it) } }
                .onFailure { it.exitIfFatal(log, "risk signal withdrawal"); log.error("checkout-without-submit withdrawal failed", it) }
            runCatching { evaluator.raiseCheckoutWithoutSubmit(today, clock.now()).let { if (it > 0) log.info("checkout-without-submit signals raised late: {}", it) } }
                .onFailure { it.exitIfFatal(log, "risk signal late raise"); log.error("checkout-without-submit late raise failed", it) }
        }, 1, everyMin, TimeUnit.MINUTES)
    }
}
