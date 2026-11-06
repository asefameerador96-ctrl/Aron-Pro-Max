package com.aktcl.aron.amo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.aktcl.aron.core.system.support.SupportContent
import com.aktcl.aron.core.system.support.SupportStatus
import kotlinx.coroutines.launch

/** PDA to Support (F-AMO-034): builds, encrypts and queues the file (offline too) and shows the state of the last one. */
@Composable
fun SupportHost(shell: SystemShell, userId: Long, versionName: String) {
    val scope = rememberCoroutineScope()
    var support by remember { mutableStateOf<SystemShell.SupportShell?>(null) }
    var status by remember { mutableStateOf<SupportStatus>(SupportStatus.Idle) }
    var lastSync by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(userId) {
        val s = shell.support(userId, versionName)
        support = s; status = s.status(); lastSync = s.lastSyncText()
    }
    // screenshot 76: after the file is sent the vendor shows "সফল / সিঙ্ক ফাইল পাঠানো হয়ে গেছে" with ওকে; Settings stays behind it
    var sentDialog by remember { mutableStateOf(false) }
    if (sentDialog) com.aktcl.aron.core.ui.AronInfoDialog(
        androidx.compose.ui.res.stringResource(com.aktcl.aron.feature.amo.R.string.amo_shell_support_title), androidx.compose.ui.res.stringResource(com.aktcl.aron.feature.amo.R.string.amo_shell_support_sent),
        androidx.compose.ui.res.stringResource(com.aktcl.aron.feature.amo.R.string.amo_shell_ok), onDismiss = { sentDialog = false },
    )
    SupportContent(
        status = status, appVersion = versionName, lastSyncText = lastSync,
        onSend = {
            val s = support ?: return@SupportContent
            if (status is SupportStatus.Preparing) return@SupportContent
            status = SupportStatus.Preparing
            scope.launch {
                status = try { s.send() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
                    SupportStatus.Failed(com.aktcl.aron.core.system.support.SupportFailure.BUILD_FAILED)
                }
                if (status is SupportStatus.Sent) sentDialog = true
                lastSync = runCatching { s.lastSyncText() }.getOrNull()
            }
        },
    )
}
