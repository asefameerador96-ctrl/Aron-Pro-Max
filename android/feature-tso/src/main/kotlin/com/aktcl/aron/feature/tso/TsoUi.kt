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

/** Section title: the kit's purple header with the short red rule (PARITY-SPEC s1). */
@Composable
internal fun TsoSectionTitle(text: String, modifier: Modifier = Modifier) {
    com.aktcl.aron.core.ui.aron.AronSectionHeader(text, modifier)
}

/**
 * The line-art icon of a TSO screen (Home tile and the top bar of the screen it opens). The kit's TSO-specific drawings are
 * Dashboard, Leave (calendar), Final Submit (shield) and Periphery (shop) reuse the SR/AMO vector drawables, as AMO does (no second icon set); only icons with no SR picture use the kit's aron_ic_tso_*.
 */
@Composable
internal fun tsoIcon(tileId: String?): Painter = painterResource(
    when (tileId) {
        RoleHomes.TsoTile.DASHBOARD -> CoreR.drawable.aron_ic_kpi
        RoleHomes.TsoTile.LEAVE -> CoreR.drawable.aron_ic_attendance
        RoleHomes.TsoTile.FINAL_SUBMIT -> CoreR.drawable.aron_ic_sales_submit
        RoleHomes.TsoTile.PERIPHERY -> CoreR.drawable.aron_ic_outlet
        RoleHomes.TsoTile.MY_CALL -> CoreR.drawable.aron_ic_tso_my_call
        RoleHomes.TsoTile.FEEDBACK -> CoreR.drawable.aron_ic_tso_feedback
        "retailer" -> CoreR.drawable.aron_ic_outlet
        "team" -> CoreR.drawable.aron_ic_tso_team
        "set_plan" -> CoreR.drawable.aron_ic_tso_set_plan
        "visit_plan" -> CoreR.drawable.aron_ic_sales_journey
        "query" -> CoreR.drawable.aron_ic_memo
        "task" -> CoreR.drawable.aron_ic_task_delegation
        "password" -> CoreR.drawable.aron_ic_lock
        else -> CoreR.drawable.aron_ic_gear
    },
)

