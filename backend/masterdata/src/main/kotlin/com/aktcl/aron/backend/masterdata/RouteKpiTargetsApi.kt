package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.config.AuditWriter
import com.aktcl.aron.backend.masterdata.AdminSupport.bad
import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.receiveStrict
import com.aktcl.aron.backend.platform.requestId
import com.aktcl.aron.backend.platform.FieldError
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

private val KPI_WRITERS = setOf(Role.ADMIN, Role.SUPERADMIN)
private val KPI_READERS = setOf(Role.ADMIN, Role.SUPERADMIN, Role.SUPPORT)
private const val KPI_MAX_ROWS = 20_000
private const val KPI_MAX_MTK = 100_000_000_000L
private const val KPI_MAX_ERRORS = 1000
private const val KPI_MAX_BODY = 4L * 1024 * 1024
private val KPI_MONTH_RE = Regex("^[0-9]{4}-(0[1-9]|1[0-2])$")
private val KPI_SELLING_KINDS = setOf("sr", "normal", "manual")

/**
 * N-5 (lane T-KPI1, D-TKPI-05): the admin entry of the SR KPI targets that do not exist in the route target upload: per route, brand and month the memo target
 * and the brand's allocated KPI (`brand_rows`), and per route and month the allocated KPI and the CPR block (`month_rows`). The volume target stays in
 * `PUT /admin/route-targets` (one source). Same shape and guarantees as that upload: `dry_run` reports every row error without writing; a real PUT commits
 * all rows or none, upserts, is audited with the reason, and is idempotent by `batch_uuid` through `app.route_target_batch` (same content replays, other
 * content or another user is a conflict). A month before the current Dhaka month is never rewritten.
 * `GET /admin/route-kpi-targets?route_code=&month=` reads one route's rows (a fleet is far too large for an unfiltered list).
 */
fun Route.routeKpiTargetRoutes(d: RouteTargetDeps) {
    authenticated(d.guard) {
        get("/admin/route-kpi-targets") { call.respond(kpiList(call, d)) }
        put("/admin/route-kpi-targets") { call.respond(kpiPut(call, d)) }
    }
}

private class BrandRow(val i: Int, val route: String?, val brand: String?, val month: LocalDate?, val memo: Long?, val alloc: Long?, val allocSet: Boolean)
private class MonthRow(val i: Int, val route: String?, val month: LocalDate?, val alloc: Long?, val cprBp: Long?, val cprAlloc: Long?, val cprBpSet: Boolean, val cprAllocSet: Boolean, val cprBpPresent: Boolean, val cprAllocPresent: Boolean)
private class ProgRow(val i: Int, val route: String?, val programme: String?, val month: LocalDate?, val qty: java.math.BigDecimal?)
private class CampRow(val i: Int, val outlet: String?, val programme: String?, val month: LocalDate?, val std: java.math.BigDecimal?, val memo: Long?)
private class CampSkuRow(val i: Int, val outlet: String?, val programme: String?, val month: LocalDate?, val sku: String?, val qty: Long?)
private class KErr(val section: String, val index: Int, val member: String, val code: String)

private fun kText(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() && it.length <= 40 && '\u0000' !in it }
private fun kInt(o: JsonObject, k: String, max: Long): Long? =
    (o[k] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.content?.takeIf { it.matches(Regex("^(0|[1-9][0-9]{0,12})$")) }?.toLongOrNull()?.takeIf { it in 0..max }
private fun kMonth(o: JsonObject): LocalDate? = (o["month"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { KPI_MONTH_RE.matches(it) }?.let { YearMonth.parse(it).atDay(1) }

private fun parseBrand(arr: JsonArray) = arr.mapIndexed { i, el ->
    val o = el as? JsonObject ?: bad("body.brand_rows/$i", "invalid_type")
    o.keys.firstOrNull { it !in setOf("route_code", "brand_code", "month", "memo_target", "kpi_alloc_mtk") }?.let { bad("body.brand_rows/$i/$it", "unknown_member") }
    BrandRow(i, kText(o, "route_code"), kText(o, "brand_code"), kMonth(o), kInt(o, "memo_target", 1_000_000), if (o.containsKey("kpi_alloc_mtk")) kInt(o, "kpi_alloc_mtk", KPI_MAX_MTK) else 0L, o.containsKey("kpi_alloc_mtk"))
}

private fun parseMonth(arr: JsonArray) = arr.mapIndexed { i, el ->
    val o = el as? JsonObject ?: bad("body.month_rows/$i", "invalid_type")
    o.keys.firstOrNull { it !in setOf("route_code", "month", "kpi_alloc_mtk", "cpr_target_bp", "cpr_alloc_mtk") }?.let { bad("body.month_rows/$i/$it", "unknown_member") }
    MonthRow(i, kText(o, "route_code"), kMonth(o), kInt(o, "kpi_alloc_mtk", KPI_MAX_MTK), kInt(o, "cpr_target_bp", 10_000), kInt(o, "cpr_alloc_mtk", KPI_MAX_MTK),
        o["cpr_target_bp"].let { it != null && it !is JsonNull }, o["cpr_alloc_mtk"].let { it != null && it !is JsonNull }, o.containsKey("cpr_target_bp"), o.containsKey("cpr_alloc_mtk"))
}

private fun parseProg(arr: JsonArray) = arr.mapIndexed { i, el ->
    val o = el as? JsonObject ?: bad("body.programme_rows/$i", "invalid_type")
    o.keys.firstOrNull { it !in setOf("route_code", "programme_code", "month", "target_qty") }?.let { bad("body.programme_rows/$i/$it", "unknown_member") }
    // An exact decimal string with up to three fraction digits ("20.000"), never a JSON number (no float on the wire).
    val q = (o["target_qty"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.matches(Regex("^(0|[1-9][0-9]{0,11})(\\.[0-9]{1,3})?$")) }?.let { java.math.BigDecimal(it) }
    ProgRow(i, kText(o, "route_code"), kText(o, "programme_code"), kMonth(o), q)
}

private val DEC3 = Regex("^(0|[1-9][0-9]{0,11})(\\.[0-9]{1,3})?$")

private fun parseCamp(arr: JsonArray) = arr.mapIndexed { i, el ->
    val o = el as? JsonObject ?: bad("body.campaign_rows/$i", "invalid_type")
    o.keys.firstOrNull { it !in setOf("outlet_code", "programme_code", "month", "std_target", "memo_target") }?.let { bad("body.campaign_rows/$i/$it", "unknown_member") }
    CampRow(i, kText(o, "outlet_code"), kText(o, "programme_code"), kMonth(o), (o["std_target"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { DEC3.matches(it) }?.let { java.math.BigDecimal(it) }, kInt(o, "memo_target", 1_000_000))
}

private fun parseCampSku(arr: JsonArray) = arr.mapIndexed { i, el ->
    val o = el as? JsonObject ?: bad("body.campaign_sku_rows/$i", "invalid_type")
    o.keys.firstOrNull { it !in setOf("outlet_code", "programme_code", "month", "sku_code", "qty_base") }?.let { bad("body.campaign_sku_rows/$i/$it", "unknown_member") }
    CampSkuRow(i, kText(o, "outlet_code"), kText(o, "programme_code"), kMonth(o), kText(o, "sku_code"), kInt(o, "qty_base", 1_000_000_000))
}

private class KChecked(val errors: List<KErr>, val routeIds: Map<String, Long>, val brandIds: Map<String, Long>, val programmeIds: Map<String, Long> = emptyMap(), val campaignIds: Map<String, Long> = emptyMap(), val outletIds: Map<String, Long> = emptyMap(), val skuIds: Map<String, Long> = emptyMap())

private fun kCheck(h: Handle, brand: List<BrandRow>, month: List<MonthRow>, prog: List<ProgRow>, camp: List<CampRow>, campSku: List<CampSkuRow>, current: LocalDate): KChecked {
    val routeCodes = (brand.mapNotNull { it.route } + month.mapNotNull { it.route } + prog.mapNotNull { it.route }).distinct()
    val routes = HashMap<String, Pair<Long, String>>()
    h.createQuery("SELECT id, code, kind FROM app.route WHERE code = ANY(:c) AND status = 'active'").bindArray("c", String::class.java, routeCodes)
        .map { rs, _ -> rs.getString("code") to (rs.getLong("id") to rs.getString("kind")) }.list().forEach { routes[it.first] = it.second }
    // A brand is named by its code; a brand without a code (the master data does not require one) by its exact name, case-insensitive, when that is unambiguous.
    val brands = HashMap<String, Long>()
    val wanted = brand.mapNotNull { it.brand }.distinct()
    val found = h.createQuery("SELECT id, code, lower(name) FROM app.product_node WHERE level = 'brand' AND status = 'active' AND (code = ANY(:c) OR lower(name) = ANY(:n))")
        .bindArray("c", String::class.java, wanted).bindArray("n", String::class.java, wanted.map { it.lowercase() })
        .map { rs, _ -> Triple(rs.getLong(1), rs.getString(2), rs.getString(3)) }.list()
    for (w in wanted) {
        val byCode = found.filter { it.second == w }
        val hit = if (byCode.size == 1) byCode else found.filter { it.third == w.lowercase() }
        if (hit.size == 1) brands[w] = hit[0].first
    }
    val errors = ArrayList<KErr>()
    fun route(section: String, i: Int, code: String?) {
        val r = code?.let { routes[it] }
        if (r == null) errors += KErr(section, i, "route_code", "unknown_route") else if (r.second !in KPI_SELLING_KINDS) errors += KErr(section, i, "route_code", "not_selling_route")
    }
    fun monthOk(section: String, i: Int, m: LocalDate?) {
        if (m == null) errors += KErr(section, i, "month", "invalid_month") else if (m.isBefore(current)) errors += KErr(section, i, "month", "past_month")
    }
    val seenB = HashSet<Triple<String, String, LocalDate>>(); val seenM = HashSet<Pair<String, LocalDate>>()
    for (r in brand) {
        val n0 = errors.size
        route("brand_rows", r.i, r.route)
        if (r.brand == null || brands[r.brand] == null) errors += KErr("brand_rows", r.i, "brand_code", "unknown_brand")
        monthOk("brand_rows", r.i, r.month)
        if (r.memo == null) errors += KErr("brand_rows", r.i, "memo_target", "invalid_quantity")
        if (r.alloc == null) errors += KErr("brand_rows", r.i, "kpi_alloc_mtk", "invalid_amount")
        // Duplicates are judged on the resolved brand (two spellings of one brand are one key) and only for rows without another error.
        val bid = r.brand?.let { brands[it] }
        if (errors.size == n0 && bid != null && !seenB.add(Triple(r.route!!, bid.toString(), r.month!!))) errors += KErr("brand_rows", r.i, "route_code", "duplicate_row")
    }
    for (r in month) {
        val n0 = errors.size
        route("month_rows", r.i, r.route); monthOk("month_rows", r.i, r.month)
        if (r.alloc == null) errors += KErr("month_rows", r.i, "kpi_alloc_mtk", "invalid_amount")
        if (r.cprBpSet && r.cprBp == null) errors += KErr("month_rows", r.i, "cpr_target_bp", "invalid_quantity")
        if (r.cprAllocSet && r.cprAlloc == null) errors += KErr("month_rows", r.i, "cpr_alloc_mtk", "invalid_amount")
        if (errors.size == n0 && !seenM.add(r.route!! to r.month!!)) errors += KErr("month_rows", r.i, "route_code", "duplicate_row")
    }
    val programmes = HashMap<String, Long>()
    h.createQuery("SELECT id, code FROM app.programme WHERE kind = 'superstar' AND status = 'active' AND code = ANY(:c)").bindArray("c", String::class.java, prog.mapNotNull { it.programme }.distinct())
        .map { rs, _ -> rs.getString(2) to rs.getLong(1) }.list().forEach { programmes[it.first] = it.second }
    val seenP = HashSet<Triple<String, String, LocalDate>>()
    for (r in prog) {
        val n0 = errors.size
        route("programme_rows", r.i, r.route); monthOk("programme_rows", r.i, r.month)
        if (r.programme == null || programmes[r.programme] == null) errors += KErr("programme_rows", r.i, "programme_code", "unknown_programme")
        if (r.qty == null) errors += KErr("programme_rows", r.i, "target_qty", "invalid_quantity")
        if (errors.size == n0 && !seenP.add(Triple(r.route!!, r.programme!!, r.month!!))) errors += KErr("programme_rows", r.i, "route_code", "duplicate_row")
    }
    val campaigns = HashMap<String, Long>()
    val campCodes = (camp.mapNotNull { it.programme } + campSku.mapNotNull { it.programme }).distinct()
    h.createQuery("SELECT id, code FROM app.programme WHERE kind = 'campaign' AND status = 'active' AND code = ANY(:c)").bindArray("c", String::class.java, campCodes)
        .map { rs, _ -> rs.getString(2) to rs.getLong(1) }.list().forEach { campaigns[it.first] = it.second }
    val outlets = HashMap<String, Long>()
    h.createQuery("SELECT id, code FROM app.outlet WHERE code = ANY(:c) AND status = 'active'").bindArray("c", String::class.java, (camp.mapNotNull { it.outlet } + campSku.mapNotNull { it.outlet }).distinct())
        .map { rs, _ -> rs.getString(2) to rs.getLong(1) }.list().forEach { outlets[it.first] = it.second }
    val skus = HashMap<String, Long>()
    h.createQuery("SELECT id, code FROM app.sku WHERE code = ANY(:c) AND status = 'active'").bindArray("c", String::class.java, campSku.mapNotNull { it.sku }.distinct())
        .map { rs, _ -> rs.getString(2) to rs.getLong(1) }.list().forEach { skus[it.first] = it.second }
    val seenC = HashSet<Triple<String, String, LocalDate>>(); val seenCS = HashSet<List<Any>>()
    for (r in camp) {
        val n0 = errors.size
        if (r.outlet == null || outlets[r.outlet] == null) errors += KErr("campaign_rows", r.i, "outlet_code", "unknown_outlet")
        if (r.programme == null || campaigns[r.programme] == null) errors += KErr("campaign_rows", r.i, "programme_code", "unknown_programme")
        monthOk("campaign_rows", r.i, r.month)
        if (r.std == null) errors += KErr("campaign_rows", r.i, "std_target", "invalid_quantity")
        if (r.memo == null) errors += KErr("campaign_rows", r.i, "memo_target", "invalid_quantity")
        if (errors.size == n0 && !seenC.add(Triple(r.outlet!!, r.programme!!, r.month!!))) errors += KErr("campaign_rows", r.i, "outlet_code", "duplicate_row")
    }
    // A SKU target needs its campaign row (outlet, programme, month): in this batch or already stored; an orphan would be stored and never shown (review B-round, D-TKPI-10).
    val inBatch = camp.filter { it.outlet != null && it.programme != null && it.month != null }.map { Triple(it.outlet!!, it.programme!!, it.month!!) }.toHashSet()
    val stored = HashSet<Triple<String, String, LocalDate>>()
    val wantKeys = campSku.filter { it.outlet != null && it.programme != null && it.month != null && Triple(it.outlet, it.programme, it.month) !in inBatch }
    if (wantKeys.isNotEmpty()) h.createQuery(
        "SELECT o.code, g.code, t.month FROM app.outlet_campaign_target t JOIN app.outlet o ON o.id = t.outlet_id JOIN app.programme g ON g.id = t.programme_id WHERE o.code = ANY(:o) AND g.code = ANY(:g) AND t.month = ANY(CAST(:m AS date[]))",
    ).bindArray("o", String::class.java, wantKeys.map { it.outlet!! }.distinct()).bindArray("g", String::class.java, wantKeys.map { it.programme!! }.distinct()).bindArray("m", String::class.java, wantKeys.map { it.month!!.toString() }.distinct())
        .map { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getDate(3).toLocalDate()) }.list().forEach { stored += it }
    for (r in campSku) {
        val n0 = errors.size
        if (r.outlet == null || outlets[r.outlet] == null) errors += KErr("campaign_sku_rows", r.i, "outlet_code", "unknown_outlet")
        if (r.programme == null || campaigns[r.programme] == null) errors += KErr("campaign_sku_rows", r.i, "programme_code", "unknown_programme")
        if (r.sku == null || skus[r.sku] == null) errors += KErr("campaign_sku_rows", r.i, "sku_code", "unknown_sku")
        monthOk("campaign_sku_rows", r.i, r.month)
        if (r.qty == null) errors += KErr("campaign_sku_rows", r.i, "qty_base", "invalid_quantity")
        if (errors.size == n0 && Triple(r.outlet!!, r.programme!!, r.month!!) !in inBatch && Triple(r.outlet, r.programme, r.month) !in stored) errors += KErr("campaign_sku_rows", r.i, "outlet_code", "campaign_row_missing")
        if (errors.size == n0 && !seenCS.add(listOf(r.outlet!!, r.programme!!, r.month!!, r.sku!!))) errors += KErr("campaign_sku_rows", r.i, "outlet_code", "duplicate_row")
    }
    return KChecked(errors, routes.mapValues { it.value.first }, brands, programmes, campaigns, outlets, skus)
}

private fun kDigest(brand: List<BrandRow>, month: List<MonthRow>, prog: List<ProgRow>, camp: List<CampRow>, campSku: List<CampSkuRow>): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    (brand.map { "B|${it.route}|${it.brand}|${it.month}|${it.memo}|${it.alloc}" } + month.map { "M|${it.route}|${it.month}|${it.alloc}|${it.cprBp}|${it.cprAlloc}" } + prog.map { "P|${it.route}|${it.programme}|${it.month}|${it.qty?.toPlainString()}" } + camp.map { "C|${it.outlet}|${it.programme}|${it.month}|${it.std?.toPlainString()}|${it.memo}" } + campSku.map { "S|${it.outlet}|${it.programme}|${it.month}|${it.sku}|${it.qty}" }).sorted().forEach { md.update(it.toByteArray()); md.update(10) }
    return md.digest()
}

private fun kReady(h: Handle) = h.createQuery("SELECT to_regclass('app.route_brand_kpi_target') IS NOT NULL AND to_regclass('app.route_kpi_month') IS NOT NULL AND to_regclass('app.route_programme_target') IS NOT NULL AND to_regclass('app.outlet_campaign_target') IS NOT NULL AND to_regclass('app.outlet_campaign_sku_target') IS NOT NULL AND to_regclass('app.route_target_batch') IS NOT NULL").mapTo(Boolean::class.java).one()

private suspend fun kpiPut(call: ApplicationCall, d: RouteTargetDeps): JsonElement {
    val p = call.principal
    if (p.role !in KPI_WRITERS) throw AdminSupport.forbidden("KPI targets are written by ADMIN and SUPERADMIN")
    val body = call.receiveStrict(JsonObject.serializer(), KPI_MAX_BODY)
    body.keys.firstOrNull { it !in setOf("batch_uuid", "brand_rows", "month_rows", "programme_rows", "campaign_rows", "campaign_sku_rows", "dry_run", "change_reason") }?.let { bad("body.$it", "unknown_member") }
    val uuid = AdminSupport.uuid((body["batch_uuid"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bad("body.batch_uuid", "required"), "body.batch_uuid")
    val dry = (body["dry_run"] as? JsonPrimitive)?.takeIf { !it.isString }?.content.let { when (it) { "true" -> true; "false" -> false; else -> bad("body.dry_run", "required") } }
    val brand = parseBrand(body["brand_rows"] as? JsonArray ?: JsonArray(emptyList()))
    val month = parseMonth(body["month_rows"] as? JsonArray ?: JsonArray(emptyList()))
    val prog = parseProg(body["programme_rows"] as? JsonArray ?: JsonArray(emptyList()))
    val camp = parseCamp(body["campaign_rows"] as? JsonArray ?: JsonArray(emptyList()))
    val campSku = parseCampSku(body["campaign_sku_rows"] as? JsonArray ?: JsonArray(emptyList()))
    if (brand.size + month.size + prog.size + camp.size + campSku.size !in 1..KPI_MAX_ROWS) bad("body.brand_rows", "length")
    val current = AdminSupport.today(d.clock).withDayOfMonth(1)
    if (dry) return d.db.jdbi.withHandle<JsonElement, Exception> { h ->
        if (!kReady(h)) throw ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "KPI targets are not deployed in this environment")
        val c = kCheck(h, brand, month, prog, camp, campSku, current)
        buildJsonObject {
            put("batch_uuid", uuid.toString()); put("brand_rows", brand.size); put("month_rows", month.size); put("programme_rows", prog.size); put("campaign_rows", camp.size); put("campaign_sku_rows", campSku.size)
            put("errors", buildJsonArray { c.errors.take(KPI_MAX_ERRORS).forEach { e -> add(buildJsonObject { put("section", e.section); put("row", e.index + 1); put("column", e.member); put("code", e.code) }) } })
            put("errors_truncated", c.errors.size > KPI_MAX_ERRORS)
        }
    }
    val reason = AdminSupport.reason((body["change_reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
    AdminSupport.noNul("body.change_reason", reason)
    return admWrite {
        d.db.jdbi.inTransaction<JsonElement, Exception> { h ->
            if (!kReady(h)) throw ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "KPI targets are not deployed in this environment")
            h.createQuery("SELECT pg_advisory_xact_lock(hashtextextended('route-target-upload', 0))").mapTo(String::class.java).findOne()
            val sha = kDigest(brand, month, prog, camp, campSku)
            h.createQuery("SELECT content_sha256, row_count, committed_by FROM app.route_target_batch WHERE batch_uuid = :u").bind("u", uuid)
                .map { rs, _ -> Triple(rs.getBytes(1), rs.getInt(2), rs.getLong(3)) }.findOne().orElse(null)?.let { (stored, n, by) ->
                    if (!stored.contentEquals(sha) || by != p.userId) throw ApiProblem(ProblemCode.ERR_CONFLICT, "batch_uuid was already used for a different batch")
                    return@inTransaction buildJsonObject { put("batch_uuid", uuid.toString()); put("rows", n); put("replayed", true) }
                }
            val c = kCheck(h, brand, month, prog, camp, campSku, current)
            if (c.errors.isNotEmpty()) throw ApiProblem(ProblemCode.ERR_VALIDATION, "${c.errors.size} row errors", errors = c.errors.take(KPI_MAX_ERRORS).map { e -> FieldError("body.${e.section}/${e.index}/${e.member}", e.code) })
            h.createUpdate("INSERT INTO app.route_target_batch (batch_uuid, content_sha256, row_count, committed_by) VALUES (:u, :h, :n, :by)")
                .bind("u", uuid).bind("h", sha).bind("n", brand.size + month.size + prog.size + camp.size + campSku.size).bind("by", p.userId).execute()
            val bb = h.prepareBatch("INSERT INTO app.route_brand_kpi_target (route_id, brand_id, month, memo_target, kpi_alloc_mtk, batch_uuid, updated_by) VALUES (:r, :b, :m, :t, :a, :u, :by) " +
                "ON CONFLICT (route_id, brand_id, month) DO UPDATE SET memo_target = EXCLUDED.memo_target, kpi_alloc_mtk = CASE WHEN :aset THEN EXCLUDED.kpi_alloc_mtk ELSE app.route_brand_kpi_target.kpi_alloc_mtk END, batch_uuid = EXCLUDED.batch_uuid, updated_by = EXCLUDED.updated_by, updated_at = now()")
            brand.forEach { r -> bb.bind("r", c.routeIds.getValue(r.route!!)).bind("b", c.brandIds.getValue(r.brand!!)).bind("m", r.month).bind("t", r.memo!!.toInt()).bind("a", r.alloc!!).bind("aset", r.allocSet).bind("u", uuid).bind("by", p.userId).add() }
            if (brand.isNotEmpty()) bb.execute()
            val mb = h.prepareBatch("INSERT INTO app.route_kpi_month (route_id, month, kpi_alloc_mtk, cpr_target_bp, cpr_alloc_mtk, batch_uuid, updated_by) VALUES (:r, :m, :a, :c, :ca, :u, :by) " +
                "ON CONFLICT (route_id, month) DO UPDATE SET kpi_alloc_mtk = EXCLUDED.kpi_alloc_mtk, cpr_target_bp = CASE WHEN :bpp THEN EXCLUDED.cpr_target_bp ELSE app.route_kpi_month.cpr_target_bp END, cpr_alloc_mtk = CASE WHEN :cap THEN EXCLUDED.cpr_alloc_mtk ELSE app.route_kpi_month.cpr_alloc_mtk END, batch_uuid = EXCLUDED.batch_uuid, updated_by = EXCLUDED.updated_by, updated_at = now()")
            month.forEach { r -> mb.bind("r", c.routeIds.getValue(r.route!!)).bind("m", r.month).bind("a", r.alloc!!).bind("c", r.cprBp?.toInt()).bind("ca", r.cprAlloc).bind("bpp", r.cprBpPresent).bind("cap", r.cprAllocPresent).bind("u", uuid).bind("by", p.userId).add() }
            if (month.isNotEmpty()) mb.execute()
            val pb = h.prepareBatch("INSERT INTO app.route_programme_target (programme_id, route_id, month, target_qty, batch_uuid, updated_by) VALUES (:p, :r, :m, :q, :u, :by) " +
                "ON CONFLICT (programme_id, route_id, month) DO UPDATE SET target_qty = EXCLUDED.target_qty, batch_uuid = EXCLUDED.batch_uuid, updated_by = EXCLUDED.updated_by, updated_at = now()")
            prog.forEach { r -> pb.bind("p", c.programmeIds.getValue(r.programme!!)).bind("r", c.routeIds.getValue(r.route!!)).bind("m", r.month).bind("q", r.qty!!).bind("u", uuid).bind("by", p.userId).add() }
            if (prog.isNotEmpty()) pb.execute()
            val cb = h.prepareBatch("INSERT INTO app.outlet_campaign_target (programme_id, month, outlet_id, std_target, memo_target, batch_uuid, updated_by) VALUES (:p, :m, :o, :s, :t, :u, :by) " +
                "ON CONFLICT (programme_id, month, outlet_id) DO UPDATE SET std_target = EXCLUDED.std_target, memo_target = EXCLUDED.memo_target, batch_uuid = EXCLUDED.batch_uuid, updated_by = EXCLUDED.updated_by, updated_at = now()")
            camp.forEach { r -> cb.bind("p", c.campaignIds.getValue(r.programme!!)).bind("m", r.month).bind("o", c.outletIds.getValue(r.outlet!!)).bind("s", r.std!!).bind("t", r.memo!!.toInt()).bind("u", uuid).bind("by", p.userId).add() }
            if (camp.isNotEmpty()) cb.execute()
            val sb = h.prepareBatch("INSERT INTO app.outlet_campaign_sku_target (programme_id, month, outlet_id, sku_id, qty_base, batch_uuid, updated_by) VALUES (:p, :m, :o, :k, :q, :u, :by) " +
                "ON CONFLICT (programme_id, month, outlet_id, sku_id) DO UPDATE SET qty_base = EXCLUDED.qty_base, batch_uuid = EXCLUDED.batch_uuid, updated_by = EXCLUDED.updated_by, updated_at = now()")
            campSku.forEach { r -> sb.bind("p", c.campaignIds.getValue(r.programme!!)).bind("m", r.month).bind("o", c.outletIds.getValue(r.outlet!!)).bind("k", c.skuIds.getValue(r.sku!!)).bind("q", r.qty!!).bind("u", uuid).bind("by", p.userId).add() }
            if (campSku.isNotEmpty()) sb.execute()
            AuditWriter.write(h, p, "route_kpi_target", uuid.toString(), "upload", null, buildJsonObject {
                put("brand_rows", brand.size); put("month_rows", month.size); put("programme_rows", prog.size); put("campaign_rows", camp.size); put("campaign_sku_rows", campSku.size); put("total_kpi_alloc_mtk", brand.sumOf { it.alloc!! } + month.sumOf { it.alloc!! })
            }, reason, call.requestId)
            buildJsonObject { put("batch_uuid", uuid.toString()); put("rows", brand.size + month.size + prog.size + camp.size + campSku.size); put("replayed", false) }
        }
    }
}

private fun kpiList(call: ApplicationCall, d: RouteTargetDeps): JsonElement {
    val p = call.principal
    if (p.role !in KPI_READERS) throw AdminSupport.forbidden("KPI targets are read by ADMIN, SUPERADMIN and SUPPORT")
    val routeCode = call.request.queryParameters["route_code"]?.takeIf { it.isNotBlank() && it.length <= 40 } ?: bad("query.route_code", "required")
    val monthRaw = call.request.queryParameters["month"]
    val month = if (monthRaw == null) AdminSupport.today(d.clock).withDayOfMonth(1) else (monthRaw.takeIf { KPI_MONTH_RE.matches(it) } ?: bad("query.month")).let { YearMonth.parse(it).atDay(1) }
    return d.db.jdbi.withHandle<JsonElement, Exception> { h ->
        if (!kReady(h)) throw ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "KPI targets are not deployed in this environment")
        val brands = h.createQuery("SELECT coalesce(b.code, b.name), b.name, t.memo_target, t.kpi_alloc_mtk FROM app.route_brand_kpi_target t JOIN app.route r ON r.id = t.route_id JOIN app.product_node b ON b.id = t.brand_id WHERE r.code = :c AND t.month = :m ORDER BY b.name COLLATE \"C\"")
            .bind("c", routeCode).bind("m", month).map { rs, _ -> listOf(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)) }.list()
        val m = h.createQuery("SELECT t.kpi_alloc_mtk, t.cpr_target_bp, t.cpr_alloc_mtk FROM app.route_kpi_month t JOIN app.route r ON r.id = t.route_id WHERE r.code = :c AND t.month = :m")
            .bind("c", routeCode).bind("m", month).map { rs, _ -> Triple(rs.getLong(1), rs.getObject(2) as Int?, rs.getObject(3) as Long?) }.findOne().orElse(null)
        val progs = h.createQuery("SELECT g.code, g.name_en, t.target_qty FROM app.route_programme_target t JOIN app.route r ON r.id = t.route_id JOIN app.programme g ON g.id = t.programme_id WHERE r.code = :c AND t.month = :m ORDER BY g.code")
            .bind("c", routeCode).bind("m", month).map { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getBigDecimal(3).setScale(3).toPlainString()) }.list()
        // The route's outlets with an outlet campaign target for the month, and their SKU targets (capped, so the page is bounded; at most 500 rows each).
        val camps = h.createQuery(
            "SELECT o.code, g.code, g.name_en, t.std_target, t.memo_target FROM app.outlet_campaign_target t JOIN app.outlet o ON o.id = t.outlet_id JOIN app.route r ON r.id = o.route_id JOIN app.programme g ON g.id = t.programme_id " +
                "WHERE r.code = :c AND t.month = :m ORDER BY o.code COLLATE \"C\", g.code LIMIT 500",
        ).bind("c", routeCode).bind("m", month).map { rs, _ -> listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4).setScale(3).toPlainString(), rs.getInt(5)) }.list()
        val campSkus = h.createQuery(
            "SELECT o.code, g.code, s.code, t.qty_base FROM app.outlet_campaign_sku_target t JOIN app.outlet o ON o.id = t.outlet_id JOIN app.route r ON r.id = o.route_id JOIN app.programme g ON g.id = t.programme_id JOIN app.sku s ON s.id = t.sku_id " +
                "WHERE r.code = :c AND t.month = :m ORDER BY o.code COLLATE \"C\", g.code, s.code COLLATE \"C\" LIMIT 500",
        ).bind("c", routeCode).bind("m", month).map { rs, _ -> listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)) }.list()
        buildJsonObject {
            put("route_code", routeCode); put("month", YearMonth.from(month).toString())
            put("campaign_rows", buildJsonArray { camps.forEach { c -> add(buildJsonObject { put("outlet_code", c[0] as String); put("programme_code", c[1] as String); put("programme_name", c[2] as String); put("std_target", c[3] as String); put("memo_target", c[4] as Int) }) } })
            put("campaign_sku_rows", buildJsonArray { campSkus.forEach { c -> add(buildJsonObject { put("outlet_code", c[0] as String); put("programme_code", c[1] as String); put("sku_code", c[2] as String); put("qty_base", c[3] as Long) }) } })
            put("programme_rows", buildJsonArray { progs.forEach { g -> add(buildJsonObject { put("programme_code", g.first); put("programme_name", g.second); put("target_qty", g.third) }) } })
            put("brand_rows", buildJsonArray { brands.forEach { b -> add(buildJsonObject { put("brand_code", b[0] as String); put("brand_name", b[1] as String); put("memo_target", b[2] as Long); put("kpi_alloc_mtk", b[3] as Long) }) } })
            if (m == null) put("month_row", JsonNull) else put("month_row", buildJsonObject {
                put("kpi_alloc_mtk", m.first); put("cpr_target_bp", m.second?.let { JsonPrimitive(it) } ?: JsonNull); put("cpr_alloc_mtk", m.third?.let { JsonPrimitive(it) } ?: JsonNull)
            })
        }
    }
}
