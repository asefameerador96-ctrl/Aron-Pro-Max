package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
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
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * F-SYS-012: the server re-check of a visit's geo verdict on ingest (docs/24 s11.3). The same shared function as the
 * phone ([GeoVerdicts.verdict]) runs against the outlet location and the radius, accuracy and policy **in force at the
 * visit's capture** (D-431, s9.3 item 6): config changed within `cfg.sys.config_accept_window_h` before the upload is
 * ignored, an older change applies. The result is stored beside the phone's verdict (`server_verdict`,
 * `server_distance_m`, `server_radius_m`, `server_max_accuracy_m`, `server_checked_at`); the visit is never refused for it
 * (the sale happened; the record is evidence). A mismatch is a risk signal the worker derives (`GEO_DEVICE_SERVER_MISMATCH`).
 *
 * Runs once, on first store, inside the record's savepoint; the update is guarded by `server_checked_at IS NULL`. A
 * visit whose re-check failed there is picked up by [sweep] (worker, [GeoRecheckSweepJob]).
 */
class GeoRecheckHandler : RecordHandler {
    override val types: Set<String> = setOf("visit")

    override fun afterStored(h: Handle, rec: IngestRecord, serverId: Long?) {
        recheckOne(h, rec.clientUuid, rec.businessDate, rec.receivedAt)
    }

    /**
     * Re-checks one stored, not yet checked visit inside its own savepoint and stores the verdict. The re-check is
     * enrichment: it never refuses or parks the visit. On any failure the savepoint is rolled back, `server_verdict`
     * stays null and [sweep] tries again later. Returns true when a verdict was stored.
     */
    internal fun recheckOne(h: Handle, clientUuid: String, businessDate: LocalDate, receivedAt: Instant, checkedAt: Instant = receivedAt): Boolean {
        val v = h.createQuery(
            """
            SELECT v.captured_at, v.outlet_id, v.fix_status, v.fix_lat, v.fix_lng, v.fix_accuracy_m, v.fix_is_mock, u.role
            FROM app.visit v JOIN app.app_user u ON u.id = v.user_id
            WHERE v.client_uuid = CAST(:u AS uuid) AND v.business_date = :bd AND v.server_checked_at IS NULL
            """.trimIndent(),
        ).bind("u", clientUuid).bind("bd", businessDate).map { rs, _ ->
            VisitFix(
                rs.getObject("captured_at", OffsetDateTime::class.java).toInstant(), rs.getLong("outlet_id"), rs.getString("fix_status"),
                rs.getObject("fix_lat") as Double?, rs.getObject("fix_lng") as Double?, rs.getObject("fix_accuracy_m") as Double?,
                rs.getObject("fix_is_mock") as Boolean?, rs.getString("role"),
            )
        }.findOne().orElse(null) ?: return false
        h.savepoint(SAVEPOINT)
        return try {
            val r = recheck(h, v, receivedAt)
            val n = h.createUpdate(
                """
                UPDATE app.visit SET server_verdict = :sv, server_distance_m = :sd, server_radius_m = :sr, server_max_accuracy_m = :sa, server_checked_at = :at
                WHERE client_uuid = CAST(:u AS uuid) AND business_date = :bd AND server_checked_at IS NULL
                """.trimIndent(),
            ).bind("sv", r.verdict).bind("sd", r.distanceM).bind("sr", r.radiusM).bind("sa", r.maxAccuracyM)
                .bind("at", OffsetDateTime.ofInstant(checkedAt, ZoneOffset.UTC)).bind("u", clientUuid).bind("bd", businessDate).execute()
            h.release(SAVEPOINT)
            n > 0
        } catch (e: Exception) {
            h.rollbackToSavepoint(SAVEPOINT)
            log.warn("geo re-check skipped visit=$clientUuid: ${e.javaClass.simpleName}")
            false
        }
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
                   hl.lat AS h_lat, hl.lng AS h_lng, hl.basis AS h_basis,
                   EXISTS (SELECT 1 FROM app.outlet_location_history x WHERE x.outlet_id = o.id) AS has_history
            FROM app.outlet o
            JOIN app.zone z ON z.id = o.zone_id JOIN app.territory t ON t.id = z.territory_id JOIN app.division d ON d.id = t.division_id
            LEFT JOIN app.geo_class_def g ON g.geo_class = o.geo_class
            LEFT JOIN LATERAL (SELECT lat, lng, basis FROM app.outlet_location_history
                               WHERE outlet_id = o.id AND valid_from <= :at ORDER BY valid_from DESC, id DESC LIMIT 1) hl ON true
            WHERE o.id = :o
            """.trimIndent(),
        ).bind("o", v.outletId).bind("role", v.role).bind("at", OffsetDateTime.ofInstant(v.capturedAt, ZoneOffset.UTC)).mapToMap().one()
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
        fun word(key: String, c: ScopedConfig.Chain) = cfg.value(key, c)?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        val mock = word("cfg.geo.mock_policy", chain)?.let { w -> MockPolicy.entries.firstOrNull { it.wire == w } } ?: MockPolicy.BLOCK_SALE
        val noLoc = word("cfg.geo.no_location_policy", chain)?.let { w -> NoLocationPolicy.entries.firstOrNull { it.wire == w } } ?: NoLocationPolicy.FORCE_SALE_REQUIRED
        val tolerant = cfg.value("cfg.geo.accuracy_tolerant", global)?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } ?: false
        // The outlet location at capture: the latest history row valid by then (V0044: a row per basis change, null
        // coordinates for none/placeholder). An outlet with no history at all never had its pin changed through a
        // recorded path, so its current pin is the one in force. History that starts after the capture means the pin
        // in force then is unknown: no location (conservative; docs/requests/backend-core-outlet-pin-history.md).
        // Until backend-admin records a cleared pin as a history row, an outlet whose basis is now none or placeholder
        // counts as having no location (the phone saw the same basis; a pin cleared after the capture under-counts).
        val basisNow = o["location_basis"]
        val usableNow = basisNow == "master" || basisNow == "provisional"
        val (usableThen, lat, lng) = when {
            o["h_basis"] != null -> Triple(o["h_basis"] == "master" || o["h_basis"] == "provisional", dbl("h_lat"), dbl("h_lng"))
            o["has_history"] == true -> Triple(false, null, null)
            basisNow == "provisional" -> Triple(true, dbl("provisional_lat"), dbl("provisional_lng"))
            else -> Triple(true, dbl("lat"), dbl("lng"))
        }
        val usable = usableNow && usableThen && lat != null && lng != null
        val oLat = lat ?: 0.0
        val oLng = lng ?: 0.0
        val fix = FixInput(v.fixStatus == "ok" && v.lat != null && v.lng != null, v.lat ?: 0.0, v.lng ?: 0.0, v.accuracyM, v.isMock == true)
        // Refreshes are spent by the time a visit is stored; the action is the phone's and is not re-judged.
        val res = GeoVerdicts.verdict(fix, OutletGeo(usable, oLat, oLng), radius, maxAcc, GeoPolicy(mock, noLoc, tolerant, 0, 0))
        return Result(res.verdict.wire, res.distanceM, radius, maxAcc)
    }

    /**
     * Fills `server_verdict` for visits of the last [days] business dates up to [today] whose re-check failed on ingest.
     * Up to [limit] rows in random order (a row that fails every time cannot starve the rest). No row lock is taken
     * up front: the store is guarded by `server_checked_at IS NULL`, so two sweeps or a sweep and an ingest never store
     * twice, and an ingest or void of a visit never waits on a sweep for long ([GeoRecheckSweepJob] runs small batches,
     * one short transaction each). A stored verdict marks its route-day dirty (once per route-day, in order, at the
     * end): no domain event comes from a sweep, and the dashboards' geo-valid counts read `server_verdict`.
     * Returns the number of verdicts stored.
     */
    fun sweep(h: Handle, today: LocalDate, days: Int, limit: Int, now: Instant): Int {
        data class Row(val uuid: String, val date: LocalDate, val route: Long?, val receivedAt: Instant)
        val rows = h.createQuery(
            """
            SELECT client_uuid::text AS u, business_date, route_id, received_at FROM app.visit
            WHERE business_date BETWEEN :from AND :to AND server_checked_at IS NULL AND voided_at IS NULL
            ORDER BY random() LIMIT :n
            """.trimIndent(),
        ).bind("from", today.minusDays(days.toLong())).bind("to", today).bind("n", limit).map { rs, _ ->
            Row(rs.getString("u"), rs.getObject("business_date", LocalDate::class.java), rs.getObject("route_id") as Long?,
                rs.getObject("received_at", OffsetDateTime::class.java).toInstant())
        }.list()
        val dirty = sortedSetOf<Pair<Long, LocalDate>>(compareBy({ it.first }, { it.second }))
        var stored = 0
        for (r in rows) {
            // The verdict is judged as of the upload (D-431 window), exactly as on ingest; checked_at is the sweep's time.
            if (recheckOne(h, r.uuid, r.date, r.receivedAt, now)) {
                stored++
                r.route?.let { dirty += it to r.date }
            }
        }
        dirty.forEach { (route, date) -> h.execute("SELECT app.mark_dirty('route_day_agg', ?, ?, 'geo_recheck')", route, date) }
        return stored
    }

    companion object {
        private const val SAVEPOINT = "geo_recheck"
        private val log = LoggerFactory.getLogger("aron.geo")
        private const val WINDOW_KEY = "cfg.sys.config_accept_window_h"
        private val KEYS = setOf(
            "cfg.geo.radius_m", "cfg.geo.radius_min_m", "cfg.geo.radius_max_m", "cfg.geo.max_accuracy_m",
            "cfg.geo.mock_policy", "cfg.geo.no_location_policy", "cfg.geo.accuracy_tolerant",
        )
    }
}

/** Worker job: [GeoRecheckHandler.sweep] every [everyMin] minutes over the last [days] Dhaka business dates. */
class GeoRecheckSweepJob(
    private val db: Database,
    private val clock: AronClock = AronClock.SYSTEM,
    private val everyMin: Long = 10,
    private val days: Int = 7,
    private val limit: Int = 200,
) {
    private val log = LoggerFactory.getLogger("aron.geo")
    private val handler = GeoRecheckHandler()

    /** Up to [limit] rows, [BATCH] per short transaction. */
    fun tick(): Int {
        var stored = 0
        repeat(limit / BATCH) {
            val now = clock.now()
            stored += db.jdbi.inTransaction<Int, Exception> { h -> handler.sweep(h, LocalDate.ofInstant(now, ZoneId.of("Asia/Dhaka")), days, BATCH, now) }
        }
        return stored
    }

    fun start() {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "geo-recheck-sweep").apply { isDaemon = true } }.scheduleWithFixedDelay({
            runCatching { tick() }.onSuccess { if (it > 0) log.info("geo re-check sweep stored {} verdict(s)", it) }
                .onFailure { log.error("geo re-check sweep failed", it) }
        }, 2, everyMin, TimeUnit.MINUTES)
    }

    private companion object { const val BATCH = 20 }
}
