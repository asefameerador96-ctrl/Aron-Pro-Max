package com.aktcl.aron.feature.attendance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.database.repo.AttendanceRefusal
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronScreen
import com.aktcl.aron.core.ui.AronTokens

/** Test tags of [CheckInFirst]. */
object CheckInFirstTags {
    const val ROOT = "checkin_first"
    const val GO = "checkin_first_go"
    const val CLOSE_CALL = "checkin_first_close_call"
}

/**
 * The UI mirror of the check-in gate (D-OWNER-PREREQ-01 rule 1): an operational screen (stock, sale, visit, outlet requests, tasks,
 * the AMO's calls) shows THIS instead of its content while the day has no open check-in, or after the check-out. The write layer
 * ([com.aktcl.aron.core.database.repo.AttendanceGate]) is the real gate; this makes sure the rep is told why and is never left on a
 * dead end: [onCheckIn] opens the attendance screen (hidden after check-out, when the day is over), and [onCloseCall] ends a call
 * that is still open without writing a sale (the close of an already open call is exempt from the gate).
 */
@Composable
fun CheckInFirst(
    why: AttendanceRefusal,
    onCheckIn: () -> Unit,
    modifier: Modifier = Modifier,
    onCloseCall: (() -> Unit)? = null,
    /** D-LEAD-PREREQ-04 ruling 4: the outlet of the earlier day's call that is still open, named in the reason (only for [AttendanceRefusal.EARLIER_CALL_OPEN]). */
    earlierCallOutlet: String? = null,
) {
    AronScreen(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AronTokens.Space.Screen).testTag(CheckInFirstTags.ROOT),
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val earlier = why == AttendanceRefusal.EARLIER_CALL_OPEN
            AronEmptyState(
                stringResource(when (why) { AttendanceRefusal.NOT_CHECKED_IN -> R.string.att_gate_title; AttendanceRefusal.CHECKED_OUT -> R.string.att_gate_out_title; AttendanceRefusal.EARLIER_CALL_OPEN -> R.string.att_gate_earlier_title }),
                hint = if (earlier) { if (earlierCallOutlet != null) stringResource(R.string.att_gate_earlier_body_at, earlierCallOutlet) else stringResource(R.string.att_gate_earlier_body) }
                else stringResource(if (why == AttendanceRefusal.NOT_CHECKED_IN) R.string.att_gate_body else R.string.att_gate_out_body),
                icon = AronIcons.Lock,
            )
            if (why == AttendanceRefusal.NOT_CHECKED_IN) {
                AronPrimaryButton(stringResource(R.string.att_gate_go), onCheckIn, Modifier.fillMaxWidth().testTag(CheckInFirstTags.GO))
            }
            if (onCloseCall != null) {
                AronPrimaryButton(stringResource(R.string.att_gate_close_call), onCloseCall, Modifier.fillMaxWidth().testTag(CheckInFirstTags.CLOSE_CALL))
            }
        }
    }
}

/**
 * What the phone did for the rep after Dhaka midnight, as ONE text in the app's language (D-LEAD-PREREQ-04 rulings 2 and 3): the day it closed for him, the earlier call it ended,
 * the unsaved sale draft it dropped; null when nothing happened. A host shows it as a banner until the rep has seen it.
 */
@Composable
fun rolloverNoticeText(n: RolloverNotice): String? {
    if (!n.any) return null
    val parts = ArrayList<String>()
    n.abandonedDate?.let { parts += stringResource(R.string.att_rollover_abandoned, com.aktcl.aron.core.ui.localizedDigits(it)) }
    if (n.editDropped) parts += stringResource(R.string.att_rollover_edit)
    if (n.draftDropped) parts += stringResource(R.string.att_rollover_draft)
    if (n.closedDates.isNotEmpty()) parts += stringResource(R.string.att_rollover_closed, n.closedDates.map { com.aktcl.aron.core.ui.localizedDigits(it) }.joinToString(", "))
    return parts.joinToString("\n")
}
