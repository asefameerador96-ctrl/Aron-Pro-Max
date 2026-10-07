package com.aktcl.aron.core.database.repo

import androidx.room.withTransaction
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.PrintEventEntity
import com.aktcl.aron.core.database.entity.PrintJobEntity
import com.aktcl.aron.core.database.record.RecordMapping
import com.aktcl.aron.core.printing.flow.PendingPrint
import com.aktcl.aron.core.printing.flow.PrintEvent
import com.aktcl.aron.core.printing.flow.PrintLedger
import java.time.Instant

/**
 * [PrintLedger] on Room (docs/requests/android-print-integration.md s1; `PrintFlowTest.MemLedger` is the reference).
 * Every write is one transaction: a printed paper is never forgotten (the pending job sets the printed flag as soon as
 * the paper is out) and a final event is written once with its outbox record (`print_event`).
 *
 * [envelope] completes the record envelope from the printed document's own row (date, route, versions) or, for a
 * document without a local row, from null: the app fills capture time and clock evidence from the trusted clock.
 */
class RoomPrintLedger(
    private val db: AronDatabase,
    private val envelope: suspend (base: CaptureMeta?) -> CaptureMeta,
    private val nowIso: () -> String = { Instant.now().toString() },
) : PrintLedger {
    private val dao = db.printDao()
    private val capture = db.captureDao()

    override suspend fun history(documentClientUuid: String): List<PrintEvent> = dao.eventsOf(documentClientUuid).map { it.toEvent() }

    override suspend fun pending(): List<PendingPrint> = dao.jobs().map { PendingPrint(it.toEvent(), it.paperOut) }

    override suspend fun savePending(job: PendingPrint): Unit = db.withTransaction {
        val e = job.event
        if (dao.eventCount(e.clientUuid) > 0) return@withTransaction // already final
        dao.upsertJob(job.toEntity())
        if (job.paperOut) markPrinted(e)
    }

    override suspend fun record(event: PrintEvent): Unit = db.withTransaction {
        if (dao.eventCount(event.clientUuid) > 0) return@withTransaction // idempotent by client uuid
        val memo = event.memoClientUuid?.let { capture.memo(it) }
        val stock = if (memo == null) event.refClientUuid?.let { capture.stockMovement(it) } else null
        val meta = envelope(memo?.meta ?: stock?.meta)
        val row = PrintEventEntity(
            event.clientUuid, meta, event.documentKind, event.memoClientUuid, event.refClientUuid, event.printCount, event.outcome,
            event.userConfirmed, event.templateVersion, event.printerModel?.take(40), event.atEpochMs,
        )
        dao.insertEvent(row)
        // A memo print belongs to the memo's visit family (child of the memo); every other print is its own family.
        val family = memo?.visitClientUuid ?: event.clientUuid
        db.outboxDao().insert(listOf(RecordMapping.printEvent(row, family, if (memo != null) 2 else 0, event.payload(), nowIso())))
        dao.deleteJob(event.clientUuid)
        when (event.outcome) {
            PrintEvent.PRINTED -> markPrinted(event)
            PrintEvent.FAILED_USER -> {
                val doc = event.memoClientUuid ?: event.refClientUuid
                if (doc != null && dao.printedCount(doc) == 0 && dao.otherPaperOutJobs(doc, event.clientUuid) == 0) clearPrinted(event)
            }
        }
        event.memoClientUuid?.let { dao.setMemoPrintCount(it, dao.printedCount(it)) }
    }

    private suspend fun markPrinted(e: PrintEvent) {
        val at = Instant.ofEpochMilli(e.atEpochMs).toString()
        val memo = e.memoClientUuid
        if (memo != null && dao.memoCount(memo) > 0) dao.markMemoPrinted(memo, at)
        else e.refClientUuid?.let { dao.setSlipPrinted(it, true) }
    }

    private suspend fun clearPrinted(e: PrintEvent) {
        val memo = e.memoClientUuid
        if (memo != null && dao.memoCount(memo) > 0) dao.clearMemoPrinted(memo)
        else e.refClientUuid?.let { dao.setSlipPrinted(it, false) }
    }

    private fun PrintEventEntity.toEvent() = PrintEvent(
        clientUuid, documentKind, memoClientUuid, refClientUuid, printCount, outcome, userConfirmed, templateVersion, printerModel, atMs,
    )

    private fun PrintJobEntity.toEvent() = PrintEvent(
        eventClientUuid, documentKind, memoClientUuid, refClientUuid, printCount, outcome, userConfirmed, templateVersion, printerModel, atMs,
    )

    private fun PendingPrint.toEntity() = PrintJobEntity(
        event.clientUuid, event.documentKind, event.memoClientUuid, event.refClientUuid, event.printCount, event.outcome,
        event.userConfirmed, event.templateVersion, event.printerModel, event.atEpochMs, paperOut,
    )
}
