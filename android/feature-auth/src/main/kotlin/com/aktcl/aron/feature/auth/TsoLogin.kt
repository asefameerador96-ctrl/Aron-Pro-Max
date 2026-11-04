package com.aktcl.aron.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronBrandMark
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.aron.AronLanguageToggle
import com.aktcl.aron.core.ui.aron.NightButton
import com.aktcl.aron.core.ui.aron.NightButtonStyle
import com.aktcl.aron.core.ui.aron.NightGlowField
import com.aktcl.aron.core.ui.aron.nightRoles

/** Which login screen an app shows: the shared field-app login (SR, AMO) or the TSO screen of screenshot 5 (D-TSO-MAN-15). */
enum class LoginVariant { Standard, Tso }

/** Test tags of the TSO login only (the fields and the button keep [LoginTags]). */
object TsoLoginTags {
    const val PLATE = "login_tso_plate"
    const val EYE = "login_tso_eye"
    const val LANGUAGE = "login_tso_language"
}

/**
 * The TSO login (screenshot 5, D-TSO-MAN-15) in the TSO look: on the navy page, the round mint-to-pink logo plate, "Username" and
 * "Password" labels ABOVE glow fields (the placeholder repeats the label, as the screenshot), and the centred cyan-to-blue Login
 * button. Kept as small improvements: the password eye and, at the bottom, the language toggle (constraint 8: the language is chosen
 * before login). The sun switch and the version line live in Settings. No OTP step (D-AUTH-TSO-NOBIND, CR-1). The button stays
 * pressable with empty fields; pressing it then shows the empty-fields message (the screenshot draws it enabled). The refusal and
 * offline messages are the shared ones, on the same notice cards.
 */
@Composable
fun TsoLoginContent(
    state: LoginUiState,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisible: () -> Unit,
    onSubmit: () -> Unit,
    onLanguageSelect: (AppLanguage) -> Unit,
    onEnrol: (() -> Unit)? = null,
) {
    val n = nightRoles()
    val wrong = state.message == LoginMessage.InvalidCredentials || state.message == LoginMessage.OfflineWrongPassword
    Column(
        Modifier
            .fillMaxSize()
            .background(n.page)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        // the round plate: an opaque mint-to-pink disc (solid white in Sunlight), the logo inside it (AronBrandMark rules)
        Box(
            Modifier
                .size(176.dp)
                .clip(CircleShape)
                .background(if (n.solid) SolidColor(n.plateTop) else Brush.verticalGradient(listOf(n.plateTop, n.plateBottom)))
                .testTag(TsoLoginTags.PLATE),
            contentAlignment = Alignment.Center,
        ) { AronBrandMark(size = 128.dp) }
        Spacer(Modifier.height(56.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            val username = stringResource(R.string.auth_username)
            val password = stringResource(R.string.auth_password)
            NightGlowField(
                state.username, onUsernameChange, label = username, placeholder = username, enabled = !state.busy, isError = wrong,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                fieldModifier = Modifier.testTag(LoginTags.USERNAME),
            )
            NightGlowField(
                state.password, onPasswordChange, label = password, placeholder = password, enabled = !state.busy, isError = wrong,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (!state.busy) onSubmit() }),
                visualTransformation = if (state.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = {
                    IconButton(onClick = onTogglePasswordVisible, modifier = Modifier.testTag(TsoLoginTags.EYE)) {
                        Icon(
                            if (state.passwordVisible) AronIcons.VisibilityOff else AronIcons.Visibility,
                            contentDescription = stringResource(if (state.passwordVisible) R.string.auth_tso_password_hide else R.string.auth_tso_password_show),
                            tint = n.inkMuted,
                        )
                    }
                },
                fieldModifier = Modifier.testTag(LoginTags.PASSWORD),
            )
            state.message?.let { message ->
                LoginMessageCard(message, Modifier.testTag(LoginTags.MESSAGE))
                if (message == LoginMessage.DeviceNotEnrolled && onEnrol != null) {
                    AuthLink(stringResource(R.string.enrol_title), onEnrol, Modifier.fillMaxWidth().testTag(LoginTags.ENROL))
                }
            }
        }
        Spacer(Modifier.height(36.dp))
        NightButton(
            stringResource(if (state.busy) R.string.auth_logging_in else R.string.auth_tso_login), onSubmit,
            Modifier.fillMaxWidth(0.72f).testTag(LoginTags.SUBMIT), style = NightButtonStyle.Cyan, busy = state.busy,
        )
        Spacer(Modifier.height(48.dp))
        AronLanguageToggle(
            stringResource(com.aktcl.aron.core.ui.R.string.core_ui_language_bangla), stringResource(com.aktcl.aron.core.ui.R.string.core_ui_language_english),
            banglaSelected = LocalAppLanguage.current == AppLanguage.BN,
            onSelect = { bangla -> onLanguageSelect(if (bangla) AppLanguage.BN else AppLanguage.EN) },
            modifier = Modifier.padding(vertical = 16.dp).testTag(TsoLoginTags.LANGUAGE),
        )
    }
}
