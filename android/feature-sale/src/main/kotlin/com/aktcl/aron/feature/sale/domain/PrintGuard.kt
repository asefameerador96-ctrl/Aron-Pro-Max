package com.aktcl.aron.feature.sale.domain

import com.aktcl.aron.core.printing.bt.PrintFailure
import com.aktcl.aron.core.printing.flow.PrintAttempt
import kotlinx.coroutines.CancellationException

/**
 * Runs a print step of the day flow (stock slip, day summary) so an exception in preparing or mapping the document can
 * never reach the process: the SR sees the "app error" dialog (`PrintFailure.INTERNAL`) instead of a crash. Cancellation
 * is not a failure and is rethrown.
 */
suspend fun guardedPrint(step: suspend () -> PrintAttempt): PrintAttempt =
    try {
        step()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        PrintAttempt.Failed(PrintFailure.INTERNAL)
    }
