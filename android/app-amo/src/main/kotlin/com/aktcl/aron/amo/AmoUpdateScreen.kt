package com.aktcl.aron.amo

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.system.update.DownloadUi
import com.aktcl.aron.core.system.update.NetworkStatus
import com.aktcl.aron.core.system.update.ReleaseInfo
import com.aktcl.aron.core.system.update.UpdateTags
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.BannerKind
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

private val Deep = Color(0xFF4A1A9E)
private val Lavender = Color(0xFFFDF6FF)

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
    when (amoUpdateLook(required, download)) {
        AmoUpdateLook.UPDATING -> UpdatingPage((download as DownloadUi.Downloading).percent, modifier)
        AmoUpdateLook.MANDATORY_PROMPT -> Column(
            modifier.fillMaxSize().background(Color.White).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag(UpdateTags.SCREEN),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Image(painterResource(R.drawable.amo_update_mandatory), null, Modifier.fillMaxWidth(0.8f))
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.amo_upd_mandatory), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = Color(0xFF212121), modifier = Modifier.padding(horizontal = 8.dp).testTag(AmoUpdateTags.MANDATORY))
            Spacer(Modifier.height(32.dp))
            StateArea(download, onDownload, onInstall, onOpenUnknownSources, stringResource(R.string.amo_upd_button), gradient = true)
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.amo_upd_current, currentVersion), style = MaterialTheme.typography.bodyMedium, color = Color(0xFF424242))
            Spacer(Modifier.height(16.dp))
        }
        AmoUpdateLook.OPTIONAL_PROMPT -> Column(modifier.fillMaxSize().background(Lavender).verticalScroll(rememberScrollState()).testTag(UpdateTags.SCREEN)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onLater).testTag(AmoUpdateTags.BACK), contentAlignment = Alignment.Center) {
                    Text("‹", fontSize = 32.sp, color = Color(0xFF212121), modifier = Modifier.padding(bottom = 4.dp))
                }
                Spacer(Modifier.weight(1f))
                NetworkLine(network)
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(72.dp))
                Image(painterResource(R.drawable.amo_update_ribbon_cloud), null, Modifier.fillMaxWidth(0.85f))
                Text(stringResource(R.string.amo_upd_heading), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color(0xFF212121), modifier = Modifier.testTag(UpdateTags.AMO_HEADING))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.amo_upd_version, release.versionName), style = MaterialTheme.typography.bodyLarge, color = Color(0xFF424242))
                Spacer(Modifier.height(40.dp))
                StateArea(download, onDownload, onInstall, onOpenUnknownSources, stringResource(R.string.amo_upd_download), gradient = false, enabled = network != NetworkStatus.OFFLINE)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun NetworkLine(network: NetworkStatus) {
    val online = network != NetworkStatus.OFFLINE
    Row(Modifier.testTag(AmoUpdateTags.NETWORK), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.amo_upd_network), style = MaterialTheme.typography.bodyLarge, color = Color(0xFF212121))
        Box(Modifier.size(14.dp).clip(CircleShape).background(if (online) Color(0xFF2E7D32) else Color(0xFFC62828)))
        Text(stringResource(if (online) R.string.amo_upd_online else R.string.amo_upd_offline), style = MaterialTheme.typography.bodyMedium, color = if (online) Color(0xFF2E7D32) else Color(0xFFC62828))
    }
}

/** The button and the banners of the download states (everything except the percentage, which has its own page). */
@Composable
private fun StateArea(
    download: DownloadUi, onDownload: () -> Unit, onInstall: () -> Unit, onOpenUnknownSources: () -> Unit, label: String, gradient: Boolean, enabled: Boolean = true,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                Text(stringResource(SysR.string.update_unknown_sources_steps), style = MaterialTheme.typography.bodyLarge)
                WideButton(stringResource(SysR.string.update_open_settings), onOpenUnknownSources, gradient, true, Modifier.testTag(UpdateTags.UNKNOWN_SOURCES))
            }
        }
    }
}

@Composable
private fun WideButton(text: String, onClick: () -> Unit, gradient: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(if (gradient) 8.dp else 4.dp)
    val fill = if (!enabled) Brush.verticalGradient(listOf(Color(0xFF9E9E9E), Color(0xFF9E9E9E)))
    else if (gradient) Brush.horizontalGradient(listOf(Color(0xFF3B1F9E), Color(0xFF1E3A9E))) else Brush.verticalGradient(listOf(Deep, Deep))
    Box(
        modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape).background(fill).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.titleMedium, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp)) }
}

/** Screenshot 07 right: the phone and gears, "অ্যাপ আপডেট হচ্ছে", the blue bar, "প্রসেসিং(n/m)" and the red tip. */
@Composable
private fun UpdatingPage(percent: Int, modifier: Modifier) {
    val p = percent.coerceIn(0, 100)
    Column(
        modifier.fillMaxSize().background(Color.White).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag(UpdateTags.SCREEN),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        Image(painterResource(R.drawable.amo_update_progress), null, Modifier.fillMaxWidth(0.62f))
        Spacer(Modifier.height(32.dp))
        Text(stringResource(SysR.string.update_amo_updating), style = MaterialTheme.typography.headlineSmall, color = Deep, modifier = Modifier.testTag(UpdateTags.AMO_HEADING))
        Spacer(Modifier.height(12.dp))
        Canvas(Modifier.fillMaxWidth().height(14.dp).testTag(AmoUpdateTags.PROGRESS)) {
            drawRoundRect(Color(0xFFEDEDED), size = Size(size.width, size.height), cornerRadius = CornerRadius(size.height / 2))
            if (p > 0) drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF0A5CA8), Color(0xFF18B4E8))), size = Size(size.width * p / 100f, size.height), cornerRadius = CornerRadius(size.height / 2))
        }
        Spacer(Modifier.height(8.dp))
        Text(localizedDigits(stringResource(R.string.amo_upd_steps, p, 100)), style = MaterialTheme.typography.bodyMedium, color = Deep, modifier = Modifier.testTag(AmoUpdateTags.STEPS))
        Spacer(Modifier.height(96.dp))
        Text(stringResource(R.string.amo_upd_tip), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color(0xFFC62828), modifier = Modifier.testTag(AmoUpdateTags.TIP))
    }
}

@Composable
private fun currentVersionName(): String {
    val ctx = LocalContext.current
    return androidx.compose.runtime.remember { orEmptyVersion(ctx) }
}

private fun orEmptyVersion(ctx: android.content.Context): String =
    try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty() } catch (_: Exception) { "" }
