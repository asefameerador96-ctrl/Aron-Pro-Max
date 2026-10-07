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
        val latest = db.captureDao().daySubmitsFor(meta.businessDate, meta.routeId).maxOfOrNull { it.submitCycle } ?: return true
        val reopened = db.referenceDao().meta(key(meta.businessDate, meta.routeId))?.toIntOrNull() ?: 0
        return latest <= reopened
    }

    suspend fun reopen(db: AronDatabase, businessDate: String, routeId: Long?, voidedCycle: Int) =
        db.referenceDao().putMeta(SyncMetaEntity(key(businessDate, routeId), voidedCycle.toString()))

    private fun key(businessDate: String, routeId: Long?) = "route_day.reopened.$businessDate.${routeId ?: "none"}"
}
