package com.aktcl.aron.feature.auth

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LanguageToggle
import com.aktcl.aron.core.ui.LocalAppLanguage

/** Test tags of the enrolment screen. */
object EnrolTags {
    const val INPUT = "enrol_input"
    const val SCAN = "enrol_scan"
    const val SUBMIT = "enrol_submit"
    const val RETRY = "enrol_retry"
    const val SKIP = "enrol_skip"
    const val MESSAGE = "enrol_message"
}

/**
 * Enrol this phone (docs/24 s10.4), shown instead of login while the phone is not enrolled. [onEnrolled] leads to login;
 * [onSkip] goes to login without enrolling (offline unlock of a known user, or a dev server without the gate): the server
 * still refuses an online login from an unknown phone, so skipping never weakens the gate. [scan] opens the QR scanner
 * (null hides the button).
 */
@Composable
fun EnrolmentScreen(
    viewModel: EnrolmentViewModel,
    appTitle: String,
    onEnrolled: () -> Unit,
    onSkip: () -> Unit,
    onLanguageSelect: (AppLanguage) -> Unit,
    scan: ((Context, (EnrolmentQrScanner.Result) -> Unit) -> Unit)? = EnrolmentQrScanner::scan,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.enrolled) { if (state.enrolled) onEnrolled() }
    val context = LocalContext.current
    EnrolmentContent(
        state = state,
        appTitle = appTitle,
        onInput = viewModel::onInput,
        onSubmit = viewModel::onSubmit,
        onRetry = viewModel::onRetry,
        onScan = scan?.let { s ->
            {
                s(context) { r ->
                    when (r) {
                        is EnrolmentQrScanner.Result.Text -> viewModel.onScanned(r.value)
                        EnrolmentQrScanner.Result.Cancelled -> Unit
                        EnrolmentQrScanner.Result.Unavailable -> viewModel.onScanUnavailable()
                    }
                }
            }
        },
        onSkip = onSkip,
        onLanguageSelect = onLanguageSelect,
    )
}

@Composable
fun EnrolmentContent(
    state: EnrolUiState,
    appTitle: String,
    onInput: (String) -> Unit,
    onSubmit: () -> Unit,
    onRetry: () -> Unit,
    onScan: (() -> Unit)?,
    onSkip: () -> Unit,
    onLanguageSelect: (AppLanguage) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            LanguageToggle(current = LocalAppLanguage.current, onSelect = onLanguageSelect)
        }
        Spacer(Modifier.height(32.dp))
        Text(text = appTitle, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(text = stringResource(R.string.enrol_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Text(text = stringResource(R.string.enrol_explain), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))
        state.message?.let { m ->
            // A solid banner (docs/32 s2a): readable in sun, never colour alone.
            AronBanner(messageText(m), Modifier.testTag(EnrolTags.MESSAGE), kind = if (m is EnrolMessage.Waiting) BannerKind.Warning else BannerKind.Error)
            Spacer(Modifier.height(12.dp))
        }
        if (state.pending) {
            AronPrimaryButton(
                text = stringResource(if (state.busy) R.string.enrol_busy else R.string.enrol_retry),
                onClick = onRetry,
                enabled = !state.busy,
                modifier = Modifier.testTag(EnrolTags.RETRY),
            )
            Spacer(Modifier.height(20.dp))
        }
        if (onScan != null) {
            AronPrimaryButton(
                text = stringResource(R.string.enrol_scan),
                onClick = onScan,
                enabled = !state.busy,
                modifier = Modifier.testTag(EnrolTags.SCAN),
            )
            Spacer(Modifier.height(16.dp))
        }
        OutlinedTextField(
            value = state.input,
            onValueChange = onInput,
            label = { Text(stringResource(R.string.enrol_token_label)) },
            enabled = !state.busy,
            minLines = 2,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().testTag(EnrolTags.INPUT),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onSubmit, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(48.dp).testTag(EnrolTags.SUBMIT)) {
            Text(stringResource(if (state.busy && !state.pending) R.string.enrol_busy else R.string.enrol_submit))
        }
        Spacer(Modifier.height(24.dp))
        // Never disabled: a slow network must not hold up the offline unlock at the start of a day.
        TextButton(onClick = onSkip, modifier = Modifier.testTag(EnrolTags.SKIP)) {
            Text(stringResource(R.string.enrol_skip))
        }
    }
}

@Composable
private fun messageText(m: EnrolMessage): String = when (m) {
    EnrolMessage.Empty -> stringResource(R.string.enrol_error_empty)
    EnrolMessage.Unreadable -> stringResource(R.string.enrol_error_unreadable)
    EnrolMessage.ScanUnavailable -> stringResource(R.string.enrol_scan_unavailable)
    is EnrolMessage.Waiting -> stringResource(if (m.offline) R.string.enrol_waiting_offline else R.string.enrol_waiting_server)
    is EnrolMessage.Refused -> when (m.code) {
        ProblemCode.ERR_ENROLMENT_TOKEN_EXPIRED.wire -> stringResource(R.string.enrol_error_expired)
        ProblemCode.ERR_ENROLMENT_TOKEN_EXHAUSTED.wire -> stringResource(R.string.enrol_error_used)
        ProblemCode.ERR_ENROLMENT_TOKEN_INVALID.wire -> stringResource(R.string.enrol_error_invalid)
        ProblemCode.ERR_ENROLMENT_ATTESTATION_FAILED.wire -> stringResource(R.string.enrol_error_attestation)
        else -> stringResource(R.string.enrol_error_refused, m.code)
    }
}

/** Where enrolment stands, read off the main thread when the logged-out screens open. */
data class EnrolStatus(val enrolled: Boolean, val pending: Boolean, val refusedCode: String?)

/**
 * The logged-out entry of every field app: the enrolment screen while the phone is not enrolled (a new install), then
 * [login]. [login] gets an "enrol this phone" action while the phone is still not enrolled (after a skip), null after.
 */
@Composable
fun EnrolmentGate(
    status: () -> EnrolStatus,
    submit: suspend (String?) -> com.aktcl.aron.core.session.EnrolmentOutcome,
    appTitle: String,
    onLanguageSelect: (AppLanguage) -> Unit,
    login: @Composable (onEnrol: (() -> Unit)?) -> Unit,
) {
    var show by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<Boolean?>(null) }
    // Bumped when the screen is reopened from login: the status is read again and the screen state starts fresh.
    var epoch by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    var current by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<EnrolStatus?>(null) }
    LaunchedEffect(epoch) {
        // An unreadable state counts as not enrolled: the screen (with its skip) is shown rather than hiding enrolment.
        val s = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching(status).getOrNull() } ?: EnrolStatus(false, false, null)
        current = s
        if (show == null) show = !s.enrolled
    }
    val s = current ?: return // a few ms: the background is the splash
    if (show == true) {
        val vm = androidx.lifecycle.viewmodel.compose.viewModel(key = "enrolment-$epoch") { EnrolmentViewModel(submit, s.pending, s.refusedCode) }
        EnrolmentScreen(
            vm, appTitle,
            onEnrolled = { current = s.copy(enrolled = true, pending = false); show = false },
            onSkip = { show = false },
            onLanguageSelect = onLanguageSelect,
        )
    } else {
        login(if (!s.enrolled) ({ current = null; epoch += 1; show = true }) else null)
    }
}
