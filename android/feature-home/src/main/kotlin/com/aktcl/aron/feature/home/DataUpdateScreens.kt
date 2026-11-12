package com.aktcl.aron.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.verticalBrush
import com.aktcl.aron.core.ui.localizedNumber

/** What the in-app data update screens show (SR manual screenshot 08, SRM-A-11). */
enum class DataUpdatePhase {
    /** The "new updates found" prompt with the আপডেট button (also the way back after an offline, failed or refused attempt). */
    PROMPT,

    /** The download runs: "অ্যাপ আপডেট হচ্ছে" with the bar and the "প্রসেসিং(n/total)" counter. */
    RUNNING,
}

object DataUpdateTags {
    const val PROMPT = "du_prompt"
    const val RUNNING = "du_running"
    const val UPDATE = "du_update"
    const val SKIP = "du_skip"
    const val BAR = "du_bar"
    const val COUNTER = "du_counter"
    const val TIP = "du_tip"
    const val REASON = "du_reason"
    const val VERSION = "du_version"
}

/**
 * The data update of the SR manual (screenshot 08): two white pages without a top bar. The prompt says in Bangla that new updates were
 * found and that the rep must update, with a vertical red-to-purple "আপডেট" button and the footer "Current version SR App x". Pressing it
 * (or the download already running) shows "অ্যাপ আপডেট হচ্ছে" with a bar and "প্রসেসিং(n/total)" in Bangla digits and the red tip "টিপস:
 * অ্যাপটি বন্ধ করবেন না". The steps are the bundle's own: the head, each page, the apply (core-sync BundleProgress), so the counter is
 * real. Nothing here deletes anything: the bundle replaces reference data in one transaction and unsent records are never touched; a kill
 * resumes with the staged pages. [reason] explains why the prompt is back (offline, failed, sign in again). The vendor has no way past
 * this page; ours keeps a quiet [onSkip] link in every state, the running one too, because check-in, selling from the held data and printing never depend on the network
 * (CLAUDE.md constraint 1).
 */
@Composable
fun DataUpdateContent(
    phase: DataUpdatePhase,
    versionName: String,
    onUpdate: () -> Unit,
    modifier: Modifier = Modifier,
    done: Int? = null,
    total: Int? = null,
    reason: FirstBundleState? = null,
    onSkip: (() -> Unit)? = null,
) {
    val look = LocalAronLook.current
    Column(
        modifier.fillMaxSize().background(Color.White).verticalScroll(rememberScrollState())
            .padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.L).testTag(if (phase == DataUpdatePhase.RUNNING) DataUpdateTags.RUNNING else DataUpdateTags.PROMPT),
        verticalArrangement = Arrangement.spacedBy(AronTokens.Space.L, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (phase == DataUpdatePhase.PROMPT) {
            AnnouncementArt()
            Text(
                stringResource(R.string.du_prompt), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium), color = look.ink,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            reason?.let { r ->
                val kind = if (r == FirstBundleState.OFFLINE) BannerKind.Offline else BannerKind.Error
                AronBanner(
                    stringResource(when (r) { FirstBundleState.OFFLINE -> R.string.fb_offline; FirstBundleState.AUTH -> R.string.fb_auth; else -> R.string.fb_failed }),
                    Modifier.testTag(DataUpdateTags.REASON), kind = kind,
                )
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp)).background(look.verticalBrush())
                    .clickable(role = Role.Button, onClick = onUpdate).testTag(DataUpdateTags.UPDATE),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
            ) {
                DownloadGlyph(look.onBar)
                Text(stringResource(R.string.du_button), color = look.onBar, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            }
            Text(
                stringResource(R.string.du_current_version, versionName), style = MaterialTheme.typography.bodySmall, color = look.inkMuted,
                textAlign = TextAlign.Center, modifier = Modifier.testTag(DataUpdateTags.VERSION),
            )
        } else {
            SyncArt()
            Text(stringResource(R.string.du_updating), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = look.purple)
            val frac = if (done != null && total != null && total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(look.track).testTag(DataUpdateTags.BAR)) {
                Box(Modifier.fillMaxWidth(frac).height(8.dp).background(look.purple))
            }
            if (done != null && total != null && total > 0) {
                Text(
                    stringResource(R.string.du_processing, localizedNumber(done.toLong()), localizedNumber(total.toLong())),
                    style = MaterialTheme.typography.bodyMedium, color = look.purple, modifier = Modifier.testTag(DataUpdateTags.COUNTER),
                )
            }
            Text(
                stringResource(R.string.du_tip), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = look.red,
                textAlign = TextAlign.Center, modifier = Modifier.testTag(DataUpdateTags.TIP),
            )
        }
        // in EVERY state, the running download included (a slow link or a relaunch must never hold a rep off check-in; F-SR-001)
        if (onSkip != null) AronSecondaryButton(stringResource(R.string.fb_skip), onSkip, Modifier.testTag(DataUpdateTags.SKIP))
    }
}

/** A tilted phone with a speech bubble and a megaphone, flat and drawn (no bitmap: the APK stays lean). */
@Composable
private fun AnnouncementArt() {
    val look = LocalAronLook.current
    Canvas(Modifier.size(180.dp).rotate(-10f)) {
        val w = size.width
        val h = size.height
        drawRoundRect(look.purple, Offset(w * 0.30f, h * 0.10f), Size(w * 0.40f, h * 0.80f), CornerRadius(w * 0.06f), style = Stroke(w * 0.035f))
        drawRoundRect(Color(0xFFE6E0F5), Offset(w * 0.34f, h * 0.17f), Size(w * 0.32f, h * 0.60f), CornerRadius(w * 0.03f))
        drawCircle(look.barStart, w * 0.07f, Offset(w * 0.50f, h * 0.84f))
        drawRoundRect(look.barStart, Offset(w * 0.64f, h * 0.20f), Size(w * 0.26f, h * 0.16f), CornerRadius(w * 0.05f))
        drawCircle(Color(0xFF3F7FD6), w * 0.05f, Offset(w * 0.22f, h * 0.30f))
        drawCircle(Color(0xFF3F7FD6), w * 0.035f, Offset(w * 0.80f, h * 0.70f))
    }
}

/** A grey phone with the magenta sync arrows and blue gears around it. */
@Composable
private fun SyncArt() {
    val look = LocalAronLook.current
    Canvas(Modifier.size(150.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(Color(0xFF9E9E9E), Offset(w * 0.28f, h * 0.06f), Size(w * 0.44f, h * 0.88f), CornerRadius(w * 0.07f), style = Stroke(w * 0.04f))
        val sync = Stroke(w * 0.05f, cap = StrokeCap.Round)
        drawArc(look.barStart, 30f, 130f, false, Offset(w * 0.36f, h * 0.36f), Size(w * 0.28f, h * 0.28f), style = sync)
        drawArc(look.barStart, 210f, 130f, false, Offset(w * 0.36f, h * 0.36f), Size(w * 0.28f, h * 0.28f), style = sync)
        drawCircle(Color(0xFF3F7FD6), w * 0.07f, Offset(w * 0.14f, h * 0.30f), style = Stroke(w * 0.035f))
        drawCircle(Color(0xFF3F7FD6), w * 0.09f, Offset(w * 0.86f, h * 0.68f), style = Stroke(w * 0.035f))
    }
}

@Composable
private fun DownloadGlyph(tint: Color) {
    Canvas(Modifier.size(22.dp)) {
        val s = Stroke(size.width * 0.1f, cap = StrokeCap.Round)
        drawRoundRect(tint, Offset(size.width * 0.18f, size.height * 0.02f), Size(size.width * 0.64f, size.height * 0.96f), CornerRadius(size.width * 0.12f), style = s)
        drawLine(tint, Offset(size.width * 0.5f, size.height * 0.25f), Offset(size.width * 0.5f, size.height * 0.62f), size.width * 0.1f, StrokeCap.Round)
        drawLine(tint, Offset(size.width * 0.34f, size.height * 0.48f), Offset(size.width * 0.5f, size.height * 0.64f), size.width * 0.1f, StrokeCap.Round)
        drawLine(tint, Offset(size.width * 0.66f, size.height * 0.48f), Offset(size.width * 0.5f, size.height * 0.64f), size.width * 0.1f, StrokeCap.Round)
    }
}
