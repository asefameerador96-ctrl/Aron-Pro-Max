package com.aktcl.aron.core.common

import android.os.StrictMode

/**
 * Debug builds only (AUD-PERF-05): network on the main thread kills the app; disk reads and writes on the main thread
 * are logged (tag `StrictMode`) so the device check D-PERF-05 lists them. Disk death is switched on once that list is
 * empty: before the first device run it would only stop the debug build at launch. Release builds never call this.
 */
object DebugStrictMode {
    fun install(debug: Boolean) {
        if (!debug) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectNetwork()
                .detectDiskReads()
                .detectDiskWrites()
                .penaltyLog()
                .penaltyDeathOnNetwork()
                .build(),
        )
    }
}
