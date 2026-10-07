package com.aktcl.aron.core.common

/**
 * Time source for session rules (offline-unlock age, cool-downs). The Day-2 trusted clock (docs/24 s3.8, F-SYS-049)
 * implements it with server-time anchors; until then [System] is the wall clock and elapsed realtime.
 */
interface WallClock {
    /** Milliseconds since the Unix epoch (trusted when an anchor exists). */
    fun nowMs(): Long

    /**
     * The phone's own wall clock, uncorrected. Rules about clock tampering (the offline-unlock rollback guard, AC-04) compare
     * wall time with wall time, so a trusted clock that falls back to the wall clock after a reboot cannot trip them.
     */
    fun wallClockMs(): Long = nowMs()

    /** SystemClock.elapsedRealtime(): monotonic since boot, used for cool-downs that must survive clock changes. */
    fun elapsedRealtimeMs(): Long

    /** Settings.Global.BOOT_COUNT, or 0 when unknown: tells a reboot from elapsed time that merely grew (F-SYS-052). */
    fun bootCount(): Int = 0

    object System : WallClock {
        override fun nowMs(): Long = java.lang.System.currentTimeMillis()
        override fun elapsedRealtimeMs(): Long = android.os.SystemClock.elapsedRealtime()
    }
}
