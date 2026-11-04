package com.aktcl.aron.amo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import android.content.res.Resources
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.common.LocaleDigits
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.feature.home.RoleBanner
import com.aktcl.aron.core.database.repo.DayRefusal
import com.aktcl.aron.core.database.repo.RouteDayFinalSubmittedException
import com.aktcl.aron.core.printing.flow.CommittedMemo
import com.aktcl.aron.core.printing.flow.MemoPrinting
import com.aktcl.aron.core.printing.flow.SaveAndPrint
import com.aktcl.aron.feature.outlet.OpenVisit
import com.aktcl.aron.feature.outlet.VisitSession
import com.aktcl.aron.feature.amo.R as AmoR

/**
 * tso-manual X4a (R-W-11, A3 asks 1 and 11, D-TSO-MAN-31, D-A3-22): how the AMO's control, joint and sale calls meet the
 * Final Submit lock that CaptureRepository enforces per route. Every refusal ([RouteDayFinalSubmittedException]) is caught
 * where the capture is launched and worded with the lock text ([AmoDayLockDialog]); nothing is stored, nothing is printed,
 * nothing throws out of a screen. Without the day object, so tests run it on a plain database with the production writers.
 * Follow-up (R-W-22, R-W-23): the stock Saves meet the lock too, and a refusal because the AMO's own supervisor-day Sales
 * Submit stands ([com.aktcl.aron.core.database.repo.RouteDaySubmittedException]) is caught the same way and worded with the
 * submit text ([AmoDaySubmittedDialog]); [DayRefusal] says which.
 */
internal object AmoLockGate {
    /**
     * The sale's Save and Print with the lock named: a commit the route-day refuses (the zone's Final Submit, or since the
     * follow-up a Sales Submit that stands) calls [onRefused] with why (the kit keeps which sale or edit was refused) and is
     * rethrown, so [SaveAndPrint] stops at SaveFailed before the print step (nothing prints); the host of that sale or edit
     * shows the matching text in its place.
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
     * Closes the open call of [session] with [write]. When the route-day refuses the close (the lock landed between the
     * caller's check ([AmoCallLock]) and the write; follow-up: or a Sales Submit stands), the call ends on the phone: no close
     * is queued, [onRefused] is told why and nothing throws. True when a close was queued.
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
     * A skip ([write] queues its `visit_skip`): true when queued, false when the route-day refused it (nothing stored;
     * [onRefused] says why: the lock, or since the follow-up the supervisor day's Sales Submit, R-W-23).
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
 * tso-manual X4a (R-W-11; follow-up R-W-23): why a new control, joint or sale call on [routeId] would be refused today, or
 * null: the zone's Final Submit closed that route-day, or the AMO's own supervisor-day Sales Submit stands. Pure reads.
 */
internal suspend fun AmoDay.newCallRefusal(routeId: Long): DayRefusal? =
    runCatching { capture.dayRefusal(metaProvider.meta(routeId), "visit") }.getOrNull()

/**
 * tso-manual X4a (R-W-11, A3 ask 11, fix round; follow-up R-W-23): the outlet pick of a control, joint or sale call ([kind]).
 * False when a call on [outlet]'s route would be refused ([newCallRefusal]; [onRefused] says why): no fix is read, the day
 * takes no route and nothing is stored; the host shows the matching text. Otherwise the day takes that route and the visit
 * flow opens the call (it reads the fix and commits; its state says how that went).
 */
internal suspend fun AmoDay.openCall(outlet: com.aktcl.aron.core.database.entity.OutletEntity, kind: String, onRefused: (DayRefusal) -> Unit = {}): Boolean {
    newCallRefusal(outlet.routeId)?.let { onRefused(it); return false }
    routeId = outlet.routeId
    runCatching { visitFlow.open(com.aktcl.aron.feature.amo.ControlCall.visitOutlet(outlet, kind)) }
    return true
}

/**
 * tso-manual X4a (R-W-11, fix round; follow-up R-W-23): why [state] is a call commit the route-day refused (the lock, or the
 * supervisor day's Sales Submit, landed while the fix was read; nothing was stored), or null. The check step shows the
 * matching text, not the generic retry.
 */
internal suspend fun AmoDay.commitRefusal(state: com.aktcl.aron.feature.outlet.VisitUiState?): DayRefusal? =
    (state as? com.aktcl.aron.feature.outlet.VisitUiState.CommitFailed)?.let { newCallRefusal(it.outlet.routeId) }

/** True when [commitRefusal] is the Final Submit lock. */
internal suspend fun AmoDay.refusedByLock(state: com.aktcl.aron.feature.outlet.VisitUiState?): Boolean =
    commitRefusal(state) == DayRefusal.FINAL_SUBMIT

/** What one Save of a call step (assessment, joint call, survey, price check) came to: stored, or refused and why. */
internal data class StepSave(val saved: Boolean, val refused: DayRefusal?)

/**
 * tso-manual X4a follow-up (R-W-23): one Save of a call step ([write]: the AMO record or survey writer). Stored: saved. A
 * refusal of the route-day (the AMO's supervisor-day Sales Submit; these records are not Final Submit types): not saved and
 * why, so the step shows the submit text. Any other failure: not saved, as before (the step keeps the form for another
 * Save). Never throws but for cancellation.
 */
internal suspend fun AmoDay.saveCallStep(write: suspend () -> Unit): StepSave =
    try {
        write()
        StepSave(true, null)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        StepSave(false, DayRefusal.of(e))
    }

/**
 * tso-manual X4a (fix round): which sale or memo edit a refused save belongs to, so only its own host shows the lock text:
 * the edited memo's uuid for an edit, else the call's visit uuid.
 */
internal fun refusalKey(draft: com.aktcl.aron.feature.sale.domain.SaleDraft): String = draft.edit?.supersedesMemoUuid ?: draft.visitUuid

/**
 * The lock text where a capture on a closed route was refused (a new control, joint or sale call, a sale save, Mark Paid,
 * a memo edit): the AMO banner's title and body with today's count of closed routes (at least the one refused), bn and en.
 */
@Composable
fun AmoDayLockDialog(day: AmoDay, onOk: () -> Unit) {
    val lock = day.dayLock.collectAsState().value
    LaunchedEffect(day) { day.refreshLock() }
    AmoDayLockDialogContent(closedRoutes = lock?.closedRouteIds?.size ?: 0, onOk = onOk)
}

/**
 * tso-manual X4a follow-up (R-W-23): the words of a capture refused because the day's sales are already submitted (the AMO's
 * supervisor-day Sales Submit stands): the Sales Submit title and feature-dayclose's `submit_blocked_done`, bn and en.
 */
@Composable
fun AmoDaySubmittedDialog(onOk: () -> Unit) {
    com.aktcl.aron.core.ui.aron.AronDialog(
        stringResource(com.aktcl.aron.feature.dayclose.R.string.submit_title), stringResource(com.aktcl.aron.feature.dayclose.R.string.submit_blocked_done),
        stringResource(AmoR.string.amo_ok), onConfirm = onOk, onDismiss = onOk,
    )
}

/** tso-manual X4a follow-up (R-W-23): the words of [why]: the lock text, or the submit text. */
@Composable
fun AmoRefusalDialog(day: AmoDay, why: DayRefusal, onOk: () -> Unit) {
    when (why) {
        DayRefusal.FINAL_SUBMIT -> AmoDayLockDialog(day, onOk)
        DayRefusal.SALES_SUBMIT -> AmoDaySubmittedDialog(onOk)
    }
}

/** What [AmoDayLockDialog] draws; [closedRoutes] below one counts as one (the route just refused). */
@Composable
fun AmoDayLockDialogContent(closedRoutes: Int, onOk: () -> Unit) {
    val (title, body) = AmoDayLockWords.refusal(closedRoutes, LocalContext.current.resources, LocalAppLanguage.current)
    com.aktcl.aron.core.ui.aron.AronDialog(title, body, stringResource(AmoR.string.amo_ok), onConfirm = onOk, onDismiss = onOk)
}

/**
 * tso-manual X4a (R-W-11, A3 ask 1): the words of the AMO day lock from the resources, outside a composition, so the Home
 * banner ([amoDayLockBanners]) and the refusal text ([AmoDayLockDialog]) are checked by a plain helper test. The strings
 * are A3's `amo_lock_*` (en and bn); counts in the language's digits.
 */
internal object AmoDayLockWords {
    /** The Home banner text for [lock]: the title (when a route is closed), then each line; null when nothing applies. */
    fun text(lock: AmoDayLock?, res: Resources, language: AppLanguage): String? {
        val l = lock ?: return null
        val n = { v: Int -> LocaleDigits.formatInteger(v.toLong(), language) }
        val closed = l.closedRouteIds.size
        return buildList {
            if (closed > 0) { add(res.getString(R.string.amo_lock_title)); add(res.getString(R.string.amo_lock_body, n(closed))) }
            if (l.acks.held > 0) add(res.getString(R.string.amo_lock_held, n(l.acks.held)))
            if (l.acks.refused > 0) add(res.getString(R.string.amo_lock_refused, n(l.acks.refused)))
        }.joinToString(separator = LINE_BREAK).ifEmpty { null }
    }

    /** The Home's banner list: one Warning banner with [text], or none while every route is open. */
    fun banners(text: String?): List<RoleBanner> = text?.let { listOf(RoleBanner(it, BannerKind.Warning)) }.orEmpty()

    /** The refusal's title and body: the lock title and body with today's closed routes, at least the one just refused. */
    fun refusal(closedRoutes: Int, res: Resources, language: AppLanguage): Pair<String, String> =
        res.getString(R.string.amo_lock_title) to res.getString(R.string.amo_lock_body, LocaleDigits.formatInteger(closedRoutes.coerceAtLeast(1).toLong(), language))
}

private const val LINE_BREAK = "\n"
