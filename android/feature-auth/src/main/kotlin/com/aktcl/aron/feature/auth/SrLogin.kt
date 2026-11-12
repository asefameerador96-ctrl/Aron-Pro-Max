package com.aktcl.aron.feature.auth

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronBrandPlate
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.barBrush

/** Test tags of the SR login only (the fields and the button keep [LoginTags]). */
object SrLoginTags {
    const val HERO = "login_sr_hero"
    const val LOGO = "login_sr_logo"
}

/**
 * The SR login of the owner's manual (screenshot 03, SRM-A-03): a red-to-purple diagonal hero with an indigo stripe and the round logo,
 * then "ইউজারনেম" and "পাসওয়ার্ড" labels above off-white fields with a thin red-to-purple outline, the full-width gradient "লগইন"
 * pill, and the footer "SR App / (version - x)". The vendor credit line of the screenshot is not shown (AKTCL owns the product). Kept
 * from the shared login: the language toggle and the sun switch (top row over the hero, constraint 8), the password show/hide link, and
 * the shared notice cards. The pill stays pressable with empty fields and then shows the empty-fields message, as the vendor screen.
 * Sunlight draws the hero solid.
 */
@Composable
fun SrLoginContent(
    state: LoginUiState,
    versionName: String,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisible: () -> Unit,
    onSubmit: () -> Unit,
    onLanguageSelect: (AppLanguage) -> Unit,
    onEnrol: (() -> Unit)? = null,
    sunlight: Boolean = false,
    onSunlight: ((Boolean) -> Unit)? = null,
) {
    val look = LocalAronLook.current
    val wrong = state.message == LoginMessage.InvalidCredentials || state.message == LoginMessage.OfflineWrongPassword
    Column(
        Modifier
            .fillMaxSize()
            .background(look.page)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .imePadding()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
    ) {
        SrLoginHero(onLanguageSelect, sunlight, onSunlight)
        Column(Modifier.padding(horizontal = AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            AmoLoginField(
                stringResource(R.string.auth_username), state.username, onUsernameChange, LoginTags.USERNAME,
                enabled = !state.busy, error = wrong, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next, gradientOutline = true,
            )
            AmoLoginField(
                stringResource(R.string.auth_password), state.password, onPasswordChange, LoginTags.PASSWORD,
                enabled = !state.busy, error = wrong, password = !state.passwordVisible, imeAction = ImeAction.Done,
                onImeAction = { if (!state.busy) onSubmit() }, gradientOutline = true,
                trailing = { AuthLink(stringResource(if (state.passwordVisible) R.string.auth_password_hide else R.string.auth_password_show), onTogglePasswordVisible) },
            )
            state.message?.let { message ->
                LoginMessageCard(message, Modifier.testTag(LoginTags.MESSAGE))
                if (message == LoginMessage.DeviceNotEnrolled && onEnrol != null) {
                    AuthLink(stringResource(R.string.enrol_title), onEnrol, Modifier.fillMaxWidth().testTag(LoginTags.ENROL))
                }
            }
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
            AuthFooter(stringResource(R.string.auth_sr_footer, versionName), Modifier.testTag(LoginTags.VERSION)) // version digits stay Latin
        }
    }
}

/** The hero: the diagonal gradient, the indigo stripe under its slanted edge, the round logo, the top row over it. */
@Composable
private fun SrLoginHero(onLanguageSelect: (AppLanguage) -> Unit, sunlight: Boolean, onSunlight: ((Boolean) -> Unit)?) {
    val look = LocalAronLook.current
    Box(
        Modifier.fillMaxWidth().height(260.dp).testTag(SrLoginTags.HERO).drawBehind {
            val w = size.width
            val h = size.height
            val edge = h * 0.80f
            val stripe = 22.dp.toPx()
            val body = Path().apply { moveTo(0f, 0f); lineTo(w, 0f); lineTo(w, edge - stripe); lineTo(0f, h - stripe); close() }
            val band = Path().apply { moveTo(0f, h - stripe); lineTo(w, edge - stripe); lineTo(w, edge); lineTo(0f, h); close() }
            // crimson at the bottom-left, through magenta, to deep purple at the top-right
            val brush: Brush = if (look.sunlight) SolidColor(look.barStart) else Brush.linearGradient(listOf(look.barStart, look.barMid, look.barEnd), start = Offset(0f, h), end = Offset(w, 0f))
            drawPath(body, brush)
            drawPath(band, look.printStart)
        },
    ) {
        Box(Modifier.align(Alignment.TopEnd).padding(horizontal = AronTokens.Space.Screen)) { AuthTopRow(onLanguageSelect, sunlight, onSunlight) }
        AronBrandPlate(
            size = 120.dp, markSize = 84.dp, cornerRadius = 60.dp,
            modifier = Modifier.align(Alignment.Center).padding(bottom = 16.dp).clip(CircleShape).testTag(SrLoginTags.LOGO), fill = look.card,
        )
    }
}
