package com.aktcl.aron.feature.tso.periphery

import com.aktcl.aron.core.map.MapPoint
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** The radii of the Retailer map (`cfg.tso.periphery_radius_options_m`, docs/08): the server refuses any other. */
val RADIUS_OPTIONS_M = listOf(50, 100, 300)

data class NearbyOutlet(val id: Long, val code: String, val name: String, val lat: Double, val lng: Double, val distanceM: Double, val routeId: Long, val cluster: String?, val channel: String?, val phone: String?)
data class Nearby(val asOf: String, val radiusM: Int, val truncated: Boolean, val items: List<NearbyOutlet>)

data class TeamMember(val userId: Long, val name: String, val routeIds: List<Long>, val lat: Double?, val lng: Double?, val atMs: Long?, val source: String?)

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

    /** `GET /v1/team/locations` (contract `TeamLocationList`): `last_fix` null = the SR has not synced a fix yet. */
    fun team(body: String): List<TeamMember>? = runCatching {
        val o = JSONObject(body); val a = o.getJSONArray("items")
        (0 until a.length()).mapNotNull { i -> runCatching { a.getJSONObject(i).let { x ->
            val rids = x.getJSONArray("route_ids").let { r -> (0 until r.length()).map { r.getLong(it) } }
            val f = if (x.isNull("last_fix")) null else x.getJSONObject("last_fix")
            TeamMember(x.getLong("user_id"), str(x, "full_name"), rids, f?.getDouble("lat"), f?.getDouble("lng"), f?.let { runCatching { Instant.parse(it.getString("at")).toEpochMilli() }.getOrNull() }, f?.let { optStr(it, "source") })
        } }.getOrNull() }
    }.getOrNull()

    fun teamToCache(m: List<TeamMember>): String = JSONObject().put("items", JSONArray(m.map {
        JSONObject().put("user_id", it.userId).put("full_name", it.name).put("route_ids", JSONArray(it.routeIds)).put("last_fix",
            if (it.lat == null || it.lng == null || it.atMs == null) JSONObject.NULL else JSONObject().put("lat", it.lat).put("lng", it.lng).put("at", Instant.ofEpochMilli(it.atMs).toString()).put("source", it.source ?: "sync")) })).toString()
}

/** One answer with the time it was read ("as of hh:mm") and whether it came from the phone's last copy. */
data class Stamped<T>(val value: T, val asOf: String, val fromCache: Boolean)

interface PeripherySource {
    suspend fun zones(): List<Long>?
    /** The answer for a position; a cached copy is only ever returned for the SAME rounded position (see [positionKey]). */
    suspend fun nearby(lat: Double, lng: Double, radiusM: Int, zoneId: Long?): Stamped<Nearby>?
    suspend fun team(zoneId: Long?, date: String): Stamped<List<TeamMember>>?
    /** Route code labels for the legend (id to code), from the cached routes. */
    suspend fun routeCodes(): Map<Long, String>
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

fun nearbyPoints(n: Nearby, distance: (Int) -> String = { "$it m" }): List<MapPoint> =
    n.items.take(MAX_SHOWN).map { MapPoint(it.id.toString(), it.name + "  ·  " + it.code + "  ·  " + distance(it.distanceM.toInt()), it.lat, it.lng) }

/** Groups team members by their first route for the legend; members with no route fall under null. */
fun legend(members: List<TeamMember>, codes: Map<Long, String>): List<Pair<String, Int>> =
    members.groupBy { it.routeIds.firstOrNull() }.map { (r, m) -> (r?.let { codes[it] ?: it.toString() } ?: "-") to m.size }.sortedBy { it.first }

fun teamPoints(members: List<TeamMember>, codes: Map<Long, String>): List<MapPoint> = members.map { m ->
    val route = m.routeIds.firstOrNull()?.let { codes[it] ?: it.toString() }
    MapPoint(m.userId.toString(), if (route != null) "${m.name} ($route)" else m.name, m.lat, m.lng, m.atMs, m.source)
}

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
    /** Offline and no copy of this view on the phone. */
    data object Unavailable : RetailerUi
}

/** F-TSO-012: zone and radius around ONE fix taken when the rep taps Find; the answer is capped and kept for offline. */
class RetailerState(private val source: PeripherySource, private val fix: PeripheryFix) {
    private val _s = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _s.asStateFlow()
    private var busy = false

    data class Ui(val zones: List<Long> = emptyList(), val zoneId: Long? = null, val radiusM: Int = RADIUS_OPTIONS_M.first(), val phase: RetailerUi = RetailerUi.Pick, val mockWarning: Boolean = false, val permissionDenied: Boolean = false)

    suspend fun loadZones() { _s.value = _s.value.copy(zones = runCatching { source.zones() }.getOrNull().orEmpty().sorted()) }
    private var generation = 0
    fun pickZone(z: Long?) { generation++; _s.value = _s.value.copy(zoneId = z, phase = RetailerUi.Pick) }
    fun pickRadius(r: Int) { if (r in RADIUS_OPTIONS_M) { generation++; _s.value = _s.value.copy(radiusM = r, phase = RetailerUi.Pick) } }

    /** The rep refused the location permission in the system dialog (no asking again): the screen then offers the app settings. */
    fun permissionRefused() { _s.value = _s.value.copy(permissionDenied = true, phase = RetailerUi.NeedsPermission) }

    suspend fun find() {
        if (busy) return
        busy = true
        try {
            val s = _s.value
            val gen = generation
            _s.value = s.copy(phase = RetailerUi.Locating, permissionDenied = false)
            val f = runCatching { fix.current() }.getOrElse { FixResult.Failed }
            when (f) {
                FixResult.PermissionNeeded -> { _s.value = _s.value.copy(phase = RetailerUi.NeedsPermission); return }
                FixResult.LocationOff -> { _s.value = _s.value.copy(phase = RetailerUi.LocationOff); return }
                FixResult.Failed -> { _s.value = _s.value.copy(phase = RetailerUi.NoFix); return }
                is FixResult.Ok -> {
                    if (!inBangladesh(f.lat, f.lng)) { _s.value = _s.value.copy(phase = RetailerUi.OutOfArea, mockWarning = f.isMock); return }
                    _s.value = _s.value.copy(phase = RetailerUi.Loading, mockWarning = f.isMock)
                    val r = runCatching { source.nearby(f.lat, f.lng, s.radiusM, s.zoneId) }.getOrNull()
                    // the rep changed zone or radius while this ran: the answer belongs to the old choice and is dropped
                    if (gen != generation) return
                    _s.value = _s.value.copy(phase = if (r == null) RetailerUi.Unavailable else RetailerUi.Shown(r, f.lat, f.lng, s.radiusM, s.zoneId))
                }
            }
        } finally { busy = false }
    }
}

sealed interface TeamUi {
    data object Loading : TeamUi
    data class Shown(val result: Stamped<List<TeamMember>>, val codes: Map<Long, String>) : TeamUi
    data object Unavailable : TeamUi
}

/** F-TSO-011: the SRs' last synced fixes (never live), the answer kept for the offline list. */
class TeamState(private val source: PeripherySource, private val today: () -> String) {
    private val _s = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _s.asStateFlow()

    data class Ui(val zones: List<Long> = emptyList(), val zoneId: Long? = null, val phase: TeamUi = TeamUi.Loading)

    suspend fun load() {
        val z = runCatching { source.zones() }.getOrNull().orEmpty().sorted()
        _s.value = _s.value.copy(zones = z, zoneId = _s.value.zoneId ?: z.firstOrNull())
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
