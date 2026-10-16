package com.aktcl.aron.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

private data class GalleryTile(val label: Int, val icon: ImageVector, val badge: Int = 0, val target: Boolean = false)

/**
 * Every kit component on one screen, inside the shared frame (top bar, scrolling content, bottom bar): the N-023
 * acceptance gallery and the visual reference for feature lanes. Amounts and names here are sample data, not real outlets.
 */
@Composable
fun KitGallery(modifier: Modifier = Modifier) {
    var qty by remember { mutableIntStateOf(12) }
    var confirm by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var sun by remember { mutableStateOf(false) }
    var big by remember { mutableIntStateOf(100000) }
    var segment by remember { mutableIntStateOf(0) }
    val todayTiles = remember {
        listOf(
            GalleryTile(R.string.core_ui_kit_gallery_tile_attendance, AronIcons.Attendance), GalleryTile(R.string.core_ui_kit_gallery_tile_stock, AronIcons.Stock),
            GalleryTile(R.string.core_ui_kit_gallery_tile_sale, AronIcons.Sale, target = true), GalleryTile(R.string.core_ui_kit_gallery_tile_memo, AronIcons.Memo),
            GalleryTile(R.string.core_ui_kit_gallery_tile_summary, AronIcons.Summary), GalleryTile(R.string.core_ui_kit_gallery_tile_submit, AronIcons.Submit),
        )
    }
    val otherTiles = remember {
        listOf(
            GalleryTile(R.string.core_ui_kit_gallery_tile_outlet, AronIcons.Outlet), GalleryTile(R.string.core_ui_kit_gallery_tile_tutorial, AronIcons.Tutorial),
            GalleryTile(R.string.core_ui_kit_gallery_tile_task, AronIcons.Tasks, badge = 120),
        )
    }
    val syncedTime = stringResource(R.string.core_ui_kit_gallery_synced_time)
    AronScreen(
        modifier = modifier,
        topBar = {
            AronTopBar(
                title = stringResource(R.string.core_ui_kit_gallery_title),
                subtitle = stringResource(R.string.core_ui_kit_gallery_subtitle),
                onBack = {},
                trailing = {
                    AronStatusChip(SyncChipState.SyncedAt(syncedTime), form = ChipForm.Compact, onClick = {})
                    SunlightToggle(sun, { sun = it })
                },
            )
        },
        bottomBar = {
            AronBottomBar(
                summaryLabel = stringResource(R.string.core_ui_kit_gallery_bar_total),
                summaryValue = localizedDigits("4,391.00 ৳"),
                secondary = AronBarAction(stringResource(R.string.core_ui_kit_gallery_bar_secondary), onClick = {}, icon = AronIcons.Print),
                primary = AronBarAction(stringResource(R.string.core_ui_kit_gallery_bar_primary), onClick = {}),
            )
        },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = AronTokens.Space.Xl)) {
            // status chips: every state, full form, then the compact and dot forms
            Column(Modifier.padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
                AronStatusChip(SyncChipState.SyncedAt(syncedTime), form = ChipForm.Full, onClick = {})
                AronStatusChip(SyncChipState.Offline, form = ChipForm.Full)
                AronStatusChip(SyncChipState.Syncing, form = ChipForm.Full)
                AronStatusChip(SyncChipState.Waiting(7), form = ChipForm.Full)
                Row(horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                    StatusChip(SyncChipState.Synced)
                    AronStatusChip(SyncChipState.Offline, form = ChipForm.Dot)
                    AronStatusChip(SyncChipState.Syncing, form = ChipForm.Dot)
                    AronStatusChip(SyncChipState.Waiting(3), form = ChipForm.Dot)
                }
            }
            AronHeroCard(
                label = stringResource(R.string.core_ui_kit_gallery_hero_label),
                moneyText = localizedDigits("4,391.00 ৳"),
                modifier = Modifier.padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S),
                meterLabel = stringResource(R.string.core_ui_kit_gallery_hero_meter),
                meterValue = 6, meterMax = 60, meterValueText = localizedDigits("6/60"),
                metaText = stringResource(R.string.core_ui_kit_gallery_hero_meta),
                onClick = {},
            )
            Row(Modifier.padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                AronStatCard(stringResource(R.string.core_ui_kit_gallery_kpi_strike), localizedDigits("10%"), Modifier.weight(1f))
                AronStatCard(stringResource(R.string.core_ui_kit_gallery_kpi_nonvisit), localizedDigits("54"), Modifier.weight(1f))
                AronStatCard(stringResource(R.string.core_ui_kit_gallery_kpi_nosale), localizedDigits("0"), Modifier.weight(1f))
            }
            AronSectionLabel(stringResource(R.string.core_ui_kit_gallery_section_today))
            AronTileGrid(todayTiles) { t, m -> AronTile(stringResource(t.label), t.icon, onClick = {}, modifier = m, badge = t.badge, target = t.target) }
            AronSectionLabel(stringResource(R.string.core_ui_kit_gallery_section_others))
            AronTileGrid(otherTiles) { t, m -> AronTile(stringResource(t.label), t.icon, onClick = {}, modifier = m, badge = t.badge, compact = true) }
            AronTile(stringResource(R.string.core_ui_kit_gallery_disabled), onClick = {}, enabled = false, modifier = Modifier.padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S))
            AronSegmented(
                listOf(stringResource(R.string.core_ui_kit_gallery_segment_all), stringResource(R.string.core_ui_kit_gallery_segment_visited, localizedDigits("6")), stringResource(R.string.core_ui_kit_gallery_segment_due)),
                selected = segment, onSelect = { segment = it }, modifier = Modifier.padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S),
            )
            Column(Modifier.padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                SunlightSuggestionChip(onSwitch = { sun = true }, onDismiss = {})
                OfflineBanner()
                AronBanner(stringResource(R.string.core_ui_kit_gallery_banner_success), kind = BannerKind.Success)
                AronBanner(
                    stringResource(R.string.core_ui_kit_gallery_banner_stale), kind = BannerKind.Warning,
                    title = stringResource(R.string.core_ui_kit_gallery_banner_stale_title),
                    actionLabel = stringResource(R.string.core_ui_kit_gallery_banner_action), onAction = {},
                )
                AronBanner(stringResource(R.string.core_ui_kit_gallery_error), kind = BannerKind.Error)
                AronBanner(stringResource(R.string.core_ui_kit_gallery_info_msg), kind = BannerKind.Info)
            }
            // a short list in one field card with dividers, then two flat rows of a long list
            AronCard(Modifier.padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S)) {
                Column {
                    AronListRow(stringResource(R.string.core_ui_kit_gallery_outlet), subtitle = stringResource(R.string.core_ui_kit_gallery_outlet_sub), trailing = localizedDigits("1,250.00"), onClick = {}, divider = true)
                    AronListRow(
                        stringResource(R.string.core_ui_kit_gallery_row_memo_no), subtitle = stringResource(R.string.core_ui_kit_gallery_row_visit), onClick = {},
                        leading = { androidx.compose.material3.Icon(AronIcons.Memo, contentDescription = null, tint = LocalAronColors.current.accent) },
                        trailingContent = { androidx.compose.material3.Icon(AronIcons.ChevronRight, contentDescription = null, tint = LocalAronColors.current.textSecondary) },
                    )
                }
            }
            Column(Modifier.padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S)) {
                AronListRow(stringResource(R.string.core_ui_kit_gallery_outlet), subtitle = stringResource(R.string.core_ui_kit_gallery_outlet_sub), trailing = localizedDigits("1,250.00"), onClick = {}, position = RowPosition.First, divider = true, flat = true)
                AronListRow(stringResource(R.string.core_ui_kit_gallery_row_memo_no), subtitle = stringResource(R.string.core_ui_kit_gallery_row_visit), onClick = {}, position = RowPosition.Last, flat = true)
            }
            LanguageToggle(LocalAppLanguage.current, onSelect = {}, modifier = Modifier.padding(horizontal = AronTokens.Space.Screen))
            AronStepper(qty, { qty = it }, stringResource(R.string.core_ui_kit_gallery_stepper_less), stringResource(R.string.core_ui_kit_gallery_stepper_more), Modifier.padding(AronTokens.Space.Screen), max = 999)
            Column(Modifier.padding(horizontal = AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                AronPrimaryButton(stringResource(R.string.core_ui_kit_gallery_save), onClick = {}, icon = AronIcons.Save)
                AronPrimaryButton(stringResource(R.string.core_ui_kit_gallery_bar_primary), onClick = {}, busy = true)
                Text(stringResource(R.string.core_ui_kit_gallery_bar_reason), style = MaterialTheme.typography.bodySmall, color = LocalAronColors.current.textSecondary)
                AronPrimaryButton(stringResource(R.string.core_ui_kit_gallery_disabled), onClick = {}, enabled = false)
                AronSecondaryButton(stringResource(R.string.core_ui_kit_gallery_cancel), onClick = {}, icon = AronIcons.Print)
                AronSecondaryButton(stringResource(R.string.core_ui_kit_gallery_disabled), onClick = {}, enabled = false)
                AronTextButton(stringResource(R.string.core_ui_kit_gallery_text_button), onClick = {}, icon = AronIcons.ChevronRight)
            }
            AronStepper(big, { big = it }, stringResource(R.string.core_ui_kit_gallery_stepper_less), stringResource(R.string.core_ui_kit_gallery_stepper_more), Modifier.padding(horizontal = AronTokens.Space.Screen), max = 9_999_999)
            AronSecondaryButton(stringResource(R.string.core_ui_kit_gallery_info), onClick = { info = true }, modifier = Modifier.padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.Xs))
            AronPressAndHoldButton(stringResource(R.string.core_ui_kit_gallery_disabled), onConfirmed = {}, enabled = false, modifier = Modifier.padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.Xs))
            AronPressAndHoldButton(stringResource(R.string.core_ui_kit_gallery_hold_submit), onConfirmed = { confirm = true }, modifier = Modifier.padding(AronTokens.Space.Screen))
            // loading: a skeleton with the geometry of a stat card
            Row(Modifier.padding(horizontal = AronTokens.Space.Screen), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                Box(Modifier.weight(1f).height(72.dp).skeleton(true))
                Box(Modifier.weight(1f).height(72.dp).skeleton(true))
                Text(stringResource(R.string.core_ui_kit_gallery_loading), style = MaterialTheme.typography.bodySmall, color = LocalAronColors.current.textSecondary, modifier = Modifier.weight(1f))
            }
            AronEmptyState(
                stringResource(R.string.core_ui_kit_gallery_empty), hint = stringResource(R.string.core_ui_kit_gallery_empty_hint),
                actionLabel = stringResource(R.string.core_ui_kit_gallery_empty_action), onAction = {}, syncedAt = syncedTime,
            )
            AronErrorState(stringResource(R.string.core_ui_kit_gallery_error), stringResource(R.string.core_ui_kit_gallery_retry), onRetry = {})
        }
    }
    if (info) AronInfoDialog(stringResource(R.string.core_ui_kit_gallery_info_title), stringResource(R.string.core_ui_kit_gallery_info_msg), stringResource(R.string.core_ui_kit_gallery_ok), onDismiss = { info = false })
    if (confirm) {
        AronConfirmDialog(
            stringResource(R.string.core_ui_kit_gallery_confirm_title), stringResource(R.string.core_ui_kit_gallery_confirm_msg),
            stringResource(R.string.core_ui_kit_gallery_save), stringResource(R.string.core_ui_kit_gallery_cancel),
            onConfirm = { confirm = false }, onDismiss = { confirm = false },
        )
    }
}
