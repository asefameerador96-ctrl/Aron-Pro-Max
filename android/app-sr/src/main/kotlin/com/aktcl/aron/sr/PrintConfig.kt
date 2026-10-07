package com.aktcl.aron.sr

/** The two print settings the SR app reads from the stored config (docs/22 cfg catalogue); a missing or malformed value keeps the default. */
data class PrintConfig(val reprintMax: Int = 5, val confirmAfterPrint: Boolean = true) {
    companion object {
        /** [reprintMax] and [confirmAfterPrint] are the stored JSON texts of the two keys, or null when absent. */
        fun from(reprintMax: String?, confirmAfterPrint: String?): PrintConfig {
            val d = PrintConfig()
            return PrintConfig(
                reprintMax = reprintMax?.trim()?.toIntOrNull()?.takeIf { it in 0..20 } ?: d.reprintMax,
                confirmAfterPrint = confirmAfterPrint?.trim()?.lowercase()?.let { if (it == "true") true else if (it == "false") false else null } ?: d.confirmAfterPrint,
            )
        }
    }
}
