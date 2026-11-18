package com.aktcl.aron.amo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.database.repo.AttendanceRefusal
import com.aktcl.aron.core.database.repo.DayRefusal
import kotlinx.coroutines.launch

/**
 * The AMO's check-in gate of an operational screen with its way out for an open call (D-AGATE-19, the SR's `SrCheckInGate` twin): "Close the open call" closes the call through
 * [AmoDay.closeVisit]; a close that cannot be written keeps the call and shows `att_close_failed` (no crash, no silent back), and a close the route-day refuses (the Final Submit, or the
 * supervisor day's Sales Submit) ends the call on the phone with nothing written and says so ([AmoRefusalDialog]) before [onCallClosed].
 */
@Composable
internal fun AmoCheckInGate(
    day: AmoDay?, why: AttendanceRefusal, canCloseCall: Boolean, earlierOutlet: String?, onCheckIn: () -> Unit, onCallClosed: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf<DayRefusal?>(null) }
    com.aktcl.aron.feature.attendance.CheckInFirst(
        why, onCheckIn = onCheckIn,
        onCloseCall = if (canCloseCall && day != null) {
            {
                scope.launch {
                    var refusal: DayRefusal? = null
                    val ok = try { refusal = day.closeVisit(); true } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Exception) { false }
                    if (!ok) failed = true else if (refusal != null) ended = refusal else onCallClosed()
                }
            }
        } else null,
        earlierCallOutlet = earlierOutlet,
    )
    ended?.let { r -> if (day != null) AmoRefusalDialog(day, r, onOk = { ended = null; onCallClosed() }) }
    if (failed) {
        com.aktcl.aron.core.ui.aron.AronDialog(
            stringResource(com.aktcl.aron.feature.attendance.R.string.att_close_failed_title), stringResource(com.aktcl.aron.feature.attendance.R.string.att_close_failed_body),
            stringResource(com.aktcl.aron.feature.attendance.R.string.att_gate_ok), onConfirm = { failed = false }, onDismiss = { failed = false },
        )
    }
}
