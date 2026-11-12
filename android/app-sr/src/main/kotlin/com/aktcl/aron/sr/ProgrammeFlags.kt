package com.aktcl.aron.sr

import com.aktcl.aron.core.common.orNullKeepingCancellation
import com.aktcl.aron.core.database.repo.ReferenceRepository

/**
 * The Home tiles of the programmes (SRM-B-61): `cfg.flag.astha_ui` and `cfg.flag.loyalty_ui` (registry default ON) show Astha and Loyalty
 * Point, `cfg.flag.superstar_ui` (registry default OFF) shows Match Program. With every key absent the SR sees 14 tiles (Match Program off).
 * A stored value is a JSON boolean text: it is read like AMO reads its flags (trimmed, quotes stripped, case ignored); anything else is
 * the registry default, never a guess.
 */
internal object ProgrammeFlags {
    const val ASTHA = "cfg.flag.astha_ui"
    const val LOYALTY = "cfg.flag.loyalty_ui"
    const val SUPERSTAR = "cfg.flag.superstar_ui"

    fun on(raw: String?, default: Boolean): Boolean = when (raw?.trim()?.trim('"')?.trim()?.lowercase()) { "true" -> true; "false" -> false; else -> default }

    /** The tile keys of [com.aktcl.aron.feature.home.HomeTile] to show, from the stored texts of the three flags (null = absent). */
    fun tiles(astha: String?, loyalty: String?, superstar: String?): Set<String> = setOfNotNull(
        "astha".takeIf { on(astha, true) }, "loyalty_point".takeIf { on(loyalty, true) }, "match_program".takeIf { on(superstar, false) },
    )

    suspend fun enabledTiles(reference: ReferenceRepository, nowIso: String): Set<String> = tiles(
        orNullKeepingCancellation { reference.config(ASTHA, nowIso) }, orNullKeepingCancellation { reference.config(LOYALTY, nowIso) },
        orNullKeepingCancellation { reference.config(SUPERSTAR, nowIso) },
    )
}
