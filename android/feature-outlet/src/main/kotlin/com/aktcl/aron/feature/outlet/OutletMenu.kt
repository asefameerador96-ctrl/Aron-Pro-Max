package com.aktcl.aron.feature.outlet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronTile
import com.aktcl.aron.core.ui.AronTileGrid
import com.aktcl.aron.core.ui.AronTokens

object OutletMenuTags { fun kind(k: OutletRequestKind) = "menu_${k.wire}"; const val OWN = "menu_own" }

/** The Outlet menu (N-040): four tiles in the screenshot order, then the 'add to my route' request (F-SR-076). */
@Composable
fun OutletMenuContent(onKind: (OutletRequestKind) -> Unit, modifier: Modifier = Modifier, onOwnRequests: (() -> Unit)? = null) {
    val tiles = listOf(OutletRequestKind.NEW, OutletRequestKind.CLOSE, OutletRequestKind.INFO, OutletRequestKind.CLUSTER)
    Column(modifier.fillMaxSize().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
        Text(stringResource(R.string.out_menu_title), style = MaterialTheme.typography.headlineSmall)
        AronTileGrid(tiles) { k, mod ->
            AronTile(
                label = stringResource(when (k) { OutletRequestKind.NEW -> R.string.out_req_new; OutletRequestKind.CLOSE -> R.string.out_req_close; OutletRequestKind.INFO -> R.string.out_req_info; else -> R.string.out_req_cluster }),
                onClick = { onKind(k) }, modifier = mod.testTag(OutletMenuTags.kind(k)),
            )
        }
        AronSecondaryButton(stringResource(R.string.out_req_route_add), { onKind(OutletRequestKind.ROUTE_ADD) }, Modifier.testTag(OutletMenuTags.kind(OutletRequestKind.ROUTE_ADD)))
        onOwnRequests?.let { AronSecondaryButton(stringResource(R.string.out_own_open), it, Modifier.testTag(OutletMenuTags.OWN)) }
    }
}
