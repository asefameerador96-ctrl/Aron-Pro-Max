package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.contract.ProblemCode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jdbi.v3.core.Handle

/** The same name spelled with other case, spacing or punctuation is the same house (docs/34 s2: houses are de-duplicated by territory and name). */
private const val NAME_KEY = "btrim(regexp_replace(lower(h.name), '[^[:alnum:]]+', ' ', 'g'))"

private fun territoriesInReach(ctx: AdminCtx): Set<Long>? =
    if (ctx.reach.national) null else ctx.reach.zoneIds.mapNotNull { ctx.d.geo.geo().zoneTerritory[it] }.toSet()

private fun houseJson(r: Map<String, Any?>) = JsonObject(mapOf(
    "id" to jv(r["id"]), "code" to jv(r["code"]), "name" to jv(r["name"]), "status" to jv(r["status"]), "is_active" to jv(r["status"] == "active"), "zone_count" to jv(r["zone_count"]),
))

/** `GET /admin/geo/house/duplicates`: groups of two or more houses of one territory whose names are the same after case, spacing and punctuation. */
private fun duplicates(call: ApplicationCall, d: AdminMasterDeps): JsonObject {
    val p = call.admPrincipal(MASTER_READERS, "geography")
    val ctx = d.ctx(call, p)
    val vis = territoriesInReach(ctx)
    val rows = d.db.jdbi.withHandle<List<Map<String, Any?>>, Exception> { h ->
        val q = h.createQuery(
            "SELECT h.id, h.code, h.name, h.status, h.territory_id, $NAME_KEY AS name_key, (SELECT count(*) FROM app.zone z WHERE z.house_id = h.id) AS zone_count FROM app.house h " +
                "WHERE ${if (vis == null) "TRUE" else if (vis.isEmpty()) "FALSE" else "h.territory_id IN (<t>)"} " +
                "AND EXISTS (SELECT 1 FROM app.house o WHERE o.territory_id = h.territory_id AND o.id <> h.id AND ${NAME_KEY.replace("h.", "o.")} = $NAME_KEY) ORDER BY h.territory_id, name_key, h.id",
        )
        if (!vis.isNullOrEmpty()) q.bindList("t", vis.toList())
        q.mapToMap().list()
    }
    val groups = rows.groupBy { (it["territory_id"] as Number).toLong() to it["name_key"] as String }.entries.take(200).map { (k, v) ->
        JsonObject(mapOf("territory_id" to JsonPrimitive(k.first), "name_key" to JsonPrimitive(k.second), "houses" to JsonArray(v.map(::houseJson))))
    }
    return JsonObject(mapOf("groups" to JsonArray(groups)))
}

/**
 * `POST /admin/geo/house/merge` (If-Match = the kept house's version): folds duplicate houses into one. Every zone of a merged house moves to the kept house, the merged
 * houses become inactive (never deleted: history keeps pointing at them), one audit row per house. The merged houses must be in the same territory as the kept one and
 * carry the same normalised name; anything else is not a duplicate and is refused.
 */
private suspend fun merge(call: ApplicationCall, d: AdminMasterDeps) {
    val p = call.admPrincipal(MASTER_WRITERS, "geography changes")
    val ifMatch = call.admIfMatch()
    val f = call.admBody(setOf("keep_id", "merge_ids", "change_reason"))
    val keep = (f.obj["keep_id"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 1 } ?: admBad("body.keep_id", "required")
    val arr = f.obj["merge_ids"] as? JsonArray ?: admBad("body.merge_ids", "required")
    val ids = arr.map { (it as? JsonPrimitive)?.takeIf { e -> !e.isString }?.longOrNull?.takeIf { v -> v >= 1 } ?: admBad("body.merge_ids", "invalid_value") }
    if (ids.size !in 1..20) admBad("body.merge_ids", "length")
    if (ids.toSet().size != ids.size) admBad("body.merge_ids", "duplicate")
    if (keep in ids) admBad("body.merge_ids", "contains_keep_id")
    val reason = f.reason(true)
    val ctx = d.ctx(call, p)
    val vis = territoriesInReach(ctx)
    val out = admWrite {
        d.db.jdbi.inTransaction<JsonElement, Exception> { h ->
            // Lock the kept house first, then the merged ones in id order (a fixed order, so two merges cannot deadlock).
            fun load(id: Long) = h.row("SELECT h.*, $NAME_KEY AS name_key FROM app.house h WHERE h.id = :i FOR UPDATE", "i" to id)
            val kept = load(keep) ?: admNotFound()
            val merged = ids.sorted().map { load(it) ?: admNotFound() }
            val terr = (kept["territory_id"] as Number).toLong()
            if (vis != null && terr !in vis) admNotFound()
            if ((kept["version"] as Number).toInt() != ifMatch) throw AdminSupport.preconditionFailed()
            for (m in merged) {
                if ((m["territory_id"] as Number).toLong() != terr || m["name_key"] != kept["name_key"]) admBad("body.merge_ids", "not_duplicates", "house ${m["code"]} is not a duplicate of ${kept["code"]}")
            }
            val moved = ArrayList<JsonElement>()
            for (m in merged) {
                val mid = (m["id"] as Number).toLong()
                val zones = h.createQuery("SELECT id FROM app.zone WHERE house_id = :m ORDER BY id").bind("m", mid).mapTo(Long::class.java).list()
                if (zones.isNotEmpty()) h.createUpdate("UPDATE app.zone SET house_id = :k, updated_by = :by WHERE house_id = :m").bind("k", keep).bind("m", mid).bind("by", p.userId).execute()
                h.createUpdate("UPDATE app.house SET status = 'inactive', updated_by = :by WHERE id = :m").bind("m", mid).bind("by", p.userId).execute()
                ctx.audit(h, "geo_house", mid.toString(), "merge", JsonObject(mapOf("status" to jv(m["status"]), "zone_ids" to JsonArray(zones.map { JsonPrimitive(it) }))), JsonObject(mapOf("status" to JsonPrimitive("inactive"), "merged_into" to JsonPrimitive(keep))), reason)
                moved += JsonObject(mapOf("house_id" to JsonPrimitive(mid), "zones_moved" to JsonPrimitive(zones.size)))
            }
            val after = h.row("SELECT h.*, (SELECT count(*) FROM app.zone z WHERE z.house_id = h.id) AS zone_count FROM app.house h WHERE h.id = :i", "i" to keep)!!
            ctx.audit(h, "geo_house", keep.toString(), "merge_keep", JsonObject(mapOf("merged_ids" to JsonArray(ids.sorted().map { JsonPrimitive(it) }))), houseJson(after), reason)
            JsonObject(mapOf("kept" to houseJson(after), "merged" to JsonArray(moved)))
        }
    }
    d.geo.invalidate()   // zone.house_id feeds the house-scope reach
    call.admRespond(HttpStatusCode.OK, out)
}

fun Route.houseDedupRoutes(d: AdminMasterDeps) {
    authenticated(d.guard) {
        get("/admin/geo/house/duplicates") { call.admRespond(HttpStatusCode.OK, duplicates(call, d)) }
        post("/admin/geo/house/merge") { merge(call, d) }
    }
}
