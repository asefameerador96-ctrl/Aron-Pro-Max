package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.contract.ProblemCode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.util.UUID

private val OUTLET_CODE_RE = Regex("^[0-9A-Za-z][0-9A-Za-z._/-]{0,31}$")
private val PHONE_RE = Regex("^01[3-9][0-9]{8}$")
private val CHANNELS = setOf("GT", "DCC", "Astha", "RCC", "MT", "HoReCa")

private fun outletJson(r: Map<String, Any?>, pii: Boolean) = JsonObject(mapOf(
    "id" to jv(r["id"]), "code" to jv(r["code"]), "name" to jv(r["name"]), "name_bn" to jv(r["name_bn"]), "owner_name" to jv(r["owner_name"]),
    "contact_number" to jv(if (pii) r["contact_number"] else null), "address" to jv(r["address"]), "zone_id" to jv(r["zone_id"]),
    "route_id" to jv(r["route_id"]), "cluster_id" to jv(r["cluster_id"]), "channel" to jv(r["channel"]), "sub_channel_id" to jv(r["sub_channel_id"]),
    "geo_class" to jv(r["geo_class"]), "lat" to jv(r["lat"]), "lng" to jv(r["lng"]), "location_confirmed" to jv(r["location_confirmed"]),
    "outlet_kind" to jv(r["outlet_kind"]), "price_type" to jv(r["price_type"]), "status" to jv(r["status"]), "visit_sequence" to jv(r["visit_sequence"]),
    "external_ref" to jv(r["external_ref"]), "created_at" to jv(r["created_at"]), "updated_at" to jv(r["updated_at"]), "version" to jv(r["version"]),
))

internal val OUTLET = MasterEntity(
    audit = "outlet", table = "app.outlet", statuses = setOf("active", "closed", "merged", "archived"),
    cols = listOf(
        Col("code", Kind.TEXT, onPatch = false, min = 1, max = 32, pattern = OUTLET_CODE_RE),
        Col("name", Kind.TEXT, required = true, min = 2, max = 120),
        Col("name_bn", Kind.TEXT, nullable = true, max = 120),
        Col("owner_name", Kind.TEXT, required = true, min = 2, max = 120),
        Col("contact_number", Kind.TEXT, nullable = true, pattern = PHONE_RE, digits = true),
        Col("address", Kind.TEXT, nullable = true, max = 300),
        Col("zone_id", Kind.LONG, required = true, onPatch = false, min = 1),
        Col("route_id", Kind.LONG, nullable = true, min = 1),
        Col("cluster_id", Kind.LONG, required = true, min = 1),
        Col("channel", Kind.ENUM, required = true, values = CHANNELS),
        Col("sub_channel_id", Kind.LONG, nullable = true, min = 1),
        Col("geo_class", Kind.ENUM, nullable = true, values = setOf("Hill", "Urban", "SemiUrban", "Rural")),
        Col("lat", Kind.DOUBLE, nullable = true, minD = -90.0, maxD = 90.0),
        Col("lng", Kind.DOUBLE, nullable = true, minD = -180.0, maxD = 180.0),
        Col("outlet_kind", Kind.ENUM, required = true, values = setOf("retail", "wholesale")),
        Col("status", Kind.ENUM, onCreate = false, values = setOf("active", "closed", "archived")),
        Col("visit_sequence", Kind.INT, nullable = true, min = 1, max = 100000),
        Col("external_ref", Kind.TEXT, nullable = true, max = 64),
    ),
    reach = { reach, _ -> if (reach.national) null else idsPred("t.zone_id", "r_ids", reach.zoneIds) },
    zoneCol = "zone_id", searchCols = listOf("code", "name"),
    out = { r, p -> outletJson(r, p.pii) },
    validate = { h, ctx, merged, cur, changes ->
        if (cur != null && cur["status"] == "merged" && changes.isNotEmpty()) throw ApiProblem(ProblemCode.ERR_CONFLICT, "a merged outlet is read-only")
        if ((merged["lat"] == null) != (merged["lng"] == null)) admBad("body.lat", "lat_lng_pair", "lat and lng are given together or not at all")
        val clusterZone = h.createQuery("SELECT zone_id FROM app.cluster WHERE id = :c").bind("c", merged["cluster_id"]).mapTo(Long::class.java).findOne().orElse(null)
        if (cur == null || "cluster_id" in changes) {
            if (clusterZone == null || !ctx.reach.coversZone(clusterZone)) admBad("body.cluster_id", "unknown_cluster", "the cluster does not exist or is outside your reach")
        }
        if (cur == null && clusterZone != merged["zone_id"]) admBad("body.cluster_id", "cluster_zone_mismatch", "the cluster belongs to another zone")
        val zone = if (cur == null) merged["zone_id"] as Long else (clusterZone ?: cur["zone_id"] as Long)
        if (cur == null || "route_id" in changes || "cluster_id" in changes) {
            val rid = merged["route_id"]
            if (rid != null) {
                val rz = h.createQuery("SELECT zone_id, status FROM app.route WHERE id = :r").bind("r", rid).mapToMap().findOne().orElse(null)
                if (rz == null || rz["zone_id"] != zone) admBad("body.route_id", "unknown_route", "the route does not exist or belongs to another zone")
                if (rz["status"] != "active" && (cur == null || "route_id" in changes)) admBad("body.route_id", "route_inactive", "the route is inactive")
            }
        }
        if (cur == null || "sub_channel_id" in changes || "channel" in changes) {
            val sid = merged["sub_channel_id"]
            if (sid != null) {
                val sc = h.createQuery("SELECT channel, status FROM app.sub_channel WHERE id = :s").bind("s", sid).mapToMap().findOne().orElse(null)
                if (sc == null || sc["channel"] != merged["channel"] || sc["status"] != "active") admBad("body.sub_channel_id", "unknown_sub_channel", "the sub-channel does not exist, is inactive, or belongs to another channel")
            }
        }
    },
    derive = { h, ctx, merged, cur, changes, _ ->
        val out = LinkedHashMap<String, Any?>()
        val pinChanged = changes.containsKey("lat") || changes.containsKey("lng")
        if (cur == null) {
            val id = h.scalarLong("SELECT nextval(pg_get_serial_sequence('app.outlet', 'id'))")
            out["id"] = id
            if (merged["code"] == null) out["code"] = "AD-$id"
            out["location_basis"] = if (merged["lat"] != null) "master" else "none"
        } else {
            val newZone = h.createQuery("SELECT zone_id FROM app.cluster WHERE id = :c").bind("c", merged["cluster_id"]).mapTo(Long::class.java).one()
            if (newZone != cur["zone_id"]) out["zone_id"] = newZone
            if (pinChanged) {
                out["location_basis"] = if (merged["lat"] != null) "master" else "none"
                out["provisional_lat"] = null; out["provisional_lng"] = null
                if (merged["lat"] == null) out["location_confirmed"] = false
            }
            changes["status"]?.let { st ->
                out["closed_at"] = if (st == "active") null else (cur["closed_at"] ?: odt(ctx.now))
            }
        }
        out
    },
    after = { h, ctx, row, cur, changes, _ ->
        val id = row["id"]
        if (cur == null) {
            h.createUpdate("INSERT INTO app.outlet_placement_history (outlet_id, route_id, cluster_id, valid_from, created_by) VALUES (:o, :r, :c, :f, :by)")
                .bind("o", id).bindAny("r", row["route_id"]).bind("c", row["cluster_id"]).bind("f", ctx.today).bind("by", ctx.p.userId).execute()
        } else if ("route_id" in changes || "cluster_id" in changes) {
            val open = h.createQuery("SELECT id, valid_from FROM app.outlet_placement_history WHERE outlet_id = :o AND valid_to IS NULL").bind("o", id).mapToMap().findOne().orElse(null)
            val openFrom = (open?.get("valid_from") as java.sql.Date?)?.toLocalDate()
            if (open != null && openFrom == ctx.today) {
                // Same-day correction: the open row is rewritten (a row cannot end on the day it starts).
                h.createUpdate("UPDATE app.outlet_placement_history SET route_id = :r, cluster_id = :c WHERE id = :i").bindAny("r", row["route_id"]).bind("c", row["cluster_id"]).bind("i", open["id"]).execute()
            } else {
                if (open != null) h.createUpdate("UPDATE app.outlet_placement_history SET valid_to = :t WHERE id = :i").bind("t", ctx.today).bind("i", open["id"]).execute()
                h.createUpdate("INSERT INTO app.outlet_placement_history (outlet_id, route_id, cluster_id, valid_from, created_by) VALUES (:o, :r, :c, :f, :by)")
                    .bind("o", id).bindAny("r", row["route_id"]).bind("c", row["cluster_id"]).bind("f", ctx.today).bind("by", ctx.p.userId).execute()
            }
        }
        val pinSet = row["lat"] != null && (cur == null || changes.containsKey("lat") || changes.containsKey("lng"))
        if (pinSet) h.createUpdate("INSERT INTO app.outlet_location_history (outlet_id, lat, lng, source, basis, created_by) VALUES (:o, :la, :ln, 'web_edit', 'master', :by)")
            .bind("o", id).bind("la", row["lat"]).bind("ln", row["lng"]).bind("by", ctx.p.userId).execute()
    },
)

private suspend fun getOutlet(call: ApplicationCall, d: AdminMasterDeps) {
    val p = call.admPrincipal(MASTER_READERS, "outlet master data")
    val id = call.admId()
    val ctx = d.ctx(call, p)
    val (row, place, loc) = d.db.jdbi.withHandle<Triple<Map<String, Any?>, List<Map<String, Any?>>, List<Map<String, Any?>>>, Exception> { h ->
        val r = ctx.visibleRow(h, OUTLET, id, false) ?: admNotFound()
        Triple(
            r,
            h.createQuery("SELECT route_id, cluster_id, valid_from, valid_to FROM app.outlet_placement_history WHERE outlet_id = :o ORDER BY valid_from, id").bind("o", id).mapToMap().list(),
            h.createQuery("SELECT lat, lng, accuracy_m, source, valid_from FROM app.outlet_location_history WHERE outlet_id = :o ORDER BY valid_from, id").bind("o", id).mapToMap().list(),
        )
    }
    val body = JsonObject(outletJson(row, p.pii) + mapOf(
        "placement_history" to JsonArray(place.map { JsonObject(mapOf("route_id" to jv(it["route_id"]), "cluster_id" to jv(it["cluster_id"]), "valid_from" to jv(it["valid_from"]), "valid_to" to jv(it["valid_to"]))) }),
        "location_history" to JsonArray(loc.map { JsonObject(mapOf("lat" to jv(it["lat"]), "lng" to jv(it["lng"]), "accuracy_m" to jv(it["accuracy_m"]), "source" to jv(it["source"]), "valid_from" to jv(it["valid_from"]))) }),
    ))
    call.admRespond(HttpStatusCode.OK, body, (row["version"] as Number).toInt())
}

// ---- F-API-045: bulk outlet kind ----------------------------------------------------------------------------------

private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

/**
 * `POST /v1/admin/outlets/outlet-kind` (contract bulkSetOutletKind): marks outlets retail or wholesale, idempotent by
 * `batch_uuid`. The batch is recorded as one audit row (`outlet_kind_batch`, entity id = the batch uuid, holding the request
 * fingerprint and the result) next to one audit row per outlet, all in the same transaction. A replay finds that row under
 * an advisory lock and returns the stored result with `replayed: true` and changes nothing; the same uuid with another
 * outlet set or kind is 409 `ERR_SYNC_BATCH_UUID_REUSED`.
 */
private suspend fun bulkOutletKind(call: ApplicationCall, d: AdminMasterDeps) {
    val p = call.admPrincipal(MASTER_WRITERS, "outlet changes")
    val f = call.admBody(setOf("batch_uuid", "outlet_kind", "outlet_ids", "reason"))
    fun need(k: String) { if (!f.has(k)) admBad("body.$k", "required", "$k is required") }
    need("batch_uuid"); need("outlet_kind"); need("outlet_ids")
    val batch = f.obj["batch_uuid"]?.let { runCatching { UUID.fromString((it as JsonPrimitive).content) }.getOrNull() }?.takeIf { f.obj["batch_uuid"]!!.jsonPrimitive.isString }
        ?: admBad("body.batch_uuid")
    val kind = f.value(Col("outlet_kind", Kind.ENUM, values = setOf("retail", "wholesale"))) as String
    val arr = f.obj["outlet_ids"] as? JsonArray ?: admBad("body.outlet_ids", "invalid_type")
    if (arr.size !in 1..5000) admBad("body.outlet_ids", "length")
    val ids = arr.mapIndexed { i, el -> (el as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()?.takeIf { it >= 1 } ?: admBad("body.outlet_ids/$i", "out_of_range") }.toSortedSet().toList()
    val reason = f.reason(true, "reason")
    val ctx = d.ctx(call, p)
    val fingerprint = hex(MessageDigest.getInstance("SHA-256").digest("$kind|${ids.joinToString(",")}".toByteArray()))
    val result = admWrite {
        d.db.jdbi.inTransaction<JsonObject, Exception> { h ->
            h.execute("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "outlet_kind:$batch")
            val prior = h.createQuery("SELECT after::text FROM app.audit_log WHERE entity = 'outlet_kind_batch' AND entity_id = :b ORDER BY id LIMIT 1").bind("b", batch.toString()).mapTo(String::class.java).findOne().orElse(null)
            if (prior != null) {
                val o = Json.parseToJsonElement(prior) as JsonObject
                if (o.getValue("fingerprint").jsonPrimitive.content != fingerprint) throw ApiProblem(ProblemCode.ERR_SYNC_BATCH_UUID_REUSED, "this batch_uuid was used with another outlet set or kind; send the new set under a new batch_uuid")
                return@inTransaction JsonObject(mapOf("batch_uuid" to JsonPrimitive(batch.toString()), "updated" to o.getValue("updated"), "unchanged" to o.getValue("unchanged"), "replayed" to JsonPrimitive(true)))
            }
            val rows = h.createQuery("SELECT id, zone_id, outlet_kind, status FROM app.outlet WHERE id IN (<ids>) ORDER BY id FOR UPDATE").bindList("ids", ids).mapToMap().list()
            val visible = rows.filter { ctx.reach.coversZone(it["zone_id"] as Long) }
            if (visible.size != ids.size) {
                if (!ctx.reach.national) throw ApiProblem(ProblemCode.ERR_OUT_OF_SCOPE, "an outlet is outside your reach")
                val known = visible.map { (it["id"] as Number).toLong() }.toSet()
                val first = ids.indexOfFirst { it !in known }
                admBad("body.outlet_ids/$first", "unknown_outlet", "outlet ${ids[first]} does not exist")
            }
            var updated = 0; var unchanged = 0
            for (r in visible) {
                val oid = r["id"].toString()
                val old = r["outlet_kind"] as String
                if (old != kind) {
                    h.createUpdate("UPDATE app.outlet SET outlet_kind = :k, updated_by = :by WHERE id = :i").bind("k", kind).bind("by", p.userId).bind("i", r["id"]).execute()
                    updated++
                } else unchanged++
                ctx.audit(h, "outlet", oid, "set_outlet_kind", JsonObject(mapOf("outlet_kind" to JsonPrimitive(old))), JsonObject(mapOf("outlet_kind" to JsonPrimitive(kind), "batch_uuid" to JsonPrimitive(batch.toString()))), reason)
            }
            ctx.audit(h, "outlet_kind_batch", batch.toString(), "apply", null, JsonObject(mapOf(
                "fingerprint" to JsonPrimitive(fingerprint), "outlet_kind" to JsonPrimitive(kind), "outlets" to JsonPrimitive(ids.size), "updated" to JsonPrimitive(updated), "unchanged" to JsonPrimitive(unchanged),
            )), reason)
            JsonObject(mapOf("batch_uuid" to JsonPrimitive(batch.toString()), "updated" to JsonPrimitive(updated), "unchanged" to JsonPrimitive(unchanged), "replayed" to JsonPrimitive(false)))
        }
    }
    call.admRespond(HttpStatusCode.OK, result)
}

/**
 * `POST /v1/admin/outlets`, `GET/PATCH /v1/admin/outlets/{id}` and `POST /v1/admin/outlets/outlet-kind` (tag admin-outlets).
 * `GET /v1/admin/outlets` (the list) is served by `outletRoutes` of OutletsApi.kt and is not repeated here.
 */
fun Route.adminOutletsRoutes(d: AdminMasterDeps) {
    authenticated(d.guard) {
        post("/admin/outlets") { val p = call.admPrincipal(MASTER_WRITERS, "outlet changes"); adminCreate(call, d, p, OUTLET) }
        post("/admin/outlets/outlet-kind") { bulkOutletKind(call, d) }
        get("/admin/outlets/{id}") { getOutlet(call, d) }
        patch("/admin/outlets/{id}") { val p = call.admPrincipal(MASTER_WRITERS, "outlet changes"); adminPatch(call, d, p, OUTLET, call.admId()) }
    }
}

/** Everything under `/v1/admin` that this file set serves, registered in one call. */
fun Route.adminMasterRoutes(d: AdminMasterDeps) {
    adminGeographyRoutes(d); adminCalendarRoutes(d); adminUsersRoutes(d); adminRoutesRoutes(d); adminOutletsRoutes(d)
}
