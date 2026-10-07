package com.aktcl.aron.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronTokens

object LocationNoticeTags {
    const val ACCEPT = "location_notice_accept"
    const val LATER = "location_notice_later"
    const val LOGOUT = "location_notice_logout"
    const val FAILED = "location_notice_failed"
    const val LOADING = "location_notice_loading"
}

/**
 * Employee-location notice (F-SYS-075, owner android-core; new file in feature-auth by request
 * docs/requests/android-core-sr-a-location-notice.md). Bangla and English together on one solid, scrollable surface
 * (outdoor-first, docs/32 s2a). [required]: no "Later" and a hint that selling waits for acceptance.
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
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AronTokens.Space.L),
        verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
    ) {
        Text(stringResource(R.string.location_notice_title_bn), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.location_notice_body_bn), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.location_notice_title_en), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.location_notice_body_en), style = MaterialTheme.typography.bodyLarge)
        if (required) Text(stringResource(R.string.location_notice_required_hint), style = MaterialTheme.typography.bodyMedium)
        if (acceptFailed) {
            AronBanner(stringResource(R.string.location_notice_failed), Modifier.testTag(LocationNoticeTags.FAILED), kind = BannerKind.Error)
        }
        AronPrimaryButton(stringResource(R.string.location_notice_accept), onAccept, Modifier.testTag(LocationNoticeTags.ACCEPT), enabled = !busy)
        if (!required) AronSecondaryButton(stringResource(R.string.location_notice_later), onLater, Modifier.testTag(LocationNoticeTags.LATER))
        // A rep signed in by mistake on a shared phone leaves without accepting (checker).
        onLogout?.let { AronSecondaryButton(stringResource(R.string.location_notice_logout), it, Modifier.testTag(LocationNoticeTags.LOGOUT)) }
    }
}

/** What the gate needs: not accepted yet ([needed]) and whether selling waits for it ([required]). */
data class NoticeNeed(val needed: Boolean, val required: Boolean)

/**
 * Shows [LocationNoticeContent] before [content] while the notice is not accepted (F-SYS-075). With [NoticeNeed.required]
 * nothing behind it opens (so no sale starts); otherwise "Later" opens [content] for this screen session and the notice
 * returns at the next start. A [load] that fails twice shows the required notice (fail closed: accepting needs only the
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
    var busy by remember(key) { mutableStateOf(false) }
    var failed by remember(key) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(key) {
        need = loadOrRequired(load)
    }
    // "Later" holds only while the setting allows it: a config delta that turns the notice required mid-session (applied
    // by the resume config check) brings it back at the next resume. One local read per resume, only after "Later".
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if (later) scope.launch { recheckAfterLater(load)?.let { need = it; later = false } }
    }
    val n = need
    if (n == null) {
        Box(Modifier.fillMaxSize().testTag(LocationNoticeTags.LOADING), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (!n.needed || later) {
        content()
        return
    }
    val shownAt = remember(key) { nowMs() }
    LocationNoticeContent(
        required = n.required,
        acceptFailed = failed,
        busy = busy,
        onLogout = onLogout,
        onAccept = {
            if (!busy) {
                busy = true
                scope.launch {
                    try {
                        accept(shownAt)
                        failed = false
                        need = n.copy(needed = false)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        failed = true
                    } finally {
                        busy = false
                    }
                }
            }
        },
        onLater = { later = true },
    )
}

/** After "Later": the fresh need when the notice is now required and still not accepted, else null (keep "Later"). */
internal suspend fun recheckAfterLater(load: suspend () -> NoticeNeed): NoticeNeed? =
    loadOrRequired(load).takeIf { it.needed && it.required }

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
