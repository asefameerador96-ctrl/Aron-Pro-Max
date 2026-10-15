package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.config.AuditWriter
import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.receiveStrict
import com.aktcl.aron.backend.platform.requestId
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Contract `ReasonRequest` and `BackfillMatchResult`. */
@Serializable
data class BackfillMatchIn(val reason: String)

@Serializable
data class BackfillMatchOut(
    val paper_memo_id: Long, val paper_memo_uuid: String, val released: List<String>, val kept_open: List<String>,
    val voided_paper_visit_uuid: String?, val phone_visit_uuid: String?,
)

/** What backend-core's `IngestService.matchBackfill` answered, without this module depending on `sync`. */
fun interface BackfillMatcher {
    fun match(quarantineId: Long, resolverUserId: Long, reason: String): BackfillMatchOut
}

class BackfillMatchDeps(val db: Database, val reach: ReachResolver, val guard: AuthGuardDeps, val matcher: BackfillMatcher?, val clock: AronClock = AronClock.SYSTEM)

/**
 * "Same memo as backfill" (docs/requests/backend-core-backfill-vs-phone-memo-review.md, T1): the phone later uploaded a memo that was already keyed
 * from paper (held `memo_no_duplicate`). Backend-core's `matchBackfill` compares, releases and re-points inside this transaction (409
 * `ERR_CONFLICT` with one error per difference, 409 `ERR_REQUEST_STATE` when the item is not open or not matchable, 404 unknown); this endpoint
 * owns the role gate (SUPPORT, ADMIN, SUPERADMIN: no money changes, so no second person), the resolver's reach on the item's route, and the
 * audit row, written in the same transaction as the match. A replay of a done match is 409 `ERR_REQUEST_STATE` (the item is no longer open).
 */
fun Route.backfillMatchRoutes(d: BackfillMatchDeps) {
    authenticated(d.guard) {
        post("/admin/quarantine/{quarantine_id}/match-backfill") { call.respond(match(call, d)) }
    }
}

private val ROLES = setOf(Role.SUPPORT, Role.ADMIN, Role.SUPERADMIN)

private suspend fun match(call: io.ktor.server.application.ApplicationCall, d: BackfillMatchDeps): BackfillMatchOut {
    val p = call.principal
    if (p.isPhone || p.role !in ROLES) throw AdminSupport.forbidden("matching a phone memo to a paper backfill is for SUPPORT, ADMIN or SUPERADMIN on the web")
    val matcher = d.matcher ?: throw ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "ingest is not available")
    val id = call.parameters["quarantine_id"]?.toLongOrNull()?.takeIf { it >= 1 } ?: AdminSupport.bad("path.quarantine_id")
    val req = call.receiveStrict(BackfillMatchIn.serializer())
    AdminSupport.noNul("body.reason", req.reason)
    val reason = AdminSupport.reason(req.reason, "body.reason")
    val reach = AdminSupport.reach(d.reach, call, d.clock)
    return d.db.jdbi.inTransaction<BackfillMatchOut, Exception> { h ->
        // Reach on the item's route, from the database. An unknown item and one outside the reach answer alike (404).
        val item = h.createQuery("SELECT route_id, business_date FROM app.sync_quarantine WHERE id = :i").bind("i", id)
            .map { rs, _ -> (rs.getObject(1) as Long?) to rs.getObject(2, java.time.LocalDate::class.java) }.findOne().orElse(null)
            ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "quarantine item $id")
        val routeId = item.first
        val zone = routeId?.let { r -> h.createQuery("SELECT zone_id FROM app.route WHERE id = :r").bind("r", r).mapTo(Long::class.java).findOne().orElse(null) }
        if (routeId == null || zone == null || !reach.coversRoute(routeId, zone)) throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "quarantine item $id")
        val phoneMemo = h.createQuery("SELECT client_uuid::text FROM app.sync_quarantine WHERE id = :i").bind("i", id).mapTo(String::class.java).one()
        val out = matcher.match(id, p.userId, reason)
        AuditWriter.write(h, p, "memo", out.paper_memo_uuid, "backfill_matched", buildJsonObject {
            put("phone_memo_uuid", phoneMemo); put("quarantine_id", id)
        }, buildJsonObject {
            put("paper_memo_uuid", out.paper_memo_uuid); put("phone_memo_uuid", phoneMemo)
            put("voided_paper_visit_uuid", out.voided_paper_visit_uuid?.let { JsonPrimitive(it) } ?: JsonNull)
            put("phone_visit_uuid", out.phone_visit_uuid?.let { JsonPrimitive(it) } ?: JsonNull)
            put("visit_reason", "superseded_by_device_visit")
            put("released", JsonArray(out.released.map { JsonPrimitive(it) })); put("kept_open", JsonArray(out.kept_open.map { JsonPrimitive(it) }))
        }, reason, call.requestId)
        out
    }
}
