package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.exitIfFatal
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The waiting-item sweep (D-LOY-API-28): releases waiting quarantine items whose awaited parent is stored ([IngestService.sweepWaiting]).
 *
 * It lives in the API process and uses the API role's database login on purpose (least privilege: the worker login has no write right on the ingest tables). It is started
 * only by `Wiring.startBackgroundJobs` from Main's API role (never in the migrate or worker role, never by a test unless the test starts it), and:
 *  - config: `cfg.sync.waiting_sweep_enabled` (default on), `cfg.sync.waiting_sweep_interval_s` (default 60, at least 10), `cfg.sync.waiting_sweep_batch` (default 200, 1..1000), each read at every
 *    pass so a change needs no deploy; env `ARON_WAITING_SWEEP=off` is an emergency stop;
 *  - one daemon thread, the next pass is scheduled when the previous one has ended, and a pass that finds another still running (`tick` from elsewhere) is skipped: passes never overlap;
 *  - no connection or request thread is held between passes (every pass borrows from the pool per item and gives it back);
 *  - [stop] lets the pass in hand finish (each release is ONE transaction: a release is whole or not at all), then ends the thread; if the pass runs past the grace it is interrupted and the
 *    open transaction rolls back, so no child is ever half released;
 *  - several API replicas are safe: the per-uuid advisory lock and the item row `FOR UPDATE SKIP LOCKED` make one release win, the others find the item no longer open or locked.
 */
class WaitingChildSweepJob(
    private val ingest: IngestService,
    private val config: ServerConfig? = null,
    private val env: (String) -> String? = System::getenv,
    private val firstDelayS: Long = 30,
) {
    private val log = LoggerFactory.getLogger("aron.sync")
    private val running = AtomicBoolean(false)
    @Volatile private var stopped = false
    private var exec: ScheduledExecutorService? = null

    private fun enabled(): Boolean = !"off".equals(env("ARON_WAITING_SWEEP"), ignoreCase = true) && runCatching { config?.bool("cfg.sync.waiting_sweep_enabled") }.getOrNull() != false
    fun intervalS(): Long = (runCatching { config?.int("cfg.sync.waiting_sweep_interval_s") }.getOrNull() ?: 60).coerceAtLeast(10).toLong()
    private fun batch(): Int = (runCatching { config?.int("cfg.sync.waiting_sweep_batch") }.getOrNull() ?: 200).coerceIn(1, 1000)

    /** One pass, or null when the sweep is switched off or another pass is still running. */
    fun tick(): IngestService.WaitingSweep? {
        if (stopped || !enabled()) return null
        if (!running.compareAndSet(false, true)) return null
        try { return ingest.sweepWaiting(batch()) } finally { running.set(false) }
    }

    fun start() {
        val ex = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "waiting-item-sweep").apply { isDaemon = true } }
        exec = ex
        fun schedule(delayS: Long) {
            if (stopped) return
            runCatching { ex.schedule({ pass(::schedule) }, delayS, TimeUnit.SECONDS) }
        }
        schedule(firstDelayS)
        log.info("waiting-item sweep started (interval {} s, batch {}, enabled {})", intervalS(), batch(), enabled())
    }

    private fun pass(next: (Long) -> Unit) {
        try {
            tick()?.let { if (it.released + it.hinted + it.failed + it.skippedByCap > 0) log.info("waiting-item sweep: {} waiting, {} released, {} hinted, {} still waiting, {} failed, {} skipped by the cap", it.waiting, it.released, it.hinted, it.stillWaiting, it.failed, it.skippedByCap) }
        } catch (e: Throwable) {
            e.exitIfFatal(log, "waiting-item sweep"); log.error("waiting-item sweep failed", e)
        } finally { next(intervalS()) }
    }

    /** Stops cleanly: no new pass, the one in hand finishes (up to [graceS]), then it is interrupted (its transaction rolls back whole). */
    fun stop(graceS: Long = 20) {
        stopped = true
        val ex = exec ?: return
        ex.shutdown()
        if (!ex.awaitTermination(graceS, TimeUnit.SECONDS)) { ex.shutdownNow(); ex.awaitTermination(5, TimeUnit.SECONDS) }
        log.info("waiting-item sweep stopped")
    }
}
