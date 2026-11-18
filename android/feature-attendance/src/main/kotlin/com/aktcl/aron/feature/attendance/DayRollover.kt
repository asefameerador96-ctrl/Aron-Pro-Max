package com.aktcl.aron.feature.attendance

import java.time.LocalDate
import java.time.ZoneOffset

/**
 * D-LEAD-PREREQ-04 (step 3 S2, S3): what happens to a day that is still open after Dhaka midnight, shared by the SR and the AMO.
 *
 * The phone closes the day (one on-demand location attempt, always marked as closed by the phone), and an open call of an earlier day has a LIMIT: at the first start
 * or resume at or after 04:00 Dhaka of the next day it is ended as abandoned by the phone. This object holds only the pure rule; each app's day object acts on it.
 */
object DayRollover {
    /** The hour (Dhaka) of the day after the call's own date at which an open call is ended by the phone. */
    const val ABANDON_AT_HOUR = 4
    private val dhaka: ZoneOffset = ZoneOffset.ofHours(6)

    /** True when an open call of [visitDate] has reached the limit at [nowMs]: 04:00 Dhaka of the date after the visit's. */
    fun abandonLimitReached(visitDate: String, nowMs: Long): Boolean =
        nowMs >= LocalDate.parse(visitDate).plusDays(1).atTime(ABANDON_AT_HOUR, 0).toInstant(dhaka).toEpochMilli()
}

/**
 * What the phone did for the rep after Dhaka midnight, to be told to him once (a notice with its own text, Bangla and English): the dates it closed, the
 * outlet of an earlier call it ended, and whether an unsaved sale draft was dropped with it. Empty when nothing happened.
 */
data class RolloverNotice(
    val closedDates: List<String> = emptyList(),
    val abandonedOutletId: Long? = null,
    val abandonedDate: String? = null,
    val draftDropped: Boolean = false,
    /** D-AGATE-15: an unsaved memo EDIT left open past Dhaka midnight was dropped (the saved memo is untouched). */
    val editDropped: Boolean = false,
) {
    val any: Boolean get() = closedDates.isNotEmpty() || abandonedDate != null || editDropped
}
