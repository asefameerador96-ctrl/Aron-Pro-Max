package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.config.AuditWriter
import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.IngestRecord
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.RecordHandler
import com.aktcl.aron.backend.platform.RecordRefusal
import com.aktcl.aron.backend.platform.RequestJson
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.requestId
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.RecordOutcomeCode
import com.aktcl.aron.contract.Role
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Serializable
data class DataVoidIn(val client_uuid: String, val route_id: Long, val business_date: String, val scope: String, val reason: String)

@Serializable
data class DataVoidOut(val route_id: Long, val business_date: String, val scope: String, val voided_at: String, val tombstoned_records: Int, val pending_rows_reported: Int?)

class DataVoidDeps(val db: Database, val reach: ReachResolver, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM)

/** TSO (own scope) and the ops admins (docs/21 matrix `day.void`). */
private val VOID_ROLES = setOf(Role.TSO, Role.ADMIN, Role.SUPERADMIN)

/**
 * Record tables a data void tombstones: every table that carries `route_id`, `business_date`, `client_uuid` and `voided_at`
 * (docs/24 s4.9 rule 5). Those of the web back office are listed in [WEB_TABLES] and are voided when their migration exists.
 */
internal val APP_VOID_TABLES = listOf(
    "attendance_event", "route_day_event", "day_exception", "stock_movement", "visit", "visit_skip", "memo", "memo_line", "memo_discount",
    "qc_entry_line", "print_event", "memo_void", "due_collection", "survey_response", "distribution_check", "distribution_check_line",
    "call_assessment", "call_assessment_answer", "outlet_change_request", "content_view", "redemption", "redemption_line", "gift_photo",
    "price_compliance_check", "sale_abort",
)

/** Back-office entry tables (docs/24 s11); absent until the db lane ships them (docs/requests/backend-admin-web-entry-tables.md). */
internal val WEB_TABLES = listOf("web_entry_route_day", "web_entry_line", "qc_summary_entry")

/** `POST /v1/admin/data-void` (F-API-048, F-ADM-058). */
fun Route.dataVoidRoutes(d: DataVoidDeps) {
    authenticated(d.guard) { post("/admin/data-void") { call.respond(voidDay(call, d)) } }
}

private fun bad(pointer: String, code: String = "invalid_value"): Nothing = AdminSupport.bad(pointer, code)

private suspend fun voidDay(call: ApplicationCall, d: DataVoidDeps): DataVoidOut {
    val p = call.principal
    if (p.role !in VOID_ROLES) throw AdminSupport.forbidden("a data void is not available to this role")
    val req = try { RequestJson.decodeFromString<DataVoidIn>(call.receiveText()) } catch (e: kotlinx.serialization.SerializationException) {
        bad("body", "invalid_body")
    }
    val id = AdminSupport.uuid(req.client_uuid, "body.client_uuid")
    if (req.route_id < 1) bad("body.route_id")
    val date = AdminSupport.date(req.business_date, "body.business_date")
    if (req.scope !in setOf("web_entry", "app_memos", "all")) bad("body.scope")
    // docs/24 s8.5 and docs/15 F-ADM-058: voiding app memos needs a role above TSO; a TSO may void web entry only (docs/21 day.void, own scope).
    if (req.scope != "web_entry" && p.role == Role.TSO) throw AdminSupport.forbidden("voiding app memos needs an administrator")
    AdminSupport.noNul("body.reason", req.reason)
    val reason = req.reason.trim()
    if (req.reason.length > 500 || reason.length < 10) bad("body.reason", "length")
    if (date.isAfter(AdminSupport.today(d.clock))) bad("body.business_date", "future")
    val reach = AdminSupport.reach(d.reach, call, d.clock)
    val now = AdminSupport.utc(d.clock)
    return d.db.jdbi.inTransaction<DataVoidOut, Exception> { h ->
        // One void per client_uuid, and no ingest of this route-day in flight while the barrier is written (shared lock in the barrier handler).
        h.createQuery("SELECT pg_advisory_xact_lock(hashtextextended(:k, 0))").bind("k", "void:" + req.client_uuid).mapTo(String::class.java).findOne()
        h.createQuery("SELECT pg_advisory_xact_lock(hashtextextended(:k, 0))").bind("k", "voidday:${req.route_id}:$date").mapTo(String::class.java).findOne()
        // One void per route at a time: the route row is the lock.
        val zone = h.createQuery("SELECT zone_id FROM app.route WHERE id = :r FOR UPDATE").bind("r", req.route_id).mapTo(Long::class.java).findOne().orElse(null)
        if (zone == null || !reach.coversRoute(req.route_id, zone)) throw AdminSupport.outOfScope("route outside your reach")
        val pending = pendingRows(h, req.route_id)
        val prior = h.createQuery("SELECT route_id, business_date, barrier_at, affected FROM app.route_day_void_barrier WHERE client_uuid = :u").bind("u", id)
            .map { rs, _ -> Triple(rs.getLong(1), rs.getObject(2, java.time.LocalDate::class.java), rs.getObject(3, OffsetDateTime::class.java)) to rs.getString(4) }.findOne().orElse(null)
        if (prior != null) {
            // A replay returns the first answer; the same uuid for another day is a conflict.
            val (key, affected) = prior
            if (key.first != req.route_id || key.second != date) throw ApiProblem(ProblemCode.ERR_CONFLICT, "client_uuid already voids a different route-day")
            val o = kotlinx.serialization.json.Json.parseToJsonElement(affected) as kotlinx.serialization.json.JsonObject
            if (o["scope"]?.let { (it as JsonPrimitive).content } != req.scope) throw ApiProblem(ProblemCode.ERR_CONFLICT, "client_uuid already voided another scope")
            return@inTransaction DataVoidOut(req.route_id, date.toString(), req.scope, key.third.toInstant().wire(), (o["total"] as JsonPrimitive).content.toInt(), pending)
        }
        val finalSubmitted = h.createQuery("SELECT count(*) FROM app.final_submit WHERE zone_id = :z AND business_date = :d AND reopened_at IS NULL").bind("z", zone).bind("d", date).mapTo(Int::class.java).one()
        if (finalSubmitted > 0) throw ApiProblem(ProblemCode.ERR_DAY_ALREADY_FINAL_SUBMITTED, "the zone-day is Final Submitted; a data void is not allowed")
        val tables = (if (req.scope != "web_entry") APP_VOID_TABLES else emptyList()) + (if (req.scope != "app_memos") WEB_TABLES.filter { exists(h, it) } else emptyList())
        val counts = linkedMapOf<String, Int>()
        val voidedUuids = mutableListOf<String>()
        val voidedMemos = mutableListOf<String>()
        for (t in tables) {
            val uuids = h.createQuery("UPDATE app.$t SET voided_at = :now WHERE route_id = :r AND business_date = :d AND voided_at IS NULL RETURNING client_uuid").bind("now", now).bind("r", req.route_id).bind("d", date)
                .map { rs, _ -> rs.getString(1) }.list()
            if (uuids.isNotEmpty()) counts[t] = uuids.size
            voidedUuids += uuids
            if (t == "memo") voidedMemos += uuids
        }
        // geo_fix has no client_uuid of its own: it follows its route-day.
        val fixes = h.createUpdate("UPDATE app.geo_fix SET voided_at = :now WHERE route_id = :r AND business_date = :d AND voided_at IS NULL").bind("now", now).bind("r", req.route_id).bind("d", date).execute()
        if (fixes > 0) counts["geo_fix"] = fixes
        if (voidedUuids.isNotEmpty()) {
            h.createUpdate("UPDATE app.ingest_registry SET status = 'voided' WHERE client_uuid IN (<u>) AND status = 'accepted'").bindList("u", voidedUuids.map { java.util.UUID.fromString(it) }).execute()
            reverseDues(h, voidedUuids, req.client_uuid)
        }
        if (voidedMemos.isNotEmpty()) {
            // The projector takes a voided memo out of the day aggregates (event catalogue: memo.voided v1).
            h.createUpdate(
                "INSERT INTO app.domain_event (event_type, payload_version, aggregate_type, aggregate_id, business_date, payload, source_client_uuid) " +
                    "SELECT 'memo.voided', 1, 'memo', m.client_uuid::text, m.business_date, jsonb_build_object('memo_uuid', m.client_uuid, 'route_id', m.route_id, 'voided_at', CAST(:at AS timestamptz), 'reason_code', 'data_void'), CAST(:src AS uuid) " +
                    "FROM app.memo m WHERE m.client_uuid IN (<u>)",
            ).bind("at", now).bind("src", req.client_uuid).bindList("u", voidedMemos.map { java.util.UUID.fromString(it) }).execute()
        }
        val total = counts.values.sum()
        val affected = buildJsonObject { put("scope", req.scope); put("total", total); counts.forEach { (k, v) -> put(k, v) } }
        h.createUpdate("INSERT INTO app.route_day_void_barrier (client_uuid, route_id, business_date, barrier_at, voided_by, reason, affected) VALUES (:u, :r, :d, :at, :by, :why, CAST(:a AS jsonb))")
            .bind("u", id).bind("r", req.route_id).bind("d", date).bind("at", now).bind("by", p.userId).bind("why", reason).bind("a", affected.toString()).execute()
        AuditWriter.write(h, p, "data_void", req.client_uuid, "void", null, buildJsonObject { put("route_id", req.route_id); put("business_date", date.toString()); put("scope", req.scope); put("tombstoned", total) }, reason, call.requestId)
        DataVoidOut(req.route_id, date.toString(), req.scope, now.toInstant().wire(), total, pending)
    }
}

private fun exists(h: Handle, table: String) = h.createQuery("SELECT to_regclass(:t) IS NOT NULL").bind("t", "app.$table").mapTo(Boolean::class.java).one()

/** Last X-Pending-Rows of the phones bound to the route's assigned users (the confirmation dialog shows it). */
private fun pendingRows(h: Handle, routeId: Long): Int? = h.createQuery(
    "SELECT d.pending_rows_reported FROM app.route_assignment a JOIN app.device_binding b ON b.user_id = a.user_id AND b.status = 'active' JOIN app.device d ON d.id = b.device_id " +
        "WHERE a.route_id = :r AND a.ended_at IS NULL AND d.pending_rows_reported IS NOT NULL ORDER BY d.last_contact_at DESC NULLS LAST LIMIT 1",
).bind("r", routeId).mapTo(Int::class.java).findOne().orElse(null)

/**
 * The dues ledger is append-only. A tombstoned memo is taken off it by ONE `adjustment` (source = the memo) that nets every
 * ledger row of that memo (its due, collections against it, an earlier `memo_void` or supersession), so a memo already
 * voided elsewhere nets to zero and posts nothing. A tombstoned collection whose memo is not tombstoned in this void is
 * reversed on its own. `memo_void` and supersession records are not reversed: the memo's status was set by them and stays.
 */
private fun reverseDues(h: Handle, voided: List<String>, voidUuid: String) {
    val u = voided.map { java.util.UUID.fromString(it) }
    h.createUpdate(
        """
        INSERT INTO app.due_ledger (outlet_id, business_date, entry_kind, amount_mtk, memo_client_uuid, memo_no, source_client_uuid, note)
        SELECT l.outlet_id, min(l.business_date), 'adjustment', -sum(l.amount_mtk), l.memo_client_uuid, max(l.memo_no), l.memo_client_uuid, 'data void ' || CAST(:v AS text)
        FROM app.due_ledger l WHERE l.memo_client_uuid IN (<u>) AND l.entry_kind <> 'adjustment'
        GROUP BY l.outlet_id, l.memo_client_uuid HAVING sum(l.amount_mtk) <> 0
        ON CONFLICT (source_client_uuid, entry_kind) DO NOTHING
        """.trimIndent(),
    ).bind("v", voidUuid).bindList("u", u).execute()
    h.createUpdate(
        """
        INSERT INTO app.due_ledger (outlet_id, business_date, entry_kind, amount_mtk, memo_client_uuid, memo_no, source_client_uuid, note)
        SELECT l.outlet_id, l.business_date, 'adjustment', -l.amount_mtk, l.memo_client_uuid, l.memo_no, l.source_client_uuid, 'data void ' || CAST(:v AS text)
        FROM app.due_ledger l WHERE l.source_client_uuid IN (<u>) AND l.entry_kind = 'collection'
          AND (l.memo_client_uuid IS NULL OR l.memo_client_uuid NOT IN (<u>))
        ON CONFLICT (source_client_uuid, entry_kind) DO NOTHING
        """.trimIndent(),
    ).bind("v", voidUuid).bindList("u", u).execute()
}

/**
 * Late rows: a record captured before an admin data void of its route-day is refused `voided_by_admin`; one captured after
 * the barrier is accepted (D24-45). The phone is told once and drops it (final, not retryable).
 */
class DataVoidBarrierHandler(override val types: Set<String>) : RecordHandler {
    override fun checkEarly(h: Handle, rec: IngestRecord): RecordRefusal? {
        val route = (rec.envelope["route_id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return null
        val captured = (rec.envelope["captured_at"] as? JsonPrimitive)?.content?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        // Shared lock until the record's transaction ends: a void of this route-day waits for in-flight rows, and rows after it see the barrier.
        h.createQuery("SELECT pg_advisory_xact_lock_shared(hashtextextended(:k, 0))").bind("k", "voidday:$route:${rec.businessDate}").mapTo(String::class.java).findOne()
        val hit = h.createQuery("SELECT EXISTS (SELECT 1 FROM app.route_day_void_barrier WHERE route_id = :r AND business_date = :d AND :c < barrier_at AND affected->>'scope' <> 'web_entry')")
            .bind("r", route).bind("d", rec.businessDate).bind("c", OffsetDateTime.ofInstant(captured, ZoneOffset.UTC)).mapTo(Boolean::class.java).one()
        return if (hit) RecordRefusal(RecordOutcomeCode.VOIDED_BY_ADMIN, "route-day voided by an administrator after this row was captured") else null
    }
}
