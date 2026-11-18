package com.aktcl.aron.feature.amo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.aktcl.aron.core.system.update.DownloadUi
import com.aktcl.aron.core.system.update.NetworkStatus
import com.aktcl.aron.core.system.update.ReleaseInfo
import com.aktcl.aron.core.system.update.UpdateTags
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronLook
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.system.R as SysR

/** Test tags of the AMO update screens. */
object AmoUpdateTags {
    const val BACK = "amo_update_back"
    const val NETWORK = "amo_update_network"
    const val MANDATORY = "amo_update_mandatory"
    const val PROGRESS = "amo_update_progress"
    const val STEPS = "amo_update_steps"
    const val TIP = "amo_update_tip"
}

/** Which of the three old screens a state shows (screenshots 05 and 07): a pure function so it is tested without a screen. */
enum class AmoUpdateLook { OPTIONAL_PROMPT, MANDATORY_PROMPT, UPDATING }

fun amoUpdateLook(required: Boolean, download: DownloadUi): AmoUpdateLook = when {
    download is DownloadUi.Downloading -> AmoUpdateLook.UPDATING
    required -> AmoUpdateLook.MANDATORY_PROMPT
    else -> AmoUpdateLook.OPTIONAL_PROMPT
}

/**
 * The AMO update screens as the existing Aron shows them (D-PAR-AMO-03). An optional update is screenshot 05: the back arrow (it is "later"),
 * "Network Status" with the online dot, the ribbon, the cloud, "Update Available", "Version x available" and one purple button. A mandatory
 * update is the illustrated screen 07 with "আপডেট" and the current version; while downloading it is the "অ্যাপ আপডেট হচ্ছে" screen with the
 * "প্রসেসিং(n/m)" caption (percent of 100: the download state carries a percentage, not bytes) and the red tip. The download, install,
 * Wi-Fi, space, failure and unknown-sources states keep their banners under the button.
 */
@Composable
fun AmoUpdateScreen(
    release: ReleaseInfo, required: Boolean, network: NetworkStatus, download: DownloadUi,
    onDownload: () -> Unit, onInstall: () -> Unit, onOpenUnknownSources: () -> Unit, onLater: () -> Unit,
    modifier: Modifier = Modifier, currentVersion: String = currentVersionName(),
) {
    val look = LocalAronLook.current
    when (amoUpdateLook(required, download)) {
        AmoUpdateLook.UPDATING -> UpdatingPage((download as DownloadUi.Downloading).percent, modifier)
        AmoUpdateLook.MANDATORY_PROMPT -> Column(
            modifier.fillMaxSize().background(look.card).verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen).testTag(UpdateTags.SCREEN),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(AronTokens.Space.Xl))
            Image(painterResource(R.drawable.amo_update_mandatory), null, Modifier.fillMaxWidth(0.8f))
            Spacer(Modifier.height(AronTokens.Space.Xl))
            Text(stringResource(R.string.amo_upd_mandatory), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = look.ink, modifier = Modifier.padding(horizontal = AronTokens.Space.S).testTag(AmoUpdateTags.MANDATORY))
            Spacer(Modifier.height(AronTokens.Space.Xl + AronTokens.Space.S))
            StateArea(download, onDownload, onInstall, onOpenUnknownSources, stringResource(R.string.amo_upd_button), gradient = true)
            Spacer(Modifier.height(AronTokens.Space.Xl))
            Text(stringResource(R.string.amo_upd_current, currentVersion), style = MaterialTheme.typography.bodyMedium, color = look.inkMuted)
            Spacer(Modifier.height(AronTokens.Space.L))
        }
        AmoUpdateLook.OPTIONAL_PROMPT -> Column(modifier.fillMaxSize().background(look.page).verticalScroll(rememberScrollState()).testTag(UpdateTags.SCREEN)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.M, vertical = AronTokens.Space.S), verticalAlignment = Alignment.CenterVertically) {
                // The old screen's "‹" is the back control; here it is a vector with a spoken label (it is "later", not a lost screen)
                Box(
                    Modifier.size(AronTokens.Touch.Min).clip(CircleShape).clickable(role = Role.Button, onClick = onLater).testTag(AmoUpdateTags.BACK),
                    contentAlignment = Alignment.Center,
                ) { Icon(AronIcons.ArrowBack, contentDescription = stringResource(R.string.amo_upd_back), tint = look.ink, modifier = Modifier.size(AronTokens.Size.Icon)) }
                Spacer(Modifier.weight(1f))
                NetworkLine(network)
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.Screen), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(AronTokens.Space.Xl * 3))
                AmoUpdateRibbonAndCloud(Modifier.fillMaxWidth(0.85f))
                Text(stringResource(R.string.amo_upd_heading), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = look.ink, modifier = Modifier.testTag(UpdateTags.AMO_HEADING))
                Spacer(Modifier.height(AronTokens.Space.S))
                Text(stringResource(R.string.amo_upd_version, release.versionName), style = MaterialTheme.typography.bodyLarge, color = look.inkMuted)
                Spacer(Modifier.height(AronTokens.Space.Xl + AronTokens.Space.L))
                StateArea(download, onDownload, onInstall, onOpenUnknownSources, stringResource(R.string.amo_upd_download), gradient = false, enabled = network != NetworkStatus.OFFLINE)
                Spacer(Modifier.height(AronTokens.Space.Xl))
            }
        }
    }
}

@Composable
private fun NetworkLine(network: NetworkStatus) {
    val online = network != NetworkStatus.OFFLINE
    val colors = LocalAronColors.current
    val look = LocalAronLook.current
    val tone = if (online) colors.success else colors.danger // solid, contrast-checked roles (Sunlight deepens both)
    Row(Modifier.testTag(AmoUpdateTags.NETWORK), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
        Text(stringResource(R.string.amo_upd_network), style = MaterialTheme.typography.bodyLarge, color = look.ink)
        Box(Modifier.size(AronTokens.Space.M).clip(CircleShape).background(tone))
        Text(stringResource(if (online) R.string.amo_upd_online else R.string.amo_upd_offline), style = MaterialTheme.typography.bodyMedium, color = tone)
    }
}

/** The button and the banners of the download states (everything except the percentage, which has its own page). */
@Composable
private fun StateArea(
    download: DownloadUi, onDownload: () -> Unit, onInstall: () -> Unit, onOpenUnknownSources: () -> Unit, label: String, gradient: Boolean, enabled: Boolean = true,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
        when (download) {
            DownloadUi.Idle -> WideButton(label, onDownload, gradient, enabled, Modifier.testTag(UpdateTags.DOWNLOAD))
            is DownloadUi.Downloading -> Unit
            DownloadUi.Ready -> WideButton(stringResource(SysR.string.update_install), onInstall, gradient, true, Modifier.testTag(UpdateTags.INSTALL))
            DownloadUi.NeedsWifi -> AronBanner(stringResource(SysR.string.update_needs_wifi), kind = BannerKind.Info)
            DownloadUi.NoSpace -> AronBanner(stringResource(SysR.string.update_no_space), kind = BannerKind.Error)
            DownloadUi.Failed -> {
                AronBanner(stringResource(SysR.string.update_failed), kind = BannerKind.Error)
                WideButton(stringResource(SysR.string.update_retry), onDownload, gradient, true, Modifier.testTag(UpdateTags.DOWNLOAD))
            }
            DownloadUi.SyncBusy -> {
                AronBanner(stringResource(SysR.string.update_sync_busy), kind = BannerKind.Info)
                WideButton(stringResource(SysR.string.update_install), onInstall, gradient, true, Modifier.testTag(UpdateTags.INSTALL))
            }
            DownloadUi.NeedsUnknownSources -> {
                AronBanner(stringResource(SysR.string.update_unknown_sources_why), kind = BannerKind.Warning)
                Text(stringResource(SysR.string.update_unknown_sources_steps), style = MaterialTheme.typography.bodyLarge, color = LocalAronLook.current.ink)
                WideButton(stringResource(SysR.string.update_open_settings), onOpenUnknownSources, gradient, true, Modifier.testTag(UpdateTags.UNKNOWN_SOURCES))
            }
        }
    }
}

/** The old screens' wide button: violet (screen 05) or violet-to-navy (screen 07), from the look's purple and choice roles; a flat solid in Sunlight. */
@Composable
private fun WideButton(text: String, onClick: () -> Unit, gradient: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(if (gradient) AronLook.ButtonRadius else AronTokens.Space.Xs)
    val end = if (look.sunlight) look.purple else look.choiceFill ?: look.purple
    val fill = when {
        !enabled -> Brush.verticalGradient(listOf(look.disabledFill, look.disabledFill))
        gradient -> Brush.horizontalGradient(listOf(look.purple, end))
        else -> Brush.verticalGradient(listOf(look.purple, look.purple))
    }
    Box(
        modifier.fillMaxWidth().heightIn(min = AronLook.ButtonHeight).clip(shape).background(fill).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = if (enabled) LocalAronColors.current.textOnAccent else look.disabledText, textAlign = TextAlign.Center, modifier = Modifier.padding(AronTokens.Space.S))
    }
}

/** Screenshot 07 right: the phone and gears, "অ্যাপ আপডেট হচ্ছে", the blue bar, "প্রসেসিং(n/m)" and the red tip. */
@Composable
private fun UpdatingPage(percent: Int, modifier: Modifier) {
    val p = percent.coerceIn(0, 100)
    val look = LocalAronLook.current
    val colors = LocalAronColors.current
    Column(
        modifier.fillMaxSize().background(look.card).verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen).testTag(UpdateTags.SCREEN),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(AronTokens.Space.Xl + AronTokens.Space.L))
        Image(painterResource(R.drawable.amo_update_progress), null, Modifier.fillMaxWidth(0.62f))
        Spacer(Modifier.height(AronTokens.Space.Xl + AronTokens.Space.S))
        Text(stringResource(SysR.string.update_amo_updating), style = MaterialTheme.typography.headlineSmall, color = look.purple, modifier = Modifier.testTag(UpdateTags.AMO_HEADING))
        Spacer(Modifier.height(AronTokens.Space.M))
        val track = look.track; val fillColor = look.choiceFill ?: look.purple // the old bar is blue: the AMO navy role (trackFill is red in Sunlight, which reads as an error)
        Canvas(Modifier.fillMaxWidth().height(AronTokens.Space.M + AronTokens.Space.Xs / 2).testTag(AmoUpdateTags.PROGRESS)) {
            drawRoundRect(track, size = Size(size.width, size.height), cornerRadius = CornerRadius(size.height / 2))
            if (p > 0) drawRoundRect(fillColor, size = Size(size.width * p / 100f, size.height), cornerRadius = CornerRadius(size.height / 2))
        }
        Spacer(Modifier.height(AronTokens.Space.S))
        Text(localizedDigits(stringResource(R.string.amo_upd_steps, p, 100)), style = MaterialTheme.typography.bodyMedium, color = look.purple, modifier = Modifier.testTag(AmoUpdateTags.STEPS))
        Spacer(Modifier.height(AronTokens.Space.Xl * 4))
        Text(stringResource(R.string.amo_upd_tip), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = colors.danger, modifier = Modifier.testTag(AmoUpdateTags.TIP))
    }
}

@Composable
private fun currentVersionName(): String {
    val ctx = LocalContext.current
    return androidx.compose.runtime.remember { orEmptyVersion(ctx) }
}

private fun orEmptyVersion(ctx: android.content.Context): String =
    try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty() } catch (_: Exception) { "" }


/**
 * The "NEW UPDATE" ribbon and the download cloud of screen 05, drawn in code (D-PAR-AMO-24): the traced picture smudged the lettering. An orange band with two
 * darker folded tails and white bold letters with a navy outline; under it a purple cloud with a white down arrow. Decoration only: the heading and the version
 * line under it carry the meaning, and the whole is hidden from a screen reader.
 */
@Composable
private fun AmoUpdateRibbonAndCloud(modifier: Modifier = Modifier) {
    val navy = androidx.compose.ui.graphics.Color(0xFF1B2A5C)
    Column(modifier.clearAndSetSemantics { }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(104.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                val tail = androidx.compose.ui.graphics.Color(0xFFD9661A); val band = androidx.compose.ui.graphics.Color(0xFFF0842A); val fold = androidx.compose.ui.graphics.Color(0xFFB04A0E)
                val left = androidx.compose.ui.graphics.Path().apply { moveTo(0f, h * 0.38f); lineTo(w * 0.2f, h * 0.30f); lineTo(w * 0.2f, h * 0.82f); lineTo(w * 0.04f, h * 0.88f); lineTo(w * 0.09f, h * 0.62f); close() }
                val right = androidx.compose.ui.graphics.Path().apply { moveTo(w, h * 0.38f); lineTo(w * 0.8f, h * 0.30f); lineTo(w * 0.8f, h * 0.82f); lineTo(w * 0.96f, h * 0.88f); lineTo(w * 0.91f, h * 0.62f); close() }
                drawPath(left, tail); drawPath(right, tail)
                val bandPath = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.1f, h * 0.20f); quadraticBezierTo(w * 0.5f, h * 0.02f, w * 0.9f, h * 0.20f); lineTo(w * 0.9f, h * 0.70f); quadraticBezierTo(w * 0.5f, h * 0.52f, w * 0.1f, h * 0.70f); close() }
                drawPath(bandPath, band)
                val foldL = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.1f, h * 0.70f); lineTo(w * 0.2f, h * 0.82f); lineTo(w * 0.2f, h * 0.64f); close() }
                val foldR = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.9f, h * 0.70f); lineTo(w * 0.8f, h * 0.82f); lineTo(w * 0.8f, h * 0.64f); close() }
                drawPath(foldL, fold); drawPath(foldR, fold)
            }
            Box(Modifier.padding(bottom = 8.dp), contentAlignment = Alignment.Center) {
                val base = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black, fontSize = 26.sp, letterSpacing = 0.5.sp)
                Text(stringResource(R.string.amo_upd_ribbon), style = base.copy(color = navy, drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = 9f, join = androidx.compose.ui.graphics.StrokeJoin.Round)))
                Text(stringResource(R.string.amo_upd_ribbon), style = base.copy(color = androidx.compose.ui.graphics.Color.White))
            }
        }
        androidx.compose.foundation.Canvas(Modifier.size(64.dp)) {
            val purple = androidx.compose.ui.graphics.Color(0xFF6B4FA8)
            val w = size.width; val h = size.height
            drawCircle(purple, radius = w * 0.2f, center = androidx.compose.ui.geometry.Offset(w * 0.3f, h * 0.55f))
            drawCircle(purple, radius = w * 0.26f, center = androidx.compose.ui.geometry.Offset(w * 0.52f, h * 0.4f))
            drawCircle(purple, radius = w * 0.2f, center = androidx.compose.ui.geometry.Offset(w * 0.74f, h * 0.56f))
            drawRect(purple, topLeft = androidx.compose.ui.geometry.Offset(w * 0.3f, h * 0.5f), size = androidx.compose.ui.geometry.Size(w * 0.44f, h * 0.26f))
            val white = androidx.compose.ui.graphics.Color.White
            drawLine(white, androidx.compose.ui.geometry.Offset(w * 0.52f, h * 0.3f), androidx.compose.ui.geometry.Offset(w * 0.52f, h * 0.78f), strokeWidth = w * 0.07f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(white, androidx.compose.ui.geometry.Offset(w * 0.38f, h * 0.64f), androidx.compose.ui.geometry.Offset(w * 0.52f, h * 0.8f), strokeWidth = w * 0.07f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(white, androidx.compose.ui.geometry.Offset(w * 0.66f, h * 0.64f), androidx.compose.ui.geometry.Offset(w * 0.52f, h * 0.8f), strokeWidth = w * 0.07f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}
