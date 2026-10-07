package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.config.AuditWriter
import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.ResponseJson
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.requestId
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.statement.UnableToExecuteStatementException
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

// ---------------------------------------------------------------------------------------------------------------------
// F-API-035c: the `admin-products` surface of the contract for the product tree and SKUs (prices are in AdminPrices.kt).
// Offers (listOffers, createOffer, updateOffer) are a deferred programme (docs/27) and the sales plan (getSalesPlan,
// putSalesPlan) belongs to F-API-035b / F-ADM-006: neither is built here.
// ---------------------------------------------------------------------------------------------------------------------

@Serializable
data class ProductNodeOut(
    val id: Long, val level: String, val parent_id: Long?, val code: String?, val name: String, val name_bn: String?, val sort: Int, val status: String,
    val created_at: String, val updated_at: String, val version: Int,
)

@Serializable
data class ProductNodePageOut(val items: List<ProductNodeOut>, val next_cursor: String?)

@Serializable
data class SkuOut(
    val id: Long, val code: String, val variant_id: Long, val category_code: String, val name: String, val short_name: String, val name_bn: String?,
    val base_unit: String, val base_per_pack: Int, val entry_unit_default: String, val report_unit: String?, val report_factor: String, val sort: Int,
    val status: String, val thumbnail_media_uuid: String?, val updated_at: String, val version: Int,
)

@Serializable
data class SkuPageOut(val items: List<SkuOut>, val next_cursor: String?)

class AdminProductsDeps(val db: Database, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM)

/** Master data is read by every web role of docs/24 s8.5 and written by ADMIN and SUPERADMIN only. */
internal val MASTER_READERS = setOf(Role.TSO, Role.DMO, Role.WM, Role.TOP, Role.ANALYST, Role.SUPPORT, Role.ADMIN, Role.SUPERADMIN)
internal val MASTER_WRITERS = setOf(Role.ADMIN, Role.SUPERADMIN)

internal fun adminBad(pointer: String, code: String = "invalid_value"): Nothing =
    throw ApiProblem(ProblemCode.ERR_VALIDATION, "invalid $pointer", errors = listOf(FieldError(pointer, code)))

internal fun ApplicationCall.masterReader(): AronPrincipal = principal.also { if (it.role !in MASTER_READERS) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "products and prices are not available to this role") }
internal fun ApplicationCall.masterWriter(): AronPrincipal = principal.also { if (it.role !in MASTER_WRITERS) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "changing products and prices needs the ADMIN or SUPERADMIN role") }

internal fun ApplicationCall.pageLimit(): Int = request.queryParameters["limit"]?.let { it.toIntOrNull()?.takeIf { v -> v in 1..500 } ?: adminBad("query.limit", "out_of_range") } ?: 100
internal fun ApplicationCall.pageCursor(): Long? = request.queryParameters["cursor"]?.let { it.toLongOrNull()?.takeIf { v -> v >= 1 } ?: adminBad("query.cursor") }
internal fun ApplicationCall.queryId(name: String): Long? = request.queryParameters[name]?.let { it.toLongOrNull()?.takeIf { v -> v >= 1 } ?: adminBad("query.$name") }
internal fun ApplicationCall.queryStatus(): String? = request.queryParameters["status"]?.also { if (it != "active" && it != "inactive") adminBad("query.status", "not_in_enum") }
internal fun ApplicationCall.queryUpdatedSince(): OffsetDateTime? = request.queryParameters["updated_since"]?.let {
    OffsetDateTime.ofInstant(runCatching { Instant.parse(it) }.getOrNull() ?: adminBad("query.updated_since"), ZoneOffset.UTC)
}
internal fun ApplicationCall.pathId(): Long = parameters["id"]?.toLongOrNull()?.takeIf { it >= 1 } ?: adminBad("path.id")

/** `If-Match` carries the quoted row version (contract IfMatch); a missing or malformed header is a validation error, a stale one is 412. */
internal fun ApplicationCall.ifMatch(): Int = request.headers["If-Match"]?.let { h -> Regex("^\"([0-9]{1,10})\"$").matchEntire(h)?.groupValues?.get(1)?.toIntOrNull() } ?: adminBad("header.If-Match")

internal fun ApplicationCall.etag(version: Int) = response.header("ETag", "\"$version\"")

internal suspend fun ApplicationCall.bodyObject(): JsonObject {
    val el = try { Json.parseToJsonElement(receiveText()) } catch (e: kotlinx.serialization.SerializationException) {
        throw ApiProblem(ProblemCode.ERR_VALIDATION, "malformed request body", errors = listOf(FieldError("body", "invalid_body")))
    }
    return el as? JsonObject ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "the body must be a JSON object", errors = listOf(FieldError("body", "invalid_body")))
}

/** Unknown fields are refused (the contract bodies are additionalProperties: false). */
internal fun JsonObject.only(allowed: Set<String>) { keys.firstOrNull { it !in allowed }?.let { adminBad("body.$it", "unknown_field") } }

internal fun JsonObject.str(k: String, min: Int, max: Int, required: Boolean, nullable: Boolean = false): String? {
    val v = this[k]
    if (v == null) { if (required) adminBad("body.$k", "required"); return null }
    if (v is JsonNull) { if (nullable) return null; adminBad("body.$k", "null_not_allowed") }
    if (v !is JsonPrimitive || !v.isString) adminBad("body.$k", "not_a_string")
    val s = v.content
    if (s.length !in min..max || (min >= 1 && s.isBlank())) adminBad("body.$k", "length")
    return s
}

internal fun JsonObject.int(k: String, min: Long, max: Long, required: Boolean): Int? {
    val v = this[k]
    if (v == null) { if (required) adminBad("body.$k", "required"); return null }
    if (v !is JsonPrimitive || v.isString) adminBad("body.$k", "not_an_integer")
    val n = v.content.toLongOrNull() ?: adminBad("body.$k", "not_an_integer")
    if (n !in min..max) adminBad("body.$k", "out_of_range")
    return n.toInt()
}

internal fun JsonObject.id(k: String, required: Boolean, nullable: Boolean = false): Long? {
    val v = this[k]
    if (v == null) { if (required) adminBad("body.$k", "required"); return null }
    if (v is JsonNull) { if (nullable) return null; adminBad("body.$k", "null_not_allowed") }
    if (v !is JsonPrimitive || v.isString) adminBad("body.$k", "not_an_integer")
    return v.content.toLongOrNull()?.takeIf { it >= 1 } ?: adminBad("body.$k", "out_of_range")
}

internal fun JsonObject.enum(k: String, allowed: Set<String>, required: Boolean, nullable: Boolean = false): String? {
    val v = this[k]
    if (v == null) { if (required) adminBad("body.$k", "required"); return null }
    if (v is JsonNull) { if (nullable) return null; adminBad("body.$k", "null_not_allowed") }
    if (v !is JsonPrimitive || !v.isString || v.content !in allowed) adminBad("body.$k", "not_in_enum")
    return v.content
}

/** Maps the database's own guarantees to the contract's stable codes (duplicate, overlap, constraint). */
internal fun <T> mapDb(block: () -> T): T = try { block() } catch (e: UnableToExecuteStatementException) {
    when ((e.cause as? java.sql.SQLException)?.sqlState) {
        "23505" -> throw ApiProblem(ProblemCode.ERR_MASTER_DUPLICATE_CODE, "a row with this code or name already exists")
        "22003" -> throw ApiProblem(ProblemCode.ERR_VALIDATION, "a number is out of range", errors = listOf(FieldError("body", "out_of_range")))
        "23P01" -> throw ApiProblem(ProblemCode.ERR_MASTER_OVERLAP, "the dates overlap an existing row")
        "23514", "23503" -> throw ApiProblem(ProblemCode.ERR_VALIDATION, "the values violate a data rule", errors = listOf(FieldError("body", "constraint")))
        else -> throw e
    }
}

internal fun preconditionFailed(current: Int): Nothing =
    throw ApiProblem(ProblemCode.ERR_PRECONDITION_FAILED, "the row changed since it was read; reload and retry", context = mapOf("current_version" to JsonPrimitive(current)))

private fun ts(rs: ResultSet, col: String): String = rs.getObject(col, OffsetDateTime::class.java).toInstant().wire()
internal fun OffsetDateTime.wireTs(): String = toInstant().wire()

private const val NODE_COLS = "id, level, parent_id, code, name, name_bn, sort, status, created_at, updated_at, version"
private const val SKU_COLS = "id, code, variant_id, category_code, name, short_name, name_bn, base_unit, base_per_pack, entry_unit_default, report_unit, report_factor, sort, status, thumbnail_media_uuid, updated_at, version"
private val LEVELS = listOf("category", "segment", "brand", "variant")
private val PARENT_LEVEL = mapOf("segment" to "category", "brand" to "segment", "variant" to "brand")
private val STATUSES = setOf("active", "inactive")
private val UNITS = setOf("stick", "piece", "dozen")
private val REPORT_UNITS = setOf("stick", "piece", "dozen", "box", "pack")
private val CATEGORY_UNIT = mapOf("cigarette" to "stick", "bidi" to "stick", "lighter" to "piece", "match" to "dozen")
private val DECIMAL3 = Regex("^\\d{1,13}(\\.\\d{1,3})?$")
private val SKU_CODE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$")
private val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

private fun nodeOf(rs: ResultSet) = ProductNodeOut(
    rs.getLong("id"), rs.getString("level"), rs.getObject("parent_id") as Long?, rs.getString("code"), rs.getString("name"), rs.getString("name_bn"),
    rs.getInt("sort"), rs.getString("status"), ts(rs, "created_at"), ts(rs, "updated_at"), rs.getInt("version"),
)

private fun skuOf(rs: ResultSet) = SkuOut(
    rs.getLong("id"), rs.getString("code"), rs.getLong("variant_id"), rs.getString("category_code"), rs.getString("name"), rs.getString("short_name"), rs.getString("name_bn"),
    rs.getString("base_unit"), rs.getInt("base_per_pack"), rs.getString("entry_unit_default"), rs.getString("report_unit"), rs.getBigDecimal("report_factor").toPlainString(),
    rs.getInt("sort"), rs.getString("status"), rs.getString("thumbnail_media_uuid"), ts(rs, "updated_at"), rs.getInt("version"),
)

private fun json(o: Any): JsonElement = when (o) {
    is ProductNodeOut -> ResponseJson.encodeToJsonElement(ProductNodeOut.serializer(), o)
    is SkuOut -> ResponseJson.encodeToJsonElement(SkuOut.serializer(), o)
    else -> error("unsupported")
}

private fun ApplicationCall.level(): String = parameters["level"]?.takeIf { it in LEVELS } ?: adminBad("path.level", "not_in_enum")

private fun checkParent(h: Handle, level: String, parentId: Long?) {
    if (level == "category") { if (parentId != null) adminBad("body.parent_id", "category_has_no_parent"); return }
    if (parentId == null) adminBad("body.parent_id", "required")
    val pl = h.createQuery("SELECT level FROM app.product_node WHERE id = :p").bind("p", parentId).mapTo(String::class.java).findOne().orElse(null)
    if (pl != PARENT_LEVEL.getValue(level)) adminBad("body.parent_id", if (pl == null) "not_found" else "wrong_level")
}

private fun hasActiveChildren(h: Handle, node: ProductNodeOut): Boolean =
    h.createQuery("SELECT EXISTS (SELECT 1 FROM app.product_node WHERE parent_id = :i AND status = 'active')").bind("i", node.id).mapTo(Boolean::class.java).one() ||
        (node.level == "variant" && h.createQuery("SELECT EXISTS (SELECT 1 FROM app.sku WHERE variant_id = :i AND status = 'active')").bind("i", node.id).mapTo(Boolean::class.java).one())

/** `listProductNodes`, `createProductNode`, `updateProductNode`, `listSkus`, `createSku`, `updateSku`. */
fun Route.adminProductsRoutes(d: AdminProductsDeps) {
    authenticated(d.guard) {
        get("/admin/product-nodes/{level}") { call.respond(listNodes(call, d)) }
        post("/admin/product-nodes/{level}") { createNode(call, d) }
        patch("/admin/product-nodes/{level}/{id}") { updateNode(call, d) }
        get("/admin/skus") { call.respond(listSkus(call, d)) }
        post("/admin/skus") { createSku(call, d) }
        patch("/admin/skus/{id}") { updateSku(call, d) }
    }
}

private fun listNodes(call: ApplicationCall, d: AdminProductsDeps): ProductNodePageOut {
    call.masterReader()
    val level = call.level()
    val limit = call.pageLimit(); val cursor = call.pageCursor(); val parent = call.queryId("parent_id"); val status = call.queryStatus(); val since = call.queryUpdatedSince()
    val rows = d.db.jdbi.withHandle<List<ProductNodeOut>, Exception> { h ->
        val where = mutableListOf("level = :level")
        if (parent != null) where += "parent_id = :parent"
        if (status != null) where += "status = :status"
        if (since != null) where += "updated_at > :since"
        if (cursor != null) where += "id > :cursor"
        val q = h.createQuery("SELECT $NODE_COLS FROM app.product_node WHERE ${where.joinToString(" AND ")} ORDER BY id LIMIT :lim").bind("level", level).bind("lim", limit + 1)
        if (parent != null) q.bind("parent", parent)
        if (status != null) q.bind("status", status)
        if (since != null) q.bind("since", since)
        if (cursor != null) q.bind("cursor", cursor)
        q.map { rs, _ -> nodeOf(rs) }.list()
    }
    val page = rows.take(limit)
    return ProductNodePageOut(page, if (rows.size > limit) page.last().id.toString() else null)
}

private suspend fun createNode(call: ApplicationCall, d: AdminProductsDeps) {
    val p = call.masterWriter()
    val level = call.level()
    val b = call.bodyObject()
    b.only(setOf("parent_id", "code", "name", "name_bn", "sort"))
    val parent = b.id("parent_id", required = false, nullable = true)
    val code = b.str("code", 1, 40, required = false, nullable = true)
    val name = b.str("name", 1, 120, required = true)!!.trim()
    val nameBn = b.str("name_bn", 0, 120, required = false, nullable = true)
    val sort = b.int("sort", -1_000_000, 1_000_000, required = true)!!
    val out = mapDb {
        d.db.jdbi.inTransaction<ProductNodeOut, Exception> { h ->
            checkParent(h, level, parent)
            val n = h.createQuery(
                "INSERT INTO app.product_node (level, parent_id, code, name, name_bn, sort, created_by, updated_by) VALUES (:l, :p, :c, :n, :nb, :s, :by, :by) RETURNING $NODE_COLS",
            ).bind("l", level).bind("p", parent).bind("c", code).bind("n", name).bind("nb", nameBn).bind("s", sort).bind("by", p.userId).map { rs, _ -> nodeOf(rs) }.one()
            AuditWriter.write(h, p, "product_node", n.id.toString(), "create", null, json(n), null, call.requestId)
            n
        }
    }
    call.etag(out.version)
    call.respond(HttpStatusCode.Created, out)
}

private suspend fun updateNode(call: ApplicationCall, d: AdminProductsDeps) {
    val p = call.masterWriter()
    val level = call.level(); val id = call.pathId(); val expected = call.ifMatch()
    val b = call.bodyObject()
    b.only(setOf("parent_id", "name", "name_bn", "sort", "status"))
    if (b.isEmpty()) adminBad("body", "empty_patch")
    val parent = b.id("parent_id", required = false)
    val name = b.str("name", 1, 120, required = false)?.trim()
    val nameBn = b.str("name_bn", 0, 120, required = false, nullable = true)
    val sort = b.int("sort", -1_000_000, 1_000_000, required = false)
    val status = b.enum("status", STATUSES, required = false)
    val out = mapDb {
        d.db.jdbi.inTransaction<ProductNodeOut, Exception> { h ->
            val cur = h.createQuery("SELECT $NODE_COLS FROM app.product_node WHERE id = :i AND level = :l FOR UPDATE").bind("i", id).bind("l", level).map { rs, _ -> nodeOf(rs) }.findOne().orElse(null)
                ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no $level with this id")
            if (cur.version != expected) preconditionFailed(cur.version)
            val set = mutableListOf<String>(); val args = mutableMapOf<String, Any?>()
            if (parent != null && parent != cur.parent_id) { checkParent(h, level, parent); set += "parent_id = :parent"; args["parent"] = parent } else if (parent != null) checkParent(h, level, parent)
            if (name != null && name != cur.name) { set += "name = :name"; args["name"] = name }
            if (b.containsKey("name_bn") && nameBn != cur.name_bn) { set += "name_bn = :nameBn"; args["nameBn"] = nameBn }
            if (sort != null && sort != cur.sort) { set += "sort = :sort"; args["sort"] = sort }
            if (status != null && status != cur.status) {
                if (status == "inactive" && hasActiveChildren(h, cur)) throw ApiProblem(ProblemCode.ERR_MASTER_IN_USE, "deactivate the active children first")
                set += "status = :status"; args["status"] = status
            }
            // A patch that changes nothing is a replay: no write, no new version, no audit row.
            if (set.isEmpty()) return@inTransaction cur
            val q = h.createQuery("UPDATE app.product_node SET ${set.joinToString(", ")}, updated_by = :by WHERE id = :i RETURNING $NODE_COLS").bind("by", p.userId).bind("i", id)
            args.forEach { (k, v) -> q.bind(k, v) }
            val n = q.map { rs, _ -> nodeOf(rs) }.one()
            AuditWriter.write(h, p, "product_node", id.toString(), "update", json(cur), json(n), null, call.requestId)
            n
        }
    }
    call.etag(out.version)
    call.respond(out)
}

private fun listSkus(call: ApplicationCall, d: AdminProductsDeps): SkuPageOut {
    call.masterReader()
    val limit = call.pageLimit(); val cursor = call.pageCursor(); val status = call.queryStatus(); val since = call.queryUpdatedSince()
    val search = call.request.queryParameters["q"]?.also { if (it.length !in 2..80) adminBad("query.q", "out_of_range") }
    val rows = d.db.jdbi.withHandle<List<SkuOut>, Exception> { h ->
        val where = mutableListOf("TRUE")
        if (status != null) where += "status = :status"
        if (since != null) where += "updated_at > :since"
        if (cursor != null) where += "id > :cursor"
        if (search != null) where += "(code ILIKE :s OR name ILIKE :s)"
        val q = h.createQuery("SELECT $SKU_COLS FROM app.sku WHERE ${where.joinToString(" AND ")} ORDER BY id LIMIT :lim").bind("lim", limit + 1)
        if (status != null) q.bind("status", status)
        if (since != null) q.bind("since", since)
        if (cursor != null) q.bind("cursor", cursor)
        if (search != null) q.bind("s", "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
        q.map { rs, _ -> skuOf(rs) }.list()
    }
    val page = rows.take(limit)
    return SkuPageOut(page, if (rows.size > limit) page.last().id.toString() else null)
}

private fun decimal3(b: JsonObject, k: String, required: Boolean): BigDecimal? {
    val v = b[k] ?: run { if (required) adminBad("body.$k", "required"); return null }
    if (v !is JsonPrimitive || !v.isString || !DECIMAL3.matches(v.content)) adminBad("body.$k", "not_decimal3")
    return BigDecimal(v.content).also { if (it.signum() <= 0) adminBad("body.$k", "out_of_range") }
}

private suspend fun createSku(call: ApplicationCall, d: AdminProductsDeps) {
    val p = call.masterWriter()
    val b = call.bodyObject()
    b.only(setOf("code", "variant_id", "category_code", "name", "short_name", "name_bn", "base_unit", "base_per_pack", "entry_unit_default", "report_unit", "report_factor", "sort"))
    val code = b.str("code", 1, 40, required = true)!!.also { if (!SKU_CODE.matches(it)) adminBad("body.code", "pattern") }
    val variant = b.id("variant_id", required = true)!!
    val category = b.enum("category_code", CATEGORY_UNIT.keys, required = true)!!
    val name = b.str("name", 1, 120, required = true)!!.trim()
    val shortName = b.str("short_name", 1, 20, required = true)!!.trim()
    val nameBn = b.str("name_bn", 0, 120, required = false, nullable = true)
    val baseUnit = b.enum("base_unit", UNITS, required = true)!!
    val perPack = b.int("base_per_pack", 1, 1000, required = true)!!
    val entry = b.enum("entry_unit_default", UNITS + "pack", required = true)!!
    val reportUnit = b.enum("report_unit", REPORT_UNITS, required = false, nullable = true)
    val factor = decimal3(b, "report_factor", required = true)!!
    val sort = b.int("sort", -1_000_000, 1_000_000, required = true)!!
    // Cigarettes and bidi count in sticks, lighters in pieces and matches in dozens (CLAUDE.md rule 5); lighter and match have no pack of sticks.
    if (CATEGORY_UNIT.getValue(category) != baseUnit) adminBad("body.base_unit", "wrong_unit_for_category")
    if (category in setOf("lighter", "match") && perPack != 1) adminBad("body.base_per_pack", "must_be_1")
    val out = mapDb {
        d.db.jdbi.inTransaction<SkuOut, Exception> { h ->
            val isVariant = h.createQuery("SELECT EXISTS (SELECT 1 FROM app.product_node WHERE id = :v AND level = 'variant')").bind("v", variant).mapTo(Boolean::class.java).one()
            if (!isVariant) adminBad("body.variant_id", "not_a_variant")
            val s = h.createQuery(
                "INSERT INTO app.sku (code, variant_id, category_code, name, short_name, name_bn, base_unit, base_per_pack, entry_unit_default, report_unit, report_factor, sort, created_by, updated_by) " +
                    "VALUES (:code, :v, :cat, :n, :sn, :nb, :bu, :pp, :eu, :ru, :rf, :s, :by, :by) RETURNING $SKU_COLS",
            ).bind("code", code).bind("v", variant).bind("cat", category).bind("n", name).bind("sn", shortName).bind("nb", nameBn).bind("bu", baseUnit).bind("pp", perPack)
                .bind("eu", entry).bind("ru", reportUnit).bind("rf", factor).bind("s", sort).bind("by", p.userId).map { rs, _ -> skuOf(rs) }.one()
            AuditWriter.write(h, p, "sku", s.id.toString(), "create", null, json(s), null, call.requestId)
            s
        }
    }
    call.etag(out.version)
    call.respond(HttpStatusCode.Created, out)
}

private suspend fun updateSku(call: ApplicationCall, d: AdminProductsDeps) {
    val p = call.masterWriter()
    val id = call.pathId(); val expected = call.ifMatch()
    val b = call.bodyObject()
    b.only(setOf("name", "short_name", "name_bn", "report_unit", "report_factor", "sort", "status", "thumbnail_media_uuid", "change_reason"))
    if (b.isEmpty()) adminBad("body", "empty_patch")
    val name = b.str("name", 1, 120, required = false)?.trim()
    val shortName = b.str("short_name", 1, 20, required = false)?.trim()
    val nameBn = b.str("name_bn", 0, 120, required = false, nullable = true)
    val reportUnit = b.enum("report_unit", REPORT_UNITS, required = false, nullable = true)
    val factor = decimal3(b, "report_factor", required = false)
    val sort = b.int("sort", -1_000_000, 1_000_000, required = false)
    val status = b.enum("status", STATUSES, required = false)
    val thumb = b.str("thumbnail_media_uuid", 36, 36, required = false, nullable = true)?.also { if (!UUID_V4.matches(it)) adminBad("body.thumbnail_media_uuid", "not_a_uuid") }
    val reason = b.str("change_reason", 10, 500, required = false, nullable = true)
    val out = mapDb {
        d.db.jdbi.inTransaction<SkuOut, Exception> { h ->
            val cur = h.createQuery("SELECT $SKU_COLS FROM app.sku WHERE id = :i FOR UPDATE").bind("i", id).map { rs, _ -> skuOf(rs) }.findOne().orElse(null)
                ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no SKU with this id")
            if (cur.version != expected) preconditionFailed(cur.version)
            val set = mutableListOf<String>(); val args = mutableMapOf<String, Any?>()
            if (name != null && name != cur.name) { set += "name = :name"; args["name"] = name }
            if (shortName != null && shortName != cur.short_name) { set += "short_name = :sn"; args["sn"] = shortName }
            if (b.containsKey("name_bn") && nameBn != cur.name_bn) { set += "name_bn = :nb"; args["nb"] = nameBn }
            if (b.containsKey("report_unit") && reportUnit != cur.report_unit) { set += "report_unit = :ru"; args["ru"] = reportUnit }
            if (factor != null && factor.compareTo(BigDecimal(cur.report_factor)) != 0) { set += "report_factor = :rf"; args["rf"] = factor }
            if (sort != null && sort != cur.sort) { set += "sort = :sort"; args["sort"] = sort }
            if (status != null && status != cur.status) { set += "status = :status"; args["status"] = status }
            if (b.containsKey("thumbnail_media_uuid") && thumb != cur.thumbnail_media_uuid) { set += "thumbnail_media_uuid = CAST(:tm AS uuid)"; args["tm"] = thumb }
            if (set.isEmpty()) return@inTransaction cur
            val q = h.createQuery("UPDATE app.sku SET ${set.joinToString(", ")}, updated_by = :by WHERE id = :i RETURNING $SKU_COLS").bind("by", p.userId).bind("i", id)
            args.forEach { (k, v) -> q.bind(k, v) }
            val s = q.map { rs, _ -> skuOf(rs) }.one()
            AuditWriter.write(h, p, "sku", id.toString(), "update", json(cur), json(s), reason, call.requestId)
            s
        }
    }
    call.etag(out.version)
    call.respond(out)
}

internal fun auditObj(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    pairs.forEach { (k, v) ->
        when (v) {
            null -> put(k, JsonNull)
            is Number -> put(k, v)
            is Boolean -> put(k, v)
            else -> put(k, v.toString())
        }
    }
}
