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
 */
internal object AmoLockGate {
    /**
     * The sale's Save and Print with the lock named: a commit refused by the lock calls [onRefused] (the kit keeps which
     * sale or edit was refused) and is rethrown, so [SaveAndPrint] stops at SaveFailed before the print step (nothing
     * prints); the host of that sale or edit shows the lock text in its place.
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
     * Closes the open call of [session] with [write]. When the lock lands between the caller's check ([AmoCallLock]) and the
     * write, the refusal ends the call on the phone: no close is queued and nothing throws. True when a close was queued.
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

    /** A skip ([write] queues its `visit_skip`): true when queued, false when the lock refused it (nothing stored). */
    suspend fun skip(write: suspend () -> Unit): Boolean =
        try {
            write()
            true
        } catch (_: RouteDayFinalSubmittedException) {
            false
        }
}

/**
 * tso-manual X4a (R-W-11, A3 ask 11, fix round): the outlet pick of a control, joint or sale call ([kind]). False when
 * [outlet]'s route is closed by its zone's Final Submit: no fix is read, the day takes no route and nothing is stored; the
 * host shows the lock text. Otherwise the day takes that route and the visit flow opens the call (it reads the fix and
 * commits; its state says how that went).
 */
internal suspend fun AmoDay.openCall(outlet: com.aktcl.aron.core.database.entity.OutletEntity, kind: String): Boolean {
    if (routeLocked(outlet.routeId)) return false
    routeId = outlet.routeId
    runCatching { visitFlow.open(com.aktcl.aron.feature.amo.ControlCall.visitOutlet(outlet, kind)) }
    return true
}

/**
 * tso-manual X4a (R-W-11, fix round): true when [state] is a call commit the Final Submit lock refused (the lock landed while
 * the fix was read; nothing was stored). The check step shows the lock text, not the generic retry.
 */
internal suspend fun AmoDay.refusedByLock(state: com.aktcl.aron.feature.outlet.VisitUiState?): Boolean =
    (state as? com.aktcl.aron.feature.outlet.VisitUiState.CommitFailed)?.let { routeLocked(it.outlet.routeId) } ?: false

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
