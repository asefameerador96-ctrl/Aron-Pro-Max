package com.aktcl.aron.core.common

/**
 * Time source for session rules (offline-unlock age, cool-downs). The Day-2 trusted clock (docs/24 s3.8, F-SYS-049)
 * implements it with server-time anchors; until then [System] is the wall clock and elapsed realtime.
 */
interface WallClock {
    /** Milliseconds since the Unix epoch (trusted when an anchor exists). */
    fun nowMs(): Long

    /** SystemClock.elapsedRealtime(): monotonic since boot, used for cool-downs that must survive clock changes. */
    fun elapsedRealtimeMs(): Long

    object System : WallClock {
        override fun nowMs(): Long = java.lang.System.currentTimeMillis()
        override fun elapsedRealtimeMs(): Long = android.os.SystemClock.elapsedRealtime()
    }
}
