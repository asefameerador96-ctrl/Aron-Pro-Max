package com.aktcl.aron.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.rules.EntryUnits
import com.aktcl.aron.rules.QtyUnit

/**
 * The word that follows every quantity in the field apps (docs/34 s4.2): packet / box / dozen for what the rep counts,
 * stick / piece for stored base units. [key] is `EntryUnits.labelKey` output; one table so no screen words a unit on its own.
 */
@Composable
fun entryUnitLabel(key: String): String = stringResource(
    when (key) {
        "packet" -> R.string.core_ui_unit_packet
        "box" -> R.string.core_ui_unit_box
        "dozen" -> R.string.core_ui_unit_dozen
        "piece" -> R.string.core_ui_unit_piece
        else -> R.string.core_ui_unit_stick
    },
)

/** Label of a stored `unit_entered` wire value (`pack`, `dozen`, `stick`, `piece`) on a line of [categoryCode]. */
@Composable
fun entryUnitLabel(categoryCode: String, unitWire: String): String =
    entryUnitLabel(EntryUnits.labelKey(categoryCode, QtyUnit.entries.firstOrNull { it.wire == unitWire } ?: QtyUnit.STICK))

/** `+5` or `−7`: the base units left over after whole entry units, signed (an oversold stock figure has a negative remainder); blank when zero. */
@Composable
fun remSuffix(remBase: Long): String =
    if (remBase == 0L) "" else (if (remBase > 0) "+" else "\u2212") + localizedNumber(kotlin.math.abs(remBase))

/** `+ 5 sticks` or `− 7 sticks`: the same with its unit word, for the small text beside a whole-units figure. */
@Composable
fun remWithUnit(remBase: Long, baseUnitWord: String): String =
    if (remBase == 0L) "" else (if (remBase > 0) "+ " else "\u2212 ") + localizedNumber(kotlin.math.abs(remBase)) + " " + baseUnitWord
