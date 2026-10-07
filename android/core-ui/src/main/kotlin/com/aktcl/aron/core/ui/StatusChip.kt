package com.aktcl.aron.core.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

/** The steady sync state on every main screen (docs/32 s1.6). Never a modal. Meaning is in the word, not the colour alone. */
@Immutable
sealed interface SyncChipState {
    data object Offline : SyncChipState
    data object Syncing : SyncChipState
    data object Synced : SyncChipState
    data class Waiting(val count: Int) : SyncChipState
}

@Composable
fun StatusChip(state: SyncChipState, modifier: Modifier = Modifier) {
    val c = LocalAronColors.current
    val (dot, label) = when (state) {
        SyncChipState.Offline -> c.offline to stringResource(R.string.core_ui_chip_offline)
        SyncChipState.Syncing -> c.accent to stringResource(R.string.core_ui_chip_syncing)
        SyncChipState.Synced -> c.success to stringResource(R.string.core_ui_chip_synced)
        is SyncChipState.Waiting -> c.warning to stringResource(R.string.core_ui_chip_waiting, localizedNumber(state.count.toLong()))
    }
    GlassSurface(modifier.semantics(mergeDescendants = true) { contentDescription = label }, RoundedCornerShape(AronTokens.Radius.Chip), elevation = AronTokens.Elevation.Page) {
        Row(Modifier.heightIn(min = 32.dp).padding(horizontal = AronTokens.Space.M, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(AronTokens.Space.S).background(dot, CircleShape))
            Spacer(Modifier.width(AronTokens.Space.S))
            Text(label, style = MaterialTheme.typography.labelMedium, color = c.textPrimary)
        }
    }
}
