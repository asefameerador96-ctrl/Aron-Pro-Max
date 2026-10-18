package com.aktcl.aron.feature.tso

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.AronLook
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.R as CoreR
import com.aktcl.aron.feature.home.RoleHomes

/**
 * TSO-local building blocks in the existing-Aron look (PARITY-SPEC s1: white card, radius 12 dp, 2 dp shadow, no blur, no gradient
 * background), made from the kit tokens only. Private to this lane until the kit grows its own (then these are swapped out).
 */
@Composable
internal fun TsoCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(AronLook.CardRadius)
    Column(
        modifier.fillMaxWidth().shadow(2.dp, shape).clip(shape).background(look.card)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp), content = content,
    )
}

/** A white card carrying one sentence: banners, hints and empty states (no coloured container: the words carry the meaning). */
@Composable
internal fun TsoNote(text: String, modifier: Modifier = Modifier) {
    TsoCard(modifier) { Text(text, style = MaterialTheme.typography.bodyMedium, color = LocalAronLook.current.ink) }
}

/** Section title in purple (PARITY-SPEC s1: section titles `aron.purple`). */
@Composable
internal fun TsoSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp).semantics { heading() },
        style = MaterialTheme.typography.titleMedium, color = LocalAronLook.current.purple,
    )
}

/** A list row inside a [TsoCard]: title over an optional subtitle, optional trailing text, 48 dp minimum when it is tappable. */
@Composable
internal fun TsoRow(
    title: String, modifier: Modifier = Modifier, subtitle: String? = null, trailing: String? = null, onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null, trailingContent: (@Composable () -> Unit)? = null,
) {
    val look = LocalAronLook.current
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = look.ink)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = look.inkMuted)
        }
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.titleSmall, color = look.purple)
        trailingContent?.invoke()
    }
}

/**
 * The line-art icon of a TSO screen (Home tile and the top bar of the screen it opens). The kit's TSO-specific drawings are
 * requested from lane ui-kit; until they land the closest SR set icons stand in (the picture is the only thing that changes).
 */
@Composable
internal fun tsoIcon(tileId: String?): Painter = painterResource(
    when (tileId) {
        RoleHomes.TsoTile.DASHBOARD -> CoreR.drawable.aron_ic_kpi
        RoleHomes.TsoTile.LEAVE -> CoreR.drawable.aron_ic_attendance
        RoleHomes.TsoTile.FINAL_SUBMIT -> CoreR.drawable.aron_ic_sales_submit
        RoleHomes.TsoTile.PERIPHERY -> CoreR.drawable.aron_ic_outlet
        RoleHomes.TsoTile.MY_CALL -> CoreR.drawable.aron_ic_task_delegation
        RoleHomes.TsoTile.FEEDBACK -> CoreR.drawable.aron_ic_memo
        else -> CoreR.drawable.aron_ic_gear
    },
)

