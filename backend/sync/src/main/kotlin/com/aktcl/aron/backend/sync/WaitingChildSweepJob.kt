package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.exitIfFatal
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Worker job (D-LOY-API-28): releases waiting quarantine items whose awaited parent is stored ([IngestService.sweepWaiting]). One pass every [everyMin] minutes; logs counts only. */
class WaitingChildSweepJob(private val ingest: IngestService, private val everyMin: Long = 1, private val limit: Int = 200) {
    private val log = LoggerFactory.getLogger("aron.sync")

    fun tick(): IngestService.WaitingSweep = ingest.sweepWaiting(limit)

    fun start() {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "waiting-item-sweep").apply { isDaemon = true } }.scheduleWithFixedDelay({
            runCatching { tick() }
                .onSuccess { if (it.released + it.hinted + it.failed + it.skippedByCap > 0) log.info("waiting-item sweep: {} waiting, {} released, {} hinted, {} still waiting, {} failed, {} skipped by the cap", it.waiting, it.released, it.hinted, it.stillWaiting, it.failed, it.skippedByCap) }
                .onFailure { it.exitIfFatal(log, "waiting-item sweep"); log.error("waiting-item sweep failed", it) }
        }, 1, everyMin, TimeUnit.MINUTES)
    }
}
