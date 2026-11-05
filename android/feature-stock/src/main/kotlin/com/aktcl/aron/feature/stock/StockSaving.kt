package com.aktcl.aron.feature.stock

import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.StockMovementEntity
import com.aktcl.aron.core.database.repo.DayRefusal

/**
 * What one Save of the Stock screen came to (tso-manual X4a, R-W-22): the screen's [message], or, when the route-day refused
 * the Save ([refused]: the zone's Final Submit closed it), no message; the host shows the lock text instead. Nothing was
 * stored or queued then, so no slip is offered or printed for it.
 */
data class StockSaveResult(val message: StockMessage?, val refused: DayRefusal? = null) {
    /** True when the movements were stored and queued (the host asks for a sync and re-reads the unprinted slip). */
    val saved: Boolean get() = message == StockMessage.SAVED
}

/**
 * One Save of the Stock screen for the SR and the AMO hosts (F-SR-013/014; tso-manual X4a, R-W-22), so both meet the day lock
 * the same way and a test runs it on the real day. Never throws for a refusal or a failed write.
 */
object StockSaving {
    /**
     * [load] builds the movements with [meta]; [record] commits them (CaptureRepository.recordStock). Stored: folded into
     * today's totals at [nowMs], [StockMessage.SAVED]. Refused by the route-day: nothing is stored, the same values may be
     * saved again once the day reopens, and the result carries why. Any other failure: [StockMessage.SAVE_FAILED], as before.
     */
    suspend fun save(load: StockLoad, nowMs: () -> Long, meta: CaptureMeta, record: suspend (List<StockMovementEntity>) -> Unit): StockSaveResult =
        when (val out = load.save(nowMs(), meta)) {
            is SaveOutcome.Refused -> StockSaveResult(if (out.reason == SaveRefusal.NOTHING_ENTERED) StockMessage.NOTHING_ENTERED else StockMessage.REFUSED_SAME_VALUES)
            is SaveOutcome.Saved -> try {
                record(out.movements)
                load.committed(out, nowMs())
                StockSaveResult(StockMessage.SAVED)
            } catch (e: kotlinx.coroutines.CancellationException) {
                load.commitFailed()
                throw e
            } catch (e: Exception) {
                load.commitFailed()
                val why = DayRefusal.of(e)
                StockSaveResult(if (why == null) StockMessage.SAVE_FAILED else null, why)
            }
        }
}
