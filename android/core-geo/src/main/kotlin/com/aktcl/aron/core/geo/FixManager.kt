package com.aktcl.aron.core.geo

import com.aktcl.aron.core.common.WallClock
import com.aktcl.aron.rules.BusinessDate
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Takes the one on-demand fix of a geo event (docs/24 s5.5, s11.1; D-74): one balanced-power fused request with a
 * timeout, never a stream and never a cached last-known location, with the mock flag, provider, accuracy and elapsed
 * realtime stamped on every result. Works fully offline: nothing here touches the network.
 *
 * Reuse (`reused = true`): within one purpose cycle (a caller-chosen key such as the outlet list of the day), the last good
 * fix is reused when it is at most `cfg.geo.fix_reuse_max_age_s` old by elapsed realtime (wall-clock changes cannot
 * stretch it, a reboot voids it) and its accuracy is at most [REUSE_MAX_ACCURACY_M]. A manual refresh never reuses.
 *
 * Requests are serialised: a double tap waits for the running request and then reuses its fix when the cycle allows,
 * instead of starting a second provider request.
 */
class FixManager(
    private val source: LocationSource,
    private val access: LocationAccess,
    private val deviceState: DeviceStateReader,
    private val clock: WallClock,
    private val ledger: FixLedger = MemoryFixLedger(),
    private val gnss: FixWindowObserver = FixWindowObserver.None,
    private val settings: () -> FixSettings = { FixSettings() },
) {
    private class Candidate(
        val location: RawLocation,
        val timeToFixMs: Long?,
        val gnssJson: String?,
        /** The priority this fix was really requested with (a reuse reports it, not today's setting). */
        val priority: FixPriority,
        val fromWarmUp: Boolean = false,
        var used: Boolean = false,
    )

    private val mutex = Mutex()
    private val candidates = HashMap<String, Candidate>()
    /** Warm-up provider requests of [warmUpDay] and how many of them a later take reused. */
    private var warmUpsIssued = 0
    private var warmUpsUsed = 0
    private var warmUpDay: String? = null

    /**
     * The fix for [purpose]. [cycle] enables reuse inside one purpose cycle (null: always a fresh request). [refreshCount]
     * is the number of manual refreshes the rep already made for this event; a refresh (> 0) is always a fresh request.
     */
    suspend fun take(purpose: FixPurpose, cycle: String? = null, refreshCount: Int = 0): TakenFix = mutex.withLock {
        val s = settings()
        // Permission and the location switch are checked on every take, so a reuse can never hide a revoked permission
        // or location turned off (docs/24 s11.2 blocks the visit screen then).
        when (access.state(s.requirePrecise)) {
            LocationAccessState.PERMISSION_DENIED -> { cycle?.let { drop(it) }; return@withLock failed(purpose, FixStatus.PERMISSION_DENIED, s, refreshCount) }
            LocationAccessState.LOCATION_OFF -> { cycle?.let { drop(it) }; return@withLock failed(purpose, FixStatus.LOCATION_OFF, s, refreshCount) }
            LocationAccessState.OK -> Unit
        }
        if (cycle != null && refreshCount == 0) reusable(cycle, s)?.let {
            if (it.fromWarmUp && !it.used) { warmUpBook(); warmUpsUsed++ }
            it.used = true
            return@withLock reuse(purpose, it, refreshCount)
        }
        val fix = request(purpose, s, refreshCount)
        if (cycle != null) {
            drop(cycle)
            fix.second?.let { candidates[cycle] = it }
        }
        fix.first
    }

    /**
     * Starts acquisition ahead of the event (on entering the outlet list, D-74) so the next [take] of the same [cycle]
     * can reuse the fix. Counts as one provider request; does nothing when a reusable fix already exists.
     */
    suspend fun warmUp(cycle: String) {
        mutex.withLock {
            val s = settings()
            if (reusable(cycle, s) != null) return@withLock
            drop(cycle)
            // Warm-ups that expire unused cost battery for nothing: after MAX_WASTED_WARMUPS_PER_DAY of them the day's
            // budget (docs/24 s5.7: at most 80 fixes) is protected by not warming up again until the next business date.
            if (wastedToday() >= MAX_WASTED_WARMUPS_PER_DAY) return@withLock
            if (access.state(s.requirePrecise) != LocationAccessState.OK) return@withLock
            // Counted as wasted from the moment it is issued (a timeout or a mock answer costs as much as a good fix);
            // a later reuse takes it off the count.
            warmUpBook(); warmUpsIssued++
            val fix = request(FixPurpose.REFRESH, s, 0)
            fix.second?.let { candidates[cycle] = Candidate(it.location, it.timeToFixMs, it.gnssJson, it.priority, fromWarmUp = true) }
        }
    }

    /** Drops the reusable fix of [cycle] (the rep left the outlet list, the day closed). */
    suspend fun forget(cycle: String) = mutex.withLock { drop(cycle) }

    /** Number of warm-up fixes of the current business date that expired without being reused. */
    suspend fun wastedWarmUps(): Int = mutex.withLock { wastedToday() }

    private fun drop(cycle: String) {
        candidates.remove(cycle)
        // Expired candidates of other cycles can never be reused: forget them so the map does not grow all day.
        val now = clock.elapsedRealtimeMs()
        candidates.entries.removeAll { (_, c) -> now - c.location.elapsedRealtimeNanos / NANOS_PER_MS !in 0..MAX_CANDIDATE_AGE_MS }
    }

    private fun warmUpBook() {
        val today = BusinessDate.of(clock.nowMs()).toString()
        if (warmUpDay != today) { warmUpDay = today; warmUpsIssued = 0; warmUpsUsed = 0 }
    }

    private fun wastedToday(): Int {
        warmUpBook()
        return warmUpsIssued - warmUpsUsed
    }

    private fun reusable(cycle: String, s: FixSettings): Candidate? {
        val c = candidates[cycle] ?: return null
        val maxAge = s.reuseMaxAgeMs
        if (maxAge <= 0) return null
        val age = clock.elapsedRealtimeMs() - c.location.elapsedRealtimeNanos / NANOS_PER_MS
        val accuracy = c.location.accuracyM ?: return null
        return c.takeIf { c.location.elapsedRealtimeNanos > 0 && age in 0..maxAge && accuracy <= REUSE_MAX_ACCURACY_M }
    }

    private fun reuse(purpose: FixPurpose, c: Candidate, refreshCount: Int): TakenFix =
        build(purpose, FixStatus.OK, c.location, c.priority, c.timeToFixMs, c.gnssJson, reused = true, refreshCount)

    private suspend fun request(purpose: FixPurpose, s: FixSettings, refreshCount: Int): Pair<TakenFix, Candidate?> {
        when (access.state(s.requirePrecise)) {
            LocationAccessState.PERMISSION_DENIED -> return failed(purpose, FixStatus.PERMISSION_DENIED, s, refreshCount) to null
            LocationAccessState.LOCATION_OFF -> return failed(purpose, FixStatus.LOCATION_OFF, s, refreshCount) to null
            LocationAccessState.OK -> Unit
        }
        val window = gnss.open()
        val started = clock.elapsedRealtimeMs()
        val result: SourceResult
        var gnssJson: String? = null
        try {
            result = try {
                withTimeoutOrNull(s.timeoutMs + TIMEOUT_GRACE_MS) { source.currentLocation(s.priority, s.timeoutMs) }
                    ?: SourceResult.TimedOut
            } catch (e: CancellationException) {
                throw e
            } catch (_: SecurityException) {
                return failed(purpose, FixStatus.PERMISSION_DENIED, s, refreshCount) to null
            } catch (_: Exception) {
                SourceResult.Unavailable
            }
        } finally {
            gnssJson = runCatching { window.close() }.getOrNull()
            val busy = clock.elapsedRealtimeMs() - started
            ledger.recordProviderRequest(BusinessDate.of(clock.nowMs()).toString(), busy)
        }
        val timeToFix = (clock.elapsedRealtimeMs() - started).coerceIn(0, MAX_TIME_TO_FIX_MS)
        return when (result) {
            is SourceResult.Located -> {
                val loc = result.location
                if (!validCoordinate(loc.lat, loc.lng)) {
                    // A provider that returns garbage coordinates is a provider failure; keep its mock flag as evidence.
                    build(purpose, FixStatus.PROVIDER_UNAVAILABLE, null, s.priority, timeToFix, gnssJson, false, refreshCount, loc.isMock) to null
                } else {
                    build(purpose, FixStatus.OK, loc, s.priority, timeToFix, gnssJson, false, refreshCount) to
                        Candidate(loc, timeToFix, gnssJson, s.priority)
                }
            }
            SourceResult.TimedOut -> build(purpose, FixStatus.TIMEOUT, null, s.priority, timeToFix, gnssJson, false, refreshCount) to null
            SourceResult.Unavailable ->
                build(purpose, FixStatus.PROVIDER_UNAVAILABLE, null, s.priority, timeToFix, gnssJson, false, refreshCount) to null
        }
    }

    private fun failed(purpose: FixPurpose, status: FixStatus, s: FixSettings, refreshCount: Int) =
        build(purpose, status, null, s.priority, null, null, false, refreshCount)

    private fun build(
        purpose: FixPurpose,
        status: FixStatus,
        loc: RawLocation?,
        priority: FixPriority,
        timeToFixMs: Long?,
        gnssJson: String?,
        reused: Boolean,
        refreshCount: Int,
        mockWithoutLocation: Boolean = false,
    ): TakenFix {
        val captureElapsed = clock.elapsedRealtimeMs()
        val fixElapsedMs = loc?.elapsedRealtimeNanos?.takeIf { it > 0 }?.div(NANOS_PER_MS)
        return TakenFix(
            purpose = purpose,
            fixStatus = status,
            lat = loc?.lat,
            lng = loc?.lng,
            accuracyM = loc?.accuracyM?.takeIf { it.isFinite() && it >= 0 }?.coerceAtMost(MAX_ACCURACY_M),
            altitudeM = loc?.altitudeM?.takeIf { it.isFinite() },
            verticalAccuracyM = loc?.verticalAccuracyM?.takeIf { it.isFinite() && it >= 0 },
            speedMps = loc?.speedMps?.takeIf { it.isFinite() && it >= 0 },
            bearingDeg = loc?.bearingDeg?.takeIf { it.isFinite() && it in 0.0..360.0 },
            provider = FixProvider.of(loc?.provider),
            fixTime = loc?.timeMs?.takeIf { it > 0 }?.let { ISO_MILLIS.format(Instant.ofEpochMilli(it)) },
            fixElapsedRealtimeMs = fixElapsedMs,
            fixAgeMs = fixElapsedMs?.let { (captureElapsed - it).coerceAtLeast(0) },
            timeToFixMs = timeToFixMs?.coerceIn(0, MAX_TIME_TO_FIX_MS),
            requestPriority = priority,
            isMock = loc?.isMock ?: mockWithoutLocation,
            reused = reused,
            refreshCount = refreshCount.coerceIn(0, MAX_REFRESH_COUNT),
            gnssJson = gnssJson,
            device = deviceState.read(),
        )
    }

    private fun validCoordinate(lat: Double, lng: Double) =
        lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0

    companion object {
        /** Unused warm-up fixes allowed per business date before warm-ups stop (keeps the day within 80 fixes). */
        const val MAX_WASTED_WARMUPS_PER_DAY: Int = 8
        /** Longest possible reuse age (`cfg.geo.fix_reuse_max_age_s` upper bound). */
        private const val MAX_CANDIDATE_AGE_MS = 300_000L
        /** D-74: a reused fix must be at least this accurate (metres). */
        const val REUSE_MAX_ACCURACY_M: Double = 30.0
        /** Extra time past the provider's own duration before the request is cut off. */
        const val TIMEOUT_GRACE_MS: Long = 2_000
        /** Contract bounds of `GeoFix`. */
        const val MAX_TIME_TO_FIX_MS: Long = 120_000
        const val MAX_ACCURACY_M: Double = 100_000.0
        const val MAX_REFRESH_COUNT: Int = 10
        private const val NANOS_PER_MS = 1_000_000L
        /** `fix_time` form: ISO-8601 UTC with milliseconds. */
        internal fun isoMillis(epochMs: Long): String = ISO_MILLIS.format(Instant.ofEpochMilli(epochMs))

        private val ISO_MILLIS: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC)
    }
}
