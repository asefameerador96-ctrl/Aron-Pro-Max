package com.aktcl.aron.tso

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.feature.tso.TsoFab
import com.aktcl.aron.feature.tso.TsoFrame
import com.aktcl.aron.feature.tso.TsoNav
import com.aktcl.aron.feature.tso.TsoScreen
import com.aktcl.aron.feature.tso.TsoScreenContent
import com.aktcl.aron.feature.tso.deviceotp.DeviceOtpFetch
import com.aktcl.aron.feature.tso.deviceotp.DeviceOtpIssue
import com.aktcl.aron.feature.tso.deviceotp.TsoDeviceOtpContent
import com.aktcl.aron.feature.tso.deviceotp.TsoDeviceOtpModel
import com.aktcl.aron.feature.tso.deviceotp.TsoDeviceOtps
import kotlinx.coroutines.launch
import com.aktcl.aron.feature.tso.R as TsoR

/** The TSO's Device OTP model over [TsoApi]: reads and issues are online only and nothing is kept (a code is a secret). */
fun deviceOtpModel(api: TsoApi): TsoDeviceOtpModel = TsoDeviceOtpModel(
    read = { cursor ->
        when (val r = api.deviceOtps(cursor)) {
            is TsoFetch.Fresh -> DeviceOtpFetch.Fresh(r.json)
            TsoFetch.Gone -> DeviceOtpFetch.Refused
            TsoFetch.Unauthorized -> DeviceOtpFetch.SessionExpired
            else -> DeviceOtpFetch.Unavailable
        }
    },
    issue = { userId ->
        when (val r = api.issueDeviceOtp(userId, TsoDeviceOtps.issueBody(userId))) {
            is TsoApi.IssueOtp.Issued -> DeviceOtpIssue.Issued(r.json)
            TsoApi.IssueOtp.Rejected -> DeviceOtpIssue.Refused
            TsoApi.IssueOtp.Unauthorized -> DeviceOtpIssue.SessionExpired
            TsoApi.IssueOtp.NoAnswer -> DeviceOtpIssue.Offline
        }
    },
)

/** The screen: reads when it opens and on pull-to-refresh, issues on a tap; the model lives only while the screen is shown. */
@Composable
fun TsoDeviceOtpHost(api: TsoApi, nav: TsoNav) {
    com.aktcl.aron.core.ui.SecureScreen() // the codes are not in recents or screenshots (they are still read aloud: the row keeps its description)
    val model = remember(api) { deviceOtpModel(api) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(model) { model.refresh() }
    TsoFrame(stringResource(TsoR.string.tso_otp_title), nav, homeButton = TsoFab.of(TsoScreen.DEVICE_OTP)) {
        TsoDeviceOtpContent(
            model,
            onRefresh = { scope.launch { model.refresh() } },
            onIssue = { id -> scope.launch { model.issueFor(id) } },
            onMore = { scope.launch { model.more() } },
        )
    }
}

/** The Device OTP entry of the host map (reached from Settings). */
fun deviceOtpScreens(api: TsoApi): Map<TsoScreen, TsoScreenContent> =
    mapOf(TsoScreen.DEVICE_OTP to { nav, _ -> TsoDeviceOtpHost(api, nav) })
