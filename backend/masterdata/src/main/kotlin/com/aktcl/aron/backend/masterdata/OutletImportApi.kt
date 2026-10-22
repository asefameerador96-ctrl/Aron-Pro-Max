package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.contract.ProblemCode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import java.security.MessageDigest

private const val MAX_IMPORT_ROWS = 5000
private const val MAX_IMPORT_ERRORS = 1000
private val ZONE_CODE_RE = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$")
private val ROUTE_CODE_RE = Regex("^[A-Za-z0-9][A-Za-z0-9._/-]{0,39}$")
private val IMPORT_CODE_RE = Regex("^[0-9A-Za-z][0-9A-Za-z._/-]{0,31}$")
private val IMPORT_PHONE_RE = Regex("^01[3-9][0-9]{8}$")
private val IMPORT_MEMBERS = setOf("code", "name", "owner_name", "zone_code", "cluster", "channel", "outlet_kind", "route_code", "lat", "lng", "contact_number", "address")

private class ImportRow(
    val index: Int, val code: String?, val name: String?, val owner: String?, val zone: String?, val cluster: String?, val channel: String,
    val kind: String?, val route: String?, val lat: Double?, val lng: Double?, val phone: String?, val address: String?,
    /** Column to code, found while reading (type and shape problems); reported with the lookup problems, in row order. */
    val shape: List<Pair<String, String>>,
)

private class ImportError(val row: Int, val column: String, val code: String)

/**
 * Reads one row. A row that is not an object, carries an unknown member, or has a missing or unknown channel refuses the whole request (400 with the
 * pointer `body.rows/<i>/<member>`, also on a dry run: docs/34 s2, the channel is mandatory). Every other problem is a row error in the report.
 */
private fun readRow(i: Int, el: kotlinx.serialization.json.JsonElement): ImportRow {
    val o = el as? JsonObject ?: admBad("body.rows/$i", "invalid_type")
    o.keys.firstOrNull { it !in IMPORT_MEMBERS }?.let { admBad("body.rows/$i/$it", "unknown_member") }
    val ch = o["channel"]
    val channel = when {
        ch == null || ch is JsonNull || (ch is JsonPrimitive && ch.isString && ch.content.isBlank()) ->
            admBad("body.rows/$i/channel", "required", "channel is required (one of ${OUTLET_CHANNELS.joinToString(", ")})")
        ch is JsonPrimitive && ch.isString && ch.content in OUTLET_CHANNELS -> ch.content
        else -> admBad("body.rows/$i/channel", "invalid", "channel is not a valid value (one of ${OUTLET_CHANNELS.joinToString(", ")})")
    }
    val shape = ArrayList<Pair<String, String>>()
    fun text(k: String, required: Boolean): String? {
        val v = o[k]
        if (v == null || v is JsonNull) { if (required) shape += k to "required"; return null }
        val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (s == null || s.contains('\u0000')) { shape += k to "invalid"; return null }
        val t = s.trim()
        if (t.isEmpty()) { if (required) shape += k to "required"; return null }
        return t
    }
    fun num(k: String, lo: Double, hi: Double): Double? {
        val v = o[k]
        if (v == null || v is JsonNull) return null
        val d = (v as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.takeIf { it.isFinite() && it in lo..hi }
        if (d == null) shape += k to "invalid"
        return d
    }
    val code = text("code", false)?.also { if (!IMPORT_CODE_RE.matches(it)) shape += "code" to "invalid" }
    val name = text("name", true)?.also { if (it.length !in 2..120) shape += "name" to "invalid" }
    val owner = text("owner_name", true)?.also { if (it.length !in 2..120) shape += "owner_name" to "invalid" }
    val zone = text("zone_code", true)?.also { if (!ZONE_CODE_RE.matches(it)) shape += "zone_code" to "invalid" }
    val cluster = text("cluster", true)?.also { if (it.length > 120) shape += "cluster" to "invalid" }
    val kind = text("outlet_kind", true)?.also { if (it !in setOf("retail", "wholesale")) shape += "outlet_kind" to "invalid" }
    val route = text("route_code", false)?.also { if (!ROUTE_CODE_RE.matches(it)) shape += "route_code" to "invalid" }
    val phone = text("contact_number", false)?.let { normDigits(it) }?.also { if (!IMPORT_PHONE_RE.matches(it)) shape += "contact_number" to "invalid" }
    val address = text("address", false)?.also { if (it.length > 300) shape += "address" to "invalid" }
    val lat = num("lat", -90.0, 90.0)
    val lng = num("lng", -180.0, 180.0)
    if ((o["lat"].let { it != null && it !is JsonNull }) != (o["lng"].let { it != null && it !is JsonNull })) {
        val missing = if (o["lat"].let { it == null || it is JsonNull }) "lat" else "lng"
        if (shape.none { it.first == missing }) shape += missing to "required"
    }
    return ImportRow(i, code, name, owner, zone, cluster, channel, kind, route, lat, lng, phone, address, shape)
}

private class Lookups(
    val zones: Map<String, Long>, val clusters: Map<Pair<Long, String>, Long>, val routes: Map<String, Pair<Long, Long>>,
    val outlets: Map<String, Map<String, Any?>>,
)

private fun lookups(h: Handle, ctx: AdminCtx, rows: List<ImportRow>): Lookups {
    val zoneCodes = rows.mapNotNull { it.zone }.distinct()
    val zones = HashMap<String, Long>()
    if (zoneCodes.isNotEmpty()) h.createQuery("SELECT id, code FROM app.zone WHERE code IN (<c>) AND status = 'active'").bindList("c", zoneCodes)
        .map { rs, _ -> rs.getString("code") to rs.getLong("id") }.list().filter { ctx.reach.coversZone(it.second) }.forEach { zones[it.first] = it.second }
    val clusters = HashMap<Pair<Long, String>, Long>()
    if (zones.isNotEmpty()) h.createQuery("SELECT id, zone_id, name FROM app.cluster WHERE zone_id IN (<z>) AND status = 'active'").bindList("z", zones.values.toList())
        .map { rs, _ -> (rs.getLong("zone_id") to rs.getString("name")) to rs.getLong("id") }.list().forEach { clusters[it.first] = it.second }
    val routeCodes = rows.mapNotNull { it.route }.distinct()
    val routes = HashMap<String, Pair<Long, Long>>()
    if (routeCodes.isNotEmpty()) h.createQuery("SELECT id, zone_id, code FROM app.route WHERE code IN (<c>) AND status = 'active'").bindList("c", routeCodes)
        .map { rs, _ -> rs.getString("code") to (rs.getLong("id") to rs.getLong("zone_id")) }.list().forEach { routes[it.first] = it.second }
    val codes = rows.mapNotNull { it.code }.distinct()
    val outlets = HashMap<String, Map<String, Any?>>()
    if (codes.isNotEmpty()) h.createQuery("SELECT id, code, zone_id, status FROM app.outlet WHERE code IN (<c>)").bindList("c", codes).mapToMap().list().forEach { outlets[it["code"] as String] = it }
    return Lookups(zones, clusters, routes, outlets)
}

private class Checked(val errors: List<ImportError>, val creates: Int, val updates: Int, val byChannel: Map<String, Int>)

private fun check(rows: List<ImportRow>, l: Lookups): Checked {
    val errors = ArrayList<ImportError>()
    var creates = 0; var updates = 0
    val byChannel = LinkedHashMap<String, Int>().also { m -> OUTLET_CHANNELS.forEach { m[it] = 0 } }
    val seen = HashSet<String>()
    for (r in rows) {
        val row = r.index + 1
        val before = errors.size
        r.shape.forEach { errors += ImportError(row, it.first, it.second) }
        val zone = r.zone?.takeIf { ZONE_CODE_RE.matches(it) }?.let { z -> l.zones[z]?.also { } ?: run { errors += ImportError(row, "zone_code", "unknown_zone"); null } }
        if (zone != null && r.cluster != null && l.clusters[zone to r.cluster] == null) errors += ImportError(row, "cluster", "unknown_cluster")
        if (r.route != null && ROUTE_CODE_RE.matches(r.route)) {
            val rt = l.routes[r.route]
            if (rt == null || (zone != null && rt.second != zone)) errors += ImportError(row, "route_code", "unknown_route")
        }
        var existing = false
        if (r.code != null && IMPORT_CODE_RE.matches(r.code)) {
            if (!seen.add(r.code)) errors += ImportError(row, "code", "duplicate")
            val o = l.outlets[r.code]
            if (o != null) {
                if (zone != null && o["zone_id"] != zone) errors += ImportError(row, "code", "code_taken")
                else if (o["status"] == "merged") errors += ImportError(row, "code", "outlet_merged")
                else existing = true
            }
        }
        if (errors.size == before) { if (existing) updates++ else creates++; byChannel.merge(r.channel, 1, Int::plus) }
    }
    return Checked(errors, creates, updates, byChannel)
}

private fun fingerprint(rows: List<ImportRow>): String {
    val md = MessageDigest.getInstance("SHA-256")
    // Order-independent: the same rows in another order are the same batch.
    rows.map { listOf(it.code, it.name, it.owner, it.zone, it.cluster, it.channel, it.kind, it.route, it.lat, it.lng, it.phone, it.address).joinToString("\u001f") { v -> v?.toString() ?: "\u0000" } }
        .sorted().forEach { md.update(it.toByteArray()); md.update(10) }
    return md.digest().joinToString("") { "%02x".format(it) }
}

private fun errorsJson(errors: List<ImportError>) = buildJsonArray {
    errors.take(MAX_IMPORT_ERRORS).forEach { e -> add(buildJsonObject { put("row", e.row); put("column", e.column); put("code", e.code) }) }
}

private fun Handle.rowId(sql: String, vararg binds: Pair<String, Any?>): Long? =
    createQuery(sql).also { q -> binds.forEach { q.bindAny(it.first, it.second) } }.mapTo(Long::class.java).findOne().orElse(null)

/**
 * `POST /v1/admin/outlets/import` (docs/34 s2): bulk create-or-update of outlets from a sheet the portal read in the browser. The channel is mandatory on
 * every row. `dry_run: true` writes nothing and returns the report; `dry_run: false` needs a `change_reason`, refuses the whole batch on any row error and
 * writes all rows in one transaction. A row whose `code` names an outlet of the same zone updates it (name, owner, cluster, channel, kind; route, pin,
 * phone and address only when the row gives them; never the status), anything else creates one (`AD-<id>` when the code is null). Idempotent by
 * `batch_uuid`: the batch is one audit row (`outlet_import`) holding the content fingerprint; the same content replays, other content is 409
 * `ERR_SYNC_BATCH_UUID_REUSED`. One audit row per outlet as well.
 */
internal suspend fun importOutlets(call: ApplicationCall, d: AdminMasterDeps) {
    val p = call.admPrincipal(MASTER_WRITERS, "outlet import")
    val f = call.admBody(setOf("batch_uuid", "rows", "dry_run", "change_reason"))
    fun need(k: String) { if (!f.has(k)) admBad("body.$k", "required", "$k is required") }
    need("batch_uuid"); need("rows"); need("dry_run")
    val batch = (f.obj["batch_uuid"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { AdminSupport.uuid(it, "body.batch_uuid") } ?: admBad("body.batch_uuid")
    val dry = (f.obj["dry_run"] as? JsonPrimitive)?.takeIf { !it.isString }?.content.let { when (it) { "true" -> true; "false" -> false; else -> admBad("body.dry_run", "invalid_type") } }
    val arr = f.obj["rows"] as? JsonArray ?: admBad("body.rows", "invalid_type")
    if (arr.size !in 1..MAX_IMPORT_ROWS) admBad("body.rows", "length")
    val rows = arr.mapIndexed { i, el -> readRow(i, el) }
    val ctx = d.ctx(call, p)
    if (dry) {
        val out = d.db.jdbi.withHandle<JsonObject, Exception> { h ->
            val c = check(rows, lookups(h, ctx, rows))
            buildJsonObject {
                put("batch_uuid", batch.toString()); put("rows", rows.size); put("creates", c.creates); put("updates", c.updates)
                put("by_channel", buildJsonObject { c.byChannel.forEach { (k, v) -> put(k, v) } })
                put("errors", errorsJson(c.errors)); put("errors_truncated", c.errors.size > MAX_IMPORT_ERRORS)
            }
        }
        call.admRespond(HttpStatusCode.OK, out)
        return
    }
    val reason = f.reason(true)!!
    val fp = fingerprint(rows)
    val result = admWrite {
        d.db.jdbi.inTransaction<JsonObject, Exception> { h ->
            h.execute("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "outlet_import:$batch")
            val prior = h.createQuery("SELECT after::text FROM app.audit_log WHERE entity = 'outlet_import' AND entity_id = :b ORDER BY id LIMIT 1").bind("b", batch.toString()).mapTo(String::class.java).findOne().orElse(null)
            if (prior != null) {
                val o = Json.parseToJsonElement(prior) as JsonObject
                if (o.getValue("fingerprint").jsonPrimitive.content != fp) throw ApiProblem(ProblemCode.ERR_SYNC_BATCH_UUID_REUSED, "this batch_uuid was used with other rows; send the new rows under a new batch_uuid")
                return@inTransaction buildJsonObject { put("batch_uuid", batch.toString()); put("rows", rows.size); put("replayed", true) }
            }
            // Same lock order as other outlet writers: lock the existing outlets of the batch first.
            val codes = rows.mapNotNull { it.code }.distinct()
            if (codes.isNotEmpty()) h.createQuery("SELECT id FROM app.outlet WHERE code IN (<c>) ORDER BY id FOR UPDATE").bindList("c", codes).mapTo(Long::class.java).list()
            val l = lookups(h, ctx, rows)
            val c = check(rows, l)
            if (c.errors.isNotEmpty()) throw ApiProblem(ProblemCode.ERR_VALIDATION, "${c.errors.size} row errors", errors = c.errors.take(MAX_IMPORT_ERRORS).map { FieldError("body.rows/${it.row - 1}/${it.column}", it.code) })
            for (r in rows) applyRow(h, ctx, l, r, batch.toString(), reason)
            ctx.audit(h, "outlet_import", batch.toString(), "import", null, buildJsonObject {
                put("fingerprint", fp); put("rows", rows.size); put("creates", c.creates); put("updates", c.updates)
                put("by_channel", buildJsonObject { c.byChannel.forEach { (k, v) -> put(k, v) } })
            }, reason)
            buildJsonObject { put("batch_uuid", batch.toString()); put("rows", rows.size); put("replayed", false) }
        }
    }
    call.admRespond(HttpStatusCode.OK, result)
}

private fun applyRow(h: Handle, ctx: AdminCtx, l: Lookups, r: ImportRow, batch: String, reason: String) {
    val zone = l.zones.getValue(r.zone!!)
    val cluster = l.clusters.getValue(zone to r.cluster!!)
    val route = r.route?.let { l.routes.getValue(it).first }
    val existing = r.code?.let { l.outlets[it] }
    if (existing == null) {
        val id = h.scalarLong("SELECT nextval(pg_get_serial_sequence('app.outlet', 'id'))")
        val code = r.code ?: "AD-$id"
        h.createUpdate(
            "INSERT INTO app.outlet (id, code, name, owner_name, contact_number, address, zone_id, route_id, cluster_id, channel, lat, lng, location_basis, outlet_kind, created_by, updated_by) " +
                "VALUES (:id, :code, :name, :owner, :phone, :addr, :zone, :route, :cluster, :channel, :lat, :lng, :basis, :kind, :by, :by)",
        ).bind("id", id).bind("code", code).bind("name", r.name!!).bind("owner", r.owner!!).bindAny("phone", r.phone).bindAny("addr", r.address).bind("zone", zone)
            .bindAny("route", route).bind("cluster", cluster).bind("channel", r.channel).bindAny("lat", r.lat).bindAny("lng", r.lng)
            .bind("basis", if (r.lat != null) "master" else "none").bind("kind", r.kind!!).bind("by", ctx.p.userId).execute()
        h.createUpdate("INSERT INTO app.outlet_placement_history (outlet_id, route_id, cluster_id, valid_from, created_by) VALUES (:o, :r, :c, :f, :by)")
            .bind("o", id).bindAny("r", route).bind("c", cluster).bind("f", ctx.today).bind("by", ctx.p.userId).execute()
        h.createUpdate("INSERT INTO app.outlet_location_history (outlet_id, lat, lng, source, basis, created_by) VALUES (:o, :la, :ln, 'web_edit', :basis, :by)")
            .bind("o", id).bindAny("la", r.lat).bindAny("ln", r.lng).bind("basis", if (r.lat != null) "master" else "none").bind("by", ctx.p.userId).execute()
        ctx.audit(h, "outlet", id.toString(), "import_create", null, buildJsonObject {
            put("code", code); put("name", r.name); put("owner_name", r.owner); put("zone_id", zone); put("cluster_id", cluster); put("channel", r.channel); put("outlet_kind", r.kind)
            put("route_id", route?.let { JsonPrimitive(it) } ?: JsonNull); put("lat", r.lat?.let { JsonPrimitive(it) } ?: JsonNull); put("lng", r.lng?.let { JsonPrimitive(it) } ?: JsonNull)
            put("contact_number", r.phone?.let { JsonPrimitive(it) } ?: JsonNull); put("batch_uuid", batch)
        }, reason)
        return
    }
    val id = (existing["id"] as Number).toLong()
    val cur = h.row("SELECT name, owner_name, cluster_id, route_id, channel, outlet_kind, lat, lng FROM app.outlet WHERE id = :i", "i" to id)!!
    val newRoute = route ?: (cur["route_id"] as Number?)?.toLong()
    val newLat = r.lat ?: cur["lat"] as Double?
    val newLng = r.lng ?: cur["lng"] as Double?
    val pinChanged = r.lat != null && (r.lat != cur["lat"] || r.lng != cur["lng"])
    val placeChanged = newRoute != (cur["route_id"] as Number?)?.toLong() || cluster != (cur["cluster_id"] as Number).toLong()
    val sets = StringBuilder("name = :name, owner_name = :owner, cluster_id = :cluster, route_id = :route, channel = :channel, outlet_kind = :kind, updated_by = :by")
    if (r.phone != null) sets.append(", contact_number = :phone")
    if (r.address != null) sets.append(", address = :addr")
    if (pinChanged) sets.append(", lat = :lat, lng = :lng, location_basis = 'master', provisional_lat = NULL, provisional_lng = NULL")
    val u = h.createUpdate("UPDATE app.outlet SET $sets WHERE id = :id").bind("id", id).bind("name", r.name!!).bind("owner", r.owner!!).bind("cluster", cluster)
        .bindAny("route", newRoute).bind("channel", r.channel).bind("kind", r.kind!!).bind("by", ctx.p.userId)
    if (r.phone != null) u.bind("phone", r.phone)
    if (r.address != null) u.bind("addr", r.address)
    if (pinChanged) u.bind("lat", r.lat!!).bind("lng", r.lng!!)
    u.execute()
    if (placeChanged) {
        val open = h.createQuery("SELECT id, valid_from FROM app.outlet_placement_history WHERE outlet_id = :o AND valid_to IS NULL").bind("o", id).mapToMap().findOne().orElse(null)
        val openFrom = (open?.get("valid_from") as java.sql.Date?)?.toLocalDate()
        if (open != null && openFrom == ctx.today) {
            h.createUpdate("UPDATE app.outlet_placement_history SET route_id = :r, cluster_id = :c WHERE id = :i").bindAny("r", newRoute).bind("c", cluster).bind("i", open["id"]).execute()
        } else {
            if (open != null) h.createUpdate("UPDATE app.outlet_placement_history SET valid_to = :t WHERE id = :i").bind("t", ctx.today).bind("i", open["id"]).execute()
            h.createUpdate("INSERT INTO app.outlet_placement_history (outlet_id, route_id, cluster_id, valid_from, created_by) VALUES (:o, :r, :c, :f, :by)")
                .bind("o", id).bindAny("r", newRoute).bind("c", cluster).bind("f", ctx.today).bind("by", ctx.p.userId).execute()
        }
    }
    if (pinChanged) {
        h.createUpdate("INSERT INTO app.outlet_location_history (outlet_id, lat, lng, source, basis, created_by) VALUES (:o, :la, :ln, 'web_edit', 'master', :by)")
            .bind("o", id).bind("la", r.lat!!).bind("ln", r.lng!!).bind("by", ctx.p.userId).execute()
    }
    ctx.audit(h, "outlet", id.toString(), "import_update",
        buildJsonObject { put("name", cur["name"] as String); put("channel", cur["channel"] as String); put("outlet_kind", cur["outlet_kind"] as String); put("cluster_id", (cur["cluster_id"] as Number).toLong()) },
        buildJsonObject {
            put("name", r.name); put("channel", r.channel); put("outlet_kind", r.kind); put("cluster_id", cluster)
            put("route_id", newRoute?.let { JsonPrimitive(it) } ?: JsonNull); put("pin_changed", pinChanged); put("batch_uuid", batch)
        }, reason)
}
