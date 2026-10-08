package com.aktcl.aron.backend.masterdata

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
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.Serializable
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64

/** Contract `Outlet` (allOf MasterAudit). PII (`contact_number`) only with the `pii` claim. */
@Serializable
data class OutletDto(
    val id: Long, val code: String, val name: String, val name_bn: String?, val owner_name: String, val contact_number: String?,
    val address: String?, val zone_id: Long, val route_id: Long?, val cluster_id: Long, val channel: String, val sub_channel_id: Long?,
    val geo_class: String?, val lat: Double?, val lng: Double?, val location_confirmed: Boolean, val outlet_kind: String,
    val price_type: String, val status: String, val visit_sequence: Int?, val external_ref: String?,
    val created_at: String, val updated_at: String, val version: Int,
)

@Serializable
data class OutletPage(val items: List<OutletDto>, val next_cursor: String?)

class OutletsDeps(val db: Database, val geo: GeoRepository, val reach: ReachResolver, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM)

/** Roles that read outlet master data on the web (docs/24 s8.5); field roles get outlets through the bundle. */
private val OUTLET_READERS = setOf(Role.TSO, Role.DMO, Role.WM, Role.TOP, Role.ANALYST, Role.SUPPORT, Role.ADMIN, Role.SUPERADMIN)

/**
 * GET /v1/admin/outlets (contract listOutlets) and GET /v1/outlets (listOutletsInReach, every role): outlets in the caller's server-side reach, narrowed by `zone_id`,
 * `route_id`, `cluster_id`, `status`, `updated_since`, `q`; keyset-paged. Any other query parameter (for example a
 * list of scope ids sent by a client) is ignored: reach comes only from the token's user (docs/24 s3.5, s8.4).
 */
fun Route.outletRoutes(d: OutletsDeps) {
    authenticated(d.guard) {
        get("/admin/outlets") { call.respond(listOutlets(call, d, OUTLET_READERS)) }
        // F-API-010 (contract listOutletsInReach): the same read for every role; the reach alone decides the rows.
        get("/outlets") { call.respond(listOutlets(call, d, Role.entries.toSet())) }
    }
}

private fun ApplicationCall.longParam(name: String): Long? = request.queryParameters[name]?.let {
    it.toLongOrNull()?.takeIf { v -> v >= 1 } ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "$name must be a positive integer", errors = listOf(FieldError("query.$name", "invalid_value")))
}

private fun listOutlets(call: ApplicationCall, d: OutletsDeps, readers: Set<Role>): OutletPage {
    val p = call.principal
    if (p.role !in readers) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "outlet master data is not available to this role")
    val q = call.request.queryParameters
    val limit = q["limit"]?.let { it.toIntOrNull()?.takeIf { v -> v in 1..500 } ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "limit must be 1..500", errors = listOf(FieldError("query.limit", "out_of_range"))) } ?: 100
    val status = q["status"]?.also { if (it !in setOf("active", "closed", "merged", "archived")) throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad status", errors = listOf(FieldError("query.status", "invalid_value"))) }
    val updatedSince = q["updated_since"]?.let {
        runCatching { Instant.parse(it) }.getOrNull()?.takeIf { t -> t.isAfter(MIN_TS) && t.isBefore(MAX_TS) }
            ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad updated_since", errors = listOf(FieldError("query.updated_since", "invalid_value")))
    }
    val search = q["q"]?.also { if (it.length !in 2..80) throw ApiProblem(ProblemCode.ERR_VALIDATION, "q must be 2..80 characters", errors = listOf(FieldError("query.q", "out_of_range"))) }
    val sel = GeoSelector(zoneId = call.longParam("zone_id"), routeId = call.longParam("route_id"))
    val clusterId = call.longParam("cluster_id")
    val cursor = q["cursor"]?.let { decodeCursor(it, updatedSince != null) }

    val reach = d.reach.reach(p.userId, p.role, p.scopeVersion, BusinessDate.of(d.clock.now().toEpochMilli()).toJavaLocalDate())
    val check = ReachFilter.check(reach, sel, d.geo.geo())
    // A cluster is checked like its zone: outside the reach, or unknown (no existence leak), is 403.
    if (clusterId != null) {
        val clusterZone = d.db.jdbi.withHandle<Long?, Exception> { h ->
            h.createQuery("SELECT zone_id FROM app.cluster WHERE id = :c").bind("c", clusterId).mapTo(Long::class.java).findOne().orElse(null)
        } ?: throw ReachFilter.outOfScope()
        ReachFilter.check(reach, GeoSelector(zoneId = clusterZone), d.geo.geoCovering(zoneIds = listOf(clusterZone)))
    }
    if (check.empty) return OutletPage(emptyList(), null)

    val where = mutableListOf(reachPredicate(reach), selectorPredicate(check, sel))
    if (clusterId != null) where += "o.cluster_id = :cluster"
    if (status != null) where += "o.status = :status"
    if (updatedSince != null) where += "o.updated_at > :since"
    if (search != null) where += "(o.name ILIKE :search OR o.code ILIKE :search)"
    val order = if (updatedSince != null) "o.updated_at, o.id" else "o.id"
    if (cursor != null) where += if (updatedSince != null) "(o.updated_at, o.id) > (:c_at, :c_id)" else "o.id > :c_id"

    val rows = d.db.jdbi.withHandle<List<Pair<OutletDto, Instant>>, Exception> { h ->
        var st = h.createQuery("SELECT o.* FROM app.outlet o WHERE ${where.joinToString(" AND ")} ORDER BY $order LIMIT :lim")
            .bindReach(reach, check, sel).bind("lim", limit + 1)
        if (clusterId != null) st = st.bind("cluster", clusterId)
        if (status != null) st = st.bind("status", status)
        if (updatedSince != null) st = st.bind("since", OffsetDateTime.ofInstant(updatedSince, java.time.ZoneOffset.UTC))
        if (search != null) st = st.bind("search", "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
        if (cursor != null) {
            st = st.bind("c_id", cursor.second)
            cursor.first?.let { st = st.bind("c_at", OffsetDateTime.ofInstant(it, java.time.ZoneOffset.UTC)) }
        }
        st.map { rs, _ -> mapOutlet(rs, p.pii) to rs.getObject("updated_at", OffsetDateTime::class.java).toInstant() }.list()
    }
    val page = rows.take(limit)
    val next = if (rows.size > limit) page.last().let { (o, at) -> encodeCursor(if (updatedSince != null) at else null, o.id) } else null
    return OutletPage(page.map { it.first }, next)
}

private fun mapOutlet(rs: ResultSet, pii: Boolean) = OutletDto(
    id = rs.getLong("id"), code = rs.getString("code"), name = rs.getString("name"), name_bn = rs.getString("name_bn"),
    owner_name = rs.getString("owner_name"), contact_number = if (pii) rs.getString("contact_number") else null,
    address = rs.getString("address"), zone_id = rs.getLong("zone_id"), route_id = rs.getObject("route_id") as Long?,
    cluster_id = rs.getLong("cluster_id"), channel = rs.getString("channel"), sub_channel_id = rs.getObject("sub_channel_id") as Long?,
    geo_class = rs.getString("geo_class"), lat = rs.getObject("lat") as Double?, lng = rs.getObject("lng") as Double?,
    location_confirmed = rs.getBoolean("location_confirmed"), outlet_kind = rs.getString("outlet_kind"),
    price_type = rs.getString("price_type"), status = rs.getString("status"), visit_sequence = rs.getObject("visit_sequence") as Int?,
    external_ref = rs.getString("external_ref"),
    created_at = rs.getObject("created_at", OffsetDateTime::class.java).toInstant().wire(),
    updated_at = rs.getObject("updated_at", OffsetDateTime::class.java).toInstant().wire(),
    version = rs.getInt("version"),
)

private fun encodeCursor(at: Instant?, id: Long): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(((at?.let { "${it.epochSecond}.${it.nano}" } ?: "") + "|" + id).toByteArray())

private val MIN_TS: Instant = Instant.parse("1970-01-01T00:00:00Z")
private val MAX_TS: Instant = Instant.parse("9999-12-31T00:00:00Z")

/** Any malformed or tampered cursor is 400 ERR_VALIDATION, never a 500 (phones retry 5xx). */
private fun decodeCursor(c: String, keyed: Boolean): Pair<Instant?, Long> {
    val bad = ApiProblem(ProblemCode.ERR_VALIDATION, "invalid cursor", errors = listOf(FieldError("query.cursor", "invalid_value")))
    return runCatching {
        val text = String(Base64.getUrlDecoder().decode(c))
        val parts = text.split('|')
        require(parts.size == 2)
        val id = parts[1].toLong()
        require(id >= 0 && keyed == parts[0].isNotEmpty())
        val at = if (parts[0].isEmpty()) null else parts[0].split('.').let { p ->
            require(p.size == 2)
            Instant.ofEpochSecond(p[0].toLong(), p[1].toLong()).also { require(it.isAfter(MIN_TS) && it.isBefore(MAX_TS)) }
        }
        at to id
    }.getOrElse { throw bad }
}
