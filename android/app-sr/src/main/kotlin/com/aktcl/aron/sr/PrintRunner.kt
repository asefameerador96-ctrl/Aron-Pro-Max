package com.aktcl.aron.sr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs a print (and its "was it readable?" confirm) in the day's scope, not in a screen's. A screen scope is cancelled the
 * moment the SR navigates away, which would cut a receipt off mid-paper and leave the print ledger half written; the day
 * scope outlives the screen. The result is handed back through [onResult], which only sets screen state (a no-op once the
 * screen is gone; a still-open ledger row is recovered after login like any other unconfirmed print).
 */
class PrintRunner(private val scope: CoroutineScope) {
    fun <T> run(block: suspend () -> T, onResult: (T) -> Unit = {}): Job = scope.launch { onResult(block()) }
}
