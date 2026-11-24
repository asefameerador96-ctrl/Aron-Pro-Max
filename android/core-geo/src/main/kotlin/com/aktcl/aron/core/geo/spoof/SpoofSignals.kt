package com.aktcl.aron.core.geo.spoof

import com.aktcl.aron.core.geo.RawLocation
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Anti-spoofing signals (D-OWNER-MDM-02). Each layer yields a code; NONE of them is proof. The decision rule:
 * a [hard] signal REFUSES the geo-validated action (and is recorded); a soft signal never blocks a sale, it is attached
 * to the fix and goes to the server so the TSO and the portal can see it.
 */
enum class SpoofCode(val wire: String, val hard: Boolean) {
    /** `Location.isMock` / `isFromMockProvider` is set on the fix. */
    MOCK_FLAG("mock_flag", true),
    /** A mock-location app is selected in Developer options (AppOps `android:mock_location` allowed) for a visible package. */
    MOCK_APP_SELECTED("mock_app_selected", true),
    /** The fix came from a provider name that is not fused, gps, network or passive: a test provider. */
    TEST_PROVIDER("test_provider", true),
    /** The "gps" provider reports no satellite requirement, the shape of a test provider. Soft until walked on the pilot phones. */
    TEST_PROVIDER_PROPS("test_provider_props", false),
    FAKE_GPS_APP_INSTALLED("fake_gps_app_installed", false),
    /** A "gps" fix with no satellite used in the fix. */
    GNSS_NO_SATS("gnss_no_sats", false),
    /** A very precise fix with no GnssStatus at all. */
    GNSS_NONE("gnss_none", false),
    FIX_STALE("fix_stale", false),
    /** The fix's own clock disagrees with the phone's wall clock and the monotonic clock. */
    FIX_CLOCK_MISMATCH("fix_clock_mismatch", false),
    /** Far from the previous fix after too short a time. */
    JUMP("jump", false),
    /** Implied speed against the previous fix beyond a plausible value. */
    SPEED("speed", false),
    /** Fused and network providers disagree beyond their accuracy. */
    PROVIDER_DISAGREE("provider_disagree", false),
    ROOT_HINT("root_hint", false),
    EMULATOR("emulator", false),
    HOOK_FRAMEWORK("hook_framework", false),
}

/** What the evaluator concluded for one fix: the codes in a stable order. */
data class SpoofResult(val codes: List<SpoofCode>) {
    val hard: Boolean get() = codes.any { it.hard }
    val wire: List<String> get() = codes.map { it.wire }

    companion object { val NONE = SpoofResult(emptyList()) }
}

/** The satellite facts of the fix window (from the `GnssSummary`), or null when there was no GnssStatus at all. */
data class GnssFacts(val satellitesUsed: Int)

/** The previous good fix of this phone, kept on the phone (one row). */
data class PreviousFix(val lat: Double, val lng: Double, val elapsedMs: Long)

/** A passive network-provider location (a passive read of the platform's newest network location: no new request, no radio cost). */
data class NetworkFix(val lat: Double, val lng: Double, val accuracyM: Double, val ageMs: Long)

/** Facts about the phone that change slowly (cached 10 minutes by the Android probes). */
data class SpoofFacts(
    val mockAppSelected: Boolean = false,
    val fakeGpsAppInstalled: Boolean = false,
    val rootHints: List<String> = emptyList(),
    val emulator: Boolean = false,
    val hookFramework: Boolean = false,
    /** True when the "gps" provider says it needs no satellite. */
    val gpsProviderLooksLikeTestProvider: Boolean = false,
)

data class SpoofThresholds(
    val preciseAccuracyM: Double = 20.0,
    val veryPreciseNoGnssM: Double = 10.0,
    val staleAgeMs: Long = 120_000,
    val clockMismatchMs: Long = 300_000,
    val jumpMinDistanceM: Double = 1_000.0,
    val jumpMaxTimeMs: Long = 20_000,
    val maxSpeedMps: Double = 70.0,
    val speedMinDistanceM: Double = 500.0,
    val speedMinTimeMs: Long = 5_000,
    val networkMaxAgeMs: Long = 120_000,
    val disagreeFloorM: Double = 1_500.0,
)

data class SpoofInput(
    val loc: RawLocation?,
    val nowElapsedMs: Long,
    val nowWallMs: Long,
    val reused: Boolean,
    val gnss: GnssFacts?,
    val previous: PreviousFix?,
    val network: NetworkFix?,
    val facts: SpoofFacts,
)

/** Pure, deterministic evaluator: every rule is a function of [SpoofInput] and [SpoofThresholds]. */
object SpoofEvaluator {
    private val STANDARD_PROVIDERS = setOf("fused", "gps", "network", "passive")

    fun evaluate(i: SpoofInput, th: SpoofThresholds = SpoofThresholds()): SpoofResult {
        val out = linkedSetOf<SpoofCode>()
        val loc = i.loc
        val f = i.facts
        // Device-level facts count with or without a location, so the codes are always reported. Refusal is decided later by the
        // verdict (shared/rules Geo.kt): a timeout or no-fix gives NO_FIX first (Refresh, or a photo-backed Force Sale that is never
        // a clean geo sale), and the hard refusal applies once a fix exists. A selected mock app is not a bypass: every fix it
        // produces carries the mock flag too.
        if (f.mockAppSelected) out += SpoofCode.MOCK_APP_SELECTED
        if (f.fakeGpsAppInstalled) out += SpoofCode.FAKE_GPS_APP_INSTALLED
        if (f.gpsProviderLooksLikeTestProvider) out += SpoofCode.TEST_PROVIDER_PROPS
        if (f.rootHints.isNotEmpty()) out += SpoofCode.ROOT_HINT
        if (f.emulator) out += SpoofCode.EMULATOR
        if (f.hookFramework) out += SpoofCode.HOOK_FRAMEWORK
        if (loc != null) {
            if (loc.isMock) out += SpoofCode.MOCK_FLAG
            val provider = loc.provider
            if (provider != null && provider !in STANDARD_PROVIDERS) out += SpoofCode.TEST_PROVIDER
            val accuracy = loc.accuracyM?.takeIf { it.isFinite() && it >= 0 }
            if (provider == "gps" && i.gnss != null && i.gnss.satellitesUsed == 0) out += SpoofCode.GNSS_NO_SATS
            if (i.gnss == null && accuracy != null && (provider == "gps" && accuracy < th.preciseAccuracyM || provider != "gps" && accuracy < th.veryPreciseNoGnssM)) {
                out += SpoofCode.GNSS_NONE
            }
            val ageMs = if (loc.elapsedRealtimeNanos > 0) i.nowElapsedMs - loc.elapsedRealtimeNanos / 1_000_000L else null
            if (ageMs != null && ageMs > th.staleAgeMs && !i.reused) out += SpoofCode.FIX_STALE
            if (ageMs != null && ageMs >= 0 && loc.timeMs > 0) {
                val expectedFixWall = i.nowWallMs - ageMs
                if (abs(loc.timeMs - expectedFixWall) > th.clockMismatchMs) out += SpoofCode.FIX_CLOCK_MISMATCH
            }
            i.previous?.let { p ->
                val dtMs = (loc.elapsedRealtimeNanos / 1_000_000L).takeIf { loc.elapsedRealtimeNanos > 0 }?.minus(p.elapsedMs)
                if (dtMs != null && dtMs >= 0) { // a negative step means a reboot between the two fixes: nothing to compare
                    val d = haversineM(p.lat, p.lng, loc.lat, loc.lng)
                    if (d > th.jumpMinDistanceM && dtMs < th.jumpMaxTimeMs) out += SpoofCode.JUMP
                    if (d > th.speedMinDistanceM && dtMs >= th.speedMinTimeMs && d / (dtMs / 1000.0) > th.maxSpeedMps) out += SpoofCode.SPEED
                }
            }
            i.network?.let { n ->
                if (n.ageMs in 0..th.networkMaxAgeMs && provider != "network") {
                    val bound = max(th.disagreeFloorM, n.accuracyM + (accuracy ?: 0.0))
                    if (haversineM(n.lat, n.lng, loc.lat, loc.lng) > bound) out += SpoofCode.PROVIDER_DISAGREE
                }
            }
        }
        return if (out.isEmpty()) SpoofResult.NONE else SpoofResult(out.sortedBy { it.ordinal })
    }

    internal fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lng2 - lng1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(sqrt(a))
    }
}
