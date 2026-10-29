package com.aktcl.aron.sr

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.database.repo.RouteDayFinalSubmittedException
import com.aktcl.aron.core.printing.flow.CommittedMemo
import com.aktcl.aron.core.printing.flow.MemoPrinting
import com.aktcl.aron.core.printing.flow.SaveAndPrint
import com.aktcl.aron.feature.outlet.OpenVisit
import com.aktcl.aron.feature.outlet.VisitSession

/**
 * tso-manual X4a (R-W-11, A3 asks 1 and 11, D-TSO-MAN-31, D-A3-22): how the SR screens meet the Final Submit lock that
 * CaptureRepository enforces. Every refusal ([RouteDayFinalSubmittedException]) is caught where the capture is launched and
 * worded with the lock text ([SrDayLockDialog]); nothing is stored, nothing is printed, nothing throws out of a screen.
 * Without the day object, so tests run it on a plain database with the production writers.
 */
internal object SrLockGate {
    /**
     * The sale's Save and Print with the lock named: a commit refused by the lock calls [onRefused] (the kit keeps which
     * sale or edit was refused) and is rethrown, so [SaveAndPrint] stops at SaveFailed before the print step (nothing
     * prints); the host of that sale or edit shows the lock text in its place.
     * A sale already stored (a replay after a kill) commits as before.
     */
    fun saveAndPrint(commit: suspend () -> CommittedMemo, printing: MemoPrinting, onRefused: () -> Unit): SaveAndPrint =
        SaveAndPrint({
            try {
                commit()
            } catch (e: RouteDayFinalSubmittedException) {
                onRefused()
                throw e
            }
        }, printing)

    /**
     * Closes the open call of [session] with [write] (its one `visit_close`). When the lock lands between the caller's check
     * and the write, the refusal ends the call on the phone instead: no close is queued (the server would refuse it) and
     * nothing throws. True when a close was queued (the caller asks for a sync).
     */
    suspend fun closeCall(session: VisitSession, write: suspend (OpenVisit) -> Unit): Boolean {
        val v = session.current.value ?: return false
        try {
            write(v)
        } catch (_: RouteDayFinalSubmittedException) {
            session.close()
            return false
        }
        session.close()
        return true
    }

    /** A skip from the outlet list ([write] queues its `visit_skip`): true when queued, false when the lock refused it (nothing stored). */
    suspend fun skip(write: suspend () -> Unit): Boolean =
        try {
            write()
            true
        } catch (_: RouteDayFinalSubmittedException) {
            false
        }
}

/**
 * tso-manual X4a (fix round): which sale or memo edit a refused save belongs to, so only its own host shows the lock text:
 * the edited memo's uuid for an edit, else the call's visit uuid.
 */
internal fun refusalKey(draft: com.aktcl.aron.feature.sale.domain.SaleDraft): String = draft.edit?.supersedesMemoUuid ?: draft.visitUuid

/**
 * The lock text where a capture was refused (a new call, a sale save, a skip, Mark Paid, a memo edit): the banner's title and
 * body ("New calls cannot start; sales, memo edits and collections are not accepted. Printing still works."), bn and en.
 */
@Composable
fun SrDayLockDialog(onOk: () -> Unit) {
    com.aktcl.aron.core.ui.aron.AronDialog(
        stringResource(R.string.sr_lock_title), stringResource(R.string.sr_lock_body), stringResource(R.string.sr_ok),
        onConfirm = onOk, onDismiss = onOk,
    )
}
