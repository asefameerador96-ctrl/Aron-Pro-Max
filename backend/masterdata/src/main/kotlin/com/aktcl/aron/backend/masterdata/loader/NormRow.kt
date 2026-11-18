package com.aktcl.aron.backend.masterdata.loader

import com.aktcl.aron.backend.masterdata.ImportRules
import com.aktcl.aron.backend.masterdata.MasterRules
import java.security.MessageDigest
import java.time.Instant
import com.aktcl.aron.backend.masterdata.loader.MasterCsv.Column as C

/**
 * One input row after validation and normalisation: every value is already in the form the tables accept (the SAME rule set as the portal import and the
 * table CHECKs: [MasterRules]), so pass 2 cannot meet a CHECK violation from the file's values, and the dry run reports the rows that would have.
 * Pass 1 (the scan) and pass 2 (the load) both read rows through [NormRow.of], so they can never disagree about what a row is.
 */
internal class NormRow(
    val rowNo: Long,
    /** Wing, division, territory, house (null when the row has none), zone and route (null when the row has none) codes and their names. */
    val wing: String, val wingName: String, val division: String, val divisionName: String, val territory: String, val territoryName: String,
    val house: String?, val houseName: String, val zone: String, val zoneName: String, val route: String?, val routeName: String,
    val visitFrequency: String, val visitDay: String,
    /** The sales rep of the row: lower-case id and the repaired phone (null when empty or unusable); null when the row has none or the id is unusable. */
    val repId: String?, val repName: String, val repPhone: String?,
    /** The file's outlet code, or the stable generated one when the file has none. */
    val code: String, val codeGenerated: Boolean,
    val name: String, val owner: String, val phone: String?, val sourcePhone: String?, val address: String?,
    val cluster: String, val clusterType: String,
    val channel: String?, val subChannel: String?, val geoClass: String?,
    val lat: Double?, val lng: Double?, val inBd: Boolean,
    val created: Instant?, val updated: Instant?,
) {
    /** A hash of EVERY value pass 2 writes from this row: the batch fingerprint of a resume covers the content, not the position (finding L1). */
    fun digestInto(md: MessageDigest) {
        for (v in listOf(
            wing, wingName, division, divisionName, territory, territoryName, house, houseName, zone, zoneName, route, routeName, visitFrequency, visitDay,
            repId, repName, repPhone, code, name, owner, phone, sourcePhone, address, cluster, clusterType, channel, subChannel, geoClass,
            lat?.toString(), lng?.toString(), inBd.toString(), created?.toString(), updated?.toString(),
        )) { md.update((v ?: "\u0000").toByteArray()); md.update(31) }
        md.update(10)
    }

    /** What reading one row gave: the row, or the codes that make it an error; [notes] are things we repaired or left out, never an error. */
    class Result(val row: NormRow?, val code: String?, val errors: List<String>, val notes: List<String>)

    companion object {
        /**
         * Validates and normalises [r]. Errors (the row loads nothing): a record with the wrong number of cells; a missing or malformed code of the hierarchy
         * or of the outlet; a name or address longer than its column; no outlet name or cluster. Notes: the phone repaired or unusable, a channel we cannot
         * name, an unknown geo class, a pin that is out of range, half given or outside Bangladesh. [code] is returned even when the row is an error, so
         * pass 1 can still see a duplicate code on a row that is bad for another reason (a duplicated code is held whole, never a winner picked).
         */
        fun of(r: MasterCsv.Row, geoClasses: List<String>, now: Instant): Result {
            val errors = ArrayList<String>()
            val notes = ArrayList<String>()
            if (!r.wellFormed) return Result(null, null, listOf("csv_record_has_the_wrong_number_of_cells"), notes)

            fun hier(col: C, issue: String, required: Boolean): String? {
                val v = r[col]
                if (v.isEmpty()) { if (required) errors += "${issue}_missing"; return null }
                if (!MasterRules.HIERARCHY_CODE.matches(v)) { errors += "${issue}_invalid"; return null }
                return v
            }
            fun name(col: C, issue: String, required: Boolean = false, max: Int = MasterRules.NAME_MAX): String {
                val v = r[col]
                if (v.isEmpty() && required) errors += "${issue}_missing"
                if (v.length > max) { errors += "${issue}_too_long"; return "" }
                return v
            }
            val wing = hier(C.WING_CODE, "wing_code", true); val division = hier(C.DIVISION_CODE, "division_code", true)
            val territory = hier(C.TERRITORY_CODE, "territory_code", true); val house = hier(C.HOUSE_CODE, "house_code", false)
            val zone = hier(C.ZONE_CODE, "zone_code", true); val route = hier(C.ROUTE_CODE, "route_code", false)
            val wingName = name(C.WING, "wing_name"); val divisionName = name(C.DIVISION, "division_name"); val territoryName = name(C.TERRITORY, "territory_name")
            val houseName = name(C.HOUSE, "house_name"); val zoneName = name(C.ZONE, "zone_name"); val routeName = name(C.ROUTE, "route_name")
            val outletName = name(C.OUTLET_NAME, "outlet_name", true)
            val owner = name(C.OWNER_NAME, "owner_name")
            val cluster = name(C.CLUSTER_NAME, "cluster_name", true)
            val clusterType = name(C.CLUSTER_TYPE, "cluster_type", max = MasterRules.CLUSTER_TYPE_MAX)
            val address = name(C.ADDRESS, "address", max = MasterRules.ADDRESS_MAX).ifEmpty { null }

            // the outlet code: the file's (validated against the table's own rule) or the stable generated one
            val fileCode = r[C.OUTLET_CODE]
            var code: String? = null
            if (fileCode.isNotEmpty()) {
                if (MasterRules.OUTLET_CODE.matches(fileCode)) code = fileCode else errors += "outlet_code_invalid"
            } else if (zone != null && outletName.isNotEmpty() && cluster.isNotEmpty()) {
                code = ImportRules.generatedOutletCode(zone, route, cluster, outletName, owner)
            }
            val generated = fileCode.isEmpty() && code != null
            if (generated) notes += "outlet_row_without_a_code"

            val ph = ImportRules.phone(r[C.CONTACT_NUMBER])
            if (ph.unusable) notes += "phone_unusable" else if (ph.repaired) notes += "phone_repaired"
            val channel = when (val p = ImportRules.channel(r[C.CHANNEL])) {
                is ImportRules.ChannelParse.Known -> p.code
                is ImportRules.ChannelParse.Unknown -> { notes += "channel_not_one_of_the_six"; null }
                ImportRules.ChannelParse.Empty -> { notes += "channel_empty_in_file"; null }
            }
            val sub = r[C.SUB_CHANNEL].takeUnless { ImportRules.isNoSubChannel(it) }
            if (sub != null && sub.length > 120) errors += "sub_channel_too_long"
            val geo = ImportRules.geoClass(r[C.GEO_CLASS], geoClasses)
            if (r[C.GEO_CLASS].isNotEmpty() && geo == null && r[C.GEO_CLASS] != "--") notes += "geo_class_unknown"
            var lat = r[C.LATITUDE].toDoubleOrNull()?.takeIf { it.isFinite() }
            var lng = r[C.LONGITUDE].toDoubleOrNull()?.takeIf { it.isFinite() }
            if ((r[C.LATITUDE].isNotEmpty() && lat == null) || (r[C.LONGITUDE].isNotEmpty() && lng == null)) { notes += "coordinates_unreadable"; lat = null; lng = null }
            if ((lat == null) != (lng == null)) { notes += "coordinates_incomplete"; lat = null; lng = null }
            if (lat != null && (lat !in -90.0..90.0 || lng!! !in -180.0..180.0)) { notes += "coordinates_out_of_range"; lat = null; lng = null }
            val inBd = lat != null && ImportRules.inBangladesh(lat, lng!!)
            if (lat != null && !inBd) notes += "coordinates_outside_bangladesh"

            // the rep: an unusable id or name makes the REP an error (counted by the scan), never the outlet
            val repRaw = r[C.REP_ID].lowercase()
            var repId: String? = null
            if (repRaw.isNotEmpty()) {
                if (MasterRules.MEMO_USERNAME.matches(repRaw) && r[C.REP_NAME].length <= MasterRules.NAME_MAX && repRaw.length <= MasterRules.EMPLOYEE_CODE_MAX) repId = repRaw
                else notes += "sales_rep_id_or_name_unusable"
            }
            if (errors.isNotEmpty() || wing == null || division == null || territory == null || zone == null || code == null) {
                return Result(null, code, errors.ifEmpty { listOf("row_is_incomplete") }, notes)
            }
            return Result(
                NormRow(
                    r.no, wing, wingName, division, divisionName, territory, territoryName, house, houseName, zone, zoneName, route, routeName,
                    r[C.VISIT_FREQUENCY], r[C.VISIT_DAY], repId, r[C.REP_NAME], ImportRules.phone(r[C.REP_PHONE]).value,
                    code, generated, outletName, owner.ifEmpty { outletName }, ph.value, if (ph.unusable) r[C.CONTACT_NUMBER].take(60) else null, address,
                    cluster, clusterType.ifEmpty { "Transit Hub" }, channel, sub, geo,
                    lat?.let { ImportRules.round8(it) }, lng?.let { ImportRules.round8(it) }, inBd,
                    ImportRules.timestamp(r[C.CREATED_AT], now), ImportRules.timestamp(r[C.UPDATED_AT], now),
                ),
                code, emptyList(), notes,
            )
        }
    }
}
