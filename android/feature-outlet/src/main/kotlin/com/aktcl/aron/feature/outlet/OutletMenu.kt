package com.aktcl.aron.feature.outlet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronSectionLabel
import com.aktcl.aron.core.ui.AronTile
import com.aktcl.aron.core.ui.AronTileGrid
import com.aktcl.aron.core.ui.AronTokens

object OutletMenuTags { fun kind(k: OutletRequestKind) = "menu_${k.wire}"; const val OWN = "menu_own" }

/**
 * The Outlet menu (N-040, screens-sr s15): four tiles in the reference order (new shop, permanent close, change
 * information, cluster), then the "add to my route" request (F-SR-076) and "my requests" as secondary actions.
 */
@Composable
fun OutletMenuContent(onKind: (OutletRequestKind) -> Unit, modifier: Modifier = Modifier, onOwnRequests: (() -> Unit)? = null) {
    val tiles = listOf(OutletRequestKind.NEW, OutletRequestKind.CLOSE, OutletRequestKind.INFO, OutletRequestKind.CLUSTER)
    // the scrolling content ends above the navigation bar (edge-to-edge): the inset pads the content, after the scroll
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(vertical = AronTokens.Space.S),
    ) {
        AronSectionLabel(stringResource(R.string.out_menu_requests))
        AronTileGrid(tiles, columns = 2) { k, mod ->
            AronTile(
                label = stringResource(when (k) { OutletRequestKind.NEW -> R.string.out_req_new; OutletRequestKind.CLOSE -> R.string.out_req_close; OutletRequestKind.INFO -> R.string.out_req_info; else -> R.string.out_req_cluster }),
                icon = when (k) { OutletRequestKind.NEW -> AronIcons.Outlet; OutletRequestKind.CLOSE -> AronIcons.Close; OutletRequestKind.INFO -> AronIcons.Edit; else -> AronIcons.Team },
                onClick = { onKind(k) }, modifier = mod.testTag(OutletMenuTags.kind(k)),
            )
        }
        Column(Modifier.padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            AronSecondaryButton(stringResource(R.string.out_req_route_add), { onKind(OutletRequestKind.ROUTE_ADD) }, Modifier.testTag(OutletMenuTags.kind(OutletRequestKind.ROUTE_ADD)), icon = AronIcons.Journey)
            onOwnRequests?.let { AronSecondaryButton(stringResource(R.string.out_own_open), it, Modifier.testTag(OutletMenuTags.OWN), icon = AronIcons.Document) }
        }
    }
}
