package com.aktcl.aron.feature.tso.deviceotp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.aron.NightButton
import com.aktcl.aron.core.ui.aron.NightButtonStyle
import com.aktcl.aron.core.ui.aron.NightRowCard
import com.aktcl.aron.core.ui.aron.nightRoles
import com.aktcl.aron.core.ui.aron.nightText
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.feature.tso.R
import com.aktcl.aron.feature.tso.TsoDashboards
import com.aktcl.aron.feature.tso.TsoLoading
import com.aktcl.aron.feature.tso.TsoNightNote

/** Test tags of the TSO Device OTP screen. */
object TsoDeviceOtpTags {
    const val LIST = "tso_otp_list"
    const val NOTE = "tso_otp_note"
    const val PROBLEM = "tso_otp_problem"
    const val MORE = "tso_otp_more"
    fun row(userId: Long) = "tso_otp_row_$userId"
    fun code(userId: Long) = "tso_otp_code_$userId"
    fun issue(userId: Long) = "tso_otp_issue_$userId"
}

/**
 * The TSO's Device OTP screen (SR manual 04 "provided by your TSO"; contract `listDeviceOtps`, `issueDeviceOtp`): the reps of the TSO's
 * own zones (the server derives them from the token) with the 4-digit code each is waiting for, to read out to the rep; a rep without a
 * live code gets an "Issue OTP" button. Online only, and nothing is kept on the phone: the codes are secrets. Each page view is audited
 * by the server. A failed refresh keeps the rows on screen and says why.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TsoDeviceOtpContent(model: TsoDeviceOtpModel, onRefresh: () -> Unit, onIssue: (Long) -> Unit, onMore: () -> Unit, modifier: Modifier = Modifier) {
    val n = nightRoles()
    val rows = model.rows
    PullToRefreshBox(model.loading && rows != null, onRefresh, modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag(TsoDeviceOtpTags.LIST),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "note") { TsoNightNote(stringResource(R.string.tso_otp_note), Modifier.testTag(TsoDeviceOtpTags.NOTE)) }
            model.problem?.let { p ->
                item(key = "problem") {
                    TsoNightNote(
                        stringResource(when (p) {
                            DeviceOtpProblem.OFFLINE -> R.string.tso_otp_offline
                            DeviceOtpProblem.REFUSED -> R.string.tso_otp_refused
                            DeviceOtpProblem.UNREADABLE -> R.string.tso_otp_unreadable
                            DeviceOtpProblem.ISSUE_OFFLINE -> R.string.tso_otp_issue_offline
                            DeviceOtpProblem.ISSUE_REFUSED -> R.string.tso_otp_issue_refused
                        }),
                        Modifier.testTag(TsoDeviceOtpTags.PROBLEM),
                    )
                }
            }
            when {
                rows == null && model.problem == null -> item(key = "loading") { TsoLoading(Modifier.heightIn(min = 160.dp)) }
                rows != null && rows.isEmpty() && model.problem == null -> item(key = "empty") { TsoNightNote(stringResource(R.string.tso_otp_empty)) }
                rows != null -> items(rows, key = { it.userId }) { r -> OtpRow(r, model.issuing, onIssue) }
            }
            if (model.nextCursor != null) item(key = "more") {
                NightButton(stringResource(R.string.tso_otp_more), onMore, Modifier.testTag(TsoDeviceOtpTags.MORE), style = NightButtonStyle.Blue, busy = model.loading)
            }
        }
    }
}

@Composable
private fun OtpRow(r: DeviceOtpRow, issuing: Long?, onIssue: (Long) -> Unit) {
    val n = nightRoles()
    val who = listOfNotNull(r.username, r.employeeCode, r.zoneName).joinToString(" · ")
    val until = r.otp?.let { TsoDashboards.asOfClock(r.expiresAt) }
    val described = stringResource(
        if (r.otp != null) R.string.tso_otp_row_desc_code else R.string.tso_otp_row_desc_none, r.fullName,
        r.otp?.toList()?.joinToString(" ") ?: "",
    )
    NightRowCard(Modifier.semantics(mergeDescendants = true) { contentDescription = described }.testTag(TsoDeviceOtpTags.row(r.userId))) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(r.fullName, style = nightText(17.sp, n.ink, semibold = true))
            Text(who, style = nightText(14.sp, n.inkMuted))
            r.deviceModel?.takeIf { it.isNotBlank() }?.let { Text(it, style = nightText(13.sp, n.inkMuted)) }
            if (r.otp != null) {
                // Latin digits on purpose: the rep types them on the OTP screen; spaced so they are read out clearly
                Text(r.otp, style = nightText(36.sp, n.figure, bold = true, align = TextAlign.Center), modifier = Modifier.fillMaxWidth().testTag(TsoDeviceOtpTags.code(r.userId)))
                if (until != null) Text(stringResource(R.string.tso_otp_valid_until, localizedDigits(until)), style = nightText(13.sp, n.inkMuted), modifier = Modifier.fillMaxWidth())
            } else {
                NightButton(
                    stringResource(R.string.tso_otp_issue), { onIssue(r.userId) }, Modifier.testTag(TsoDeviceOtpTags.issue(r.userId)),
                    style = NightButtonStyle.Blue, busy = issuing == r.userId, enabled = issuing == null,
                )
            }
        }
    }
}
