package com.aktcl.aron.feature.attendance

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.database.repo.AttendanceRefusal
import com.aktcl.aron.core.ui.aron.AronDialog

/**
 * D-AGATE-15 (T4): a screen that READS before check-in but also WRITES (outlet requests and verification, task delegation, the AMO's SR stock correction, exceptions) refuses
 * every write AT THE BUTTON with the named reason (check in first / finish yesterday's call at <outlet>), before anything is read, claimed or saved, never after the fact with a
 * generic failure. [refused] is called by the button: true means "stop, the rep has been told why".
 */
class WriteGuard internal constructor(val why: AttendanceRefusal?, private val tell: () -> Unit) {
    fun refused(): Boolean { if (why == null) return false; tell(); return true }
}

val LocalWriteGuard = staticCompositionLocalOf<WriteGuard?> { null }

/** The guard of the current screen, or null when the host provides none (no gate). */
@Composable fun rememberWriteGuardOrOpen(): WriteGuard = LocalWriteGuard.current ?: remember { WriteGuard(null) {} }

/** Provides a [WriteGuard] for [why] (null = open) to [content] and shows the named reason when a write button is refused. */
@Composable
fun ProvideWriteGuard(why: AttendanceRefusal?, earlierCallOutlet: String?, onCheckIn: () -> Unit, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    val guard = remember(why) { WriteGuard(why) { shown = true } }
    CompositionLocalProvider(LocalWriteGuard provides guard) { content() }
    if (shown && why != null) {
        val earlier = why == AttendanceRefusal.EARLIER_CALL_OPEN
        val title = stringResource(when (why) { AttendanceRefusal.NOT_CHECKED_IN -> R.string.att_gate_title; AttendanceRefusal.CHECKED_OUT -> R.string.att_gate_out_title; AttendanceRefusal.EARLIER_CALL_OPEN -> R.string.att_gate_earlier_title })
        val body = if (earlier) { if (earlierCallOutlet != null) stringResource(R.string.att_gate_earlier_body_at, earlierCallOutlet) else stringResource(R.string.att_gate_earlier_body) }
        else stringResource(if (why == AttendanceRefusal.NOT_CHECKED_IN) R.string.att_gate_body else R.string.att_gate_out_body)
        if (why == AttendanceRefusal.NOT_CHECKED_IN) {
            AronDialog(title, body, stringResource(R.string.att_gate_go), onConfirm = { shown = false; onCheckIn() }, onDismiss = { shown = false })
        } else {
            AronDialog(title, body, stringResource(R.string.att_gate_ok), onConfirm = { shown = false }, onDismiss = { shown = false })
        }
    }
}
