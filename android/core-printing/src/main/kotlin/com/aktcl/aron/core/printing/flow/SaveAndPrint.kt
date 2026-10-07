package com.aktcl.aron.core.printing.flow

import com.aktcl.aron.core.printing.bt.PrintFailure
import com.aktcl.aron.core.printing.doc.MemoPrint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A memo the sale screen has committed: its client uuid and what prints. */
class CommittedMemo(val memoClientUuid: String, val print: MemoPrint)

/**
 * The dialogs behind the sale screen's Print button (F-SR-028, F-SR-073), as a state machine the screen renders:
 * 1. "আপনি কি নিশ্চিত? বিক্রয় জমা হবে" — Yes commits the immutable memo (through [commit], one local transaction
 *    with the outbox) before and regardless of printing; No goes back to the sale.
 * 2. "আপনি কি এই বিক্রয়টি প্রিন্ট করতে চান?" — No leaves printed_at empty (reprint from the Memo menu).
 * 3. Printing; on a printer failure: retry or print later (the sale is already saved).
 * 4. "ছাপা ঠিক আছে?" — the answer is recorded; then "বিক্রয় সফল ভাবে জমা হয়েছে".
 * A second tap while a step runs is ignored, so one tap never commits or prints twice.
 */
class SaveAndPrint(
    private val commit: suspend () -> CommittedMemo,
    private val printing: MemoPrinting,
) {
    sealed interface Step {
        data object Idle : Step
        data object AskSave : Step
        data object Saving : Step
        /** The local save failed (storage); nothing was printed. The seller can try again. */
        data object SaveFailed : Step
        data object AskPrint : Step
        data object Printing : Step
        data class PrintFailed(val reason: PrintFailure) : Step
        data object TooLong : Step
        data object LimitReached : Step
        data object AskReadable : Step
        /** The sale is saved; [printed] tells whether paper was confirmed. */
        data class Done(val printed: Boolean) : Step
    }

    private val _step = MutableStateFlow<Step>(Step.Idle)
    val step: StateFlow<Step> = _step.asStateFlow()

    private var committed: CommittedMemo? = null
    private var awaiting: PrintAttempt.AwaitingConfirmation? = null

    /** The memo saved by this flow (null until "Yes" on the first dialog has committed it). */
    val memo: CommittedMemo? get() = committed

    fun onPrintTapped() {
        if (_step.value == Step.Idle || _step.value == Step.SaveFailed) _step.value = Step.AskSave
    }

    /** Leaves the "save failed" message back to the sale. */
    fun onDismissSaveFailed() {
        if (_step.value == Step.SaveFailed) _step.value = Step.Idle
    }

    suspend fun onSaveAnswer(yes: Boolean) {
        if (_step.value != Step.AskSave) return
        if (!yes) { _step.value = Step.Idle; return }
        _step.value = Step.Saving
        committed = try {
            commit()
        } catch (e: CancellationException) {
            _step.value = Step.Idle
            throw e
        } catch (_: Exception) {
            _step.value = Step.SaveFailed
            return
        }
        _step.value = Step.AskPrint
    }

    suspend fun onPrintAnswer(yes: Boolean) {
        if (_step.value != Step.AskPrint) return
        if (!yes) { _step.value = Step.Done(printed = false); return }
        printNow()
    }

    /** Retry from the failure step. */
    suspend fun onRetry() {
        if (_step.value !is Step.PrintFailed) return
        printNow()
    }

    /** "Print later" from the failure step: the sale stays saved and reprintable. */
    fun onLater() {
        val s = _step.value
        if (s is Step.PrintFailed || s == Step.TooLong || s == Step.LimitReached) _step.value = Step.Done(printed = false)
    }

    private suspend fun printNow() {
        val m = committed ?: return
        _step.value = Step.Printing
        val attempt = try {
            printing.printMemo(m.memoClientUuid, m.print)
        } catch (e: CancellationException) {
            _step.value = Step.PrintFailed(PrintFailure.DISCONNECTED)
            throw e
        } catch (_: Exception) {
            PrintAttempt.Failed(PrintFailure.DISCONNECTED) // storage or rendering trouble: the sale stays saved
        }
        _step.value = when (val a = attempt) {
            is PrintAttempt.AwaitingConfirmation -> { awaiting = a; Step.AskReadable }
            PrintAttempt.Done -> Step.Done(printed = true)
            is PrintAttempt.Failed -> Step.PrintFailed(a.reason)
            PrintAttempt.LimitReached -> Step.LimitReached
            PrintAttempt.TooLong -> Step.TooLong
        }
    }

    suspend fun onReadableAnswer(readable: Boolean) {
        if (_step.value != Step.AskReadable) return
        val a = awaiting ?: return
        try {
            printing.confirm(a, readable)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return // not stored; the dialog stays and the seller can answer again (the job is recovered on restart)
        }
        awaiting = null
        _step.value = Step.Done(printed = readable)
    }
}
