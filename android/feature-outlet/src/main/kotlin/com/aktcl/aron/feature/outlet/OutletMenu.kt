package com.aktcl.aron.feature.outlet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.AronSecondaryButton
import com.aktcl.aron.core.ui.aron.AronTile
import com.aktcl.aron.core.ui.aron.AronTileGrid

object OutletMenuTags { fun kind(k: OutletRequestKind) = "menu_${k.wire}"; const val OWN = "menu_own" }

/**
 * The Outlet menu (N-040; PARITY-SPEC sr/outlet-menu.png): four tiles in the reference order (new shop, permanent close, change
 * information, cluster), three to a row on the `#F5F5F5` menu tiles with the original art over red labels, then the "add to my route"
 * request (F-SR-076) and "my requests" as grey buttons under them.
 */
@Composable
fun OutletMenuContent(onKind: (OutletRequestKind) -> Unit, modifier: Modifier = Modifier, onOwnRequests: (() -> Unit)? = null) {
    val look = LocalAronLook.current
    val tiles = listOf(OutletRequestKind.NEW, OutletRequestKind.CLOSE, OutletRequestKind.INFO, OutletRequestKind.CLUSTER)
    // the scrolling content ends above the navigation bar (edge-to-edge): the inset pads the content, after the scroll
    Column(
        modifier.fillMaxSize().background(look.page).verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(AronTokens.Space.Screen),
        verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
    ) {
        // three columns, two from font scale 1.3 and one from 1.9 (reflow: labels wrap, numbers and glyphs never clip)
        val scale = LocalDensity.current.fontScale
        val columns = if (scale >= AronTokens.Size.OneColumnFontScale) 1 else if (scale >= 1.3f) 2 else 3
        AronTileGrid(tiles, columns = columns) { k, mod ->
            AronTile(
                label = stringResource(when (k) { OutletRequestKind.NEW -> R.string.out_req_new; OutletRequestKind.CLOSE -> R.string.out_req_close; OutletRequestKind.INFO -> R.string.out_req_info; else -> R.string.out_req_cluster }),
                icon = painterResource(
                    when (k) {
                        OutletRequestKind.NEW -> com.aktcl.aron.core.ui.R.drawable.aron_art_outlet_new
                        OutletRequestKind.CLOSE -> com.aktcl.aron.core.ui.R.drawable.aron_art_outlet_close
                        OutletRequestKind.INFO -> com.aktcl.aron.core.ui.R.drawable.aron_art_outlet_edit
                        else -> com.aktcl.aron.core.ui.R.drawable.aron_art_cluster
                    },
                ),
                onClick = { onKind(k) }, modifier = mod.testTag(OutletMenuTags.kind(k)), menu = true, labelColor = look.tileLabelOutlet,
            )
        }
        AronSecondaryButton(stringResource(R.string.out_req_route_add), { onKind(OutletRequestKind.ROUTE_ADD) }, Modifier.fillMaxWidth().testTag(OutletMenuTags.kind(OutletRequestKind.ROUTE_ADD)))
        onOwnRequests?.let { AronSecondaryButton(stringResource(R.string.out_own_open), it, Modifier.fillMaxWidth().testTag(OutletMenuTags.OWN)) }
    }
}
