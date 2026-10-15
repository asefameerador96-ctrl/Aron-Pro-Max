package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.config.AuditWriter
import com.aktcl.aron.backend.masterdata.AdminSupport.bad
import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.receiveStrict
import com.aktcl.aron.backend.platform.requestId
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/** Contract `DuesAdjustmentWrite`, `DuesAdjustment`, `DuesAdjustmentPage`, `DecisionRequest`. */
@Serializable
data class DuesAdjustmentIn(
    val client_uuid: String, val outlet_id: Long, val memo_client_uuid: String? = null, val kind: String, val amount_mtk: Long, val reason: String,
)

@Serializable
data class DuesAdjustmentDto(
    val adjustment_id: Long, val outlet_id: Long, val memo_client_uuid: String?, val kind: String, val amount_mtk: Long, val reason: String,
    val status: String, val created_by_user_id: Long, val created_at: String, val decided_by_user_id: Long?, val decided_at: String?,
)

@Serializable
data class DuesAdjustmentPageDto(val items: List<DuesAdjustmentDto>, val next_cursor: String?)

@Serializable
data class DuesDecisionIn(val decision: String, val note: String? = null)

class DuesAdjustmentDeps(val db: Database, val reach: ReachResolver, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM)

/**
 * F-ADM-036 (T1, money): a correction or write-off of an outlet's dues is proposed pending by ADMIN or SUPERADMIN (the maker) and
 * decided once by a SUPERADMIN who is not the maker (docs/24 s8.5 and s8.6; `ERR_SEPARATION_OF_DUTIES`). The outlet must be in the caller's
 * reach (the outlet's zone; read from the database, never from the client). Approval appends one `app.due_ledger` row (kind `adjustment`,
 * source = the proposal's client_uuid, business date = the Dhaka date of the decision, user = the checker) in the same transaction as the
 * status change, so the status and the balance cannot disagree; the unique `(source_client_uuid, entry_kind)` makes it once-only.
 * A write-off may not exceed the outlet's balance at the moment of approval.
 */
fun Route.duesAdjustmentRoutes(d: DuesAdjustmentDeps) {
    authenticated(d.guard) {
        get("/admin/dues-adjustments") { call.respond(listAdjustments(call, d)) }
        post("/admin/dues-adjustments") {
            val (dto, created) = createAdjustment(call, d)
            call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, dto)
        }
        post("/admin/dues-adjustments/{adjustment_id}/decision") { call.respond(decideAdjustment(call, d)) }
    }
}

private val MAKERS = setOf(Role.ADMIN, Role.SUPERADMIN)
private val CHECKERS = setOf(Role.SUPERADMIN)
private const val COLS = "a.id, a.outlet_id, a.memo_client_uuid, a.kind, a.amount_mtk, a.reason, a.status, a.created_by, a.created_at, a.decided_by, a.decided_at"

private fun map(rs: ResultSet) = DuesAdjustmentDto(
    rs.getLong("id"), rs.getLong("outlet_id"), rs.getObject("memo_client_uuid")?.toString(), rs.getString("kind"), rs.getLong("amount_mtk"), rs.getString("reason"),
    rs.getString("status"), rs.getLong("created_by"), rs.getObject("created_at", OffsetDateTime::class.java).toInstant().wire(),
    rs.getObject("decided_by") as Long?, rs.getObject("decided_at", OffsetDateTime::class.java)?.toInstant()?.wire(),
)

private fun outletZone(h: Handle, outletId: Long): Long? =
    h.createQuery("SELECT zone_id FROM app.outlet WHERE id = :o").bind("o", outletId).mapTo(Long::class.java).findOne().orElse(null)

private fun lock(h: Handle, key: String) {
    h.createQuery("SELECT pg_advisory_xact_lock(hashtextextended(:k, 0))").bind("k", key).mapTo(String::class.java).findOne()
}

private fun listAdjustments(call: ApplicationCall, d: DuesAdjustmentDeps): DuesAdjustmentPageDto {
    val p = call.principal
    if (p.role !in MAKERS) throw AdminSupport.forbidden("dues adjustments are available to ADMIN and SUPERADMIN")
    val limit = AdminSupport.limit(call)
    val outlet = AdminSupport.queryId(call, "outlet_id")
    val status = call.request.queryParameters["status"]?.also { if (it !in setOf("pending", "approved", "rejected")) bad("query.status") }
    val cursor = AdminSupport.decodeCursor(call, 1)?.let { (a) -> a.toLongOrNull()?.takeIf { it >= 1 } ?: bad("query.cursor") }
    val reach = AdminSupport.reach(d.reach, call, d.clock)
    return d.db.jdbi.withHandle<DuesAdjustmentPageDto, Exception> { h ->
        val where = mutableListOf<String>()
        if (!reach.national) where += "o.zone_id IN (<zones>)"
        if (outlet != null) where += "a.outlet_id = :outlet"
        if (status != null) where += "a.status = :status"
        if (cursor != null) where += "a.id < :c"
        val q = h.createQuery("SELECT $COLS FROM app.dues_adjustment a JOIN app.outlet o ON o.id = a.outlet_id ${if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")} ORDER BY a.id DESC LIMIT :lim")
            .bind("lim", limit + 1)
        if (!reach.national) q.bindList("zones", reach.zoneIds.toList().ifEmpty { listOf(0L) })
        if (outlet != null) q.bind("outlet", outlet)
        if (status != null) q.bind("status", status)
        if (cursor != null) q.bind("c", cursor)
        val rows = q.map { rs, _ -> map(rs) }.list()
        val page = rows.take(limit)
        DuesAdjustmentPageDto(page, if (rows.size > limit) AdminSupport.encodeCursor(page.last().adjustment_id) else null)
    }
}

private suspend fun createAdjustment(call: ApplicationCall, d: DuesAdjustmentDeps): Pair<DuesAdjustmentDto, Boolean> {
    val p = call.principal
    if (p.role !in MAKERS) throw AdminSupport.forbidden("only ADMIN and SUPERADMIN propose dues adjustments")
    val req = call.receiveStrict(DuesAdjustmentIn.serializer())
    val uuid = AdminSupport.uuid(req.client_uuid, "body.client_uuid")
    val memo = req.memo_client_uuid?.let { AdminSupport.uuid(it, "body.memo_client_uuid") }
    if (req.outlet_id < 1) bad("body.outlet_id")
    if (req.kind !in setOf("correction", "write_off")) bad("body.kind")
    if (req.amount_mtk == 0L) bad("body.amount_mtk", "zero")
    if (req.kind == "write_off" && req.amount_mtk > 0) bad("body.amount_mtk", "write_off_must_be_negative")
    AdminSupport.noNul("body.reason", req.reason)
    val reason = AdminSupport.reason(req.reason, "body.reason")
    val reach = AdminSupport.reach(d.reach, call, d.clock)
    return d.db.jdbi.inTransaction<Pair<DuesAdjustmentDto, Boolean>, Exception> { h ->
        // Unknown and out-of-reach outlets answer alike: the server never confirms an outlet outside the caller's reach.
        val zone = outletZone(h, req.outlet_id)
        if (zone == null || !reach.coversZone(zone)) throw AdminSupport.outOfScope("the outlet does not exist or is outside your reach")
        if (memo != null) {
            val ok = h.createQuery("SELECT EXISTS (SELECT 1 FROM app.memo WHERE client_uuid = :m AND outlet_id = :o)").bind("m", memo).bind("o", req.outlet_id).mapTo(Boolean::class.java).one()
            if (!ok) bad("body.memo_client_uuid", "not_an_outlet_memo")
        }
        lock(h, "dues-adj-create:$uuid")
        val inserted = h.createQuery(
            "INSERT INTO app.dues_adjustment (client_uuid, outlet_id, memo_client_uuid, kind, amount_mtk, reason, created_by) VALUES (:u, :o, CAST(:m AS uuid), :k, :a, :r, :by) " +
                "ON CONFLICT (client_uuid) DO NOTHING RETURNING id",
        ).bind("u", uuid).bind("o", req.outlet_id).bind("m", memo?.toString()).bind("k", req.kind).bind("a", req.amount_mtk).bind("r", reason).bind("by", p.userId)
            .mapTo(Long::class.java).findOne().orElse(null)
        if (inserted != null) {
            AuditWriter.write(h, p, "dues_adjustment", inserted.toString(), "create", null, buildJsonObject {
                put("outlet_id", req.outlet_id); put("kind", req.kind); put("amount_mtk", req.amount_mtk); put("client_uuid", uuid.toString())
            }, reason, call.requestId)
            return@inTransaction h.createQuery("SELECT $COLS FROM app.dues_adjustment a WHERE a.id = :i").bind("i", inserted).map { rs, _ -> map(rs) }.one() to true
        }
        // A replay of the same proposal by the same maker answers the stored row; the same uuid with other content is a conflict.
        val row = h.createQuery("SELECT $COLS FROM app.dues_adjustment a WHERE a.client_uuid = :u").bind("u", uuid).map { rs, _ -> map(rs) }.one()
        val same = row.created_by_user_id == p.userId && row.outlet_id == req.outlet_id && row.kind == req.kind && row.amount_mtk == req.amount_mtk &&
            row.reason == reason && row.memo_client_uuid == memo?.toString()
        if (!same) throw ApiProblem(ProblemCode.ERR_CONFLICT, "client_uuid was already used for a different proposal")
        row to false
    }
}

private suspend fun decideAdjustment(call: ApplicationCall, d: DuesAdjustmentDeps): DuesAdjustmentDto {
    val p = call.principal
    if (p.role !in CHECKERS) throw AdminSupport.forbidden("only SUPERADMIN decides dues adjustments")
    val id = call.parameters["adjustment_id"]?.toLongOrNull()?.takeIf { it >= 1 } ?: bad("path.adjustment_id")
    val req = call.receiveStrict(DuesDecisionIn.serializer())
    if (req.decision !in setOf("approve", "reject")) bad("body.decision")
    AdminSupport.noNul("body.note", req.note)
    val note = req.note?.trim()?.takeIf { it.isNotEmpty() }
    if (note != null && note.length > 500) bad("body.note", "length")
    val reach = AdminSupport.reach(d.reach, call, d.clock)
    return d.db.jdbi.inTransaction<DuesAdjustmentDto, Exception> { h ->
        val cur = h.createQuery("SELECT $COLS, a.client_uuid, a.decision_note FROM app.dues_adjustment a WHERE a.id = :i").bind("i", id).map { rs, _ -> map(rs) to rs.getObject("client_uuid", UUID::class.java) }.findOne().orElse(null)
            ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "dues adjustment $id")
        val zone = outletZone(h, cur.first.outlet_id)
        if (zone == null || !reach.coversZone(zone)) throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "dues adjustment $id")
        // One decision at a time per outlet: balance check, status change and ledger row are one serial step.
        lock(h, "dues-outlet:${cur.first.outlet_id}")
        val row = h.createQuery("SELECT $COLS FROM app.dues_adjustment a WHERE a.id = :i FOR UPDATE").bind("i", id).map { rs, _ -> map(rs) }.one()
        if (row.created_by_user_id == p.userId) throw ApiProblem(ProblemCode.ERR_SEPARATION_OF_DUTIES, "the checker of a dues adjustment is not its maker")
        if (row.status != "pending") throw ApiProblem(ProblemCode.ERR_CONFLICT, "the adjustment is already ${row.status}")
        val approve = req.decision == "approve"
        if (approve && row.kind == "write_off") {
            val balance = h.createQuery("SELECT COALESCE(SUM(amount_mtk), 0) FROM app.due_ledger WHERE outlet_id = :o").bind("o", row.outlet_id).mapTo(Long::class.java).one()
            if (-row.amount_mtk > balance) throw ApiProblem(ProblemCode.ERR_CONFLICT, "the write-off is larger than the outlet's open balance")
        }
        h.createUpdate("UPDATE app.dues_adjustment SET status = :s, decided_by = :by, decided_at = now(), decision_note = :n WHERE id = :i")
            .bind("s", if (approve) "approved" else "rejected").bind("by", p.userId).bind("n", note).bind("i", id).execute()
        if (approve) {
            h.createUpdate(
                "INSERT INTO app.due_ledger (outlet_id, business_date, entry_kind, amount_mtk, memo_client_uuid, source_client_uuid, user_id, note) " +
                    "VALUES (:o, app.dhaka_date(now()), 'adjustment', :a, CAST(:m AS uuid), :src, :by, :n)",
            ).bind("o", row.outlet_id).bind("a", row.amount_mtk).bind("m", row.memo_client_uuid).bind("src", cur.second).bind("by", p.userId)
                .bind("n", "${row.kind}: ${row.reason}").execute()
        }
        AuditWriter.write(h, p, "dues_adjustment", id.toString(), req.decision, buildJsonObject { put("status", "pending") }, buildJsonObject {
            put("status", if (approve) "approved" else "rejected"); put("outlet_id", row.outlet_id); put("kind", row.kind); put("amount_mtk", row.amount_mtk)
        }, note ?: row.reason, call.requestId)
        h.createQuery("SELECT $COLS FROM app.dues_adjustment a WHERE a.id = :i").bind("i", id).map { rs, _ -> map(rs) }.one()
    }
}
