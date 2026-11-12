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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AronTokens.Space.Screen),
        verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
    ) {
        AuthTopRow(onLanguageSelect, sunlight, onSunlight)
        Spacer(Modifier.height(AronTokens.Space.M))
        BrandBlock(appTitle)
        Spacer(Modifier.height(AronTokens.Space.S))
        AmoLoginField(
            stringResource(R.string.auth_username), state.username, onUsernameChange, LoginTags.USERNAME,
            enabled = !state.busy, error = wrong, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next,
        )
        AmoLoginField(
            stringResource(R.string.auth_password), state.password, onPasswordChange, LoginTags.PASSWORD,
            enabled = !state.busy, error = wrong, password = !state.passwordVisible, imeAction = ImeAction.Done,
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
) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(12.dp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = look.ink)
        BasicTextField(
            value = value, onValueChange = onValueChange, enabled = enabled, singleLine = true,
            textStyle = TextStyle(color = look.ink, fontSize = 16.sp),
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
                    Box(Modifier.weight(1f)) { inner() }
                    trailing?.invoke()
                }
            },
        )
    }
}
