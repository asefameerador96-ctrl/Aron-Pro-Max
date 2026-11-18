package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.masterdata.GeoRepository
import com.aktcl.aron.backend.masterdata.SqlReachResolver
import com.aktcl.aron.backend.notify.PushNotifier
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.NIL_GENERATION
import com.aktcl.aron.backend.sync.WaitingChildSweepJob

/** The worker's waiting-item sweep built as Main builds it (D-LOY-API-28): the real job, the same ingest service and handlers as the API. */
fun waitingSweepJob(wiring: Wiring, clock: AronClock): WaitingChildSweepJob {
    val db = wiring.database!!
    return WaitingChildSweepJob(Wiring.ingestService(db, wiring.config, SqlReachResolver(db, GeoRepository(db, clock), clock), clock, { NIL_GENERATION }, PushNotifier(db, wiring.config, null)))
}
