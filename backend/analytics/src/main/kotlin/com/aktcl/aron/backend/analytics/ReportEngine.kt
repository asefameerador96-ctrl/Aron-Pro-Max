package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuditLog
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

/** Roles that run business reports: the web dashboard roles less SUPPORT, which sees sync health only (docs/24 s8.5, AZ-2; sync health is `/v1/dashboards/sync-health`). */
val REPORT_ROLES: Set<com.aktcl.aron.contract.Role> = com.aktcl.aron.contract.Role.entries.toSet() - com.aktcl.aron.contract.Role.SR - com.aktcl.aron.contract.Role.AMO - com.aktcl.aron.contract.Role.SUPPORT
/** The old name, kept so handlers written against it still compile and still leave SUPPORT out. */
@Deprecated("use REPORT_ROLES", ReplaceWith("REPORT_ROLES"))
val WEB_ROLES: Set<com.aktcl.aron.contract.Role> get() = REPORT_ROLES

/** One SELECT per report: the engine adds scope, ordering, paging, totals, masking and the output format. */
class SqlSpec(val sql: String, val binds: Map<String, Any?> = emptyMap(), val totalColumns: List<String> = emptyList())

interface ReportHandler {
    val definition: ReportDefinition
    /** Roles that may run it (the server decides; the client never does). Default: the web dashboard roles. */
    val roles: Set<com.aktcl.aron.contract.Role> get() = REPORT_ROLES
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
    /** cfg.geo.suspicious_score_threshold: the user-day score that makes a day suspicious (docs/24 s11.4). */ val suspiciousThreshold: Int = Aggregator.DEFAULT_SUSPICIOUS_THRESHOLD,
    /** Successful Login (login AND download AND check-in) over `dw.agg_daily_route` columns of alias `a`; see [DashboardSummaries.successfulLoginSql]. */ val successfulLogin: String = "a.day_state <> 'not_started'",
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
                // As was: the zones the selected nodes had on the dates of the period (D-REPORTS-NODEASWAS-01), then narrowed by the reach below.
                val members = memberZones(h, col, ids.distinct(), from, to)
                // Every selected node, each by itself: a node that exists and lies inside today's reach (D-LEAD-REPORT-SELECTOR-01: scope only; one with no member in the period reads no rows).
                if (!members.allowed(if (reach.national) null else reach.zoneIds, ids.distinct())) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "a selected geography is outside your reach")
                val sel = members.zones
                zones = if (zones == null) sel else zones.intersect(sel.toSet()).toList()
            }
            if (geo.route.isNotEmpty()) {
                // D-REPORTS-NODEASWAS-05/-06, ruling D-LEAD-REPORT-SELECTOR-01. A refusal is for SCOPE only: an unknown id or a route outside the caller's reach
                // (today's reach: one of the zones it has or had in the period, or the zone it has today, is in the reach; an assignment in reach.routeIds does not widen the zones) is 403, the same answer for both.
                // A route inside the reach that was not in the selected zones during the period is a 200 with no rows.
                val ids = geo.route.distinct()
                val known = h.createQuery("SELECT id FROM app.route WHERE id = ANY(:ids)").bindArray("ids", Long::class.javaObjectType, ids).mapTo(Long::class.javaObjectType).set()
                // The zones a route had on the dates of the period (app.route_zone_history). A route with no history row at all (rows loaded with triggers off,
                // a bulk import) is placed by the zone it has now (dw.dim_geo), as AsWasSql.geo does for the Ops lists.
                val rows = h.createQuery(
                    "SELECT route_id, zone_id FROM app.route_zone_history WHERE route_id = ANY(:ids) AND daterange(valid_from, valid_to, '[)') && daterange(:f, :t, '[]') " +
                        "UNION SELECT g.route_id, g.zone_id FROM dw.dim_geo g WHERE g.route_id = ANY(:ids) AND NOT EXISTS (SELECT 1 FROM app.route_zone_history z WHERE z.route_id = g.route_id)",
                )
                    .bindArray("ids", Long::class.javaObjectType, ids).bind("f", from).bind("t", to)
                    .map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.list().groupBy({ it.first }, { it.second })
                // The zone a route has today, for the reach test (a route in the caller's zone today is theirs; one that left it stays readable only for the dates it was there).
                val nowZone = h.createQuery("SELECT id, zone_id FROM app.route WHERE id = ANY(:ids)").bindArray("ids", Long::class.javaObjectType, ids)
                    .map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.list().toMap()
                val reachZones = if (reach.national) null else reach.zoneIds
                fun inReach(r: Long) = (routes == null || r in routes!!) &&
                    (reachZones == null || rows[r].orEmpty().any { it in reachZones } || nowZone[r]?.let { it in reachZones } == true)
                if (ids.any { it !in known || !inReach(it) }) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "a selected route is outside your reach")
                val sel = ids.filter { r -> rows[r].orEmpty().any { zones == null || it in zones!! } }
                routes = sel.ifEmpty { NO_ROUTE }   // in reach but not in the selected zones during the period: no rows, never "everything"
            }
        }
        if (zones != null && zones.isEmpty()) zones = listOf(-1L)   // an empty reach reads nothing, never "everything"
        if (routes != null && routes.isEmpty()) routes = NO_ROUTE
        return ReportContext(q, from, to, p, zones, routes, p.pii, runCatching { config.int("cfg.geo.suspicious_score_threshold") }.getOrNull() ?: Aggregator.DEFAULT_SUSPICIOUS_THRESHOLD, DashboardSummaries.successfulLoginSql(h, "a"))
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

    fun json(h: Handle, key: String, ctx: ReportContext): ReportResult = jsonUncharged(h, key, ctx).also { if (readsPii(key, ctx)) chargeListRead(ctx, it.rows.size) }

    private fun jsonUncharged(h: Handle, key: String, ctx: ReportContext): ReportResult {
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
            put("scope", auditScope(ctx.zoneIds == null, ctx.zoneIds, ctx.routeIds))
        }
        AuditLog.write(h, ctx.principal, "report_export", exportId, "export.$format", null, after, null, requestId, via = "api")
    }

    /** True when the result would carry unmasked personal columns to this caller. */
    fun readsPii(key: String, ctx: ReportContext): Boolean = ctx.pii && handler(key).definition.columns.any { it.pii }

    private fun scopeHash(ctx: ReportContext): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest("${ctx.zoneIds?.sorted()}|${ctx.routeIds?.sorted()}".toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * Opens an export in the log and, for a personal-column export, reserves its rows against the daily budget in the same transaction: a per-user advisory
     * lock serialises the check and the insert, so parallel exports cannot all pass the same remaining budget. [status] is `queued` (job) or `running` (sync).
     */
    fun beginExport(exportId: String, key: String, format: String, ctx: ReportContext, rows: Int, status: String) {
        db.jdbi.useTransaction<Exception> { h ->
            if (readsPii(key, ctx)) {
                h.execute("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "pii-export:" + ctx.principal.userId)
                checkExportBudget(h, ctx, rows)
            }
            recordExport(h, exportId, key, format, ctx, rows, status)
        }
    }

    /** A synchronous export that produced its file: `running` becomes `done` with its row count. */
    fun finishExport(exportId: String, rows: Int) {
        db.jdbi.useHandle<Exception> { h ->
            h.createUpdate("UPDATE app.report_export SET status = 'done', row_count = :n, finished_at = :f WHERE export_id = CAST(:id AS uuid) AND status = 'running'")
                .bind("n", rows).bind("f", OffsetDateTime.ofInstant(clock.now(), java.time.ZoneOffset.UTC)).bind("id", exportId).execute()
        }
    }

    fun failExport(exportId: String, why: String) {
        runCatching {
            db.jdbi.useHandle<Exception> { h ->
                h.createUpdate("UPDATE app.report_export SET status = 'failed', error = :e, finished_at = :f WHERE export_id = CAST(:id AS uuid) AND status = 'running'")
                    .bind("e", why.take(280)).bind("f", OffsetDateTime.ofInstant(clock.now(), java.time.ZoneOffset.UTC)).bind("id", exportId).execute()
            }
        }
    }

    /** One row of `app.report_export` (the export log, F-SYS-058 / AUD-DA-05): [status] `done` for a synchronous export, `queued` for a job. */
    fun recordExport(h: Handle, exportId: String, key: String, format: String, ctx: ReportContext, rows: Int?, status: String) {
        h.createUpdate(
            """
            INSERT INTO app.report_export (export_id, report_key, user_id, format, status, filters, scope_hash, row_count, pii_included, finished_at, created_at)
            VALUES (CAST(:id AS uuid), :key, :uid, :fmt, :st, CAST(:f AS jsonb), :sh, :rows, :pii, CASE WHEN :st = 'done' THEN :now END, :now)
            ON CONFLICT (export_id) DO NOTHING
            """,
        // The resolved period is stored (not the relative one): a job run later exports the days the screen showed.
        ).bind("id", exportId).bind("key", key).bind("uid", ctx.principal.userId).bind("fmt", format).bind("st", status)
            .bind("f", kotlinx.serialization.json.Json.encodeToString(ReportQuery.serializer(), ctx.query.copy(period = PeriodDto(from = ctx.from.toString(), to = ctx.to.toString())))).bind("sh", scopeHash(ctx))
            .bind("rows", rows).bind("pii", readsPii(key, ctx)).bind("now", OffsetDateTime.ofInstant(clock.now(), java.time.ZoneOffset.UTC)).execute()
    }

    // ---------------- PII read budgets (F-SYS-058, docs/21 s4.5) ----------------

    private fun tooMany(what: String, retryAfterS: Long): Nothing =
        throw ApiProblem(ProblemCode.ERR_RATE_LIMITED, what, retryAfterS = retryAfterS.coerceIn(1, 86_400).toInt(), headers = mapOf("Retry-After" to retryAfterS.coerceIn(1, 86_400).toString()))

    /** The hourly budget lives in the platform ([com.aktcl.aron.backend.platform.PiiReadBudget]) so every list endpoint shares it. */
    fun chargeListRead(ctx: ReportContext, rows: Int) =
        com.aktcl.aron.backend.platform.PiiReadBudget.charge(db, config, clock, ctx.principal.userId, ctx.principal.role, rows)

    /** `cfg.pii.export_rows_per_day`: rows exported with personal columns by this user since the Dhaka midnight, plus [rows], must stay within the limit. */
    private fun checkExportBudget(h: Handle, ctx: ReportContext, rows: Int) {
        val limit = runCatching { config.int("cfg.pii.export_rows_per_day") }.getOrDefault(5000)
        val now = clock.now()
        val dhakaDay = now.atZone(java.time.ZoneId.of("Asia/Dhaka")).toLocalDate()
        val startOfDay = dhakaDay.atStartOfDay(java.time.ZoneId.of("Asia/Dhaka")).toInstant()
        val used = h.createQuery("SELECT coalesce(sum(coalesce(row_count, 0)), 0)::bigint FROM app.report_export WHERE user_id = :u AND pii_included AND status <> 'failed' AND created_at >= :s")
            .bind("u", ctx.principal.userId).bind("s", OffsetDateTime.ofInstant(startOfDay, java.time.ZoneOffset.UTC)).mapTo(Long::class.java).one()
        if (used + rows > limit) tooMany("daily limit of exported personal-data rows reached", java.time.Duration.between(now, startOfDay.plusSeconds(86_400)).seconds)
    }

    private fun filtersText(q: ReportQuery): String = kotlinx.serialization.json.Json.encodeToString(ReportQuery.serializer(), q.copy(output = q.output.copy(sort = emptyList()))).take(1900)
}

/**
 * The route list that selects no row. Not -1: handlers filter a route-less row with `coalesce(route_id, -1)`, which -1 would match (every outlet,
 * task or QC line with no route in the caller's zones would come back).
 */
private val NO_ROUTE = listOf(-2L)
