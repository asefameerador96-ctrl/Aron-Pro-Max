package com.aktcl.aron.amo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aktcl.aron.core.sync.shell.UpdateScreen
import com.aktcl.aron.core.sync.shell.UpdateShell
import com.aktcl.aron.core.system.update.DayGate
import com.aktcl.aron.core.system.update.DayGateBanner
import com.aktcl.aron.core.system.update.UpdateContent
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronTokens

/**
 * F-SYS-020 in the shell: the update page (or the new-day block when nothing can be installed) over the app, else [content].
 * A required update never interrupts an open day: [dayOpen] is re-read every time the update answer changes, and until
 * the first answer is worked out the app shows as usual. Upload is never blocked. The block keeps logout reachable
 * (shared phones).
 */
@Composable
fun UpdateHost(
    shell: UpdateShell,
    dayOpen: suspend () -> Boolean,
    serverSaidTooOld: Boolean,
    onLogout: () -> Unit,
    content: @Composable () -> Unit,
) {
    val state by shell.state.collectAsStateWithLifecycle()
    val laterFor by shell.laterFor.collectAsStateWithLifecycle()
    val download by shell.download.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val screen by produceState<UpdateScreen>(UpdateScreen.None, state, laterFor, serverSaidTooOld) {
        value = runCatching { shell.screen(state, laterFor, runCatching { dayOpen() }.getOrDefault(true), serverSaidTooOld) }.getOrDefault(UpdateScreen.None)
    }
    when (val s = screen) {
        UpdateScreen.None -> content()
        is UpdateScreen.Prompt -> {
            val network = remember(download, s.release) { shell.network() }
            UpdateContent(
                s.release, s.required, network, download,
                onDownload = { shell.startDownload(s.release) },
                onInstall = { shell.startInstall(s.release) },
                onOpenUnknownSources = { runCatching { context.startActivity(shell.unknownSourcesIntent()) } },
                onLater = { shell.later(s.release) },
            )
        }
        UpdateScreen.Blocked -> Column(Modifier.padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            DayGateBanner(DayGate.BLOCKED_UPDATE_REQUIRED, onUpdate = { shell.startCheck() })
            AronSecondaryButton(stringResource(com.aktcl.aron.feature.home.R.string.set_logout), onLogout, Modifier.fillMaxWidth())
        }
    }
}
