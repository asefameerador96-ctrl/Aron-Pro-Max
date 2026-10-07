package com.aktcl.aron.amo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aktcl.aron.core.sync.shell.UpdateScreen
import com.aktcl.aron.core.sync.shell.UpdateShell
import com.aktcl.aron.core.system.update.DayGate
import com.aktcl.aron.core.system.update.DayGateBanner
import com.aktcl.aron.core.system.update.UpdateContent
import kotlinx.coroutines.launch

/**
 * F-SYS-020 in the shell: the update page (or the new-day block when nothing can be installed) over the app, else [content].
 * A required update never interrupts an open day; upload is never blocked (UpdateShell.screen).
 */
@Composable
fun UpdateHost(shell: UpdateShell, dayOpen: Boolean, serverSaidTooOld: Boolean, content: @Composable () -> Unit) {
    val state by shell.state.collectAsStateWithLifecycle()
    val laterFor by shell.laterFor.collectAsStateWithLifecycle()
    val download by shell.download.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    when (val screen = shell.screen(state, laterFor, dayOpen, serverSaidTooOld)) {
        UpdateScreen.None -> content()
        is UpdateScreen.Prompt -> UpdateContent(
            screen.release, screen.required, shell.network(), download,
            onDownload = { scope.launch { shell.download(screen.release) } },
            onInstall = { scope.launch { shell.install(screen.release) } },
            onOpenUnknownSources = { runCatching { context.startActivity(shell.unknownSourcesIntent()) } },
            onLater = { shell.later(screen.release) },
        )
        UpdateScreen.Blocked -> DayGateBanner(DayGate.BLOCKED_UPDATE_REQUIRED, onUpdate = { scope.launch { shell.check(atLogin = true) } })
    }
}
