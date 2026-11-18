package com.aktcl.aron.feature.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.barBrush

/**
 * The AMO login (screenshot 03, owner device walk of build 1860): the brand block, then "Username" and "Password" labels ABOVE grey rounded
 * boxes with a thin purple outline, the "Login" pill straight under the fields (not in a bottom bar), and the footer line. Like the vendor
 * screen the pill stays pressable with empty fields; pressing it then shows the empty-fields message. The sun switch and language toggle
 * stay in the top row (constraint 8); the refusal and offline messages are the shared notice cards. [footerText] is shown verbatim.
 */
@Composable
fun AmoLoginContent(
    state: LoginUiState,
    appTitle: String,
    versionName: String,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisible: () -> Unit,
    onSubmit: () -> Unit,
    onLanguageSelect: (AppLanguage) -> Unit,
    onEnrol: (() -> Unit)? = null,
    sunlight: Boolean = false,
    onSunlight: ((Boolean) -> Unit)? = null,
    footerText: String? = null,
) {
    val look = LocalAronLook.current
    val wrong = state.message == LoginMessage.InvalidCredentials || state.message == LoginMessage.OfflineWrongPassword
    Column(
        Modifier
            .fillMaxSize()
            .background(look.pageHome)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .imePadding()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
    ) {
        Box(Modifier.padding(horizontal = AronTokens.Space.Screen)) { AuthTopRow(onLanguageSelect, sunlight, onSunlight) }
        // screenshot 03: the full-width diagonal header (red at the lower left to deep purple at the upper right, a darker band along the cut) with the oval logo
        AmoLoginHeader(appTitle)
        Column(Modifier.padding(horizontal = AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
        AmoLoginField(
            stringResource(R.string.auth_username), state.username, onUsernameChange, LoginTags.USERNAME,
            enabled = !state.busy, error = wrong, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next, gradientOutline = true, centered = true,
        )
        AmoLoginField(
            stringResource(R.string.auth_password), state.password, onPasswordChange, LoginTags.PASSWORD,
            enabled = !state.busy, error = wrong, password = !state.passwordVisible, imeAction = ImeAction.Done, gradientOutline = true, centered = true,
            onImeAction = { if (!state.busy) onSubmit() },
            trailing = { AuthLink(stringResource(if (state.passwordVisible) R.string.auth_password_hide else R.string.auth_password_show), onTogglePasswordVisible) },
        )
        state.message?.let { message ->
            LoginMessageCard(message, Modifier.testTag(LoginTags.MESSAGE))
            if (message == LoginMessage.DeviceNotEnrolled && onEnrol != null) {
                AuthLink(stringResource(R.string.enrol_title), onEnrol, Modifier.fillMaxWidth().testTag(LoginTags.ENROL))
            }
        }
        // the pill: the bar ramp (red to purple), white bold label; busy keeps the label and holds the press
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(50))
                .background(look.barBrush())
                .clickable(enabled = !state.busy, role = Role.Button, onClick = onSubmit)
                .testTag(LoginTags.SUBMIT),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(if (state.busy) R.string.auth_logging_in else R.string.auth_tso_login),
                style = TextStyle(color = look.onBar, fontSize = 18.sp, fontWeight = FontWeight.Bold), textAlign = TextAlign.Center,
            )
        }
        AuthFooter(footerText ?: com.aktcl.aron.core.ui.localizedDigits(stringResource(R.string.auth_version, versionName)), Modifier.testTag(LoginTags.VERSION))
        }
    }
}

/**
 * The AMO login header (screenshot 03): a full-width area cut by a diagonal that rises from the lower left to the upper right, filled with the bar ramp
 * (red, plum, deep purple), a thin darker indigo band along the cut, and the logo on a pale mint-to-pink oval. It carries no text, so no contrast
 * is asked of it; the app name is the accessible name of the logo.
 */
@Composable
private fun AmoLoginHeader(appTitle: String) {
    val look = LocalAronLook.current
    val ramp = look.barBrush()
    val band = look.purple
    Box(Modifier.fillMaxWidth().height(AmoHeaderHeight).testTag(LoginTags.HEADER), contentAlignment = Alignment.TopCenter) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val cut = androidx.compose.ui.graphics.Path().apply { moveTo(0f, 0f); lineTo(w, 0f); lineTo(w, h * 0.62f); lineTo(0f, h); close() }
            val strip = androidx.compose.ui.graphics.Path().apply { moveTo(0f, h * 0.9f); lineTo(w, h * 0.52f); lineTo(w, h * 0.62f); lineTo(0f, h); close() }
            val flipped = androidx.compose.ui.graphics.Brush.linearGradient(listOf(look.barStart, look.barMid, look.barEnd), start = androidx.compose.ui.geometry.Offset(0f, h), end = androidx.compose.ui.geometry.Offset(w, 0f))
            drawPath(cut, flipped)
            drawPath(strip, androidx.compose.ui.graphics.SolidColor(band))
        }
        Box(
            Modifier.padding(top = AronTokens.Space.L).size(width = 96.dp, height = 84.dp).clip(RoundedCornerShape(50))
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFD7F5D0), Color(0xFFFBDDE0)))).semantics { contentDescription = appTitle },
            contentAlignment = Alignment.Center,
        ) { com.aktcl.aron.core.ui.AronBrandMark(size = 56.dp) }
    }
}

private val AmoHeaderHeight = 200.dp

/** A label above a grey rounded box with a thin outline (red when [error]); the tag goes on the editable node, [label] is its accessible name. */
@Composable
internal fun AmoLoginField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    tag: String,
    enabled: Boolean,
    error: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: (() -> Unit)? = null,
    password: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    /** The SR login (manual screenshot 03): the thin outline is the red-to-purple bar ramp instead of plain purple. */
    gradientOutline: Boolean = false,
    /** The AMO login (screenshot 03): the placeholder (the label) and the typed text are centred in the box; false keeps the left-aligned SR and TSO field. */
    centered: Boolean = false,
) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(12.dp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = look.ink)
        BasicTextField(
            value = value, onValueChange = onValueChange, enabled = enabled, singleLine = true,
            textStyle = TextStyle(color = look.ink, fontSize = 16.sp, textAlign = if (centered) TextAlign.Center else TextAlign.Start),
            cursorBrush = SolidColor(look.purple),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(onDone = { onImeAction?.invoke() }),
            modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = label },
            decorationBox = { inner ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape).background(look.page)
                        .border(if (error) BorderStroke(1.5.dp, look.red) else if (gradientOutline) BorderStroke(1.5.dp, look.barBrush()) else BorderStroke(1.5.dp, look.purple), shape).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f), contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart) {
                        if (centered && value.isEmpty()) Text(label, style = TextStyle(color = look.inkMuted, fontSize = 16.sp, textAlign = TextAlign.Center))
                        inner()
                    }
                    trailing?.invoke()
                }
            },
        )
    }
}
