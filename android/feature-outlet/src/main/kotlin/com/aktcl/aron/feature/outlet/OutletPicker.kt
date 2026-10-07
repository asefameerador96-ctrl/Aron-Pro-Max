package com.aktcl.aron.feature.outlet

import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.rules.Geo
import com.aktcl.aron.rules.TextRules

/** One row of the Sale picker (F-SR-016). [label] is `name (code-phone-cluster)`; [distanceM] only when a fix exists (F-SR-074). */
data class PickerRow(val outlet: OutletEntity, val label: String, val chip: String, val distanceM: Double?)

/**
 * The Sale picker's list logic. Pure functions over the bundle's outlets, so it is the same offline, in airplane mode,
 * on a kill and relaunch, and in both languages (docs/ui-reference/sr/sale-select.md; UI-SR-23, UI-SR-24, UI-SR-25).
 */
object OutletPicker {
    const val ALL_CHIP = ""

    /** Closed, merged and archived outlets are hidden; the planned order of the route (visit_sequence, then name key) is kept. */
    fun visible(outlets: List<OutletEntity>): List<OutletEntity> =
        outlets.filter { it.status == "active" }
            .sortedWith(
                compareBy<OutletEntity, Int?>(nullsLast()) { it.visitSequence }
                    .thenComparator { a, b -> TextRules.compareKeys(a.nameSortKey, b.nameSortKey) },
            )

    /** `name (code-phone-cluster)` with the phone shown as 11 digits `01XXXXXXXXX`; a missing or unusable phone is left out. */
    fun label(o: OutletEntity): String {
        val phone = o.contactNumber?.let { TextRules.normalisePhone(it)?.value }
        val parts = listOfNotNull(o.code, phone, o.clusterName)
        return "${o.name} (${parts.joinToString("-")})"
    }

    /**
     * The filter chip of an outlet: the first letter of its name, case-insensitive (UI-SR-23 treats `S` and `s` as one
     * chip); Bangla names use the first Bangla letter. Names that start with a digit or symbol share the `#` chip.
     */
    fun chipOf(name: String): String {
        val key = TextRules.nameSortKey(name)
        val c = key.firstOrNull() ?: return "#"
        return when {
            !c.isLetter() -> "#"
            c.code < 0x80 -> c.uppercaseChar().toString()
            else -> c.toString()
        }
    }

    /** The chips present in [rows], Latin first (A to Z) then Bangla in Unicode order, `#` last; the caller prepends All. */
    fun chips(rows: List<PickerRow>): List<String> =
        rows.map { it.chip }.distinct().sortedWith { a, b -> compareKeys(a, b) }

    private fun rank(c: String): Int = when {
        c == "#" -> 2
        c[0].code < 0x80 -> 0
        else -> 1
    }

    private fun compareKeys(a: String, b: String): Int {
        val ra = rank(a)
        val rb = rank(b)
        return if (ra != rb) ra - rb else TextRules.compareKeys(a, b)
    }

    /**
     * Rows for the picker. [chip] is [ALL_CHIP] or one of [chips]. With [sortByDistance] on (`cfg.sale.sort_by_distance`,
     * default off) and a fix, the nearest outlet comes first; outlets without coordinates go last in their planned order.
     */
    fun rows(
        outlets: List<OutletEntity>,
        chip: String = ALL_CHIP,
        sortByDistance: Boolean = false,
        fixLat: Double? = null,
        fixLng: Double? = null,
    ): List<PickerRow> {
        val useDistance = fixLat != null && fixLng != null && Geo.isValidCoordinate(fixLat, fixLng)
        val all = visible(outlets).map { o ->
            val d = if (useDistance && o.lat != null && o.lng != null) Geo.haversineM(fixLat!!, fixLng!!, o.lat!!, o.lng!!) else null
            PickerRow(o, label(o), chipOf(o.name), d)
        }
        val filtered = if (chip == ALL_CHIP) all else all.filter { it.chip == chip }
        return if (sortByDistance && useDistance) filtered.sortedWith(compareBy(nullsLast()) { it.distanceM }) else filtered
    }
}
