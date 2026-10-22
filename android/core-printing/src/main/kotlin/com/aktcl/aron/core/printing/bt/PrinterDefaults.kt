package com.aktcl.aron.core.printing.bt

/**
 * The memo image mode a printer model starts with when it is picked (D-PRINT-02). The diagnostics screen can still
 * change it per printer; re-picking the same printer keeps that choice. Update an entry when a device check proves
 * a model needs the other mode, with the date and the evidence in [Entry.source].
 */
object PrinterDefaults {
    data class Entry(val name: Regex, val mode: ImageMode, val source: String)

    val LIST: List<Entry> = listOf(
        Entry(Regex("""(?<![A-Z0-9])MP-?58N(?![A-Z0-9])""", RegexOption.IGNORE_CASE), ImageMode.RASTER, "Thermal Crest MP-58N; raster until the owner's D-PRINT-02 export says otherwise (2026-10-22)"), // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
        Entry(Regex("""(?<![A-Z0-9])RPP-?02N?(?![A-Z0-9])""", RegexOption.IGNORE_CASE), ImageMode.RASTER, "RPP02N class 58 mm, N-019 design target"), // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
    )

    /** The mode for a printer's Bluetooth name; raster when no entry matches. */
    fun modeFor(name: String?): ImageMode = name?.let { n -> LIST.firstOrNull { it.name.containsMatchIn(n) }?.mode } ?: ImageMode.RASTER

    /** The matching entry's note, for the diagnostics report. */
    fun sourceFor(name: String?): String = name?.let { n -> LIST.firstOrNull { it.name.containsMatchIn(n) }?.source } ?: "no entry: raster" // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
}
