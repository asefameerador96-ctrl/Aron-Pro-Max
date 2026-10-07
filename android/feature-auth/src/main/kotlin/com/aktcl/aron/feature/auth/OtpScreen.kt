package com.aktcl.aron.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.localizedDigits

object OtpTags {
    const val BOXES = "otp_boxes"
    const val VERIFY = "otp_verify"
    const val MESSAGE = "otp_message"
}

/** Device OTP prompt (F-SR-002): four digits, Verify, and the wrong / expired / too-many-attempts texts. */
@Composable
fun OtpContent(state: OtpState, onDigits: (String) -> Unit, onVerify: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
        Text(stringResource(R.string.otp_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.otp_hint))
        OutlinedTextField(
            value = localizedDigits(state.digits), onValueChange = onDigits, modifier = Modifier.fillMaxWidth().testTag(OtpTags.BOXES),
            singleLine = true, enabled = !state.bound, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        )
        state.error?.let {
            AronBanner(
                stringResource(when (it) {
                    OtpError.INVALID -> R.string.otp_invalid
                    OtpError.EXPIRED -> R.string.otp_expired
                    OtpError.ATTEMPTS_EXCEEDED -> R.string.otp_attempts
                    OtpError.LOCKED -> R.string.otp_locked
                    OtpError.OFFLINE -> R.string.otp_offline
                    OtpError.OTHER -> R.string.otp_other
                }),
                Modifier.testTag(OtpTags.MESSAGE), BannerKind.Error,
            )
        }
        if (state.bound) AronBanner(stringResource(R.string.otp_bound), kind = BannerKind.Info)
        AronPrimaryButton(stringResource(R.string.otp_verify), onVerify, Modifier.testTag(OtpTags.VERIFY), enabled = state.canVerify)
    }
}
