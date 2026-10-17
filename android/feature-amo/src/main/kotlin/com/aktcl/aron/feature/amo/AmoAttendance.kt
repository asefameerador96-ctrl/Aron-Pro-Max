package com.aktcl.aron.feature.amo

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.system.permission.GatedFeature
import com.aktcl.aron.core.system.permission.PermissionGate
import com.aktcl.aron.core.ui.AronScreen
import com.aktcl.aron.core.ui.AronTopBar
import com.aktcl.aron.feature.attendance.AttendanceContent
import com.aktcl.aron.feature.attendance.AttendanceState
import com.aktcl.aron.feature.home.R

/**
 * F-AMO-003: the AMO's attendance is the SR's screen ([AttendanceContent]: address, press-and-hold, four states, check-out
 * from the configured time, coordinates offline) in the AMO frame; nothing is copied. The flow behind it is built by the app.
 */
@Composable
fun AmoAttendance(
    state: AttendanceState,
    checkoutAtLabel: String,
    onCheckIn: () -> Unit,
    onCheckOut: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onRefreshFix: (() -> Unit)? = null,
) {
    AronScreen(modifier, topBar = { AronTopBar(stringResource(R.string.rh_amo_attendance), onBack = onBack) }) {
        PermissionGate(GatedFeature.ATTENDANCE, onBack = onBack) {
            AttendanceContent(state, checkoutAtLabel, onCheckIn, onCheckOut, onRefreshFix = onRefreshFix)
        }
    }
}
