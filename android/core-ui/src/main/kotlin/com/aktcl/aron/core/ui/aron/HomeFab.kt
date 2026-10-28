package com.aktcl.aron.core.ui.aron

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.AronIcons

/** Height the [AronHomeFab] takes at the bottom of a screen; a scrolling list leaves this much room after its last item. */
val AronHomeFabHeight = 104.dp

/**
 * The round Home button at the bottom centre of the TSO screens that have it (build contract s8 FAB column; screenshots 6 to 8, 28,
 * 29, 31 to 38, 43): a 64 dp blue disc with the white house over a soft blue glow rising from the bottom edge. In Sunlight the glow
 * is not drawn and the disc is solid. [label] is the TalkBack name ("Home"); [buttonModifier] goes on the disc (test tags). Place it
 * at the bottom centre of the screen's box; the glow does not take touches.
 */
@Composable
fun AronHomeFab(onClick: () -> Unit, label: String, modifier: Modifier = Modifier, buttonModifier: Modifier = Modifier) {
    val n = nightRoles()
    Box(modifier.fillMaxWidth().height(AronHomeFabHeight).windowInsetsPadding(WindowInsets.navigationBars), contentAlignment = Alignment.BottomCenter) {
        if (!n.solid) {
            Canvas(Modifier.matchParentSize()) {
                drawRect(
                    Brush.radialGradient(
                        listOf(n.fabGlow.copy(alpha = 0.55f), n.fabGlow.copy(alpha = 0.18f), Color.Transparent),
                        center = Offset(size.width / 2f, size.height), radius = size.width * 0.55f,
                    ),
                )
            }
        }
        Box(
            buttonModifier
                .padding(bottom = 14.dp)
                .size(64.dp)
                .nightGlow(n, n.fabGlow, CircleShape, 12.dp)
                .clip(CircleShape)
                .background(n.fab)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) { Icon(AronIcons.Home, contentDescription = null, tint = n.onFab, modifier = Modifier.size(30.dp)) }
    }
}
