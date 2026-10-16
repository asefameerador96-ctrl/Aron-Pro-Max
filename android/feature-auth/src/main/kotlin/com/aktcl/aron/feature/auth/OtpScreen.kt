package com.aktcl.aron.feature.auth

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronCard
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronTextButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.localizedDigits

object OtpTags {
    const val BOXES = "otp_boxes"
    const val VERIFY = "otp_verify"
    const val MESSAGE = "otp_message"
    const val BACK = "otp_back"
}

/** The four OTP boxes of screens-sr s1: 56 x 64 dp with 12 dp gaps (260 dp, centred). */
private val OtpBoxWidth = 56.dp
private val OtpBoxHeight = 64.dp

/**
 * Device OTP prompt (F-SR-002; screens-sr s1 "OTP step"): the same frame as login, the card with the title, the hint,
 * four boxes over ONE hidden text field (so paste, SMS autofill and TalkBack see a single field), the wrong, expired and
 * too-many-attempts texts in a danger line, and the bar primary "Verify" enabled at 4 digits. Back to login is always
 * reachable: after too many wrong codes or a lock the rep must be able to go back and ask for a new code.
 */
@Composable
fun OtpContent(
    state: OtpState,
    onDigits: (String) -> Unit,
    onVerify: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    appTitle: String? = null,
    onLanguageSelect: ((AppLanguage) -> Unit)? = null,
    sunlight: Boolean = false,
    onSunlight: ((Boolean) -> Unit)? = null,
) {
    val c = LocalAronColors.current
    ScreenWithBar(modifier.fillMaxSize(), bar = {
        Column(Modifier.fillMaxWidth()) {
            AuthBar(
                primaryText = stringResource(R.string.otp_verify), onPrimary = onVerify, primaryTag = OtpTags.VERIFY,
                enabled = state.canVerify, busy = state.busy,
            )
        }
    }) {
        ScrollColumn(contentPadding = PaddingValues(horizontal = AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            if (onLanguageSelect != null) AuthTopRow(onLanguageSelect, sunlight, onSunlight)
            if (appTitle != null) {
                Spacer(Modifier.height(AronTokens.Space.L))
                BrandBlock(appTitle)
                Spacer(Modifier.height(AronTokens.Space.L))
            }
            AronCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.L), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.otp_title), style = MaterialTheme.typography.titleLarge, color = c.textPrimary, modifier = Modifier.fillMaxWidth())
                    // the code errors replace the hint in one danger line (screens-sr s1); the other refusals are banners under the boxes
                    val codeError = state.error?.takeIf { it == OtpError.INVALID || it == OtpError.EXPIRED || it == OtpError.ATTEMPTS_EXCEEDED }
                    if (codeError != null) {
                        DangerLine(stringResource(when (codeError) { OtpError.INVALID -> R.string.otp_invalid; OtpError.EXPIRED -> R.string.otp_expired; else -> R.string.otp_attempts }), Modifier.testTag(OtpTags.MESSAGE))
                    } else {
                        Text(stringResource(R.string.otp_hint), style = MaterialTheme.typography.bodyLarge, color = c.textSecondary, modifier = Modifier.fillMaxWidth())
                    }
                    OtpBoxes(state.digits, onDigits, enabled = !state.bound && !state.busy, error = codeError != null)
                    state.error?.takeIf { codeError == null }?.let {
                        AronBanner(
                            stringResource(when (it) {
                                OtpError.LOCKED -> R.string.otp_locked
                                OtpError.OFFLINE -> R.string.otp_offline
                                OtpError.DEVICE_LIMIT -> R.string.otp_device_limit
                                OtpError.SIGN_IN_AGAIN -> R.string.otp_sign_in_again
                                else -> R.string.otp_other
                            }),
                            Modifier.testTag(OtpTags.MESSAGE), if (it == OtpError.OFFLINE) BannerKind.Offline else BannerKind.Error,
                        )
                    }
                    if (state.bound) AronBanner(stringResource(R.string.otp_bound), kind = BannerKind.Success)
                }
            }
            if (onBack != null) AronTextButton(stringResource(R.string.otp_back), onBack, Modifier.fillMaxWidth().testTag(OtpTags.BACK), enabled = !state.busy)
        }
    }
}

/** One `danger.container` line with the warning glyph and `danger.onContainer` words: the code error in the hint's place, never colour alone. */
@Composable
private fun DangerLine(text: String, modifier: Modifier = Modifier) {
    val c = LocalAronColors.current
    val bg = c.dangerContainer
    Row(
        modifier.fillMaxWidth().drawBehind { drawRoundRect(bg, cornerRadius = CornerRadius(AronTokens.Radius.Chip.toPx())) }.padding(horizontal = AronTokens.Space.M, vertical = AronTokens.Space.S),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S),
    ) {
        Icon(AronIcons.Warning, contentDescription = null, tint = c.dangerOnContainer, modifier = Modifier.size(AronTokens.Size.IconSmall))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = c.dangerOnContainer, modifier = Modifier.weight(1f))
    }
}

/**
 * Four boxes drawn under one transparent field: the field has the focus, the keyboard (numeric) and the test tag; each
 * box shows one digit in the current language's digits; the box after the last digit carries the focus ring.
 */
@Composable
private fun OtpBoxes(digits: String, onDigits: (String) -> Unit, enabled: Boolean, error: Boolean) {
    val c = LocalAronColors.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shown = localizedDigits(digits)
    val fill = if (enabled) c.surfaceSolidRaised else c.stateDisabledFill
    val idle = if (error) c.danger else c.borderInput
    val focus = c.borderFocus
    val hairline = if (c.sunlight) AronTokens.Stroke.SunlightHairline else AronTokens.Stroke.Hairline
    val ring = if (c.sunlight) AronTokens.Stroke.SunlightFocus else AronTokens.Stroke.Focus
    val hint = stringResource(R.string.otp_hint)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            repeat(OtpState.LENGTH) { i ->
                val active = focused && i == digits.length.coerceAtMost(OtpState.LENGTH - 1)
                Box(
                    Modifier.size(OtpBoxWidth, OtpBoxHeight).drawBehind {
                        val r = CornerRadius(AronTokens.Radius.Chip.toPx())
                        drawRoundRect(fill, cornerRadius = r)
                        val w = (if (active) ring else hairline).toPx()
                        drawRoundRect(if (active) focus else idle, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(r.x - w / 2), style = Stroke(w))
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(shown.getOrNull(i)?.toString().orEmpty(), style = MaterialTheme.typography.headlineLarge, color = c.textPrimary, textAlign = TextAlign.Center)
                }
            }
        }
        // the one real field: transparent text and cursor, the whole box row as its touch target
        BasicTextField(
            value = digits,
            onValueChange = onDigits,
            modifier = Modifier.matchParentSize().testTag(OtpTags.BOXES).semantics { contentDescription = hint },
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineLarge.copy(color = Color.Transparent),
            cursorBrush = SolidColor(Color.Transparent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            interactionSource = interaction,
        )
    }
}
