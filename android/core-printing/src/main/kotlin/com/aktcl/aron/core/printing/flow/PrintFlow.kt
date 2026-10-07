package com.aktcl.aron.core.printing.flow

import com.aktcl.aron.core.printing.PaperRenderer
import com.aktcl.aron.core.printing.RenderedPaper
import com.aktcl.aron.core.printing.bt.PrintFailure
import com.aktcl.aron.core.printing.bt.PrintJob
import com.aktcl.aron.core.printing.bt.PrintOutcome
import com.aktcl.aron.core.printing.bt.PrinterManager
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
 * Where print events live. The android-core lane implements it on Room: [record] writes the `print_event` row
 * and its outbox record in one transaction and, when [PrintEvent.outcome] is `printed`, sets the document's
 * printed flag (`memo.printed_at` if null, `memo.print_count`; `stock_movement.slip_printed`) in the same
 * transaction. REQUEST: docs/requests/android-print-integration.md
 */
interface PrintLedger {
    /** Every print event of one document, oldest first. */
    suspend fun history(documentClientUuid: String): List<PrintEvent>

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
        val history = ledger.history(memoClientUuid)
        if (!ReprintPolicy.allowed(history, reprintMax())) return PrintAttempt.LimitReached
        val reprintNo = ReprintPolicy.nextReprintNo(history)
        val kind = if (reprintNo == 0) "memo" else "memo_reprint"
        return print(memoClientUuid, kind, memoClientUuid, null, history) { it.memo(memo.copy(reprintNo = reprintNo)) }
    }

    /** Prints the stock slip of the stock family [stockClientUuid] (F-SR-015). */
    suspend fun printStockSlip(stockClientUuid: String, slip: StockSlipPrint): PrintAttempt {
        val history = ledger.history(stockClientUuid)
        if (!ReprintPolicy.allowed(history, reprintMax())) return PrintAttempt.LimitReached
        val reprintNo = ReprintPolicy.nextReprintNo(history)
        return print(stockClientUuid, "stock_slip", null, stockClientUuid, history) { it.stockSlip(slip.copy(reprintNo = reprintNo)) }
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
            printCount = (history.size + 1).coerceIn(1, 100), outcome = PrintEvent.PRINTED, userConfirmed = null,
            templateVersion = paper.templateVersion, printerModel = printer.savedPrinter?.name, atEpochMs = nowMs(),
        )
        // Each attempt is its own job id: a retry after a failure is a fresh, whole print.
        return when (val r = printer.print(PrintJob(eventUuid, paper.bitmap))) {
            is PrintOutcome.Failed -> {
                ledger.record(base.copy(outcome = PrintEvent.FAILED))
                PrintAttempt.Failed(r.reason)
            }
            PrintOutcome.Printed, PrintOutcome.AlreadyPrinted -> {
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
        ledger.record(
            if (readable) attempt.pending.copy(userConfirmed = true)
            else attempt.pending.copy(outcome = PrintEvent.FAILED_USER, userConfirmed = false),
        )
    }
}
