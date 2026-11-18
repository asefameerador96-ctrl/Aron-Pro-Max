package com.aktcl.aron.feature.attendance

import kotlinx.coroutines.ensureActive
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

/** D-AGATE-18: like `runCatching`, but a cancellation is never swallowed (a cancelled caller stops instead of running its later steps). */
inline fun <T> catching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (c: kotlinx.coroutines.CancellationException) {
    throw c
} catch (t: Throwable) {
    Result.failure(t)
}

/**
 * D-AGATE-25: like [catching], but a CancellationException that is NOT the caller's own (Room throws one when its database is closed while a query runs) is a plain failure:
 * the caller goes on (a timer re-arms). The caller's own cancellation is still rethrown (`ensureActive`).
 */
suspend inline fun <T> catchingLive(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (c: kotlinx.coroutines.CancellationException) {
    kotlin.coroutines.coroutineContext.ensureActive()
    Result.failure(c)
} catch (t: Throwable) {
    Result.failure(t)
}

/** D-AGATE-18: the notice, built from the LOCAL "ended by the phone" markers only (never inferred from dates, so the rep's own late close never shows it). */
fun rolloverNoticeOf(marks: List<com.aktcl.aron.core.database.entity.PhoneMarkEntity>): RolloverNotice {
    val ended = marks.firstOrNull { it.kind == com.aktcl.aron.core.database.entity.PhoneMarkEntity.CALL_ENDED }
    return RolloverNotice(
        closedDates = marks.filter { it.kind == com.aktcl.aron.core.database.entity.PhoneMarkEntity.DAY_CLOSED }.map { it.businessDate }.distinct().sorted(),
        abandonedOutletId = ended?.outletId, abandonedDate = ended?.businessDate,
        draftDropped = marks.any { it.kind == com.aktcl.aron.core.database.entity.PhoneMarkEntity.DRAFT_DROPPED },
        editDropped = marks.any { it.kind == com.aktcl.aron.core.database.entity.PhoneMarkEntity.EDIT_DROPPED },
    )
}
