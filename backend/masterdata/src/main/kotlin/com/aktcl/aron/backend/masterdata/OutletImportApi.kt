package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.contract.ProblemCode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import java.security.MessageDigest
import java.time.Instant

private const val MAX_IMPORT_ROWS = 5000
private const val MAX_IMPORT_ERRORS = 1000
/** Longest phone text kept in app.outlet.source_contact_number (its CHECK, V0199). */
private const val SOURCE_PHONE_MAX = 60
/** Longest tier text kept in app.outlet.source_sub_channel (its CHECK, V0199). */
private const val SOURCE_SUB_MAX = 120
private val ZONE_CODE_RE = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$")
private val ROUTE_CODE_RE = Regex("^[A-Za-z0-9][A-Za-z0-9._/-]{0,39}$")
private val IMPORT_CODE_RE = Regex("^[0-9A-Za-z][0-9A-Za-z._/-]{0,31}$")
private val IMPORT_MEMBERS = setOf("code", "name", "owner_name", "zone_code", "cluster", "channel", "outlet_kind", "route_code", "lat", "lng", "contact_number", "address", "sub_channel", "geo_class", "created_at", "updated_at")

private class ImportRow(
    val index: Int,
    /** The file's code, or the stable generated one ([generated]) when the file has none. */
    val code: String?, val generated: Boolean,
    val name: String?, val owner: String?, val zone: String?, val cluster: String?, val channel: ImportRules.ChannelParse,
    val kind: String?, val route: String?,
    /** A usable pin (both values, numbers in range, rounded to 8 decimals) or null; a pin we cannot use never costs the row (a warning). */
    val lat: Double?, val lng: Double?,
    /** The repaired phone, or null. [phoneRaw] is the text as the file gave it (for the fingerprint and, when unusable, the source column). */
    val phone: String?, val phoneRaw: String?, val phoneUnusable: Boolean,
    val address: String?,
    val subChannel: String?, val geoClass: String?, val createdAt: String?, val updatedAt: String?,
    /** Warnings found while reading (phone, coordinates); the rest are found against the lookups. */
    val readWarnings: List<ImportWarning>,
    /** Column to code, found while reading (type and shape problems); reported with the lookup problems, in row order. */
    val shape: List<Pair<String, String>>,
)

private class ImportError(val row: Int, val column: String, val code: String)

/** A row that loads, with something the owner should see. Codes in [INFO_ONLY_WARNINGS] are counted only (high volume, nothing to decide). */
private class ImportWarning(val row: Int, val column: String, val code: String)

private val INFO_ONLY_WARNINGS = setOf("phone_repaired", "coordinates_rounded", "channel_derived", "astha_without_tier", "tier_source_kept", "phone_source_kept")

/**
 * Reads one row. A row that is not an object or carries an unknown member refuses the whole request (400 with the pointer `body.rows/<i>/<member>`, also on a
 * dry run: that is a malformed request). Every other problem, a bad channel included, is a row error in the report (D-MD-05); a value we can repair is
 * repaired (phones, coordinates) and a value we cannot use is a warning, never a rejected row: an unusable phone keeps its text in the source column, an unusable
 * pin is left out (the outlet loads without one).
 */
private fun readRow(i: Int, el: JsonElement): ImportRow {
    val o = el as? JsonObject ?: admBad("body.rows/$i", "invalid_type")
    o.keys.firstOrNull { it !in IMPORT_MEMBERS }?.let { admBad("body.rows/$i/$it", "unknown_member") }
    val warnings = ArrayList<ImportWarning>()
    val row = i + 1
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
    /** A number in range, or null with the reason; never a row error. */
    fun num(k: String, lo: Double, hi: Double): Pair<Double?, Boolean> {
        val v = o[k]
        if (v == null || v is JsonNull) return null to false
        val d = (v as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.takeIf { it.isFinite() && it in lo..hi }
        return d to (d == null)
    }
    val fileCode = text("code", false)?.also { if (!IMPORT_CODE_RE.matches(it)) shape += "code" to "invalid" }
    val name = text("name", true)?.also { if (it.length !in 2..120) shape += "name" to "invalid" }
    val owner = text("owner_name", true)?.also { if (it.length !in 2..120) shape += "owner_name" to "invalid" }
    val zone = text("zone_code", true)?.also { if (!ZONE_CODE_RE.matches(it)) shape += "zone_code" to "invalid" }
    val cluster = text("cluster", true)?.also { if (it.length > 120) shape += "cluster" to "invalid" }
    val kind = text("outlet_kind", true)?.also { if (it !in setOf("retail", "wholesale")) shape += "outlet_kind" to "invalid" }
    val route = text("route_code", false)?.also { if (!ROUTE_CODE_RE.matches(it)) shape += "route_code" to "invalid" }
    // B1: the portal sends the phone text as the sheet has it; only the server repairs, warns and counts.
    val phoneRaw = text("contact_number", false)
    val ph = ImportRules.phone(phoneRaw)
    if (ph.unusable) warnings += ImportWarning(row, "contact_number", "phone_unusable") else if (ph.repaired) warnings += ImportWarning(row, "contact_number", "phone_repaired")
    val address = text("address", false)?.also { if (it.length > 300) shape += "address" to "invalid" }
    val (rawLat, badLat) = num("lat", -90.0, 90.0)
    val (rawLng, badLng) = num("lng", -180.0, 180.0)
    val givenLat = o["lat"].let { it != null && it !is JsonNull }
    val givenLng = o["lng"].let { it != null && it !is JsonNull }
    var lat: Double? = null; var lng: Double? = null
    when {
        badLat || badLng -> warnings += ImportWarning(row, if (badLat) "lat" else "lng", "coordinates_invalid")
        givenLat != givenLng -> warnings += ImportWarning(row, if (givenLat) "lng" else "lat", "coordinates_incomplete")
        rawLat != null && rawLng != null -> {
            lat = ImportRules.round8(rawLat); lng = ImportRules.round8(rawLng)
            if (lat != rawLat || lng != rawLng) warnings += ImportWarning(row, "lat", "coordinates_rounded")
        }
    }
    val sub = text("sub_channel", false)
    val geo = text("geo_class", false)
    val created = text("created_at", false)
    val updated = text("updated_at", false)
    val ch = o["channel"]
    val channel = when {
        ch == null || ch is JsonNull -> ImportRules.ChannelParse.Empty
        ch is JsonPrimitive && ch.isString -> ImportRules.channel(ch.content)
        else -> { shape += "channel" to "invalid"; ImportRules.ChannelParse.Empty }
    }
    // M12 (push 1b): a row without a code gets a STABLE code from its identifying fields, so a second import finds it again.
    val generated = fileCode == null && name != null && owner != null && zone != null && cluster != null
    val code = fileCode ?: if (generated) ImportRules.generatedOutletCode(zone!!, route, cluster!!, name!!, owner!!) else null
    return ImportRow(i, code, generated, name, owner, zone, cluster, channel, kind, route, lat, lng, ph.value, phoneRaw, ph.unusable, address, sub, geo, created, updated, warnings, shape)
}

private class SubChannelRef(val id: Long, val channel: String, val active: Boolean)

private class ImportLookups(
    val zones: Map<String, Long>, val clusters: Map<Pair<Long, String>, Long>, val routes: Map<String, Pair<Long, Long>>,
    /** The stored outlets the batch names, by code, with every column the import compares. */
    val outlets: Map<String, Map<String, Any?>>,
    /** app.sub_channel by lower-case code, and by id. */
    val subChannels: Map<String, SubChannelRef>, val subById: Map<Long, SubChannelRef>, val geoClasses: List<String>,
    /** Candidate pins already recorded (outlet id, lat, lng): a re-run does not record the same candidate twice. */
    val candidates: Set<Triple<Long, Double, Double>>,
)

private const val OUTLET_COMPARE_COLS = "id, code, zone_id, status, name, owner_name, cluster_id, route_id, channel, outlet_kind, contact_number, address, sub_channel_id, " +
    "geo_class, lat, lng, location_basis, location_confirmed, source_contact_number, source_sub_channel, source_created_at, source_updated_at"

private fun importLookups(h: Handle, ctx: AdminCtx, rows: List<ImportRow>): ImportLookups {
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
    if (codes.isNotEmpty()) h.createQuery("SELECT $OUTLET_COMPARE_COLS FROM app.outlet WHERE code IN (<c>)").bindList("c", codes).mapToMap().list().forEach { outlets[it["code"] as String] = it }
    val subList = h.createQuery("SELECT id, code, channel, status FROM app.sub_channel").map { rs, _ -> rs.getString("code").lowercase() to SubChannelRef(rs.getLong("id"), rs.getString("channel"), rs.getString("status") == "active") }.list()
    val geo = h.createQuery("SELECT geo_class FROM app.geo_class_def").mapTo(String::class.java).list()
    val ids = outlets.values.map { (it["id"] as Number).toLong() }
    val candidates = if (ids.isEmpty()) emptySet() else h.createQuery("SELECT outlet_id, lat, lng FROM app.outlet_location_history WHERE basis = 'candidate' AND outlet_id IN (<o>)")
        .bindList("o", ids).map { rs, _ -> Triple(rs.getLong(1), rs.getDouble(2), rs.getDouble(3)) }.set()
    return ImportLookups(zones, clusters, routes, outlets, subList.toMap(), subList.associate { it.second.id to it.second }, geo, candidates)
}

private enum class PlanKind { CREATE, UPDATE, UNCHANGED }

/** What a pin of the row does: nothing, becomes the outlet's pin, only confirms the stored pin, or is recorded as a candidate beside a pin the field confirmed. */
private enum class PinAction { NONE, APPLY, CONFIRM, CANDIDATE }

/**
 * What a valid row will do, decided once from the lookups so that the dry run and the commit agree. [set] is the column -> value map: every column for a
 * create, only the columns that CHANGE for an update (an empty map and no pin action is UNCHANGED: no write, no version bump, no audit row).
 */
private class Plan(
    val kind: PlanKind, val outletId: Long?, val channel: String, val channelDefaulted: Boolean, val set: LinkedHashMap<String, Any?>,
    val pin: PinAction, val pinConfirmed: Boolean, val placeChanged: Boolean, val before: Map<String, Any?>?,
)

private class ImportChecked(
    val errors: List<ImportError>, val warnings: List<ImportWarning>, val creates: Int, val updates: Int, val unchanged: Int, val byChannel: Map<String, Int>,
    val plans: Map<Int, Plan>, val badRows: Set<Int>,
)

private fun sameNum(a: Any?, b: Double?): Boolean = (a as Number?)?.toDouble() == b
private fun asLong(a: Any?): Long? = (a as Number?)?.toLong()

private fun checkImport(rows: List<ImportRow>, l: ImportLookups, confirmSwitch: Boolean, now: Instant): ImportChecked {
    val errors = ArrayList<ImportError>()
    val warnings = ArrayList<ImportWarning>()
    val plans = HashMap<Int, Plan>()
    val bad = HashSet<Int>()
    var creates = 0; var updates = 0; var unchanged = 0
    val byChannel = LinkedHashMap<String, Int>().also { m -> OUTLET_CHANNELS.forEach { m[it] = 0 } }
    val seen = HashSet<String>()
    for (r in rows) {
        val row = r.index + 1
        val before = errors.size
        r.shape.forEach { errors += ImportError(row, it.first, it.second) }
        val rowWarnings = ArrayList<ImportWarning>(r.readWarnings)
        val zone = r.zone?.takeIf { ZONE_CODE_RE.matches(it) }?.let { z -> l.zones[z] ?: run { errors += ImportError(row, "zone_code", "unknown_zone"); null } }
        val cluster = if (zone != null && r.cluster != null) l.clusters[zone to r.cluster] ?: run { errors += ImportError(row, "cluster", "unknown_cluster"); null } else null
        var route: Long? = null
        if (r.route != null && ROUTE_CODE_RE.matches(r.route)) {
            val rt = l.routes[r.route]
            if (rt == null || (zone != null && rt.second != zone)) errors += ImportError(row, "route_code", "unknown_route") else route = rt.first
        }
        var cur: Map<String, Any?>? = null
        if (r.code != null && IMPORT_CODE_RE.matches(r.code)) {
            if (!seen.add(r.code)) errors += ImportError(row, "code", "duplicate")
            val o = l.outlets[r.code]
            if (o != null) {
                if (zone != null && asLong(o["zone_id"]) != zone) errors += ImportError(row, "code", "code_taken")
                else if (o["status"] == "merged") errors += ImportError(row, "code", "outlet_merged")
                else cur = o
            }
        }
        // ---- channel and tier (M05 M06 M07 M15; B3): the GT default and the derived channel apply on CREATE only; on an update a blank channel or
        // sub-channel keeps what is stored, a channel change needs a consistent tier (or none) and clears a stored tier that no longer belongs.
        val storedChannel = cur?.get("channel") as String?
        val storedSub = asLong(cur?.get("sub_channel_id"))
        val noSub = ImportRules.isNoSubChannel(r.subChannel)
        val subRef = if (noSub) null else l.subChannels[r.subChannel!!.trim().lowercase()]
        var channel: String? = null
        var defaulted = false
        when (val ch = r.channel) {
            is ImportRules.ChannelParse.Known -> channel = ch.code
            is ImportRules.ChannelParse.Unknown -> errors += ImportError(row, "channel", "unknown_channel")
            ImportRules.ChannelParse.Empty -> if (r.shape.none { it.first == "channel" }) {
                if (storedChannel != null) channel = storedChannel
                else if (subRef != null) { channel = subRef.channel; rowWarnings += ImportWarning(row, "channel", "channel_derived") }
                else { channel = "GT"; defaulted = true; rowWarnings += ImportWarning(row, "channel", "channel_defaulted") }
            }
        }
        val channelChanged = cur != null && channel != null && channel != storedChannel
        var subToStore: Long? = storedSub          // the sub_channel_id the row leaves stored
        var droppedTier: String? = null            // the file's tier text we did not store (source_sub_channel)
        if (channel != null) {
            if (!noSub && subRef == null) { rowWarnings += ImportWarning(row, "sub_channel", "unknown_sub_channel"); droppedTier = r.subChannel }
            else if (subRef != null) when {
                subRef.channel != channel ->
                    if (channelChanged) errors += ImportError(row, "sub_channel", "sub_channel_inconsistent")
                    else { rowWarnings += ImportWarning(row, "sub_channel", "sub_channel_mismatch"); droppedTier = r.subChannel }
                !subRef.active -> if (subRef.id != storedSub) { rowWarnings += ImportWarning(row, "sub_channel", "sub_channel_retired"); droppedTier = r.subChannel }
                else -> subToStore = subRef.id
            } else if (cur == null && channel == "Astha") rowWarnings += ImportWarning(row, "sub_channel", "astha_without_tier")
            if (channelChanged && subToStore != null && subToStore == storedSub && l.subById[storedSub]?.channel != channel) {
                subToStore = null
                rowWarnings += ImportWarning(row, "sub_channel", "tier_cleared")
            }
            if (cur == null && subRef == null) subToStore = null
        }
        if (droppedTier != null) rowWarnings += ImportWarning(row, "sub_channel", "tier_source_kept")
        val geo = ImportRules.geoClass(r.geoClass, l.geoClasses)
        if (r.geoClass != null && geo == null && r.geoClass != "--") rowWarnings += ImportWarning(row, "geo_class", "unknown_geo_class")
        val created = r.createdAt?.let { ImportRules.timestamp(it, now) ?: run { rowWarnings += ImportWarning(row, "created_at", "created_at_unreadable"); null } }
        val updated = r.updatedAt?.let { ImportRules.timestamp(it, now) ?: run { rowWarnings += ImportWarning(row, "updated_at", "updated_at_unreadable"); null } }
        val inBd = r.lat != null && r.lng != null && ImportRules.inBangladesh(r.lat, r.lng)
        if (r.lat != null && !inBd) rowWarnings += ImportWarning(row, "lat", "coordinates_outside_bangladesh")
        if (r.phoneUnusable) rowWarnings += ImportWarning(row, "contact_number", "phone_source_kept")
        val sourcePhone = if (r.phoneUnusable) r.phoneRaw!!.take(SOURCE_PHONE_MAX) else null
        val sourceTier = droppedTier?.take(SOURCE_SUB_MAX)
        if (errors.size != before || channel == null || zone == null || cluster == null) {
            bad += r.index
            // An unusable phone of a skipped row is still counted (nothing vanishes without a trace).
            warnings += rowWarnings.filter { it.code in setOf("phone_unusable", "unknown_sub_channel") }
            continue
        }
        val plan = if (cur == null) {
            val set = linkedMapOf<String, Any?>(
                "code" to r.code, "name" to r.name, "owner_name" to r.owner, "contact_number" to r.phone, "source_contact_number" to sourcePhone, "address" to r.address,
                "zone_id" to zone, "route_id" to route, "cluster_id" to cluster, "channel" to channel, "sub_channel_id" to subToStore, "source_sub_channel" to sourceTier,
                "geo_class" to geo, "lat" to r.lat, "lng" to r.lng, "location_basis" to if (r.lat != null) "master" else "none",
                "location_confirmed" to (confirmSwitch && inBd), "outlet_kind" to r.kind,
                "source_created_at" to created?.let { java.sql.Timestamp.from(it) }, "source_updated_at" to updated?.let { java.sql.Timestamp.from(it) },
            )
            Plan(PlanKind.CREATE, null, channel, defaulted, set, if (r.lat != null) PinAction.APPLY else PinAction.NONE, confirmSwitch && inBd, true, null)
        } else {
            val id = (cur["id"] as Number).toLong()
            val set = LinkedHashMap<String, Any?>()
            fun put(col: String, v: Any?) { if (cur[col] != v) set[col] = v }
            put("name", r.name); put("owner_name", r.owner); put("outlet_kind", r.kind); put("channel", channel)
            if (asLong(cur["cluster_id"]) != cluster) set["cluster_id"] = cluster
            if (route != null && asLong(cur["route_id"]) != route) set["route_id"] = route
            if (subToStore != storedSub) set["sub_channel_id"] = subToStore
            if (sourceTier != null) put("source_sub_channel", sourceTier) else if (subRef != null && subToStore == subRef.id) put("source_sub_channel", null)
            if (r.phone != null) { put("contact_number", r.phone); put("source_contact_number", null) }
            if (sourcePhone != null) put("source_contact_number", sourcePhone)
            if (r.address != null) put("address", r.address)
            if (geo != null) put("geo_class", geo)
            // ---- pins (M01, push 1b): a pin the field confirmed (or captured, provisional) is never overwritten or un-confirmed by a master pin unless the owner's
            // switch says the master coordinates are confirmed; otherwise the master pin is recorded as a candidate. A pin outside Bangladesh never replaces a pin.
            var pin = PinAction.NONE
            var pinConfirmed = false
            if (r.lat != null && r.lng != null) {
                val samePin = sameNum(cur["lat"], r.lat) && sameNum(cur["lng"], r.lng)
                val fieldOwned = cur["location_confirmed"] == true || cur["location_basis"] == "provisional"
                val hasPin = cur["lat"] != null
                when {
                    samePin -> if (confirmSwitch && inBd && cur["location_basis"] == "master" && cur["location_confirmed"] == false) { pin = PinAction.CONFIRM; pinConfirmed = true }
                    !inBd && hasPin -> pin = PinAction.CANDIDATE
                    inBd && fieldOwned && !confirmSwitch -> pin = PinAction.CANDIDATE
                    else -> { pin = PinAction.APPLY; pinConfirmed = confirmSwitch && inBd }
                }
                if (pin == PinAction.CANDIDATE) {
                    if (Triple(id, r.lat, r.lng) in l.candidates) pin = PinAction.NONE
                    else rowWarnings += ImportWarning(row, "lat", "pin_kept_as_candidate")
                }
            }
            // The sheet's own dates are compared like any other column: an identical row still changes nothing, and a sheet whose dates really changed
            // is stored instead of being dropped. They never touch our own created_at / updated_at, which stay the time the SERVER wrote the row.
            if (created != null && (cur["source_created_at"] as java.sql.Timestamp?)?.toInstant() != created) set["source_created_at"] = java.sql.Timestamp.from(created)
            if (updated != null && (cur["source_updated_at"] as java.sql.Timestamp?)?.toInstant() != updated) set["source_updated_at"] = java.sql.Timestamp.from(updated)
            val placeChanged = "cluster_id" in set || "route_id" in set
            Plan(if (set.isEmpty() && pin == PinAction.NONE) PlanKind.UNCHANGED else PlanKind.UPDATE, id, channel, defaulted, set, pin, pinConfirmed, placeChanged, cur)
        }
        when (plan.kind) { PlanKind.CREATE -> creates++; PlanKind.UPDATE -> updates++; PlanKind.UNCHANGED -> unchanged++ }
        byChannel.merge(channel, 1, Int::plus)
        plans[r.index] = plan
        warnings += rowWarnings
    }
    return ImportChecked(errors, warnings, creates, updates, unchanged, byChannel, plans, bad)
}

private fun importFingerprint(rows: List<ImportRow>, confirmSwitch: Boolean, skipRows: Boolean): String {
    val md = MessageDigest.getInstance("SHA-256")
    md.update("confirm=$confirmSwitch;skip=$skipRows\n".toByteArray())
    // Order-independent: the same rows in another order are the same batch.
    rows.map { listOf(it.code, it.name, it.owner, it.zone, it.cluster, it.channel.toString(), it.kind, it.route, it.lat, it.lng, it.phoneRaw, it.address, it.subChannel, it.geoClass, it.createdAt, it.updatedAt, it.readWarnings.joinToString(",") { w -> w.code }).joinToString("\u001f") { v -> v?.toString() ?: "\u0000" } }
        .sorted().forEach { md.update(it.toByteArray()); md.update(10) }
    return md.digest().joinToString("") { "%02x".format(it) }
}

private fun importErrorsJson(errors: List<ImportError>) = buildJsonArray {
    errors.take(MAX_IMPORT_ERRORS).forEach { e -> add(buildJsonObject { put("row", e.row); put("column", e.column); put("code", e.code) }) }
}

private fun listedWarnings(w: List<ImportWarning>) = w.filter { it.code !in INFO_ONLY_WARNINGS }

private fun importWarningsJson(w: List<ImportWarning>) = buildJsonArray {
    listedWarnings(w).take(MAX_IMPORT_ERRORS).forEach { e -> add(buildJsonObject { put("row", e.row); put("column", e.column); put("code", e.code) }) }
}

private fun warningCounts(w: List<ImportWarning>) = buildJsonObject {
    w.groupingBy { it.code }.eachCount().toSortedMap().forEach { (k, v) -> put(k, v) }
}

/**
 * `POST /v1/admin/outlets/import` (docs/34 s2, changed by D-OWNER-PARITY-02): bulk create-or-update of outlets from a sheet the portal read in the browser.
 * The field's data is accepted as it is: channel labels ("GT Channel"), an empty channel, phones in any field shape (the portal sends the text as it is; the
 * SERVER repairs, warns and counts), coordinates with more than 8 decimals, the Astha tier in `sub_channel`, `geo_class`, `created_at` and `updated_at`.
 * What cannot be used is a WARNING (the row loads): an unusable phone or tier is kept in the outlet's source column (`source_contact_number`,
 * `source_sub_channel`), a pin out of range, half given or outside Bangladesh is left out or left unconfirmed. A row that cannot load is a row ERROR in the
 * report. `dry_run: true` writes nothing and returns the same report the commit would; `dry_run: false` needs a `change_reason` and by default
 * (`on_row_error` = `reject_batch`) refuses the whole batch on any row error; `skip_rows` writes the good rows and lists every skipped row.
 *
 * A row whose `code` names an outlet of the same zone UPDATES it, comparing column by column: a row that changes nothing is `unchanged` (no write, no version
 * bump, no audit row). On an update a blank channel or sub-channel keeps what is stored (the GT default and the derived channel apply on create only); a
 * channel change must carry a tier of the new channel or none, and clears a stored tier of the old channel (warning `tier_cleared`). Pins: with
 * `location_confirmed: true` (the owner says the master coordinates are confirmed) a master pin inside Bangladesh is applied and confirmed; with it off a
 * master pin replaces only a pin the field has NOT confirmed or captured, else it is recorded in the location history as a `candidate`. A row without a code
 * gets the stable code [ImportRules.generatedOutletCode], so importing it again finds the same outlet. The file's Created At / Updated At go to the source
 * columns; our `created_at`/`updated_at` are the time of the write (delta readers see imported outlets).
 * Idempotent by `batch_uuid`: the batch is one audit row (`outlet_import`) holding the content fingerprint; the same content replays, other content is
 * 409 `ERR_SYNC_BATCH_UUID_REUSED`. One audit row per created or changed outlet.
 */
internal suspend fun importOutlets(call: ApplicationCall, d: AdminMasterDeps) {
    val p = call.admPrincipal(MASTER_WRITERS, "outlet import")
    val f = call.admBody(setOf("batch_uuid", "rows", "dry_run", "change_reason", "location_confirmed", "on_row_error"))
    fun need(k: String) { if (!f.has(k)) admBad("body.$k", "required", "$k is required") }
    need("batch_uuid"); need("rows"); need("dry_run")
    val batch = (f.obj["batch_uuid"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { AdminSupport.uuid(it, "body.batch_uuid") } ?: admBad("body.batch_uuid")
    fun flag(k: String, default: Boolean): Boolean {
        val v = f.obj[k] ?: return default
        return (v as? JsonPrimitive)?.takeIf { !it.isString }?.content.let { when (it) { "true" -> true; "false" -> false; else -> admBad("body.$k", "invalid_type") } }
    }
    val dry = flag("dry_run", false)
    val confirmSwitch = flag("location_confirmed", false)
    val skipRows = when (val v = (f.obj["on_row_error"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
        null -> if (f.obj["on_row_error"] != null && f.obj["on_row_error"] !is JsonNull) admBad("body.on_row_error", "invalid_type") else false
        "reject_batch" -> false
        "skip_rows" -> true
        else -> admBad("body.on_row_error", "invalid", "on_row_error is reject_batch or skip_rows (got $v)")
    }
    val arr = f.obj["rows"] as? JsonArray ?: admBad("body.rows", "invalid_type")
    if (arr.size !in 1..MAX_IMPORT_ROWS) admBad("body.rows", "length")
    val rows = arr.mapIndexed { i, el -> readRow(i, el) }
    val ctx = d.ctx(call, p)
    val now = Instant.now()
    fun putCounts(o: kotlinx.serialization.json.JsonObjectBuilder, c: ImportChecked) = with(o) {
        put("creates", c.creates); put("updates", c.updates); put("unchanged", c.unchanged)
        put("by_channel", buildJsonObject { c.byChannel.forEach { (k, v) -> put(k, v) } })
        put("errors", importErrorsJson(c.errors)); put("errors_truncated", c.errors.size > MAX_IMPORT_ERRORS)
        put("warnings", importWarningsJson(c.warnings)); put("warnings_truncated", listedWarnings(c.warnings).size > MAX_IMPORT_ERRORS)
        put("warnings_by_code", warningCounts(c.warnings))
    }
    if (dry) {
        val out = d.db.jdbi.withHandle<JsonObject, Exception> { h ->
            val c = checkImport(rows, importLookups(h, ctx, rows), confirmSwitch, now)
            buildJsonObject {
                put("batch_uuid", batch.toString()); put("rows", rows.size)
                putCounts(this, c)
                put("skipped", c.badRows.size)
            }
        }
        call.admRespond(HttpStatusCode.OK, out)
        return
    }
    val reason = f.reason(true)!!
    val fp = importFingerprint(rows, confirmSwitch, skipRows)
    val result = admWrite {
        d.db.jdbi.inTransaction<JsonObject, Exception> { h ->
            h.execute("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "outlet_import:$batch")
            val prior = h.createQuery("SELECT after::text FROM app.audit_log WHERE entity = 'outlet_import' AND entity_id = :b ORDER BY id LIMIT 1").bind("b", batch.toString()).mapTo(String::class.java).findOne().orElse(null)
            if (prior != null) {
                val o = Json.parseToJsonElement(prior) as JsonObject
                if (o.getValue("fingerprint").jsonPrimitive.content != fp) throw ApiProblem(ProblemCode.ERR_SYNC_BATCH_UUID_REUSED, "this batch_uuid was used with other rows; send the new rows under a new batch_uuid")
                return@inTransaction buildJsonObject {
                    put("batch_uuid", batch.toString()); put("rows", rows.size); put("replayed", true)
                    listOf("creates", "updates", "unchanged", "skipped").forEach { k -> o[k]?.let { put(k, it) } }
                    o["warnings_by_code"]?.let { put("warnings_by_code", it) }
                }
            }
            // Same lock order as other outlet writers: lock the existing outlets of the batch first.
            val codes = rows.mapNotNull { it.code }.distinct()
            if (codes.isNotEmpty()) h.createQuery("SELECT id FROM app.outlet WHERE code IN (<c>) ORDER BY id FOR UPDATE").bindList("c", codes).mapTo(Long::class.java).list()
            val l = importLookups(h, ctx, rows)
            val c = checkImport(rows, l, confirmSwitch, now)
            if (c.errors.isNotEmpty() && !skipRows) throw ApiProblem(ProblemCode.ERR_VALIDATION, "${c.errors.size} row errors", errors = c.errors.take(MAX_IMPORT_ERRORS).map { FieldError("body.rows/${it.row - 1}/${it.column}", it.code) })
            for (r in rows) c.plans[r.index]?.let { applyPlan(h, ctx, r, it, batch.toString(), reason) }
            val skipped = c.badRows.size
            ctx.audit(h, "outlet_import", batch.toString(), "import", null, buildJsonObject {
                put("fingerprint", fp); put("rows", rows.size); put("creates", c.creates); put("updates", c.updates); put("unchanged", c.unchanged); put("skipped", skipped)
                put("by_channel", buildJsonObject { c.byChannel.forEach { (k, v) -> put(k, v) } })
                put("warnings_by_code", warningCounts(c.warnings)); put("location_confirmed_switch", confirmSwitch)
            }, reason)
            buildJsonObject {
                put("batch_uuid", batch.toString()); put("rows", rows.size); put("replayed", false)
                putCounts(this, c)
                put("skipped", skipped)
            }
        }
    }
    call.admRespond(HttpStatusCode.OK, result)
}

private fun jsonOf(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is java.sql.Timestamp -> JsonPrimitive(v.toInstant().toString())
    else -> JsonPrimitive(v.toString())
}

/** Columns that are personal data or the field's raw text: never copied into an audit image. */
private val NOT_AUDITED = setOf("source_contact_number")

private fun applyPlan(h: Handle, ctx: AdminCtx, r: ImportRow, plan: Plan, batch: String, reason: String) {
    if (plan.kind == PlanKind.UNCHANGED) return
    val set = plan.set
    if (plan.kind == PlanKind.CREATE) {
        val cols = set.keys.toList() + listOf("created_by", "updated_by")
        val ins = h.createUpdate("INSERT INTO app.outlet (${cols.joinToString()}) VALUES (${set.keys.joinToString { ":$it" }}, :by, :by)")
        set.forEach { (k, v) -> ins.bindAny(k, v) }
        val id = ins.bind("by", ctx.p.userId).executeAndReturnGeneratedKeys("id").mapTo(Long::class.java).one()
        h.createUpdate("INSERT INTO app.outlet_placement_history (outlet_id, route_id, cluster_id, valid_from, created_by) VALUES (:o, :r, :c, :f, :by)")
            .bind("o", id).bindAny("r", set["route_id"]).bind("c", set["cluster_id"]).bind("f", ctx.today).bind("by", ctx.p.userId).execute()
        h.createUpdate("INSERT INTO app.outlet_location_history (outlet_id, lat, lng, source, basis, created_by) VALUES (:o, :la, :ln, 'migration', :basis, :by)")
            .bind("o", id).bindAny("la", r.lat).bindAny("ln", r.lng).bind("basis", if (r.lat != null) "master" else "none").bind("by", ctx.p.userId).execute()
        ctx.audit(h, "outlet", id.toString(), "import_create", null, buildJsonObject {
            set.filterKeys { it !in NOT_AUDITED }.forEach { (k, v) -> put(k, jsonOf(v)) }
            put("channel_defaulted", plan.channelDefaulted); put("generated_code", r.generated); put("batch_uuid", batch)
        }, reason)
        return
    }
    val id = plan.outletId!!
    val cur = plan.before!!
    when (plan.pin) {
        PinAction.APPLY -> { set["lat"] = r.lat; set["lng"] = r.lng; set["location_basis"] = "master"; set["provisional_lat"] = null; set["provisional_lng"] = null; set["location_confirmed"] = plan.pinConfirmed }
        PinAction.CONFIRM -> set["location_confirmed"] = true
        else -> Unit
    }
    if (set.isNotEmpty()) {
        val u = h.createUpdate("UPDATE app.outlet SET ${set.keys.joinToString { "$it = :$it" }}, updated_by = :by WHERE id = :id").bind("id", id).bind("by", ctx.p.userId)
        set.forEach { (k, v) -> u.bindAny(k, v) }
        u.execute()
    }
    if (plan.placeChanged) {
        val newRoute = if ("route_id" in set) set["route_id"] else cur["route_id"]
        val cluster = if ("cluster_id" in set) set["cluster_id"] else cur["cluster_id"]
        val open = h.createQuery("SELECT id, valid_from FROM app.outlet_placement_history WHERE outlet_id = :o AND valid_to IS NULL").bind("o", id).mapToMap().findOne().orElse(null)
        val openFrom = (open?.get("valid_from") as java.sql.Date?)?.toLocalDate()
        if (open != null && openFrom == ctx.today) {
            h.createUpdate("UPDATE app.outlet_placement_history SET route_id = :r, cluster_id = :c WHERE id = :i").bindAny("r", newRoute).bindAny("c", cluster).bind("i", open["id"]).execute()
        } else {
            if (open != null) h.createUpdate("UPDATE app.outlet_placement_history SET valid_to = :t WHERE id = :i").bind("t", ctx.today).bind("i", open["id"]).execute()
            h.createUpdate("INSERT INTO app.outlet_placement_history (outlet_id, route_id, cluster_id, valid_from, created_by) VALUES (:o, :r, :c, :f, :by)")
                .bind("o", id).bindAny("r", newRoute).bindAny("c", cluster).bind("f", ctx.today).bind("by", ctx.p.userId).execute()
        }
    }
    if (plan.pin == PinAction.APPLY || plan.pin == PinAction.CANDIDATE) {
        h.createUpdate("INSERT INTO app.outlet_location_history (outlet_id, lat, lng, source, basis, created_by) VALUES (:o, :la, :ln, 'migration', :basis, :by)")
            .bind("o", id).bind("la", r.lat!!).bind("ln", r.lng!!).bind("basis", if (plan.pin == PinAction.APPLY) "master" else "candidate").bind("by", ctx.p.userId).execute()
    }
    ctx.audit(h, "outlet", id.toString(), "import_update",
        buildJsonObject { set.keys.filter { it !in NOT_AUDITED && it in cur }.forEach { k -> put(k, jsonOf(cur[k])) } },
        buildJsonObject {
            set.filterKeys { it !in NOT_AUDITED }.forEach { (k, v) -> put(k, jsonOf(v)) }
            put("pin", plan.pin.name.lowercase()); put("batch_uuid", batch)
        }, reason)
}
