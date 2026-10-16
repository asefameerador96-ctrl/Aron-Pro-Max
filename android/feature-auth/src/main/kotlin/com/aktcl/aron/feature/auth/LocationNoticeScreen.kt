package com.aktcl.aron.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronCard
import com.aktcl.aron.core.ui.AronTextButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.aronPageBackground
import com.aktcl.aron.core.ui.skeleton
import kotlinx.coroutines.launch

object LocationNoticeTags {
    const val ACCEPT = "location_notice_accept"
    const val LATER = "location_notice_later"
    const val LOGOUT = "location_notice_logout"
    const val FAILED = "location_notice_failed"
    const val LOADING = "location_notice_loading"
    const val OVERLAY = "location_notice_overlay"
}

/**
 * Employee-location notice (F-SYS-075, owner android-core; new file in feature-auth by request
 * docs/requests/android-core-sr-a-location-notice.md). Bangla and English together on one solid card (outdoor-first,
 * docs/32 s2a), the kit buttons in the bar: Accept as the primary, Later as the secondary when not [required] (then a
 * hint says selling waits for acceptance), and a Log out text button for a rep who signed in by mistake.
 */
@Composable
fun LocationNoticeContent(
    required: Boolean,
    onAccept: () -> Unit,
    onLater: () -> Unit,
    modifier: Modifier = Modifier,
    acceptFailed: Boolean = false,
    busy: Boolean = false,
    onLogout: (() -> Unit)? = null,
) {
    val c = LocalAronColors.current
    ScreenWithBar(modifier.fillMaxSize(), bar = {
        Column(Modifier.fillMaxWidth()) {
            AuthBar(
                primaryText = stringResource(R.string.location_notice_accept), onPrimary = onAccept, primaryTag = LocationNoticeTags.ACCEPT,
                enabled = !busy, busy = busy,
                secondaryText = if (!required) stringResource(R.string.location_notice_later) else null,
                onSecondary = if (!required) onLater else null, secondaryTag = LocationNoticeTags.LATER,
            )
        }
    }) {
        ScrollColumn(contentPadding = PaddingValues(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            AronCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                    Text(stringResource(R.string.location_notice_title_bn), style = MaterialTheme.typography.titleLarge, color = c.textPrimary)
                    Text(stringResource(R.string.location_notice_title_en), style = MaterialTheme.typography.titleSmall, color = c.textSecondary)
                    Text(stringResource(R.string.location_notice_body_bn), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                    CardDivider()
                    Text(stringResource(R.string.location_notice_body_en), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                }
            }
            if (required) Text(stringResource(R.string.location_notice_required_hint), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            if (acceptFailed) {
                AronBanner(stringResource(R.string.location_notice_failed), Modifier.testTag(LocationNoticeTags.FAILED), kind = BannerKind.Error)
            }
            // A rep signed in by mistake on a shared phone leaves without accepting (checker).
            onLogout?.let { AronTextButton(stringResource(R.string.location_notice_logout), it, Modifier.fillMaxWidth().testTag(LocationNoticeTags.LOGOUT)) }
        }
    }
}

/** What the gate needs: not accepted yet ([needed]) and whether selling waits for it ([required]). */
data class NoticeNeed(val needed: Boolean, val required: Boolean)

/**
 * Shows [LocationNoticeContent] before [content] while the notice is not accepted (F-SYS-075). With [NoticeNeed.required]
 * nothing behind it opens (so no sale starts); otherwise "Later" opens [content] and the notice returns at the next start,
 * or at the next resume once a config change makes it required (then drawn over [content], which stays composed). A [load] that fails twice shows the required notice (fail closed: accepting needs only the
 * same local database a sale needs); a failed [accept] says so and keeps Accept for another tap. [nowMs] is trusted
 * time; [key] is the signed-in user; [onLogout] lets a rep who signed in by mistake leave without accepting.
 */
@Composable
fun LocationNoticeGate(
    key: Any,
    load: suspend () -> NoticeNeed,
    accept: suspend (shownAtMs: Long) -> Unit,
    nowMs: () -> Long,
    onLogout: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var need by remember(key) { mutableStateOf<NoticeNeed?>(null) }
    var later by remember(key) { mutableStateOf(false) }
    var reshow by remember(key) { mutableStateOf(false) }
    var busy by remember(key) { mutableStateOf(false) }
    var failed by remember(key) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(key) {
        need = loadOrRequired(load)
    }
    // "Later" holds only while the setting allows it: a config delta that turns the notice required mid-session (applied
    // by the resume config check) brings it back at the next resume. One local read per resume, only after "Later".
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if (later && !reshow) scope.launch { recheckAfterLater(load)?.let { need = it; reshow = true } }
    }
    fun onAccept(shownAt: Long, current: NoticeNeed) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                accept(shownAt)
                failed = false
                need = current.copy(needed = false)
                reshow = false
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
            } finally {
                busy = false
            }
        }
    }
    val n = need
    if (n == null) {
        // a few ms: a skeleton card where the notice will stand (android-glass s6.11), never a spinner overlay
        Box(Modifier.fillMaxSize().testTag(LocationNoticeTags.LOADING)) {
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(AronTokens.Space.Screen).height(200.dp).skeleton(true))
        }
        return
    }
    if (!n.needed || later) {
        // One stable slot for the day: a notice that returns after "Later" is drawn OVER it, so a visit in progress
        // (cart, back stack, saved state) survives the notice and continues after Accept (checker: no mid-sale teardown).
        Box(Modifier.fillMaxSize()) {
            content()
            if (reshow && n.needed) {
                Box(Modifier.fillMaxSize().aronPageBackground().testTag(LocationNoticeTags.OVERLAY)) {
                    androidx.activity.compose.BackHandler(enabled = true) { } // Back never reaches the day behind it
                    val shownAt = remember { nowMs() }
                    LocationNoticeContent(
                        required = true, acceptFailed = failed, busy = busy, onLogout = onLogout,
                        onAccept = { onAccept(shownAt, n) }, onLater = { },
                    )
                }
            }
        }
        return
    }
    val shownAt = remember(key) { nowMs() }
    LocationNoticeContent(
        required = n.required,
        acceptFailed = failed,
        busy = busy,
        onLogout = onLogout,
        onAccept = { onAccept(shownAt, n) },
        onLater = { later = true },
    )
}

/**
 * After "Later": the fresh need when the notice is now required and still not accepted, else null (keep "Later"). A read
 * error keeps "Later": the setting already allowed selling and an error carries no new information (checker).
 */
internal suspend fun recheckAfterLater(load: suspend () -> NoticeNeed): NoticeNeed? = try {
    load().takeIf { it.needed && it.required }
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/** One retry, then the required notice: a read error never lets a sale start without acceptance (checker). */
internal suspend fun loadOrRequired(load: suspend () -> NoticeNeed): NoticeNeed {
    repeat(2) {
        try {
            return load()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
    return NoticeNeed(needed = true, required = true)
}
