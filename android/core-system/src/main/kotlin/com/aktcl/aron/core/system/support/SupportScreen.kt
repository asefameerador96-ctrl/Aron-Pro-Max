package com.aktcl.aron.core.system.support

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.system.R
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.localizedDigits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the PDA to Support screen shows. */
sealed interface SupportStatus {
    data object Idle : SupportStatus
    data object Preparing : SupportStatus
    /** Queued; [online] false = "will be sent when online", true = sending now or waiting for Wi-Fi. */
    data class Queued(val online: Boolean, val needsWifi: Boolean) : SupportStatus
    data class Sent(val atText: String) : SupportStatus
    data class Failed(val reason: SupportFailure) : SupportStatus
    /** Several attempts failed (server, sign-in, network): shown as a failure, still retried in the background. */
    data object RetryingAfterFailure : SupportStatus
}

enum class SupportFailure { NO_KEY, TOO_LARGE, REFUSED, BUILD_FAILED }

/** Where the file's content comes from (the app reads the user's database and the log ring buffer). */
fun interface SupportSource {
    suspend fun input(): SupportInput
}

/**
 * "PDA to Support" (F-SYS-021): build the file with app version and last sync, encrypt it, queue it, ask WorkManager to
 * send it. Works offline: the file waits in the queue and goes on reconnect. One file at a time; a new tap replaces it.
 */
class SupportController(
    private val queue: SupportQueue,
    private val source: SupportSource,
    /** The AKTCL support public key (X.509 SubjectPublicKeyInfo, base64); null until it is configured. */
    private val supportPublicKey: () -> String?,
    private val maxUploadMb: () -> Int,
    private val schedule: () -> Unit,
    private val nowMs: () -> Long,
) {
    suspend fun send(): SupportStatus {
        val key = supportPublicKey() ?: return SupportStatus.Failed(SupportFailure.NO_KEY)
        val input = source.input()
        val sealed = try {
            withContext(Dispatchers.Default) { SupportBundle.build(input, key, maxUploadMb() * 1024 * 1024) }
        } catch (_: SupportFileTooLargeException) {
            return SupportStatus.Failed(SupportFailure.TOO_LARGE)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (_: Exception) {
            return SupportStatus.Failed(SupportFailure.BUILD_FAILED)
        }
        val pending = input.counts.filterKeys { it != "acked" }.values.sum()
        queue.enqueue(ClientIds.newUuid(), sealed, input.appVersion, input.lastSyncAt, pending, nowMs())
        schedule()
        return SupportStatus.Queued(online = false, needsWifi = false)
    }

    /** The visible state of the newest file, for the screen (re-read on resume). */
    suspend fun status(online: Boolean, onWifi: Boolean, wifiOnly: Boolean, formatTime: (Long) -> String): SupportStatus {
        val job = queue.current() ?: return SupportStatus.Idle
        return when (job.state) {
            SupportState.QUEUED ->
                if (job.attempts >= SupportUploader.SHOW_FAILURE_AFTER && job.lastError != null) SupportStatus.RetryingAfterFailure
                else SupportStatus.Queued(online, needsWifi = online && wifiOnly && !onWifi)
            SupportState.SENT -> SupportStatus.Sent(formatTime(job.sentAtMs ?: job.createdAtMs))
            SupportState.FAILED -> SupportStatus.Failed(if (job.lastError in TOO_LARGE_CODES) SupportFailure.TOO_LARGE else SupportFailure.REFUSED)
        }
    }
}

private val TOO_LARGE_CODES = setOf("http_413", "put_413")

object SupportTags {
    const val SEND = "support_send"
    const val STATE = "support_state"
}

/** The screen: what is sent, the button, and the state of the last file. Solid surfaces only (docs/32 s2a). */
@Composable
fun SupportContent(status: SupportStatus, appVersion: String, lastSyncText: String?, onSend: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
        Text(stringResource(R.string.support_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.support_what), style = MaterialTheme.typography.bodyLarge)
        // The version is an identifier (ASCII); the last-sync time is a date and follows the language's digits.
        Text(stringResource(R.string.support_version, appVersion), style = MaterialTheme.typography.bodyMedium)
        Text(
            if (lastSyncText != null) localizedDigits(stringResource(R.string.support_last_sync, lastSyncText)) else stringResource(R.string.support_never_synced),
            style = MaterialTheme.typography.bodyMedium,
        )
        val busy = status is SupportStatus.Preparing
        AronPrimaryButton(stringResource(R.string.support_send), onSend, Modifier.fillMaxWidth().testTag(SupportTags.SEND), enabled = !busy)
        val stateMod = Modifier.testTag(SupportTags.STATE)
        when (status) {
            SupportStatus.Idle -> Unit
            SupportStatus.Preparing -> AronBanner(stringResource(R.string.support_preparing), stateMod, kind = BannerKind.Info)
            is SupportStatus.Queued -> AronBanner(
                stringResource(
                    when {
                        !status.online -> R.string.support_queued_offline
                        status.needsWifi -> R.string.support_queued_wifi
                        else -> R.string.support_sending
                    },
                ),
                stateMod, kind = BannerKind.Info,
            )
            SupportStatus.RetryingAfterFailure -> AronBanner(stringResource(R.string.support_failed_retrying), stateMod, kind = BannerKind.Error)
            is SupportStatus.Sent -> AronBanner(localizedDigits(stringResource(R.string.support_sent, status.atText)), stateMod, kind = BannerKind.Info)
            is SupportStatus.Failed -> AronBanner(
                stringResource(
                    when (status.reason) {
                        SupportFailure.NO_KEY -> R.string.support_failed_no_key
                        SupportFailure.TOO_LARGE -> R.string.support_failed_too_large
                        SupportFailure.REFUSED, SupportFailure.BUILD_FAILED -> R.string.support_failed
                    },
                ),
                stateMod, kind = BannerKind.Error,
            )
        }
    }
}
