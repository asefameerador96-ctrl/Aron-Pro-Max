package com.aktcl.aron.feature.amo

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.core.ui.aron.AronTile
import com.aktcl.aron.core.ui.aron.AronTileGrid

/** One tile of a hub: its label, an optional pending count ([badge], hidden at zero), the tap, and its line-art icon (null: the kit's placeholder square). */
data class HubRow(val label: String, val trailing: String? = null, val onClick: () -> Unit = {}, @DrawableRes val icon: Int? = null, val badge: Int = 0)

/** A group of tiles under an optional caption. */
data class HubSection(val caption: String?, val rows: List<HubRow>)

/**
 * The AMO hubs (Team, Outlet) as the Outlet menu of the existing Aron (sr/outlet-menu.png): captioned groups of `#F5F5F5` tiles, three
 * to a row, the icon over the label ([redLabels]: the red Outlet-menu labels), the pending count as a red badge.
 */
@Composable
fun HubListContent(sections: List<HubSection>, modifier: Modifier = Modifier, redLabels: Boolean = false) {
    val look = LocalAronLook.current
    AmoPage(modifier) {
        sections.forEach { s ->
            s.caption?.let { AmoHeading(it) }
            AronTileGrid(s.rows, columns = if (LocalDensity.current.fontScale >= 1.3f) null else 3) { r, m ->
                AronTile(
                    r.label, r.icon?.let { painterResource(it) }, onClick = r.onClick, modifier = m.testTag("amo_hub_" + r.label),
                    badge = r.badge, badgeDescription = if (r.badge > 0) r.label + ": " + localizedNumber(r.badge.toLong()) else null,
                    menu = true, labelColor = if (redLabels) look.tileLabelOutlet else null,
                )
            }
        }
    }
}
