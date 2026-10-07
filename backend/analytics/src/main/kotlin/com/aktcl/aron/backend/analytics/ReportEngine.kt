package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.statement.Query
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth

val WEB_ROLES: Set<com.aktcl.aron.contract.Role> = com.aktcl.aron.contract.Role.entries.toSet() - com.aktcl.aron.contract.Role.SR - com.aktcl.aron.contract.Role.AMO

/** One SELECT per report: the engine adds scope, ordering, paging, totals, masking and the output format. */
class SqlSpec(val sql: String, val binds: Map<String, Any?> = emptyMap(), val totalColumns: List<String> = emptyList())

interface ReportHandler {
    val definition: ReportDefinition
    /** Roles that may run it (the server decides; the client never does). Default: the web dashboard roles. */
    val roles: Set<com.aktcl.aron.contract.Role> get() = WEB_ROLES
    /** The rows as one SELECT whose output columns are exactly the definition's column keys. Use [ReportContext] clauses for scope and dates. */
    fun spec(ctx: ReportContext): SqlSpec
}

/**
 * What one report run is allowed to read, derived on the server: the caller's reach narrowed by the optional geo selectors
 * (a selector outside the reach is 403, never silently dropped). Handlers put [zoneClause] and [routeClause] in their WHERE.
 */
class ReportContext(
    val query: ReportQuery, val from: LocalDate, val to: LocalDate, val principal: AronPrincipal,
    /** null = every zone. */ val zoneIds: List<Long>?, /** null = no route restriction. */ val routeIds: List<Long>?, val pii: Boolean,
) {
    fun zoneClause(col: String) = if (zoneIds == null) "true" else "$col = ANY(:zones)"
    fun routeClause(col: String) = if (routeIds == null) "true" else "$col = ANY(:routes)"
    fun dateClause(col: String) = "$col BETWEEN :from AND :to"

    internal fun bind(q: Query): Query {
        q.bind("from", from).bind("to", to)
        zoneIds?.let { q.bindArray("zones", Long::class.javaObjectType, it) }
        routeIds?.let { q.bindArray("routes", Long::class.javaObjectType, it) }
        return q
    }
}

class ReportEngine(
    private val db: Database, private val config: ServerConfig, private val clock: AronClock = AronClock.SYSTEM,
    handlers: List<ReportHandler>,
) {
    private val byKey = handlers.associateBy { it.definition.report_key }
    init { require(byKey.size == handlers.size) { "duplicate report key" } }

    fun definitions(): List<ReportDefinition> = byKey.values.sortedBy { it.definition.report_key }.map { it.definition }
    fun handler(key: String): ReportHandler = byKey[key] ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "unknown report")

    // ---------------- query validation and scope ----------------

    fun context(h: Handle, key: String, q: ReportQuery, reach: Reach, p: AronPrincipal, today: LocalDate): ReportContext {
        val def = handler(key).definition
        fun bad(field: String, why: String = "invalid_value"): Nothing =
            throw ApiProblem(ProblemCode.ERR_REPORT_INVALID_QUERY, "invalid $field", errors = listOf(FieldError("body.$field", why)))
        if (q.output.format !in def.formats) bad("output.format", "not_supported")
        if (q.output.page_size !in setOf(10, 25, 50, 100, 500)) bad("output.page_size")
        if (q.output.page < 1) bad("output.page")
        if (q.date_grouping !in setOf("total", "day", "week", "month")) bad("date_grouping")
        if (q.active_status !in setOf("all", "active", "inactive")) bad("active_status")
        if (q.field_force_type !in setOf("sr", "amo", "all")) bad("field_force_type")
        q.location?.let { if (it !in setOf("wing", "division", "territory", "zone", "route", "outlet", "user")) bad("location") }
        q.product_type?.let { if (it !in setOf("category", "brand", "variant", "sku")) bad("product_type") }
        for ((n, c) in listOf("std_criteria" to q.std_criteria, "memo_criteria" to q.memo_criteria)) if (c != null && c.op !in setOf(">", ">=", "=", "<=", "<")) bad("$n.op")
        q.outlet_code?.let { if (it.length > 32) bad("outlet_code") }
        for (s in q.output.sort) {
            // A masked (personal) column is not sortable: the order would reveal what the mask hides.
            if (def.columns.none { it.key == s.col && (p.pii || !it.pii) }) bad("output.sort.col", "unknown_column")
            if (s.dir != "asc" && s.dir != "desc") bad("output.sort.dir")
        }
        val (from, to) = period(q.period, today) { f, w -> bad(f, w) }
        if (to.isBefore(from) || java.time.temporal.ChronoUnit.DAYS.between(from, to) > 366) bad("period", "out_of_range")

        // Scope: the reach, narrowed by selectors. A selector the caller cannot reach is 403 (unknown ids too: no existence leak).
        val geo = q.geo
        var zones: List<Long>? = if (reach.national) null else reach.zoneIds.toList()
        var routes: List<Long>? = if (reach.ownRecordsOnly) reach.routeIds.toList() else null
        if (geo != null) {
            val selectors = listOf("wing_id" to geo.wing, "division_id" to geo.division, "territory_id" to geo.territory, "zone_id" to geo.zone)
            for ((col, ids) in selectors) {
                if (ids.isEmpty()) continue
                val sel = h.createQuery("SELECT DISTINCT zone_id FROM dw.dim_geo WHERE $col = ANY(:ids)").bindArray("ids", Long::class.javaObjectType, ids).mapTo(Long::class.java).list()
                val known = h.createQuery("SELECT count(DISTINCT $col) FROM dw.dim_geo WHERE $col = ANY(:ids)").bindArray("ids", Long::class.javaObjectType, ids).mapTo(Long::class.java).one()
                if (known != ids.distinct().size.toLong() || (zones != null && !zones.containsAll(sel))) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "a selected geography is outside your reach")
                zones = if (zones == null) sel else zones.intersect(sel.toSet()).toList()
            }
            if (geo.route.isNotEmpty()) {
                val rows = h.createQuery("SELECT route_id, zone_id FROM dw.dim_geo WHERE route_id = ANY(:ids)").bindArray("ids", Long::class.javaObjectType, geo.route)
                    .map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.list()
                val inReach = rows.filter { (r, z) -> (zones == null || z in zones!!) && (routes == null || r in routes!!) }
                if (inReach.size != geo.route.distinct().size) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "a selected route is outside your reach")
                routes = inReach.map { it.first }
            }
        }
        if (zones != null && zones.isEmpty()) zones = listOf(-1L)   // an empty reach reads nothing, never "everything"
        if (routes != null && routes.isEmpty()) routes = listOf(-1L)
        return ReportContext(q, from, to, p, zones, routes, p.pii)
    }

    private fun period(p: PeriodDto, today: LocalDate, bad: (String, String) -> Nothing): Pair<LocalDate, LocalDate> {
        fun d(s: String, f: String) = runCatching { LocalDate.parse(s) }.getOrNull() ?: bad("period.$f", "invalid_value")
        return when {
            p.date != null -> d(p.date, "date").let { it to it }
            p.month != null -> (runCatching { YearMonth.parse(p.month) }.getOrNull() ?: bad("period.month", "invalid_value")).let { it.atDay(1) to it.atEndOfMonth() }
            p.from != null || p.to != null -> {
                val f = p.from?.let { d(it, "from") } ?: p.to?.let { d(it, "to") }!!
                f to (p.to?.let { d(it, "to") } ?: f)
            }
            else -> today to today
        }
    }

    // ---------------- running ----------------

    private fun orderBy(def: ReportDefinition, q: ReportQuery): String =
        // Requested sort first, then every column by position: a total order, so paging never repeats or skips a row.
        (q.output.sort.map { "\"${it.col}\" ${it.dir}" } + (1..def.columns.size).map { it.toString() }).joinToString(", ")

    private fun wrapped(handler: ReportHandler, ctx: ReportContext): Pair<String, SqlSpec> {
        val spec = handler.spec(ctx)
        return "(${spec.sql}) r" to spec
    }

    private fun query(h: Handle, sql: String, ctx: ReportContext, spec: SqlSpec): Query {
        val q = h.createQuery(sql)
        q.configure(org.jdbi.v3.core.statement.SqlStatements::class.java) { it.isUnusedBindingAllowed = true }   // a report need not use every scope binding
        ctx.bind(q)
        spec.binds.forEach { (k, v) -> if (v is Array<*>) q.bindArray(k, Long::class.javaObjectType, v.map { it as Long }) else q.bind(k, v) }
        return q
    }

    /** Row count without fetching rows (used to choose inline or job and to refuse oversized exports). */
    fun count(h: Handle, key: String, ctx: ReportContext): Int {
        val (from, spec) = wrapped(handler(key), ctx)
        return query(h, "SELECT count(*) FROM $from", ctx, spec).mapTo(Int::class.java).one()
    }

    fun json(h: Handle, key: String, ctx: ReportContext): ReportResult {
        val handler = handler(key); val def = handler.definition; val q = ctx.query
        val (from, spec) = wrapped(handler, ctx)
        val total = count(h, key, ctx)
        val rows = query(h, "SELECT * FROM $from ORDER BY ${orderBy(def, q)} LIMIT :lim OFFSET :off", ctx, spec)
            .bind("lim", q.output.page_size).bind("off", (q.output.page - 1).toLong() * q.output.page_size)
            .map { rs, _ -> rowOf(rs, def, ctx.pii) }.list()
        val totalCols = spec.totalColumns.filter { k -> def.columns.any { it.key == k && (ctx.pii || !it.pii) } }
        val totals = if (totalCols.isEmpty()) null else
            query(h, "SELECT " + totalCols.joinToString(", ") { "coalesce(sum(\"$it\"), 0) AS \"$it\"" } + " FROM $from", ctx, spec)
                .map { rs, _ -> totalCols.associateWith { scalar(rs.getObject(it)) } }.one()
        return ReportResult(key, clock.now().wire(), visibleColumns(def, ctx.pii), rows, totals, q.output.page, q.output.page_size, total)
    }

    /** Streams every row (all pages) to [sink]; the caller has already checked the size against cfg.ops.report_export_max_rows. */
    fun stream(h: Handle, key: String, ctx: ReportContext, sink: (Map<String, JsonElement>) -> Unit): Int {
        val handler = handler(key); val def = handler.definition
        val (from, spec) = wrapped(handler, ctx)
        var n = 0
        query(h, "SELECT * FROM $from ORDER BY ${orderBy(def, ctx.query)}", ctx, spec).setFetchSize(1000)
            .map { rs, _ -> rowOf(rs, def, ctx.pii) }.useStream<Exception> { s -> s.forEach { sink(it); n++ } }
        return n
    }

    fun visibleColumns(def: ReportDefinition, pii: Boolean) = if (pii) def.columns else def.columns.filter { !it.pii }

    /** Registry defaults of docs/24 s9.5 when the server config does not carry the key yet. */
    fun syncMaxRows(): Int = runCatching { config.int("cfg.ops.report_sync_max_rows") }.getOrDefault(10_000)
    fun exportMaxRows(): Int = runCatching { config.int("cfg.ops.report_export_max_rows") }.getOrDefault(200_000)

    private fun rowOf(rs: ResultSet, def: ReportDefinition, pii: Boolean): Map<String, JsonElement> =
        buildMap { for (c in def.columns) if (pii || !c.pii) put(c.key, scalar(rs.getObject(c.key))) }

    /** Database value to JSON scalar: dates ISO, timestamps RFC 3339 UTC, numerics as numbers (money stays integer milli-taka). */
    private fun scalar(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(v)
        is Int -> JsonPrimitive(v); is Long -> JsonPrimitive(v); is Short -> JsonPrimitive(v.toInt())
        is BigDecimal -> if (v.scale() <= 0) JsonPrimitive(v.toBigInteger().toLong()) else JsonPrimitive(v)
        is Double -> JsonPrimitive(v); is Float -> JsonPrimitive(v.toDouble())
        is java.sql.Date -> JsonPrimitive(v.toLocalDate().toString())
        is java.sql.Timestamp -> JsonPrimitive(v.toInstant().wire())
        is OffsetDateTime -> JsonPrimitive(v.toInstant().wire())
        else -> JsonPrimitive(v.toString().take(2000))
    }

    // ---------------- export log (hash-chained audit_log, one row per print/xlsx/pdf export) ----------------

    fun logExport(h: Handle, exportId: String, key: String, format: String, ctx: ReportContext, rows: Int, requestId: String?) {
        val after = buildJsonObject {
            put("report_key", key); put("format", format); put("rows", rows); put("pii", ctx.pii && handler(key).definition.columns.any { it.pii })
            put("from", ctx.from.toString()); put("to", ctx.to.toString())
            put("filters", buildJsonObject { put("query", filtersText(ctx.query)) })
        }
        h.createUpdate(
            "INSERT INTO app.audit_log (actor_user_id, actor_username, actor_role, via, entity, entity_id, action, after, request_id) " +
                "VALUES (:uid, :uname, :role, 'api', 'report_export', :eid, :action, CAST(:after AS jsonb), CAST(:rid AS uuid))",
        ).bind("uid", ctx.principal.userId).bind("uname", ctx.principal.username.take(40)).bind("role", ctx.principal.role.wire)
            .bind("eid", exportId).bind("action", "export.$format").bind("after", after.toString())
            .bind("rid", requestId?.takeIf { runCatching { java.util.UUID.fromString(it) }.isSuccess }).execute()
    }

    private fun filtersText(q: ReportQuery): String = kotlinx.serialization.json.Json.encodeToString(ReportQuery.serializer(), q.copy(output = q.output.copy(sort = emptyList()))).take(1900)
}
