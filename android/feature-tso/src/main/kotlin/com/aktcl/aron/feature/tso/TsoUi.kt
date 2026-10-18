package com.aktcl.aron.feature.tso

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.AronCard
import com.aktcl.aron.core.ui.R as CoreR
import com.aktcl.aron.feature.home.RoleHomes

/** A white kit card carrying one sentence: banners, hints and empty states (no coloured container: the words carry the meaning). */
@Composable
internal fun TsoNote(text: String, modifier: Modifier = Modifier) {
    AronCard(modifier) { Text(text, style = MaterialTheme.typography.bodyMedium, color = LocalAronLook.current.ink) }
}

/** Section title in purple (PARITY-SPEC s1: section titles `aron.purple`). */
@Composable
internal fun TsoSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp).semantics { heading() },
        style = MaterialTheme.typography.titleMedium, color = LocalAronLook.current.purple,
    )
}

/**
 * The line-art icon of a TSO screen (Home tile and the top bar of the screen it opens). The kit's TSO-specific drawings are
 * requested from lane ui-kit (aron_ic_tso_*); until they land the closest original-art icons stand in (the picture is the only thing that changes).
 */
@Composable
internal fun tsoIcon(tileId: String?): Painter = painterResource(
    when (tileId) {
        RoleHomes.TsoTile.DASHBOARD -> CoreR.drawable.aron_art_kpi
        RoleHomes.TsoTile.LEAVE -> CoreR.drawable.aron_art_attendance
        RoleHomes.TsoTile.FINAL_SUBMIT -> CoreR.drawable.aron_art_sales_submit
        RoleHomes.TsoTile.PERIPHERY -> CoreR.drawable.aron_art_outlet
        RoleHomes.TsoTile.MY_CALL -> CoreR.drawable.aron_art_task_delegation
        RoleHomes.TsoTile.FEEDBACK -> CoreR.drawable.aron_art_memo
        else -> CoreR.drawable.aron_ic_gear
    },
)

