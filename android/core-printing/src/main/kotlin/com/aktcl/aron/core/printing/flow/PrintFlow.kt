package com.aktcl.aron.core.printing.flow

import com.aktcl.aron.core.printing.PaperRenderer
import com.aktcl.aron.core.printing.RenderedPaper
import com.aktcl.aron.core.printing.bt.PrintFailure
import com.aktcl.aron.core.printing.bt.PrintJob
import com.aktcl.aron.core.printing.bt.PrintOutcome
import com.aktcl.aron.core.printing.bt.PrinterManager
import com.aktcl.aron.core.printing.doc.DaySummaryPrint
import com.aktcl.aron.core.printing.doc.MemoPrint
import com.aktcl.aron.core.printing.doc.StockSlipPrint
import com.aktcl.aron.core.printing.render.PaperTooLongException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * One `print_event` record (contract `PrintEventPayload`), written once per print attempt when its outcome is
 * known: `printed` after the seller confirmed the paper (or without confirmation when that is switched off),
 * `failed` when the printer failed, `failed_user` when the seller said the paper is not readable.
 */
data class PrintEvent(
    val clientUuid: String,
    /** Contract `PrintDocumentKind`: memo, memo_reprint, stock_slip, day_summary, void_slip, due_receipt. */
    val documentKind: String,
    val memoClientUuid: String?,
    val refClientUuid: String?,
    val printCount: Int,
    val outcome: String,
    val userConfirmed: Boolean?,
    val templateVersion: Int,
    val printerModel: String?,
    val atEpochMs: Long,
) {
    /** The record payload exactly as the contract names it. */
    fun payload(): JsonObject = buildJsonObject {
        put("document_kind", JsonPrimitive(documentKind))
        put("memo_client_uuid", memoClientUuid?.let { JsonPrimitive(it) } ?: JsonNull)
        put("ref_client_uuid", refClientUuid?.let { JsonPrimitive(it) } ?: JsonNull)
        put("print_count", JsonPrimitive(printCount))
        put("outcome", JsonPrimitive(outcome))
        put("user_confirmed", userConfirmed?.let { JsonPrimitive(it) } ?: JsonNull)
        put("template_version", JsonPrimitive(templateVersion))
        put("printer_model", printerModel?.take(40)?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    companion object {
        const val PRINTED = "printed"
        const val FAILED = "failed"
        const val FAILED_USER = "failed_user"
    }
}

/**
 * A print job not yet final (docs/17 s9.4): saved before the printer is called, marked [paperOut] as soon as the
 * printer took the whole job. Local only; it becomes one immutable [PrintEvent] when the outcome is final.
 */
data class PendingPrint(val event: PrintEvent, val paperOut: Boolean)

/**
 * Where print jobs and events live. The android-core lane implements it on Room
 * (REQUEST: docs/requests/android-print-integration.md); `PrintFlowTest.MemLedger` is the reference behaviour.
 * - [savePending] upserts the local job by event uuid; with `paperOut` true it also sets the document's printed
 *   flag in the same transaction (`memo.printed_at` if null; `stock_movement.slip_printed`), so a printed paper is
 *   never forgotten, even if the app dies before the seller answers.
 * - [record] writes the final `print_event` row and its outbox record and deletes the pending job, in one
 *   transaction; a second record of the same uuid is ignored. A `failed_user` record clears the printed flag
 *   again when no other copy of the document counts (no `printed` event, no other job with paper out).
 */
interface PrintLedger {
    /** Final print events of one document, oldest first. */
    suspend fun history(documentClientUuid: String): List<PrintEvent>

    /** Jobs not yet final, all documents. */
    suspend fun pending(): List<PendingPrint>

    suspend fun savePending(job: PendingPrint)

    suspend fun record(event: PrintEvent)
}

/** What the seller sees after tapping Print (or Yes on "print this sale?"). */
sealed interface PrintAttempt {
    /** On paper; ask "ছাপা ঠিক আছে?" and call [MemoPrinting.confirm] with the answer. */
    class AwaitingConfirmation internal constructor(internal val pending: PrintEvent) : PrintAttempt {
        internal val answered = java.util.concurrent.atomic.AtomicBoolean(false)
    }
    /** On paper and recorded (confirmation switched off). */
    data object Done : PrintAttempt
    /** Not printed; the document stays reprintable. Nothing about the sale changed. */
    data class Failed(val reason: PrintFailure) : PrintAttempt
    /** The configured reprint limit is used up (cfg.memo.reprint_max). */
    data object LimitReached : PrintAttempt
    /** The document is too long to print (a runaway template); treated like a failure. */
    data object TooLong : PrintAttempt
}

/**
 * Reprint rules (F-SR-031, F-SR-066, F-SR-073): only `printed` events count. The first counted print is the
 * original; every later one is a reprint with the duplicate marker and its ordinal, refused once
 * `cfg.memo.reprint_max` reprints exist. A print the seller rejected (`failed_user`) or the printer failed
 * does not count, so the next print carries no marker if no counted print exists yet.
 */
object ReprintPolicy {
    fun counted(history: List<PrintEvent>): Int = history.count { it.outcome == PrintEvent.PRINTED }

    /** Final events plus paper already out but not yet confirmed, which counts as printed until a "না". */
    internal fun effective(history: List<PrintEvent>, pending: List<PendingPrint>): List<PrintEvent> =
        history + pending.filter { it.paperOut }.map { it.event.copy(outcome = PrintEvent.PRINTED) }

    /** Reprint ordinal of the next print: 0 = original. */
    fun nextReprintNo(history: List<PrintEvent>): Int = counted(history).let { if (it == 0) 0 else it }

    fun allowed(history: List<PrintEvent>, reprintMax: Int): Boolean {
        val c = counted(history)
        return c == 0 || c <= reprintMax.coerceIn(0, 20)
    }
}

/**
 * Printing of committed documents (F-SR-028, F-SR-031, F-SR-066, F-SR-073, F-SR-015). Callers commit first:
 * nothing here touches the sale, and every failure leaves the document reprintable. Never throws for printer
 * problems.
 */
class MemoPrinting(
    private val printer: PrinterManager,
    private val renderer: () -> PaperRenderer,
    private val ledger: PrintLedger,
    private val newUuid: () -> String,
    private val nowMs: () -> Long,
    private val reprintMax: () -> Int = { 5 },
    private val confirmAfterPrint: () -> Boolean = { true },
) {
    /** Prints [memo] (the stored memo, [memoClientUuid]) as the original or as the next reprint. */
    suspend fun printMemo(memoClientUuid: String, memo: MemoPrint): PrintAttempt {
        val history = historyOf(memoClientUuid)
        if (!ReprintPolicy.allowed(history, reprintMax())) return PrintAttempt.LimitReached
        val reprintNo = ReprintPolicy.nextReprintNo(history)
        val kind = if (reprintNo == 0) "memo" else "memo_reprint"
        return print(memoClientUuid, kind, memoClientUuid, null, history) { it.memo(memo.copy(reprintNo = reprintNo)) }
    }

    /** Prints the stock slip of the stock family [stockClientUuid] (F-SR-015). */
    suspend fun printStockSlip(stockClientUuid: String, slip: StockSlipPrint): PrintAttempt {
        val history = historyOf(stockClientUuid)
        if (!ReprintPolicy.allowed(history, reprintMax())) return PrintAttempt.LimitReached
        val reprintNo = ReprintPolicy.nextReprintNo(history)
        return print(stockClientUuid, "stock_slip", null, stockClientUuid, history) { it.stockSlip(slip.copy(reprintNo = reprintNo)) }
    }

    /**
     * Prints the day summary (F-SR-036) as it stands now. A summary is a report of the moment it was printed, not a
     * bill, so it has no reprint limit and no duplicate marker (AP-09). [daySummaryUuid] is the caller's stable
     * UUID v4 for that user's business date, so every print of one day is one document on the server.
     */
    suspend fun printDaySummary(daySummaryUuid: String, summary: DaySummaryPrint): PrintAttempt =
        print(daySummaryUuid, "day_summary", null, daySummaryUuid, historyOf(daySummaryUuid)) { it.daySummary(summary) }

    private suspend fun historyOf(uuid: String): List<PrintEvent> = ReprintPolicy.effective(
        ledger.history(uuid),
        ledger.pending().filter { it.event.memoClientUuid == uuid || it.event.refClientUuid == uuid },
    )

    /**
     * At app start: finishes jobs a killed process left behind. Paper that came out is recorded `printed` with
     * no confirmation; a job that never finished printing is `failed` (docs/17 s9.4).
     */
    suspend fun recover() {
        for (p in ledger.pending()) {
            ledger.record(if (p.paperOut) p.event.copy(userConfirmed = null) else p.event.copy(outcome = PrintEvent.FAILED))
        }
    }

    private suspend fun markPaperOut(event: PrintEvent) {
        repeat(3) {
            try {
                ledger.savePending(PendingPrint(event, paperOut = true))
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun print(
        documentUuid: String, kind: String, memoUuid: String?, refUuid: String?, history: List<PrintEvent>,
        render: (PaperRenderer) -> RenderedPaper,
    ): PrintAttempt {
        val paper = try {
            render(renderer())
        } catch (_: PaperTooLongException) {
            return PrintAttempt.TooLong
        }
        val eventUuid = newUuid()
        val base = PrintEvent(
            clientUuid = eventUuid, documentKind = kind, memoClientUuid = memoUuid, refClientUuid = refUuid,
            // The copy this attempt would be: copies on paper so far + 1 (failed attempts printed nothing).
            printCount = (ReprintPolicy.counted(history) + 1).coerceIn(1, 100), outcome = PrintEvent.PRINTED, userConfirmed = null,
            templateVersion = paper.templateVersion, printerModel = printer.savedPrinter?.name, atEpochMs = nowMs(),
        )
        // The job is saved before the printer is called; each attempt is its own job id, so a retry after a
        // failure is a fresh, whole print.
        ledger.savePending(PendingPrint(base, paperOut = false))
        return when (val r = printer.print(PrintJob(eventUuid, paper.bitmap))) {
            is PrintOutcome.Failed -> {
                ledger.record(base.copy(outcome = PrintEvent.FAILED))
                PrintAttempt.Failed(r.reason)
            }
            PrintOutcome.Printed, PrintOutcome.AlreadyPrinted -> {
                // The paper is out whatever storage says next: a failed save here never turns it into a
                // failure; the confirmation (or the final record) stores it again.
                markPaperOut(base)
                if (confirmAfterPrint()) {
                    PrintAttempt.AwaitingConfirmation(base)
                } else {
                    ledger.record(base)
                    PrintAttempt.Done
                }
            }
        }
    }

    /**
     * The seller's answer to "ছাপা ঠিক আছে?". Yes records `printed` (the memo becomes printed and the print
     * counts); No records `failed_user` (nothing counts, the next print is again the original or the same reprint).
     */
    suspend fun confirm(attempt: PrintAttempt.AwaitingConfirmation, readable: Boolean) {
        if (!attempt.answered.compareAndSet(false, true)) return // a double tap records once
        try {
            ledger.record(
                if (readable) attempt.pending.copy(userConfirmed = true)
                else attempt.pending.copy(outcome = PrintEvent.FAILED_USER, userConfirmed = false),
            )
        } catch (e: Exception) {
            attempt.answered.set(false) // not stored: the seller can answer again
            throw e
        }
    }
}
