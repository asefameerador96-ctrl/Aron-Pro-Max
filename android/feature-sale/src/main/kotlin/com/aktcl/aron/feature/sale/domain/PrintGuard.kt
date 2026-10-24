package com.aktcl.aron.feature.sale.domain

import com.aktcl.aron.core.printing.bt.PrintFailure
import com.aktcl.aron.core.printing.flow.PrintAttempt
import kotlinx.coroutines.CancellationException

/**
 * Runs a print step of the day flow (stock slip, day summary) so an exception in preparing or mapping the document can
 * never reach the process (an Error too, as in core-printing's own guard): the SR sees the "app error" dialog (`PrintFailure.INTERNAL`) instead of a crash. Cancellation
 * is not a failure and is rethrown.
 */
suspend fun guardedPrint(step: suspend () -> PrintAttempt): PrintAttempt =
    try {
        step()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // kept for Settings → Printer diagnostics → Share report, like every other caught print error
        com.aktcl.aron.core.printing.diag.CrashLog.handled("print-step", e)
        PrintAttempt.Failed(PrintFailure.INTERNAL)
    }
