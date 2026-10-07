package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import com.aktcl.aron.rules.BusinessDate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime

@Serializable
data class LastFix(val lat: Double, val lng: Double, val accuracy_m: Double?, val at: String, val source: String, val age_min: Int)

@Serializable
data class TeamLocation(val user_id: Long, val full_name: String, val route_ids: List<Long>, val last_fix: LastFix?)

@Serializable
data class TeamLocationList(val as_of: String, val items: List<TeamLocation>)

class TeamDeps(val db: Database, val reach: ReachResolver, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM)

private val SUPERVISORS = setOf(Role.AMO, Role.TSO, Role.DMO, Role.WM, Role.TOP, Role.SUPPORT, Role.ADMIN, Role.SUPERADMIN)

/**
 * GET /v1/team/locations (contract getTeamLocations, N-036): the last synced fix of each field user on a route in the
 * caller's reach on the business date, from check-in/out, visits and breadcrumbs, with its age. No live tracking: a
 * point exists only once its record has synced. Reach comes from the token; `zone_id` only narrows it.
 */
fun Route.teamRoutes(d: TeamDeps) {
    authenticated(d.guard) {
        get("/team/locations") {
            val p = call.principal
            if (p.role !in SUPERVISORS) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "team locations are for supervisors")
            val now = d.clock.now()
            val date = call.request.queryParameters["business_date"]?.let {
                runCatching { LocalDate.parse(it) }.getOrNull() ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "business_date", errors = listOf(FieldError("query.business_date", "invalid_value")))
            } ?: BusinessDate.of(now.toEpochMilli()).toJavaLocalDate()
            val zone = call.request.queryParameters["zone_id"]?.let {
                it.toLongOrNull()?.takeIf { z -> z >= 1 } ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "zone_id", errors = listOf(FieldError("query.zone_id", "invalid_value")))
            }
            val reach = d.reach.reach(p.userId, p.role, p.scopeVersion, date)
            if (zone != null && !reach.coversZone(zone)) throw ApiProblem(ProblemCode.ERR_OUT_OF_SCOPE, "zone outside your reach")
            val rows = d.db.jdbi.withHandle<List<TeamLocation>, Exception> { h ->
                data class Member(val id: Long, val name: String, val routes: List<Long>)
                val members = h.createQuery(
                    """
                    SELECT u.id, u.full_name, array_agg(DISTINCT a.route_id ORDER BY a.route_id) AS routes, array_agg(DISTINCT r.zone_id) AS zones
                    FROM app.route_assignment a JOIN app.route r ON r.id = a.route_id JOIN app.app_user u ON u.id = a.user_id
                    WHERE a.valid_from <= :d AND (a.valid_to IS NULL OR a.valid_to > :d) AND u.id <> :me AND (CAST(:z AS bigint) IS NULL OR r.zone_id = :z)
                    GROUP BY u.id, u.full_name ORDER BY u.id
                    """.trimIndent(),
                ).bind("d", date).bind("me", p.userId).bind("z", zone).map { rs, _ ->
                    @Suppress("UNCHECKED_CAST")
                    val routes = (rs.getArray("routes").array as Array<Any>).map { (it as Number).toLong() }
                    Member(rs.getLong("id"), rs.getString("full_name"), routes)
                }.list()
                // Only members on a route the caller reaches, and only those routes.
                val zoneOf = h.createQuery("SELECT id, zone_id FROM app.route WHERE id = ANY(:r)")
                    .bindArray("r", Long::class.javaObjectType, members.flatMap { it.routes }.distinct()).map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.list().toMap()
                members.mapNotNull { m ->
                    val visible = m.routes.filter { r -> reach.coversRoute(r, zoneOf.getValue(r)) }
                    if (visible.isEmpty()) null else TeamLocation(m.id, m.name, visible, lastFix(h, m.id, date, now))
                }
            }
            call.respond(TeamLocationList(now.wire(), rows))
        }
    }
}

private fun lastFix(h: org.jdbi.v3.core.Handle, userId: Long, date: LocalDate, now: java.time.Instant): LastFix? = h.createQuery(
    """
    SELECT lat, lng, acc, at, source FROM (
        SELECT fix_lat AS lat, fix_lng AS lng, fix_accuracy_m AS acc, captured_at AS at, kind AS source FROM app.attendance_event
         WHERE user_id = :u AND business_date = :d AND fix_lat IS NOT NULL AND voided_at IS NULL
        UNION ALL
        SELECT fix_lat, fix_lng, fix_accuracy_m, captured_at, 'visit' FROM app.visit WHERE user_id = :u AND business_date = :d AND fix_lat IS NOT NULL AND voided_at IS NULL
        UNION ALL
        SELECT fix_lat, fix_lng, fix_accuracy_m, captured_at, 'breadcrumb' FROM app.geo_breadcrumb WHERE user_id = :u AND business_date = :d AND fix_lat IS NOT NULL AND voided_at IS NULL
    ) f ORDER BY at DESC LIMIT 1
    """.trimIndent(),
).bind("u", userId).bind("d", date).map { rs, _ ->
    val at = rs.getObject("at", OffsetDateTime::class.java).toInstant()
    LastFix(rs.getDouble("lat"), rs.getDouble("lng"), rs.getObject("acc") as Double?, at.wire(), rs.getString("source"),
        Duration.between(at, now).toMinutes().coerceAtLeast(0).toInt())
}.findOne().orElse(null)
