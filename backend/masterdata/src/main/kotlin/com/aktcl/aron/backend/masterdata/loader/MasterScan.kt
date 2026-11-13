package com.aktcl.aron.backend.masterdata.loader

import com.aktcl.aron.backend.masterdata.ImportRules
import com.aktcl.aron.backend.masterdata.loader.MasterCsv.Column as C

/**
 * Pass 1 of the load: read the whole file WITHOUT writing anything and decide what the file says, so that pass 2 can be a set of batched upserts that
 * never guess. Held in memory: the hierarchy (10 wings, 50 divisions, 291 territories, 292 houses, 1,049 zones, 11,325 routes), the clusters
 * (104,876) and the reps (6,954) of the October profile — a few tens of megabytes, not the 737,159 rows.
 *
 * What the scan DECIDES (and reports, never guesses silently):
 *  * a child with more than one parent (the four hierarchy exceptions of the profile): the parent on the most rows wins and the case is listed by code;
 *  * a route's visit plan: the pattern of the most of its outlets that have one; a route whose outlets disagree, or mix a pattern with blanks, is listed;
 *  * a route's rep: the rep on the most of its rows is the primary, the others are open-ended covers; ties go to the smallest id and are listed;
 *  * a cluster's type: the type on the most of its rows; a cluster whose rows disagree is listed.
 */
class MasterScan {
    /** A node of the hierarchy: its name, and how many rows name each parent. */
    class Node(val code: String) {
        var name: String = ""
        val parents = HashMap<String, Int>()
        var rows: Int = 0
        fun parent(): String? = parents.maxWithOrNull(compareBy({ it.value }, { it.key }))?.key
        fun conflicted(): Boolean = parents.size > 1
    }

    class RouteFacts(val code: String) {
        var name: String = ""
        var zone: String = ""
        /** (visit_kind, mask, label) of the rows that carry a plan, counted. */
        val plans = HashMap<Triple<String?, Int, String>, Int>()
        var blankPlanRows: Int = 0
        val reps = HashMap<String, Int>()
        var rows: Int = 0
    }

    class RepFacts(val id: String) {
        var name: String = ""
        var phone: String? = null
        val zones = HashMap<String, Int>()
        var rows: Int = 0
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
    var unreadable: Long = 0

    /**
     * Outlet codes already seen, so that a code the file carries TWICE is reported instead of silently making the second row update the first
     * (the file's codes are unique per row in the October profile; a duplicate would merge two shops). One set of 737,159 short strings.
     */
    private val outletCodes = HashSet<String>(1 shl 20)
    val duplicateOutletCodes = LinkedHashSet<String>()

    private fun node(map: LinkedHashMap<String, Node>, code: String, name: String, parent: String?): Node {
        val n = map.getOrPut(code) { Node(code) }
        if (n.name.isEmpty() && name.isNotEmpty()) n.name = name
        n.rows++
        if (!parent.isNullOrEmpty()) n.parents.merge(parent, 1, Int::plus)
        return n
    }

    /** Folds one input row into the scan. Never throws on bad data: an unreadable row is counted and skipped. */
    fun add(row: MasterCsv.Row) {
        rows++
        val wing = row[C.WING_CODE]; val division = row[C.DIVISION_CODE]; val territory = row[C.TERRITORY_CODE]
        val house = row[C.HOUSE_CODE]; val zone = row[C.ZONE_CODE]; val route = row[C.ROUTE_CODE]
        if (zone.isEmpty() || route.isEmpty()) { unreadable++; return }
        if (wing.isNotEmpty()) node(wings, wing, row[C.WING], null)
        if (division.isNotEmpty()) node(divisions, division, row[C.DIVISION], wing.ifEmpty { null })
        if (territory.isNotEmpty()) node(territories, territory, row[C.TERRITORY], division.ifEmpty { null })
        if (house.isNotEmpty()) node(houses, house, row[C.HOUSE], territory.ifEmpty { null })
        node(zones, zone, row[C.ZONE], territory.ifEmpty { null })
        if (house.isNotEmpty()) zoneHouses.getOrPut(zone) { HashMap() }.merge(house, 1, Int::plus)

        val r = routes.getOrPut(route) { RouteFacts(route) }
        r.rows++
        if (r.name.isEmpty()) r.name = row[C.ROUTE].ifEmpty { route }
        if (r.zone.isEmpty()) r.zone = zone
        val plan = ImportRules.visitPlan(row[C.VISIT_FREQUENCY], row[C.VISIT_DAY])
        if (plan.kind == "none" && plan.mask == 0 && row[C.VISIT_FREQUENCY].isEmpty() && row[C.VISIT_DAY].isEmpty()) r.blankPlanRows++
        else r.plans.merge(Triple(plan.kind, plan.mask, label(row[C.VISIT_DAY], row[C.VISIT_FREQUENCY])), 1, Int::plus)

        val rep = row[C.REP_ID].lowercase()
        if (rep.isNotEmpty()) {
            r.reps.merge(rep, 1, Int::plus)
            val f = reps.getOrPut(rep) { RepFacts(rep) }
            f.rows++
            if (f.name.isEmpty()) f.name = row[C.REP_NAME]
            if (f.phone == null) f.phone = ImportRules.phone(row[C.REP_PHONE]).value
            f.zones.merge(zone, 1, Int::plus)
        }
        val code = row[C.OUTLET_CODE]
        if (code.isNotEmpty() && !outletCodes.add(code) && duplicateOutletCodes.size < DUPLICATES_KEPT) duplicateOutletCodes += code
        val cluster = row[C.CLUSTER_NAME]
        if (cluster.isNotEmpty()) clusters.getOrPut(zone + '\u001f' + cluster) { HashMap() }.merge(row[C.CLUSTER_TYPE].ifEmpty { "Transit Hub" }, 1, Int::plus)
    }

    /** The route's display label: the field's own Visit Day text, else the frequency, else "No visit plan" (at most 60 characters, the column's CHECK). */
    private fun label(day: String, freq: String): String {
        val t = day.ifEmpty { if (freq.isEmpty()) "" else "$freq/week" }
        return if (t.isEmpty()) NO_PLAN_LABEL else t.take(60)
    }

    /** The plan of a route: the pattern of the most of its outlets that have one; `none` when none of them has one. */
    class RoutePlan(val kind: String?, val mask: Int, val label: String, val patterns: Int, val mixed: Boolean)

    fun planOf(r: RouteFacts): RoutePlan {
        val best = r.plans.maxWithOrNull(compareBy({ it.value }, { -it.key.second })) ?: return RoutePlan("none", 0, NO_PLAN_LABEL, 0, false)
        val mixed = r.blankPlanRows > 0
        return RoutePlan(best.key.first, best.key.second, best.key.third, r.plans.size, mixed)
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
        into.rowsUnreadable = unreadable
        if (unreadable > 0) into.issue("row_without_zone_or_route", times = unreadable.toInt())
        for ((name, map) in listOf("division" to divisions, "territory" to territories, "house" to houses, "zone" to zones)) {
            map.values.filter { it.conflicted() }.forEach { into.issue("${name}_with_two_parents", it.code) }
        }
        zoneHouses.filterValues { it.size > 1 }.keys.forEach { into.issue("zone_served_by_two_houses", it) }
        for (r in routes.values) {
            val p = planOf(r)
            if (p.patterns > 1) into.issue("route_with_more_than_one_visit_pattern", r.code)
            if (p.mixed && p.patterns > 0) into.issue("route_mixes_a_pattern_with_blank_rows", r.code)
            if (p.kind == "none") into.issue("route_without_a_visit_plan", r.code)
            if (p.mask != 0 && p.kind != "daily" && p.mask and WEEKEND_BIT != 0) into.issue("route_visit_pattern_includes_friday", r.code)
            if (r.reps.size > 1) into.issue("route_with_more_than_one_rep", r.code)
            if (r.reps.isEmpty()) into.issue("route_without_a_rep", r.code)
        }
        clusters.filterValues { it.size > 1 }.keys.forEach { into.issue("cluster_with_more_than_one_type", it.substringBefore('\u001f')) }
        duplicateOutletCodes.forEach { into.issue("outlet_code_twice_in_the_file", it) }
    }

    companion object {
        const val NO_PLAN_LABEL = "No visit plan"
        /** At most this many duplicate codes are kept to name in the report (the count is exact, the list is a sample). */
        const val DUPLICATES_KEPT = 1_000
        /** Bit 6 = Friday in the contract's mask (bit0 Saturday). */
        const val WEEKEND_BIT = 1 shl 6
    }
}
