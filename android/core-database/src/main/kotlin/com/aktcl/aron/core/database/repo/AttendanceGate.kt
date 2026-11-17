package com.aktcl.aron.core.database.repo

import com.aktcl.aron.core.database.dao.CaptureDao
import com.aktcl.aron.core.database.entity.AttendanceEventEntity

/** Why an operational write is refused: no check-in on its business date, or the day is already checked out. */
enum class AttendanceRefusal { NOT_CHECKED_IN, CHECKED_OUT }

/** Thrown by the write layer INSIDE the saving transaction; nothing is written (D-OWNER-PREREQ-01 rule 1). */
class AttendanceRefusedException(val why: AttendanceRefusal, val businessDate: String) :
    IllegalStateException("no operational write on $businessDate: $why")

/**
 * The ONE function that decides "may this operational write happen" (D-OWNER-PREREQ-01 rule 1, D-LEAD-PREREQ-02): read from the phone's
 * STORED attendance (`attendance_event`) by the record's own business date, never from an in-memory copy. A write is allowed
 * only while the date's latest attendance event is a check-in: no check-in, or a check-out after it, refuses it.
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
        val last = events.maxWithOrNull(compareBy({ it.meta.capturedAt }, { if (it.kind == "check_out") 1 else 0 })) ?: return AttendanceRefusal.NOT_CHECKED_IN
        if (events.none { it.kind == "check_in" }) return AttendanceRefusal.NOT_CHECKED_IN
        return if (last.kind == "check_out") AttendanceRefusal.CHECKED_OUT else null
    }

    suspend fun refusal(dao: CaptureDao, businessDate: String): AttendanceRefusal? = refusal(dao.attendanceOn(businessDate))
}
