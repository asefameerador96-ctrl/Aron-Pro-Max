package com.aktcl.aron.core.geo

/**
 * Optional low-power breadcrumbs (N-035, docs/24 s5.5, D24-46): off by default (`cfg.geo.breadcrumbs_enabled`); when on,
 * batched fused updates arrive at most every `cfg.geo.breadcrumb_interval_min` and this gate decides which become a
 * `geo_breadcrumb` record. Nothing is requested while the setting is off or the rep is not checked in.
 */
data class BreadcrumbSettings(
    val enabled: Boolean = false,
    /** `cfg.geo.breadcrumb_interval_min`, 5..120. */
    val intervalMin: Int = 30,
    /** A point is kept only after moving at least this far from the last kept point. */
    val minDisplacementM: Double = 50.0,
    /** Below this battery level (and not charging) no point is kept and updates are stopped. */
    val batteryFloorPct: Int = 20,
) {
    val intervalMs: Long get() = intervalMin.coerceIn(5, 120) * 60_000L
}

/**
 * The gate's persisted state for the current working day. Times are elapsed realtime; [wallMinusElapsedMs] detects a
 * reboot (elapsed realtime restarts), after which the window is re-anchored instead of stopping for the day.
 */
data class BreadcrumbState(
    val dayStartElapsedMs: Long? = null,
    val wallMinusElapsedMs: Long? = null,
    val lastKeptElapsedMs: Long? = null,
    val lastLat: Double? = null,
    val lastLng: Double? = null,
    val keptToday: Int = 0,
)

enum class BreadcrumbDecision { KEEP, OFF, NOT_CHECKED_IN, TOO_SOON, NOT_MOVED, BATTERY_LOW, BAD_FIX }

/**
 * Pure keep-or-drop rule. The first point is due one interval after check-in and each next one at least one interval
 * after the last kept point, so a day of H hours keeps at most H x 60 / interval points (8 h at 10 min: 48).
 */
object BreadcrumbGate {
    fun decide(s: BreadcrumbSettings, st: BreadcrumbState, loc: RawLocation, nowElapsedMs: Long, batteryPct: Int?, charging: Boolean): BreadcrumbDecision {
        if (!s.enabled) return BreadcrumbDecision.OFF
        val start = st.dayStartElapsedMs ?: return BreadcrumbDecision.NOT_CHECKED_IN
        if (batteryPct != null && batteryPct < s.batteryFloorPct && !charging) return BreadcrumbDecision.BATTERY_LOW
        if (!loc.lat.isFinite() || !loc.lng.isFinite() || loc.lat !in -90.0..90.0 || loc.lng !in -180.0..180.0) return BreadcrumbDecision.BAD_FIX
        // Judge by the fix's own time (batched deliveries arrive late), never by the delivery time.
        val at = (loc.elapsedRealtimeNanos / 1_000_000L).takeIf { it > 0 } ?: nowElapsedMs
        val since = st.lastKeptElapsedMs ?: start
        if (at - since < s.intervalMs) return BreadcrumbDecision.TOO_SOON
        if (st.lastLat != null && st.lastLng != null &&
            com.aktcl.aron.rules.Geo.haversineM(st.lastLat, st.lastLng, loc.lat, loc.lng) < s.minDisplacementM) return BreadcrumbDecision.NOT_MOVED
        return BreadcrumbDecision.KEEP
    }

    fun kept(st: BreadcrumbState, loc: RawLocation, nowElapsedMs: Long): BreadcrumbState {
        val at = (loc.elapsedRealtimeNanos / 1_000_000L).takeIf { it > 0 } ?: nowElapsedMs
        return st.copy(lastKeptElapsedMs = at, lastLat = loc.lat, lastLng = loc.lng, keptToday = st.keptToday + 1)
    }
}

/** Stores one kept breadcrumb as a `geo_breadcrumb` record in the outbox (the app wires it to core-database). */
fun interface BreadcrumbSink {
    fun store(fix: TakenFix)
}

/** Starts and stops the batched update request (Android: [AndroidBreadcrumbClient]). */
interface BreadcrumbClient {
    fun start(intervalMs: Long, minDisplacementM: Double)
    fun stop()
}

/**
 * Turns breadcrumbs on only when enabled AND checked in AND the battery allows; otherwise the request is removed, so
 * with the setting off no extra fix is ever requested. Batched points flow through [onLocations].
 */
class BreadcrumbController(
    private val client: BreadcrumbClient,
    private val store: BreadcrumbStateStore,
    private val sink: BreadcrumbSink,
    private val deviceState: DeviceStateReader,
    private val clock: com.aktcl.aron.core.common.WallClock,
    private val settings: () -> BreadcrumbSettings,
) {
    /** The interval and distance the running request was started with (null: not running). */
    private var runningWith: Pair<Long, Double>? = null

    @Synchronized
    fun onCheckIn(batteryPct: Int? = null, charging: Boolean = false) {
        val now = clock.elapsedRealtimeMs()
        store.save(BreadcrumbState(dayStartElapsedMs = now, wallMinusElapsedMs = clock.nowMs() - now))
        reconcile(batteryPct, charging)
    }

    @Synchronized
    fun onCheckOut() {
        store.save(BreadcrumbState())
        reconcile(batteryPct = null, charging = false)
    }

    /** Call on policy change, app start, boot and battery-low/okay broadcasts. Restarts the request when its settings changed. */
    @Synchronized
    fun reconcile(batteryPct: Int?, charging: Boolean) {
        val s = settings()
        val st = reanchored(store.load())
        val want = s.enabled && st.dayStartElapsedMs != null && !(batteryPct != null && batteryPct < s.batteryFloorPct && !charging)
        val target = s.intervalMs to s.minDisplacementM
        if (want && runningWith != target) {
            if (runningWith != null) runCatching { client.stop() }
            runningWith = if (runCatching { client.start(target.first, target.second) }.isSuccess) target else null
        }
        if (!want) { runCatching { client.stop() }; runningWith = null }
    }

    /** After a reboot elapsed realtime restarts: re-anchor the window at "now" (spacing from here, last place kept). */
    private fun reanchored(st: BreadcrumbState): BreadcrumbState {
        val start = st.dayStartElapsedMs ?: return st
        val now = clock.elapsedRealtimeMs()
        val offset = clock.nowMs() - now
        val rebooted = now < start || (st.lastKeptElapsedMs != null && now < st.lastKeptElapsedMs) ||
            (st.wallMinusElapsedMs != null && kotlin.math.abs(offset - st.wallMinusElapsedMs) > REBOOT_OFFSET_MS)
        if (!rebooted) return st
        return st.copy(dayStartElapsedMs = now, wallMinusElapsedMs = offset, lastKeptElapsedMs = null).also { store.save(it) }
    }

    /** A batch of locations from the platform; returns how many were kept. Never throws. */
    @Synchronized
    fun onLocations(locations: List<RawLocation>, batteryPct: Int?, charging: Boolean): Int = runCatching {
        val s = settings()
        var st = reanchored(store.load())
        var kept = 0
        for (loc in locations.sortedBy { it.elapsedRealtimeNanos }) {
            val now = clock.elapsedRealtimeMs()
            when (BreadcrumbGate.decide(s, st, loc, now, batteryPct, charging)) {
                BreadcrumbDecision.KEEP -> {
                    sink.store(toFix(loc, now))
                    st = BreadcrumbGate.kept(st, loc, now)
                    store.save(st) // after each point: a failing write later in the batch cannot undo the spacing
                    kept++
                }
                // A delivery while off or checked out means a request outlived its reason: remove it now.
                BreadcrumbDecision.OFF, BreadcrumbDecision.NOT_CHECKED_IN -> { reconcile(batteryPct, charging); return@runCatching kept }
                else -> Unit
            }
        }
        if (batteryPct != null && batteryPct < s.batteryFloorPct && !charging) reconcile(batteryPct, charging)
        kept
    }.getOrDefault(0)

    private fun toFix(loc: RawLocation, nowElapsedMs: Long): TakenFix {
        val fixMs = loc.elapsedRealtimeNanos.takeIf { it > 0 }?.div(1_000_000L)
        return TakenFix(
            purpose = FixPurpose.BREADCRUMB, fixStatus = FixStatus.OK, lat = loc.lat, lng = loc.lng,
            accuracyM = loc.accuracyM?.takeIf { it.isFinite() && it >= 0 }?.coerceAtMost(FixManager.MAX_ACCURACY_M),
            altitudeM = loc.altitudeM?.takeIf { it.isFinite() }, verticalAccuracyM = loc.verticalAccuracyM?.takeIf { it.isFinite() && it >= 0 },
            speedMps = loc.speedMps?.takeIf { it.isFinite() && it >= 0 }, bearingDeg = loc.bearingDeg?.takeIf { it.isFinite() && it in 0.0..360.0 },
            provider = FixProvider.of(loc.provider),
            fixTime = loc.timeMs.takeIf { it > 0 }?.let { FixManager.isoMillis(it) },
            fixElapsedRealtimeMs = fixMs, fixAgeMs = fixMs?.let { (nowElapsedMs - it).coerceAtLeast(0) },
            timeToFixMs = null, requestPriority = FixPriority.BALANCED, isMock = loc.isMock, reused = false, refreshCount = 0,
            gnssJson = null, device = deviceState.read(),
        )
    }
}

private const val REBOOT_OFFSET_MS = 5 * 60_000L

/** Persists [BreadcrumbState]. */
interface BreadcrumbStateStore {
    fun load(): BreadcrumbState
    fun save(s: BreadcrumbState)
}

class MemoryBreadcrumbStateStore : BreadcrumbStateStore {
    private var s = BreadcrumbState()
    @Synchronized override fun load() = s
    @Synchronized override fun save(s: BreadcrumbState) { this.s = s }
}
