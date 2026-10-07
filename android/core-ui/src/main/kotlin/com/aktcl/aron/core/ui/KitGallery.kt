package com.aktcl.aron.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

private data class GalleryTile(val label: Int, val badge: Int)

/** Every kit component on one scrollable screen: the N-023 acceptance gallery and a visual reference for feature lanes. */
@Composable
fun KitGallery(modifier: Modifier = Modifier) {
    var qty by remember { mutableIntStateOf(12) }
    var confirm by remember { mutableStateOf(false) }
    val tiles = remember {
        listOf(
            GalleryTile(R.string.core_ui_kit_gallery_tile_attendance, 0), GalleryTile(R.string.core_ui_kit_gallery_tile_stock, 0),
            GalleryTile(R.string.core_ui_kit_gallery_tile_sale, 0), GalleryTile(R.string.core_ui_kit_gallery_tile_task, 3),
        )
    }
    Surface(modifier, color = MaterialTheme.colorScheme.background) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            Text(stringResource(R.string.core_ui_kit_gallery_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
            OfflineBanner()
            AronTileGrid(tiles, columns = 2) { t, m -> AronTile(stringResource(t.label), onClick = {}, modifier = m, badge = t.badge) }
            AronListRow(stringResource(R.string.core_ui_kit_gallery_outlet), subtitle = stringResource(R.string.core_ui_kit_gallery_outlet_sub), trailing = localizedNumber(1250), onClick = {})
            AronStepper(qty, { qty = it }, stringResource(R.string.core_ui_kit_gallery_stepper_less), stringResource(R.string.core_ui_kit_gallery_stepper_more), Modifier.padding(16.dp), max = 999)
            AronPrimaryButton(stringResource(R.string.core_ui_kit_gallery_save), onClick = {}, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            AronSecondaryButton(stringResource(R.string.core_ui_kit_gallery_cancel), onClick = {}, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            AronPressAndHoldButton(stringResource(R.string.core_ui_kit_gallery_hold_submit), onConfirmed = { confirm = true }, modifier = Modifier.padding(16.dp))
            AronBanner(stringResource(R.string.core_ui_kit_gallery_error), kind = BannerKind.Error)
            AronEmptyState(stringResource(R.string.core_ui_kit_gallery_empty), hint = stringResource(R.string.core_ui_kit_gallery_empty_hint))
            AronErrorState(stringResource(R.string.core_ui_kit_gallery_error), stringResource(R.string.core_ui_kit_gallery_retry), onRetry = {})
        }
        if (confirm) {
            AronConfirmDialog(
                stringResource(R.string.core_ui_kit_gallery_confirm_title), stringResource(R.string.core_ui_kit_gallery_confirm_msg),
                stringResource(R.string.core_ui_kit_gallery_save), stringResource(R.string.core_ui_kit_gallery_cancel),
                onConfirm = { confirm = false }, onDismiss = { confirm = false },
            )
        }
    }
}
