package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.contract.Role
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** One geography snapshot and its [version] (counts the snapshots that differed from the one before); a cache of anything derived from the geography keys on the pair. */
class GeoSnapshot(val geo: Geo, val version: Long)

/** Geography snapshot from PostgreSQL, refreshed at most every [ttlMs] (master data changes rarely). */
class GeoRepository(
    private val db: Database, private val clock: AronClock = AronClock.SYSTEM, private val ttlMs: Long = 60_000,
    /** How long a caller that must not see the old geography (after an [invalidate]) waits for a reload in progress before it loads on its own; keep it below the pool's connection timeout. */
    private val lockWaitMs: Long = 1_000,
) {
    /** A snapshot is valid only for the [gen]eration it was read under: [invalidate] bumps the generation, so no reader accepts an entry that predates it. */
    private class Cached(val at: Long, val snap: GeoSnapshot, val gen: Long)
    private val cached = AtomicReference<Cached?>(null)
    /** Bumped by [invalidate]: a load that began before it read the geography before the edit and must not be served again. */
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private val lock = java.util.concurrent.locks.ReentrantLock()
    private var lastGeo: Geo? = null
    private var changes = 0L
    /** Test seams: after a load has read the database; and after the staleness check, right before the snapshot is installed. */
    @Volatile internal var afterLoad: (() -> Unit)? = null
    @Volatile internal var beforeInstall: (() -> Unit)? = null
    /** Test seam: runs at the start of [invalidate]. */
    @Volatile internal var onInvalidate: (() -> Unit)? = null

    fun geo(): Geo = snapshot().geo

    /** The geography version (see [GeoSnapshot]); refreshes the snapshot when it is older than the TTL. */
    fun version(): Long = snapshot().version

    private fun isFresh(c: Cached) = clock.now().toEpochMilli() - c.at < ttlMs && c.gen == generation.get()
    private fun fresh(c: Cached?): GeoSnapshot? = c?.takeIf { isFresh(it) }?.snap

    /**
     * The current snapshot and its version as ONE pair (a reach computed from the geography is cached under the version of that same geography).
     * Only one thread reloads at a time. When the entry is merely older than the TTL (no edit since), a caller that finds a reload in progress is served the
     * expired entry instead of waiting: a request that holds a pool connection must never queue behind a loader that needs one. After an [invalidate] no old
     * entry may be served; a caller then waits up to [lockWaitMs] for the reload in progress and, failing that, loads on its own (it began after the edit).
     */
    fun snapshot(): GeoSnapshot {
        val c0 = cached.get()
        fresh(c0)?.let { return it }
        val ttlOnly = c0 != null && c0.gen == generation.get()
        val got = if (ttlOnly) lock.tryLock() else lock.tryLock(lockWaitMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!got) {
            if (ttlOnly) return c0!!.snap
            val g = load()
            return GeoSnapshot(g, register(g))
        }
        try {
            fresh(cached.get())?.let { return it }
            var attempt = 0
            while (true) {
                val gen = generation.get()
                val g = load()
                afterLoad?.invoke()
                val stale = generation.get() != gen
                // An edit committed and invalidated while this load ran: it may predate the edit. Read again; after a few tries answer with what was read, uncached.
                if (stale && ++attempt < 3) continue
                val snap = GeoSnapshot(g, register(g))
                beforeInstall?.invoke()
                // Tagged with the generation it was read under: an invalidation after this point (even one statement later) makes every reader reject the entry.
                if (!stale) cached.set(Cached(clock.now().toEpochMilli(), snap, gen))
                return snap
            }
        } finally { lock.unlock() }
    }

    @Synchronized private fun register(g: Geo): Long { if (lastGeo != g) { lastGeo = g; changes++ }; return changes }

    fun invalidate() { onInvalidate?.invoke(); generation.incrementAndGet(); cached.set(null) }

    /** The snapshot, reloaded once when it lacks a route or zone that live rows already reference (no 60 s blind spot). */
    fun snapshotCovering(routeIds: Collection<Long> = emptyList(), zoneIds: Collection<Long> = emptyList()): GeoSnapshot {
        val s = snapshot()
        if (routeIds.all { it in s.geo.routeZone } && zoneIds.all { it in s.geo.zoneTerritory }) return s
        invalidate()
        return snapshot()
    }

    fun geoCovering(routeIds: Collection<Long> = emptyList(), zoneIds: Collection<Long> = emptyList()): Geo = snapshotCovering(routeIds, zoneIds).geo

    /**
     * ONE statement: a single MVCC snapshot under READ COMMITTED, so the chain zone -> territory -> division -> wing is never a mix of two commits
     * (separate statements could read a zone's new territory and that territory's old division). It also runs on a handle the caller already holds.
     */
    private fun load(): Geo = db.jdbi.withHandle<Geo, Exception> { h ->
        val zoneTerritory = HashMap<Long, Long>(); val territoryDivision = HashMap<Long, Long>(); val divisionWing = HashMap<Long, Long>()
        val routeZone = HashMap<Long, Long>(); val zoneHouse = HashMap<Long, Long>()
        val names = HashMap<Pair<String, Long>, Pair<String?, String?>>()
        h.createQuery(
            """
            SELECT 'zone' k, id a, territory_id b, NULL::text c, NULL::text d FROM app.zone
            UNION ALL SELECT 'territory', id, division_id, NULL, NULL FROM app.territory
            UNION ALL SELECT 'division', id, wing_id, NULL, NULL FROM app.division
            UNION ALL SELECT 'route', id, zone_id, NULL, NULL FROM app.route
            UNION ALL SELECT 'zone_house', id, house_id, NULL, NULL FROM app.zone WHERE house_id IS NOT NULL
            UNION ALL SELECT 'n:wing', id, NULL, code, name FROM app.wing
            UNION ALL SELECT 'n:division', id, NULL, code, name FROM app.division
            UNION ALL SELECT 'n:territory', id, NULL, code, name FROM app.territory
            UNION ALL SELECT 'n:house', id, NULL, code, name FROM app.house
            UNION ALL SELECT 'n:zone', id, NULL, code, name FROM app.zone
            UNION ALL SELECT 'n:route', id, NULL, code, name FROM app.route
            """.trimIndent(),
        ).map { rs, _ ->
            val k = rs.getString(1); val a = rs.getLong(2); val b = rs.getLong(3)
            when {
                k == "zone" -> zoneTerritory[a] = b
                k == "territory" -> territoryDivision[a] = b
                k == "division" -> divisionWing[a] = b
                k == "route" -> routeZone[a] = b
                k == "zone_house" -> zoneHouse[a] = b
                else -> names[k.removePrefix("n:") to a] = rs.getString(4) to rs.getString(5)
            }
        }.list()
        Geo(zoneTerritory, territoryDivision, divisionWing, routeZone, names, zoneHouse)
    }
}

/**
 * Reach from `user_scope` and `route_assignment` (docs/24 s8.4), computed by [ReachCalculator] and cached per
 * (user, scope_version, business date, geography version) for 5 minutes. A change of scope or assignment bumps `scope_version`, so a
 * stale entry is never used by a token minted after the change.
 */
class SqlReachResolver(private val db: Database, private val geo: GeoRepository, private val clock: AronClock = AronClock.SYSTEM) : ReachResolver {
    private data class Key(val userId: Long, val sv: Long, val date: LocalDate, val geoVersion: Long)
    private val cache = ConcurrentHashMap<Key, Pair<Long, Reach>>()
    @Volatile private var cacheVersion = -1L
    internal fun cacheSize() = cache.size

    override fun reach(userId: Long, role: Role, scopeVersion: Long, businessDate: LocalDate): Reach {
        val now = clock.now().toEpochMilli()
        // Keyed by the user's CURRENT scope version and role (round 2, N-a): an upload token minted before a role change
        // (sync/batch skips the scope-version check) must not hit an entry computed while the old role was current.
        val (current, sv) = db.jdbi.withHandle<Pair<Role?, Long>, Exception> { h ->
            h.createQuery("SELECT role, scope_version FROM app.app_user WHERE id = :u").bind("u", userId)
                .map { rs, _ -> Role.entries.firstOrNull { it.wire == rs.getString(1) } to rs.getLong(2) }.findOne().orElse(null to scopeVersion)
        }
        // The geography version too: a zone created or moved does not bump scope_version, yet it changes the zones a territory/division/wing
        // reach holds and the zones a node dashboard reads live from app.zone (D-REPORTS-NODEASWAS-04), so the two must agree.
        val snap = geo.snapshot()
        // A new geography version empties the cache (the old entries could never be hit again, and each keeps its snapshot's views alive).
        if (snap.version > cacheVersion) synchronized(cache) { if (snap.version > cacheVersion) { cache.clear(); cacheVersion = snap.version } }
        val key = Key(userId, sv, businessDate, snap.version)
        cache[key]?.let { (at, r) -> if (now - at < 300_000 && r.role == role) return r }
        val (scope, asg) = db.jdbi.withHandle<Pair<List<ScopeRow>, List<AssignmentRow>>, Exception> { h ->
            h.createQuery("SELECT node_type, node_id, valid_from, valid_to FROM app.user_scope WHERE user_id = :u").bind("u", userId)
                .map { rs, _ -> ScopeRow(rs.getString(1), rs.getLong(2), rs.getObject(3, LocalDate::class.java), rs.getObject(4, LocalDate::class.java)) }.list() to
                h.createQuery("SELECT route_id, kind, valid_from, valid_to FROM app.route_assignment WHERE user_id = :u").bind("u", userId)
                    .map { rs, _ -> AssignmentRow(rs.getLong(1), rs.getString(2), rs.getObject(3, LocalDate::class.java), rs.getObject(4, LocalDate::class.java)) }.list()
        }
        // ONE (snapshot, version) pair for the compute and the key it is stored under.
        val used = if (asg.all { it.routeId in snap.geo.routeZone }) snap else geo.snapshotCovering(routeIds = asg.map { it.routeId })
        val r = ReachCalculator.compute(userId, role, businessDate, scope, asg, used.geo, current)
        if (cache.size > 50_000) cache.clear()
        cache[Key(userId, sv, businessDate, used.version)] = now to r
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
