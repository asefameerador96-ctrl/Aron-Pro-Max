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
 * F-AMO-003 / amo-shell A1 (D-AMO-MAN-03, R7): the AMO's attendance is the SR's screen ([AttendanceContent]: address, press-and-hold, four states, check-out
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
    /** D-NIGHT-CHECKOUT-01: the "Sales Submit first" link. */
    onOpenSalesSubmit: (() -> Unit)? = null,
    offline: Boolean = false,
    /** The user card of screenshots 14 to 16: "<name> (<username>)" over "<zone> AMO, <date>" (see [AmoHomeHeader]); null leaves it out. */
    userTitle: String? = null,
    userSubtitle: String? = null,
) {
    AmoFrame(stringResource(R.string.rh_amo_attendance), onBack, modifier, icon = com.aktcl.aron.core.ui.R.drawable.aron_art_attendance) {
        PermissionGate(GatedFeature.ATTENDANCE, onBack = onBack) {
            AttendanceContent(
                state, checkoutAtLabel, onCheckIn, onCheckOut, onRefreshFix = onRefreshFix, onOpenSalesSubmit = onOpenSalesSubmit, offline = offline,
                userTitle = userTitle, userSubtitle = userSubtitle, amoStyle = true,
            )
        }
    }
}
