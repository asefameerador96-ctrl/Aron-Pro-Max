package com.aktcl.aron.feature.amo

import androidx.compose.runtime.Composable
import com.aktcl.aron.core.ui.entryUnitLabel

/**
 * The unit word of a unit code the SERVER sends (`report_unit`: stick | piece | dozen | box | pack; `entry_unit`: pack | dozen) or the phone stores
 * (`packet`). `pack` is the packet of the cigarette and bidi (units rule 5); the lighter's box and the match's dozen already arrive as `box` and `dozen`.
 * A code nobody here knows shows as its raw code, never as a silent "stick" (the core-ui label maps every unknown key to stick).
 */
object UnitWords {
    private val KNOWN = setOf("packet", "box", "dozen", "piece", "stick")

    /** The label key for [wire], or null for a code this phone does not know. */
    fun key(wire: String?): String? = when (val w = wire?.trim()?.lowercase()) { null, "" -> null; "pack" -> "packet"; in KNOWN -> w; else -> null }
}

/** The localised unit word for a server or stored unit code; the raw code when unknown. */
@Composable
fun fieldUnitWord(wire: String): String = UnitWords.key(wire)?.let { entryUnitLabel(it) } ?: wire
