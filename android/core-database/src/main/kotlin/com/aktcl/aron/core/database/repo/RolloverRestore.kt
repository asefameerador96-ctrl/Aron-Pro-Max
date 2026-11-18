package com.aktcl.aron.core.database.repo

import com.aktcl.aron.core.database.AronDatabase

/**
 * D-AGATE-15: what the PHONE did after Dhaka midnight and the rep has not been told yet, rebuilt from the stored records (never from memory), so a process kill between the
 * phone's close and the screen cannot lose the notice. A check-out is the phone's when [closedByPhone]; a call end is the phone's when its `ended_at` is on a later Dhaka date than
 * the call's business date (the flag the abandon always carries). "Not told yet" = captured after [seenUpToIso], which the host moves forward when the rep has seen the notice.
 */
object RolloverRestore {
    data class Found(val closedDates: List<String>, val abandonedOutletId: Long?, val abandonedDate: String?) {
        val any: Boolean get() = closedDates.isNotEmpty() || abandonedDate != null
    }

    suspend fun unseen(db: AronDatabase, dates: List<String>, seenUpToIso: String): Found {
        val closed = ArrayList<String>()
        var outlet: Long? = null
        var abandonedDate: String? = null
        for (d in dates) {
            db.captureDao().attendanceOn(d).filter { it.closedByPhone() && it.meta.capturedAt > seenUpToIso }.forEach { closed += d }
            db.captureDao().visitClosesOn(d).filter { c ->
                val end = c.endedAt
                end != null && end > seenUpToIso &&
                    runCatching { com.aktcl.aron.rules.BusinessDate.of(java.time.Instant.parse(end).toEpochMilli()).toString() > c.meta.businessDate }.getOrDefault(false)
            }.forEach { c -> if (abandonedDate == null) { abandonedDate = d; outlet = db.captureDao().visit(c.visitClientUuid)?.outletId } }
        }
        return Found(closed.distinct(), outlet, abandonedDate)
    }
}
