package com.aktcl.aron.core.database.repo

import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.SyncMetaEntity

/**
 * Sales Submit closes a route-day (docs/24 s4.2 rule 2: `day_submit` is its last record); the server may reopen it (a
 * submit void), recorded with [reopen]. Shared by captures and the print ledger.
 */
internal object RouteDayLock {
    suspend fun isOpen(db: AronDatabase, meta: CaptureMeta): Boolean {
        if (!routeOpen(db, meta.businessDate, meta.routeId)) return false
        // An AMO's supervisor-day submit (no route) closes the whole day: records that carry a route are refused too.
        return meta.routeId == null || supervisorOpen(db, meta.businessDate)
    }

    private suspend fun routeOpen(db: AronDatabase, businessDate: String, routeId: Long?): Boolean {
        val latest = db.captureDao().daySubmitsFor(businessDate, routeId).maxOfOrNull { it.submitCycle } ?: return true
        val reopened = db.referenceDao().meta(key(businessDate, routeId))?.toIntOrNull() ?: 0
        return latest <= reopened
    }

    private suspend fun supervisorOpen(db: AronDatabase, businessDate: String): Boolean {
        val latest = db.captureDao().daySubmitsFor(businessDate, null).filter { it.scope == "supervisor_day" }.maxOfOrNull { it.submitCycle } ?: return true
        val reopened = db.referenceDao().meta(key(businessDate, null))?.toIntOrNull() ?: 0
        return latest <= reopened
    }

    /** True while a submit of the date stands: some route-day (or the supervisor day) has a cycle the server has not voided. */
    suspend fun submitStands(db: AronDatabase, businessDate: String): Boolean =
        db.captureDao().daySubmitsOn(businessDate).groupBy { it.meta.routeId }.any { (route, rows) ->
            rows.maxOf { it.submitCycle } > (db.referenceDao().meta(key(businessDate, route))?.toIntOrNull() ?: 0)
        }

    suspend fun reopen(db: AronDatabase, businessDate: String, routeId: Long?, voidedCycle: Int) =
        db.referenceDao().putMeta(SyncMetaEntity(key(businessDate, routeId), voidedCycle.toString()))

    private fun key(businessDate: String, routeId: Long?) = "route_day.reopened.$businessDate.${routeId ?: "none"}"
}
