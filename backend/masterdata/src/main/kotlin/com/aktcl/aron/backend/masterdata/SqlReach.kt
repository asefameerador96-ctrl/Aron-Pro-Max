package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.contract.Role
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Geography snapshot from PostgreSQL, refreshed at most every [ttlMs] (master data changes rarely). */
class GeoRepository(private val db: Database, private val clock: AronClock = AronClock.SYSTEM, private val ttlMs: Long = 60_000) {
    private val cached = AtomicReference<Pair<Long, Geo>?>(null)

    fun geo(): Geo {
        val now = clock.now().toEpochMilli()
        cached.get()?.let { (at, g) -> if (now - at < ttlMs) return g }
        return load().also { cached.set(now to it) }
    }

    fun invalidate() = cached.set(null)

    /** The snapshot, reloaded once when it lacks a route or zone that live rows already reference (no 60 s blind spot). */
    fun geoCovering(routeIds: Collection<Long> = emptyList(), zoneIds: Collection<Long> = emptyList()): Geo {
        val g = geo()
        if (routeIds.all { it in g.routeZone } && zoneIds.all { it in g.zoneTerritory }) return g
        invalidate()
        return geo()
    }

    private fun load(): Geo = db.jdbi.withHandle<Geo, Exception> { h ->
        fun pairs(sql: String) = h.createQuery(sql).map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.list().toMap()
        val names = HashMap<Pair<String, Long>, Pair<String?, String?>>()
        for (t in listOf("wing", "division", "territory", "zone", "route")) {
            h.createQuery("SELECT id, code, name FROM app.$t").map { rs, _ -> (t to rs.getLong(1)) to (rs.getString(2) to rs.getString(3)) }
                .list().forEach { names[it.first] = it.second }
        }
        Geo(
            zoneTerritory = pairs("SELECT id, territory_id FROM app.zone"),
            territoryDivision = pairs("SELECT id, division_id FROM app.territory"),
            divisionWing = pairs("SELECT id, wing_id FROM app.division"),
            routeZone = pairs("SELECT id, zone_id FROM app.route"),
            names = names,
        )
    }
}

/**
 * Reach from `user_scope` and `route_assignment` (docs/24 s8.4), computed by [ReachCalculator] and cached per
 * (user, scope_version, business date) for 5 minutes. A change of scope or assignment bumps `scope_version`, so a
 * stale entry is never used by a token minted after the change.
 */
class SqlReachResolver(private val db: Database, private val geo: GeoRepository, private val clock: AronClock = AronClock.SYSTEM) : ReachResolver {
    private data class Key(val userId: Long, val sv: Long, val date: LocalDate)
    private val cache = ConcurrentHashMap<Key, Pair<Long, Reach>>()

    override fun reach(userId: Long, role: Role, scopeVersion: Long, businessDate: LocalDate): Reach {
        val now = clock.now().toEpochMilli()
        val key = Key(userId, scopeVersion, businessDate)
        cache[key]?.let { (at, r) -> if (now - at < 300_000 && r.role == role) return r }
        val (scope, asg) = db.jdbi.withHandle<Pair<List<ScopeRow>, List<AssignmentRow>>, Exception> { h ->
            h.createQuery("SELECT node_type, node_id, valid_from, valid_to FROM app.user_scope WHERE user_id = :u").bind("u", userId)
                .map { rs, _ -> ScopeRow(rs.getString(1), rs.getLong(2), rs.getObject(3, LocalDate::class.java), rs.getObject(4, LocalDate::class.java)) }.list() to
                h.createQuery("SELECT route_id, kind, valid_from, valid_to FROM app.route_assignment WHERE user_id = :u").bind("u", userId)
                    .map { rs, _ -> AssignmentRow(rs.getLong(1), rs.getString(2), rs.getObject(3, LocalDate::class.java), rs.getObject(4, LocalDate::class.java)) }.list()
        }
        val r = ReachCalculator.compute(userId, role, businessDate, scope, asg, geo.geoCovering(routeIds = asg.map { it.routeId }))
        if (cache.size > 50_000) cache.clear()
        cache[key] = now to r
        return r
    }
}

/** Outlets in reach straight from SQL (the scope-leak harness checks it against the oracle). */
class SqlOutletReach(private val db: Database, private val geo: GeoRepository) {
    /**
     * Ids of the outlets the [reach] covers inside [sel] (ordered by id); 403 ERR_OUT_OF_SCOPE for a selector
     * outside the reach. Reach comes only from the server-side [Reach]; nothing the client sends widens it.
     */
    fun ids(reach: Reach, sel: GeoSelector): List<Long> {
        val check = ReachFilter.check(reach, sel, geo.geo())
        if (check.empty) return emptyList()
        return db.jdbi.withHandle<List<Long>, Exception> { h ->
            h.createQuery("SELECT o.id FROM app.outlet o WHERE ${reachPredicate(reach)} AND ${selectorPredicate(check, sel)} ORDER BY o.id")
                .bindReach(reach, check, sel).mapTo(Long::class.java).list()
        }
    }
}

internal fun reachPredicate(reach: Reach): String = when {
    reach.ownRecordsOnly -> "o.route_id = ANY(:reach_routes)"
    reach.national -> "TRUE"
    else -> "(o.zone_id = ANY(:reach_zones) OR o.route_id = ANY(:reach_routes))"
}

internal fun selectorPredicate(check: ReachFilter.Check, sel: GeoSelector): String =
    listOfNotNull(check.zones?.let { "o.zone_id = ANY(:sel_zones)" }, sel.routeId?.let { "o.route_id = :sel_route" }).ifEmpty { listOf("TRUE") }.joinToString(" AND ")

internal fun <T : org.jdbi.v3.core.statement.SqlStatement<T>> T.bindReach(reach: Reach, check: ReachFilter.Check, sel: GeoSelector): T {
    var s = this
    if (!reach.national || reach.ownRecordsOnly) {
        s = s.bindArray("reach_routes", Long::class.javaObjectType, reach.routeIds.toList())
        if (!reach.ownRecordsOnly) s = s.bindArray("reach_zones", Long::class.javaObjectType, reach.zoneIds.toList())
    }
    check.zones?.let { s = s.bindArray("sel_zones", Long::class.javaObjectType, it.toList()) }
    sel.routeId?.let { s = s.bind("sel_route", it) }
    return s
}
