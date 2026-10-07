package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.authenticated
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

internal val CODE_RE = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$")
internal val EMAIL_RE = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
private val PDA_RE = Regex("^\\+?[0-9]{5,15}$")
internal val STATUS_VALUES = setOf("active", "inactive")

private class GeoDef(val level: String, val table: String, val parentCol: String?, val parentTable: String?)

private val GEO_DEFS = listOf(
    GeoDef("wing", "app.wing", null, null),
    GeoDef("division", "app.division", "wing_id", "app.wing"),
    GeoDef("territory", "app.territory", "division_id", "app.division"),
    GeoDef("house", "app.house", "territory_id", "app.territory"),
    GeoDef("zone", "app.zone", "territory_id", "app.territory"),
).associateBy { it.level }

/** Ids of the nodes of [level] that contain a zone in the caller's reach (null = national, unrestricted). */
private fun geoReach(level: String): (com.aktcl.aron.backend.platform.Reach, Geo) -> Pred? = { reach, geo ->
    if (reach.national) null else {
        val zones = reach.zoneIds.filter { it in geo.zoneTerritory }
        val terr = zones.mapNotNull { geo.zoneTerritory[it] }.toSet()
        when (level) {
            "zone" -> idsPred("t.id", "r_ids", zones)
            "territory" -> idsPred("t.id", "r_ids", terr)
            "division" -> idsPred("t.id", "r_ids", terr.mapNotNull { geo.territoryDivision[it] }.toSet())
            "wing" -> idsPred("t.id", "r_ids", terr.mapNotNull { geo.territoryDivision[it] }.mapNotNull { geo.divisionWing[it] }.toSet())
            else -> idsPred("t.territory_id", "r_ids", terr) // house
        }
    }
}

private fun geoEntity(def: GeoDef): MasterEntity {
    val zone = def.level == "zone"
    val cols = buildList {
        add(Col("code", Kind.TEXT, required = true, onPatch = false, min = 1, max = 40, pattern = CODE_RE))
        add(Col("name", Kind.TEXT, required = true, min = 1, max = 120))
        add(Col("name_bn", Kind.TEXT, nullable = true, max = 120))
        if (def.parentCol != null) add(Col("parent_id", Kind.LONG, sqlName = def.parentCol, required = true, min = 1))
        if (zone) add(Col("dep_name", Kind.TEXT, nullable = true, max = 120))
        add(Col("email", Kind.TEXT, nullable = true, max = 120, pattern = EMAIL_RE))
        add(Col("address", Kind.TEXT, nullable = true, max = 300))
        add(Col("pda_contact_no", Kind.TEXT, nullable = true, pattern = PDA_RE, digits = true))
        add(Col("status", Kind.ENUM, onCreate = false, values = STATUS_VALUES))
    }
    return MasterEntity(
        audit = "geo_${def.level}", table = def.table, cols = cols, reach = geoReach(def.level),
        extraKeys = if (def.parentCol == null) setOf("parent_id") else emptySet(),
        parentCol = def.parentCol, searchCols = listOf("code", "name"),
        out = { r, _ ->
            JsonObject(buildMap {
                put("id", jv(r["id"])); put("level", jv(def.level)); put("code", jv(r["code"])); put("name", jv(r["name"])); put("name_bn", jv(r["name_bn"]))
                put("parent_id", jv(def.parentCol?.let { r[it] })); put("status", jv(r["status"]))
                if (zone) put("dep_name", jv(r["dep_name"]))
                put("email", jv(r["email"])); put("address", jv(r["address"])); put("pda_contact_no", jv(r["pda_contact_no"]))
                put("created_at", jv(r["created_at"])); put("updated_at", jv(r["updated_at"])); put("version", jv(r["version"]))
            })
        },
        validate = { h, ctx, merged, cur, changes ->
            if (def.parentCol != null && (cur == null || def.parentCol in changes || ("status" in changes && merged["status"] == "active"))) {
                val pid = merged[def.parentCol] as Long
                val pcol = geoReach(parentLevel(def.level))(ctx.reach, ctx.d.geo.geo())
                val ok = h.createQuery("SELECT status FROM ${def.parentTable} t WHERE t.id = :id" + (pcol?.let { " AND (${it.sql})" } ?: "")).bind("id", pid).bindPred(pcol)
                    .mapTo(String::class.java).findOne().orElse(null)
                if (ok == null) admBad("body.parent_id", "unknown_parent", "the parent does not exist or is outside your reach")
                if (ok != "active") admBad("body.parent_id", "parent_inactive", "the parent is inactive")
            }
        },
        derive = { h, _, merged, cur, changes, f ->
            if (def.parentCol == null && f != null && f.obj["parent_id"].let { it != null && it !is JsonNull }) admBad("body.parent_id", "invalid_value", "a wing has no parent")
            // A zone moved to another territory drops a distribution house of the old territory.
            if (zone && cur != null && "territory_id" in changes && cur["house_id"] != null) {
                val houseOk = h.count("SELECT count(*) FROM app.house WHERE id = :h AND territory_id = :t", "h" to cur["house_id"], "t" to merged["territory_id"]) > 0
                if (!houseOk) mapOf("house_id" to null) else emptyMap()
            } else emptyMap()
        },
        guardDeactivate = { h, _, id ->
            val busy = when (def.level) {
                "wing" -> h.count("SELECT count(*) FROM app.division WHERE wing_id = :i AND status = 'active'", "i" to id)
                "division" -> h.count("SELECT count(*) FROM app.territory WHERE division_id = :i AND status = 'active'", "i" to id)
                "territory" -> h.count("SELECT (SELECT count(*) FROM app.zone WHERE territory_id = :i AND status = 'active') + (SELECT count(*) FROM app.house WHERE territory_id = :i AND status = 'active')", "i" to id)
                "house" -> h.count("SELECT count(*) FROM app.zone WHERE house_id = :i AND status = 'active'", "i" to id)
                else -> h.count(
                    "SELECT (SELECT count(*) FROM app.cluster WHERE zone_id = :i AND status = 'active') + (SELECT count(*) FROM app.route WHERE zone_id = :i AND status = 'active') + " +
                        "(SELECT count(*) FROM app.outlet WHERE zone_id = :i AND status = 'active') + (SELECT count(*) FROM app.app_user WHERE home_zone_id = :i AND status = 'active')", "i" to id,
                )
            }
            if (busy > 0) inUse("the ${def.level} still has $busy active dependants; deactivate or move them first")
        },
    )
}

private fun parentLevel(level: String) = when (level) { "division" -> "wing"; "territory" -> "division"; else -> "territory" }

private val GEO_ENTITIES = GEO_DEFS.mapValues { geoEntity(it.value) }

private val CLUSTER = MasterEntity(
    audit = "cluster", table = "app.cluster", createReason = false,
    cols = listOf(
        Col("zone_id", Kind.LONG, required = true, min = 1),
        Col("name", Kind.TEXT, required = true, min = 1, max = 120),
        Col("cluster_type", Kind.TEXT, nullable = true, max = 60),
        Col("status", Kind.ENUM, onCreate = false, values = STATUS_VALUES),
    ),
    reach = { reach, _ -> if (reach.national) null else idsPred("t.zone_id", "r_ids", reach.zoneIds) },
    zoneCol = "zone_id",
    out = { r, _ ->
        JsonObject(mapOf(
            "id" to jv(r["id"]), "zone_id" to jv(r["zone_id"]), "name" to jv(r["name"]), "cluster_type" to jv(r["cluster_type"]), "status" to jv(r["status"]),
            "created_at" to jv(r["created_at"]), "updated_at" to jv(r["updated_at"]), "version" to jv(r["version"]),
        ))
    },
    validate = { h, ctx, merged, cur, changes ->
        if (cur == null || "zone_id" in changes || ("status" in changes && merged["status"] == "active")) {
            val z = merged["zone_id"] as Long
            if (!ctx.reach.coversZone(z)) admBad("body.zone_id", "unknown_zone", "the zone does not exist or is outside your reach")
            val st = h.createQuery("SELECT status FROM app.zone WHERE id = :z").bind("z", z).mapTo(String::class.java).findOne().orElse(null)
                ?: admBad("body.zone_id", "unknown_zone", "the zone does not exist or is outside your reach")
            if (st != "active") admBad("body.zone_id", "parent_inactive", "the zone is inactive")
            if (cur != null && "zone_id" in changes && h.count("SELECT count(*) FROM app.outlet WHERE cluster_id = :c", "c" to cur["id"]) > 0) inUse("the cluster has outlets; move them before moving the cluster to another zone")
        }
    },
    guardDeactivate = { h, _, id ->
        val n = h.count("SELECT count(*) FROM app.outlet WHERE cluster_id = :c AND status = 'active'", "c" to id)
        if (n > 0) inUse("the cluster still has $n active outlets")
    },
)

private fun io.ktor.server.application.ApplicationCall.geoEntity(): MasterEntity = GEO_ENTITIES[parameters["level"]] ?: admBad("path.level")

/** `GET/POST /v1/admin/geo/{level}`, `GET/PATCH /v1/admin/geo/{level}/{id}`, `GET/POST /v1/admin/clusters`, `PATCH /v1/admin/clusters/{id}` (tag admin-geography). */
fun Route.adminGeographyRoutes(d: AdminMasterDeps) {
    authenticated(d.guard) {
        get("/admin/geo/{level}") { val p = call.admPrincipal(MASTER_READERS, "geography"); call.admRespond(HttpStatusCode.OK, adminList(call, d, p, call.geoEntity())) }
        post("/admin/geo/{level}") { val p = call.admPrincipal(MASTER_WRITERS, "geography changes"); adminCreate(call, d, p, call.geoEntity()) }
        get("/admin/geo/{level}/{id}") { val p = call.admPrincipal(MASTER_READERS, "geography"); adminGet(call, d, p, call.geoEntity(), call.admId()) }
        patch("/admin/geo/{level}/{id}") { val p = call.admPrincipal(MASTER_WRITERS, "geography changes"); adminPatch(call, d, p, call.geoEntity(), call.admId()) }
        get("/admin/clusters") { val p = call.admPrincipal(MASTER_READERS, "clusters"); call.admRespond(HttpStatusCode.OK, adminList(call, d, p, CLUSTER)) }
        post("/admin/clusters") { val p = call.admPrincipal(MASTER_WRITERS, "cluster changes"); adminCreate(call, d, p, CLUSTER) }
        patch("/admin/clusters/{id}") { val p = call.admPrincipal(MASTER_WRITERS, "cluster changes"); adminPatch(call, d, p, CLUSTER, call.admId()) }
    }
}

