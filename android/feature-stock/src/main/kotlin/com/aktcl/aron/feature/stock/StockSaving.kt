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
    suspend fun save(
        load: StockLoad, nowMs: () -> Long, meta: CaptureMeta,
        /** Which of these client UUIDs the repository already holds (an attempt that landed before a process kill); default none. */
        storedAlready: (suspend (List<String>) -> Set<String>)? = null,
        record: suspend (List<StockMovementEntity>) -> Unit,
    ): StockSaveResult =
        when (val out = load.save(nowMs(), meta)) {
            is SaveOutcome.Refused -> StockSaveResult(if (out.reason == SaveRefusal.NOTHING_ENTERED) StockMessage.NOTHING_ENTERED else StockMessage.REFUSED_SAME_VALUES)
            is SaveOutcome.Saved -> try {
                val ids = out.movements.map { it.clientUuid }
                val already = storedAlready?.invoke(ids) ?: emptySet()
                record(out.movements)
                // what the repository REALLY holds now: a UUID it dropped (the purge already forgot an acked copy) adds nothing to the loaded figure
                val added = if (storedAlready == null) ids.toSet() - already else storedAlready(ids) - already
                load.committed(out, nowMs(), ids.toSet() - added)
                // nothing was added: every line was on the phone before this tap (or is one the purge forgot): the earlier Save landed
                StockSaveResult(if (added.isEmpty()) StockMessage.ALREADY_SAVED else StockMessage.SAVED)
            } catch (e: kotlinx.coroutines.CancellationException) {
                load.commitFailed()
                throw e
            } catch (e: Exception) {
                load.commitFailed()
                val why = DayRefusal.of(e)
                // D-LEAD-PREREQ-04 ruling 4: the check-in gate refused: the rep is told so, never the generic "could not save"
                val gate = (e as? com.aktcl.aron.core.database.repo.AttendanceRefusedException)?.why
                StockSaveResult(
                    when {
                        why != null -> null
                        gate == com.aktcl.aron.core.database.repo.AttendanceRefusal.CHECKED_OUT -> StockMessage.CHECKED_OUT
                        gate != null -> StockMessage.NOT_CHECKED_IN
                        else -> StockMessage.SAVE_FAILED
                    },
                    why,
                )
            }
        }
}
