package com.aktcl.aron.sr

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.database.repo.DayRefusal
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
 * Without the day object, so tests run it on a plain database with the production writers. Follow-up (R-W-22, R-W-23): the
 * stock Save meets the lock too, and a refusal because the SR's own Sales Submit stands
 * ([com.aktcl.aron.core.database.repo.RouteDaySubmittedException]) is caught the same way and worded with the submit text
 * ([SrDaySubmittedDialog]); [DayRefusal] says which.
 */
internal object SrLockGate {
    /**
     * The sale's Save and Print with the lock named: a commit refused by the route-day (the zone's Final Submit, or, since
     * the follow-up (R-W-23), a Sales Submit that stands) calls [onRefused] with why (the kit keeps which sale or edit was
     * refused) and is rethrown, so [SaveAndPrint] stops at SaveFailed before the print step (nothing prints); the host of that
     * sale or edit shows the matching text in its place. A sale already stored (a replay after a kill) commits as before.
     */
    fun saveAndPrint(commit: suspend () -> CommittedMemo, printing: MemoPrinting, onRefused: (DayRefusal) -> Unit): SaveAndPrint =
        SaveAndPrint({
            try {
                commit()
            } catch (e: Exception) {
                DayRefusal.of(e)?.let(onRefused)
                throw e
            }
        }, printing)

    /**
     * Closes the open call of [session] with [write] (its one `visit_close`). When the route-day refuses the close (the lock
     * landed between the caller's check and the write; follow-up: or a Sales Submit stands), the call ends on the phone
     * instead: no close is queued, [onRefused] is told why and nothing throws. True when a close was queued (the caller asks
     * for a sync).
     */
    suspend fun closeCall(session: VisitSession, onRefused: (DayRefusal) -> Unit = {}, write: suspend (OpenVisit) -> Unit): Boolean {
        val v = session.current.value ?: return false
        try {
            write(v)
        } catch (e: Exception) {
            val why = DayRefusal.of(e) ?: throw e
            session.close()
            onRefused(why)
            return false
        }
        session.close()
        return true
    }

    /**
     * A skip from the outlet list ([write] queues its `visit_skip`): true when queued, false when the route-day refused it
     * (nothing stored; [onRefused] is told why: the lock, or since the follow-up the rep's own Sales Submit, R-W-23).
     */
    suspend fun skip(onRefused: (DayRefusal) -> Unit = {}, write: suspend () -> Unit): Boolean =
        try {
            write()
            true
        } catch (e: Exception) {
            onRefused(DayRefusal.of(e) ?: throw e)
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

/**
 * tso-manual X4a follow-up (R-W-23): the words of a capture refused because today's sales are already submitted (the SR's
 * own Sales Submit stands): the Sales Submit title and feature-dayclose's `submit_blocked_done`, bn and en; nothing was stored.
 */
@Composable
fun SrDaySubmittedDialog(onOk: () -> Unit) {
    com.aktcl.aron.core.ui.aron.AronDialog(
        stringResource(com.aktcl.aron.feature.dayclose.R.string.submit_title), stringResource(com.aktcl.aron.feature.dayclose.R.string.submit_blocked_done),
        stringResource(R.string.sr_ok), onConfirm = onOk, onDismiss = onOk,
    )
}

/** tso-manual X4a follow-up (R-W-23): the words of [why]: the lock text, or the submit text. */
@Composable
fun SrRefusalDialog(why: DayRefusal, onOk: () -> Unit) {
    when (why) {
        DayRefusal.FINAL_SUBMIT -> SrDayLockDialog(onOk)
        DayRefusal.SALES_SUBMIT -> SrDaySubmittedDialog(onOk)
    }
}
