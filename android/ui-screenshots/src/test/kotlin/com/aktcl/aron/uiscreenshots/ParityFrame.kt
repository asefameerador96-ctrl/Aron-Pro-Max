package com.aktcl.aron.uiscreenshots

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.aron.AronPrinterBadge
import com.aktcl.aron.core.ui.aron.AronScaffold
import com.aktcl.aron.core.ui.aron.AronTopBar

/**
 * The host frame of the parity screens (the screenshot twin of app-sr `SrFrame`): the gradient bar with back chevron, the tile art and
 * the white title; [printerConnected] non-null hangs the printer badge on the bar edge (Stock, Memo, Summary). Used by the screens already
 * moved to the parity look; the older `Frame` replicas stay for the screens that have not moved yet.
 */
@Composable
internal fun ParityFrame(title: String, icon: Int?, printerConnected: Boolean? = null, subtitle: String? = null, content: @Composable () -> Unit) {
    AronScaffold(
        topBar = {
            AronTopBar(
                title = title, icon = icon?.let { painterResource(it) }, onBack = {},
                backLabel = stringResource(com.aktcl.aron.core.ui.R.string.core_ui_back),
                hanging = printerConnected?.let { c -> { AronPrinterBadge(connected = c, label = "printer", onClick = {}) } },
            )
        },
    ) {
        androidx.compose.foundation.layout.Column {
            subtitle?.let { androidx.compose.material3.Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            content()
        }
    }
}
