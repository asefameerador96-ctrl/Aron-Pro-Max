package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.exitIfFatal
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.ServerConfig
import kotlinx.serialization.json.Json
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

/** One fix's phone signals (contract 1.19.0 `GeoFix.signals`, only fixes that carry at least one code). [phone] names the phone: its device id, else `user-<id>`. */
data class PhoneSignalFix(val phone: String, val userId: Long, val routeId: Long?, val sourceUuid: String, val signals: List<String>)

/** One developer-options gate onset from a status report (contract 1.19.0 `DeviceStatusReport.integrity_events`). */
data class IntegrityEventRow(val phone: String, val userId: Long, val atUtc: String, val flags: List<String>, val exempt: String)

/**
 * Reads the stored `integrity_events` of one status report. The report is stored as the phone sent it (the ingest checks its shape, but rows stored before that
 * check, or by a newer phone, can hold anything), so this NEVER throws: an element that is not an object, has no readable timestamp or holds a non-string is skipped
 * and counted in [skipped]; only the known flag codes are kept (an unknown one is dropped, never trusted), and an unknown `exempt` code reads as `unknown`, which is
 * never counted as a blocked onset.
 */
object IntegrityEventReader {
    val FLAGS = setOf("dev_options", "adb", "adb_wifi")
    val EXEMPT = setOf("none", "debug_build", "benchmark_build", "lab")

    class Read(val events: List<IntegrityEventRow>, val skipped: Int)

    fun read(phone: String, userId: Long, json: String?): Read {
        val arr = runCatching { json?.let { Json.parseToJsonElement(it) } }.getOrNull() as? kotlinx.serialization.json.JsonArray ?: return Read(emptyList(), if (json == null) 0 else 1)
        var skipped = 0
        val out = ArrayList<IntegrityEventRow>()
        for (e in arr) {
            val o = e as? JsonObject
            val at = (o?.get("at") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { runCatching { Instant.parse(it) }.isSuccess }
            val rawFlags = o?.get("flags") as? kotlinx.serialization.json.JsonArray
            if (o == null || at == null || rawFlags == null) { skipped++; continue }
            val flags = rawFlags.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.filter { it in FLAGS }.distinct().sorted()
            val exempt = (o["exempt"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in EXEMPT } ?: "unknown"
            out += IntegrityEventRow(phone, userId, at, flags, exempt)
        }
        return Read(out, skipped)
    }
}

/**
 * The phone's own anti-spoofing codes (D-OWNER-MDM-02, contract 1.19.0) mapped onto the existing risk codes, one hit per code and PHONE
 * (subject type `device`, so they never overwrite the user-level hits of the server rules above). Codes the server does not know are kept
 * on the fix but make no hit. Evidence is the count per phone code, never a position.
 */
object PhoneSignalRules {
    /** phone code -> (risk code, severity). */
    private val MAP: Map<String, Pair<String, Int>> = mapOf(
        "mock_flag" to ("GEO_MOCK" to 4), "mock_app_selected" to ("GEO_MOCK" to 4), "test_provider" to ("GEO_MOCK" to 4),
        "test_provider_props" to ("GEO_GNSS_INCONSISTENT" to 3), "gnss_no_sats" to ("GEO_GNSS_INCONSISTENT" to 3),
        "gnss_none" to ("GEO_GNSS_INCONSISTENT" to 3), "provider_disagree" to ("GEO_GNSS_INCONSISTENT" to 3),
        "fix_stale" to ("GEO_STALE_FIX" to 2), "fix_clock_mismatch" to ("GEO_GNSS_TIME_SKEW" to 2),
        "jump" to ("GEO_TELEPORT" to 3), "speed" to ("GEO_TELEPORT" to 3),
        "fake_gps_app_installed" to ("DEVICE_MOCK_APP_PRESENT" to 3),
        "root_hint" to ("DEVICE_INTEGRITY_FAIL" to 3), "emulator" to ("DEVICE_INTEGRITY_FAIL" to 3), "hook_framework" to ("DEVICE_INTEGRITY_FAIL" to 3),
    )

    /**
     * One hit per risk code, phone AND user: on a shared phone each rep's codes make their own row (their own `user_id`, so portal scope and the reviewer's view are right),
     * never one row attributed to the first rep. [disambiguate] gives rows of the same phone and code distinct subject ids.
     */
    fun fromFixes(fixes: List<PhoneSignalFix>): List<RiskHit> = fixes.groupBy { it.phone to it.userId }.flatMap { (key, list) ->
        val (phone, userId) = key
        val perRisk = linkedMapOf<String, MutableList<Pair<String, PhoneSignalFix>>>()
        list.forEach { f -> f.signals.distinct().forEach { code -> MAP[code]?.let { (risk, _) -> perRisk.getOrPut(risk) { mutableListOf() } += code to f } } }
        perRisk.map { (risk, hits) ->
            val first = hits.first().second
            RiskHit(risk, hits.maxOf { MAP.getValue(it.first).second }, "device", phone, userId, first.routeId, buildJsonObject {
                put("source", "phone_signals")
                put("fixes", hits.map { it.second.sourceUuid }.distinct().size)
                put("codes", buildJsonObject { hits.groupingBy { it.first }.eachCount().toSortedMap().forEach { (c, n) -> put(c, n) } })
                put("first_source", first.sourceUuid)
            })
        }
    }

    /**
     * Rows of one risk code on one phone but of DIFFERENT users (a shared phone) must not collide on the unique key (code, subject type, subject id, date): they get the subject id
     * `<phone>#<user id>`. A phone with one user keeps the plain phone id, which is the device UUID for an enrolled phone (the same id the existing DEVICE_INTEGRITY_FAIL rows use).
     */
    fun disambiguate(hits: List<RiskHit>): List<RiskHit> {
        val shared = hits.filter { it.subjectType == "device" }.groupBy { it.code to it.subjectId }.filterValues { rows -> rows.map { it.userId }.distinct().size > 1 }.keys
        return hits.map { h -> if (h.subjectType == "device" && (h.code to h.subjectId) in shared) h.copy(subjectId = "${h.subjectId}#${h.userId}") else h }
    }

    /** DEVICE_DEBUG_ENABLED when the gate blocked the app (exempt `none`) at least once; a repeated event counts once (identity: at + flags). Exempt events are only counted in the evidence. */
    fun fromIntegrityEvents(events: List<IntegrityEventRow>): List<RiskHit> = events.distinctBy { listOf(it.phone, it.userId, runCatching { Instant.parse(it.atUtc).toEpochMilli() }.getOrDefault(it.atUtc), it.flags) }.groupBy { it.phone to it.userId }.mapNotNull { (key, list) ->
        val (phone, userId) = key
        val blocked = list.filter { it.exempt == "none" }
        if (blocked.isEmpty()) return@mapNotNull null
        RiskHit("DEVICE_DEBUG_ENABLED", 3, "device", phone, userId, null, buildJsonObject {
            put("source", "integrity_events"); put("blocked_events", blocked.size); put("exempt_events", list.size - blocked.size)
            put("flags", buildJsonObject { blocked.flatMap { it.flags }.groupingBy { it }.eachCount().toSortedMap().forEach { (c, n) -> put(c, n) } })
        })
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
    private val log = LoggerFactory.getLogger("aron.risk")

    private companion object {
        const val LATE_REPORT_DAYS = 1
        val DHAKA: java.time.ZoneId = java.time.ZoneId.of("Asia/Dhaka")
    }

    fun evaluateDay(date: LocalDate): Int = db.jdbi.inTransaction<Int, Exception> { h -> evaluate(h, date) }

    /**
     * One source of hits (a rule family, a query) or one stored row must never stop the rest: stored data comes from phones, and a tampered or newer phone can
     * store something no rule expected. A failing source is logged (its kind only, never the data) and skipped; it runs inside a savepoint so a database error of
     * one source does not abort the transaction of the others. Only a fatal error (out of memory) is rethrown.
     */
    private fun <T> isolated(h: Handle, what: String, date: LocalDate, fallback: T, block: () -> T): T {
        val sp = "risk_" + what.replace(Regex("[^a-z0-9_]"), "_")
        h.savepoint(sp)
        return try {
            block().also { h.release(sp) }
        } catch (e: Exception) {
            runCatching { h.rollbackToSavepoint(sp); h.release(sp) }
            log.error("risk source $what skipped for $date: ${e.javaClass.simpleName}")
            fallback
        }
    }

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
     * user-day has route captures in the ingest registry ([ROUTE_CAPTURE_TYPES]; a web paper backfill, registry flag `manual_entry`, is not one: it stays exempt) but NO Sales Submit at all, and has no `CHECKOUT_WITHOUT_SUBMIT` signal yet, raises it. The ingest
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
               AND EXISTS (SELECT 1 FROM app.ingest_registry r WHERE r.user_id = e.user_id AND r.business_date = e.business_date AND r.record_type = ANY(:types) AND NOT ('manual_entry' = ANY(coalesce(r.flags, '{}'))))
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
        // One user's rules never stop another's; one route's never stop another's.
        fixes.groupBy { it.userId }.forEach { (uid, u) ->
            try { RiskRules.mock(u)?.let(hits::add); RiskRules.teleport(u, t)?.let(hits::add); RiskRules.zeroJitter(u, t)?.let(hits::add) }
            catch (e: Exception) { log.error("risk rules skipped for one user on $date: ${e.javaClass.simpleName}") }
        }
        fixes.filter { it.purpose == "visit_open" && it.routeId != null }.groupBy { it.routeId }.values.forEach { r ->
            try { RiskRules.routeSinglePoint(r, t)?.let(hits::add) } catch (e: Exception) { log.error("route rule skipped on $date: ${e.javaClass.simpleName}") }
        }
        hits += isolated(h, "mismatch", date, emptyList()) { mismatches(h, date, t) }
        val phoneHits = isolated(h, "phone_signals", date, emptyList()) { PhoneSignalRules.fromFixes(phoneSignalFixes(h, date)) } +
            isolated(h, "integrity_events", date, emptyList()) { PhoneSignalRules.fromIntegrityEvents(integrityEvents(h, date)) }
        hits += PhoneSignalRules.disambiguate(phoneHits)
        var stored = 0
        for (hit in hits) stored += isolated(h, "store", date, 0) { renameEarlierPlainRow(h, hit, date); store(h, hit, date, weights[hit.code] ?: 0.0, version); 1 }
        return stored
    }

    /** Fixes that carry at least one code. Never throws on a stored value: a null or odd array element is skipped, a row that cannot be read is skipped. The phone is its device UUID (the id the other device rows use), else `user-<id>`. */
    private fun phoneSignalFixes(h: Handle, date: LocalDate): List<PhoneSignalFix> = h.createQuery(
        "SELECT COALESCE(d.device_uuid::text, 'user-' || g.user_id) AS phone, g.user_id, g.route_id, g.source_client_uuid, g.signals FROM app.geo_fix g LEFT JOIN app.device d ON d.id = g.device_id " +
            "WHERE g.business_date = :d AND g.voided_at IS NULL AND g.signals IS NOT NULL AND cardinality(g.signals) > 0 ORDER BY g.id",
    ).bind("d", date).map { rs, _ ->
        runCatching {
            PhoneSignalFix(rs.getString("phone"), rs.getLong("user_id"), rs.getObject("route_id") as Long?, rs.getString("source_client_uuid"), (rs.getArray("signals").array as Array<*>).mapNotNull { it?.toString() })
        }.getOrNull()
    }.list().filterNotNull()

    /**
     * The gate onsets whose OWN time falls on [date] (Dhaka business date), whichever report carried them: a report sent days later, or the same event re-sent across Dhaka
     * midnight after a kill, counts on the date the event happened and once (the identity is phone, user, time and flags). Reports of [date] up to [LATE_REPORT_DAYS] day later are read: the job evaluates today and yesterday only, so a read window longer than that would never be used (an onset reported two or more days late stays in its status report and makes no risk row).
     */
    private fun integrityEvents(h: Handle, date: LocalDate): List<IntegrityEventRow> {
        var skipped = 0
        val rows = h.createQuery(
            "SELECT COALESCE(d.device_uuid::text, 'user-' || r.user_id) AS phone, r.user_id, (r.report -> 'integrity_events')::text AS ev FROM app.device_status_report r LEFT JOIN app.device d ON d.id = r.device_id " +
                "WHERE r.business_date BETWEEN :d AND :d + :late AND r.user_id IS NOT NULL AND CASE WHEN jsonb_typeof(r.report -> 'integrity_events') = 'array' THEN jsonb_array_length(r.report -> 'integrity_events') ELSE 0 END > 0 ORDER BY r.id",
        ).bind("d", date).bind("late", LATE_REPORT_DAYS).map { rs, _ -> Triple(rs.getString("phone"), rs.getLong("user_id"), rs.getString("ev")) }.list()
            .flatMap { (phone, uid, ev) -> IntegrityEventReader.read(phone, uid, ev).also { skipped += it.skipped }.events }
            .filter { runCatching { java.time.Instant.parse(it.atUtc).atZone(DHAKA).toLocalDate() == date }.getOrDefault(false) }
        if (skipped > 0) log.warn("risk integrity events: $skipped malformed element(s) skipped on $date")
        return rows
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

    /**
     * A second rep appeared on the same phone later the same date: this rep's earlier row (evaluated alone, plain phone id) becomes `<phone>#<user>` instead of staying beside the
     * new row, so there is one row per rep and date and a reviewer's status carries over. No-op when the new key already exists or there was no earlier row.
     */
    private fun renameEarlierPlainRow(h: Handle, hit: RiskHit, date: LocalDate) {
        if (hit.subjectType != "device" || !hit.subjectId.contains('#')) return
        h.createUpdate(
            "UPDATE app.risk_signal SET subject_id = :new WHERE code = :c AND subject_type = 'device' AND subject_id = :plain AND business_date = :d AND user_id = :u " +
                "AND NOT EXISTS (SELECT 1 FROM app.risk_signal x WHERE x.code = :c AND x.subject_type = 'device' AND x.subject_id = :new AND x.business_date = :d)",
        ).bind("new", hit.subjectId).bind("c", hit.code).bind("plain", hit.subjectId.substringBefore('#')).bind("d", date).bind("u", hit.userId).execute()
    }

    private fun store(h: Handle, hit: RiskHit, date: LocalDate, score: Double, version: Long) {
        // A phone-signal row (DEVICE_INTEGRITY_FAIL, DEVICE_MOCK_APP_PRESENT, ...) can share its key with another writer's row of the same code and device (the Play Integrity
        // verdict row of DeviceService uses the same device UUID): the evidence is MERGED (the phone's members refresh, the other writer's stay) and the score is the higher of the two.
        val phone = (hit.evidence["source"] as? JsonPrimitive)?.content in setOf("phone_signals", "integrity_events")
        val evidenceOnConflict = if (phone) "risk_signal.evidence || EXCLUDED.evidence" else "EXCLUDED.evidence"
        val scoreOnConflict = if (phone) "GREATEST(risk_signal.score, EXCLUDED.score)" else "EXCLUDED.score"
        h.createUpdate(
            "INSERT INTO app.risk_signal (code, severity, business_date, subject_type, subject_id, user_id, route_id, score, evidence, config_version) " +
                "VALUES (:c, :sev, :d, :st, :sid, :u, :r, :score, CAST(:ev AS jsonb), :cv) " +
                "ON CONFLICT (code, subject_type, subject_id, business_date) DO UPDATE SET score = $scoreOnConflict, evidence = $evidenceOnConflict, config_version = EXCLUDED.config_version, updated_at = now()",
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

    /** Today and yesterday, each on its own: a date that fails (logged) never stops the other. */
    internal fun runDates(today: LocalDate, evaluate: (LocalDate) -> Unit) {
        for (d in listOf(today, today.minusDays(1))) {
            runCatching { evaluate(d) }.onFailure { it.exitIfFatal(log, "risk signals"); log.error("risk signal evaluation failed for $d", it) }
        }
    }

    fun start() {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "risk-signals").apply { isDaemon = true } }.scheduleWithFixedDelay({
            val today = runCatching { AdminSupport.today(clock) }.getOrNull() ?: return@scheduleWithFixedDelay
            runDates(today) { evaluator.evaluateDay(it) }
            // The two checkout rules, each in its own guard: a failure of one never stops the others (lead review of 2f5922ee, low 5).
            runCatching { evaluator.withdrawCheckoutWithoutSubmit(today).let { if (it > 0) log.info("checkout-without-submit signals withdrawn: {}", it) } }
                .onFailure { it.exitIfFatal(log, "risk signal withdrawal"); log.error("checkout-without-submit withdrawal failed", it) }
            runCatching { evaluator.raiseCheckoutWithoutSubmit(today, clock.now()).let { if (it > 0) log.info("checkout-without-submit signals raised late: {}", it) } }
                .onFailure { it.exitIfFatal(log, "risk signal late raise"); log.error("checkout-without-submit late raise failed", it) }
        }, 1, everyMin, TimeUnit.MINUTES)
    }
}
