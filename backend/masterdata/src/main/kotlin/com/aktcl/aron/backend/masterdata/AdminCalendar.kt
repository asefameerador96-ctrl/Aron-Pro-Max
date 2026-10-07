package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.contract.ProblemCode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import org.jdbi.v3.core.Handle
import java.time.LocalDate

private val HOLIDAY_KINDS = setOf("holiday", "makeup_day", "emergency_off")
private val HOLIDAY_SCOPES = setOf("global", "wing", "division", "territory", "zone")

private val SCOPE_TABLE = mapOf("wing" to "app.wing", "division" to "app.division", "territory" to "app.territory", "zone" to "app.zone")

/** Node ids per scope type that contain a zone in the caller's reach (null = national). */
private fun scopeIds(ctx: AdminCtx): Map<String, Set<Long>>? {
    if (ctx.reach.national) return null
    val geo = ctx.d.geo.geo()
    val zones = ctx.reach.zoneIds.filter { it in geo.zoneTerritory }.toSet()
    val terr = zones.mapNotNull { geo.zoneTerritory[it] }.toSet()
    val div = terr.mapNotNull { geo.territoryDivision[it] }.toSet()
    val wing = div.mapNotNull { geo.divisionWing[it] }.toSet()
    return mapOf("zone" to zones, "territory" to terr, "division" to div, "wing" to wing)
}

private fun holidayJson(r: Map<String, Any?>) = JsonObject(mapOf(
    "id" to jv(r["id"]), "date" to jv(r["date"]), "scope_type" to jv(r["scope_type"]), "scope_id" to jv(r["scope_id"]), "kind" to jv(r["kind"]),
    "selling_day" to jv(r["selling_day"]), "name_en" to jv(r["name_en"]), "name_bn" to jv(r["name_bn"]),
))

private fun listHolidays(call: ApplicationCall, d: AdminMasterDeps): JsonObject {
    val p = call.admPrincipal(MASTER_READERS, "the calendar")
    val ctx = d.ctx(call, p)
    val from = call.admDate("from") ?: LocalDate.of(ctx.today.year, 1, 1)
    val to = call.admDate("to") ?: from.plusYears(2).minusDays(1)
    if (to.isBefore(from)) admBad("query.to", "before_from")
    if (to.isAfter(from.plusYears(3))) admBad("query.to", "range_too_long", "at most three years per request")
    val vis = scopeIds(ctx)
    val rows = d.db.jdbi.withHandle<List<Map<String, Any?>>, Exception> { h ->
        val where = StringBuilder("date BETWEEN :f AND :t AND revoked_at IS NULL")
        val lists = HashMap<String, List<Long>>()
        if (vis != null) {
            where.append(" AND (scope_type = 'global'")
            vis.forEach { (type, ids) -> if (ids.isNotEmpty()) { where.append(" OR (scope_type = '$type' AND scope_id IN (<ids_$type>))"); lists["ids_$type"] = ids.toList() } }
            where.append(")")
        }
        val q = h.createQuery("SELECT * FROM app.calendar_holiday WHERE $where ORDER BY date, id LIMIT 2000").bind("f", from).bind("t", to)
        lists.forEach { (k, v) -> q.bindList(k, v) }
        q.mapToMap().list()
    }
    return JsonObject(mapOf("items" to JsonArray(rows.map(::holidayJson))))
}

private suspend fun createHoliday(call: ApplicationCall, d: AdminMasterDeps) {
    val p = call.admPrincipal(MASTER_WRITERS, "calendar changes")
    val f = call.admBody(setOf("date", "scope_type", "scope_id", "kind", "name_en", "name_bn", "reason"))
    fun need(k: String) = if (f.has(k)) k else admBad("body.$k", "required", "$k is required")
    val date = parseDate("body.date", f.obj[need("date")]) ?: admBad("body.date")
    val scopeType = Col("scope_type", Kind.ENUM, values = HOLIDAY_SCOPES).let { need("scope_type"); f.value(it) as String }
    val scopeId = Col("scope_id", Kind.LONG, min = 0).let { need("scope_id"); f.value(it) as Long }
    val kind = Col("kind", Kind.ENUM, values = HOLIDAY_KINDS).let { need("kind"); f.value(it) as String }
    val nameEn = Col("name_en", Kind.TEXT, min = 2, max = 120).let { need("name_en"); f.value(it) as String }
    val nameBn = if (f.has("name_bn")) f.value(Col("name_bn", Kind.TEXT, nullable = true, max = 120)) as String? else null
    val reason = f.reason(true, "reason")
    if ((scopeType == "global") != (scopeId == 0L)) admBad("body.scope_id", "scope_mismatch", "a global entry has scope_id 0 and any other scope a node id")
    val ctx = d.ctx(call, p)
    val earliest = if (kind == "emergency_off") ctx.today else ctx.today.plusDays(1)
    if (date.isBefore(earliest)) throw ApiProblem(ProblemCode.ERR_MASTER_EFFECTIVE_DATE_PAST, "the date is not in the future", errors = listOf(com.aktcl.aron.backend.platform.FieldError("body.date", "past")))
    val row = admWrite {
        d.db.jdbi.inTransaction<Map<String, Any?>, Exception> { h ->
            if (scopeType != "global") {
                val vis = scopeIds(ctx)
                if (h.count("SELECT count(*) FROM ${SCOPE_TABLE.getValue(scopeType)} WHERE id = :i", "i" to scopeId) == 0L || (vis != null && scopeId !in vis.getValue(scopeType)))
                    admBad("body.scope_id", "unknown_node", "the node does not exist or is outside your reach")
            } else if (!ctx.reach.national) throw ApiProblem(ProblemCode.ERR_OUT_OF_SCOPE, "a global entry needs national reach")
            if (h.count("SELECT count(*) FROM app.calendar_holiday WHERE date = :d AND scope_type = :s AND scope_id = :i AND revoked_at IS NULL", "d" to date, "s" to scopeType, "i" to scopeId) > 0)
                throw ApiProblem(ProblemCode.ERR_MASTER_OVERLAP, "an entry for this date and scope already exists")
            val r = h.createQuery(
                "INSERT INTO app.calendar_holiday (date, scope_type, scope_id, kind, selling_day, name_en, name_bn, reason, created_by) " +
                    "VALUES (:d, :st, :si, :k, :sd, :ne, :nb, :r, :by) RETURNING *",
            ).bind("d", date).bind("st", scopeType).bind("si", scopeId).bind("k", kind).bind("sd", kind == "makeup_day").bind("ne", nameEn)
                .bindAny("nb", nameBn).bind("r", reason).bind("by", p.userId).mapToMap().one()
            ctx.audit(h, "calendar_holiday", r["id"].toString(), "create", null, holidayJson(r), reason)
            r
        }
    }
    call.admRespond(HttpStatusCode.Created, holidayJson(row))
}

// ---- code lists ---------------------------------------------------------------------------------------------------

private val TABLE_LISTS = listOf(
    "force_reason", "edit_reason", "void_reason", "visit_outcome", "skip_reason", "day_exception_reason", "stock_variance_reason", "task_type",
    "leave_type", "feedback_category", "qc_fault_type", "payment_mode", "outlet_close_reason", "submit_void_reason",
)
/** Contract CodeListKey members that are not rows of `app.code_list` (channel, sub_channel, geo_class are fixed enums or their own tables). */
private val OTHER_LISTS = setOf("channel", "sub_channel", "geo_class")
private val CODE_ITEM_RE = Regex("^[a-z][a-z0-9_]{1,40}$")

private fun attrsOf(x: Any?): JsonObject = x?.toString()?.let { kotlinx.serialization.json.Json.parseToJsonElement(it) as? JsonObject } ?: JsonObject(emptyMap())

private fun itemJson(r: Map<String, Any?>): JsonObject {
    val attrs = attrsOf(r["attrs"])
    return JsonObject(mapOf(
        "code" to jv(r["code"]), "label_bn" to jv(r["label_bn"]), "label_en" to jv(r["label_en"]), "sort" to jv(r["sort"]), "attrs" to attrs,
        "valid_from" to jv(r["valid_from"]), "valid_to" to jv(r["valid_to"]),
    ))
}

private fun loadList(h: Handle, key: String): JsonObject =
    JsonObject(mapOf("list_key" to JsonPrimitive(key), "items" to JsonArray(
        h.createQuery("SELECT code, label_bn, label_en, sort, attrs, valid_from, valid_to FROM app.code_list_item WHERE list_key = :k ORDER BY sort, code").bind("k", key).mapToMap().list().map(::itemJson),
    )))

private fun listCodeLists(call: ApplicationCall, d: AdminMasterDeps): JsonObject {
    call.admPrincipal(MASTER_READERS, "code lists")
    return d.db.jdbi.withHandle<JsonObject, Exception> { h -> JsonObject(mapOf("lists" to JsonArray(TABLE_LISTS.map { loadList(h, it) }))) }
}

private class ItemIn(val code: String, val labelEn: String, val labelBn: String?, val hasBn: Boolean, val sort: Int, val attrs: JsonObject?, val from: LocalDate?, val hasTo: Boolean, val to: LocalDate?)

private fun parseItem(el: JsonElement, i: Int): ItemIn {
    val o = el as? JsonObject ?: admBad("body.items/$i", "invalid_type")
    val allowed = setOf("code", "label_bn", "label_en", "sort", "attrs", "valid_from", "valid_to")
    o.keys.firstOrNull { it !in allowed }?.let { admBad("body.items/$i/$it", "unknown_member") }
    val f = Fields(o, allowed)
    fun req(k: String) { if (!f.has(k)) admBad("body.items/$i/$k", "required") }
    req("code"); req("label_en"); req("sort")
    val code = f.value(Col("code", Kind.TEXT, min = 2, max = 41, pattern = CODE_ITEM_RE)) as String
    val en = f.value(Col("label_en", Kind.TEXT, min = 1, max = 120)) as String
    val bn = if (f.has("label_bn")) f.value(Col("label_bn", Kind.TEXT, nullable = true, max = 120)) as String? else null
    val sort = f.value(Col("sort", Kind.INT, min = -1_000_000, max = 1_000_000)) as Int
    val attrs = o["attrs"]?.let { a ->
        val ao = a as? JsonObject ?: admBad("body.items/$i/attrs", "invalid_type")
        if (ao.size > 20) admBad("body.items/$i/attrs", "too_many")
        ao.forEach { (k, v) ->
            val ok = k.length in 1..40 && v is JsonPrimitive && (v is JsonNull || (v.isString && v.content.length <= 200) || v.booleanOrNull != null || v.doubleOrNull != null)
            if (!ok) admBad("body.items/$i/attrs/$k", "invalid_value")
        }
        ao
    }
    val from = o["valid_from"]?.let { parseDate("body.items/$i/valid_from", it) }
    val to = o["valid_to"]?.takeIf { it !is JsonNull }?.let { parseDate("body.items/$i/valid_to", it) }
    return ItemIn(code, en, bn, f.has("label_bn"), sort, attrs, from, f.has("valid_to"), to)
}

/** Bumps the config version of kind `content` so phones pull the changed lists (same lock and notify as the config service). */
internal fun bumpContentVersion(h: Handle, ctx: AdminCtx, summary: String) {
    h.execute("SELECT pg_advisory_xact_lock(7242001)")
    val v = h.scalarLong("SELECT COALESCE(max(config_version), 0) + 1 FROM app.cfg_version")
    h.createUpdate("INSERT INTO app.cfg_version (config_version, kind, committed_at, committed_by, summary) VALUES (:v, 'content', :at, :by, :s)")
        .bind("v", v).bind("at", odt(ctx.now)).bind("by", ctx.p.userId).bind("s", summary.take(500)).execute()
    h.createUpdate("SELECT pg_notify('cfg_changed', :v)").bind("v", v.toString()).execute()
}

private suspend fun putCodeList(call: ApplicationCall, d: AdminMasterDeps) {
    val p = call.admPrincipal(MASTER_WRITERS, "code list changes")
    val key = call.parameters["list_key"].orEmpty()
    if (key in OTHER_LISTS) admBad("path.list_key", "list_not_editable", "$key is not an editable code list")
    if (key !in TABLE_LISTS) admBad("path.list_key")
    val f = call.admBody(setOf("items", "change_reason"))
    val arr = f.obj["items"] as? JsonArray ?: admBad("body.items", "required")
    if (arr.size !in 1..100) admBad("body.items", "length")
    val items = arr.mapIndexed { i, el -> parseItem(el, i) }
    if (items.map { it.code }.toSet().size != items.size) admBad("body.items", "duplicate_code")
    val reason = f.reason(true)
    val ctx = d.ctx(call, p)
    val out = admWrite {
        d.db.jdbi.inTransaction<JsonObject, Exception> { h ->
            h.execute("SELECT 1 FROM app.code_list WHERE list_key = ? FOR UPDATE", key)
            val existing = h.createQuery("SELECT * FROM app.code_list_item WHERE list_key = :k").bind("k", key).mapToMap().list().associateBy { it["code"] as String }
            val newCount = items.count { it.code !in existing }
            if (existing.size + newCount > 100) admBad("body.items", "too_many_items", "a list holds at most 100 items")
            val before = ArrayList<JsonElement>(); val after = ArrayList<JsonElement>()
            for ((i, it) in items.withIndex()) {
                val cur = existing[it.code]
                if (cur == null) {
                    val from = it.from ?: ctx.today
                    if (it.to != null && !it.to.isAfter(from)) admBad("body.items/$i/valid_to", "before_valid_from")
                    val r = h.createQuery(
                        "INSERT INTO app.code_list_item (list_key, code, label_en, label_bn, sort, attrs, valid_from, valid_to) VALUES (:k, :c, :en, :bn, :s, CAST(:a AS jsonb), :f, :t) RETURNING *",
                    ).bind("k", key).bind("c", it.code).bind("en", it.labelEn).bindAny("bn", it.labelBn).bind("s", it.sort).bind("a", (it.attrs ?: JsonObject(emptyMap())).toString())
                        .bind("f", from).bindAny("t", it.to).mapToMap().one()
                    after += itemJson(r)
                } else {
                    val curFrom = (cur["valid_from"] as java.sql.Date).toLocalDate()
                    val curTo = (cur["valid_to"] as java.sql.Date?)?.toLocalDate()
                    if (it.from != null && it.from != curFrom) admBad("body.items/$i/valid_from", "immutable", "a code's valid_from never changes; retire it with valid_to")
                    val newTo = if (it.hasTo) it.to else curTo
                    if (newTo != null && !newTo.isAfter(curFrom)) admBad("body.items/$i/valid_to", "before_valid_from")
                    if (it.hasTo && newTo != curTo && newTo != null && newTo.isBefore(ctx.today))
                        throw ApiProblem(ProblemCode.ERR_MASTER_EFFECTIVE_DATE_PAST, "a code is retired from today or later", errors = listOf(com.aktcl.aron.backend.platform.FieldError("body.items/$i/valid_to", "past")))
                    val newBn = if (it.hasBn) it.labelBn else cur["label_bn"] as String?
                    val curAttrs = attrsOf(cur["attrs"])
                    val newAttrs = it.attrs ?: curAttrs
                    val unchanged = cur["label_en"] == it.labelEn && cur["label_bn"] == newBn && (cur["sort"] as Number).toInt() == it.sort && curTo == newTo && curAttrs == newAttrs
                    if (unchanged) continue
                    before += itemJson(cur)
                    val r = h.createQuery("UPDATE app.code_list_item SET label_en = :en, label_bn = :bn, sort = :s, attrs = CAST(:a AS jsonb), valid_to = :t WHERE id = :id RETURNING *")
                        .bind("en", it.labelEn).bindAny("bn", newBn).bind("s", it.sort).bind("a", newAttrs.toString()).bindAny("t", newTo).bind("id", cur["id"]).mapToMap().one()
                    after += itemJson(r)
                }
            }
            if (after.isNotEmpty()) {
                h.createUpdate("UPDATE app.code_list SET updated_at = :at WHERE list_key = :k").bind("at", odt(ctx.now)).bind("k", key).execute()
                ctx.audit(h, "code_list", key, "put", JsonArray(before), JsonArray(after), reason)
                bumpContentVersion(h, ctx, "code list $key: ${after.size} item(s)")
            }
            loadList(h, key)
        }
    }
    call.admRespond(HttpStatusCode.OK, out)
}

/** `GET/POST /v1/admin/calendar/holidays`, `GET /v1/admin/code-lists`, `PUT /v1/admin/code-lists/{list_key}` (tag admin-calendar). */
fun Route.adminCalendarRoutes(d: AdminMasterDeps) {
    authenticated(d.guard) {
        get("/admin/calendar/holidays") { call.admRespond(HttpStatusCode.OK, listHolidays(call, d)) }
        post("/admin/calendar/holidays") { createHoliday(call, d) }
        get("/admin/code-lists") { call.admRespond(HttpStatusCode.OK, listCodeLists(call, d)) }
        put("/admin/code-lists/{list_key}") { putCodeList(call, d) }
    }
}
