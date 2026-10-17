package com.aktcl.aron.amo

import com.aktcl.aron.feature.amo.AmoReconciliation
import com.aktcl.aron.feature.dayclose.DaySource
import com.aktcl.aron.feature.dayclose.ServerCounts

/** The SR's day facts with the AMO's fixed reconciliation rows (F-AMO-030). */
class AmoDaySource(private val inner: DaySource) : DaySource by inner {
    override suspend fun deviceCounts(businessDate: String): Map<String, Int> = AmoReconciliation.deviceCounts(inner.deviceCounts(businessDate))
}

class AmoServerCounts(private val inner: ServerCounts) : ServerCounts {
    override suspend fun counts(businessDate: String): Map<String, Int>? = AmoReconciliation.serverCounts(inner.counts(businessDate))
}
