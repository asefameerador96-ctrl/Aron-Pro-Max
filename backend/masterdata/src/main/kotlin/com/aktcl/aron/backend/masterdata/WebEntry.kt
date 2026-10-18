package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.config.AuditWriter
import com.aktcl.aron.backend.masterdata.AdminSupport.bad
import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.receiveStrict
import com.aktcl.aron.backend.platform.requestId
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@Serializable
data class WebEntryLineDto(
    val sku_id: Long, val issue_qty_base: Long, val return_qty_base: Long, val memo_count: Int,
    val class_qty_base: Map<String, Long> = emptyMap(), val sale_qty_base: Long? = null,
)

@Serializable
data class WebEntryRouteDayIn(
    val client_uuid: String, val route_id: Long, val business_date: String, val lines: List<WebEntryLineDto>, val successful_calls: Int, val change_reason: String? = null,
)

@Serializable
data class WebEntryRouteDayOut(
    val route_id: Long, val business_date: String, val lines: List<WebEntryLineDto>, val successful_calls: Int, val target_outlets: Int, val app_overlap: Boolean, val source: String = "web_entry",
    val client_uuid: String? = null, val saved_at: String? = null, val saved_by_user_id: Long? = null,
)

@Serializable
data class QcRowIn(val sku_id: Long, val fault_type_code: String, val qty_base: Long)

@Serializable
data class QcEntryIn(
    val client_uuid: String, val source: String, val business_date: String, val zone_id: Long, val rows: List<QcRowIn>, val route_id: Long? = null, val reason: String? = null,
)

@Serializable
data class QcEntryOut(val client_uuid: String, val stored_rows: Int, val replayed: Boolean)

class WebEntryDeps(val db: Database, val reach: ReachResolver, val config: ServerConfig, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM)

/** docs/24 s8.5: TSO (own zones), ADMIN and SUPERADMIN write web entry; the warehouse QC needs an administrator. */
private val ENTRY_ROLES = setOf(Role.TSO, Role.ADMIN, Role.SUPERADMIN)
private val WAREHOUSE_ROLES = setOf(Role.ADMIN, Role.SUPERADMIN)

/** `GET`/`POST /v1/web-entry/route-day` (F-API-050) and `POST /v1/web-entry/qc` (F-API-052). The Astha outlet-SKU entry is trimmed (docs/27). */
fun Route.webEntryRoutes(d: WebEntryDeps) {
    authenticated(d.guard) {
        get("/web-entry/route-day") { call.respond(getRouteDay(call, d)) }
        post("/web-entry/route-day") { call.respond(saveRouteDay(call, d)) }
        post("/web-entry/qc") { call.respond(saveQc(call, d)) }
    }
}

private fun cfgInt(d: WebEntryDeps, key: String, default: Int) = runCatching { d.config.int(key) }.getOrDefault(default)
private fun cfgBool(d: WebEntryDeps, key: String, default: Boolean) = runCatching { d.config.bool(key) }.getOrDefault(default)

/** Zone of a route; null when the route does not exist. */
private fun routeZone(h: Handle, routeId: Long): Long? = h.createQuery("SELECT zone_id FROM app.route WHERE id = :r").bind("r", routeId).mapTo(Long::class.java).findOne().orElse(null)

private fun requireRouteReach(d: WebEntryDeps, call: ApplicationCall, h: Handle, routeId: Long): Long {
    val zone = routeZone(h, routeId)
    if (zone == null || !AdminSupport.reach(d.reach, call, d.clock).coversRoute(routeId, zone)) throw AdminSupport.outOfScope("route outside your reach")
    return zone
}

private fun finalSubmitted(h: Handle, zone: Long, date: LocalDate): Boolean =
    h.createQuery("SELECT count(*) FROM app.final_submit WHERE zone_id = :z AND business_date = :d AND reopened_at IS NULL").bind("z", zone).bind("d", date).mapTo(Int::class.java).one() > 0

/** Back-dating: inside `cfg.web.entry_backdate_days` free, outside it only under an active unlock of the route or its zone (F-API-049). */
private fun requireWindow(d: WebEntryDeps, h: Handle, routeId: Long?, zone: Long, date: LocalDate, now: OffsetDateTime) {
    val today = AdminSupport.today(d.clock)
    if (date.isAfter(today)) bad("body.business_date", "future")
    if (!date.isBefore(today.minusDays(cfgInt(d, "cfg.web.entry_backdate_days", 0).toLong()))) return
    val unlocked = h.createQuery(
        "SELECT count(*) FROM app.entry_unlock WHERE expired_at IS NULL AND expires_at > :now AND from_date <= :d AND to_date >= :d AND ((scope_type = 'zone' AND scope_id = :z) OR (scope_type = 'route' AND scope_id = :r))",
    ).bind("now", now).bind("d", date).bind("z", zone).bind("r", routeId ?: 0L).mapTo(Int::class.java).one() > 0
    if (!unlocked) throw ApiProblem(ProblemCode.ERR_ENTRY_WINDOW_CLOSED, "$date is outside the web entry window; ask for an entry unlock")
}

private fun targetOutlets(h: Handle, routeId: Long, date: LocalDate): Int =
    h.createQuery("SELECT target_outlets FROM app.route_day WHERE route_id = :r AND business_date = :d AND target_outlets IS NOT NULL").bind("r", routeId).bind("d", date).mapTo(Int::class.java).findOne().orElse(null)
        ?: h.createQuery("SELECT count(*) FROM app.outlet WHERE route_id = :r AND status = 'active'").bind("r", routeId).mapTo(Int::class.java).one()

private fun appOverlap(h: Handle, routeId: Long, date: LocalDate): Boolean =
    h.createQuery("SELECT count(*) FROM app.memo WHERE route_id = :r AND business_date = :d AND voided_at IS NULL").bind("r", routeId).bind("d", date).mapTo(Int::class.java).one() > 0

private fun linesOf(h: Handle, entry: UUID, includeVoided: Boolean = false): List<WebEntryLineDto> =
    h.createQuery("SELECT sku_id, issue_qty_base, return_qty_base, memo_count, class_qty_base::text AS cls FROM app.web_entry_line WHERE entry_client_uuid = :e ${if (includeVoided) "" else "AND voided_at IS NULL"} ORDER BY sku_id").bind("e", entry)
        .map { rs, _ ->
            val issue = rs.getLong("issue_qty_base"); val ret = rs.getLong("return_qty_base")
            val cls = (kotlinx.serialization.json.Json.parseToJsonElement(rs.getString("cls")) as kotlinx.serialization.json.JsonObject).mapValues { (it.value as kotlinx.serialization.json.JsonPrimitive).content.toLong() }
            WebEntryLineDto(rs.getLong("sku_id"), issue, ret, rs.getInt("memo_count"), cls, issue - ret)
        }.list()

private fun liveEntry(h: Handle, routeId: Long, date: LocalDate): WebEntryRouteDayOut? =
    h.createQuery("SELECT client_uuid, successful_calls, target_outlets, app_overlap, entered_by, entered_at FROM app.web_entry_route_day WHERE route_id = :r AND business_date = :d AND replaced_at IS NULL AND voided_at IS NULL")
        .bind("r", routeId).bind("d", date).map { rs, _ ->
            val cu = rs.getObject("client_uuid", UUID::class.java)
            WebEntryRouteDayOut(routeId, date.toString(), linesOf(h, cu), rs.getInt("successful_calls"), rs.getInt("target_outlets"), rs.getBoolean("app_overlap"),
                client_uuid = cu.toString(), saved_at = rs.getObject("entered_at", OffsetDateTime::class.java).toInstant().wire(), saved_by_user_id = rs.getLong("entered_by"))
        }.findOne().orElse(null)

private fun getRouteDay(call: ApplicationCall, d: WebEntryDeps): WebEntryRouteDayOut {
    if (call.principal.role !in ENTRY_ROLES) throw AdminSupport.forbidden("web entry is not available to this role")
    val routeId = AdminSupport.queryId(call, "route_id") ?: bad("query.route_id", "required")
    val date = AdminSupport.queryDate(call, "business_date") ?: bad("query.business_date", "required")
    return d.db.jdbi.withHandle<WebEntryRouteDayOut, Exception> { h ->
        requireRouteReach(d, call, h, routeId)
        liveEntry(h, routeId, date) ?: WebEntryRouteDayOut(routeId, date.toString(), emptyList(), 0, targetOutlets(h, routeId, date), appOverlap(h, routeId, date))
    }
}

private fun validateLines(h: Handle, lines: List<WebEntryLineDto>) {
    if (lines.size > 500) bad("body.lines", "length")
    val skus = HashSet<Long>()
    lines.forEachIndexed { i, l ->
        val at = "body.lines[$i]"
        if (l.sku_id < 1 || !skus.add(l.sku_id)) bad("$at.sku_id", if (l.sku_id in skus) "duplicate" else "invalid_value")
        if (l.issue_qty_base !in 0..10_000_000 || l.return_qty_base !in 0..10_000_000) bad("$at.issue_qty_base", "out_of_range")
        if (l.return_qty_base > l.issue_qty_base) bad("$at.return_qty_base", "above_issue")
        if (l.memo_count !in 0..100_000) bad("$at.memo_count", "out_of_range")
        if (l.class_qty_base.keys.any { !Regex("^[1-9][0-9]{0,17}$").matches(it) } || l.class_qty_base.values.any { it < 0 || it > 10_000_000 } || l.class_qty_base.values.sum() > l.issue_qty_base - l.return_qty_base) bad("$at.class_qty_base")
    }
    if (skus.isNotEmpty()) {
        val known = h.createQuery("SELECT id FROM app.sku WHERE id IN (<ids>)").bindList("ids", skus.toList()).mapTo(Long::class.java).set()
        (skus - known).firstOrNull()?.let { bad("body.lines.sku_id", "unknown_sku") }
    }
}

private suspend fun saveRouteDay(call: ApplicationCall, d: WebEntryDeps): WebEntryRouteDayOut {
    val p = call.principal
    if (p.role !in ENTRY_ROLES) throw AdminSupport.forbidden("web entry is not available to this role")
    val req = call.receiveStrict(WebEntryRouteDayIn.serializer())
    val cu = AdminSupport.uuid(req.client_uuid, "body.client_uuid")
    if (req.route_id < 1) bad("body.route_id")
    val date = AdminSupport.date(req.business_date, "body.business_date")
    if (req.successful_calls !in 0..100_000) bad("body.successful_calls", "out_of_range")
    AdminSupport.noNul("body.change_reason", req.change_reason)
    val reason = req.change_reason?.trim()?.also { if (it.length !in 10..500) bad("body.change_reason", "length") }
    val now = AdminSupport.utc(d.clock)
    return uniqueAsConflict { d.db.jdbi.inTransaction<WebEntryRouteDayOut, Exception> { h ->
        h.createQuery("SELECT pg_advisory_xact_lock(hashtextextended(:k, 0))").bind("k", "webentry-uuid:$cu").mapTo(String::class.java).findOne()
        // Replay first: a stored entry answers before reach and the lines are re-judged (a route moved out of reach or a
        // SKU retired since the first save must not turn a retry into 403 or 400); other content is a 409.
        val prior = h.createQuery("SELECT route_id, business_date, successful_calls, entered_by FROM app.web_entry_route_day WHERE client_uuid = :u").bind("u", cu)
            .map { rs, _ -> arrayOf<Any?>(rs.getLong(1), rs.getObject(2, LocalDate::class.java), rs.getInt(3), rs.getLong(4)) }.findOne().orElse(null)
        if (prior != null) {
            val same = prior[0] == req.route_id && prior[1] == date && prior[2] == req.successful_calls && prior[3] == p.userId &&
                linesOf(h, cu, includeVoided = true).map { it.copy(sale_qty_base = null) } == req.lines.map { it.copy(sale_qty_base = null) }.sortedBy { it.sku_id }
            if (!same) throw ApiProblem(ProblemCode.ERR_CONFLICT, "client_uuid already stores a different entry")
            return@inTransaction WebEntryRouteDayOut(req.route_id, date.toString(), linesOf(h, cu), req.successful_calls, h.createQuery("SELECT target_outlets FROM app.web_entry_route_day WHERE client_uuid = :u").bind("u", cu).mapTo(Int::class.java).one(),
                h.createQuery("SELECT app_overlap FROM app.web_entry_route_day WHERE client_uuid = :u").bind("u", cu).mapTo(Boolean::class.java).one(), client_uuid = cu.toString(), saved_at = null, saved_by_user_id = p.userId)
        }
        val zone = requireRouteReach(d, call, h, req.route_id)
        h.createQuery("SELECT pg_advisory_xact_lock(hashtextextended(:k, 0))").bind("k", "webentry:${req.route_id}:$date").mapTo(String::class.java).findOne()
        // Shared with Final Submit's exclusive lock (DayApi): an entry in flight finishes before the zone-day is submitted, and one after sees it.
        h.createQuery("SELECT pg_advisory_xact_lock_shared(hashtextextended(:k, 3))").bind("k", "final:$zone:$date").mapTo(String::class.java).findOne()
        // Shared with the data void's exclusive lock: a void of this route-day waits for an entry in flight, and an entry after it sees the tombstones.
        h.createQuery("SELECT pg_advisory_xact_lock_shared(hashtextextended(:k, 0))").bind("k", "voidday:${req.route_id}:$date").mapTo(String::class.java).findOne()
        validateLines(h, req.lines)
        if (finalSubmitted(h, zone, date)) throw ApiProblem(ProblemCode.ERR_DAY_ALREADY_FINAL_SUBMITTED, "the zone-day is Final Submitted")
        requireWindow(d, h, req.route_id, zone, date, now)
        val target = targetOutlets(h, req.route_id, date)
        if (cfgBool(d, "cfg.web.entry_validate_calls_le_target", true) && req.successful_calls > target) bad("body.successful_calls", "above_target")
        val overlap = appOverlap(h, req.route_id, date)
        val live = h.createQuery("SELECT client_uuid FROM app.web_entry_route_day WHERE route_id = :r AND business_date = :d AND replaced_at IS NULL AND voided_at IS NULL FOR UPDATE").bind("r", req.route_id).bind("d", date).mapTo(UUID::class.java).findOne().orElse(null)
        if (live != null && reason == null) bad("body.change_reason", "required_on_resave")
        if (live != null) h.createUpdate("UPDATE app.web_entry_route_day SET replaced_at = :now, replaced_by = :by WHERE client_uuid = :u").bind("now", now).bind("by", p.userId).bind("u", live).execute()
        h.createUpdate(
            "INSERT INTO app.web_entry_route_day (client_uuid, supersedes_client_uuid, route_id, business_date, successful_calls, target_outlets, app_overlap, change_reason, entered_by, entered_at) VALUES (:u, CAST(:sup AS uuid), :r, :d, :sc, :t, :ov, :why, :by, :now)",
        ).bind("u", cu).bindAny("sup", live?.toString()).bind("r", req.route_id).bind("d", date).bind("sc", req.successful_calls).bind("t", target).bind("ov", overlap).bindAny("why", reason).bind("by", p.userId).bind("now", now).execute()
        for (l in req.lines) {
            h.createUpdate("INSERT INTO app.web_entry_line (entry_client_uuid, route_id, business_date, sku_id, issue_qty_base, return_qty_base, memo_count, class_qty_base) VALUES (:e, :r, :d, :s, :i, :ret, :m, CAST(:c AS jsonb))")
                .bind("e", cu).bind("r", req.route_id).bind("d", date).bind("s", l.sku_id).bind("i", l.issue_qty_base).bind("ret", l.return_qty_base).bind("m", l.memo_count)
                .bind("c", kotlinx.serialization.json.JsonObject(l.class_qty_base.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }).toString()).execute()
        }
        AuditWriter.write(h, p, "web_entry_route_day", cu.toString(), if (live != null) "resave" else "create", null, buildJsonObject {
            put("route_id", req.route_id); put("business_date", date.toString()); put("lines", req.lines.size); put("successful_calls", req.successful_calls); put("app_overlap", overlap)
            if (live != null) put("supersedes", live.toString())
        }, reason, call.requestId)
        liveEntry(h, req.route_id, date)!!
    } }
}

private suspend fun saveQc(call: ApplicationCall, d: WebEntryDeps): QcEntryOut {
    val p = call.principal
    if (p.role !in ENTRY_ROLES) throw AdminSupport.forbidden("QC entry is not available to this role")
    val req = call.receiveStrict(QcEntryIn.serializer())
    val cu = AdminSupport.uuid(req.client_uuid, "body.client_uuid")
    if (req.source !in setOf("market", "warehouse")) bad("body.source")
    if (req.source == "warehouse" && p.role !in WAREHOUSE_ROLES) throw AdminSupport.forbidden("the warehouse QC entry needs an administrator")
    val date = AdminSupport.date(req.business_date, "body.business_date")
    if (req.zone_id < 1) bad("body.zone_id")
    if (req.rows.isEmpty() || req.rows.size > 5000) bad("body.rows", "length")
    AdminSupport.noNul("body.reason", req.reason)
    val reason = req.reason?.trim()
    if (req.source == "warehouse") { if (reason == null || reason.length !in 10..500) bad("body.reason", "required") ; if (req.route_id != null) bad("body.route_id", "not_allowed") }
    else { if (req.route_id == null || req.route_id < 1) bad("body.route_id", "required"); if (reason != null && reason.length !in 10..500) bad("body.reason", "length") }
    val triples = HashSet<Triple<Long, String, Unit>>()
    req.rows.forEachIndexed { i, r ->
        val at = "body.rows[$i]"
        if (r.sku_id < 1) bad("$at.sku_id")
        if (!AdminSupport.CODE.matches(r.fault_type_code)) bad("$at.fault_type_code")
        if (r.qty_base !in 0..10_000_000) bad("$at.qty_base", "out_of_range")
        if (!triples.add(Triple(r.sku_id, r.fault_type_code, Unit))) bad("$at.fault_type_code", "duplicate")
    }
    val now = AdminSupport.utc(d.clock)
    return uniqueAsConflict { d.db.jdbi.inTransaction<QcEntryOut, Exception> { h ->
        h.createQuery("SELECT pg_advisory_xact_lock(hashtextextended(:k, 0))").bind("k", "qcentry:$cu").mapTo(String::class.java).findOne()
        // Replay first: a stored entry answers before reach and the route's zone are re-judged; other content is a 409.
        val prior = h.createQuery("SELECT qc_source, zone_id, route_id, business_date, (SELECT count(*) FROM app.qc_summary_entry_line l WHERE l.entry_client_uuid = e.client_uuid) AS n, entered_by FROM app.qc_summary_entry e WHERE client_uuid = :u").bind("u", cu)
            .map { rs, _ -> arrayOf<Any?>(rs.getString(1), rs.getLong(2), rs.getObject(3) as Long?, rs.getObject(4, LocalDate::class.java), rs.getInt(5), rs.getLong(6)) }.findOne().orElse(null)
        if (prior != null) {
            val storedRows = h.createQuery("SELECT sku_id, fault_type_code, qty_base FROM app.qc_summary_entry_line WHERE entry_client_uuid = :u").bind("u", cu).map { rs, _ -> Triple(rs.getLong(1), rs.getString(2), rs.getLong(3)) }.set()
            val storedReason = h.createQuery("SELECT reason FROM app.qc_summary_entry WHERE client_uuid = :u").bind("u", cu).mapTo(String::class.java).findOne().orElse(null)
            if (prior[0] != req.source || prior[1] != req.zone_id || prior[2] != req.route_id || prior[3] != date || prior[4] != req.rows.size || prior[5] != p.userId ||
                storedRows != req.rows.map { Triple(it.sku_id, it.fault_type_code, it.qty_base) }.toSet() || storedReason != reason) throw ApiProblem(ProblemCode.ERR_CONFLICT, "client_uuid already stores a different QC entry")
            return@inTransaction QcEntryOut(cu.toString(), req.rows.size, true)
        }
        val reach = AdminSupport.reach(d.reach, call, d.clock)
        if (!reach.coversZone(req.zone_id) || h.createQuery("SELECT count(*) FROM app.zone WHERE id = :z").bind("z", req.zone_id).mapTo(Int::class.java).one() == 0) throw AdminSupport.outOfScope("zone outside your reach")
        if (req.route_id != null) {
            val rz = routeZone(h, req.route_id)
            if (rz == null || rz != req.zone_id) bad("body.route_id", "not_in_zone")
            h.createQuery("SELECT pg_advisory_xact_lock_shared(hashtextextended(:k, 0))").bind("k", "voidday:${req.route_id}:$date").mapTo(String::class.java).findOne()
        }
        h.createQuery("SELECT pg_advisory_xact_lock_shared(hashtextextended(:k, 3))").bind("k", "final:${req.zone_id}:$date").mapTo(String::class.java).findOne()
        if (finalSubmitted(h, req.zone_id, date)) throw ApiProblem(ProblemCode.ERR_DAY_ALREADY_FINAL_SUBMITTED, "the zone-day is Final Submitted")
        requireWindow(d, h, req.route_id, req.zone_id, date, now)
        val skus = req.rows.map { it.sku_id }.toSet(); val faults = req.rows.map { it.fault_type_code }.toSet()
        val knownSkus = h.createQuery("SELECT id FROM app.sku WHERE id IN (<ids>)").bindList("ids", skus.toList()).mapTo(Long::class.java).set()
        (skus - knownSkus).firstOrNull()?.let { bad("body.rows.sku_id", "unknown_sku") }
        val knownFaults = h.createQuery("SELECT code FROM app.code_list_item WHERE list_key = 'qc_fault_type' AND code IN (<c>) AND valid_from <= :d AND (valid_to IS NULL OR valid_to > :d)").bindList("c", faults.toList()).bind("d", date).mapTo(String::class.java).set()
        (faults - knownFaults).firstOrNull()?.let { bad("body.rows.fault_type_code", "unknown_fault_type") }
        h.createUpdate("INSERT INTO app.qc_summary_entry (client_uuid, qc_source, zone_id, route_id, business_date, reason, entered_by, entered_at) VALUES (:u, :s, :z, :r, :d, :why, :by, :now)")
            .bind("u", cu).bind("s", req.source).bind("z", req.zone_id).bindAny("r", req.route_id).bind("d", date).bindAny("why", reason).bind("by", p.userId).bind("now", now).execute()
        for (r in req.rows) h.createUpdate("INSERT INTO app.qc_summary_entry_line (entry_client_uuid, sku_id, fault_type_code, qty_base) VALUES (:e, :s, :f, :q)").bind("e", cu).bind("s", r.sku_id).bind("f", r.fault_type_code).bind("q", r.qty_base).execute()
        AuditWriter.write(h, p, "qc_summary_entry", cu.toString(), "create", null, buildJsonObject {
            put("source", req.source); put("zone_id", req.zone_id); put("business_date", date.toString()); put("rows", req.rows.size)
        }, reason, call.requestId)
        QcEntryOut(cu.toString(), req.rows.size, false)
    } }
}

/** A unique-key race the advisory locks cannot see (a competing commit of the same client_uuid) is a 409 the browser can retry as a replay, never a 500. */
private fun <T> uniqueAsConflict(block: () -> T): T = try { block() } catch (e: org.jdbi.v3.core.JdbiException) {
    if (generateSequence<Throwable>(e) { it.cause }.filterIsInstance<java.sql.SQLException>().any { it.sqlState == "23505" }) throw ApiProblem(ProblemCode.ERR_CONFLICT, "the entry was saved by a concurrent request; retry")
    throw e
}
