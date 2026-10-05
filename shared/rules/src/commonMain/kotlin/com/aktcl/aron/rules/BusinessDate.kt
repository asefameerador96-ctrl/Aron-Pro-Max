package com.aktcl.aron.rules

import kotlinx.datetime.LocalDate

/** Business-date and trusted-time rules of docs/24 s3.8: Asia/Dhaka is UTC+6 with no daylight saving, so plain arithmetic is exact. */
object BusinessDate {
    /** Asia/Dhaka offset from UTC in milliseconds (UTC+6, no DST). */
    const val DHAKA_OFFSET_MS: Long = 6L * 3_600_000L
    private const val DAY_MS: Long = 86_400_000L

    /** Business date of a UTC instant (epoch ms): the Dhaka calendar date after shifting back by [cutoffMinutes] (default 00:00). */
    fun of(epochMs: Long, cutoffMinutes: Int = 0): LocalDate {
        require(cutoffMinutes in 0 until 1440) { "cutoffMinutes must be 0..1439" }
        val local = epochMs + DHAKA_OFFSET_MS - cutoffMinutes * 60_000L
        return LocalDate.fromEpochDays(local.floorDiv(DAY_MS).toInt())
    }

    /** The `yyMMdd` form used in memo numbers (docs/24 s7.5), e.g. 2026-10-05 -> "261005". */
    fun yyMMdd(date: LocalDate): String =
        (date.year % 100).toString().padStart(2, '0') + date.monthNumber.toString().padStart(2, '0') + date.dayOfMonth.toString().padStart(2, '0')
}

/** One server-time anchor: server time, phone elapsedRealtime and boot count at a successful contact (docs/24 s3.8). */
data class TimeAnchor(val bootCount: Int, val serverTimeMs: Long, val elapsedMs: Long)

/** A trusted instant; [clockOffsetMs] = trusted minus wall clock, null when no anchor covered the current boot. */
data class TrustedTime(val epochMs: Long, val clockOffsetMs: Long?)

/** Clock-skew helpers of docs/24 s3.8; the phone and the server derive instants identically. */
object TrustedClock {
    /** Derives the trusted instant at [elapsedMs] since boot from the latest anchor of [bootCount]; null when none applies. */
    fun deriveMs(anchors: List<TimeAnchor>, bootCount: Int, elapsedMs: Long): Long? {
        val a = anchors.filter { it.bootCount == bootCount && it.elapsedMs <= elapsedMs }.maxByOrNull { it.elapsedMs } ?: return null
        return checkedAdd(a.serverTimeMs, elapsedMs - a.elapsedMs)
    }

    /** Trusted now: anchor-derived when possible, else the wall clock with a null offset. */
    fun trustedNow(anchors: List<TimeAnchor>, bootCount: Int, elapsedMs: Long, wallClockMs: Long): TrustedTime {
        val t = deriveMs(anchors, bootCount, elapsedMs) ?: return TrustedTime(wallClockMs, null)
        return TrustedTime(t, t - wallClockMs)
    }

    /** Absolute skew in ms between the phone's claimed capture time and the server-derived instant. */
    fun skewMs(capturedAtMs: Long, derivedMs: Long): Long = kotlin.math.abs(capturedAtMs - derivedMs)

    /** True when the skew is strictly above [maxSkewMin] minutes (`cfg.sync.max_clock_skew_min`, 10): raises CLOCK_SKEW. */
    fun exceedsSkew(capturedAtMs: Long, derivedMs: Long, maxSkewMin: Int = 10): Boolean =
        skewMs(capturedAtMs, derivedMs) > maxSkewMin * 60_000L
}

/** Outcome of the server's business-date reconciliation for one record. */
data class BusinessDateDecision(val businessDate: LocalDate, val businessDateDevice: LocalDate?, val clockSkew: Boolean)

/** Window result of docs/24 s3.8.4. */
enum class DateWindow { OK, OUT_OF_WINDOW_PAST, FUTURE }

/** Server-side business-date decisions of docs/24 s3.8 items 3 and 4. */
object BusinessDateRules {
    /**
     * Item 3: on skew above the limit the phone's date is kept unless it differs from the server-derived date; then the
     * server's date wins and the phone's is kept in [BusinessDateDecision.businessDateDevice]. Without skew the phone's date stands.
     */
    fun reconcile(deviceDate: LocalDate, capturedAtMs: Long, derivedMs: Long, maxSkewMin: Int = 10, cutoffMinutes: Int = 0): BusinessDateDecision {
        if (!TrustedClock.exceedsSkew(capturedAtMs, derivedMs, maxSkewMin)) return BusinessDateDecision(deviceDate, null, false)
        val serverDate = BusinessDate.of(derivedMs, cutoffMinutes)
        return if (serverDate == deviceDate) BusinessDateDecision(deviceDate, null, true)
        else BusinessDateDecision(serverDate, deviceDate, true)
    }

    /** Item 4: more than [maxBackdateDays] before the server's today is past-window; a capture over [futureToleranceMin] ahead is future. */
    fun classify(
        businessDate: LocalDate,
        capturedAtMs: Long,
        serverNowMs: Long,
        maxBackdateDays: Int = 7,
        futureToleranceMin: Int = 10,
        cutoffMinutes: Int = 0,
    ): DateWindow {
        if (capturedAtMs > serverNowMs + futureToleranceMin * 60_000L) return DateWindow.FUTURE
        val today = BusinessDate.of(serverNowMs, cutoffMinutes)
        return if (today.toEpochDays() - businessDate.toEpochDays() > maxBackdateDays) DateWindow.OUT_OF_WINDOW_PAST else DateWindow.OK
    }
}
