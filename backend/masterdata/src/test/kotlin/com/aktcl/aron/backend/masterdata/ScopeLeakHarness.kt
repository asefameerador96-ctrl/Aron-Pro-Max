package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import java.time.LocalDate
import kotlin.random.Random

/**
 * A random world (geography, routes, outlets, users with effective-dated scope and assignments) and an oracle that
 * decides visibility from first principles (walking each outlet's ancestors), independent of [ReachCalculator].
 * Any outlet source (the in-memory filter, the SQL repository) is checked against the same oracle.
 */
class ScopeWorld(seed: Long) {
    val rnd = Random(seed)
    val wings = listOf(1L, 2L)
    val divisionWing = mutableMapOf<Long, Long>()
    val territoryDivision = mutableMapOf<Long, Long>()
    val zoneTerritory = mutableMapOf<Long, Long>()
    val routeZone = mutableMapOf<Long, Long>()
    val outlets = mutableListOf<OutletRef>()
    data class User(val id: Long, val role: Role, val scope: List<ScopeRow>, val assignments: List<AssignmentRow>)
    val users = mutableListOf<User>()
    val day0: LocalDate = LocalDate.parse("2026-10-01")

    init {
        var d = 10L; var t = 100L; var z = 1000L; var r = 10_000L; var o = 100_000L
        for (w in wings) repeat(2) {
            val div = d++; divisionWing[div] = w
            repeat(2) {
                val ter = t++; territoryDivision[ter] = div
                repeat(3) {
                    val zone = z++; zoneTerritory[zone] = ter
                    repeat(3) {
                        val route = r++; routeZone[route] = zone
                        repeat(4) { outlets += OutletRef(o++, zone, route) }
                    }
                    repeat(2) { outlets += OutletRef(o++, zone, null) } // outlets not on any route yet
                }
            }
        }
        var uid = 1L
        val roles = Role.entries
        repeat(60) {
            val role = roles[rnd.nextInt(roles.size)]
            val scope = List(rnd.nextInt(0, 3)) { randomScopeRow() }
            val asg = List(rnd.nextInt(0, 4)) {
                val from = day0.plusDays(rnd.nextLong(-5, 10))
                AssignmentRow(routeZone.keys.random(rnd), if (rnd.nextBoolean()) "primary" else "cover", from, if (rnd.nextBoolean()) from.plusDays(rnd.nextLong(1, 8)) else null)
            }
            users += User(uid++, role, scope, asg)
        }
    }

    private fun randomScopeRow(): ScopeRow {
        val (type, id) = when (rnd.nextInt(10)) {
            0 -> "national" to 0L
            1 -> "wing" to wings.random(rnd)
            2, 3 -> "division" to divisionWing.keys.random(rnd)
            4, 5, 6 -> "territory" to territoryDivision.keys.random(rnd)
            else -> "zone" to zoneTerritory.keys.random(rnd)
        }
        val from = day0.plusDays(rnd.nextLong(-5, 10))
        return ScopeRow(type, id, from, if (rnd.nextBoolean()) from.plusDays(rnd.nextLong(1, 8)) else null)
    }

    val geo get() = Geo(zoneTerritory, territoryDivision, divisionWing, routeZone)

    private fun ancestors(zone: Long): Map<String, Long> {
        val t = zoneTerritory.getValue(zone); val d = territoryDivision.getValue(t); val w = divisionWing.getValue(d)
        return mapOf("zone" to zone, "territory" to t, "division" to d, "wing" to w, "national" to 0L)
    }

    private fun inDates(from: LocalDate, to: LocalDate?, d: LocalDate) = !d.isBefore(from) && (to == null || d.isBefore(to))

    /** Oracle: may [u] see outlet [o] on [date]? */
    fun visible(u: User, o: OutletRef, date: LocalDate): Boolean {
        val assigned = o.routeId != null && u.assignments.any { it.routeId == o.routeId && inDates(it.validFrom, it.validTo, date) }
        return when (u.role) {
            Role.TOP, Role.ANALYST, Role.SUPPORT, Role.ADMIN, Role.SUPERADMIN -> true
            Role.SR -> assigned
            else -> assigned || u.scope.any { s -> inDates(s.validFrom, s.validTo, date) && ancestors(o.zoneId)[s.nodeType] == s.nodeId }
        }
    }

    fun inSelector(o: OutletRef, s: GeoSelector): Boolean {
        val a = ancestors(o.zoneId)
        return (s.wingId == null || a["wing"] == s.wingId) && (s.divisionId == null || a["division"] == s.divisionId) &&
            (s.territoryId == null || a["territory"] == s.territoryId) && (s.zoneId == null || a["zone"] == s.zoneId) &&
            (s.routeId == null || o.routeId == s.routeId)
    }

    fun randomSelector(): GeoSelector = when (rnd.nextInt(7)) {
        0, 1 -> GeoSelector()
        2 -> GeoSelector(wingId = wings.random(rnd))
        3 -> GeoSelector(divisionId = divisionWing.keys.random(rnd))
        4 -> GeoSelector(territoryId = territoryDivision.keys.random(rnd))
        5 -> GeoSelector(zoneId = zoneTerritory.keys.random(rnd))
        else -> GeoSelector(routeId = routeZone.keys.random(rnd))
    }

    fun randomDate(): LocalDate = day0.plusDays(rnd.nextLong(-3, 14))

    /** Result of one query by the system under test: rows, or the problem code it answered with. */
    sealed interface Outcome { data class Rows(val ids: Set<Long>) : Outcome; data class Refused(val code: ProblemCode) : Outcome }

    data class Leak(val query: Int, val user: User, val date: LocalDate, val sel: GeoSelector, val detail: String)

    /**
     * Runs [n] randomised queries through [sut] and returns every disagreement with the oracle: a row outside the
     * reach (a leak), a row in reach and selector that is missing, or a refusal when the oracle sees rows.
     */
    fun run(n: Int, sut: (User, LocalDate, GeoSelector) -> Outcome): List<Leak> {
        val leaks = mutableListOf<Leak>()
        repeat(n) { q ->
            val u = users.random(rnd); val date = randomDate(); val sel = randomSelector()
            val expected = outlets.filter { visible(u, it, date) && inSelector(it, sel) }.map { it.id }.toSet()
            when (val got = sut(u, date, sel)) {
                is Outcome.Rows -> {
                    val leaked = got.ids - expected
                    val missing = expected - got.ids
                    if (leaked.isNotEmpty()) leaks += Leak(q, u, date, sel, "leaked ${leaked.take(5)}")
                    if (missing.isNotEmpty()) leaks += Leak(q, u, date, sel, "missing ${missing.take(5)}")
                }
                is Outcome.Refused -> {
                    if (got.code != ProblemCode.ERR_OUT_OF_SCOPE) leaks += Leak(q, u, date, sel, "unexpected ${got.code}")
                    if (expected.isNotEmpty()) leaks += Leak(q, u, date, sel, "refused but ${expected.size} rows are in reach")
                }
            }
        }
        return leaks
    }
}

/** Runs a query and maps ERR_OUT_OF_SCOPE to [ScopeWorld.Outcome.Refused]. */
fun outcomeOf(block: () -> Collection<Long>): ScopeWorld.Outcome = try {
    ScopeWorld.Outcome.Rows(block().toSet())
} catch (e: ApiProblem) {
    ScopeWorld.Outcome.Refused(e.code)
}
