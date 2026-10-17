package com.aktcl.aron.feature.tso.password

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronBarAction
import com.aktcl.aron.core.ui.AronBottomBar
import com.aktcl.aron.core.ui.AronScreen
import com.aktcl.aron.core.ui.AronTextButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.AronTopBar
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.feature.tso.R
import kotlinx.coroutines.launch

object CpTags {
    const val CURRENT = "tso_cp_current"
    const val NEW = "tso_cp_new"
    const val CONFIRM = "tso_cp_confirm"
    const val SUBMIT = "tso_cp_submit"
    const val DONE = "tso_cp_done"
    const val ERRORS = "tso_cp_errors"
    const val REFUSED = "tso_cp_refused"
}

@Composable
fun ChangePasswordScreen(state: ChangePasswordState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val ui by state.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val phase = ui.phase
    val transform = if (ui.visible) VisualTransformation.None else PasswordVisualTransformation()
    val kb = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
    AronScreen(
        modifier, topBar = { AronTopBar(stringResource(R.string.tso_cp_title), onBack = onBack) },
        bottomBar = { AronBottomBar(primary = if (phase is CpPhase.Done) AronBarAction(stringResource(R.string.tso_leave_done), onBack) else AronBarAction(stringResource(R.string.tso_cp_submit), { scope.launch { state.submit() } }, enabled = phase !is CpPhase.Sending, busy = phase is CpPhase.Sending), modifier = Modifier.testTag(CpTags.SUBMIT)) },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            if (phase is CpPhase.Done) AronBanner(stringResource(R.string.tso_cp_done), Modifier.testTag(CpTags.DONE), kind = BannerKind.Success)
            if (phase !is CpPhase.Done) {
                OutlinedTextField(ui.current, state::setCurrent, Modifier.fillMaxWidth().testTag(CpTags.CURRENT), singleLine = true, label = { Text(stringResource(R.string.tso_cp_current)) }, visualTransformation = transform, keyboardOptions = kb)
                OutlinedTextField(ui.new, state::setNew, Modifier.fillMaxWidth().testTag(CpTags.NEW), singleLine = true, label = { Text(stringResource(R.string.tso_cp_new)) }, visualTransformation = transform, keyboardOptions = kb, supportingText = { Text(stringResource(R.string.tso_cp_hint)) })
                OutlinedTextField(ui.confirm, state::setConfirm, Modifier.fillMaxWidth().testTag(CpTags.CONFIRM), singleLine = true, label = { Text(stringResource(R.string.tso_cp_confirm)) }, visualTransformation = transform, keyboardOptions = kb)
                AronTextButton(stringResource(if (ui.visible) R.string.tso_cp_hide else R.string.tso_cp_show), { state.toggleVisible() })
            }
            (phase as? CpPhase.Invalid)?.errors?.forEach { e ->
                AronBanner(stringResource(when (e) {
                    CpError.CURRENT_EMPTY -> R.string.tso_cp_err_current; CpError.NEW_SHORT -> R.string.tso_cp_err_short; CpError.NEW_LONG -> R.string.tso_cp_err_long
                    CpError.NEW_SAME -> R.string.tso_cp_err_same; CpError.MISMATCH -> R.string.tso_cp_err_mismatch }), Modifier.testTag(CpTags.ERRORS), kind = BannerKind.Error)
            }
            (phase as? CpPhase.Refused)?.let { r ->
                AronBanner(stringResource(when (r.why) {
                    CpOutcome.WrongCurrent -> R.string.tso_cp_wrong; CpOutcome.Policy -> R.string.tso_cp_policy; CpOutcome.RateLimited -> R.string.tso_cp_rate
                    CpOutcome.Offline -> R.string.tso_fs_offline; else -> R.string.tso_fs_server }), Modifier.testTag(CpTags.REFUSED), kind = BannerKind.Error)
            }
        }
    }
}
