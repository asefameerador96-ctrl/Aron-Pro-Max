package com.aktcl.aron.feature.tso.leave

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.aktcl.aron.feature.tso.RetainedCounter
import com.aktcl.aron.feature.tso.rememberRetained

/**
 * Leave (F-TSO-008/009): the list and the apply form, moved out of TsoApp by a1-stubs with the behaviour unchanged. The
 * epoch makes a fresh list state and form after an application; the states themselves survive recreation. Every retained
 * key carries [userKey]: a shared phone never hands one rep's state to the next. A null [queue] hides the Apply button.
 */
@Composable
fun LeaveFlow(source: LeaveSource, queue: LeaveQueue?, userKey: String, today: () -> String, onBack: () -> Unit) {
    var applying by rememberSaveable { mutableStateOf(false) }
    val counter = rememberRetained("tso-$userKey-leave-epoch") { RetainedCounter() }
    val epoch = counter.n
    val state = rememberRetained("tso-$userKey-leave-list-$epoch") { LeaveListState(source) }
    BackHandler(enabled = applying) { applying = false; counter.n++ }
    if (applying && queue != null) {
        val apply = rememberRetained("tso-$userKey-leave-apply-$epoch") { LeaveApplyState(queue, today) }
        LeaveApplyScreen(apply, onBack = { applying = false; counter.n++ })
    } else {
        LeaveListScreen(state, onBack = onBack, onApply = if (queue != null) ({ applying = true }) else null)
    }
}
