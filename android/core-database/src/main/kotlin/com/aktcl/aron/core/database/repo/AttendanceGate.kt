package com.aktcl.aron.core.database.repo

import com.aktcl.aron.core.database.dao.CaptureDao
import com.aktcl.aron.core.database.entity.AttendanceEventEntity

/** Why an operational write is refused: no check-in on its business date, or the day is already checked out. */
enum class AttendanceRefusal {
    NOT_CHECKED_IN, CHECKED_OUT,

    /**
     * D-LEAD-PREREQ-04 ruling 4: a UI MIRROR value only, never returned by the write layer. After Dhaka midnight an earlier day's call is still open: only that
     * call's screens work and every other operational screen says "finish or close yesterday's call first" (never a generic failure).
     */
    EARLIER_CALL_OPEN,
}

/**
 * D-LEAD-PREREQ-04 ruling 2: true when this check-out was made by the PHONE for a forgotten day (the rep's own check-out is made on the day of his check-in; after
 * Dhaka midnight there is none). It is derived from the stored event, so it cannot be lost or forged by a flag: the capture time is on a later Dhaka date
 * than the business date. The field `closed_by` is NOT on the wire yet (the server lane lands it first); this is what it will be built from.
 */
fun AttendanceEventEntity.closedByPhone(): Boolean = kind == "check_out" &&
    runCatching { com.aktcl.aron.rules.BusinessDate.of(java.time.Instant.parse(meta.capturedAt).toEpochMilli()).toString() > meta.businessDate }.getOrDefault(false)

/** Thrown by the write layer INSIDE the saving transaction; nothing is written (D-OWNER-PREREQ-01 rule 1). */
class AttendanceRefusedException(val why: AttendanceRefusal, val businessDate: String) :
    IllegalStateException("no operational write on $businessDate: $why")

/**
 * The ONE function that decides "may this operational write happen" (D-OWNER-PREREQ-01 rule 1, D-LEAD-PREREQ-02): read from the phone's
 * STORED attendance (`attendance_event`) by the record's own business date, never from an in-memory copy. A write is allowed
 * only while the date has a check-in and NO check-out stored (any check-out, whatever the clock order, ruling 7 of D-LEAD-PREREQ-03).
 *
 * It is enforced in [CaptureRepository] (the write layer), so no screen, deep link, notification, restored open visit or
 * background path can go round it, and mirrored in the UI (tiles show a lock and lead to check-in). Hard: no config softens it.
 * The server never refuses (an offline sale may upload before its check-in); it flags.
 *
 * Exempt, on purpose, and tested one by one: the check-in and check-out themselves, the close of a call already open
 * (`visit_close`), Sales Submit (`day_submit`), breadcrumbs, device status, media meta, print events and uploads.
 */
object AttendanceGate {
    /** Pure: [events] are the date's attendance rows in capture order. Null means the write may happen. */
    fun refusal(events: List<AttendanceEventEntity>): AttendanceRefusal? {
        if (events.none { it.kind == "check_in" }) return AttendanceRefusal.NOT_CHECKED_IN
        // D-LEAD-PREREQ-03 ruling 7: once ANY check-out is stored for the date the date is checked out, whatever the clock order of the rows
        // (a check-out captured on an earlier clock than the check-in, after a clock fallback, must not reopen the day)
        return if (events.any { it.kind == "check_out" }) AttendanceRefusal.CHECKED_OUT else null
    }

    suspend fun refusal(dao: CaptureDao, businessDate: String): AttendanceRefusal? = refusal(dao.attendanceOn(businessDate))
}
