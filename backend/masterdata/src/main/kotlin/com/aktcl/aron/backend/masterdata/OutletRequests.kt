package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.IngestRecord
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.RecordHandler
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.receiveStrict
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import com.aktcl.aron.rules.BusinessDate
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jdbi.v3.core.Handle
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

/** Contract OutletRequest.events[]. */
@Serializable
data class OutletRequestEventDto(val event: String, val actor_user_id: Long, val at: String, val note: String? = null)

/** Contract OutletRequest. */
@Serializable
data class OutletRequestDto(
    val request_uuid: String, val request_type: String, val status: String, val outlet_id: Long?, val proposed: JsonObject,
    val route_id: Long?, val cluster_id: Long?, val requested_by_user_id: Long, val requested_at: String, val request_fix: JsonObject?,
    val moved_m: Double?, val requires_tso: Boolean, val flags: List<String>, val photos: List<String>,
    val verified_by_user_id: Long?, val verified_at: String?, val verified_via: String?, val approved_by_user_id: Long?, val approved_at: String?,
    val rejection_reason: String?, val resulting_outlet_id: Long?, val events: List<OutletRequestEventDto>,
)

/** Contract OutletRequestSummary. */
@Serializable
data class OutletRequestSummaryDto(
    val request_uuid: String, val request_type: String, val status: String, val outlet_id: Long?, val outlet_name: String?, val route_id: Long?,
    val cluster_id: Long?, val requested_by_user_id: Long, val requested_at: String, val rejection_reason: String?,
)

/** Contract OutletRequestPage. */
@Serializable
data class OutletRequestPageDto(val items: List<OutletRequestSummaryDto>, val next_cursor: String?)

/** Contract OutletRequestCreateRequest. */
@Serializable
data class OutletRequestCreateDto(val request_uuid: String, val request_type: String, val outlet_id: Long, val proposed: JsonObject, val note: String? = null)

/** Contract OutletRequestVerifyRequest. */
@Serializable
data class OutletRequestVerifyDto(val sub_channel_id: Long, val geo_class: String? = null, val note: String? = null)

/** Contract OutletRequestApproveRequest. */
@Serializable
data class OutletRequestApproveDto(val outlet_code: String? = null, val route_id: Long? = null, val change_reason: String)

/** Contract ReasonRequest. */
@Serializable
data class OutletRequestReasonDto(val reason: String)

/**
 * F-API-055 (contract listOutletRequests, getOutletRequest, createOutletRequest, verifyOutletRequest, approveOutletRequest,
 * rejectOutletRequest; docs/24 s12.2, BC-84). One table for app and web requests (`app.outlet_change_request`, keyed by
 * the request uuid) and one trail (`app.outlet_request_event`). Life cycle: pending -> verified (AMO, in the app or here)
 * -> approved or rejected (DMO or admin; a request whose pin moves more than `cfg.sec.fraud.location_move_alert_m` on a
 * confirmed outlet is approved by the TSO or an admin); approver, verifier and requester are three different people
 * (409 `ERR_SEPARATION_OF_DUTIES`). The contract's decision bodies carry no event uuid, so a decision is idempotent by
 * state: the same person repeating the decision the request already has gets it back unchanged; any other decision on a
 * decided request is 409 `ERR_CONFLICT`. An approval applies the change to the outlet master through the admin outlet
 * entity (validation, audit, placement and location history) in the same transaction.
 */
fun Route.outletRequestRoutes(d: AdminMasterDeps) {
    authenticated(d.guard) {
        get("/outlet-requests") {
            val q = OutletRequestQuery.parse(call.request.queryParameters)
            call.respond(withContext(Dispatchers.IO) { OutletRequests(d).page(call, call.principal, q) })
        }
        get("/outlet-requests/{request_uuid}") {
            val u = requestUuid(call)
            call.respond(withContext(Dispatchers.IO) { OutletRequests(d).get(call, call.principal, u) })
        }
        post("/outlet-requests") {
            val req = call.receiveStrict(OutletRequestCreateDto.serializer())
            val (status, dto) = withContext(Dispatchers.IO) { OutletRequests(d).create(call, call.principal, req) }
            call.respond(status, dto)
        }
        post("/outlet-requests/{request_uuid}/verify") {
            val u = requestUuid(call)
            val req = call.receiveStrict(OutletRequestVerifyDto.serializer())
            call.respond(withContext(Dispatchers.IO) { OutletRequests(d).verify(call, call.principal, u, req) })
        }
        post("/outlet-requests/{request_uuid}/approve") {
            val u = requestUuid(call)
            val req = call.receiveStrict(OutletRequestApproveDto.serializer())
            call.respond(withContext(Dispatchers.IO) { OutletRequests(d).approve(call, call.principal, u, req) })
        }
        post("/outlet-requests/{request_uuid}/reject") {
            val u = requestUuid(call)
            val req = call.receiveStrict(OutletRequestReasonDto.serializer())
            call.respond(withContext(Dispatchers.IO) { OutletRequests(d).reject(call, call.principal, u, req) })
        }
    }
}

private val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

private fun requestUuid(call: ApplicationCall): UUID =
    call.parameters["request_uuid"]?.takeIf { UUID_V4.matches(it) }?.let(UUID::fromString)
        ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "request_uuid", errors = listOf(FieldError("path.request_uuid", "invalid_value")))

private fun bad(pointer: String, code: String = "invalid_value"): Nothing =
    throw ApiProblem(ProblemCode.ERR_VALIDATION, "invalid $pointer", errors = listOf(FieldError(pointer, code)))

internal data class OutletRequestQuery(
    val status: String?, val type: String?, val zoneId: Long?, val territoryId: Long?, val from: LocalDate?, val to: LocalDate?,
    val limit: Int, val after: Pair<OffsetDateTime, Long>?,
) {
    companion object {
        val STATUSES = setOf("pending", "verified", "approved", "rejected", "lapsed", "discarded")
        val TYPES = setOf("new", "close", "info", "cluster", "location", "route_add")
        private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        private val CURSOR = Regex("^[A-Za-z0-9_-]{1,512}$")

        private fun id(q: Parameters, n: String): Long? = q[n]?.let { it.toLongOrNull()?.takeIf { v -> v > 0 } ?: bad("query.$n") }
        private fun date(q: Parameters, n: String): LocalDate? =
            q[n]?.let { s -> s.takeIf { DATE.matches(it) }?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: bad("query.$n") }

        fun parse(q: Parameters): OutletRequestQuery {
            val status = q["status"]?.also { if (it !in STATUSES) bad("query.status") }
            val type = q["request_type"]?.also { if (it !in TYPES) bad("query.request_type") }
            val from = date(q, "from"); val to = date(q, "to")
            if (from != null && to != null && from.isAfter(to)) bad("query.from", "out_of_range")
            val limit = q["limit"]?.let { it.toIntOrNull()?.takeIf { v -> v in 1..500 } ?: bad("query.limit", "out_of_range") } ?: 100
            val after = q["cursor"]?.let { c ->
                runCatching {
                    require(CURSOR.matches(c)); val p = String(Base64.getUrlDecoder().decode(c)).split('|'); require(p.size == 3 && p[0] == "o1")
                    OffsetDateTime.parse(p[1]) to p[2].toLong()
                }.getOrNull() ?: bad("query.cursor")
            }
            return OutletRequestQuery(status, type, id(q, "zone_id"), id(q, "territory_id"), from, to, limit, after)
        }

        fun cursor(at: OffsetDateTime, id: Long): String = Base64.getUrlEncoder().withoutPadding().encodeToString("o1|$at|$id".toByteArray())
    }
}

internal class OutletRequests(private val d: AdminMasterDeps) {
    private val today: LocalDate get() = BusinessDate.of(d.clock.now().toEpochMilli()).toJavaLocalDate()
    private fun reachOf(p: AronPrincipal): Reach = d.reach.reach(p.userId, p.role, p.scopeVersion, today)

    /** The request's zone: its outlet's, else its route's, else the cluster it proposes, else its requester's home zone. */
    private val zoneExpr = "coalesce(o.zone_id, rt.zone_id, pc.zone_id, ru.home_zone_id)"
    private val from = """
        FROM app.outlet_change_request q LEFT JOIN app.outlet o ON o.id = q.outlet_id LEFT JOIN app.route rt ON rt.id = q.route_id
        LEFT JOIN app.cluster pc ON pc.id = CASE WHEN q.proposed ->> 'cluster_id' ~ '^[0-9]{1,18}$' THEN (q.proposed ->> 'cluster_id')::bigint END
        LEFT JOIN app.app_user ru ON ru.id = q.user_id
    """.trimIndent()

    /** Field reps see their own requests; everyone else the requests whose zone or route is in their reach. */
    private fun reachClause(reach: Reach): String = when {
        reach.ownRecordsOnly -> "q.user_id = :me"
        reach.national -> "true"
        else -> "($zoneExpr = ANY(:zones) OR q.route_id = ANY(:routes))"
    }

    private fun bindReach(st: org.jdbi.v3.core.statement.SqlStatement<*>, reach: Reach) {
        if (reach.ownRecordsOnly) st.bind("me", reach.userId)
        else if (!reach.national) st.bindArray("zones", Long::class.javaObjectType, reach.zoneIds.toList()).bindArray("routes", Long::class.javaObjectType, reach.routeIds.toList())
    }

    fun page(call: ApplicationCall, p: AronPrincipal, q: OutletRequestQuery): OutletRequestPageDto {
        val reach = reachOf(p)
        if (q.zoneId != null && !reach.coversZone(q.zoneId) && !reach.ownRecordsOnly) throw ApiProblem(ProblemCode.ERR_OUT_OF_SCOPE, "zone_id is outside your reach")
        val sql = StringBuilder(
            "SELECT q.id, q.client_uuid::text AS u, q.request_type, q.status, q.outlet_id, o.name AS outlet_name, q.route_id, " +
                "q.proposed ->> 'cluster_id' AS pcluster, o.cluster_id AS ocluster, q.user_id, q.captured_at, q.decision_reason, q.status AS st " +
                "$from WHERE q.voided_at IS NULL AND ${reachClause(reach)}",
        )
        if (q.status != null) sql.append(" AND q.status = :status")
        if (q.type != null) sql.append(" AND q.request_type = :type")
        if (q.zoneId != null) sql.append(" AND $zoneExpr = :zone")
        if (q.territoryId != null) sql.append(" AND $zoneExpr IN (SELECT id FROM app.zone WHERE territory_id = :terr)")
        if (q.from != null) sql.append(" AND q.business_date >= :from")
        if (q.to != null) sql.append(" AND q.business_date <= :to")
        if (q.after != null) sql.append(" AND (q.captured_at, q.id) < (:at, :aid)")
        sql.append(" ORDER BY q.captured_at DESC, q.id DESC LIMIT :lim")
        val rows = d.db.readJdbi.withHandle<List<Pair<OffsetDateTime, Pair<Long, OutletRequestSummaryDto>>>, Exception> { h ->
            val st = h.createQuery(sql.toString()).bind("lim", q.limit + 1)
            bindReach(st, reach)
            q.status?.let { st.bind("status", it) }; q.type?.let { st.bind("type", it) }; q.zoneId?.let { st.bind("zone", it) }
            q.territoryId?.let { st.bind("terr", it) }; q.from?.let { st.bind("from", it) }; q.to?.let { st.bind("to", it) }
            q.after?.let { st.bind("at", it.first).bind("aid", it.second) }
            st.map { rs, _ ->
                val at = rs.getObject("captured_at", OffsetDateTime::class.java)
                val status = rs.getString("status")
                at to (rs.getLong("id") to OutletRequestSummaryDto(
                    rs.getString("u"), rs.getString("request_type"), status, rs.getObject("outlet_id") as Long?, rs.getString("outlet_name"),
                    rs.getObject("route_id") as Long?, rs.getString("pcluster")?.toLongOrNull() ?: rs.getObject("ocluster") as Long?,
                    rs.getLong("user_id"), at.toInstant().wire(), if (status == "rejected") rs.getString("decision_reason") else null,
                ))
            }.list()
        }
        val items = rows.take(q.limit)
        val next = if (rows.size > q.limit) items.last().let { OutletRequestQuery.cursor(it.first, it.second.first) } else null
        return OutletRequestPageDto(items.map { it.second.second }, next)
    }

    fun get(call: ApplicationCall, p: AronPrincipal, u: UUID): OutletRequestDto =
        d.db.readJdbi.withHandle<OutletRequestDto, Exception> { h -> one(h, u, reachOf(p), lock = false)?.let { dto(h, it) } ?: admNotFound("request not found") }

    /** The request row when it is in reach (out of reach and unknown are the same 404). */
    private fun one(h: Handle, u: UUID, reach: Reach, lock: Boolean): Map<String, Any?>? {
        if (lock) h.createQuery("SELECT id FROM app.outlet_change_request WHERE client_uuid = :u FOR UPDATE").bind("u", u).mapTo(Long::class.java).findOne()
        val st = h.createQuery("SELECT q.*, $zoneExpr AS req_zone $from WHERE q.client_uuid = :u AND q.voided_at IS NULL AND ${reachClause(reach)}").bind("u", u)
        bindReach(st, reach)
        return st.mapToMap().findOne().orElse(null)
    }

    private fun ts(v: Any?): String? = when (v) {
        is OffsetDateTime -> v.toInstant().wire()
        is java.sql.Timestamp -> v.toInstant().wire()
        else -> null
    }

    private fun dto(h: Handle, r: Map<String, Any?>): OutletRequestDto {
        val u = r["client_uuid"].toString()
        val proposed = Json.parseToJsonElement(r["proposed"].toString()).jsonObject
        val events = h.createQuery("SELECT event, actor_user_id, at, note, via FROM app.outlet_request_event WHERE request_uuid = :u AND voided_at IS NULL ORDER BY at, id")
            .bind("u", UUID.fromString(u)).mapToMap().list()
        val trail = ArrayList<OutletRequestEventDto>()
        trail += OutletRequestEventDto("requested", (r["user_id"] as Number).toLong(), ts(r["captured_at"])!!, r["note"] as String?)
        events.filter { it["event"] != "created" && it["actor_user_id"] != null }.take(49).forEach {
            trail += OutletRequestEventDto(it["event"] as String, (it["actor_user_id"] as Number).toLong(), ts(it["at"])!!, it["note"] as String?)
        }
        val fix = (r["fix_status"] as String?)?.let { st ->
            JsonObject(buildMap {
                put("status", JsonPrimitive(st))
                (r["fix_lat"] as Double?)?.let { put("lat", JsonPrimitive(it)) }; (r["fix_lng"] as Double?)?.let { put("lng", JsonPrimitive(it)) }
                (r["fix_accuracy_m"] as Double?)?.let { put("accuracy_m", JsonPrimitive(it)) }; (r["fix_is_mock"] as Boolean?)?.let { put("is_mock", JsonPrimitive(it)) }
            })
        }
        val (moved, tso) = movement(h, r)
        val status = r["status"] as String
        val verifiedVia = events.lastOrNull { it["event"] == "verified" }?.get("via")?.let { if (it == "device") "app" else "web" }
        val photos = (r["photo_uuids"] as java.sql.Array?)?.let { a -> (a.array as Array<*>).map { it.toString() } } ?: emptyList()
        return OutletRequestDto(
            u, r["request_type"] as String, status, (r["outlet_id"] as Number?)?.toLong(), proposed, (r["route_id"] as Number?)?.toLong(),
            proposed["cluster_id"]?.jsonPrimitive?.longOrNull, (r["user_id"] as Number).toLong(), ts(r["captured_at"])!!, fix, moved, tso,
            if (r["fix_is_mock"] == true) listOf("mocked_fix") else emptyList(), photos,
            (r["verified_by"] as Number?)?.toLong(), ts(r["verified_at"]), verifiedVia,
            if (status == "approved") (r["decided_by"] as Number?)?.toLong() else null, if (status == "approved") ts(r["decided_at"]) else null,
            if (status == "rejected" || status == "discarded") r["decision_reason"] as String? else null, (r["created_outlet_id"] as Number?)?.toLong() ?: if (status == "approved") (r["outlet_id"] as Number?)?.toLong() else null,
            trail,
        )
    }

    /** For a location request on a confirmed outlet: the move in metres and whether it is above the TSO threshold. */
    private fun movement(h: Handle, r: Map<String, Any?>): Pair<Double?, Boolean> {
        if (r["request_type"] != "location" || r["outlet_id"] == null) return null to false
        val p = Json.parseToJsonElement(r["proposed"].toString()).jsonObject
        val lat = p["lat"]?.jsonPrimitive?.doubleOrNull; val lng = p["lng"]?.jsonPrimitive?.doubleOrNull
        if (lat == null || lng == null) return null to false
        val o = h.row("SELECT lat, lng, location_confirmed FROM app.outlet WHERE id = :o", "o" to r["outlet_id"]) ?: return null to false
        val ol = o["lat"] as Double? ?: return null to false
        val m = Math.round(com.aktcl.aron.rules.Geo.haversineM(ol, o["lng"] as Double, lat, lng) * 10) / 10.0
        val limit = runCatching { d.config?.int("cfg.sec.fraud.location_move_alert_m") }.getOrNull() ?: 300
        return m to (o["location_confirmed"] == true && m > limit)
    }

    // ---- web create ----------------------------------------------------------------------------------------------------

    fun create(call: ApplicationCall, p: AronPrincipal, req: OutletRequestCreateDto): Pair<HttpStatusCode, OutletRequestDto> {
        if (p.role !in setOf(Role.TSO, Role.DMO)) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "web outlet requests are raised by a TSO or DMO")
        if (!UUID_V4.matches(req.request_uuid)) bad("/request_uuid")
        if (req.request_type !in setOf("info", "location", "cluster", "close")) bad("/request_type")
        if ((req.note?.length ?: 0) > 500) bad("/note")
        validateProposal(req.request_type, req.proposed)
        val u = UUID.fromString(req.request_uuid)
        val reach = reachOf(p)
        return admWrite {
            d.db.jdbi.inTransaction<Pair<HttpStatusCode, OutletRequestDto>, Exception> { h ->
                h.execute("SELECT pg_advisory_xact_lock(hashtextextended(?, 4))", "outlet_request:$u")
                h.row("SELECT user_id, outlet_id, request_type FROM app.outlet_change_request WHERE client_uuid = :u", "u" to u)?.let { prior ->
                    if ((prior["user_id"] as Number).toLong() != p.userId || (prior["outlet_id"] as Number?)?.toLong() != req.outlet_id || prior["request_type"] != req.request_type) {
                        throw ApiProblem(ProblemCode.ERR_CONFLICT, "request_uuid is already used for another request")
                    }
                    return@inTransaction HttpStatusCode.Created to dto(h, one(h, u, reach, lock = false) ?: admNotFound("request not found"))
                }
                val outlet = h.row("SELECT zone_id, route_id, status FROM app.outlet WHERE id = :o", "o" to req.outlet_id) ?: admNotFound("outlet not found")
                if (!reach.coversZone((outlet["zone_id"] as Number).toLong())) throw ApiProblem(ProblemCode.ERR_OUT_OF_SCOPE, "the outlet is outside your reach")
                if (outlet["status"] != "active") throw ApiProblem(ProblemCode.ERR_CONFLICT, "the outlet is not active")
                val now = d.clock.now()
                h.createUpdate(
                    """
                    INSERT INTO app.outlet_change_request (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, request_type, outlet_id, proposed, note, external_ref)
                    VALUES (:u, :u, :bd, :me, :route, :at, :cv, :t, :o, CAST(:p AS jsonb), :note, :ref)
                    """.trimIndent(),
                ).bind("u", u).bind("bd", today).bind("me", p.userId).bindAny("route", outlet["route_id"]).bind("at", odt(now))
                    .bind("cv", runCatching { d.config?.configVersion() }.getOrNull() ?: 0L).bind("t", req.request_type).bind("o", req.outlet_id)
                    .bind("p", req.proposed.toString()).bindAny("note", req.note).bind("ref", u.toString()).execute()
                h.createUpdate("INSERT INTO app.outlet_request_event (request_uuid, event, actor_user_id, via, business_date, at, note) VALUES (:u, 'created', :me, 'web', :bd, :at, :note)")
                    .bind("u", u).bind("me", p.userId).bind("bd", today).bind("at", odt(now)).bindAny("note", req.note).execute()
                HttpStatusCode.Created to dto(h, one(h, u, reach, lock = false)!!)
            }
        }
    }

    /** The members each web request type requires (docs/24 s12.2); closed objects: unknown members are refused. */
    private fun validateProposal(type: String, p: JsonObject) {
        val allowed = mapOf(
            "info" to setOf("name", "name_bn", "owner_name", "contact_number", "address", "sub_channel_id"),
            "location" to setOf("lat", "lng"), "cluster" to setOf("cluster_id"), "close" to setOf("close_reason_code"),
        ).getValue(type)
        p.keys.firstOrNull { it !in allowed }?.let { bad("/proposed/$it", "not_allowed") }
        if (p.isEmpty()) bad("/proposed", "required")
        fun str(k: String, min: Int, max: Int, nullable: Boolean = false) = p[k]?.let { v ->
            if (v is JsonNull) { if (!nullable) bad("/proposed/$k") } else {
                val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bad("/proposed/$k")
                if (s.length !in min..max) bad("/proposed/$k")
            }
        }
        str("name", 2, 120); str("name_bn", 0, 120, true); str("owner_name", 2, 120); str("address", 0, 300, true)
        p["contact_number"]?.let { if (!Regex("^01[3-9][0-9]{8}$").matches((it as? JsonPrimitive)?.contentOrNull ?: "")) bad("/proposed/contact_number") }
        p["sub_channel_id"]?.let { if (it !is JsonNull && ((it as? JsonPrimitive)?.longOrNull ?: 0) < 1) bad("/proposed/sub_channel_id") }
        when (type) {
            "location" -> {
                val lat = p["lat"]?.jsonPrimitive?.doubleOrNull ?: bad("/proposed/lat", "required"); val lng = p["lng"]?.jsonPrimitive?.doubleOrNull ?: bad("/proposed/lng", "required")
                if (lat !in -90.0..90.0) bad("/proposed/lat"); if (lng !in -180.0..180.0) bad("/proposed/lng")
            }
            "cluster" -> if ((p["cluster_id"]?.jsonPrimitive?.longOrNull ?: 0) < 1) bad("/proposed/cluster_id", "required")
            "close" -> if (!Regex("^[a-z][a-z0-9_]{1,40}$").matches(p["close_reason_code"]?.jsonPrimitive?.contentOrNull ?: "")) bad("/proposed/close_reason_code", "required")
        }
    }

    // ---- decisions -----------------------------------------------------------------------------------------------------

    private fun sod(p: AronPrincipal, r: Map<String, Any?>) {
        if ((r["user_id"] as Number).toLong() == p.userId) throw ApiProblem(ProblemCode.ERR_SEPARATION_OF_DUTIES, "the requester cannot decide their own request")
        if ((r["verified_by"] as Number?)?.toLong() == p.userId) throw ApiProblem(ProblemCode.ERR_SEPARATION_OF_DUTIES, "the verifier cannot also decide the request")
    }

    private fun event(h: Handle, u: UUID, event: String, p: AronPrincipal, note: String?, subChannel: Long? = null, geoClass: String? = null) {
        h.createUpdate(
            "INSERT INTO app.outlet_request_event (request_uuid, event, actor_user_id, via, business_date, at, note, sub_channel_id, geo_class) VALUES (:u, :e, :me, 'web', :bd, :at, :note, :sc, :gc)",
        ).bind("u", u).bind("e", event).bind("me", p.userId).bind("bd", today).bind("at", odt(d.clock.now())).bindAny("note", note)
            .bindAny("sc", subChannel).bindAny("gc", geoClass).execute()
    }

    fun verify(call: ApplicationCall, p: AronPrincipal, u: UUID, req: OutletRequestVerifyDto): OutletRequestDto {
        if (p.role !in VERIFIERS) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "requests are verified by the AMO")
        if (req.sub_channel_id < 1) bad("/sub_channel_id")
        if (req.geo_class != null && req.geo_class !in setOf("Hill", "Urban", "SemiUrban", "Rural")) bad("/geo_class")
        if ((req.note?.length ?: 0) > 500) bad("/note")
        val reach = reachOf(p)
        return admWrite {
            d.db.jdbi.inTransaction<OutletRequestDto, Exception> { h ->
                val r = one(h, u, reach, lock = true) ?: admNotFound("request not found")
                if (r["status"] == "verified" && (r["verified_by"] as Number?)?.toLong() == p.userId) return@inTransaction dto(h, r)
                if (r["status"] != "pending") throw ApiProblem(ProblemCode.ERR_CONFLICT, "the request is ${r["status"]}")
                if ((r["user_id"] as Number).toLong() == p.userId) throw ApiProblem(ProblemCode.ERR_SEPARATION_OF_DUTIES, "the requester cannot verify their own request")
                if (h.row("SELECT id FROM app.sub_channel WHERE id = :s AND status = 'active'", "s" to req.sub_channel_id) == null) bad("/sub_channel_id", "unknown_sub_channel")
                h.createUpdate("UPDATE app.outlet_change_request SET status = 'verified', status_changed_at = :at, verified_by = :me, verified_at = :at WHERE client_uuid = :u")
                    .bind("at", odt(d.clock.now())).bind("me", p.userId).bind("u", u).execute()
                event(h, u, "verified", p, req.note, req.sub_channel_id, req.geo_class)
                dto(h, one(h, u, reach, lock = false)!!)
            }
        }
    }

    fun reject(call: ApplicationCall, p: AronPrincipal, u: UUID, req: OutletRequestReasonDto): OutletRequestDto {
        if (p.role !in DECIDERS + VERIFIERS) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "this role cannot reject requests")
        if (req.reason.length !in 10..500) bad("/reason")
        val reach = reachOf(p)
        return admWrite {
            d.db.jdbi.inTransaction<OutletRequestDto, Exception> { h ->
                val r = one(h, u, reach, lock = true) ?: admNotFound("request not found")
                if (r["status"] == "rejected" && (r["decided_by"] as Number?)?.toLong() == p.userId) return@inTransaction dto(h, r)
                if (r["status"] !in setOf("pending", "verified")) throw ApiProblem(ProblemCode.ERR_CONFLICT, "the request is ${r["status"]}")
                // A verified request is decided by the approvers; a pending one may also be turned down by a verifier.
                if (r["status"] == "verified" && p.role !in DECIDERS) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "a verified request is decided by the DMO or an admin")
                sod(p, r)
                h.createUpdate("UPDATE app.outlet_change_request SET status = 'rejected', status_changed_at = :at, decided_by = :me, decided_at = :at, decision_reason = :why WHERE client_uuid = :u")
                    .bind("at", odt(d.clock.now())).bind("me", p.userId).bind("why", req.reason).bind("u", u).execute()
                event(h, u, "rejected", p, req.reason)
                dto(h, one(h, u, reach, lock = false)!!)
            }
        }
    }

    fun approve(call: ApplicationCall, p: AronPrincipal, u: UUID, req: OutletRequestApproveDto): OutletRequestDto {
        if (p.role !in DECIDERS + Role.TSO) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "requests are approved by the DMO or an admin")
        if (req.change_reason.length !in 10..500) bad("/change_reason")
        req.outlet_code?.let { if (!Regex("^[0-9A-Za-z][0-9A-Za-z._/-]{0,31}$").matches(it)) bad("/outlet_code") }
        val ctx = d.ctx(call, p)
        val outcome = admWrite {
            d.db.jdbi.inTransaction<OutletRequestDto, Exception> { h ->
                val r = one(h, u, ctx.reach, lock = true) ?: admNotFound("request not found")
                if (r["status"] == "approved" && (r["decided_by"] as Number?)?.toLong() == p.userId) return@inTransaction dto(h, r)
                if (r["status"] != "verified") throw ApiProblem(ProblemCode.ERR_CONFLICT, "only a verified request can be approved (it is ${r["status"]})")
                sod(p, r)
                val (_, tso) = movement(h, r)
                if (tso && p.role !in setOf(Role.TSO, Role.ADMIN, Role.SUPERADMIN)) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "a move above the alert distance is approved by the TSO")
                if (!tso && p.role == Role.TSO) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "requests are approved by the DMO or an admin")
                val created = apply(h, ctx, r, req)
                h.createUpdate("UPDATE app.outlet_change_request SET status = 'approved', status_changed_at = :at, decided_by = :me, decided_at = :at, decision_reason = :why, created_outlet_id = :co WHERE client_uuid = :u")
                    .bind("at", odt(d.clock.now())).bind("me", p.userId).bind("why", req.change_reason).bindAny("co", created).bind("u", u).execute()
                event(h, u, "approved", p, req.change_reason)
                dto(h, one(h, u, ctx.reach, lock = false)!!)
            }
        }
        d.geo.invalidate()
        return outcome
    }

    /** Applies an approved request to the outlet master; returns the new outlet's id for a `new` request. */
    private fun apply(h: Handle, ctx: AdminCtx, r: Map<String, Any?>, req: OutletRequestApproveDto): Long? {
        val p = Json.parseToJsonElement(r["proposed"].toString()).jsonObject
        fun s(k: String): Any? = p[k]?.let { v ->
            val prim = v as? JsonPrimitive ?: bad("/proposed/$k")
            when { prim is JsonNull -> null; prim.isString -> prim.content; else -> prim.longOrNull ?: prim.doubleOrNull ?: prim.booleanOrNull }
        }
        val verification = h.row(
            "SELECT sub_channel_id, geo_class FROM app.outlet_request_event WHERE request_uuid = :u AND event = 'verified' AND voided_at IS NULL ORDER BY at DESC, id DESC LIMIT 1",
            "u" to r["client_uuid"],
        )
        val outletId = (r["outlet_id"] as Number?)?.toLong()
        val reason = req.change_reason
        fun update(vals: Map<String, Any?>) {
            val cur = h.row("SELECT version, status FROM app.outlet WHERE id = :o", "o" to outletId) ?: admNotFound("outlet not found")
            if (cur["status"] != "active") throw ApiProblem(ProblemCode.ERR_CONFLICT, "the outlet is ${cur["status"]}")
            adminUpdate(h, ctx, OUTLET, outletId!!, (cur["version"] as Number).toInt(), vals, null, reason)
        }
        when (r["request_type"]) {
            "new" -> {
                val sub = (verification?.get("sub_channel_id") as Number?)?.toLong() ?: (s("sub_channel_id") as Long?) ?: bad("/proposed/sub_channel_id", "required")
                val channel = h.row("SELECT channel FROM app.sub_channel WHERE id = :s", "s" to sub)?.get("channel") ?: bad("/proposed/sub_channel_id", "unknown_sub_channel")
                val cluster = s("cluster_id") as Long? ?: bad("/proposed/cluster_id", "required")
                val zone = h.row("SELECT zone_id FROM app.cluster WHERE id = :c", "c" to cluster)?.get("zone_id") ?: bad("/proposed/cluster_id", "unknown_cluster")
                val route = req.route_id ?: (r["route_id"] as Number?)?.toLong() ?: bad("/route_id", "required")
                val vals = linkedMapOf<String, Any?>(
                    "name" to s("name"), "name_bn" to s("name_bn"), "owner_name" to s("owner_name"), "contact_number" to s("contact_number"),
                    "address" to s("address"), "zone_id" to (zone as Number).toLong(), "route_id" to route, "cluster_id" to cluster, "channel" to channel,
                    "sub_channel_id" to sub, "geo_class" to (verification?.get("geo_class") ?: s("geo_class")), "lat" to s("lat"), "lng" to s("lng"),
                    "outlet_kind" to "retail",
                )
                req.outlet_code?.let { vals["code"] = it }
                val row = adminInsert(h, ctx, OUTLET, vals, null, reason)
                val id = (row["id"] as Number).toLong()
                // The pin was taken on site and verified: confirmed (docs/24 s12.2).
                if (row["lat"] != null) h.createUpdate("UPDATE app.outlet SET location_confirmed = true WHERE id = :o").bind("o", id).execute()
                return id
            }
            "close" -> update(mapOf("status" to "closed"))
            "info" -> update(p.keys.filter { it in setOf("name", "name_bn", "owner_name", "contact_number", "address", "sub_channel_id") }.associateWith { s(it) })
            "cluster" -> update(mapOf("cluster_id" to s("cluster_id")))
            "route_add" -> update(mapOf("cluster_id" to s("cluster_id"), "route_id" to (req.route_id ?: (r["route_id"] as Number?)?.toLong() ?: bad("/route_id", "required"))))
            "location" -> {
                update(mapOf("lat" to s("lat"), "lng" to s("lng")))
                h.createUpdate("UPDATE app.outlet SET location_confirmed = true WHERE id = :o").bind("o", outletId).execute()
            }
        }
        return null
    }

    companion object {
        val VERIFIERS = setOf(Role.AMO, Role.ADMIN, Role.SUPERADMIN)
        val DECIDERS = setOf(Role.DMO, Role.ADMIN, Role.SUPERADMIN)
    }
}

/**
 * The AMO app's `outlet_request_verification` record (stored as an `outlet_request_event` by the generic writer) moves
 * the request on: `verified` sets it verified, `discarded` discards it, only while it is pending and only when the
 * verifier is not the requester (otherwise the event stays in the trail and the request is unchanged). A guarded UPDATE
 * in the record's transaction, so a resend or a second verification never moves it twice.
 */
class OutletRequestVerificationHandler : RecordHandler {
    override val types: Set<String> = setOf("outlet_request_verification")

    override fun afterStored(h: Handle, rec: IngestRecord, serverId: Long?) {
        val u = rec.payload["request_uuid"]?.jsonPrimitive?.contentOrNull ?: return
        val decision = rec.payload["decision"]?.jsonPrimitive?.contentOrNull ?: return
        val at = rec.envelope["captured_at"]?.jsonPrimitive?.contentOrNull?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() } ?: return
        when (decision) {
            "verified" -> h.createUpdate(
                "UPDATE app.outlet_change_request SET status = 'verified', status_changed_at = :at, verified_by = :me, verified_at = :at WHERE client_uuid = CAST(:u AS uuid) AND status = 'pending' AND user_id <> :me",
            ).bind("at", at).bind("me", rec.userId).bind("u", u).execute()
            "discarded" -> h.createUpdate(
                "UPDATE app.outlet_change_request SET status = 'discarded', status_changed_at = :at WHERE client_uuid = CAST(:u AS uuid) AND status = 'pending' AND user_id <> :me",
            ).bind("at", at).bind("me", rec.userId).bind("u", u).execute()
        }
    }
}
