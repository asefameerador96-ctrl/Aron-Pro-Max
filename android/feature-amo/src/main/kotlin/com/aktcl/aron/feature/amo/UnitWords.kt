package com.aktcl.aron.feature.amo

import androidx.compose.runtime.Composable
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

/** The localised unit word for a server or stored unit code of a SKU of [categoryCode]; the raw code when unknown, nothing when the server sent none. */
@Composable
fun fieldUnitWord(wire: String, categoryCode: String = ""): String = UnitWords.key(wire, categoryCode)?.let { entryUnitLabel(it) } ?: wire
