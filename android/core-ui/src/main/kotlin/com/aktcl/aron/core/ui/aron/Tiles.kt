package com.aktcl.aron.core.ui.aron

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.AronLook
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber

/**
 * One tile of a tile grid (PARITY-SPEC s2.4, sr/home-1-tiles.webp, sr/outlet-menu.png): a white card (Home, hairline `#EEEEEE`) or a
 * `#F5F5F5` card ([menu] screens), radius 6 dp (measured on both references), the 56 dp gradient [icon] (menu: 78 % of the tile width, at most 96 dp) over the [label] in 14 sp indigo (pass [labelColor] =
 * `LocalAronLook.current.tileLabelOutlet` for the red Outlet-menu labels). [badge] > 0 shows a red count at the top end
 * ([badgeDescription] is its TalkBack text). Locked or deferred tiles are left out of the grid, never greyed (pass [enabled] = false
 * only for a tile that is visible but momentarily unavailable).
 */
@Composable
fun AronTile(
    label: String,
    icon: Painter?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: Int = 0,
    badgeDescription: String? = null,
    menu: Boolean = false,
    labelColor: Color? = null,
    enabled: Boolean = true,
) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(TileRadius)
    Box(
        modifier
            .heightIn(min = 112.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(if (menu) look.tileMenu else look.card)
            .border(1.dp, look.tileHairline, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // menu tiles (sr/outlet-menu.png, three per row) carry a picture of about 78 % of the tile width; Home a fixed 56 dp
            val iconModifier = if (menu) Modifier.fillMaxWidth(MenuIconFraction).sizeIn(maxWidth = MenuIconMax).aspectRatio(1f) else Modifier.size(AronLook.TileIcon)
            if (icon != null) Image(icon, contentDescription = null, modifier = iconModifier)
            else Box(iconModifier.clip(RoundedCornerShape(14.dp)).background(look.verticalBrush()))
            Spacer(Modifier.height(10.dp))
            Text(label, style = lookStyle(14.sp, color = labelColor ?: look.tileLabel, align = TextAlign.Center, medium = true))
        }
        if (badge > 0) {
            val text = if (badge > 99) localizedDigits("99+") else localizedNumber(badge.toLong())
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .sizeIn(minWidth = 22.dp, minHeight = 22.dp)
                    .clip(CircleShape)
                    .background(look.badge)
                    .semantics { if (badgeDescription != null) contentDescription = badgeDescription },
                contentAlignment = Alignment.Center,
            ) { Text(text, style = lookStyle(12.sp, bold = true, color = Color.White), modifier = Modifier.padding(horizontal = 5.dp)) }
        }
    }
}

/** Tile corner radius: about 5 units on sr/home-1-tiles.webp and sr/outlet-menu.png (the 12 dp of the cards is too round here). */
private val TileRadius = 6.dp
private const val MenuIconFraction = 0.78f
private val MenuIconMax = 96.dp

/** Grid gap of the Outlet-type menus (sr/outlet-menu.png: 17 units between tiles and at the edges): `AronTileGrid(gap = AronMenuGap)`. */
val AronMenuGap = 16.dp

/**
 * Columns of the tile grid: 4 on a phone (PARITY-SPEC s2.4), 3 when large text leaves less than 330 dp of "1x" width, 2 below 240
 * (font scale 2.0 on a 360 dp phone gives 180, so 2).
 */
fun aronTileColumns(widthDp: Float, fontScale: Float): Int {
    val effective = widthDp / fontScale.coerceAtLeast(1f)
    return when {
        effective >= 330f -> 4
        effective >= 240f -> 3
        else -> 2
    }
}

/**
 * The tile grid (PARITY-SPEC s2.4): [columns] or [aronTileColumns], every tile in a row as tall as the tallest. Not lazy: a Home has
 * at most about 16 tiles. [gap] 6 dp (Home): four Bangla labels such as `টিউটোরিয়াল` fit one line at 360 dp; menus pass [AronMenuGap].
 * The [tile] lambda gets the modifier that sizes the cell; pass it to [AronTile].
 */
@Composable
fun <T> AronTileGrid(items: List<T>, modifier: Modifier = Modifier, columns: Int? = null, gap: Dp = 6.dp, tile: @Composable (T, Modifier) -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val n = columns ?: aronTileColumns(maxWidth.value, fontScale)
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            items.chunked(n).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { item -> tile(item, Modifier.weight(1f).fillMaxHeight()) }
                    repeat(n - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Keeps a decorative node out of TalkBack (used for the silhouettes when the caller gives no description). */
internal fun Modifier.decorative(description: String?): Modifier =
    if (description == null) clearAndSetSemantics { } else semantics { contentDescription = description; role = Role.Image }
