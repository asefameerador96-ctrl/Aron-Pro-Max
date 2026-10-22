package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.config.AuditWriter
import com.aktcl.aron.backend.masterdata.AdminSupport.bad
import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.receiveStrict
import com.aktcl.aron.backend.platform.requestId
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
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
import java.time.YearMonth

class RouteTargetDeps(val db: Database, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM)

private val WRITERS = setOf(Role.ADMIN, Role.SUPERADMIN)
private val READERS = setOf(Role.ADMIN, Role.SUPERADMIN, Role.SUPPORT)
private const val MAX_ROWS = 20_000
private const val MAX_QTY = 1_000_000_000L
private const val MAX_ERRORS = 1000
private const val MAX_BODY_BYTES = 4L * 1024 * 1024
private val MONTH_RE = Regex("^[0-9]{4}-(0[1-9]|1[0-2])$")
/** Route kinds that can carry a target: today `sr`; `normal` and `manual` once db renames the kinds (docs/34 s2). */
private val SELLING_KINDS = setOf("sr", "normal", "manual")

/**
 * D-TARGET-MIN-01 (T1, money-adjacent): the route x SKU monthly target upload. The portal reads the Excel sheet in the browser and sends JSON.
 * `PUT /admin/route-targets` with `dry_run: true` validates and reports every row error without writing; `dry_run: false` commits all rows
 * or none, upserting by (route, SKU, month), audited with the reason. Idempotent by `batch_uuid` (same content replays, other content is a
 * conflict). A month before the current Dhaka month is never rewritten.
 */
fun Route.routeTargetRoutes(d: RouteTargetDeps) {
    authenticated(d.guard) {
        get("/admin/route-targets") { call.respond(list(call, d)) }
        put("/admin/route-targets") { call.respond(put(call, d)) }
    }
}

private class ParsedRow(val index: Int, val route: String?, val sku: String?, val month: LocalDate?, val monthOk: Boolean, val qty: Long?, val monthRaw: String?)

private class RowError(val row: Int, val column: String, val code: String)

private fun tableReady(h: Handle) = h.createQuery("SELECT to_regclass('app.route_monthly_target') IS NOT NULL AND to_regclass('app.route_target_batch') IS NOT NULL").mapTo(Boolean::class.java).one()

private fun parseRows(arr: JsonArray): List<ParsedRow> = arr.mapIndexed { i, el ->
    val o = el as? JsonObject ?: bad("body.rows/$i", "invalid_type")
    o.keys.firstOrNull { it !in setOf("route_code", "sku_code", "month", "qty_base") }?.let { bad("body.rows/$i/$it", "unknown_member") }
    fun text(k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() && it.length <= 40 && '\u0000' !in it }
    val monthRaw = (o["month"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
    val month = monthRaw?.takeIf { MONTH_RE.matches(it) }?.let { YearMonth.parse(it).atDay(1) }
    // An integer in range only: 12.5, "12", true, null and 1e3 are not quantities.
    val q = o["qty_base"]?.let { it as? JsonPrimitive }?.takeIf { !it.isString && it !is JsonNull }?.let { p -> p.content.takeIf { c -> c.matches(Regex("^[0-9]{1,12}$")) }?.toLongOrNull() }?.takeIf { it in 0..MAX_QTY }
    ParsedRow(i, text("route_code"), text("sku_code"), month, month != null, q, monthRaw)
}

private class Checked(val errors: List<RowError>, val routeIds: Map<String, Long>, val skuIds: Map<String, Long>, val ok: List<ParsedRow>)

private fun check(h: Handle, rows: List<ParsedRow>, currentMonth: LocalDate): Checked {
    val routeCodes = rows.mapNotNull { it.route }.distinct()
    val skuCodes = rows.mapNotNull { it.sku }.distinct()
    val routes = HashMap<String, Pair<Long, String>>()
    h.createQuery("SELECT id, code, kind FROM app.route WHERE code = ANY(:c) AND status = 'active'").bindArray("c", String::class.java, routeCodes)
        .map { rs, _ -> rs.getString("code") to (rs.getLong("id") to rs.getString("kind")) }.list().forEach { routes[it.first] = it.second }
    val skus = HashMap<String, Long>()
    h.createQuery("SELECT id, code FROM app.sku WHERE code = ANY(:c) AND status = 'active'").bindArray("c", String::class.java, skuCodes)
        .map { rs, _ -> rs.getString("code") to rs.getLong("id") }.list().forEach { skus[it.first] = it.second }
    val errors = ArrayList<RowError>()
    val ok = ArrayList<ParsedRow>()
    val seen = HashSet<Triple<String, String, LocalDate>>()
    for (r in rows) {
        val row = r.index + 1
        var failed = false
        val rt = r.route?.let { routes[it] }
        if (rt == null) { errors += RowError(row, "route", "unknown_route"); failed = true }
        else if (rt.second !in SELLING_KINDS) { errors += RowError(row, "route", "not_selling_route"); failed = true }
        if (r.sku == null || skus[r.sku] == null) { errors += RowError(row, "sku", "unknown_sku"); failed = true }
        if (r.month == null) { errors += RowError(row, "month", "invalid_month"); failed = true }
        else if (r.month.isBefore(currentMonth)) { errors += RowError(row, "month", "past_month"); failed = true }
        if (r.qty == null) { errors += RowError(row, "quantity", "invalid_quantity"); failed = true }
        if (!failed && !seen.add(Triple(r.route!!, r.sku!!, r.month!!))) { errors += RowError(row, "route", "duplicate_row"); failed = true }
        if (!failed) ok += r
    }
    return Checked(errors, routes.mapValues { it.value.first }, skus, ok)
}

private const val MATCH = "app.route_monthly_target t JOIN unnest(:r, :s, CAST(:m AS date[])) AS u(route_id, sku_id, month) ON t.route_id = u.route_id AND t.sku_id = u.sku_id AND t.month = u.month"

private fun <T : org.jdbi.v3.core.statement.SqlStatement<T>> T.bindKeys(c: Checked): T =
    bindArray("r", Long::class.javaObjectType, c.ok.map { c.routeIds.getValue(it.route!!) })
        .bindArray("s", Long::class.javaObjectType, c.ok.map { c.skuIds.getValue(it.sku!!) })
        .bindArray("m", String::class.java, c.ok.map { it.month.toString() })

private fun replaces(h: Handle, c: Checked): Int =
    if (c.ok.isEmpty()) 0 else h.createQuery("SELECT count(*) FROM $MATCH").bindKeys(c).mapTo(Int::class.java).one()

private fun replacedTotal(h: Handle, c: Checked): Long =
    if (c.ok.isEmpty()) 0 else h.createQuery("SELECT COALESCE(sum(t.qty_base), 0) FROM $MATCH").bindKeys(c).mapTo(Long::class.java).one()

/** Order-independent digest of the batch content: two sends of the same rows in another order are the same batch. */
private fun digest(rows: List<ParsedRow>): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    rows.map { "${it.route}|${it.sku}|${it.month}|${it.qty}" }.sorted().forEach { md.update(it.toByteArray()); md.update(10) }
    return md.digest()
}

private fun lock(h: Handle) { h.createQuery("SELECT pg_advisory_xact_lock(hashtextextended('route-target-upload', 0))").mapTo(String::class.java).findOne() }

private fun currentMonth(d: RouteTargetDeps): LocalDate = AdminSupport.today(d.clock).withDayOfMonth(1)

private suspend fun put(call: ApplicationCall, d: RouteTargetDeps): JsonElement {
    val p = call.principal
    if (p.role !in WRITERS) throw AdminSupport.forbidden("route targets are written by ADMIN and SUPERADMIN")
    val body = call.receiveStrict(JsonObject.serializer(), MAX_BODY_BYTES)
    body.keys.firstOrNull { it !in setOf("batch_uuid", "rows", "dry_run", "change_reason") }?.let { bad("body.$it", "unknown_member") }
    val uuid = AdminSupport.uuid((body["batch_uuid"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bad("body.batch_uuid", "required"), "body.batch_uuid")
    val dry = (body["dry_run"] as? JsonPrimitive)?.takeIf { !it.isString }?.content.let { when (it) { "true" -> true; "false" -> false; else -> bad("body.dry_run", "required") } }
    val arr = body["rows"] as? JsonArray ?: bad("body.rows", "required")
    if (arr.size !in 1..MAX_ROWS) bad("body.rows", "length")
    val rows = parseRows(arr)
    val month = currentMonth(d)
    if (dry) return d.db.jdbi.withHandle<JsonElement, Exception> { h ->
        if (!tableReady(h)) throw ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "route targets are not deployed in this environment")
        val c = check(h, rows, month)
        buildJsonObject {
            put("batch_uuid", uuid.toString()); put("rows", rows.size)
            put("routes", c.ok.mapNotNull { it.route }.distinct().size); put("skus", c.ok.mapNotNull { it.sku }.distinct().size)
            put("months", buildJsonArray { c.ok.mapNotNull { it.month }.distinct().sorted().forEach { m -> add(JsonPrimitive(YearMonth.from(m).toString())) } })
            put("total_qty_base", c.ok.sumOf { it.qty!! }); put("replaces", replaces(h, c))
            put("errors", buildJsonArray { c.errors.take(MAX_ERRORS).forEach { e -> add(buildJsonObject { put("row", e.row); put("column", e.column); put("code", e.code) }) } })
            put("errors_truncated", c.errors.size > MAX_ERRORS)
        }
    }
    val reason = AdminSupport.reason((body["change_reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
    AdminSupport.noNul("body.change_reason", reason)
    return admWrite {
        d.db.jdbi.inTransaction<JsonElement, Exception> { h ->
            if (!tableReady(h)) throw ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "route targets are not deployed in this environment")
            lock(h)
            val sha = digest(rows)
            // Replay first: the stored batch answers before the month window is judged again (a retry after midnight of the last day must not fail).
            h.createQuery("SELECT content_sha256, row_count, committed_by FROM app.route_target_batch WHERE batch_uuid = :u").bind("u", uuid)
                .map { rs, _ -> Triple(rs.getBytes(1), rs.getInt(2), rs.getLong(3)) }.findOne().orElse(null)?.let { (stored, n, by) ->
                    if (!stored.contentEquals(sha) || by != p.userId) throw ApiProblem(ProblemCode.ERR_CONFLICT, "batch_uuid was already used for a different batch")
                    return@inTransaction buildJsonObject { put("batch_uuid", uuid.toString()); put("rows", n); put("replayed", true) }
                }
            val c = check(h, rows, month)
            if (c.errors.isNotEmpty()) throw ApiProblem(ProblemCode.ERR_VALIDATION, "${c.errors.size} row errors", errors = c.errors.take(MAX_ERRORS).map { e ->
                FieldError("body.rows/${e.row - 1}/" + when (e.column) { "route" -> "route_code"; "sku" -> "sku_code"; "quantity" -> "qty_base"; else -> "month" }, e.code)
            })
            val replaced = replaces(h, c)
            val before = replacedTotal(h, c)
            h.createUpdate("INSERT INTO app.route_target_batch (batch_uuid, content_sha256, row_count, committed_by) VALUES (:u, :h, :n, :by)")
                .bind("u", uuid).bind("h", sha).bind("n", rows.size).bind("by", p.userId).execute()
            val batch = h.prepareBatch(
                "INSERT INTO app.route_monthly_target (route_id, sku_id, month, qty_base, batch_uuid, updated_by) VALUES (:r, :s, :m, :q, :b, :by) " +
                    "ON CONFLICT (route_id, sku_id, month) DO UPDATE SET qty_base = EXCLUDED.qty_base, batch_uuid = EXCLUDED.batch_uuid, updated_by = EXCLUDED.updated_by, updated_at = now()",
            )
            c.ok.forEach { r -> batch.bind("r", c.routeIds.getValue(r.route!!)).bind("s", c.skuIds.getValue(r.sku!!)).bind("m", r.month).bind("q", r.qty!!).bind("b", uuid).bind("by", p.userId).add() }
            batch.execute()
            AuditWriter.write(h, p, "route_target", uuid.toString(), "upload", null, buildJsonObject {
                put("rows", rows.size); put("replaced_rows", replaced); put("replaced_total_qty_base", before); put("total_qty_base", c.ok.sumOf { it.qty!! })
                put("months", buildJsonArray { c.ok.mapNotNull { it.month }.distinct().sorted().forEach { m -> add(JsonPrimitive(YearMonth.from(m).toString())) } })
            }, reason, call.requestId)
            buildJsonObject { put("batch_uuid", uuid.toString()); put("rows", rows.size); put("replayed", false) }
        }
    }
}

private fun list(call: ApplicationCall, d: RouteTargetDeps): JsonElement {
    val p = call.principal
    if (p.role !in READERS) throw AdminSupport.forbidden("route targets are read by ADMIN, SUPERADMIN and SUPPORT")
    val monthRaw = call.request.queryParameters["month"]
    val month = if (monthRaw == null) currentMonth(d) else (monthRaw.takeIf { MONTH_RE.matches(it) } ?: bad("query.month")).let { YearMonth.parse(it).atDay(1) }
    val limit = AdminSupport.limit(call, 100, 500)
    val after = AdminSupport.decodeCursor(call, 2)
    return d.db.jdbi.withHandle<JsonElement, Exception> { h ->
        if (!tableReady(h)) throw ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "route targets are not deployed in this environment")
        val q = h.createQuery(
            "SELECT r.code AS route_code, r.name AS route_name, k.code AS sku_code, t.qty_base FROM app.route_monthly_target t JOIN app.route r ON r.id = t.route_id JOIN app.sku k ON k.id = t.sku_id " +
                "WHERE t.month = :m" + (if (after != null) " AND (r.code, k.code) > (:rc, :sc)" else "") + " ORDER BY r.code, k.code LIMIT :lim",
        ).bind("m", month).bind("lim", limit + 1)
        if (after != null) q.bind("rc", after[0]).bind("sc", after[1])
        val rows = q.map { rs, _ -> Triple(rs.getString("route_code"), rs.getString("route_name"), rs.getString("sku_code") to rs.getLong("qty_base")) }.list()
        val page = rows.take(limit)
        buildJsonObject {
            put("items", buildJsonArray {
                page.forEach { (rc, rn, sk) -> add(buildJsonObject { put("route_code", rc); put("route_name", rn); put("sku_code", sk.first); put("month", YearMonth.from(month).toString()); put("qty_base", sk.second) }) }
            })
            if (rows.size > limit) put("next_cursor", AdminSupport.encodeCursor(page.last().first, page.last().third.first)) else put("next_cursor", JsonNull)
        }
    }
}
