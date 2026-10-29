package com.aktcl.aron.feature.tso.periphery

import com.aktcl.aron.core.map.FixAge
import com.aktcl.aron.core.map.MapCircle
import com.aktcl.aron.core.map.MapPoint
import com.aktcl.aron.core.map.MapPointKind
import com.aktcl.aron.rules.Geo
import java.time.Instant
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The radii of the Retailer map when the bundle carries no `cfg.tso.periphery_radius_options_m` (docs/08, D-TSO-MAN-34):
 * the server refuses any radius outside that config, so the phone offers exactly its values.
 */
val RADIUS_OPTIONS_M = listOf(50, 100, 300)

/**
 * The My Periphery settings the phone reads from the bundle config (D-TSO-MAN-34): the Retailer radii
 * (`cfg.tso.periphery_radius_options_m`, 1 to 6 values of 10..5000 m) and the age after which an SR's fix is greyed
 * (`cfg.tso.team_location_max_age_min`, 15..720, default 120). A value outside the registry bounds falls back.
 */
data class PeripheryConfig(val radiiM: List<Int> = RADIUS_OPTIONS_M, val staleAfterMin: Int = FixAge.DEFAULT_STALE_AFTER_MIN) {
    companion object {
        fun of(radii: List<Int>?, staleAfterMin: Int?): PeripheryConfig = PeripheryConfig(
            radiiM = radii?.distinct()?.sorted()?.takeIf { r -> r.size in 1..6 && r.all { it in 10..5000 } } ?: RADIUS_OPTIONS_M,
            staleAfterMin = staleAfterMin?.takeIf { it in 15..720 } ?: FixAge.DEFAULT_STALE_AFTER_MIN,
        )
    }
}

data class NearbyOutlet(val id: Long, val code: String, val name: String, val lat: Double, val lng: Double, val distanceM: Double, val routeId: Long, val cluster: String?, val channel: String?, val phone: String?)
data class Nearby(val asOf: String, val radiusM: Int, val truncated: Boolean, val items: List<NearbyOutlet>)

/** [mockFixesToday]: the SR's fixes flagged as mock locations on the business date (contract 1.10.0, D-TSO-MAN-34); never the shown fix. */
data class TeamMember(val userId: Long, val name: String, val routeIds: List<Long>, val lat: Double?, val lng: Double?, val atMs: Long?, val source: String?, val mockFixesToday: Int = 0)

object PeripheryJson {
    private fun str(o: JSONObject, k: String): String { require(!o.isNull(k)) { "$k null" }; return o.getString(k) }
    private fun optStr(o: JSONObject, k: String) = if (o.isNull(k)) null else o.optString(k).ifBlank { null }

    fun nearby(body: String): Nearby? = runCatching {
        val o = JSONObject(body); val a = o.getJSONArray("items")
        Nearby(str(o, "as_of"), o.getInt("radius_m"), o.getBoolean("truncated"), (0 until a.length()).mapNotNull { i -> runCatching { a.getJSONObject(i).let { x ->
            NearbyOutlet(x.getLong("outlet_id"), str(x, "code"), str(x, "name"), x.getDouble("lat"), x.getDouble("lng"), x.getDouble("distance_m"), x.getLong("route_id"), optStr(x, "cluster_name"), optStr(x, "channel"), optStr(x, "contact_number"))
        } }.getOrNull() })
    }.getOrNull()

    fun nearbyToCache(n: Nearby): String = JSONObject().put("as_of", n.asOf).put("radius_m", n.radiusM).put("truncated", n.truncated).put("items", JSONArray(n.items.map {
        JSONObject().put("outlet_id", it.id).put("code", it.code).put("name", it.name).put("lat", it.lat).put("lng", it.lng).put("distance_m", it.distanceM).put("route_id", it.routeId)
            .put("cluster_name", it.cluster ?: JSONObject.NULL).put("channel", it.channel ?: JSONObject.NULL).put("contact_number", it.phone ?: JSONObject.NULL) })).toString()

    /** `GET /v1/team/locations` (contract `TeamLocationList`): `last_fix` null = the SR has not synced a fix yet; `mock_fixes_today` absent = 0. */
    fun team(body: String): List<TeamMember>? = runCatching {
        val o = JSONObject(body); val a = o.getJSONArray("items")
        (0 until a.length()).mapNotNull { i -> runCatching { a.getJSONObject(i).let { x ->
            val rids = x.getJSONArray("route_ids").let { r -> (0 until r.length()).map { r.getLong(it) } }
            val f = if (x.isNull("last_fix")) null else x.getJSONObject("last_fix")
            TeamMember(
                x.getLong("user_id"), str(x, "full_name"), rids, f?.getDouble("lat"), f?.getDouble("lng"),
                f?.let { runCatching { Instant.parse(it.getString("at")).toEpochMilli() }.getOrNull() }, f?.let { optStr(it, "source") },
                mockFixesToday = x.optInt("mock_fixes_today", 0).coerceAtLeast(0),
            )
        } }.getOrNull() }
    }.getOrNull()

    fun teamToCache(m: List<TeamMember>): String = JSONObject().put("items", JSONArray(m.map {
        JSONObject().put("user_id", it.userId).put("full_name", it.name).put("route_ids", JSONArray(it.routeIds)).put("mock_fixes_today", it.mockFixesToday).put("last_fix",
            if (it.lat == null || it.lng == null || it.atMs == null) JSONObject.NULL else JSONObject().put("lat", it.lat).put("lng", it.lng).put("at", Instant.ofEpochMilli(it.atMs).toString()).put("source", it.source ?: "visit")) })).toString()
}

/**
 * One answer with the time it was read ("as of hh:mm") and where it came from: [fromCache] the phone's last copy of the
 * same view, [computedOnPhone] worked out offline from the outlets of the bundle ([NearbyRule]).
 */
data class Stamped<T>(val value: T, val asOf: String, val fromCache: Boolean, val computedOnPhone: Boolean = false)

/**
 * One outlet of the bundle as the offline Retailer search needs it: the same columns the server's `getNearbyOutlets`
 * filters on (status, confirmed coordinates, route, zone of the route).
 */
data class BundleOutlet(
    val id: Long, val code: String, val name: String, val lat: Double?, val lng: Double?, val routeId: Long?, val zoneId: Long?,
    val active: Boolean, val locationConfirmed: Boolean, val cluster: String?, val channel: String?, val phone: String?,
)

/**
 * The server's nearby rule (`NearbyApi.nearby`), run on the phone over the bundle when there is no connection
 * (D-TSO-MAN-34): active outlets with confirmed coordinates and a route, in the zone when one is chosen, at most [radiusM]
 * by the shared haversine ([Geo.haversineM], the server's), the distance rounded to 0.1 m half up as the server sends it,
 * nearest first and ties by outlet id, at most [maxMarkers] with `truncated` when more exist. The reach is the bundle's
 * (the server built it from the token); no id is sent anywhere.
 */
object NearbyRule {
    fun compute(outlets: List<BundleOutlet>, lat: Double, lng: Double, radiusM: Int, zoneId: Long?, asOf: String, maxMarkers: Int = MAX_SHOWN): Nearby {
        val rows = outlets.asSequence()
            .filter { it.active && it.locationConfirmed && it.routeId != null && it.lat != null && it.lng != null }
            .filter { zoneId == null || it.zoneId == zoneId }
            .mapNotNull { o ->
                val d = Geo.haversineM(lat, lng, o.lat!!, o.lng!!)
                if (d > radiusM) null else NearbyOutlet(o.id, o.code, o.name, o.lat, o.lng, roundTenth(d), o.routeId!!, o.cluster, o.channel, o.phone)
            }
            .sortedWith(compareBy<NearbyOutlet> { it.distanceM }.thenBy { it.id })
            .toList()
        return Nearby(asOf, radiusM, rows.size > maxMarkers, rows.take(maxMarkers))
    }

    /** `Math.round(d * 10) / 10.0`, exactly the server's rounding of `distance_m`. */
    fun roundTenth(d: Double): Double = Math.round(d * 10) / 10.0
}

interface PeripherySource {
    suspend fun zones(): List<Long>?
    /** The answer for a position; a cached copy is only ever returned for the SAME rounded position (see [positionKey]). */
    suspend fun nearby(lat: Double, lng: Double, radiusM: Int, zoneId: Long?): Stamped<Nearby>?
    suspend fun team(zoneId: Long?, date: String): Stamped<List<TeamMember>>?
    /** Route code labels for the legend (id to code), from the cached routes. */
    suspend fun routeCodes(): Map<Long, String>
    /** The radii and the stale age from the bundle config ([PeripheryConfig]); the defaults when the bundle has none. */
    suspend fun config(): PeripheryConfig = PeripheryConfig()
    /** The outlets of the bundle in [zoneId] (all zones of the bundle when null); null when the bundle holds none. */
    suspend fun bundleOutlets(zoneId: Long?): List<BundleOutlet>? = null
}

sealed interface FixResult {
    data class Ok(val lat: Double, val lng: Double, val accuracyM: Double?, val isMock: Boolean) : FixResult
    data object PermissionNeeded : FixResult
    data object LocationOff : FixResult
    data object Failed : FixResult
}

/** Bangladesh bounds of the contract's lat and lng parameters; a fix outside them (0,0 or a fake) is never sent. */
fun inBangladesh(lat: Double, lng: Double) = lat in 20.0..27.0 && lng in 88.0..93.0

/** A position rounded to about 25 m, for cache keys: a result is never shown for another place. */
fun positionKey(lat: Double, lng: Double): String = "%d_%d".format(Math.round(lat * 4000), Math.round(lng * 4000))

/** One balanced-power location on demand (no stream, no cached last-known). */
fun interface PeripheryFix { suspend fun current(): FixResult }

/** Markers are capped and the list is grouped: at most [MAX_SHOWN] pins, the rest only in the count (server also caps at 300). */
const val MAX_SHOWN = 300

/** The distance a pin and a row show: whole metres, rounded (12.5 m reads "13 m"), never truncated. */
fun shownMetres(distanceM: Double): Int = distanceM.roundToInt()

/**
 * The Retailer pins: the outlets (name, code, cluster in the bubble, the rounded distance as the note) nearest first, then
 * the TSO's own fix as a green pin when [centre] is given.
 */
fun nearbyPoints(n: Nearby, distance: (Int) -> String = { "$it m" }, centre: Pair<Double, Double>? = null, selfLabel: String = ""): List<MapPoint> =
    n.items.take(MAX_SHOWN).map {
        MapPoint(it.id.toString(), it.name + "  ·  " + it.code, it.lat, it.lng, kind = MapPointKind.OUTLET, note = listOfNotNull(it.cluster, distance(shownMetres(it.distanceM))).joinToString("  ·  "))
    } + listOfNotNull(centre?.let { (lat, lng) -> MapPoint("self", selfLabel, lat, lng, kind = MapPointKind.SELF) })

/** The radius circle around the TSO's fix of a shown answer. */
fun nearbyCircle(shown: RetailerUi.Shown): MapCircle = MapCircle(shown.lat, shown.lng, shown.radiusM)

/** One route of My Team with its SRs (D-TSO-MAN-34 "group by route"); [label] is the route code, "-" for SRs without a route. */
data class RouteGroup(val routeId: Long?, val label: String, val members: List<TeamMember>)

/** SRs grouped by their first route, routes in label order, SRs by name inside a route; members with no route last. */
fun teamGroups(members: List<TeamMember>, codes: Map<Long, String>): List<RouteGroup> =
    members.groupBy { it.routeIds.firstOrNull() }
        .map { (r, m) -> RouteGroup(r, r?.let { codes[it] ?: it.toString() } ?: "-", m.sortedWith(compareBy<TeamMember> { it.name }.thenBy { it.userId })) }
        .sortedWith(compareBy<RouteGroup> { it.routeId == null }.thenBy { it.label })

/** The legend line: each route with its number of SRs. */
fun legend(members: List<TeamMember>, codes: Map<Long, String>): List<Pair<String, Int>> = teamGroups(members, codes).map { it.label to it.members.size }

/**
 * My Team pins in route order ([teamGroups]); [mockNote] words the anti-spoofing chip for an SR with mock fixes today
 * (null for none). Greying an old fix is the map's job ([FixAge], [PeripheryConfig.staleAfterMin]).
 */
fun teamPoints(members: List<TeamMember>, codes: Map<Long, String>, mockNote: (Int) -> String? = { null }): List<MapPoint> =
    teamGroups(members, codes).flatMap { g -> g.members }.map { m ->
        val route = m.routeIds.firstOrNull()?.let { codes[it] ?: it.toString() }
        MapPoint(m.userId.toString(), if (route != null) "${m.name} ($route)" else m.name, m.lat, m.lng, m.atMs, m.source, MapPointKind.PERSON, if (m.mockFixesToday > 0) mockNote(m.mockFixesToday) else null)
    }

/** True when the SR's last fix is older than the configured age (or there is none): the row and pin are greyed. */
fun isStale(m: TeamMember, nowMs: Long, cfg: PeripheryConfig): Boolean = FixAge.of(m.atMs, nowMs, cfg.staleAfterMin)?.stale ?: true

sealed interface RetailerUi {
    data object Pick : RetailerUi
    data object Locating : RetailerUi
    data object Loading : RetailerUi
    data class Shown(val result: Stamped<Nearby>, val lat: Double, val lng: Double, val radiusM: Int, val zoneId: Long?) : RetailerUi
    /** The fix is outside Bangladesh (0,0, a failed or fake fix): nothing is sent. */
    data object OutOfArea : RetailerUi
    data object NeedsPermission : RetailerUi
    data object LocationOff : RetailerUi
    data object NoFix : RetailerUi
    /** Offline, no outlets in the bundle and no copy of this view on the phone. */
    data object Unavailable : RetailerUi
}

/**
 * F-TSO-012, D-TSO-MAN-34: zone and radius around ONE balanced fix, taken when the rep picks a zone or a radius, opens the
 * screen or taps the refresh icon (never a stream). Online the server answers; offline (or when the server cannot be
 * reached) the phone works the answer out from the bundle with the server's rule ([NearbyRule]); a copy of the same view
 * is the last resort.
 */
class RetailerState(private val source: PeripherySource, private val asOfNow: () -> String = { "" }, private val fix: PeripheryFix) {
    private val _s = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _s.asStateFlow()
    private var busy = false
    /** A find() asked for while one ran (a zone or radius pick, a refresh tap). */
    private var again = false

    data class Ui(
        val zones: List<Long> = emptyList(), val zoneId: Long? = null, val radiusM: Int = RADIUS_OPTIONS_M.first(), val phase: RetailerUi = RetailerUi.Pick,
        val mockWarning: Boolean = false, val permissionDenied: Boolean = false, val radii: List<Int> = RADIUS_OPTIONS_M,
    )

    /** The zones of the reach (the first one chosen, as the screenshot shows) and the radii of the config. */
    suspend fun loadZones() {
        val z = runCatching { source.zones() }.getOrNull().orEmpty().sorted()
        val cfg = runCatching { source.config() }.getOrDefault(PeripheryConfig())
        val s = _s.value
        _s.value = s.copy(zones = z, zoneId = s.zoneId?.takeIf { it in z } ?: z.firstOrNull(), radii = cfg.radiiM, radiusM = s.radiusM.takeIf { it in cfg.radiiM } ?: cfg.radiiM.first())
    }

    private var generation = 0
    fun pickZone(z: Long?) { generation++; _s.value = _s.value.copy(zoneId = z, phase = RetailerUi.Pick) }
    fun pickRadius(r: Int) { if (r in _s.value.radii) { generation++; _s.value = _s.value.copy(radiusM = r, phase = RetailerUi.Pick) } }

    /** The rep refused the location permission in the system dialog (no asking again): the screen then offers the app settings. */
    fun permissionRefused() { _s.value = _s.value.copy(permissionDenied = true, phase = RetailerUi.NeedsPermission) }

    /**
     * One balanced fix and its answer for the current zone and radius. A pick while a fix runs (its own find() comes in
     * while this one is busy) is not lost: the running find takes one more fix for the new choice once the old answer is
     * dropped. A second tap on refresh during a run takes no extra fix. A pick without a find of its own leaves the screen
     * on Pick (never on a "Loading" that no answer ends).
     */
    suspend fun find() {
        if (busy) { again = true; return }
        busy = true
        try {
            do {
                again = false
                val current = findOnce()
                // the rep changed zone or radius while this ran: the old choice's answer was dropped
                if (!current && !again) _s.value = _s.value.copy(phase = RetailerUi.Pick)
            } while (!current && again)
        } finally { busy = false; again = false }
    }

    /** One fix and one answer; false when the zone or radius changed meanwhile (nothing of the old choice is shown). */
    private suspend fun findOnce(): Boolean {
        val s = _s.value
        val gen = generation
        _s.value = s.copy(phase = RetailerUi.Locating, permissionDenied = false)
        val f = runCatching { fix.current() }.getOrElse { FixResult.Failed }
        if (gen != generation) return false
        when (f) {
            FixResult.PermissionNeeded -> _s.value = _s.value.copy(phase = RetailerUi.NeedsPermission)
            FixResult.LocationOff -> _s.value = _s.value.copy(phase = RetailerUi.LocationOff)
            FixResult.Failed -> _s.value = _s.value.copy(phase = RetailerUi.NoFix)
            is FixResult.Ok -> {
                if (!inBangladesh(f.lat, f.lng)) { _s.value = _s.value.copy(phase = RetailerUi.OutOfArea, mockWarning = f.isMock); return true }
                _s.value = _s.value.copy(phase = RetailerUi.Loading, mockWarning = f.isMock)
                val r = answer(f.lat, f.lng, s.radiusM, s.zoneId)
                if (gen != generation) return false
                _s.value = _s.value.copy(phase = if (r == null) RetailerUi.Unavailable else RetailerUi.Shown(r, f.lat, f.lng, s.radiusM, s.zoneId))
            }
        }
        return true
    }

    /** The server's answer; else the bundle worked out on the phone; else the last copy of this very view. */
    private suspend fun answer(lat: Double, lng: Double, radiusM: Int, zoneId: Long?): Stamped<Nearby>? {
        val server = runCatching { source.nearby(lat, lng, radiusM, zoneId) }.getOrNull()
        if (server != null && !server.fromCache) return server
        val bundle = runCatching { source.bundleOutlets(zoneId) }.getOrNull()
        if (!bundle.isNullOrEmpty()) {
            val at = asOfNow()
            return Stamped(NearbyRule.compute(bundle, lat, lng, radiusM, zoneId, at), at, fromCache = false, computedOnPhone = true)
        }
        return server
    }
}

sealed interface TeamUi {
    data object Loading : TeamUi
    data class Shown(val result: Stamped<List<TeamMember>>, val codes: Map<Long, String>) : TeamUi
    data object Unavailable : TeamUi
}

/** F-TSO-011, D-TSO-MAN-34: the SRs' last synced non-mock fixes (never live tracking), read on open and on Refresh only. */
class TeamState(private val source: PeripherySource, private val today: () -> String) {
    private val _s = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _s.asStateFlow()

    data class Ui(val zones: List<Long> = emptyList(), val zoneId: Long? = null, val phase: TeamUi = TeamUi.Loading, val config: PeripheryConfig = PeripheryConfig())

    suspend fun load() {
        val z = runCatching { source.zones() }.getOrNull().orEmpty().sorted()
        val cfg = runCatching { source.config() }.getOrDefault(PeripheryConfig())
        _s.value = _s.value.copy(zones = z, zoneId = _s.value.zoneId ?: z.firstOrNull(), config = cfg)
        refresh()
    }

    fun pickZone(z: Long?) { _s.value = _s.value.copy(zoneId = z) }

    suspend fun refresh() {
        _s.value = _s.value.copy(phase = TeamUi.Loading)
        val r = runCatching { source.team(_s.value.zoneId, today()) }.getOrNull()
        val codes = runCatching { source.routeCodes() }.getOrDefault(emptyMap())
        _s.value = _s.value.copy(phase = if (r == null) TeamUi.Unavailable else TeamUi.Shown(r, codes))
    }
}
