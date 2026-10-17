package com.aktcl.aron.amo

import com.aktcl.aron.feature.amo.AmoReconciliation
import com.aktcl.aron.feature.dayclose.DaySource
import com.aktcl.aron.feature.dayclose.ServerCounts

/** The SR's day facts with the AMO's reconciliation rows (F-AMO-030); [types] reads `cfg.sync.reconcile_types` for the AMO. */
class AmoDaySource(private val inner: DaySource, private val types: suspend () -> List<String>) : DaySource by inner {
    override suspend fun deviceCounts(businessDate: String): Map<String, Int> = AmoReconciliation.deviceCounts(types(), inner.deviceCounts(businessDate))
}

class AmoServerCounts(private val inner: ServerCounts, private val types: suspend () -> List<String>) : ServerCounts {
    override suspend fun counts(businessDate: String): Map<String, Int>? = AmoReconciliation.serverCounts(types(), inner.counts(businessDate))
}
