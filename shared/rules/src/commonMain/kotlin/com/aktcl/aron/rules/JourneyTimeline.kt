package com.aktcl.aron.rules

/** The status of one call on the Sales Journey (contract `JourneyCall.status`; `repeat_sale` is the 1.14.0 widening). */
enum class JourneyCallStatus(val wire: String) {
    NEW_SALE("new_sale"), EDITED_SALE("edited_sale"), REPEAT_SALE("repeat_sale"), NO_SALE("no_sale"),

    /** Phone only: a call that is still open and has no memo yet; never sent to the server. */
    IN_PROGRESS("in_progress"),
}

/**
 * One call as the phone stores it (visit, visit_close, memo rows). [closedAtMs] null = still open. [sold] = the call has an ACTIVE
 * (not superseded) memo of kind `sale`: a zero-sale or QC-only memo is not a sale (contract `getTeamJourney`). [edited] = an active
 * memo of the call supersedes another, or a memo of the call was superseded by an active edit (the server's `edited_sale` test).
 * [lat]/[lng] = the outlet's base point (SRM-B-84 default: outlet base point, not the GPS fix).
 */
data class JourneyCallInput(
    val visitUuid: String, val outletId: Long, val openedAtMs: Long, val closedAtMs: Long?,
    val lat: Double?, val lng: Double?, val sold: Boolean, val edited: Boolean,
)

/** A call of the timeline with the figures the server also computes (haversine on base points, closed - opened, gap to the next open). */
data class JourneyCallRow(
    val input: JourneyCallInput, val status: JourneyCallStatus,
    val durationS: Long?, val distanceFromPrevM: Double?, val runningM: Double, val gapToNextS: Long?,
)

/**
 * The call timeline of one business date (SRM-B-82 to 85). Same figures as the server's `getTeamJourney` (AmoTeamReads.journey) where the
 * contract defines them, and the differences are declared here, not hidden:
 * FIVE declared differences from the server (listed for SV's train 2): (1) `repeat_sale`, (2) `in_progress`, (3) the PHONE caller falls back
 * to the point the visit stored when the outlet row has no base point (`RoomSaleReads.journeyCalls`; the server has no fallback), (4) the
 * phone does not filter calls by route (it holds one SR's own calls; the server filters by `route_id`), (5) this rule treats a point that
 * fails [Geo.isValidCoordinate] as missing (the server only null-checks). Everything else is equal: the call set (abandoned kept), the
 * order, sold, edited, per-leg rounding, the running total, duration and gap.
 *
 *  - order: opened_at, ties by visit uuid (the server's order);
 *  - calls: every `sr_call` visit of the day, abandoned ones included (the server keeps them; they read `no_sale`);
 *  - status: `edited_sale` when [JourneyCallInput.edited]; else `new_sale` when [JourneyCallInput.sold]; else `no_sale`. DIFFERENCES from
 *    the server today: (a) `repeat_sale` (a sold call at an outlet already sold earlier that day, the owner-question default) does not
 *    exist on the server until contract 1.14.0; (b) `in_progress` (a call still open with no sale) is phone only and never sent;
 *  - distance: each leg is the haversine between the two outlets' base points ROUNDED to two decimals, null for the first call or a
 *    missing point; the running total is `r2(total + r2(leg))`, the server's arithmetic;
 *  - duration = closed - opened in whole seconds (truncated), null while open; gap = next opened - this closed, null when unknown.
 */
object JourneyTimeline {
    /** Two decimals, half up, as the server's `r2` (Math.round(v * 100) / 100). */
    private fun r2(v: Double): Double = kotlin.math.floor(v * 100.0 + 0.5) / 100.0

    fun build(calls: List<JourneyCallInput>): List<JourneyCallRow> {
        val ordered = calls.sortedWith(compareBy<JourneyCallInput> { it.openedAtMs }.thenBy { it.visitUuid })
        val soldBefore = HashSet<Long>()
        var running = 0.0
        return ordered.mapIndexed { i, c ->
            val status = when {
                c.edited -> JourneyCallStatus.EDITED_SALE
                c.sold && c.outletId in soldBefore -> JourneyCallStatus.REPEAT_SALE
                c.sold -> JourneyCallStatus.NEW_SALE
                c.closedAtMs == null -> JourneyCallStatus.IN_PROGRESS
                else -> JourneyCallStatus.NO_SALE
            }
            if (c.sold) soldBefore += c.outletId
            val prev = ordered.getOrNull(i - 1)
            val leg = if (prev != null && prev.lat != null && prev.lng != null && c.lat != null && c.lng != null &&
                Geo.isValidCoordinate(prev.lat, prev.lng) && Geo.isValidCoordinate(c.lat, c.lng)
            ) r2(Geo.haversineM(prev.lat, prev.lng, c.lat, c.lng)) else null
            if (leg != null) running = r2(running + leg)
            val next = ordered.getOrNull(i + 1)
            JourneyCallRow(
                c, status, c.closedAtMs?.let { ((it - c.openedAtMs) / 1000L).coerceAtLeast(0L) }, leg, running,
                if (next != null && c.closedAtMs != null) ((next.openedAtMs - c.closedAtMs) / 1000L).coerceAtLeast(0L) else null,
            )
        }
    }
}

/** The words of a journey line, from the string layer of the screen. */
data class JourneyWords(val min: String, val sec: String, val metre: String, val km: String)

/** Journey text shared by the SR and AMO screens (screenshot 59 and AMO 74): same digits, same clock, same units. */
object JourneyFormat {
    /** "৫ মিনিট ২৭ সেকেন্ড" / "5 min 27 s"; seconds only under a minute; whole seconds, never negative. */
    fun duration(seconds: Long, w: JourneyWords, locale: UiLocale): String {
        val t = seconds.coerceAtLeast(0L)
        val m = t / 60L; val s = t % 60L
        val sPart = Formats.digits(s.toString(), locale) + " " + w.sec
        return if (m == 0L) sPart else Formats.digits(m.toString(), locale) + " " + w.min + " " + sPart
    }

    /** The "after" gap text: the duration; the screen appends its own "পরে" / "after" word. */
    fun gap(seconds: Long, w: JourneyWords, locale: UiLocale): String = duration(seconds, w, locale)

    /**
     * Distance with two decimals, HALF_UP: under 1,000 m in metres ("412.72 মি"), from 1,000 m in kilometres ("7.60 কি.মি").
     * The unit is chosen after rounding to metres (999.996 m prints as "1.00 কি.মি", never "1000.00 মি"), but the kilometre figure is
     * rounded ONCE from the raw metres (1004.995 m prints "1.00", not "1.01" through a rounded 1005.00).
     */
    fun distance(metres: Double, w: JourneyWords, locale: UiLocale): String {
        val m = if (metres.isFinite()) metres.coerceAtLeast(0.0) else 0.0
        val cents = kotlin.math.floor(m * 100.0 + 0.5).toLong()
        return if (cents < 100_000L) fixed2(cents, locale) + " " + w.metre else fixed2(kotlin.math.floor(m / 10.0 + 0.5).toLong(), locale) + " " + w.km
    }

    private fun fixed2(cents: Long, locale: UiLocale): String =
        Formats.digits((cents / 100L).toString() + "." + (cents % 100L).toString().padStart(2, '0'), locale)

    /** "9:53:35 AM": Dhaka time, 12-hour, Latin digits and AM/PM in both languages (screenshot 59). */
    fun clock12(epochMs: Long): String {
        val sec = (((epochMs + BusinessDate.DHAKA_OFFSET_MS) % 86_400_000L) + 86_400_000L) % 86_400_000L / 1000L
        val h24 = (sec / 3600L).toInt(); val mi = ((sec % 3600L) / 60L).toInt(); val s = (sec % 60L).toInt()
        val h12 = if (h24 % 12 == 0) 12 else h24 % 12
        return "$h12:${mi.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')} ${if (h24 < 12) "AM" else "PM"}"
    }

    /** "9:53:35 AM - 9:59:02 AM"; an open call prints only its start. */
    fun range(openedAtMs: Long, closedAtMs: Long?): String = clock12(openedAtMs) + (closedAtMs?.let { " - " + clock12(it) } ?: "")
}
