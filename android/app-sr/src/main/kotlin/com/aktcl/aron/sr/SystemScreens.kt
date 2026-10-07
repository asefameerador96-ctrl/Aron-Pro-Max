package com.aktcl.aron.sr

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

/** PDA to Support (F-SR-006): builds, encrypts and queues the file (offline too) and shows the state of the last one. */
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
                lastSync = runCatching { s.lastSyncText() }.getOrNull()
            }
        },
    )
}
