package com.aktcl.aron.feature.auth

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronCard
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronTextButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAronColors

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
 * (null hides the button). [sunlight] and [onSunlight] wire the sun switch of the top row.
 */
@Composable
fun EnrolmentScreen(
    viewModel: EnrolmentViewModel,
    appTitle: String,
    onEnrolled: () -> Unit,
    onSkip: () -> Unit,
    onLanguageSelect: (AppLanguage) -> Unit,
    scan: ((Context, (EnrolmentQrScanner.Result) -> Unit) -> Unit)? = EnrolmentQrScanner::scan,
    sunlight: Boolean = false,
    onSunlight: ((Boolean) -> Unit)? = null,
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
        sunlight = sunlight,
        onSunlight = onSunlight,
    )
}

/**
 * The enrolment frame (screens-sr s1 frame): the top row, the brand block, the card with the explanation, the banner
 * slot and the token field; the bar primary is "Scan QR" (or "Try again" while a token waits for the server) with
 * "Enrol" as the secondary; "Log in without enrolling" is a text button that is never disabled, so a slow network
 * never holds up the offline unlock at the start of a day.
 */
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
    sunlight: Boolean = false,
    onSunlight: ((Boolean) -> Unit)? = null,
) {
    val c = LocalAronColors.current
    ScreenWithBar(Modifier.fillMaxSize(), bar = {
        Column(Modifier.fillMaxWidth()) {
            val enrolText = stringResource(if (state.busy && !state.pending) R.string.enrol_busy else R.string.enrol_submit)
            when {
                state.pending -> AuthBar(
                    primaryText = stringResource(if (state.busy) R.string.enrol_busy else R.string.enrol_retry), onPrimary = onRetry, primaryTag = EnrolTags.RETRY,
                    enabled = !state.busy, busy = state.busy,
                    secondaryText = enrolText, onSecondary = onSubmit, secondaryTag = EnrolTags.SUBMIT, secondaryEnabled = !state.busy,
                )
                onScan != null -> AuthBar(
                    primaryText = stringResource(R.string.enrol_scan), onPrimary = onScan, primaryTag = EnrolTags.SCAN, enabled = !state.busy,
                    secondaryText = enrolText, onSecondary = onSubmit, secondaryTag = EnrolTags.SUBMIT, secondaryEnabled = !state.busy,
                )
                else -> AuthBar(primaryText = enrolText, onPrimary = onSubmit, primaryTag = EnrolTags.SUBMIT, enabled = !state.busy, busy = state.busy)
            }
        }
    }) {
        ScrollColumn(contentPadding = PaddingValues(horizontal = AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            AuthTopRow(onLanguageSelect, sunlight, onSunlight)
            Spacer(Modifier.height(AronTokens.Space.L))
            BrandBlock(appTitle)
            Spacer(Modifier.height(AronTokens.Space.L))
            AronCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.L)) {
                    Text(stringResource(R.string.enrol_title), style = MaterialTheme.typography.titleLarge, color = c.textPrimary)
                    Text(stringResource(R.string.enrol_explain), style = MaterialTheme.typography.bodyLarge, color = c.textSecondary)
                    state.message?.let { m ->
                        // A solid banner (docs/32 s2a): readable in sun, never colour alone.
                        AronBanner(
                            messageText(m), Modifier.testTag(EnrolTags.MESSAGE),
                            kind = when { m is EnrolMessage.Waiting && m.offline -> BannerKind.Offline; m is EnrolMessage.Waiting -> BannerKind.Warning; else -> BannerKind.Error },
                        )
                    }
                    AuthField(
                        value = state.input, onValueChange = onInput, label = stringResource(R.string.enrol_token_label),
                        modifier = Modifier.testTag(EnrolTags.INPUT), enabled = !state.busy, singleLine = false, minLines = 2,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    )
                    if (state.pending && onScan != null) {
                        AronSecondaryButton(stringResource(R.string.enrol_scan), onScan, Modifier.fillMaxWidth().testTag(EnrolTags.SCAN), enabled = !state.busy)
                    }
                }
            }
            // Never disabled: a slow network must not hold up the offline unlock at the start of a day.
            AronTextButton(stringResource(R.string.enrol_skip), onSkip, Modifier.fillMaxWidth().testTag(EnrolTags.SKIP))
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
 * [sunlight] and [onSunlight] reach the enrolment screen's sun switch (the login screen takes its own through [login]).
 */
@Composable
fun EnrolmentGate(
    status: () -> EnrolStatus,
    submit: suspend (String?) -> com.aktcl.aron.core.session.EnrolmentOutcome,
    appTitle: String,
    onLanguageSelect: (AppLanguage) -> Unit,
    sunlight: Boolean = false,
    onSunlight: ((Boolean) -> Unit)? = null,
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
            sunlight = sunlight, onSunlight = onSunlight,
        )
    } else {
        login(if (!s.enrolled) ({ current = null; epoch += 1; show = true }) else null)
    }
}
