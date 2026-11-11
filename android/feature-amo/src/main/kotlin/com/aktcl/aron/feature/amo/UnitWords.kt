package com.aktcl.aron.feature.amo

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.entryUnitLabel

/**
 * The unit word of a unit code the SERVER sends (`report_unit`: stick | piece | dozen | box | pack; `entry_unit`: pack | dozen) or the phone stores
 * (`packet`). The server's `pack` is the category's pack (units rule 5): a packet for cigarettes and bidi, a box of 25 for lighters, a dozen for
 * matches (`EntryUnits.labelKey(category, PACK)`); the category comes from the SKU the line is for.
 * A code nobody here knows shows as its raw code, never as a silent "stick" (the core-ui label maps every unknown key to stick).
 */
object UnitWords {
    private val KNOWN = setOf("packet", "box", "dozen", "piece", "stick")

    /** The label key for [wire] of a SKU of [categoryCode] (blank = unknown: a packet), or null for a code this phone does not know. */
    fun key(wire: String?, categoryCode: String = ""): String? = when (val w = wire?.trim()?.lowercase()) {
        null, "" -> null
        "pack" -> com.aktcl.aron.rules.EntryUnits.labelKey(categoryCode, com.aktcl.aron.rules.QtyUnit.PACK)
        in KNOWN -> w
        else -> null
    }
}

/**
 * The localised unit word for a server or stored unit code of a SKU of [categoryCode]; the raw code when unknown, nothing when the server sent none.
 * [quantityText] (the exact quantity as text) picks the English singular when the exact value is 1 ("1 packet", "2 packets", "62.5 packets"); the dozen has no plural.
 */
@Composable
fun fieldUnitWord(wire: String, categoryCode: String = "", quantityText: String? = null): String {
    val key = UnitWords.key(wire, categoryCode) ?: return wire
    // the exact Decimal3 text decides (1 = singular); plain strings, not plurals: the quantity is a decimal, not an int count
    val one = quantityText?.trim()?.let { runCatching { java.math.BigDecimal(it).compareTo(java.math.BigDecimal.ONE) == 0 }.getOrDefault(false) } == true
    return when (key) {
        "packet" -> stringResource(if (one) R.string.amo_field_unit_packet_one else R.string.amo_field_unit_packet)
        "box" -> stringResource(if (one) R.string.amo_field_unit_box_one else R.string.amo_field_unit_box)
        "piece" -> stringResource(if (one) R.string.amo_field_unit_piece_one else R.string.amo_field_unit_piece)
        "stick" -> stringResource(if (one) R.string.amo_field_unit_stick_one else R.string.amo_field_unit_stick)
        else -> entryUnitLabel(key) // dozen: the same word for one and many
    }
}
