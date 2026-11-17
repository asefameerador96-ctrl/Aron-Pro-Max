package com.aktcl.aron.backend.masterdata.loader

import com.aktcl.aron.backend.masterdata.ImportRules
import java.security.MessageDigest
import java.time.Instant

/**
 * Pass 1 of the load: read the whole file WITHOUT writing anything and decide what the file says, so that pass 2 can be a set of batched upserts that
 * never guess. Held in memory: the hierarchy (10 wings, 50 divisions, 291 territories, 292 houses, 1,049 zones, 11,325 routes), the clusters (104,876), the
 * reps (6,954) and the set of outlet codes seen (to find duplicates: one short string per outlet, the dominant cost; see
 * `docs/reference/master-data-loader.md` for the measured figure).
 *
 * What the scan DECIDES (and reports, never guesses silently):
 *  * a child with more than one parent (the four hierarchy exceptions of the profile): the parent on the most rows wins and the case is listed by code;
 *  * a route's visit plan: the pattern of the most of its outlets that have one; a route whose outlets disagree, or mix a pattern with blanks, is listed;
 *  * a route's rep: the rep on the most of its rows is the primary, the others are open-ended covers; ties go to the smallest id and are listed;
 *  * a cluster's type: the type on the most of its rows; a cluster whose rows disagree is listed;
 *  * an outlet code that appears on more than one row (a generated code included): NO winner is picked. Every row of such a code is held as a row error
 *    in pass 2 ([duplicateCodes]), so one shop can never overwrite another (finding L2).
 * Only rows that pass [NormRow.of] fold into the hierarchy: a value a table would refuse never reaches pass 2.
 */
internal class MasterScan(private val geoClasses: List<String>, private val now: Instant) {
    /** A node of the hierarchy: its name, and how many rows name each parent. */
    class Node(val code: String) {
        var name: String = ""
        val parents = HashMap<String, Int>()
        fun parent(): String? = parents.maxWithOrNull(compareBy({ it.value }, { it.key }))?.key
        fun conflicted(): Boolean = parents.size > 1
    }

    class RouteFacts(val code: String) {
        var name: String = ""
        val zones = HashMap<String, Int>()
        /** (visit_kind, mask, label) of the rows that carry a plan, counted. */
        val plans = HashMap<Triple<String?, Int, String>, Int>()
        var blankPlanRows: Int = 0
        val reps = HashMap<String, Int>()
        fun zone(): String = zones.maxWithOrNull(compareBy({ it.value }, { it.key }))!!.key
    }

    class RepFacts(val id: String) {
        var name: String = ""
        var phone: String? = null
        val zones = HashMap<String, Int>()
    }

    val wings = LinkedHashMap<String, Node>()
    val divisions = LinkedHashMap<String, Node>()
    val territories = LinkedHashMap<String, Node>()
    val houses = LinkedHashMap<String, Node>()
    val zones = LinkedHashMap<String, Node>()
    /** Zone code to how many rows name each house code (a zone served by two houses is an exception to list). */
    val zoneHouses = HashMap<String, HashMap<String, Int>>()
    val routes = LinkedHashMap<String, RouteFacts>()
    /** zone code + \u001f + cluster name -> cluster type counts. */
    val clusters = LinkedHashMap<String, HashMap<String, Int>>()
    val reps = LinkedHashMap<String, RepFacts>()

    var rows: Long = 0
    /** Records with the wrong number of cells. */
    var malformed: Long = 0

    /** Outlet codes seen once, and the codes seen more than once: every row of the latter is held in pass 2. */
    private val seenCodes = HashSet<String>(1 shl 20)
    val duplicateCodes = HashSet<String>()

    private val fileHash = MessageDigest.getInstance("SHA-256")
    /** A hash of the whole file (header and every raw record): binds a run id to ITS file (finding L1). Valid after the last [add]. */
    val fileDigest: String get() = fileHash.clone().let { (it as MessageDigest).digest().joinToString("") { b -> "%02x".format(b) } }

    fun header(csv: MasterCsv) = csv.headerDigestInto(fileHash)

    private fun node(map: LinkedHashMap<String, Node>, code: String, name: String, parent: String?): Node {
        val n = map.getOrPut(code) { Node(code) }
        if (n.name.isEmpty() && name.isNotEmpty()) n.name = name
        if (!parent.isNullOrEmpty()) n.parents.merge(parent, 1, Int::plus)
        return n
    }

    /** Folds one input row into the scan. Never throws on bad data. */
    fun add(raw: MasterCsv.Row) {
        rows++
        raw.digestInto(fileHash)
        val res = NormRow.of(raw, geoClasses, now)
        if (!raw.wellFormed) { malformed++; return }
        res.code?.let { if (!seenCodes.add(it)) duplicateCodes += it }
        val r = res.row ?: return
        node(wings, r.wing, r.wingName, null)
        node(divisions, r.division, r.divisionName, r.wing)
        node(territories, r.territory, r.territoryName, r.division)
        if (r.house != null) { node(houses, r.house, r.houseName, r.territory); zoneHouses.getOrPut(r.zone) { HashMap() }.merge(r.house, 1, Int::plus) }
        node(zones, r.zone, r.zoneName, r.territory)
        if (r.route != null) {
            val f = routes.getOrPut(r.route) { RouteFacts(r.route) }
            if (f.name.isEmpty()) f.name = r.routeName.ifEmpty { r.route }
            f.zones.merge(r.zone, 1, Int::plus)
            val plan = ImportRules.visitPlan(r.visitFrequency, r.visitDay)
            if (r.visitFrequency.isEmpty() && r.visitDay.isEmpty()) f.blankPlanRows++
            else f.plans.merge(Triple(plan.kind, plan.mask, label(r.visitDay, r.visitFrequency)), 1, Int::plus)
            if (r.repId != null) f.reps.merge(r.repId, 1, Int::plus)
        }
        if (r.repId != null) {
            val f = reps.getOrPut(r.repId) { RepFacts(r.repId) }
            if (f.name.isEmpty()) f.name = r.repName
            if (f.phone == null) f.phone = r.repPhone
            f.zones.merge(r.zone, 1, Int::plus)
        }
        clusters.getOrPut(r.zone + '\u001f' + r.cluster) { HashMap() }.merge(r.clusterType, 1, Int::plus)
    }

    /** The route's display label: the field's own Visit Day text, else the frequency, else "No visit plan" (at most 60 characters, the column's CHECK). */
    private fun label(day: String, freq: String): String {
        val t = day.ifEmpty { if (freq.isEmpty()) "" else "$freq/week" }
        return if (t.isEmpty()) NO_PLAN_LABEL else t.take(com.aktcl.aron.backend.masterdata.MasterRules.ROUTE_LABEL_MAX)
    }

    /** The plan of a route: the pattern of the most of its outlets that have one; `none` when none of them has one. */
    class RoutePlan(val kind: String?, val mask: Int, val label: String, val patterns: Int, val mixed: Boolean)

    fun planOf(r: RouteFacts): RoutePlan {
        val best = r.plans.maxWithOrNull(compareBy({ it.value }, { -it.key.second })) ?: return RoutePlan("none", 0, NO_PLAN_LABEL, 0, false)
        return RoutePlan(best.key.first, best.key.second, best.key.third, r.plans.size, r.blankPlanRows > 0)
    }

    fun clusterType(key: String): String = clusters[key]?.maxWithOrNull(compareBy({ it.value }, { it.key }))?.key ?: "Transit Hub"

    /** The route's primary rep (most rows; a tie goes to the smallest id) and its covers. */
    fun repsOf(r: RouteFacts): Pair<String?, List<String>> {
        if (r.reps.isEmpty()) return null to emptyList()
        val primary = r.reps.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).first().key
        return primary to r.reps.keys.filter { it != primary }.sorted()
    }

    /** Everything the scan found that the owner must see, as codes and counts (never a name or a phone). */
    fun report(into: LoadReport) {
        into.rowsRead = rows
        into.rowsUnreadable = malformed
        for ((name, map) in listOf("division" to divisions, "territory" to territories, "house" to houses, "zone" to zones)) {
            map.values.filter { it.conflicted() }.forEach { into.issue("${name}_with_two_parents", it.code) }
        }
        zoneHouses.filterValues { it.size > 1 }.keys.forEach { into.issue("zone_served_by_two_houses", it) }
        for (r in routes.values) {
            val p = planOf(r)
            if (r.zones.size > 1) into.issue("route_in_two_zones", r.code)
            if (p.patterns > 1) into.issue("route_with_more_than_one_visit_pattern", r.code)
            if (p.mixed && p.patterns > 0) into.issue("route_mixes_a_pattern_with_blank_rows", r.code)
            if (p.kind == "none") into.issue("route_without_a_visit_plan", r.code)
            if (p.mask != 0 && p.kind != "daily" && p.mask and WEEKEND_BIT != 0) into.issue("route_visit_pattern_includes_friday", r.code)
            if (r.reps.size > 1) into.issue("route_with_more_than_one_rep", r.code)
            if (r.reps.isEmpty()) into.issue("route_without_a_rep", r.code)
        }
        clusters.filterValues { it.size > 1 }.keys.forEach { into.issue("cluster_with_more_than_one_type", it.substringBefore('\u001f')) }
    }

    companion object {
        const val NO_PLAN_LABEL = "No visit plan"
        /** Bit 6 = Friday in the contract's mask (bit0 Saturday). */
        const val WEEKEND_BIT = 1 shl 6
    }
}
