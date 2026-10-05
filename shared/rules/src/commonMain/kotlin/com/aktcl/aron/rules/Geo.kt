package com.aktcl.aron.rules

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Distance maths of docs/24 s7.6: WGS84 mean radius, metres as Double, pure functions only. */
object Geo {
    /** WGS84 mean earth radius in metres (docs/24 s7.6). */
    const val EARTH_RADIUS_M: Double = 6_371_008.8

    private const val DEG = PI / 180.0

    /** Haversine great-circle distance in metres between two WGS84 points given in degrees. */
    fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = lat1 * DEG
        val p2 = lat2 * DEG
        val dp = (lat2 - lat1) * DEG
        val dl = (lng2 - lng1) * DEG
        val s1 = sin(dp / 2)
        val s2 = sin(dl / 2)
        val a = (s1 * s1 + cos(p1) * cos(p2) * s2 * s2).coerceIn(0.0, 1.0)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }

    /** True when the coordinates are finite and inside lat -90..90, lng -180..180. */
    fun isValidCoordinate(lat: Double, lng: Double): Boolean =
        lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0

    /** Implied speed in km/h for [distanceM] covered in [elapsedMs]; +Infinity when elapsed <= 0 and distance > 0, 0 for no distance. */
    fun impliedSpeedKmh(distanceM: Double, elapsedMs: Long): Double = when {
        distanceM <= 0.0 -> 0.0
        elapsedMs <= 0L -> Double.POSITIVE_INFINITY
        else -> distanceM / (elapsedMs / 1000.0) * 3.6
    }

    /** GEO_TELEPORT test (docs/24 s11.4): at least [minDistanceM] apart and implied speed above [maxSpeedKmh]. */
    fun isTeleport(a: GeoPoint, b: GeoPoint, minDistanceM: Int = 500, maxSpeedKmh: Int = 60): Boolean {
        val d = haversineM(a.lat, a.lng, b.lat, b.lng)
        return d >= minDistanceM && impliedSpeedKmh(d, abs(b.timeMs - a.timeMs)) > maxSpeedKmh
    }

    /** Coordinate-wise median point (lat and lng medians independently; mean of the two middle values for an even count). */
    fun medianPoint(points: List<GeoPoint>): GeoPoint {
        require(points.isNotEmpty()) { "points must not be empty" }
        fun median(v: List<Double>): Double {
            val s = v.sorted()
            val n = s.size
            return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
        }
        return GeoPoint(median(points.map { it.lat }), median(points.map { it.lng }), 0L)
    }

    /**
     * GEO_ROUTE_SINGLE_POINT test (docs/24 s11.4): with at least [minVisits] fixes, true when at least [pct] percent
     * (integer arithmetic, `count x 100 >= pct x n`) lie within [radiusM] of their median point.
     */
    fun isRouteSinglePoint(points: List<GeoPoint>, pct: Int = 80, radiusM: Int = 30, minVisits: Int = 8): Boolean {
        if (points.size < minVisits || points.isEmpty()) return false
        val m = medianPoint(points)
        val within = points.count { haversineM(it.lat, it.lng, m.lat, m.lng) <= radiusM }
        return within.toLong() * 100 >= pct.toLong() * points.size
    }

    /** Clamps a resolved radius into the registry bounds `cfg.geo.radius_min_m..radius_max_m`. */
    fun clampRadius(radiusM: Int, minM: Int, maxM: Int): Int {
        require(minM <= maxM) { "min must be <= max" }
        return max(minM, min(maxM, radiusM))
    }

    /** Cross/dot great-circle distance on the same sphere, computed independently of [haversineM] (used by tests as a second formula). */
    internal fun vectorDistanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        fun v(lat: Double, lng: Double): DoubleArray {
            val p = lat * DEG
            val l = lng * DEG
            return doubleArrayOf(cos(p) * cos(l), cos(p) * sin(l), sin(p))
        }
        val a = v(lat1, lng1)
        val b = v(lat2, lng2)
        val cx = a[1] * b[2] - a[2] * b[1]
        val cy = a[2] * b[0] - a[0] * b[2]
        val cz = a[0] * b[1] - a[1] * b[0]
        val dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        return EARTH_RADIUS_M * atan2(sqrt(cx * cx + cy * cy + cz * cz), dot)
    }
}

/** A WGS84 point with the trusted capture instant in epoch milliseconds (0 when time is irrelevant). */
data class GeoPoint(val lat: Double, val lng: Double, val timeMs: Long = 0L)

/** Config scope levels in specificity order (docs/24 s9.2); [HOUSE] sits between geo_class and territory and is unused in Phase 1. */
enum class ConfigScope(val precedence: Int) {
    DEVICE(120), USER(110), OUTLET(100), ROUTE(90), ZONE(80), GEO_CLASS(70), HOUSE(60),
    TERRITORY(50), DIVISION(40), WING(30), ROLE(10), GLOBAL(0),
}

/** A value found on one node of the subject's scope chain. */
data class ScopedValue<T>(val scope: ConfigScope, val value: T)

/** Radius resolution of docs/24 s9.2 over plain data; the server runs it and ships the result per outlet (D24-15). */
object RadiusResolver {
    /** Picks the value of the most specific scope present, or [default] when none; two values on one scope are a caller bug. */
    fun <T> resolve(candidates: List<ScopedValue<T>>, default: T): T {
        require(candidates.map { it.scope }.toSet().size == candidates.size) { "duplicate scope level in chain" }
        return candidates.maxByOrNull { it.scope.precedence }?.value ?: default
    }
}

/** What to do when the fix is mocked (`cfg.geo.mock_policy`). */
enum class MockPolicy(val wire: String) { SILENT_FLAG("silent_flag"), WARN_REP("warn_rep"), BLOCK_SALE("block_sale") }

/** What to do when the outlet has no usable location (`cfg.geo.no_location_policy`). */
enum class NoLocationPolicy(val wire: String) { FORCE_SALE_REQUIRED("force_sale_required"), ALLOW_UNVALIDATED("allow_unvalidated"), BLOCK("block") }

/** On-device verdict of docs/24 s11.2. */
enum class GeoVerdict(val wire: String) {
    NO_FIX("no_fix"), MOCKED("mocked"), NO_OUTLET_LOCATION("no_outlet_location"),
    ACCURACY_TOO_LOW("accuracy_too_low"), IN_RANGE("in_range"), OUT_OF_RANGE("out_of_range"),
}

/** What the visit screen does next: sell, offer a refresh, force-sale path, or block. */
enum class GeoAction(val wire: String) { SALE_ALLOWED("sale_allowed"), REFRESH_OFFERED("refresh_offered"), FORCE_SALE("force_sale"), BLOCKED("blocked") }

/** The fix fields the verdict needs; [fixOk] is `fix_status == ok`; [accuracyM] may be null when the provider gave none. */
data class FixInput(val fixOk: Boolean, val lat: Double, val lng: Double, val accuracyM: Double?, val isMock: Boolean)

/** Outlet location as downloaded: [usable] false for `location_basis` none or placeholder. */
data class OutletGeo(val usable: Boolean, val lat: Double, val lng: Double)

/** The configured policy inputs of the verdict; [refreshCount] is how many refreshes the rep already did. */
data class GeoPolicy(
    val mockPolicy: MockPolicy,
    val noLocationPolicy: NoLocationPolicy,
    val accuracyTolerant: Boolean,
    val refreshCount: Int,
    val refreshMax: Int,
)

/** Verdict, next action, the haversine [distanceM] (null when not computed) and whether to show the mock warning. */
data class GeoVerdictResult(val verdict: GeoVerdict, val action: GeoAction, val distanceM: Double?, val warnRep: Boolean = false)

/** The one verdict function used by phone and server (docs/24 s11.2). */
object GeoVerdicts {
    /** Applies the six ordered checks of s11.2; a mocked fix never yields [GeoAction.SALE_ALLOWED]. */
    fun verdict(fix: FixInput, outlet: OutletGeo, radiusM: Int, maxAccuracyM: Int, policy: GeoPolicy): GeoVerdictResult {
        val refreshLeft = policy.refreshCount < policy.refreshMax
        val refreshOrForce = if (refreshLeft) GeoAction.REFRESH_OFFERED else GeoAction.FORCE_SALE
        if (!fix.fixOk || !Geo.isValidCoordinate(fix.lat, fix.lng)) {
            return GeoVerdictResult(GeoVerdict.NO_FIX, refreshOrForce, null)
        }
        if (fix.isMock) {
            return when (policy.mockPolicy) {
                MockPolicy.BLOCK_SALE -> GeoVerdictResult(GeoVerdict.MOCKED, GeoAction.BLOCKED, null)
                MockPolicy.WARN_REP -> GeoVerdictResult(GeoVerdict.MOCKED, GeoAction.FORCE_SALE, null, warnRep = true)
                MockPolicy.SILENT_FLAG -> GeoVerdictResult(GeoVerdict.MOCKED, GeoAction.FORCE_SALE, null)
            }
        }
        if (!outlet.usable || !Geo.isValidCoordinate(outlet.lat, outlet.lng)) {
            val action = when (policy.noLocationPolicy) {
                NoLocationPolicy.FORCE_SALE_REQUIRED -> GeoAction.FORCE_SALE
                NoLocationPolicy.ALLOW_UNVALIDATED -> GeoAction.SALE_ALLOWED
                NoLocationPolicy.BLOCK -> GeoAction.BLOCKED
            }
            return GeoVerdictResult(GeoVerdict.NO_OUTLET_LOCATION, action, null)
        }
        val distance = Geo.haversineM(fix.lat, fix.lng, outlet.lat, outlet.lng)
        val accuracy = fix.accuracyM
        if (accuracy == null || !accuracy.isFinite() || accuracy < 0 || accuracy > maxAccuracyM) {
            return GeoVerdictResult(GeoVerdict.ACCURACY_TOO_LOW, refreshOrForce, distance)
        }
        val effective = if (policy.accuracyTolerant) distance - accuracy else distance
        return if (effective <= radiusM) GeoVerdictResult(GeoVerdict.IN_RANGE, GeoAction.SALE_ALLOWED, distance)
        else GeoVerdictResult(GeoVerdict.OUT_OF_RANGE, refreshOrForce, distance)
    }
}
