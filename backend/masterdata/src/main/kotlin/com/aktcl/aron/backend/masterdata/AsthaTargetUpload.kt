package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.AsthaQuarter
import com.aktcl.aron.backend.platform.AuditLog
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.requestId
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import com.aktcl.aron.rules.BusinessDate
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

// ---------------------------------------------------------------------------------------------------------------------
// PUT /v1/programmes/astha/targets (contract putAsthaTargets, 1.15.0; lane portal-loyalty-api, train 3, push B): the Astha target upload.
// Idempotent by `batch_uuid` (app.astha_target_batch holds the first counts and a fingerprint of the normalised rows: a replay answers the first
// counts, the same UUID with other rows is 409), all-or-nothing in one transaction, rows validated with indexed pointers, targets never negative
// (the -37,500% bug), `value_target_mtk` integer milli-taka text, the route derived from the outlet on the server, scope from the token only.
// Who may upload: SUPERADMIN and the roles of `cfg.astha.target_entry_roles` (default ADMIN, TSO); a TSO only for outlets of routes in the reach.
// Units: std_target is STD in base units (sticks, pieces) with up to 3 decimals, memo_target a count, value_target_mtk integer milli-taka.
// ---------------------------------------------------------------------------------------------------------------------

class AsthaTargetUploadDeps(
    val db: Database, val reach: ReachResolver, val config: ServerConfig, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM,
)

fun Route.asthaTargetUploadRoutes(d: AsthaTargetUploadDeps) {
    authenticated(d.guard) {
        put("/programmes/astha/targets") { call.respond(putAsthaTargets(call, d)) }
    }
}

@Serializable
data class AsthaBatchResultOut(val batch_uuid: String, val inserted: Int, val updated: Int, val unchanged: Int)

private const val MAX_ROWS = 50_000
private val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val STD_RE = Regex("^\\d{1,13}(\\.\\d{1,3})?$")
private val MTK_RE = Regex("^(0|[1-9][0-9]{0,17})$")
private val MONTH_RE = Regex("^(\\d{4})-(0[1-9]|1[0-2])$")

private class TargetRow(val outlet: Long, val brand: Long?, val month: LocalDate, val std: BigDecimal, val memo: Int, val value: Long)

/**
 * Roles that may enter targets: SUPERADMIN always, plus the roles named in `cfg.astha.target_entry_roles`, matched exactly as the contract enum spells them (upper case).
 * A malformed, missing or empty list FAILS CLOSED: SUPERADMIN only, never a fallback list (B4 of the Opus pass). Unknown names are ignored.
 */
internal fun asthaEntryRoles(config: ServerConfig): Set<Role> {
    val configured = runCatching { config.value("cfg.astha.target_entry_roles").jsonArray.mapNotNull { e -> Role.entries.firstOrNull { it.wire == e.jsonPrimitive.content } }.toSet() }.getOrNull().orEmpty()
    return configured + Role.SUPERADMIN
}

private fun readTargetRows(o: JsonObject, quarter: AsthaQuarter): List<TargetRow> {
    val a = o["rows"] as? JsonArray ?: adminBad("body.rows", "required")
    if (a.isEmpty() || a.size > MAX_ROWS) adminBad("body.rows", "out_of_range")
    val seen = HashSet<Triple<Long, Long, LocalDate>>()
    val months = quarter.months.map { it.toString().substring(0, 7) }.toSet()
    return a.mapIndexed { i, e ->
        val r = e as? JsonObject ?: adminBad("body.rows[$i]", "not_an_object")
        atElement("body.rows[$i]") {
            r.only(setOf("outlet_id", "brand_id", "month", "std_target", "memo_target", "value_target_mtk"))
            val outlet = r.id("outlet_id", required = true)!!
            val brand = r.id("brand_id", required = false, nullable = true)
            val monthText = r.str("month", 7, 7, required = true)!!
            if (!MONTH_RE.matches(monthText)) adminBad("body.month", "pattern")
            if (monthText !in months) adminBad("body.month", "outside_quarter")
            val stdText = (r["std_target"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: adminBad("body.std_target", if (r.containsKey("std_target")) "not_a_decimal" else "required")
            if (!STD_RE.matches(stdText)) adminBad("body.std_target", if (stdText.startsWith("-")) "negative" else "not_a_decimal")   // never negative: the -37,500% bug
            val memo = r.int("memo_target", 0, 1_000_000, required = true)!!
            val value = when (val v = r["value_target_mtk"]) {
                null -> 0L
                is JsonNull -> adminBad("body.value_target_mtk", "null_not_allowed")
                else -> {
                    val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: adminBad("body.value_target_mtk", "not_a_string")
                    if (!MTK_RE.matches(s)) adminBad("body.value_target_mtk", if (s.startsWith("-")) "negative" else "not_an_integer")
                    s.toLongOrNull() ?: adminBad("body.value_target_mtk", "out_of_range")
                }
            }
            val month = LocalDate.parse("$monthText-01")
            if (!seen.add(Triple(outlet, brand ?: 0L, month))) adminBad("body.rows[$i]", "duplicate")
            TargetRow(outlet, brand, month, BigDecimal(stdText).setScale(3), memo, value)
        }
    }
}

private fun fingerprint(quarter: String, rows: List<TargetRow>, reason: String): String {
    val text = buildString {
        append("astha_targets|").append(quarter).append('|').append(reason.trim())
        rows.map { "${it.outlet}:${it.brand ?: 0}:${it.month}:${it.std.toPlainString()}:${it.memo}:${it.value}" }.sorted().forEach { append('|').append(it) }
    }
    return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

private fun forbidden(msg: String, pointer: String? = null) =
    ApiProblem(ProblemCode.ERR_FORBIDDEN, msg, errors = pointer?.let { listOf(FieldError(it, "out_of_reach")) } ?: emptyList())

private suspend fun putAsthaTargets(call: ApplicationCall, d: AsthaTargetUploadDeps): AsthaBatchResultOut {
    val p: AronPrincipal = call.principal
    if (p.role !in asthaEntryRoles(d.config)) throw forbidden("Astha targets are entered by the roles of cfg.astha.target_entry_roles")
    val o = call.bodyObject()
    o.only(setOf("batch_uuid", "quarter", "rows", "change_reason"))
    val batch = o.str("batch_uuid", 36, 36, required = true)!!.also { if (!UUID_V4.matches(it)) adminBad("body.batch_uuid", "not_a_uuid") }.let(UUID::fromString)
    val quarterLabel = o.str("quarter", 7, 7, required = true)!!
    val startMonth = runCatching { d.config.int("cfg.astha.quarter_start_month") }.getOrDefault(1)
    val quarter = AsthaQuarter.parse(quarterLabel, startMonth) ?: adminBad("body.quarter", "pattern")
    val reason = o.str("change_reason", 10, 500, required = true)!!.trim().also { if (it.length < 10) adminBad("body.change_reason", "length") }
    val rows = readTargetRows(o, quarter)
    val fp = fingerprint(quarterLabel, rows, reason)
    val today = BusinessDate.of(d.clock.now().toEpochMilli()).toJavaLocalDate()
    val reach: Reach = d.reach.reach(p.userId, p.role, p.scopeVersion, today)   // every role is judged by its reach (national roles pass BY reach, not by skipping the check)
    return mapDb {
        d.db.jdbi.inTransaction<AsthaBatchResultOut, Exception> { h ->
            h.createQuery("SELECT pg_advisory_xact_lock(hashtext(:k))").bind("k", "astha_batch:$batch").mapTo(String::class.java).findOne()
            val outlets = rows.map { it.outlet }.distinct()
            val route = h.createQuery("SELECT o.id, o.route_id, r.zone_id FROM app.outlet o JOIN app.route r ON r.id = o.route_id WHERE o.id IN (<ids>)").bindList("ids", outlets)
                .map { rs, _ -> rs.getLong(1) to (rs.getLong(2) to rs.getLong(3)) }.list().toMap()
            rows.forEachIndexed { i, r ->
                val (routeId, zoneId) = route[r.outlet] ?: adminBad("body.rows[$i].outlet_id", "not_found")
                if (!reach.coversRoute(routeId, zoneId)) throw forbidden("outlet ${r.outlet} is not in your territory", "body.rows[$i].outlet_id")
            }
            // The caller's reach is judged BEFORE a replay is answered: a replay never shows counts to someone who may no longer upload these outlets.
            h.createQuery("SELECT request_fingerprint, inserted, updated, unchanged FROM app.astha_target_batch WHERE batch_uuid = :b").bind("b", batch)
                .map { rs, _ -> arrayOf<Any>(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)) }.findOne().orElse(null)?.let { r ->
                    // The FIRST result answers a replay (role and reach were judged above).
                    if (r[0] != fp) throw ApiProblem(ProblemCode.ERR_CONFLICT, "batch_uuid already used for a different batch", errors = listOf(FieldError("body.batch_uuid", "reused")))
                    return@inTransaction AsthaBatchResultOut(batch.toString(), r[1] as Int, r[2] as Int, r[3] as Int)
                }
            // Serialise the quarter's batches: the counts below are computed against the rows this transaction is about to write.
            h.createQuery("SELECT pg_advisory_xact_lock(hashtext(:k))").bind("k", "astha_quarter:$quarterLabel").mapTo(String::class.java).findOne()
            val brands = rows.mapNotNull { it.brand }.distinct()
            if (brands.isNotEmpty()) {
                val known = h.createQuery("SELECT id FROM app.product_node WHERE level = 'brand' AND id IN (<ids>)").bindList("ids", brands).mapTo(Long::class.java).list().toSet()
                rows.forEachIndexed { i, r -> if (r.brand != null && r.brand !in known) adminBad("body.rows[$i].brand_id", "not_a_brand") }
            }
            val programmes = h.createQuery("SELECT id FROM app.programme WHERE kind = 'astha' AND status = 'active' AND active_from <= :last AND active_to >= :first ORDER BY id LIMIT 2")
                .bind("first", quarter.months.first()).bind("last", quarter.months.last().plusMonths(1).minusDays(1)).mapTo(Long::class.java).list()
            val programmeId: Long? = programmes.singleOrNull()   // bound only when exactly one Astha programme is active in the quarter; otherwise the row is programme-less (the bundle reads by route and month)
            val n = rows.size
            val months = Array<String>(n) { rows[it].month.toString() }
            val routes = Array<Long>(n) { route.getValue(rows[it].outlet).first }
            val outletArr = Array<Long>(n) { rows[it].outlet }
            val brandArr = Array<Long>(n) { rows[it].brand ?: 0L }
            val stdArr = Array<String>(n) { rows[it].std.toPlainString() }
            val memoArr = Array<Int>(n) { rows[it].memo }
            val valueArr = Array<Long>(n) { rows[it].value }
            val inp = "SELECT * FROM unnest(CAST(:months AS date[]), CAST(:routes AS bigint[]), CAST(:outlets AS bigint[]), CAST(:brands AS bigint[]), CAST(:std AS numeric[]), CAST(:memo AS int[]), CAST(:val AS bigint[])) " +
                "AS t(month, route_id, outlet_id, brand0, std_target, memo_target, value_target_mtk)"
            fun <Q : org.jdbi.v3.core.statement.SqlStatement<Q>> Q.bindRows(): Q = bindArray("months", String::class.java, months.toList()).bindArray("routes", Long::class.javaObjectType, routes.toList())
                .bindArray("outlets", Long::class.javaObjectType, outletArr.toList()).bindArray("brands", Long::class.javaObjectType, brandArr.toList())
                .bindArray("std", String::class.java, stdArr.toList()).bindArray("memo", Int::class.javaObjectType, memoArr.toList()).bindArray("val", Long::class.javaObjectType, valueArr.toList())
            val counts = h.createQuery(
                "WITH inp AS ($inp) SELECT count(*) FILTER (WHERE a.id IS NULL), " +
                    "count(*) FILTER (WHERE a.id IS NOT NULL AND (a.route_id <> i.route_id OR a.std_target <> i.std_target OR a.memo_target <> i.memo_target OR a.value_target_mtk <> i.value_target_mtk)) " +
                    "FROM inp i LEFT JOIN app.astha_target a ON a.month = i.month AND coalesce(a.brand_id, 0) = i.brand0 AND a.outlet_id = i.outlet_id",
            ).bindRows().map { rs, _ -> rs.getInt(1) to rs.getInt(2) }.one()
            h.createUpdate(
                "INSERT INTO app.astha_target (programme_id, month, route_id, outlet_id, brand_id, std_target, memo_target, value_target_mtk, batch_uuid, created_by) " +
                    "SELECT CAST(:prog AS bigint), month, route_id, outlet_id, NULLIF(brand0, 0), std_target, memo_target, value_target_mtk, :batch, :by FROM ($inp) AS x " +
                    "ON CONFLICT (month, outlet_id, coalesce(brand_id, 0)) WHERE outlet_id IS NOT NULL DO UPDATE SET route_id = EXCLUDED.route_id, std_target = EXCLUDED.std_target, memo_target = EXCLUDED.memo_target, " +
                    "value_target_mtk = EXCLUDED.value_target_mtk, batch_uuid = EXCLUDED.batch_uuid, updated_at = now() " +
                    "WHERE app.astha_target.route_id <> EXCLUDED.route_id OR app.astha_target.std_target <> EXCLUDED.std_target OR app.astha_target.memo_target <> EXCLUDED.memo_target OR app.astha_target.value_target_mtk <> EXCLUDED.value_target_mtk",
            ).bind("prog", programmeId as Any?).bind("batch", batch).bind("by", p.userId).bindRows().execute()
            val inserted = counts.first; val updated = counts.second; val unchanged = n - inserted - updated
            h.createUpdate(
                "INSERT INTO app.astha_target_batch (batch_uuid, quarter, request_fingerprint, row_count, inserted, updated, unchanged, reason, actor) VALUES (:b, :q, :fp, :n, :i, :u, :c, :r, :a)",
            ).bind("b", batch).bind("q", quarterLabel).bind("fp", fp).bind("n", n).bind("i", inserted).bind("u", updated).bind("c", unchanged).bind("r", reason).bind("a", p.userId).execute()
            AuditLog.write(
                h, p, "astha_target", quarterLabel, "upload", null,
                auditObj("batch_uuid" to batch.toString(), "rows" to n, "inserted" to inserted, "updated" to updated, "unchanged" to unchanged), reason, call.requestId, via = if (p.isPhone) "api" else "web",
            )
            AsthaBatchResultOut(batch.toString(), inserted, updated, unchanged)
        }
    }
}
