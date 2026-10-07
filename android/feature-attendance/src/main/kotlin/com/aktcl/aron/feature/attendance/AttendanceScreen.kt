package com.aktcl.aron.feature.attendance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronCard
import com.aktcl.aron.core.ui.AronInfoDialog
import com.aktcl.aron.core.ui.AronPressAndHoldButton
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.localizedDigits
import java.time.Instant

object AttendanceTags {
    const val PROMPT = "att_prompt"
    const val CHECK_IN = "att_check_in"
    const val CHECK_OUT = "att_check_out"
    const val ADDRESS = "att_address"
    const val STATUS = "att_status"
}

/** `HH:mm` Asia/Dhaka (UTC+6) of an RFC 3339 instant; the screen shows corrected business time, never the raw clock. */
internal fun dhakaClock(iso: String): String = runCatching {
    val minutes = (Instant.parse(iso).epochSecond / 60 + 6 * 60).mod(24 * 60L)
    "%02d:%02d".format(java.util.Locale.ROOT, minutes / 60, minutes % 60)
}.getOrDefault(iso)

/**
 * Attendance (F-SR-011 check-in, F-SR-012 check-out). Stateless: the caller owns the flow. The check-in message is a
 * prompt, not a block (Q-UI-04); check-out is press-and-hold and enabled from 17:00 on corrected Dhaka time.
 */
@Composable
fun AttendanceContent(
    state: AttendanceState,
    checkoutAtLabel: String,
    onCheckIn: () -> Unit,
    onCheckOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDayComplete by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AronTokens.Space.L),
        verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
    ) {
        Text(stringResource(R.string.att_title), style = MaterialTheme.typography.headlineSmall)
        AronCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(AronTokens.Space.M), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                Text(stringResource(R.string.att_location), style = MaterialTheme.typography.labelLarge)
                Text(
                    state.addressText ?: stringResource(R.string.att_location_none),
                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(AttendanceTags.ADDRESS),
                )
                state.checkedInAt?.let {
                    Text(stringResource(R.string.att_checked_in, localizedDigits(dhakaClock(it))), Modifier.testTag(AttendanceTags.STATUS))
                }
                state.checkedOutAt?.let { Text(stringResource(R.string.att_checked_out, localizedDigits(dhakaClock(it)))) }
            }
        }
        if (state.lastFixMock) AronBanner(stringResource(R.string.att_mock_warning), kind = BannerKind.Error)
        if (state.showCheckInPrompt) {
            AronBanner(stringResource(R.string.att_prompt_checkin), Modifier.testTag(AttendanceTags.PROMPT), BannerKind.Info)
        }
        AronPrimaryButton(
            stringResource(R.string.att_check_in), onCheckIn,
            Modifier.testTag(AttendanceTags.CHECK_IN), enabled = state.checkInEnabled && !state.busy,
        )
        AronPressAndHoldButton(
            stringResource(R.string.att_check_out_hold),
            onConfirmed = { onCheckOut(); showDayComplete = true },
            Modifier.testTag(AttendanceTags.CHECK_OUT),
            enabled = state.checkOutEnabled && !state.busy,
        )
        if (state.checkedInAt != null && state.checkedOutAt == null && !state.checkOutEnabled) {
            Text(stringResource(R.string.att_check_out_hint, localizedDigits(checkoutAtLabel)), style = MaterialTheme.typography.bodyMedium)
        }
        Text(stringResource(R.string.att_offline_saved), style = MaterialTheme.typography.bodySmall)
    }
    if (showDayComplete && state.checkedOutAt != null) {
        AronInfoDialog(
            stringResource(R.string.att_day_complete_title), stringResource(R.string.att_day_complete_message),
            stringResource(R.string.att_ok),
        ) { showDayComplete = false }
    }
}
