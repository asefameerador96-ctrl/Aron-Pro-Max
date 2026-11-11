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
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import java.security.MessageDigest
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

// ---------------------------------------------------------------------------------------------------------------------
// Discount-programme portal, admin surface (lane portal-discount-api, train 2; D-DISC-API-03; brief s6 and s8 defaults):
// listOffers, getOffer, listOfferVersions, createOffer (always a draft), updateOffer (a new version while draft),
// setOfferStatus (draft -> active by a SUPERADMIN, active -> inactive by SUPERADMIN or ADMIN with `w`).
// Hooks only for the field: nothing here changes pricing, the sale screen, memos, print or the bundle (docs/27). No discount
// engine, stacking evaluation, slab or threshold is invented: the rule is stored exactly as entered.
// ---------------------------------------------------------------------------------------------------------------------

@Serializable
data class OfferQualOut(val product_level: String, val product_id: Long)

@Serializable
data class OfferScopeOut(val node_type: String, val node_id: Long)

@Serializable
data class OfferSkuUnitOut(val sku_id: Long, val base_unit: String, val pack_factor: Int, val field_unit: String)

@Serializable
data class OfferOut(
    val id: Long, val code: String, val group_code: String, val offer_type: String, val level: String, val title_en: String, val title_bn: String?,
    val valid_from: String, val valid_to: String, val threshold_qty_base: Int?, val discount_bp: Int?, val discount_mtk_per_unit: Long?,
    val reward_sku_id: Long?, val reward_qty_base: Int?, val qualifying: List<OfferQualOut>, val scope: List<OfferScopeOut>, val stacking: String,
    val status: String, val version: Int, val offer_version_id: Long, val version_no: Int, val change_reason: String?, val created_at: String,
    val updated_at: String, val sku_units: List<OfferSkuUnitOut>,
)

@Serializable
data class OfferPageOut(val items: List<OfferOut>, val next_cursor: String?)

@Serializable
data class OfferVersionOut(
    val offer_version_id: Long, val offer_id: Long, val version_no: Int, val is_current: Boolean, val offer_type: String, val level: String,
    val title_en: String, val title_bn: String?, val valid_from: String, val valid_to: String, val threshold_qty_base: Int?, val discount_bp: Int?,
    val discount_mtk_per_unit: Long?, val reward_sku_id: Long?, val reward_qty_base: Int?, val qualifying: List<OfferQualOut>, val scope: List<OfferScopeOut>,
    val stacking: String, val change_reason: String?, val created_at: String, val sku_units: List<OfferSkuUnitOut>,
)

@Serializable
data class OfferVersionPageOut(val items: List<OfferVersionOut>, val next_cursor: String?)

class AdminOffersDeps(val db: Database, val reach: ReachResolver, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM)

private val OFFER_READERS = setOf(Role.SUPERADMIN, Role.ADMIN, Role.NATIONAL, Role.TOP, Role.BRAND, Role.WM, Role.DMO, Role.TSO)
private val OFFER_WRITERS = setOf(Role.SUPERADMIN, Role.ADMIN)
private val NATIONAL_READERS = setOf(Role.SUPERADMIN, Role.ADMIN, Role.NATIONAL, Role.TOP)
private val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private const val MODULE = "discounts"

/** v1 offers: price-off per unit (money or percent) and quantity-based free goods. `drp_slide` is not offered in v1 (brief s4); bundle has no type. */
private val V1_TYPES = setOf("pct_discount", "amount_per_unit", "free_qty")
private val ALL_TYPES = V1_TYPES + "drp_slide"
private val PRODUCT_LEVELS = setOf("category", "brand", "variant", "sku")
private val SCOPE_TYPES = setOf("wing", "division", "territory", "zone", "route", "channel", "sub_channel")
private const val MAX_MTK_PER_UNIT = 1_000_000_000_000L   // overflow guard for a later engine (qty x mtk fits in a Long), not a business rule
private const val MAX_QTY_BASE = 100_000_000L

/** `listOffers`, `getOffer`, `listOfferVersions`, `createOffer`, `updateOffer`, `setOfferStatus`. */
fun Route.adminOffersRoutes(d: AdminOffersDeps) {
    authenticated(d.guard) {
        get("/admin/offers") { call.respond(listOffers(call, d)) }
        get("/admin/offers/{id}") { respondOffer(call, getOffer(call, d), HttpStatusCode.OK) }
        get("/admin/offers/{id}/versions") { call.respond(listVersions(call, d)) }
        post("/admin/offers") { val (o, code) = createOffer(call, d); respondOffer(call, o, code) }
        patch("/admin/offers/{id}") { respondOffer(call, updateOffer(call, d), HttpStatusCode.OK) }
        post("/admin/offers/{id}/status") { respondOffer(call, setStatus(call, d), HttpStatusCode.OK) }
    }
}

private suspend fun respondOffer(call: ApplicationCall, o: OfferOut, code: HttpStatusCode) {
    call.etag(o.version)
    call.respond(code, o)
}

// ------------------------------------------------------------------------------------------------ who may do what

private fun forbidden(what: String) = ApiProblem(ProblemCode.ERR_FORBIDDEN, what)

/** Portal only (a phone token of the TSO app never reaches the discount screens), role-gated, and the ADMIN module grant judged here too when the grants are live. */
private fun ApplicationCall.offerPrincipal(write: Boolean): AronPrincipal {
    val p = principal
    if (p.isPhone) throw forbidden("the discount programmes are a portal screen")
    val allowed = if (write) OFFER_WRITERS else OFFER_READERS
    if (p.role !in allowed) throw forbidden(if (write) "changing discount programmes needs the ADMIN or SUPERADMIN role" else "discount programmes are not available to this role")
    if (p.role == Role.ADMIN) p.modules?.let { m ->
        val a = m[MODULE] ?: throw forbidden("no access to module $MODULE")
        if (write && a != "w") throw forbidden("read-only access to module $MODULE")
    }
    return p
}

/** What a reader may see (server-side, from rows: the token's `brands` and `geo` claims are informational and never read here). */
private sealed interface Vis {
    data object All : Vis
    data object Nobody : Vis
    data class Brands(val ids: List<Long>) : Vis
    data class Geo(val zones: List<Long>, val routes: List<Long>) : Vis
}

private fun visibility(h: Handle, p: AronPrincipal, d: AdminOffersDeps, today: LocalDate): Vis = when (p.role) {
    in NATIONAL_READERS -> Vis.All
    Role.BRAND -> h.createQuery("SELECT brand_id FROM app.user_brand_scope WHERE user_id = :u ORDER BY brand_id").bind("u", p.userId).mapTo(Long::class.java).list()
        .let { if (it.isEmpty()) Vis.Nobody else Vis.Brands(it) }
    Role.WM, Role.DMO, Role.TSO -> d.reach.reach(p.userId, p.role, p.scopeVersion, today).let { r: Reach ->
        if (r.national) Vis.All else Vis.Geo(r.zoneIds.toList(), r.routeIds.toList())
    }
    else -> Vis.Nobody
}

private class VisSql(val sql: String, val lists: Map<String, List<Long>>)

/**
 * SQL over a version row aliased [v]. Brand: the version names no product (all products) or one of its products is, or sits under, one of the
 * user's brands. Geography: the version names no wing/division/territory/zone/route (channel rows do not narrow geography) or one of them
 * contains a zone of the user's reach (or is a route of it). A version that mixes the user's places or brands with others is visible.
 */
private fun visSql(vis: Vis, v: String): VisSql? = when (vis) {
    Vis.All -> null
    Vis.Nobody -> VisSql("FALSE", emptyMap())
    is Vis.Brands -> VisSql(
        "(NOT EXISTS (SELECT 1 FROM app.offer_version_product x WHERE x.offer_version_id = $v.id) OR EXISTS (SELECT 1 FROM app.offer_version_product x WHERE x.offer_version_id = $v.id AND (" +
            "(x.product_level = 'brand' AND x.product_id IN (<vb>)) " +
            "OR (x.product_level = 'variant' AND EXISTS (SELECT 1 FROM app.product_node n WHERE n.id = x.product_id AND n.parent_id IN (<vb>))) " +
            "OR (x.product_level = 'sku' AND EXISTS (SELECT 1 FROM app.sku s JOIN app.product_node n ON n.id = s.variant_id WHERE s.id = x.product_id AND n.parent_id IN (<vb>))) " +
            "OR (x.product_level = 'category' AND EXISTS (SELECT 1 FROM app.product_node br JOIN app.product_node sg ON sg.id = br.parent_id WHERE br.id IN (<vb>) AND sg.parent_id = x.product_id)))))",
        mapOf("vb" to vis.ids),
    )
    is Vis.Geo -> VisSql(
        "(NOT EXISTS (SELECT 1 FROM app.offer_version_scope s WHERE s.offer_version_id = $v.id AND s.node_type IN ('wing','division','territory','zone','route')) " +
            "OR EXISTS (SELECT 1 FROM app.offer_version_scope s WHERE s.offer_version_id = $v.id AND (" +
            "(s.node_type = 'zone' AND s.node_id IN (<vz>)) " +
            "OR (s.node_type = 'territory' AND EXISTS (SELECT 1 FROM app.zone zz WHERE zz.territory_id = s.node_id AND zz.id IN (<vz>))) " +
            "OR (s.node_type = 'division' AND EXISTS (SELECT 1 FROM app.zone zz JOIN app.territory tt ON tt.id = zz.territory_id WHERE tt.division_id = s.node_id AND zz.id IN (<vz>))) " +
            "OR (s.node_type = 'wing' AND EXISTS (SELECT 1 FROM app.zone zz JOIN app.territory tt ON tt.id = zz.territory_id JOIN app.division dd ON dd.id = tt.division_id WHERE dd.wing_id = s.node_id AND zz.id IN (<vz>))) " +
            "OR (s.node_type = 'route' AND EXISTS (SELECT 1 FROM app.route rr WHERE rr.id = s.node_id AND (rr.zone_id IN (<vz>) OR rr.id IN (<vr>)))))))",
        // An empty list would be invalid SQL; no id is ever -1.
        mapOf("vz" to vis.zones.ifEmpty { listOf(-1L) }, "vr" to vis.routes.ifEmpty { listOf(-1L) }),
    )
}

private fun org.jdbi.v3.core.statement.Query.bindVis(s: VisSql?): org.jdbi.v3.core.statement.Query { s?.lists?.forEach { (k, v) -> bindList(k, v) }; return this }

private fun today(d: AdminOffersDeps): LocalDate = BusinessDate.of(d.clock.now().toEpochMilli()).toJavaLocalDate()

// ------------------------------------------------------------------------------------------------ reading

private const val VERSION_COLS = "v.id AS vid, v.offer_id, v.version_no, v.offer_type, v.level, v.title_en, v.title_bn, v.valid_from, v.valid_to, v.threshold_qty_base, v.discount_bp, " +
    "v.discount_mtk_per_unit, v.reward_sku_id, v.reward_qty_base, v.stacking, v.change_reason, v.created_at AS v_created_at"

private class Row(
    val id: Long, val code: String, val group: String, val status: String, val version: Int, val createdAt: OffsetDateTime, val updatedAt: OffsetDateTime, val currentVid: Long?,
    val vid: Long, val no: Int, val type: String, val level: String, val titleEn: String, val titleBn: String?, val from: LocalDate, val to: LocalDate,
    val threshold: Int?, val bp: Int?, val mtk: Long?, val rewardSku: Long?, val rewardQty: Int?, val stacking: String, val reason: String?, val vCreated: OffsetDateTime,
)

private const val ROW_SELECT = "SELECT o.id, o.code, o.group_code, o.status, o.version, o.created_at, o.updated_at, o.current_version_id, $VERSION_COLS FROM app.offer o JOIN app.offer_version v ON v.offer_id = o.id"

private fun java.sql.ResultSet.row() = Row(
    getLong("id"), getString("code"), getString("group_code"), getString("status"), getInt("version"), getObject("created_at", OffsetDateTime::class.java),
    getObject("updated_at", OffsetDateTime::class.java), getObject("current_version_id") as Long?, getLong("vid"), getInt("version_no"), getString("offer_type"), getString("level"),
    getString("title_en"), getString("title_bn"), getObject("valid_from", LocalDate::class.java), getObject("valid_to", LocalDate::class.java),
    getObject("threshold_qty_base") as Int?, getObject("discount_bp") as Int?, getObject("discount_mtk_per_unit") as Long?, getObject("reward_sku_id") as Long?,
    getObject("reward_qty_base") as Int?, getString("stacking"), getString("change_reason"), getObject("v_created_at", OffsetDateTime::class.java),
)

private class Extras(val qual: Map<Long, List<OfferQualOut>>, val scope: Map<Long, List<OfferScopeOut>>, val units: Map<Long, OfferSkuUnitOut>)

private fun extras(h: Handle, rows: List<Row>): Extras {
    if (rows.isEmpty()) return Extras(emptyMap(), emptyMap(), emptyMap())
    val vids = rows.map { it.vid }.distinct()
    val qual = h.createQuery("SELECT offer_version_id, product_level, product_id FROM app.offer_version_product WHERE offer_version_id IN (<v>) ORDER BY offer_version_id, product_level, product_id")
        .bindList("v", vids).map { rs, _ -> rs.getLong(1) to OfferQualOut(rs.getString(2), rs.getLong(3)) }.list().groupBy({ it.first }, { it.second })
    val scope = h.createQuery("SELECT offer_version_id, node_type, node_id FROM app.offer_version_scope WHERE offer_version_id IN (<v>) ORDER BY offer_version_id, node_type, node_id")
        .bindList("v", vids).map { rs, _ -> rs.getLong(1) to OfferScopeOut(rs.getString(2), rs.getLong(3)) }.list().groupBy({ it.first }, { it.second })
    val skuIds = (rows.mapNotNull { it.rewardSku } + qual.values.flatten().filter { it.product_level == "sku" }.map { it.product_id }).distinct()
    val units = if (skuIds.isEmpty()) emptyMap() else h.createQuery("SELECT id, base_unit, base_per_pack, category_code FROM app.sku WHERE id IN (<s>)").bindList("s", skuIds)
        .map { rs, _ -> OfferSkuUnitOut(rs.getLong(1), rs.getString(2), rs.getInt(3), fieldUnitOf(rs.getString(4))) }.list().associateBy { it.sku_id }
    return Extras(qual, scope, units)
}

/** The word the field apps count a SKU in (docs/34 s4.2): packet for cigarettes and bidi, box for lighters, dozen for matches, piece for anything else. */
internal fun fieldUnitOf(categoryCode: String): String = when (categoryCode) {
    "cigarette", "bidi" -> "packet"
    "lighter" -> "box"
    "match" -> "dozen"
    else -> "piece"
}

private fun unitsOf(r: Row, e: Extras): List<OfferSkuUnitOut> =
    ((e.qual[r.vid].orEmpty().filter { it.product_level == "sku" }.map { it.product_id }) + listOfNotNull(r.rewardSku)).distinct().sorted().mapNotNull { e.units[it] }

private fun offerOut(r: Row, e: Extras) = OfferOut(
    r.id, r.code, r.group, r.type, r.level, r.titleEn, r.titleBn, r.from.toString(), r.to.toString(), r.threshold, r.bp, r.mtk, r.rewardSku, r.rewardQty,
    e.qual[r.vid].orEmpty(), e.scope[r.vid].orEmpty(), r.stacking, r.status, r.version, r.vid, r.no, r.reason, r.createdAt.toInstant().wire(), r.updatedAt.toInstant().wire(), unitsOf(r, e),
)

private fun versionOut(r: Row, e: Extras) = OfferVersionOut(
    r.vid, r.id, r.no, r.currentVid == r.vid, r.type, r.level, r.titleEn, r.titleBn, r.from.toString(), r.to.toString(), r.threshold, r.bp, r.mtk, r.rewardSku, r.rewardQty,
    e.qual[r.vid].orEmpty(), e.scope[r.vid].orEmpty(), r.stacking, r.reason, r.vCreated.toInstant().wire(), unitsOf(r, e),
)

/** One offer shown with [versionId] (null = its current version). */
private fun loadOffer(h: Handle, offerId: Long, versionId: Long? = null, vis: VisSql? = null, lock: Boolean = false): OfferOut? {
    val join = if (versionId == null) "v.id = o.current_version_id" else "v.id = :vid"
    val q = h.createQuery("$ROW_SELECT AND $join WHERE o.id = :o" + (vis?.let { " AND " + it.sql } ?: "") + if (lock) " FOR UPDATE OF o" else "").bind("o", offerId)
    if (versionId != null) q.bind("vid", versionId)
    val row = q.bindVis(vis).map { rs, _ -> rs.row() }.findOne().orElse(null) ?: return null
    return offerOut(row, extras(h, listOf(row)))
}

private fun listOffers(call: ApplicationCall, d: AdminOffersDeps): OfferPageOut {
    val p = call.offerPrincipal(write = false)
    val q = call.request.queryParameters
    val limit = call.pageLimit(); val cursor = call.pageCursor()
    val status = q["status"]?.also { if (it !in setOf("draft", "active", "inactive")) adminBad("query.status", "not_in_enum") }
    val on = q["valid_on"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: adminBad("query.valid_on") }
    return d.db.jdbi.withHandle<OfferPageOut, Exception> { h ->
        val vis = visSql(visibility(h, p, d, today(d)), "v")
        val where = mutableListOf("v.id = o.current_version_id")
        if (status != null) where += "o.status = :status"
        if (on != null) where += "v.valid_from <= :on AND v.valid_to >= :on"
        if (cursor != null) where += "o.id < :cursor"
        if (vis != null) where += vis.sql
        val query = h.createQuery("$ROW_SELECT WHERE ${where.joinToString(" AND ")} ORDER BY o.id DESC LIMIT :lim").bind("lim", limit + 1)
        if (status != null) query.bind("status", status)
        if (on != null) query.bind("on", on)
        if (cursor != null) query.bind("cursor", cursor)
        val rows = query.bindVis(vis).map { rs, _ -> rs.row() }.list()
        val page = rows.take(limit)
        val e = extras(h, page)
        OfferPageOut(page.map { offerOut(it, e) }, if (rows.size > limit) page.last().id.toString() else null)
    }
}

private fun getOffer(call: ApplicationCall, d: AdminOffersDeps): OfferOut {
    val p = call.offerPrincipal(write = false)
    val id = call.pathId()
    return d.db.jdbi.withHandle<OfferOut, Exception> { h ->
        loadOffer(h, id, vis = visSql(visibility(h, p, d, today(d)), "v")) ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no discount programme with this id in your reach")
    }
}

private fun listVersions(call: ApplicationCall, d: AdminOffersDeps): OfferVersionPageOut {
    val p = call.offerPrincipal(write = false)
    val id = call.pathId(); val limit = call.pageLimit(); val cursor = call.pageCursor()
    return d.db.jdbi.withHandle<OfferVersionPageOut, Exception> { h ->
        val vis = visSql(visibility(h, p, d, today(d)), "v")
        // The offer itself must be visible through its current version; then each version is judged on its own scope (a version outside the reach is not listed).
        h.createQuery("SELECT o.id FROM app.offer o JOIN app.offer_version v ON v.id = o.current_version_id WHERE o.id = :o" + (vis?.let { " AND " + it.sql } ?: "")).bind("o", id).bindVis(vis)
            .mapTo(Long::class.java).findOne().orElse(null) ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no discount programme with this id in your reach")
        val where = mutableListOf("o.id = :o")
        if (cursor != null) where += "v.version_no < :cursor"
        if (vis != null) where += vis.sql
        val query = h.createQuery("SELECT o.id, o.code, o.group_code, o.status, o.version, o.created_at, o.updated_at, o.current_version_id, $VERSION_COLS FROM app.offer o JOIN app.offer_version v ON v.offer_id = o.id " +
            "WHERE ${where.joinToString(" AND ")} ORDER BY v.version_no DESC LIMIT :lim").bind("o", id).bind("lim", limit + 1)
        if (cursor != null) query.bind("cursor", cursor)
        val rows = query.bindVis(vis).map { rs, _ -> rs.row() }.list()
        val page = rows.take(limit)
        val e = extras(h, page)
        OfferVersionPageOut(page.map { versionOut(it, e) }, if (rows.size > limit) page.last().no.toString() else null)
    }
}

// ------------------------------------------------------------------------------------------------ writing: parsing

/** The rule of one version, normalised (the fingerprint and the audit image are made from it). */
private data class Spec(
    val type: String, val level: String, val titleEn: String, val titleBn: String?, val from: LocalDate, val to: LocalDate, val threshold: Int?, val bp: Int?, val mtk: Long?,
    val rewardSku: Long?, val rewardQty: Int?, val stacking: String, val qualifying: List<Pair<String, Long>>, val scope: List<Pair<String, Long>>,
)

private fun JsonObject.long(k: String, min: Long, max: Long, nullable: Boolean): Long? {
    val v = this[k] ?: return null
    if (v is JsonNull) { if (nullable) return null; adminBad("body.$k", "null_not_allowed") }
    if (v !is JsonPrimitive || v.isString) adminBad("body.$k", "not_an_integer")
    val n = v.content.toLongOrNull() ?: adminBad("body.$k", "not_an_integer")
    if (n !in min..max) adminBad("body.$k", "out_of_range")
    return n
}

private fun JsonObject.date(k: String): LocalDate? {
    val v = this[k] ?: return null
    if (v !is JsonPrimitive || !v.isString) adminBad("body.$k", "not_a_date")
    return runCatching { LocalDate.parse(v.content) }.getOrNull()?.takeIf { it.toString() == v.content } ?: adminBad("body.$k", "not_a_date")
}

private fun JsonObject.pairs(k: String, max: Int, idKey: String, kindKey: String, kinds: Set<String>): List<Pair<String, Long>>? {
    val v = this[k] ?: return null
    if (v !is JsonArray) adminBad("body.$k", "not_an_array")
    if (v.size > max) adminBad("body.$k", "out_of_range")
    val out = LinkedHashSet<Pair<String, Long>>()
    v.forEachIndexed { i, e ->
        val o = e as? JsonObject ?: adminBad("body.$k[$i]", "not_an_object")
        o.only(setOf(kindKey, idKey))
        val kind = o.enum(kindKey, kinds, required = true)!!
        val id = o.id(idKey, required = true)!!
        if (!out.add(kind to id)) adminBad("body.$k[$i]", "duplicate")
    }
    return out.sortedWith(compareBy({ it.first }, { it.second }))
}

private fun reasonOf(o: JsonObject): String {
    val raw = o.str("change_reason", 1, 2000, required = true)!!
    val t = raw.trim()
    if (t.length < 10 || t.length > 500) adminBad("body.change_reason", "length")
    return t
}

private fun requestUuid(o: JsonObject): UUID {
    val s = o.str("request_uuid", 1, 64, required = true)!!
    if (!UUID_V4.matches(s)) adminBad("body.request_uuid", "not_a_uuid")
    return UUID.fromString(s)
}

private val RULE_MEMBERS = setOf(
    "offer_type", "level", "title_en", "title_bn", "valid_from", "valid_to", "threshold_qty_base", "discount_bp", "discount_mtk_per_unit", "reward_sku_id", "reward_qty_base",
    "stacking", "qualifying", "scope",
)

/** Reads the rule members present in [o] over [base] (null base = a create: the required ones must be present). */
private fun readSpec(o: JsonObject, base: Spec?): Spec {
    val create = base == null
    fun has(k: String) = o.containsKey(k)
    val type = if (has("offer_type")) o.enum("offer_type", ALL_TYPES, required = true)!! else base?.type ?: adminBad("body.offer_type", "required")
    val level = if (has("level")) o.enum("level", setOf("line", "memo"), required = true)!! else base?.level ?: adminBad("body.level", "required")
    val titleEn = if (has("title_en")) o.str("title_en", 1, 200, required = true)!! else base?.titleEn ?: adminBad("body.title_en", "required")
    val titleBn = if (has("title_bn")) o.str("title_bn", 0, 200, required = false, nullable = true) else base?.titleBn
    val from = if (has("valid_from")) o.date("valid_from") ?: adminBad("body.valid_from", "required") else base?.from ?: adminBad("body.valid_from", "required")
    val to = if (has("valid_to")) o.date("valid_to") ?: adminBad("body.valid_to", "required") else base?.to ?: adminBad("body.valid_to", "required")
    val threshold = if (has("threshold_qty_base")) o.long("threshold_qty_base", 1, MAX_QTY_BASE, nullable = true)?.toInt() else base?.threshold
    val bp = if (has("discount_bp")) o.long("discount_bp", 1, 10000, nullable = true)?.toInt() else base?.bp
    val mtk = if (has("discount_mtk_per_unit")) o.long("discount_mtk_per_unit", 1, MAX_MTK_PER_UNIT, nullable = true) else base?.mtk
    val rewardSku = if (has("reward_sku_id")) o.long("reward_sku_id", 1, Long.MAX_VALUE, nullable = true) else base?.rewardSku
    val rewardQty = if (has("reward_qty_base")) o.long("reward_qty_base", 1, MAX_QTY_BASE, nullable = true)?.toInt() else base?.rewardQty
    val stacking = if (has("stacking")) o.enum("stacking", setOf("exclusive", "stackable"), required = true)!! else base?.stacking ?: adminBad("body.stacking", "required")
    val qualifying = if (has("qualifying")) o.pairs("qualifying", 100, "product_id", "product_level", PRODUCT_LEVELS)!! else base?.qualifying ?: adminBad("body.qualifying", "required")
    val scope = if (has("scope")) o.pairs("scope", 200, "node_id", "node_type", SCOPE_TYPES)!! else base?.scope ?: adminBad("body.scope", "required")
    if (create) listOf("offer_type", "level", "title_en", "valid_from", "valid_to", "stacking", "qualifying", "scope").forEach { if (!has(it)) adminBad("body.$it", "required") }
    val s = Spec(type, level, titleEn, titleBn, from, to, threshold, bp, mtk, rewardSku, rewardQty, stacking, qualifying, scope)
    checkRule(s)
    return s
}

/** The V0003 CHECKs in code, plus the v1 limits (no drp_slide, no channel node) and one rule per type with nothing the type does not use. */
private fun checkRule(s: Spec) {
    if (s.type !in V1_TYPES) adminBad("body.offer_type", "not_supported")
    if (s.to.isBefore(s.from)) adminBad("body.valid_to", "before_valid_from")
    when (s.type) {
        "pct_discount" -> {
            if (s.bp == null) adminBad("body.discount_bp", "required")
            if (s.mtk != null) adminBad("body.discount_mtk_per_unit", "not_applicable")
            if (s.rewardSku != null) adminBad("body.reward_sku_id", "not_applicable")
            if (s.rewardQty != null) adminBad("body.reward_qty_base", "not_applicable")
        }
        "amount_per_unit" -> {
            if (s.mtk == null) adminBad("body.discount_mtk_per_unit", "required")
            if (s.bp != null) adminBad("body.discount_bp", "not_applicable")
            if (s.rewardSku != null) adminBad("body.reward_sku_id", "not_applicable")
            if (s.rewardQty != null) adminBad("body.reward_qty_base", "not_applicable")
        }
        "free_qty" -> {
            if (s.rewardSku == null) adminBad("body.reward_sku_id", "required")
            if (s.rewardQty == null) adminBad("body.reward_qty_base", "required")
            if (s.threshold == null) adminBad("body.threshold_qty_base", "required")
            if (s.bp != null) adminBad("body.discount_bp", "not_applicable")
            if (s.mtk != null) adminBad("body.discount_mtk_per_unit", "not_applicable")
        }
    }
    if (s.scope.any { it.first == "channel" }) adminBad("body.scope", "channel_not_supported")
}

private fun refsExist(h: Handle, s: Spec) {
    s.rewardSku?.let { id -> if (!h.exists("SELECT 1 FROM app.sku WHERE id = :i", id)) adminBad("body.reward_sku_id", "not_found") }
    s.qualifying.forEachIndexed { i, (level, id) ->
        val ok = if (level == "sku") h.exists("SELECT 1 FROM app.sku WHERE id = :i", id) else h.exists("SELECT 1 FROM app.product_node WHERE id = :i AND level = '$level'", id)
        if (!ok) adminBad("body.qualifying[$i]", "not_found")
    }
    s.scope.forEachIndexed { i, (type, id) ->
        val table = when (type) { "wing" -> "app.wing"; "division" -> "app.division"; "territory" -> "app.territory"; "zone" -> "app.zone"; "route" -> "app.route"; "sub_channel" -> "app.sub_channel"; else -> adminBad("body.scope[$i]", "channel_not_supported") }
        if (!h.exists("SELECT 1 FROM $table WHERE id = :i", id)) adminBad("body.scope[$i]", "not_found")
    }
}

private fun Handle.exists(sql: String, id: Long): Boolean = createQuery(sql).bind("i", id).mapTo(Int::class.java).findOne().isPresent

/** Canonical text of the SUPPLIED members of a request (not the merged result): a replay is told from a reused UUID by it. */
private fun fingerprint(kind: String, offerId: Long?, o: JsonObject): String {
    val canon = buildString {
        append(kind).append('|').append(offerId ?: 0)
        for (k in o.keys.sorted()) {
            if (k == "request_uuid") continue
            val v = o.getValue(k)
            append('|').append(k).append('=').append(
                when {
                    k == "change_reason" -> (v as? JsonPrimitive)?.content?.trim() ?: v.toString()
                    k == "qualifying" || k == "scope" -> (v as? JsonArray)?.map { e -> (e as? JsonObject)?.entries?.sortedBy { it.key }?.joinToString(",") { "${it.key}:${it.value}" } ?: e.toString() }?.sorted()?.joinToString(";") ?: v.toString()
                    else -> v.toString()
                },
            )
        }
    }
    return MessageDigest.getInstance("SHA-256").digest(canon.toByteArray()).joinToString("") { "%02x".format(it) }
}

private fun specJson(s: Spec): JsonObject = buildJsonObject {
    put("offer_type", s.type); put("level", s.level); put("title_en", s.titleEn); put("title_bn", s.titleBn)
    put("valid_from", s.from.toString()); put("valid_to", s.to.toString()); put("threshold_qty_base", s.threshold); put("discount_bp", s.bp)
    put("discount_mtk_per_unit", s.mtk); put("reward_sku_id", s.rewardSku); put("reward_qty_base", s.rewardQty); put("stacking", s.stacking)
    put("qualifying", buildJsonArray { s.qualifying.forEach { add(buildJsonObject { put("product_level", it.first); put("product_id", it.second) }) } })
    put("scope", buildJsonArray { s.scope.forEach { add(buildJsonObject { put("node_type", it.first); put("node_id", it.second) }) } })
}

private fun specOf(o: OfferOut) = Spec(
    o.offer_type, o.level, o.title_en, o.title_bn, LocalDate.parse(o.valid_from), LocalDate.parse(o.valid_to), o.threshold_qty_base, o.discount_bp, o.discount_mtk_per_unit,
    o.reward_sku_id, o.reward_qty_base, o.stacking, o.qualifying.map { it.product_level to it.product_id }.sortedWith(compareBy({ it.first }, { it.second })),
    o.scope.map { it.node_type to it.node_id }.sortedWith(compareBy({ it.first }, { it.second })),
)

private fun insertVersion(h: Handle, offerId: Long, no: Int, s: Spec, reason: String, uuid: UUID, fp: String, actor: AronPrincipal): Long {
    val vid = h.createQuery(
        "INSERT INTO app.offer_version (offer_id, version_no, offer_type, level, title_en, title_bn, valid_from, valid_to, threshold_qty_base, discount_bp, discount_mtk_per_unit, " +
            "reward_sku_id, reward_qty_base, stacking, change_reason, created_by, request_uuid, request_fingerprint) VALUES (:o, :n, :t, :l, :te, :tb, :vf, :vt, :th, :bp, :mtk, :rs, :rq, :st, :why, :by, :u, :fp) RETURNING id",
    ).bind("o", offerId).bind("n", no).bind("t", s.type).bind("l", s.level).bind("te", s.titleEn).bindAny("tb", s.titleBn).bind("vf", s.from).bind("vt", s.to)
        .bindAny("th", s.threshold).bindAny("bp", s.bp).bindAny("mtk", s.mtk).bindAny("rs", s.rewardSku).bindAny("rq", s.rewardQty).bind("st", s.stacking)
        .bind("why", reason).bind("by", actor.userId).bind("u", uuid).bind("fp", fp).mapTo(Long::class.java).one()
    s.qualifying.forEach { (level, id) -> h.createUpdate("INSERT INTO app.offer_version_product (offer_version_id, product_level, product_id) VALUES (:v, :l, :i)").bind("v", vid).bind("l", level).bind("i", id).execute() }
    s.scope.forEach { (type, id) -> h.createUpdate("INSERT INTO app.offer_version_scope (offer_version_id, node_type, node_id) VALUES (:v, :t, :i)").bind("v", vid).bind("t", type).bind("i", id).execute() }
    return vid
}

private fun org.jdbi.v3.core.statement.Query.bindAny(name: String, v: Any?): org.jdbi.v3.core.statement.Query = bind(name, v as Any?)

private fun lockRequest(h: Handle, uuid: UUID) { h.createQuery("SELECT pg_advisory_xact_lock(hashtext(:k))").bind("k", "offer_req:$uuid").mapTo(String::class.java).findOne() }

private data class Prior(val versionId: Long, val offerId: Long, val versionNo: Int, val fingerprint: String?)

private fun priorRequest(h: Handle, uuid: UUID): Prior? = h.createQuery("SELECT id, offer_id, version_no, request_fingerprint FROM app.offer_version WHERE request_uuid = :u").bind("u", uuid)
    .map { rs, _ -> Prior(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getString(4)) }.findOne().orElse(null)

private fun conflict(msg: String) = ApiProblem(ProblemCode.ERR_CONFLICT, msg, errors = listOf(FieldError("body.request_uuid", "reused")))

// ------------------------------------------------------------------------------------------------ writing: handlers

private suspend fun createOffer(call: ApplicationCall, d: AdminOffersDeps): Pair<OfferOut, HttpStatusCode> {
    val p = call.offerPrincipal(write = true)
    val o = call.bodyObject()
    o.only(RULE_MEMBERS + setOf("request_uuid", "code", "group_code", "change_reason"))
    val uuid = requestUuid(o)
    val code = o.str("code", 1, 40, required = true)!!.also { if (!CODE_RE.matches(it)) adminBad("body.code", "pattern") }
    val group = o.str("group_code", 1, 40, required = true)!!
    val reason = reasonOf(o)
    val spec = readSpec(o, null)
    val fp = fingerprint("create", null, o)
    return mapDb {
        d.db.jdbi.inTransaction<Pair<OfferOut, HttpStatusCode>, Exception> { h ->
            lockRequest(h, uuid)
            priorRequest(h, uuid)?.let { prior ->
                if (prior.fingerprint != fp || prior.versionNo != 1) throw conflict("request_uuid already used for a different call")
                return@inTransaction loadOffer(h, prior.offerId, prior.versionId)!! to HttpStatusCode.Created
            }
            if (h.createQuery("SELECT 1 FROM app.offer WHERE code = :c").bind("c", code).mapTo(Int::class.java).findOne().isPresent)
                throw ApiProblem(ProblemCode.ERR_MASTER_DUPLICATE_CODE, "a discount programme with this code already exists", errors = listOf(FieldError("body.code", "duplicate")))
            refsExist(h, spec)
            val id = h.createQuery("INSERT INTO app.offer (code, group_code, status, created_by, updated_by) VALUES (:c, :g, 'draft', :by, :by) RETURNING id")
                .bind("c", code).bind("g", group).bind("by", p.userId).mapTo(Long::class.java).one()
            val vid = insertVersion(h, id, 1, spec, reason, uuid, fp, p)
            h.createUpdate("UPDATE app.offer SET current_version_id = :v WHERE id = :o").bind("v", vid).bind("o", id).execute()
            val after = JsonObject(specJson(spec) + mapOf("code" to JsonPrimitive(code), "group_code" to JsonPrimitive(group), "status" to JsonPrimitive("draft"), "version_no" to JsonPrimitive(1), "request_uuid" to JsonPrimitive(uuid.toString())))
            AuditWriter.write(h, p, "offer", id.toString(), "create", null, after, reason, call.requestId)
            loadOffer(h, id)!! to HttpStatusCode.Created
        }
    }
}

private suspend fun updateOffer(call: ApplicationCall, d: AdminOffersDeps): OfferOut {
    val p = call.offerPrincipal(write = true)
    val id = call.pathId()
    val ifMatch = call.ifMatch()
    val o = call.bodyObject()
    if (o.containsKey("status")) adminBad("body.status", "use_status_endpoint")
    o.only(RULE_MEMBERS + setOf("request_uuid", "change_reason"))
    val uuid = requestUuid(o)
    val reason = reasonOf(o)
    if (o.keys.none { it in RULE_MEMBERS }) adminBad("body", "no_change")
    val fp = fingerprint("patch", id, o)
    return mapDb {
        d.db.jdbi.inTransaction<OfferOut, Exception> { h ->
            lockRequest(h, uuid)
            priorRequest(h, uuid)?.let { prior ->
                if (prior.fingerprint != fp || prior.offerId != id) throw conflict("request_uuid already used for a different call")
                return@inTransaction loadOffer(h, prior.offerId, prior.versionId)!!
            }
            val cur = loadOffer(h, id, lock = true) ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no discount programme with this id")
            if (cur.status != "draft") throw ApiProblem(ProblemCode.ERR_REQUEST_STATE, "only a draft can change; an ${cur.status} programme is final (create a new one)")
            if (cur.version != ifMatch) preconditionFailed(cur.version)
            val before = specOf(cur)
            val spec = readSpec(o, before)
            if (spec == before) adminBad("body", "no_change")
            refsExist(h, spec)
            val no = h.createQuery("SELECT max(version_no) + 1 FROM app.offer_version WHERE offer_id = :o").bind("o", id).mapTo(Int::class.java).one()
            val vid = insertVersion(h, id, no, spec, reason, uuid, fp, p)
            h.createUpdate("UPDATE app.offer SET current_version_id = :v, updated_by = :by WHERE id = :o").bind("v", vid).bind("by", p.userId).bind("o", id).execute()
            AuditWriter.write(
                h, p, "offer", id.toString(), "new_version",
                JsonObject(specJson(before) + mapOf("version_no" to JsonPrimitive(cur.version_no))),
                JsonObject(specJson(spec) + mapOf("version_no" to JsonPrimitive(no), "request_uuid" to JsonPrimitive(uuid.toString()))), reason, call.requestId,
            )
            loadOffer(h, id)!!
        }
    }
}

private suspend fun setStatus(call: ApplicationCall, d: AdminOffersDeps): OfferOut {
    val p = call.offerPrincipal(write = true)
    val id = call.pathId()
    val o = call.bodyObject()
    o.only(setOf("request_uuid", "to_status", "reason"))
    val uuid = requestUuid(o)
    val to = o.enum("to_status", setOf("active", "inactive"), required = true)!!
    val reason = o.str("reason", 1, 2000, required = true)!!.trim().also { if (it.length < 10 || it.length > 500) adminBad("body.reason", "length") }
    // Activation is the owner's decision (brief s8 Q3, as for prices): SUPERADMIN only. Ending is a write like any other.
    if (to == "active" && p.role != Role.SUPERADMIN) throw forbidden("only a SUPERADMIN activates a discount programme")
    return mapDb {
        d.db.jdbi.inTransaction<OfferOut, Exception> { h ->
            lockRequest(h, uuid)
            h.createQuery("SELECT offer_id, to_status, reason FROM app.offer_status_change WHERE request_uuid = :u").bind("u", uuid)
                .map { rs, _ -> Triple(rs.getLong(1), rs.getString(2), rs.getString(3)) }.findOne().orElse(null)?.let { (offer, status, why) ->
                    if (offer != id || status != to || why != reason) throw conflict("request_uuid already used for a different call")
                    return@inTransaction loadOffer(h, id)!!
                }
            val cur = loadOffer(h, id, lock = true) ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no discount programme with this id")
            val allowed = (cur.status == "draft" && to == "active") || (cur.status == "active" && to == "inactive")
            if (!allowed) throw ApiProblem(ProblemCode.ERR_REQUEST_STATE, "a ${cur.status} programme cannot move to $to (allowed: draft to active, active to inactive)")
            if (to == "active" && LocalDate.parse(cur.valid_to).isBefore(today(d)))
                throw ApiProblem(ProblemCode.ERR_VALIDATION, "the programme ended on ${cur.valid_to}; make a new version with a later valid_to first", errors = listOf(FieldError("valid_to", "past")))
            h.createUpdate("INSERT INTO app.offer_status_change (offer_id, from_status, to_status, request_uuid, reason, actor) VALUES (:o, :f, :t, :u, :r, :a)")
                .bind("o", id).bind("f", cur.status).bind("t", to).bind("u", uuid).bind("r", reason).bind("a", p.userId).execute()
            h.createUpdate("UPDATE app.offer SET status = :t, updated_by = :by WHERE id = :o").bind("t", to).bind("by", p.userId).bind("o", id).execute()
            AuditWriter.write(
                h, p, "offer", id.toString(), if (to == "active") "activate" else "end",
                auditObj("status" to cur.status), auditObj("status" to to, "request_uuid" to uuid.toString()), reason, call.requestId,
            )
            loadOffer(h, id)!!
        }
    }
}
