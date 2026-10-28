package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.AuditLog
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import com.aktcl.aron.rules.BusinessDate
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.util.UUID

/**
 * The audited range rebuild (data-architecture review High 2): `rebuild(from, to, scope)` queues every route-day with a record in the range and
 * scope for the aggregation worker, under one `rebuild_id` that the restatement log carries into every figure the rebuild changes.
 * ADMIN and SUPERADMIN only; the scope must be named (national, zones or routes) and lies inside the caller's reach, which comes from the
 * token's user, never from the request. The request is written to the audit chain in the same transaction. No HTTP surface yet (the contract has
 * no admin rebuild path: v1.10 queue row).
 */
class RebuildService(private val db: Database, private val clock: AronClock = AronClock.SYSTEM) {
    /** What to rebuild: everything ([national], needs a national reach), or the routes of [zoneIds] (as the route belonged to them on each date) and [routeIds]. */
    data class Scope(val national: Boolean = false, val zoneIds: Set<Long> = emptySet(), val routeIds: Set<Long> = emptySet())

    data class Result(val rebuildId: UUID, val routeDays: Int)

    fun rebuild(actor: AronPrincipal, reach: Reach, from: LocalDate, to: LocalDate, scope: Scope, reason: String, requestId: String? = null): Result {
        if (reach.userId != actor.userId) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "reach does not belong to the caller")
        if (actor.role !in ROLES) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "a rebuild is an admin action")
        val today = BusinessDate.of(clock.now().toEpochMilli()).toJavaLocalDate()
        fun bad(field: String, msg: String): Nothing = throw ApiProblem(ProblemCode.ERR_VALIDATION, msg, errors = listOf(FieldError(field, "invalid_value")))
        if (reason.isBlank()) bad("body.reason", "a reason is required")
        if (from.isAfter(to)) bad("body.from", "from is after to")
        if (to.isAfter(today)) bad("body.to", "to is in the future")
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) bad("body.to", "at most $MAX_DAYS days per rebuild")
        if (!scope.national && scope.zoneIds.isEmpty() && scope.routeIds.isEmpty()) bad("body.scope", "name national, zones or routes")
        if (reach.ownRecordsOnly) throw ApiProblem(ProblemCode.ERR_OUT_OF_SCOPE, "scope is outside your reach")
        if (scope.national && !reach.national) throw ApiProblem(ProblemCode.ERR_OUT_OF_SCOPE, "scope is outside your reach")
        if (!reach.national && !reach.zoneIds.containsAll(scope.zoneIds)) throw ApiProblem(ProblemCode.ERR_OUT_OF_SCOPE, "scope is outside your reach")
        val rebuildId = UUID.randomUUID()
        return db.jdbi.inTransaction<Result, Exception> { h ->
            if (!reach.national) {
                // Routes by id are checked against the zones of the caller's reach (the route's zone on its current record).
                val outside = h.createQuery("SELECT count(*) FROM app.route WHERE id = ANY(:r) AND NOT (zone_id = ANY(:z))")
                    .bindArray("r", Long::class.javaObjectType, scope.routeIds.toList()).bindArray("z", Long::class.javaObjectType, reach.zoneIds.toList()).mapTo(Long::class.java).one()
                val known = h.createQuery("SELECT count(*) FROM app.route WHERE id = ANY(:r)").bindArray("r", Long::class.javaObjectType, scope.routeIds.toList()).mapTo(Long::class.java).one()
                if (outside > 0 || known < scope.routeIds.size) throw ApiProblem(ProblemCode.ERR_OUT_OF_SCOPE, "scope is outside your reach")
            }
            val days = h.createQuery(
                """
                SELECT k.route_id, k.business_date FROM (
                  SELECT route_id, business_date FROM app.route_day WHERE business_date BETWEEN :f AND :t
                  UNION SELECT route_id, business_date FROM app.visit WHERE business_date BETWEEN :f AND :t AND route_id IS NOT NULL
                  UNION SELECT route_id, business_date FROM app.memo WHERE business_date BETWEEN :f AND :t AND route_id IS NOT NULL
                  UNION SELECT route_id, business_date FROM app.due_collection WHERE business_date BETWEEN :f AND :t AND route_id IS NOT NULL
                  UNION SELECT route_id, business_date FROM dw.agg_daily_route WHERE business_date BETWEEN :f AND :t) k
                 CROSS JOIN LATERAL (SELECT coalesce(app.route_zone_on(k.route_id, k.business_date), (SELECT zone_id FROM app.route WHERE id = k.route_id)) AS zone_id) z
                 WHERE (:nat OR k.route_id = ANY(:routes) OR z.zone_id = ANY(:zones))
                   AND (:reachNat OR z.zone_id = ANY(:reachZones))
                """,
            ).bind("f", from).bind("t", to).bind("nat", scope.national).bind("reachNat", reach.national)
                .bindArray("routes", Long::class.javaObjectType, scope.routeIds.toList()).bindArray("zones", Long::class.javaObjectType, scope.zoneIds.toList())
                .bindArray("reachZones", Long::class.javaObjectType, reach.zoneIds.toList())
                .map { rs, _ -> rs.getLong(1) to rs.getObject(2, LocalDate::class.java) }.list()
            // One statement for all route-days (a national 93-day range is large); the keys are the distinct pairs above.
            h.createUpdate("SELECT app.mark_dirty('route_day_agg', r, d, :why) FROM unnest(CAST(:rs AS bigint[]), CAST(:ds AS date[])) AS t(r, d)")
                .bind("why", "rebuild:$rebuildId").bindArray("rs", Long::class.javaObjectType, days.map { it.first }).bindArray("ds", String::class.java, days.map { it.second.toString() }).execute()
            AuditLog.write(
                h, actor, "dw_rebuild", rebuildId.toString(), "rebuild", null,
                buildJsonObject {
                    put("from", from.toString()); put("to", to.toString()); put("route_days", days.size)
                    put("scope", auditScope(scope.national, scope.zoneIds.ifEmpty { null }, scope.routeIds.ifEmpty { null }))
                },
                reason, requestId, via = "api",
            )
            Result(rebuildId, days.size)
        }
    }

    companion object {
        private val ROLES = setOf(Role.ADMIN, Role.SUPERADMIN)
        const val MAX_DAYS = 93
    }
}
