package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.config.AuditWriter
import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.requestId
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import com.aktcl.aron.rules.BusinessDate
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

// ---------------------------------------------------------------------------------------------------------------------
// Loyalty-programme portal, admin surface (lane portal-loyalty-api, train 2; D-LOY-API-05; contract 1.13.0; brief s5 to s7 defaults):
// programmes: listProgrammes, getProgramme, listProgrammeVersions, createProgramme (always a draft), updateProgramme (a new version while
// draft), setProgrammeStatus (draft -> active by a SUPERADMIN, active -> inactive by SUPERADMIN or ADMIN with `w`), listProgrammeEnrolments,
// putProgrammeEnrolments; gifts: listGifts, getGift, createGift, updateGift (SUPERADMIN only, brief Q3).
// Hooks only for the field: nothing here earns, redeems or posts points, changes the memo, print or the bundle (docs/27: the phone loyalty
// operations stay 501 in this train). No tier threshold, slab or gift is invented: everything is stored exactly as entered, in STD as stored.
// Writer rule (V0158): the portal copies the new version's name, period, dates, points expiry and rules into app.programme in the same
// transaction that sets current_version_id, only while the programme is a draft.
// ---------------------------------------------------------------------------------------------------------------------

@Serializable
data class LoyaltyTierOut(val tier_code: String, val sort_order: Int, val label_en: String, val label_bn: String?, val threshold: String?, val threshold_basis: String)

@Serializable
data class LoyaltyProductOut(val product_level: String, val product_id: Long)

@Serializable
data class LoyaltyScopeOut(val node_type: String, val node_id: Long)

@Serializable
data class ProgrammeVersionOut(
    val programme_version_id: Long, val programme_id: Long, val version_no: Int, val is_current: Boolean, val name_en: String, val name_bn: String?, val period_label: String?,
    val active_from: String, val active_to: String, val points_expire_on: String?, val rules: JsonObject, val tiers: List<LoyaltyTierOut>, val qualifying: List<LoyaltyProductOut>,
    val scope: List<LoyaltyScopeOut>, val change_reason: String?, val created_at: String,
)

@Serializable
data class ProgrammeVersionPageOut(val items: List<ProgrammeVersionOut>, val next_cursor: String?)

@Serializable
data class GiftOut(
    val gift_id: Long, val programme_id: Long, val code: String, val name_en: String, val name_bn: String?, val points_cost: Int?, val tier_codes: List<String>, val image_url: String?,
    val status: String, val version: Int,
)

@Serializable
data class GiftListOut(val items: List<GiftOut>)

@Serializable
data class EnrolmentOut(
    val programme_id: Long, val outlet_id: Long, val league_label: String?, val tier_code: String?, val base_target: String?, val valid_from: String, val valid_to: String?,
)

@Serializable
data class EnrolmentPageOut(val items: List<EnrolmentOut>, val next_cursor: String?)

@Serializable
data class BatchResultOut(val batch_uuid: String, val inserted: Int, val updated: Int, val unchanged: Int)

class AdminLoyaltyDeps(
    val db: Database, val reach: ReachResolver, val guard: AuthGuardDeps, val blob: BlobSasIssuer = UnconfiguredBlobSasIssuer, val clock: AronClock = AronClock.SYSTEM,
)

private val LOY_READERS = setOf(Role.SUPERADMIN, Role.ADMIN, Role.NATIONAL, Role.TOP, Role.BRAND, Role.WM, Role.DMO, Role.TSO)
private val LOY_ENROLMENT_READERS = LOY_READERS - Role.BRAND          // outlet data: a BRAND user reads programmes and gifts, not outlets (brief s3)
private val LOY_WRITERS = setOf(Role.SUPERADMIN, Role.ADMIN)
private val LOY_NATIONAL = setOf(Role.SUPERADMIN, Role.ADMIN, Role.NATIONAL, Role.TOP)
private val LOY_UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val TIER_CODE = Regex("^[a-z][a-z0-9_]{1,40}$")
private val GIFT_CODE = Regex("^[a-z][a-z0-9_]{1,40}$")
private val THRESHOLD_RE = Regex("^\\d{1,13}(\\.\\d{1,3})?$")
private val DECIMAL3_RE = Regex("^-?\\d{1,13}(\\.\\d{1,3})?$")
private val LOY_KINDS = setOf("diamond_league", "astha", "campaign", "superstar")
private val LOY_STATUSES = setOf("draft", "active", "inactive")
private val LOY_PRODUCT_LEVELS = setOf("category", "brand", "variant", "sku")
private val LOY_SCOPE_TYPES = setOf("wing", "division", "territory", "zone", "route", "channel", "sub_channel")
private const val LOY_MODULE = "loyalty"
private const val MAX_BATCH_ROWS = 20_000
private const val MAX_LIST = 500

private enum class Need { READ, READ_ENROLMENTS, WRITE, SUPER }

/** All the programme and gift operations of contract 1.13.0. */
fun Route.adminLoyaltyRoutes(d: AdminLoyaltyDeps) {
    authenticated(d.guard) {
        get("/admin/programmes") { call.respond(listProgrammes(call, d)) }
        post("/admin/programmes") { val (o, code) = createProgramme(call, d); respondProgramme(call, o, code) }
        get("/admin/programmes/{id}") { respondProgramme(call, getProgramme(call, d), HttpStatusCode.OK) }
        patch("/admin/programmes/{id}") { respondProgramme(call, updateProgramme(call, d), HttpStatusCode.OK) }
        get("/admin/programmes/{id}/versions") { call.respond(listProgrammeVersions(call, d)) }
        post("/admin/programmes/{id}/status") { respondProgramme(call, setProgrammeStatus(call, d), HttpStatusCode.OK) }
        get("/admin/programmes/{id}/enrolments") { call.respond(listEnrolments(call, d)) }
        put("/admin/programmes/{id}/enrolments") { call.respond(putEnrolments(call, d)) }
        get("/admin/gifts") { call.respond(listGifts(call, d)) }
        post("/admin/gifts") { val (g, code) = createGift(call, d); respondGift(call, g, code) }
        get("/admin/gifts/{id}") { respondGift(call, getGift(call, d), HttpStatusCode.OK) }
        patch("/admin/gifts/{id}") { respondGift(call, updateGift(call, d), HttpStatusCode.OK) }
    }
}

private class ProgrammeView(val json: JsonObject, val version: Int, val status: String, val activeTo: LocalDate, val currentVid: Long?, val tierCodes: Set<String>, val spec: ProgSpec?)

private suspend fun respondProgramme(call: ApplicationCall, p: ProgrammeView, code: HttpStatusCode) {
    call.etag(p.version)
    call.respond(code, p.json)
}

private suspend fun respondGift(call: ApplicationCall, g: GiftOut, code: HttpStatusCode) {
    call.etag(g.version)
    call.respond(code, g)
}

// ------------------------------------------------------------------------------------------------ who may do what

private fun loyForbidden(what: String) = ApiProblem(ProblemCode.ERR_FORBIDDEN, what)

/** Portal only, role-gated, and the ADMIN module grant judged here too when the grants are live (the guard judged it first). */
private fun ApplicationCall.loyaltyPrincipal(need: Need): AronPrincipal {
    val p = principal
    if (p.isPhone) throw loyForbidden("the loyalty programmes are a portal screen")
    val allowed = when (need) { Need.READ -> LOY_READERS; Need.READ_ENROLMENTS -> LOY_ENROLMENT_READERS; Need.WRITE -> LOY_WRITERS; Need.SUPER -> setOf(Role.SUPERADMIN) }
    if (p.role !in allowed) throw loyForbidden(
        when (need) {
            Need.READ -> "loyalty programmes are not available to this role"
            Need.READ_ENROLMENTS -> "enrolled outlets are not available to this role"
            Need.WRITE -> "changing loyalty programmes needs the ADMIN or SUPERADMIN role"
            Need.SUPER -> "only a SUPERADMIN may do this (activation and the gift catalogue)"
        },
    )
    if (p.role == Role.ADMIN) p.modules?.let { m ->
        val a = m[LOY_MODULE] ?: throw loyForbidden("no access to module $LOY_MODULE")
        if (need == Need.WRITE && a != "w") throw loyForbidden("read-only access to module $LOY_MODULE")
    }
    return p
}

/** What a reader may see (server-side, from rows: the token's `brands` and `geo` claims are informational and never read here). */
private sealed interface LVis {
    data object All : LVis
    data object Nobody : LVis
    data class Brands(val ids: List<Long>) : LVis
    data class Geo(val zones: List<Long>, val routes: List<Long>) : LVis
}

private fun loyVisibility(h: Handle, p: AronPrincipal, d: AdminLoyaltyDeps): LVis = when (p.role) {
    in LOY_NATIONAL -> LVis.All
    Role.BRAND -> h.createQuery("SELECT brand_id FROM app.user_brand_scope WHERE user_id = :u ORDER BY brand_id").bind("u", p.userId).mapTo(Long::class.java).list()
        .let { if (it.isEmpty()) LVis.Nobody else LVis.Brands(it) }
    Role.WM, Role.DMO, Role.TSO -> d.reach.reach(p.userId, p.role, p.scopeVersion, today(d)).let { r: Reach ->
        if (r.national) LVis.All else LVis.Geo(r.zoneIds.toList(), r.routeIds.toList())
    }
    else -> LVis.Nobody
}

private class LVisSql(val sql: String, val lists: Map<String, List<Long>>)

/**
 * SQL over a version row aliased [v] (LEFT JOINed: a programme made before the portal has none and shows to everyone who may read, as "no product,
 * no place"). Brand: the version names no product or one of its products is, or sits under, one of the user's brands. Geography: the version names
 * no wing/division/territory/zone/route (channel rows do not narrow geography) or one of them contains a zone of the user's reach (or is a route of it).
 */
private fun loyVisSql(vis: LVis, v: String): LVisSql? = when (vis) {
    LVis.All -> null
    LVis.Nobody -> LVisSql("FALSE", emptyMap())
    is LVis.Brands -> LVisSql(
        "(NOT EXISTS (SELECT 1 FROM app.programme_version_product x WHERE x.programme_version_id = $v.id) OR EXISTS (SELECT 1 FROM app.programme_version_product x WHERE x.programme_version_id = $v.id AND (" +
            "(x.product_level = 'brand' AND x.product_id IN (<vb>)) " +
            "OR (x.product_level = 'variant' AND EXISTS (SELECT 1 FROM app.product_node n WHERE n.id = x.product_id AND n.parent_id IN (<vb>))) " +
            "OR (x.product_level = 'sku' AND EXISTS (SELECT 1 FROM app.sku s JOIN app.product_node n ON n.id = s.variant_id WHERE s.id = x.product_id AND n.parent_id IN (<vb>))) " +
            "OR (x.product_level = 'category' AND EXISTS (SELECT 1 FROM app.product_node br JOIN app.product_node sg ON sg.id = br.parent_id WHERE br.id IN (<vb>) AND sg.parent_id = x.product_id)))))",
        mapOf("vb" to vis.ids),
    )
    is LVis.Geo -> LVisSql(
        "(NOT EXISTS (SELECT 1 FROM app.programme_version_scope s WHERE s.programme_version_id = $v.id AND s.node_type IN ('wing','division','territory','zone','route')) " +
            "OR EXISTS (SELECT 1 FROM app.programme_version_scope s WHERE s.programme_version_id = $v.id AND (" +
            "(s.node_type = 'zone' AND s.node_id IN (<vz>)) " +
            "OR (s.node_type = 'territory' AND EXISTS (SELECT 1 FROM app.zone zz WHERE zz.territory_id = s.node_id AND zz.id IN (<vz>))) " +
            "OR (s.node_type = 'division' AND EXISTS (SELECT 1 FROM app.zone zz JOIN app.territory tt ON tt.id = zz.territory_id WHERE tt.division_id = s.node_id AND zz.id IN (<vz>))) " +
            "OR (s.node_type = 'wing' AND EXISTS (SELECT 1 FROM app.zone zz JOIN app.territory tt ON tt.id = zz.territory_id JOIN app.division dd ON dd.id = tt.division_id WHERE dd.wing_id = s.node_id AND zz.id IN (<vz>))) " +
            "OR (s.node_type = 'route' AND EXISTS (SELECT 1 FROM app.route rr WHERE rr.id = s.node_id AND (rr.zone_id IN (<vz>) OR rr.id IN (<vr>)))))))",
        mapOf("vz" to vis.zones.ifEmpty { listOf(-1L) }, "vr" to vis.routes.ifEmpty { listOf(-1L) }),   // an empty list would be invalid SQL; no id is ever -1
    )
}

private fun org.jdbi.v3.core.statement.Query.bindLVis(s: LVisSql?): org.jdbi.v3.core.statement.Query { s?.lists?.forEach { (k, v) -> bindList(k, v) }; return this }

private fun today(d: AdminLoyaltyDeps): LocalDate = BusinessDate.of(d.clock.now().toEpochMilli()).toJavaLocalDate()

private fun loyConflict(msg: String, pointer: String = "body.request_uuid") = ApiProblem(ProblemCode.ERR_CONFLICT, msg, errors = listOf(FieldError(pointer, "reused")))

private fun noProgramme() = ApiProblem(ProblemCode.ERR_NOT_FOUND, "no loyalty programme with this id in your reach")

private fun Handle.lexists(sql: String, id: Long): Boolean = createQuery(sql).bind("i", id).mapTo(Int::class.java).findOne().isPresent

// ------------------------------------------------------------------------------------------------ reading programmes

private class PRow(
    val id: Long, val kind: String, val code: String, val nameEn: String, val nameBn: String?, val period: String?, val from: LocalDate, val to: LocalDate, val expire: LocalDate?,
    val rules: String, val status: String, val version: Int, val createdAt: OffsetDateTime, val updatedAt: OffsetDateTime, val currentVid: Long?, val no: Int?, val reason: String?,
)

private const val PROGRAMME_SELECT = "SELECT p.id, p.kind, p.code, p.name_en, p.name_bn, p.period_label, p.active_from, p.active_to, p.points_expire_on, CAST(p.rules AS text) AS rules, p.status, " +
    "p.version, p.created_at, p.updated_at, p.current_version_id, v.version_no, v.change_reason FROM app.programme p LEFT JOIN app.programme_version v ON v.id = p.current_version_id"

private fun java.sql.ResultSet.prow() = PRow(
    getLong("id"), getString("kind"), getString("code"), getString("name_en"), getString("name_bn"), getString("period_label"), getObject("active_from", LocalDate::class.java),
    getObject("active_to", LocalDate::class.java), getObject("points_expire_on", LocalDate::class.java), getString("rules"), getString("status"), getInt("version"),
    getObject("created_at", OffsetDateTime::class.java), getObject("updated_at", OffsetDateTime::class.java), getObject("current_version_id") as Long?, getObject("version_no") as Int?, getString("change_reason"),
)

private class VExtras(val tiers: Map<Long, List<LoyaltyTierOut>>, val qual: Map<Long, List<LoyaltyProductOut>>, val scope: Map<Long, List<LoyaltyScopeOut>>)

private fun vextras(h: Handle, vids: List<Long>): VExtras {
    if (vids.isEmpty()) return VExtras(emptyMap(), emptyMap(), emptyMap())
    val v = vids.distinct()
    val tiers = h.createQuery("SELECT programme_version_id, tier_code, sort_order, label_en, label_bn, threshold, threshold_basis FROM app.programme_version_tier WHERE programme_version_id IN (<v>) ORDER BY programme_version_id, sort_order")
        .bindList("v", v).map { rs, _ -> rs.getLong(1) to LoyaltyTierOut(rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5), rs.getBigDecimal(6)?.toPlainString(), rs.getString(7)) }.list().groupBy({ it.first }, { it.second })
    val qual = h.createQuery("SELECT programme_version_id, product_level, product_id FROM app.programme_version_product WHERE programme_version_id IN (<v>) ORDER BY programme_version_id, product_level, product_id")
        .bindList("v", v).map { rs, _ -> rs.getLong(1) to LoyaltyProductOut(rs.getString(2), rs.getLong(3)) }.list().groupBy({ it.first }, { it.second })
    val scope = h.createQuery("SELECT programme_version_id, node_type, node_id FROM app.programme_version_scope WHERE programme_version_id IN (<v>) ORDER BY programme_version_id, node_type, node_id")
        .bindList("v", v).map { rs, _ -> rs.getLong(1) to LoyaltyScopeOut(rs.getString(2), rs.getLong(3)) }.list().groupBy({ it.first }, { it.second })
    return VExtras(tiers, qual, scope)
}

private fun rulesObject(text: String): JsonObject = Json.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap())

private fun toSpec(r: PRow, e: VExtras) = ProgSpec(
    r.nameEn, r.nameBn, r.period, r.from, r.to, r.expire, rulesObject(r.rules),
    r.currentVid?.let { e.tiers[it] }.orEmpty().map { TierSpec(it.tier_code, it.sort_order, it.label_en, it.label_bn, it.threshold?.let(::BigDecimal)) },
    r.currentVid?.let { e.qual[it] }.orEmpty().map { it.product_level to it.product_id }, r.currentVid?.let { e.scope[it] }.orEmpty().map { it.node_type to it.node_id },
)

private fun programmeView(r: PRow, e: VExtras): ProgrammeView {
    val spec = toSpec(r, e)
    val json = buildJsonObject {
        put("programme_id", r.id); put("kind", r.kind); put("code", r.code); put("name_en", r.nameEn); put("name_bn", r.nameBn); put("period_label", r.period)
        put("active_from", r.from.toString()); put("active_to", r.to.toString()); put("points_expire_on", r.expire?.toString()); put("rules", rulesObject(r.rules))
        put("status", r.status); put("version", r.version)
        // Optional members of 1.13.0: absent (not null) for a programme made before the portal, which has no version.
        r.currentVid?.let { vid ->
            put("programme_version_id", vid); put("version_no", r.no!!)
            put("tiers", tiersJson(e.tiers[vid].orEmpty())); put("qualifying", qualJson(e.qual[vid].orEmpty())); put("scope", scopeJson(e.scope[vid].orEmpty()))
            put("change_reason", r.reason)
        }
        put("created_at", r.createdAt.toInstant().wire()); put("updated_at", r.updatedAt.toInstant().wire())
    }
    return ProgrammeView(json, r.version, r.status, r.to, r.currentVid, spec.tiers.map { it.code }.toSet(), if (r.currentVid != null) spec else null)
}

private fun tiersJson(l: List<LoyaltyTierOut>) = buildJsonArray { l.forEach { t -> add(buildJsonObject {
    put("tier_code", t.tier_code); put("sort_order", t.sort_order); put("label_en", t.label_en); put("label_bn", t.label_bn); put("threshold", t.threshold); put("threshold_basis", t.threshold_basis)
}) } }
private fun qualJson(l: List<LoyaltyProductOut>) = buildJsonArray { l.forEach { add(buildJsonObject { put("product_level", it.product_level); put("product_id", it.product_id) }) } }
private fun scopeJson(l: List<LoyaltyScopeOut>) = buildJsonArray { l.forEach { add(buildJsonObject { put("node_type", it.node_type); put("node_id", it.node_id) }) } }

private fun loadProgramme(h: Handle, id: Long, vis: LVisSql? = null, lock: Boolean = false): ProgrammeView? {
    val q = h.createQuery("$PROGRAMME_SELECT WHERE p.id = :o" + (vis?.let { " AND " + it.sql } ?: "") + if (lock) " FOR UPDATE OF p" else "").bind("o", id)
    val row = q.bindLVis(vis).map { rs, _ -> rs.prow() }.findOne().orElse(null) ?: return null
    return programmeView(row, vextras(h, listOfNotNull(row.currentVid)))
}

private fun listProgrammes(call: ApplicationCall, d: AdminLoyaltyDeps): JsonObject {
    val p = call.loyaltyPrincipal(Need.READ)
    val q = call.request.queryParameters
    val status = q["status"]?.also { if (it !in LOY_STATUSES) adminBad("query.status", "not_in_enum") }
    val kind = q["kind"]?.also { if (it !in LOY_KINDS) adminBad("query.kind", "not_in_enum") }
    return d.db.jdbi.withHandle<JsonObject, Exception> { h ->
        val vis = loyVisSql(loyVisibility(h, p, d), "v")
        val where = mutableListOf("TRUE")
        if (status != null) where += "p.status = :status"
        if (kind != null) where += "p.kind = :kind"
        if (vis != null) where += vis.sql
        val query = h.createQuery("$PROGRAMME_SELECT WHERE ${where.joinToString(" AND ")} ORDER BY p.id DESC LIMIT :lim").bind("lim", MAX_LIST)
        if (status != null) query.bind("status", status)
        if (kind != null) query.bind("kind", kind)
        val rows = query.bindLVis(vis).map { rs, _ -> rs.prow() }.list()
        val e = vextras(h, rows.mapNotNull { it.currentVid })
        buildJsonObject { put("items", buildJsonArray { rows.forEach { add(programmeView(it, e).json) } }) }
    }
}

private fun getProgramme(call: ApplicationCall, d: AdminLoyaltyDeps): ProgrammeView {
    val p = call.loyaltyPrincipal(Need.READ)
    val id = call.pathId()
    return d.db.jdbi.withHandle<ProgrammeView, Exception> { h -> loadProgramme(h, id, loyVisSql(loyVisibility(h, p, d), "v")) ?: throw noProgramme() }
}

private fun listProgrammeVersions(call: ApplicationCall, d: AdminLoyaltyDeps): ProgrammeVersionPageOut {
    val p = call.loyaltyPrincipal(Need.READ)
    val id = call.pathId(); val limit = call.pageLimit(); val cursor = call.pageCursor()
    return d.db.jdbi.withHandle<ProgrammeVersionPageOut, Exception> { h ->
        val vis = loyVisSql(loyVisibility(h, p, d), "v")
        // The programme must be visible through its current version; then each version is judged on its OWN scope (a version outside the caller's reach is not listed,
        // as for discounts): the visibility SQL runs on the version row.
        h.createQuery("SELECT p.id FROM app.programme p LEFT JOIN app.programme_version v ON v.id = p.current_version_id WHERE p.id = :o" + (vis?.let { " AND " + it.sql } ?: "")).bind("o", id).bindLVis(vis)
            .mapTo(Long::class.java).findOne().orElse(null) ?: throw noProgramme()
        val rows = h.createQuery(
            "SELECT v.id, v.version_no, v.name_en, v.name_bn, v.period_label, v.active_from, v.active_to, v.points_expire_on, CAST(v.rules AS text), v.change_reason, v.created_at, " +
                "(v.id = p.current_version_id) AS is_current FROM app.programme_version v JOIN app.programme p ON p.id = v.programme_id WHERE v.programme_id = :o" +
                (if (cursor != null) " AND v.version_no < :c" else "") + (vis?.let { " AND " + it.sql } ?: "") + " ORDER BY v.version_no DESC LIMIT :lim",
        ).bind("o", id).bind("lim", limit + 1).also { if (cursor != null) it.bind("c", cursor) }.bindLVis(vis)
            .map { rs, _ -> VRow(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getObject(6, LocalDate::class.java), rs.getObject(7, LocalDate::class.java),
                rs.getObject(8, LocalDate::class.java), rs.getString(9), rs.getString(10), rs.getObject(11, OffsetDateTime::class.java), rs.getBoolean(12)) }.list()
        val page = rows.take(limit)
        val e = vextras(h, page.map { it.vid })
        ProgrammeVersionPageOut(
            page.map {
                ProgrammeVersionOut(it.vid, id, it.no, it.current, it.nameEn, it.nameBn, it.period, it.from.toString(), it.to.toString(), it.expire?.toString(), rulesObject(it.rules),
                    e.tiers[it.vid].orEmpty(), e.qual[it.vid].orEmpty(), e.scope[it.vid].orEmpty(), it.reason, it.createdAt.toInstant().wire())
            },
            if (rows.size > limit) page.last().no.toString() else null,
        )
    }
}

private class VRow(
    val vid: Long, val no: Int, val nameEn: String, val nameBn: String?, val period: String?, val from: LocalDate, val to: LocalDate, val expire: LocalDate?, val rules: String,
    val reason: String?, val createdAt: OffsetDateTime, val current: Boolean,
)

// ------------------------------------------------------------------------------------------------ writing programmes: parsing

private class TierSpec(val code: String, val order: Int, val labelEn: String, val labelBn: String?, val threshold: BigDecimal?) {
    private fun norm() = listOf(code, order, labelEn, labelBn, threshold?.setScale(3)?.toPlainString())
    override fun equals(other: Any?) = other is TierSpec && norm() == other.norm()
    override fun hashCode() = norm().hashCode()
}

/** The definition of one version, normalised (the audit image and the no-change test are made from it). */
private class ProgSpec(
    val nameEn: String, val nameBn: String?, val period: String?, val from: LocalDate, val to: LocalDate, val expire: LocalDate?, val rules: JsonObject,
    val tiers: List<TierSpec>, val qualifying: List<Pair<String, Long>>, val scope: List<Pair<String, Long>>,
) {
    private fun norm() = listOf(nameEn, nameBn, period, from, to, expire, rules.toString(), tiers.sortedBy { it.order }, qualifying.sortedBy { "${it.first}:${it.second}" }, scope.sortedBy { "${it.first}:${it.second}" })
    override fun equals(other: Any?) = other is ProgSpec && norm() == other.norm()
    override fun hashCode() = norm().hashCode()
}

private val RULE_MEMBERS = setOf("name_en", "name_bn", "period_label", "active_from", "active_to", "points_expire_on", "rules", "tiers", "qualifying", "scope")

private fun JsonObject.ldate(k: String): LocalDate? {
    val v = this[k] ?: return null
    if (v is JsonNull) return null
    if (v !is JsonPrimitive || !v.isString) adminBad("body.$k", "not_a_date")
    return runCatching { LocalDate.parse(v.content) }.getOrNull()?.takeIf { it.toString() == v.content } ?: adminBad("body.$k", "not_a_date")
}

private fun JsonObject.lint(k: String, min: Long, max: Long, pointer: String = "body.$k", nullable: Boolean = false): Long? {
    val v = this[k] ?: return null
    if (v is JsonNull) { if (nullable) return null; adminBad(pointer, "null_not_allowed") }
    if (v !is JsonPrimitive || v.isString) adminBad(pointer, "not_an_integer")
    val n = v.content.toLongOrNull() ?: adminBad(pointer, "not_an_integer")
    if (n !in min..max) adminBad(pointer, "out_of_range")
    return n
}

private fun readRules(o: JsonObject): JsonObject {
    val v = o["rules"] ?: return JsonObject(emptyMap())
    val r = v as? JsonObject ?: adminBad("body.rules", "invalid_type")
    if (r.size > 50) adminBad("body.rules", "length")
    r.forEach { (k, e) ->
        if (k.length > 80) adminBad("body.rules/$k", "length")
        if (e !is JsonPrimitive) adminBad("body.rules/$k", "not_a_scalar")
        if (e.isString && e.content.length > 2000) adminBad("body.rules/$k", "length")
    }
    return JsonObject(r.toSortedMap())
}

private fun readTiers(o: JsonObject): List<TierSpec> {
    val v = o["tiers"] as? JsonArray ?: adminBad("body.tiers", "not_an_array")
    if (v.size > 10) adminBad("body.tiers", "out_of_range")
    val out = v.mapIndexed { i, e ->
        val t = e as? JsonObject ?: adminBad("body.tiers[$i]", "not_an_object")
        t.only(setOf("tier_code", "sort_order", "label_en", "label_bn", "threshold", "threshold_basis"))
        val code = t.str("tier_code", 2, 41, required = true)!!.also { if (!TIER_CODE.matches(it)) adminBad("body.tiers[$i].tier_code", "pattern") }
        val order = (t.lint("sort_order", 1, 10, "body.tiers[$i].sort_order") ?: adminBad("body.tiers[$i].sort_order", "required")).toInt()
        val labelEn = t.str("label_en", 1, 80, required = true)!!
        val labelBn = t.str("label_bn", 0, 80, required = false, nullable = true)
        t["threshold_basis"]?.let { b -> if ((b as? JsonPrimitive)?.takeIf { it.isString }?.content != "std") adminBad("body.tiers[$i].threshold_basis", "not_in_enum") }
        val th = t["threshold"]?.let { x ->
            if (x is JsonNull) null else {
                val s = (x as? JsonPrimitive)?.takeIf { it.isString }?.content ?: adminBad("body.tiers[$i].threshold", "not_a_decimal")
                if (!THRESHOLD_RE.matches(s)) adminBad("body.tiers[$i].threshold", "not_a_decimal")
                BigDecimal(s)
            }
        }
        TierSpec(code, order, labelEn, labelBn, th)
    }
    if (out.map { it.code }.toSet().size != out.size) adminBad("body.tiers", "duplicate_tier_code")
    if (out.map { it.order }.toSet().size != out.size) adminBad("body.tiers", "duplicate_sort_order")
    // Thresholds rise with the tier (brief s7): among the tiers that have one, strictly, in sort order. Never negative (the pattern has no minus sign).
    out.sortedBy { it.order }.mapNotNull { it.threshold }.zipWithNext().forEach { (a, b) -> if (b.compareTo(a) <= 0) adminBad("body.tiers", "thresholds_not_rising") }
    return out.sortedBy { it.order }
}

private fun readPairs(o: JsonObject, k: String, max: Int, idKey: String, kindKey: String, kinds: Set<String>): List<Pair<String, Long>> {
    val v = o[k] as? JsonArray ?: adminBad("body.$k", "not_an_array")
    if (v.size > max) adminBad("body.$k", "out_of_range")
    val out = LinkedHashSet<Pair<String, Long>>()
    v.forEachIndexed { i, e ->
        val x = e as? JsonObject ?: adminBad("body.$k[$i]", "not_an_object")
        x.only(setOf(kindKey, idKey))
        val kind = x.enum(kindKey, kinds, required = true)!!
        val id = x.id(idKey, required = true)!!
        if (!out.add(kind to id)) adminBad("body.$k[$i]", "duplicate")
    }
    return out.sortedWith(compareBy({ it.first }, { it.second }))
}

/** Reads the definition members present in [o] over [base] (null base = a create: name, window are required, the rest defaults to empty). */
private fun readSpec(o: JsonObject, base: ProgSpec?): ProgSpec {
    fun has(k: String) = o.containsKey(k)
    val nameEn = if (has("name_en")) o.str("name_en", 1, 120, required = true)!! else base?.nameEn ?: adminBad("body.name_en", "required")
    val nameBn = if (has("name_bn")) o.str("name_bn", 0, 120, required = false, nullable = true) else base?.nameBn
    val period = if (has("period_label")) o.str("period_label", 0, 40, required = false, nullable = true) else base?.period
    val from = if (has("active_from")) o.ldate("active_from") ?: adminBad("body.active_from", "required") else base?.from ?: adminBad("body.active_from", "required")
    val to = if (has("active_to")) o.ldate("active_to") ?: adminBad("body.active_to", "required") else base?.to ?: adminBad("body.active_to", "required")
    val expire = if (has("points_expire_on")) o.ldate("points_expire_on") else base?.expire
    val rules = if (has("rules")) readRules(o) else base?.rules ?: JsonObject(emptyMap())
    val tiers = if (has("tiers")) readTiers(o) else base?.tiers ?: emptyList()
    val qual = if (has("qualifying")) readPairs(o, "qualifying", 100, "product_id", "product_level", LOY_PRODUCT_LEVELS) else base?.qualifying ?: emptyList()
    val scope = if (has("scope")) readPairs(o, "scope", 200, "node_id", "node_type", LOY_SCOPE_TYPES) else base?.scope ?: emptyList()
    if (to.isBefore(from)) adminBad("body.active_to", "before_active_from")
    if (expire != null && expire.isBefore(to)) adminBad("body.points_expire_on", "before_active_to")
    if (scope.any { it.first == "channel" }) adminBad("body.scope", "channel_not_supported")   // no id exists for a channel yet (as for discounts)
    return ProgSpec(nameEn, nameBn, period, from, to, expire, rules, tiers, qual, scope)
}

private fun refsExist(h: Handle, s: ProgSpec) {
    s.qualifying.forEachIndexed { i, (level, id) ->
        val ok = if (level == "sku") h.lexists("SELECT 1 FROM app.sku WHERE id = :i", id)
        else h.createQuery("SELECT 1 FROM app.product_node WHERE id = :i AND level = :l").bind("i", id).bind("l", level).mapTo(Int::class.java).findOne().isPresent
        if (!ok) adminBad("body.qualifying[$i]", "not_found")
    }
    s.scope.forEachIndexed { i, (type, id) ->
        // A closed choice of literal statements: no text is ever put into SQL.
        val exists = when (type) {
            "wing" -> h.lexists("SELECT 1 FROM app.wing WHERE id = :i", id)
            "division" -> h.lexists("SELECT 1 FROM app.division WHERE id = :i", id)
            "territory" -> h.lexists("SELECT 1 FROM app.territory WHERE id = :i", id)
            "zone" -> h.lexists("SELECT 1 FROM app.zone WHERE id = :i", id)
            "route" -> h.lexists("SELECT 1 FROM app.route WHERE id = :i", id)
            "sub_channel" -> h.lexists("SELECT 1 FROM app.sub_channel WHERE id = :i", id)
            else -> adminBad("body.scope[$i]", "channel_not_supported")
        }
        if (!exists) adminBad("body.scope[$i]", "not_found")
    }
}

private fun loyReason(o: JsonObject, key: String = "change_reason"): String {
    val t = o.str(key, 1, 2000, required = true)!!.trim()
    if (t.length < 10 || t.length > 500) adminBad("body.$key", "length")
    return t
}

private fun loyRequestUuid(o: JsonObject, key: String = "request_uuid"): UUID {
    val s = o.str(key, 1, 64, required = true)!!
    if (!LOY_UUID_V4.matches(s)) adminBad("body.$key", "not_a_uuid")
    return UUID.fromString(s)
}

private fun canon(e: JsonElement): String = when (e) {
    is JsonObject -> e.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "${it.key}:${canon(it.value)}" }
    is JsonArray -> e.map { canon(it) }.sorted().joinToString(";", "[", "]")
    is JsonPrimitive -> e.toString()
}

/** Canonical text of the SUPPLIED members of a request (not the merged result): a replay is told from a reused UUID by it. Reason trimmed. */
private fun fingerprint(kind: String, targetId: Long?, o: JsonObject, uuidKey: String = "request_uuid"): String {
    val canonText = buildString {
        append(kind).append('|').append(targetId ?: 0)
        for (k in o.keys.sorted()) {
            if (k == uuidKey) continue
            val v = o.getValue(k)
            append('|').append(k).append('=').append(if (k == "change_reason" || k == "reason") JsonPrimitive((v as? JsonPrimitive)?.content?.trim() ?: v.toString()).toString() else canon(v))
        }
    }
    return MessageDigest.getInstance("SHA-256").digest(canonText.toByteArray()).joinToString("") { "%02x".format(it) }
}

private fun specJson(s: ProgSpec): JsonObject = buildJsonObject {
    put("name_en", s.nameEn); put("name_bn", s.nameBn); put("period_label", s.period); put("active_from", s.from.toString()); put("active_to", s.to.toString())
    put("points_expire_on", s.expire?.toString()); put("rules", s.rules)
    put("tiers", buildJsonArray { s.tiers.forEach { t -> add(buildJsonObject { put("tier_code", t.code); put("sort_order", t.order); put("label_en", t.labelEn); put("label_bn", t.labelBn); put("threshold", t.threshold?.setScale(3)?.toPlainString()) }) } })
    put("qualifying", buildJsonArray { s.qualifying.forEach { add(buildJsonObject { put("product_level", it.first); put("product_id", it.second) }) } })
    put("scope", buildJsonArray { s.scope.forEach { add(buildJsonObject { put("node_type", it.first); put("node_id", it.second) }) } })
}

private fun insertVersion(h: Handle, programmeId: Long, no: Int, s: ProgSpec, reason: String, uuid: UUID, fp: String, actor: AronPrincipal, resultRow: Int): Long {
    val vid = h.createQuery(
        "INSERT INTO app.programme_version (programme_id, version_no, name_en, name_bn, period_label, active_from, active_to, points_expire_on, rules, change_reason, request_uuid, request_fingerprint, " +
            "result_row_version, created_by) VALUES (:p, :n, :ne, :nb, :pl, :af, :at, :ex, CAST(:rules AS jsonb), :why, :u, :fp, :rr, :by) RETURNING id",
    ).bind("p", programmeId).bind("n", no).bind("ne", s.nameEn).bind("nb", s.nameBn as Any?).bind("pl", s.period as Any?).bind("af", s.from).bind("at", s.to).bind("ex", s.expire as Any?)
        .bind("rules", s.rules.toString()).bind("why", reason).bind("u", uuid).bind("fp", fp).bind("rr", resultRow).bind("by", actor.userId).mapTo(Long::class.java).one()
    s.tiers.forEach { t ->
        h.createUpdate("INSERT INTO app.programme_version_tier (programme_version_id, tier_code, sort_order, label_en, label_bn, threshold, threshold_basis) VALUES (:v, :c, :o, :le, :lb, :th, 'std')")
            .bind("v", vid).bind("c", t.code).bind("o", t.order).bind("le", t.labelEn).bind("lb", t.labelBn as Any?).bind("th", t.threshold as Any?).execute()
    }
    s.qualifying.forEach { (level, id) -> h.createUpdate("INSERT INTO app.programme_version_product (programme_version_id, product_level, product_id) VALUES (:v, :l, :i)").bind("v", vid).bind("l", level).bind("i", id).execute() }
    s.scope.forEach { (type, id) -> h.createUpdate("INSERT INTO app.programme_version_scope (programme_version_id, node_type, node_id) VALUES (:v, :t, :i)").bind("v", vid).bind("t", type).bind("i", id).execute() }
    return vid
}

private fun lockRequest(h: Handle, uuid: UUID, ns: String = "programme_req") { h.createQuery("SELECT pg_advisory_xact_lock(hashtext(:k))").bind("k", "$ns:$uuid").mapTo(String::class.java).findOne() }

private class PriorVersion(val versionId: Long, val programmeId: Long, val versionNo: Int, val fingerprint: String, val resultRow: Int?)

private fun priorVersion(h: Handle, uuid: UUID): PriorVersion? = h.createQuery("SELECT id, programme_id, version_no, request_fingerprint, result_row_version FROM app.programme_version WHERE request_uuid = :u").bind("u", uuid)
    .map { rs, _ -> PriorVersion(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getString(4), rs.getObject(5) as Int?) }.findOne().orElse(null)

private fun uuidUsedByStatus(h: Handle, uuid: UUID) = h.createQuery("SELECT 1 FROM app.programme_status_change WHERE request_uuid = :u").bind("u", uuid).mapTo(Int::class.java).findOne().isPresent
private fun uuidUsedByVersion(h: Handle, uuid: UUID) = priorVersion(h, uuid) != null

/** A replay answers the FIRST result: the version that call made, with the programme status and ETag it had right after the call (V0158 result_row_version). */
private fun firstProgramme(h: Handle, prior: PriorVersion, statusAfter: String): ProgrammeView {
    val row = h.createQuery("$PROGRAMME_SELECT WHERE p.id = :o").bind("o", prior.programmeId).map { rs, _ -> rs.prow() }.one()
    val v = h.createQuery(
        "SELECT v.name_en, v.name_bn, v.period_label, v.active_from, v.active_to, v.points_expire_on, CAST(v.rules AS text), v.change_reason FROM app.programme_version v WHERE v.id = :v",
    ).bind("v", prior.versionId).map { rs, _ -> arrayOf<Any?>(rs.getString(1), rs.getString(2), rs.getString(3), rs.getObject(4, LocalDate::class.java), rs.getObject(5, LocalDate::class.java), rs.getObject(6, LocalDate::class.java), rs.getString(7), rs.getString(8)) }.one()
    val first = PRow(
        row.id, row.kind, row.code, v[0] as String, v[1] as String?, v[2] as String?, v[3] as LocalDate, v[4] as LocalDate, v[5] as LocalDate?, v[6] as String, statusAfter,
        prior.resultRow ?: row.version, row.createdAt, row.updatedAt, prior.versionId, prior.versionNo, v[7] as String?,
    )
    return programmeView(first, vextras(h, listOf(prior.versionId)))
}

// ------------------------------------------------------------------------------------------------ writing programmes: handlers

private suspend fun createProgramme(call: ApplicationCall, d: AdminLoyaltyDeps): Pair<ProgrammeView, HttpStatusCode> {
    val p = call.loyaltyPrincipal(Need.WRITE)
    val o = call.bodyObject()
    if (o.containsKey("status")) adminBad("body.status", "use_status_endpoint")
    o.only(RULE_MEMBERS + setOf("request_uuid", "kind", "code", "change_reason"))
    val uuid = loyRequestUuid(o)
    val kind = o.enum("kind", LOY_KINDS, required = true)!!
    val code = o.str("code", 1, 40, required = true)!!.also { if (!CODE_RE.matches(it)) adminBad("body.code", "pattern") }
    val reason = loyReason(o)
    listOf("name_en", "active_from", "active_to").forEach { if (!o.containsKey(it)) adminBad("body.$it", "required") }
    val spec = readSpec(o, null)
    val fp = fingerprint("create", null, o)
    return mapDb {
        d.db.jdbi.inTransaction<Pair<ProgrammeView, HttpStatusCode>, Exception> { h ->
            lockRequest(h, uuid)
            priorVersion(h, uuid)?.let { prior ->
                if (prior.fingerprint != fp || prior.versionNo != 1) throw loyConflict("request_uuid already used for a different call")
                return@inTransaction firstProgramme(h, prior, "draft") to HttpStatusCode.Created
            }
            if (uuidUsedByStatus(h, uuid)) throw loyConflict("request_uuid already used for a status change")
            if (h.createQuery("SELECT 1 FROM app.programme WHERE code = :c").bind("c", code).mapTo(Int::class.java).findOne().isPresent)
                throw ApiProblem(ProblemCode.ERR_MASTER_DUPLICATE_CODE, "a loyalty programme with this code already exists", errors = listOf(FieldError("body.code", "duplicate")))
            refsExist(h, spec)
            val id = h.createQuery(
                "INSERT INTO app.programme (kind, code, name_en, name_bn, period_label, active_from, active_to, points_expire_on, rules, status, created_by, updated_by) " +
                    "VALUES (:k, :c, :ne, :nb, :pl, :af, :at, :ex, CAST(:rules AS jsonb), 'draft', :by, :by) RETURNING id",
            ).bind("k", kind).bind("c", code).bind("ne", spec.nameEn).bind("nb", spec.nameBn as Any?).bind("pl", spec.period as Any?).bind("af", spec.from).bind("at", spec.to).bind("ex", spec.expire as Any?)
                .bind("rules", spec.rules.toString()).bind("by", p.userId).mapTo(Long::class.java).one()
            val vid = insertVersion(h, id, 1, spec, reason, uuid, fp, p, resultRow = 2)   // insert (1) + the link to the first version (2)
            h.createUpdate("UPDATE app.programme SET current_version_id = :v WHERE id = :o").bind("v", vid).bind("o", id).execute()
            val after = JsonObject(specJson(spec) + mapOf("kind" to JsonPrimitive(kind), "code" to JsonPrimitive(code), "status" to JsonPrimitive("draft"), "version_no" to JsonPrimitive(1), "request_uuid" to JsonPrimitive(uuid.toString())))
            AuditWriter.write(h, p, "programme", id.toString(), "create", null, after, reason, call.requestId)
            loadProgramme(h, id)!! to HttpStatusCode.Created
        }
    }
}

private suspend fun updateProgramme(call: ApplicationCall, d: AdminLoyaltyDeps): ProgrammeView {
    val p = call.loyaltyPrincipal(Need.WRITE)
    val id = call.pathId()
    val ifMatch = call.ifMatch()
    val o = call.bodyObject()
    if (o.containsKey("status")) adminBad("body.status", "use_status_endpoint")
    o.only(RULE_MEMBERS + setOf("request_uuid", "change_reason"))      // kind and code are immutable: they are not members of ProgrammePatch (unknown_field, 400)
    val uuid = loyRequestUuid(o)
    val reason = loyReason(o)
    if (o.keys.none { it in RULE_MEMBERS }) adminBad("body", "no_change")
    val fp = fingerprint("patch", id, o)
    return mapDb {
        d.db.jdbi.inTransaction<ProgrammeView, Exception> { h ->
            lockRequest(h, uuid)
            priorVersion(h, uuid)?.let { prior ->
                if (prior.fingerprint != fp || prior.programmeId != id) throw loyConflict("request_uuid already used for a different call")
                return@inTransaction firstProgramme(h, prior, "draft")
            }
            if (uuidUsedByStatus(h, uuid)) throw loyConflict("request_uuid already used for a status change")
            val cur = loadProgramme(h, id, lock = true) ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no loyalty programme with this id")
            if (cur.status != "draft") throw ApiProblem(ProblemCode.ERR_REQUEST_STATE, "only a draft can change; an ${cur.status} programme is final (create a new one)")
            if (cur.version != ifMatch) preconditionFailed(cur.version)
            val before = cur.spec ?: throw ApiProblem(ProblemCode.ERR_REQUEST_STATE, "this programme has no version")
            val spec = readSpec(o, before)
            if (spec == before) adminBad("body", "no_change")
            refsExist(h, spec)
            val no = h.createQuery("SELECT max(version_no) + 1 FROM app.programme_version WHERE programme_id = :o").bind("o", id).mapTo(Int::class.java).one()
            val vid = insertVersion(h, id, no, spec, reason, uuid, fp, p, resultRow = cur.version + 1)   // the one update that follows adds 1
            // Writer rule: the version is copied into app.programme in the same transaction, while the programme is a draft.
            h.createUpdate(
                "UPDATE app.programme SET name_en = :ne, name_bn = :nb, period_label = :pl, active_from = :af, active_to = :at, points_expire_on = :ex, rules = CAST(:rules AS jsonb), " +
                    "current_version_id = :v, updated_by = :by WHERE id = :o",
            ).bind("ne", spec.nameEn).bind("nb", spec.nameBn as Any?).bind("pl", spec.period as Any?).bind("af", spec.from).bind("at", spec.to).bind("ex", spec.expire as Any?)
                .bind("rules", spec.rules.toString()).bind("v", vid).bind("by", p.userId).bind("o", id).execute()
            AuditWriter.write(
                h, p, "programme", id.toString(), "new_version",
                JsonObject(specJson(before) + mapOf("version_no" to JsonPrimitive(cur.json.getValue("version_no").toString().toInt()))),
                JsonObject(specJson(spec) + mapOf("version_no" to JsonPrimitive(no), "request_uuid" to JsonPrimitive(uuid.toString()))), reason, call.requestId,
            )
            loadProgramme(h, id)!!
        }
    }
}

private suspend fun setProgrammeStatus(call: ApplicationCall, d: AdminLoyaltyDeps): ProgrammeView {
    val p = call.loyaltyPrincipal(Need.WRITE)
    val id = call.pathId()
    val o = call.bodyObject()
    o.only(setOf("request_uuid", "to_status", "reason"))
    val uuid = loyRequestUuid(o)
    val to = o.enum("to_status", setOf("active", "inactive"), required = true)!!
    val reason = loyReason(o, "reason")
    // A replay of a stored request_uuid is answered BEFORE If-Match is parsed or judged (a retry that carries a stale or even malformed ETag still gets the first result).
    val ifMatchRaw: String? = call.request.headers["If-Match"]
    // Activation is the owner's decision (brief s10 Q3, as for prices and discounts): SUPERADMIN only. Ending is a write like any other.
    if (to == "active" && p.role != Role.SUPERADMIN) throw loyForbidden("only a SUPERADMIN activates a loyalty programme")
    val fp = fingerprint("status", id, o)
    return mapDb {
        d.db.jdbi.inTransaction<ProgrammeView, Exception> { h ->
            lockRequest(h, uuid)
            h.createQuery("SELECT programme_id, request_fingerprint, to_status, result_row_version FROM app.programme_status_change WHERE request_uuid = :u").bind("u", uuid)
                .map { rs, _ -> arrayOf<Any?>(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getObject(4) as Int?) }.findOne().orElse(null)?.let { r ->
                    if (r[0] != id || r[1] != fp) throw loyConflict("request_uuid already used for a different call")
                    return@inTransaction loadProgramme(h, id)!!.let { cur -> (r[3] as Int?)?.let { rv -> replayStatus(h, id, r[2] as String, rv) } ?: cur }
                }
            if (uuidUsedByVersion(h, uuid)) throw loyConflict("request_uuid already used for a create or an update")
            // An If-Match is optional here (the move is judged by the status itself); when given it must be well formed and the programme's current row version.
            val ifMatchGiven: Int? = ifMatchRaw?.let { call.ifMatch() }
            val cur = loadProgramme(h, id, lock = true) ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no loyalty programme with this id")
            ifMatchGiven?.let { if (it != cur.version) preconditionFailed(cur.version) }
            val allowed = (cur.status == "draft" && to == "active") || (cur.status == "active" && to == "inactive")
            if (!allowed) throw ApiProblem(ProblemCode.ERR_REQUEST_STATE, "a ${cur.status} programme cannot move to $to (allowed: draft to active, active to inactive)")
            if (to == "active") {
                if (cur.currentVid == null) throw ApiProblem(ProblemCode.ERR_REQUEST_STATE, "this programme has no version to activate")
                if (cur.activeTo.isBefore(today(d)))
                    throw ApiProblem(ProblemCode.ERR_VALIDATION, "the programme ended on ${cur.activeTo}; make a new version with a later active_to first", errors = listOf(FieldError("body.active_to", "past")))
            }
            h.createUpdate("INSERT INTO app.programme_status_change (programme_id, from_status, to_status, request_uuid, request_fingerprint, reason, result_row_version, actor) VALUES (:o, :f, :t, :u, :fp, :r, :rr, :a)")
                .bind("o", id).bind("f", cur.status).bind("t", to).bind("u", uuid).bind("fp", fp).bind("r", reason).bind("rr", cur.version + 1).bind("a", p.userId).execute()
            h.createUpdate("UPDATE app.programme SET status = :t, updated_by = :by WHERE id = :o").bind("t", to).bind("by", p.userId).bind("o", id).execute()
            AuditWriter.write(
                h, p, "programme", id.toString(), if (to == "active") "activate" else "end",
                auditObj("status" to cur.status), auditObj("status" to to, "request_uuid" to uuid.toString()), reason, call.requestId,
            )
            loadProgramme(h, id)!!
        }
    }
}

/** The programme as it was right after a stored status move: the status that move set and the row version it produced (the version/definition is unchanged by a move). */
private fun replayStatus(h: Handle, id: Long, statusAfter: String, rowVersion: Int): ProgrammeView {
    val row = h.createQuery("$PROGRAMME_SELECT WHERE p.id = :o").bind("o", id).map { rs, _ -> rs.prow() }.one()
    val first = PRow(row.id, row.kind, row.code, row.nameEn, row.nameBn, row.period, row.from, row.to, row.expire, row.rules, statusAfter, rowVersion, row.createdAt, row.updatedAt, row.currentVid, row.no, row.reason)
    return programmeView(first, vextras(h, listOfNotNull(row.currentVid)))
}

// ------------------------------------------------------------------------------------------------ enrolments

private fun listEnrolments(call: ApplicationCall, d: AdminLoyaltyDeps): EnrolmentPageOut {
    val p = call.loyaltyPrincipal(Need.READ_ENROLMENTS)
    val id = call.pathId(); val limit = call.pageLimit(); val cursor = call.pageCursor(); val zone = call.queryId("zone_id")
    return d.db.jdbi.withHandle<EnrolmentPageOut, Exception> { h ->
        val vis = loyVisibility(h, p, d)
        val pv = loyVisSql(vis, "v")
        h.createQuery("SELECT p.id FROM app.programme p LEFT JOIN app.programme_version v ON v.id = p.current_version_id WHERE p.id = :o" + (pv?.let { " AND " + it.sql } ?: "")).bind("o", id).bindLVis(pv)
            .mapTo(Long::class.java).findOne().orElse(null) ?: throw noProgramme()
        val where = mutableListOf("e.programme_id = :p")
        if (cursor != null) where += "e.id < :cursor"
        if (zone != null) where += "o.zone_id = :zone"
        val geo = vis as? LVis.Geo
        if (geo != null) {
            if (zone != null && zone !in geo.zones &&
                h.createQuery("SELECT 1 FROM app.route WHERE zone_id = :z AND id IN (<vr>)").bind("z", zone).bindList("vr", geo.routes.ifEmpty { listOf(-1L) }).mapTo(Int::class.java).findOne().isEmpty
            ) throw loyForbidden("zone $zone is outside your reach")
            where += "(o.zone_id IN (<vz>) OR o.route_id IN (<vr>))"
        } else if (vis == LVis.Nobody) where += "FALSE"
        val q = h.createQuery(
            "SELECT e.id, e.programme_id, e.outlet_id, e.league_label, e.tier_code, e.base_target, e.valid_from, e.valid_to FROM app.programme_enrolment e JOIN app.outlet o ON o.id = e.outlet_id " +
                "WHERE ${where.joinToString(" AND ")} ORDER BY e.id DESC LIMIT :lim",
        ).bind("p", id).bind("lim", limit + 1)
        if (cursor != null) q.bind("cursor", cursor)
        if (zone != null) q.bind("zone", zone)
        if (geo != null) { q.bindList("vz", geo.zones.ifEmpty { listOf(-1L) }); q.bindList("vr", geo.routes.ifEmpty { listOf(-1L) }) }
        val rows = q.map { rs, _ ->
            rs.getLong(1) to EnrolmentOut(rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getBigDecimal(6)?.toPlainString(), rs.getObject(7, LocalDate::class.java).toString(), rs.getObject(8, LocalDate::class.java)?.toString())
        }.list()
        val page = rows.take(limit)
        EnrolmentPageOut(page.map { it.second }, if (rows.size > limit) page.last().first.toString() else null)
    }
}

private class EnrolRow(val outlet: Long, val league: String?, val tier: String?, val target: BigDecimal?, val from: LocalDate, val to: LocalDate?)

private fun readEnrolRows(o: JsonObject, programmeId: Long): List<EnrolRow> {
    val a = o["rows"] as? JsonArray ?: adminBad("body.rows", "required")
    if (a.isEmpty() || a.size > MAX_BATCH_ROWS) adminBad("body.rows", "out_of_range")
    val seen = HashSet<Pair<Long, LocalDate>>()
    return a.mapIndexed { i, e ->
        val r = e as? JsonObject ?: adminBad("body.rows[$i]", "not_an_object")
        r.only(setOf("programme_id", "outlet_id", "league_label", "tier_code", "base_target", "valid_from", "valid_to"))
        val pid = r.id("programme_id", required = true)!!
        if (pid != programmeId) adminBad("body.rows[$i].programme_id", "path_mismatch")
        val outlet = r.id("outlet_id", required = true)!!
        val league = r.str("league_label", 0, 40, required = false, nullable = true)?.ifEmpty { null }
        val tier = r.str("tier_code", 0, 41, required = false, nullable = true)?.ifEmpty { null }?.also { if (!TIER_CODE.matches(it)) adminBad("body.rows[$i].tier_code", "pattern") }
        val target = r["base_target"]?.let { x ->
            if (x is JsonNull) null else {
                val s = (x as? JsonPrimitive)?.takeIf { it.isString }?.content ?: adminBad("body.rows[$i].base_target", "not_a_decimal")
                if (!DECIMAL3_RE.matches(s)) adminBad("body.rows[$i].base_target", "not_a_decimal")
                BigDecimal(s).also { if (it.signum() < 0) adminBad("body.rows[$i].base_target", "negative") }   // the -37,500% bug: targets are never negative
            }
        }
        val from = r.ldate("valid_from") ?: adminBad("body.rows[$i].valid_from", "required")
        val to = r.ldate("valid_to")
        if (to != null && !to.isAfter(from)) adminBad("body.rows[$i].valid_to", "before_valid_from")      // exclusive end, as the table CHECK
        if (!seen.add(outlet to from)) adminBad("body.rows[$i]", "duplicate")
        EnrolRow(outlet, league, tier, target, from, to)
    }
}

private suspend fun putEnrolments(call: ApplicationCall, d: AdminLoyaltyDeps): BatchResultOut {
    val p = call.loyaltyPrincipal(Need.WRITE)
    val id = call.pathId()
    val o = call.bodyObject()
    o.only(setOf("batch_uuid", "rows", "change_reason"))
    val batch = loyRequestUuid(o, "batch_uuid")
    val reason = loyReason(o)
    val rows = readEnrolRows(o, id)
    val fp = fingerprint("enrol", id, o, "batch_uuid")
    return mapDb {
        d.db.jdbi.inTransaction<BatchResultOut, Exception> { h ->
            lockRequest(h, batch, "enrol_batch")
            h.createQuery("SELECT programme_id, request_fingerprint, inserted, updated, unchanged FROM app.programme_enrolment_batch WHERE batch_uuid = :b").bind("b", batch)
                .map { rs, _ -> arrayOf<Any>(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)) }.findOne().orElse(null)?.let { r ->
                    if (r[0] != id || r[1] != fp) throw loyConflict("batch_uuid already used for a different batch", "body.batch_uuid")
                    return@inTransaction BatchResultOut(batch.toString(), r[2] as Int, r[3] as Int, r[4] as Int)
                }
            val prog = loadProgramme(h, id, lock = true) ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no loyalty programme with this id")
            if (prog.status == "inactive") throw ApiProblem(ProblemCode.ERR_REQUEST_STATE, "an ended programme takes no new enrolments")
            rows.forEachIndexed { i, r ->
                if (r.tier != null && prog.tierCodes.isNotEmpty() && r.tier !in prog.tierCodes) adminBad("body.rows[$i].tier_code", "unknown_tier")
            }
            val known = h.createQuery("SELECT id FROM app.outlet WHERE id IN (<ids>)").bindList("ids", rows.map { it.outlet }.distinct()).mapTo(Long::class.java).list().toSet()
            rows.forEachIndexed { i, r -> if (r.outlet !in known) adminBad("body.rows[$i].outlet_id", "not_found") }
            var inserted = 0; var updated = 0; var unchanged = 0
            for (r in rows) {
                val cur = h.createQuery("SELECT id, league_label, tier_code, base_target, valid_to FROM app.programme_enrolment WHERE programme_id = :p AND outlet_id = :o AND valid_from = :f").bind("p", id).bind("o", r.outlet).bind("f", r.from)
                    .map { rs, _ -> arrayOf<Any?>(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getObject(5, LocalDate::class.java)) }.findOne().orElse(null)
                if (cur == null) {
                    h.createUpdate("INSERT INTO app.programme_enrolment (programme_id, outlet_id, league_label, tier_code, base_target, valid_from, valid_to, created_by) VALUES (:p, :o, :l, :t, :b, :f, :to, :by)")
                        .bind("p", id).bind("o", r.outlet).bind("l", r.league as Any?).bind("t", r.tier as Any?).bind("b", r.target as Any?).bind("f", r.from).bind("to", r.to as Any?).bind("by", p.userId).execute()
                    inserted++
                } else {
                    val curTarget = cur[3] as BigDecimal?
                    val sameTarget = (curTarget == null && r.target == null) || (curTarget != null && r.target != null && curTarget.compareTo(r.target) == 0)
                    val same = cur[1] == r.league && cur[2] == r.tier && sameTarget && cur[4] == r.to
                    if (same) unchanged++ else {
                        h.createUpdate("UPDATE app.programme_enrolment SET league_label = :l, tier_code = :t, base_target = :b, valid_to = :to WHERE id = :i")
                            .bind("l", r.league as Any?).bind("t", r.tier as Any?).bind("b", r.target as Any?).bind("to", r.to as Any?).bind("i", cur[0] as Long).execute()
                        updated++
                    }
                }
            }
            h.createUpdate("INSERT INTO app.programme_enrolment_batch (batch_uuid, programme_id, request_fingerprint, row_count, inserted, updated, unchanged, reason, actor) VALUES (:b, :p, :fp, :n, :i, :u, :c, :r, :a)")
                .bind("b", batch).bind("p", id).bind("fp", fp).bind("n", rows.size).bind("i", inserted).bind("u", updated).bind("c", unchanged).bind("r", reason).bind("a", p.userId).execute()
            AuditWriter.write(h, p, "programme", id.toString(), "enrol", null, auditObj("batch_uuid" to batch.toString(), "rows" to rows.size, "inserted" to inserted, "updated" to updated, "unchanged" to unchanged), reason, call.requestId)
            BatchResultOut(batch.toString(), inserted, updated, unchanged)
        }
    }
}

// ------------------------------------------------------------------------------------------------ gifts

private const val GIFT_SELECT = "SELECT g.id, g.programme_id, g.code, g.name_en, g.name_bn, g.points_cost, g.tier_codes, g.status, g.version, g.image_url, a.blob_path " +
    "FROM app.gift g JOIN app.programme p ON p.id = g.programme_id LEFT JOIN app.programme_version v ON v.id = p.current_version_id LEFT JOIN app.admin_asset a ON a.asset_id = g.image_asset_id"

private fun giftOf(rs: java.sql.ResultSet, d: AdminLoyaltyDeps) = GiftOut(
    rs.getLong("id"), rs.getLong("programme_id"), rs.getString("code"), rs.getString("name_en"), rs.getString("name_bn"), rs.getObject("points_cost") as Int?,
    (rs.getArray("tier_codes").array as Array<*>).map { it.toString() }, rs.getString("blob_path")?.let { runCatching { d.blob.readUrl(it) }.getOrNull() } ?: rs.getString("image_url"),
    rs.getString("status"), rs.getInt("version"),
)

private fun loadGift(h: Handle, d: AdminLoyaltyDeps, id: Long, vis: LVisSql? = null, lock: Boolean = false): GiftOut? =
    h.createQuery("$GIFT_SELECT WHERE g.id = :g" + (vis?.let { " AND " + it.sql } ?: "") + if (lock) " FOR UPDATE OF g" else "").bind("g", id).bindLVis(vis).map { rs, _ -> giftOf(rs, d) }.findOne().orElse(null)

private fun listGifts(call: ApplicationCall, d: AdminLoyaltyDeps): GiftListOut {
    val p = call.loyaltyPrincipal(Need.READ)
    val programme = call.queryId("programme_id"); val status = call.queryStatus()
    return d.db.jdbi.withHandle<GiftListOut, Exception> { h ->
        val vis = loyVisSql(loyVisibility(h, p, d), "v")
        val where = mutableListOf("TRUE")
        if (programme != null) where += "g.programme_id = :prog"
        if (status != null) where += "g.status = :status"
        if (vis != null) where += vis.sql
        val q = h.createQuery("$GIFT_SELECT WHERE ${where.joinToString(" AND ")} ORDER BY g.id DESC LIMIT :lim").bind("lim", MAX_LIST)
        if (programme != null) q.bind("prog", programme)
        if (status != null) q.bind("status", status)
        GiftListOut(q.bindLVis(vis).map { rs, _ -> giftOf(rs, d) }.list())
    }
}

private fun getGift(call: ApplicationCall, d: AdminLoyaltyDeps): GiftOut {
    val p = call.loyaltyPrincipal(Need.READ)
    val id = call.pathId()
    return d.db.jdbi.withHandle<GiftOut, Exception> { h -> loadGift(h, d, id, loyVisSql(loyVisibility(h, p, d), "v")) ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no gift with this id in your reach") }
}

private fun currentTierCodes(h: Handle, programmeId: Long): Set<String> =
    h.createQuery("SELECT t.tier_code FROM app.programme_version_tier t JOIN app.programme p ON p.current_version_id = t.programme_version_id WHERE p.id = :p").bind("p", programmeId).mapTo(String::class.java).list().toSet()

private fun readTierCodes(o: JsonObject): List<String> {
    val a = o["tier_codes"] as? JsonArray ?: adminBad("body.tier_codes", "not_an_array")
    if (a.size > 10) adminBad("body.tier_codes", "out_of_range")
    val out = a.mapIndexed { i, e -> (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.also { if (!TIER_CODE.matches(it)) adminBad("body.tier_codes[$i]", "pattern") } ?: adminBad("body.tier_codes[$i]", "invalid_type") }
    if (out.toSet().size != out.size) adminBad("body.tier_codes", "duplicate")
    return out
}

private fun readImageAsset(o: JsonObject): String? = o.str("image_asset_id", 36, 36, required = false, nullable = true)?.also { if (!LOY_UUID_V4.matches(it)) adminBad("body.image_asset_id", "not_a_uuid") }

private fun checkGiftImage(h: Handle, asset: String) {
    val purpose = h.createQuery("SELECT purpose FROM app.admin_asset WHERE asset_id = CAST(:a AS uuid)").bind("a", asset).mapTo(String::class.java).findOne().orElse(null) ?: adminBad("body.image_asset_id", "unknown_asset")
    if (purpose != "gift_image") adminBad("body.image_asset_id", "wrong_purpose")
}

/**
 * The first result of a gift call, as stored in the append-only journal: the gift with `image_url` NULL plus the `image_asset_id` it was made with. A signed
 * read URL never goes into a journal row (it expires, and an append-only row could never lose it); the URL is resolved again whenever the gift is answered.
 */
private fun giftSnapshotJson(g: GiftOut, asset: String?): String =
    JsonObject(Json.encodeToJsonElement(GiftOut.serializer(), g.copy(image_url = null)).let { it as JsonObject } + mapOf("image_asset_id" to (asset?.let { JsonPrimitive(it) } ?: JsonNull))).toString()

private class PriorGift(val giftId: Long, val fingerprint: String, val kind: String, val gift: GiftOut, val asset: String?)

/** A stored first result answered again: the picture URL is resolved now from the asset. */
private fun resolvedGift(h: Handle, d: AdminLoyaltyDeps, prior: PriorGift): GiftOut {
    val url = prior.asset?.let { a ->
        h.createQuery("SELECT blob_path FROM app.admin_asset WHERE asset_id = CAST(:a AS uuid)").bind("a", a).mapTo(String::class.java).findOne().orElse(null)?.let { runCatching { d.blob.readUrl(it) }.getOrNull() }
    }
    return prior.gift.copy(image_url = url)
}

private fun giftAudit(g: GiftOut, asset: String?) = buildJsonObject {
    put("programme_id", g.programme_id); put("code", g.code); put("name_en", g.name_en); put("name_bn", g.name_bn); put("points_cost", g.points_cost)
    put("tier_codes", buildJsonArray { g.tier_codes.forEach { add(JsonPrimitive(it)) } }); put("status", g.status); put("image_asset_id", asset)
}

private fun giftAssetOf(h: Handle, id: Long): String? = h.createQuery("SELECT image_asset_id::text FROM app.gift WHERE id = :g").bind("g", id).mapTo(String::class.java).findOne().orElse(null)

private val SnapshotJson = Json { ignoreUnknownKeys = true }

private fun priorGift(h: Handle, uuid: UUID): PriorGift? =
    h.createQuery("SELECT gift_id, request_fingerprint, change_kind, CAST(snapshot AS text) FROM app.gift_change WHERE request_uuid = :u").bind("u", uuid)
        .map { rs, _ ->
            val text = rs.getString(4)
            val asset = (Json.parseToJsonElement(text) as JsonObject)["image_asset_id"]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            PriorGift(rs.getLong(1), rs.getString(2), rs.getString(3), SnapshotJson.decodeFromString(GiftOut.serializer(), text), asset)
        }.findOne().orElse(null)

private suspend fun createGift(call: ApplicationCall, d: AdminLoyaltyDeps): Pair<GiftOut, HttpStatusCode> {
    val p = call.loyaltyPrincipal(Need.SUPER)
    val o = call.bodyObject()
    o.only(setOf("request_uuid", "programme_id", "code", "name_en", "name_bn", "points_cost", "tier_codes", "image_asset_id", "status", "change_reason"))
    val uuid = loyRequestUuid(o)
    val programmeId = o.id("programme_id", required = true)!!
    val code = o.str("code", 2, 41, required = true)!!.also { if (!GIFT_CODE.matches(it)) adminBad("body.code", "pattern") }
    val nameEn = o.str("name_en", 1, 120, required = true)!!
    val nameBn = o.str("name_bn", 0, 120, required = false, nullable = true)
    val cost = o.lint("points_cost", 1, 100_000, nullable = true)?.toInt()
    val tiers = if (o.containsKey("tier_codes")) readTierCodes(o) else emptyList()
    val asset = readImageAsset(o)
    val status = if (o.containsKey("status")) o.enum("status", setOf("active", "inactive"), required = true)!! else "active"
    val reason = loyReason(o)
    val fp = fingerprint("gift_create", null, o)
    return mapDb {
        d.db.jdbi.inTransaction<Pair<GiftOut, HttpStatusCode>, Exception> { h ->
            lockRequest(h, uuid, "gift_req")
            priorGift(h, uuid)?.let { prior ->
                if (prior.fingerprint != fp || prior.kind != "create") throw loyConflict("request_uuid already used for a different call")
                return@inTransaction resolvedGift(h, d, prior) to HttpStatusCode.Created
            }
            val prog = loadProgramme(h, programmeId, lock = true) ?: adminBad("body.programme_id", "not_found")
            if (prog.status == "inactive") throw ApiProblem(ProblemCode.ERR_REQUEST_STATE, "an ended programme takes no new gifts")
            if (prog.tierCodes.isNotEmpty()) tiers.forEachIndexed { i, t -> if (t !in prog.tierCodes) adminBad("body.tier_codes[$i]", "unknown_tier") }
            asset?.let { checkGiftImage(h, it) }
            if (h.createQuery("SELECT 1 FROM app.gift WHERE programme_id = :p AND code = :c").bind("p", programmeId).bind("c", code).mapTo(Int::class.java).findOne().isPresent)
                throw ApiProblem(ProblemCode.ERR_MASTER_DUPLICATE_CODE, "a gift with this code already exists in the programme", errors = listOf(FieldError("body.code", "duplicate")))
            val id = h.createQuery(
                "INSERT INTO app.gift (programme_id, code, name_en, name_bn, points_cost, tier_codes, status, image_asset_id) VALUES (:p, :c, :ne, :nb, :pc, CAST(:tc AS text[]), :st, CAST(:img AS uuid)) RETURNING id",
            ).bind("p", programmeId).bind("c", code).bind("ne", nameEn).bind("nb", nameBn as Any?).bind("pc", cost as Any?).bind("tc", "{" + tiers.joinToString(",") + "}").bind("st", status).bind("img", asset as Any?).mapTo(Long::class.java).one()
            val g = loadGift(h, d, id)!!
            h.createUpdate("INSERT INTO app.gift_change (gift_id, change_kind, request_uuid, request_fingerprint, reason, snapshot, result_row_version, actor) VALUES (:g, 'create', :u, :fp, :r, CAST(:s AS jsonb), :rv, :a)")
                .bind("g", id).bind("u", uuid).bind("fp", fp).bind("r", reason).bind("s", giftSnapshotJson(g, asset)).bind("rv", g.version).bind("a", p.userId).execute()
            AuditWriter.write(h, p, "gift", id.toString(), "create", null, JsonObject(giftAudit(g, asset) + mapOf("request_uuid" to JsonPrimitive(uuid.toString()))), reason, call.requestId)
            g to HttpStatusCode.Created
        }
    }
}

private suspend fun updateGift(call: ApplicationCall, d: AdminLoyaltyDeps): GiftOut {
    val p = call.loyaltyPrincipal(Need.SUPER)
    val id = call.pathId()
    val ifMatch = call.ifMatch()
    val o = call.bodyObject()
    o.only(setOf("request_uuid", "name_en", "name_bn", "points_cost", "tier_codes", "image_asset_id", "status", "change_reason"))   // code and programme_id are immutable (not members of GiftPatch)
    val uuid = loyRequestUuid(o)
    val reason = loyReason(o)
    val members = setOf("name_en", "name_bn", "points_cost", "tier_codes", "image_asset_id", "status")
    if (o.keys.none { it in members }) adminBad("body", "no_change")
    val fp = fingerprint("gift_patch", id, o)
    val nameEn = if (o.containsKey("name_en")) o.str("name_en", 1, 120, required = true)!! else null
    val hasBn = o.containsKey("name_bn"); val nameBn = if (hasBn) o.str("name_bn", 0, 120, required = false, nullable = true) else null
    val hasCost = o.containsKey("points_cost"); val cost = if (hasCost) o.lint("points_cost", 1, 100_000, nullable = true)?.toInt() else null
    val tiers = if (o.containsKey("tier_codes")) readTierCodes(o) else null
    val hasAsset = o.containsKey("image_asset_id"); val asset = if (hasAsset) readImageAsset(o) else null
    val status = if (o.containsKey("status")) o.enum("status", setOf("active", "inactive"), required = true)!! else null
    return mapDb {
        d.db.jdbi.inTransaction<GiftOut, Exception> { h ->
            lockRequest(h, uuid, "gift_req")
            priorGift(h, uuid)?.let { prior ->
                if (prior.fingerprint != fp || prior.giftId != id || prior.kind != "update") throw loyConflict("request_uuid already used for a different call")
                return@inTransaction resolvedGift(h, d, prior)
            }
            val cur = loadGift(h, d, id, lock = true) ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no gift with this id")
            if (cur.version != ifMatch) preconditionFailed(cur.version)
            val curAsset = giftAssetOf(h, id)
            val next = GiftOut(
                cur.gift_id, cur.programme_id, cur.code, nameEn ?: cur.name_en, if (hasBn) nameBn else cur.name_bn, if (hasCost) cost else cur.points_cost, tiers ?: cur.tier_codes, cur.image_url, status ?: cur.status, cur.version,
            )
            val nextAsset = if (hasAsset) asset else curAsset
            if (next.name_en == cur.name_en && next.name_bn == cur.name_bn && next.points_cost == cur.points_cost && next.tier_codes == cur.tier_codes && next.status == cur.status && nextAsset == curAsset) adminBad("body", "no_change")
            if (tiers != null) currentTierCodes(h, cur.programme_id).let { all -> if (all.isNotEmpty()) tiers.forEachIndexed { i, t -> if (t !in all) adminBad("body.tier_codes[$i]", "unknown_tier") } }
            if (hasAsset && asset != null && asset != curAsset) checkGiftImage(h, asset)
            h.createUpdate("UPDATE app.gift SET name_en = :ne, name_bn = :nb, points_cost = :pc, tier_codes = CAST(:tc AS text[]), status = :st, image_asset_id = CAST(:img AS uuid) WHERE id = :g")
                .bind("ne", next.name_en).bind("nb", next.name_bn as Any?).bind("pc", next.points_cost as Any?).bind("tc", "{" + next.tier_codes.joinToString(",") + "}").bind("st", next.status).bind("img", nextAsset as Any?).bind("g", id).execute()
            val after = loadGift(h, d, id)!!
            h.createUpdate("INSERT INTO app.gift_change (gift_id, change_kind, request_uuid, request_fingerprint, reason, snapshot, result_row_version, actor) VALUES (:g, 'update', :u, :fp, :r, CAST(:s AS jsonb), :rv, :a)")
                .bind("g", id).bind("u", uuid).bind("fp", fp).bind("r", reason).bind("s", giftSnapshotJson(after, nextAsset)).bind("rv", after.version).bind("a", p.userId).execute()
            AuditWriter.write(h, p, "gift", id.toString(), "update", giftAudit(cur, curAsset), JsonObject(giftAudit(after, nextAsset) + mapOf("request_uuid" to JsonPrimitive(uuid.toString()))), reason, call.requestId)
            after
        }
    }
}
