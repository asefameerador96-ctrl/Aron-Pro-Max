package com.aktcl.aron.backend.masterdata.loader

import kotlin.random.Random

/**
 * SYNTHETIC master data shaped like the owner's October workbook (`docs/reference/master-data-profile-2026-10.md`): the same 32 columns, the same code
 * shapes, the same proportions of blanks and the same data exceptions. **No row of the real workbook, no real name and no real phone number is here or
 * anywhere else in this repository** (the repository is public; standing rule 8): every value is generated from a seeded [Random] and names are
 * "Outlet 12345" / "Owner 12345" / "Rep ab12".
 *
 * Proportions taken from the profile and scaled: 12.6% of rows with no rep, house, status or channel; 12.0% with no visit plan; 1.0% with no
 * coordinates; 19.6% with no geo class; phones: 37% national form, 55% without the leading zero, 0.05% with 880, 7.6% unusable; the exceptions
 * (a zone under two territories, a zone served by two houses, a route with two reps, a route whose rows disagree on the pattern, a Friday pattern,
 * an Astha outlet with a GT tier, an Astha outlet with "--", a row with no outlet code, a 3-character rep id).
 */
object SyntheticMaster {
    val HEADER = listOf(
        "Wing Code", "Wing", "Division Code", "Division", "Territory Code", "Territory", "House Code", "House", "Zone Code", "Zone",
        "Route Code", "Route", "Visit Frequency", "Visit Day", "Sales_Rep_Id", "Sales_Rep_Name", "Phone_No", "Outlet Code", "Outlet Name",
        "Owner Name", "Contact Number", "Address", "Cluster Name", "Cluster Type", "Status", "Latitude", "Longitude", "Geo Classification",
        "Channel", "Sub-Channel", "Created At", "Updated At",
    )

    /** What the generator put in the file, so a test can assert against it without re-deriving it. */
    class Facts {
        var rows: Int = 0
        var outletsWithoutRep: Int = 0
        var routesWithoutPlan: Int = 0
        var codelessRows: Int = 0
        var shortRepIds: MutableSet<String> = linkedSetOf()
        var zonesWithTwoTerritories: MutableSet<String> = linkedSetOf()
        var routesWithTwoReps: MutableSet<String> = linkedSetOf()
        var routesWithMixedPatterns: MutableSet<String> = linkedSetOf()
        var fridayRoutes: MutableSet<String> = linkedSetOf()
        var unusablePhones: Int = 0
        var outsideBangladesh: Int = 0
        var noCoordinates: Int = 0
    }

    private val PATTERNS = listOf(
        "6" to "Daily", "3" to "(Sat, Mon, Wed)", "3" to "(Sun, Tue, Thu)", "2" to "Sat, Tue", "2" to "Sun, Wed", "2" to "Mon, Thu",
    )

    /**
     * Writes [outlets] rows over a hierarchy of [wings] wings. The shape is the profile's: each wing has 5 divisions, each division 6 territories,
     * each territory 4 zones and 2 houses, each zone 11 routes, each route 50 to 99 outlets (capped by [outlets]).
     */
    fun csv(outlets: Int, wings: Int = 2, seed: Long = 42, facts: Facts = Facts()): Pair<String, Facts> {
        val rnd = Random(seed)
        val sb = StringBuilder(outlets * 200)
        sb.append(HEADER.joinToString(",")).append('\n')
        var written = 0
        var routeNo = 0
        var repNo = 0
        var outletNo = 0
        outer@ for (w in 1..wings) {
            for (d in 1..5) {
                for (t in 1..6) {
                    for (z in 1..4) {
                        val zone = "%04d".format(w * 1000 + d * 100 + t * 10 + z)
                        // one zone of the file sits under two territories, and one is served by two houses (the profile's exceptions)
                        val secondTerritory = w == 1 && d == 1 && t == 1 && z == 1
                        if (secondTerritory) facts.zonesWithTwoTerritories += zone
                        for (r in 1..11) {
                            routeNo++
                            val route = "%05d".format(routeNo)
                            val noPlan = routeNo % 8 == 0
                            val mixed = routeNo % 37 == 0
                            val friday = routeNo % 101 == 0
                            val twoReps = routeNo % 53 == 0
                            if (noPlan) facts.routesWithoutPlan++
                            if (mixed) facts.routesWithMixedPatterns += route
                            if (friday) facts.fridayRoutes += route
                            if (twoReps) facts.routesWithTwoReps += route
                            val noRep = routeNo % 9 == 0
                            repNo++
                            val rep = if (routeNo % 13 == 0) "ab${repNo % 10}".also { facts.shortRepIds += it } else "ab%04d".format(repNo)
                            val pattern = PATTERNS[routeNo % PATTERNS.size]
                            val n = 50 + rnd.nextInt(50)
                            for (o in 1..n) {
                                if (written >= outlets) break@outer
                                outletNo++
                                written++
                                val code = outletCode(outletNo, rnd, facts)
                                val house = "%03d".format((t * 2) + (if (z % 2 == 0) 1 else 0))
                                val territory = if (secondTerritory && o % 2 == 0) "%03d".format(w * 100 + d * 10 + 6) else "%03d".format(w * 100 + d * 10 + t)
                                val thisRep = if (twoReps && o % 3 == 0) "ab%04d".format(repNo + 5000) else rep
                                val plan = when {
                                    noPlan -> "" to ""
                                    mixed && o % 4 == 0 -> "" to ""
                                    friday -> "3" to "(Mon, Thu, Fri)"
                                    else -> pattern
                                }
                                val (lat, lng) = coordinates(o, outletNo, rnd, facts)
                                val (channel, sub) = channel(outletNo)
                                sb.append(
                                    listOf(
                                        "$w", "Wing $w", "%02d".format(w * 10 + d), "Division $d", territory, "Territory $t",
                                        if (noRep) "" else house, if (noRep) "" else "House $house", zone, "Zone $z",
                                        route, "Route $route", plan.first, quote(plan.second),
                                        if (noRep) "" else thisRep, if (noRep) "" else "Rep $thisRep", if (noRep) "" else phone(outletNo, facts),
                                        code, "Outlet $outletNo", "Owner $outletNo", phone(outletNo + 7, facts), "",
                                        "Cluster ${zone}-${(o % 4) + 1}", if (outletNo % 997 == 0) "Residential" else "Transit Hub",
                                        if (noRep) "" else "Active", lat, lng,
                                        geoClass(outletNo), if (noRep) "" else channel, sub,
                                        "2024-03-05 10:15:00", if (outletNo % 3 == 0) "2025-01-02" else "",
                                    ).joinToString(","),
                                ).append('\n')
                                if (noRep) facts.outletsWithoutRep++
                            }
                        }
                    }
                }
            }
        }
        facts.rows = written
        return sb.toString() to facts
    }

    /** The six code shapes of the profile, in their proportions; one row in 500 has no code at all. */
    private fun outletCode(n: Int, rnd: Random, facts: Facts): String = when {
        n % 500 == 0 -> { facts.codelessRows++; "" }
        n % 100 < 80 -> "${100000 + n}"
        n % 100 < 97 -> "${1000 + n % 900}A${n}"
        n % 100 < 98 -> "${1000 + n % 900}AB$n"
        n % 100 < 99 -> "AB-${100000 + n}"
        else -> "%06dC%02d".format(n, n % 100)
    }

    private fun coordinates(o: Int, n: Int, rnd: Random, facts: Facts): Pair<String, String> = when {
        n % 100 == 0 -> { facts.noCoordinates++; "" to "" }
        n % 701 == 0 -> { facts.outsideBangladesh++; "19.500000" to "85.200000" }
        else -> "%.8f".format(23.5 + rnd.nextDouble() * 2) to "%.8f".format(89.0 + rnd.nextDouble() * 2)
    }

    private fun geoClass(n: Int): String = when (n % 100) {
        in 0..41 -> "Rural"
        in 42..63 -> "Urban"
        in 64..77 -> "Semi Urban"
        in 78..79 -> "Hill"
        else -> ""
    }

    /** The profile's channel and sub-channel mix, including the mismatched pairs and the Astha rows with "--". */
    private fun channel(n: Int): Pair<String, String> = when (n % 1000) {
        in 0..729 -> "GT Channel" to "GT"
        in 730..803 -> "Astha Channel" to "Gold"
        in 804..825 -> "RCC" to "RCC"
        in 826..841 -> "DCC Channel" to "DCC"
        in 842..856 -> "Astha Channel" to "Diamond"
        in 857..867 -> "Astha Channel" to "Platinum"
        in 868..869 -> "MT Channel" to "MT"
        870 -> "HoReCa" to "HoReCa"
        871 -> "Astha Channel" to "Titanium"
        872 -> "Astha Channel" to "--"
        873 -> "Astha Channel" to "GT"
        874 -> "GT Channel" to "Gold"
        875 -> "GT Channel" to "Silver"
        else -> "GT Channel" to "GT"
    }

    /** The profile's phone shapes: national, without the leading zero, with 880, and unusable. */
    private fun phone(n: Int, facts: Facts): String = when (n % 100) {
        in 0..36 -> "01%d%08d".format(3 + n % 7, n % 100000000)
        in 37..91 -> "1%d%08d".format(3 + n % 7, n % 100000000)
        92 -> "8801%d%08d".format(3 + n % 7, n % 100000000)
        in 93..99 -> { facts.unusablePhones++; if (n % 2 == 0) "123" else "N/A" }
        else -> ""
    }

    private fun quote(s: String): String = if (s.contains(',')) "\"$s\"" else s
}
