package com.aktcl.aron.feature.tso.leave

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aktcl.aron.feature.tso.R
import com.aktcl.aron.feature.tso.RetainedCounter
import com.aktcl.aron.feature.tso.rememberRetained

/**
 * Leave (F-TSO-008/009, D-TSO-MAN-33): the list and the apply form. After Apply the application is queued on the phone and
 * the flow returns to the list with a toast; the epoch makes a fresh list state (it shows the new row as not sent yet) and a
 * fresh form with a new uuid next time. The states survive recreation; every retained key carries [userKey], so a shared
 * phone never hands one rep's state to the next. A null [queue] hides the Apply button. System back and the on-screen back
 * do the same thing (form to list, list to [onBack]).
 */
@Composable
fun LeaveFlow(source: LeaveSource, queue: LeaveQueue?, userKey: String, today: () -> String, onBack: () -> Unit) {
    var applying by rememberSaveable { mutableStateOf(false) }
    val counter = rememberRetained("tso-$userKey-leave-epoch") { RetainedCounter() }
    val epoch = counter.n
    val state = rememberRetained("tso-$userKey-leave-list-$epoch") { LeaveListState(source) }
    BackHandler(enabled = applying) { applying = false; counter.n++ }
    if (applying && queue != null) {
        val apply = rememberRetained("tso-$userKey-leave-apply-$epoch") {
            LeaveApplyState(queue, today, types = { source.leaveTypes() }, window = { source.window() })
        }
        val result by apply.result.collectAsStateWithLifecycle()
        val context = LocalContext.current
        val toast = stringResource(R.string.tso_leave_applied_toast)
        LaunchedEffect(result) {
            if (result is LeaveApplyResult.Queued) {
                Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
                applying = false; counter.n++
            }
        }
        LeaveApplyScreen(apply, onBack = { applying = false; counter.n++ })
    } else {
        LeaveListScreen(state, onBack = onBack, onApply = if (queue != null) ({ applying = true }) else null)
    }
}
