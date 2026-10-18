package com.aktcl.aron.core.ui.aron

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.R
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber

/**
 * Every parity-kit component on one scrolling page, in the existing-Aron look (docs/ui-reference/PARITY-SPEC.md s2): Home header and
 * tiles, the Stock top bar with the printer badge, pack cards with silhouettes, the totals panel with Save and Print, the retailer
 * picker and letter filter, and the summary table. `AronLookGalleryTest` sweeps it; `KitGalleryShot` captures it.
 */
@Composable
fun AronLookGallery(modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    var qty by remember { mutableStateOf("0") }
    var letter by remember { mutableStateOf("") }
    var retailer by remember { mutableStateOf<String?>(null) }
    val all = stringResource(R.string.core_ui_look_all)
    val tiles = listOf(
        R.string.core_ui_look_tile_attendance to R.drawable.aron_art_attendance, R.string.core_ui_look_tile_stock to R.drawable.aron_art_stock,
        R.string.core_ui_look_tile_sale to R.drawable.aron_art_sale, R.string.core_ui_look_tile_memo to R.drawable.aron_art_memo,
        R.string.core_ui_look_tile_summary to R.drawable.aron_art_summary, R.string.core_ui_look_tile_submit to R.drawable.aron_art_sales_submit,
        R.string.core_ui_look_tile_outlet to R.drawable.aron_art_outlet, R.string.core_ui_look_tile_tutorial to R.drawable.aron_art_tutorial,
        R.string.core_ui_look_tile_tasks to R.drawable.aron_art_task_delegation, R.string.core_ui_look_tile_photo to R.drawable.aron_art_photo_capture,
        R.string.core_ui_look_tile_journey to R.drawable.aron_art_sales_journey, R.string.core_ui_look_tile_kpi to R.drawable.aron_art_kpi,
    )
    val outletMenu = listOf(
        R.string.core_ui_look_outlet_new to R.drawable.aron_art_outlet_new, R.string.core_ui_look_outlet_close to R.drawable.aron_art_outlet_close,
        R.string.core_ui_look_outlet_edit to R.drawable.aron_art_outlet_edit, R.string.core_ui_look_outlet_cluster to R.drawable.aron_art_cluster,
    )
    Column(modifier.fillMaxWidth().background(look.page).verticalScroll(rememberScrollState())) {
        AronHomeHeader(stringResource(R.string.core_ui_look_home_name), localizedDigits(stringResource(R.string.core_ui_look_home_route)), onSettings = {}, settingsLabel = stringResource(R.string.core_ui_look_settings))
        AronTileGrid(tiles, Modifier.background(look.pageHome).padding(6.dp)) { (label, icon), m ->
            AronTile(stringResource(label), icon = painterResource(icon), onClick = {}, modifier = m, badge = if (label == R.string.core_ui_look_tile_tasks) 3 else 0)
        }
        AronTopBar(stringResource(R.string.core_ui_look_tile_outlet), icon = painterResource(R.drawable.aron_art_outlet), onBack = {}, backLabel = stringResource(R.string.core_ui_back))
        AronTileGrid(outletMenu, Modifier.padding(12.dp), columns = 3) { (label, icon), m ->
            AronTile(stringResource(label), icon = painterResource(icon), onClick = {}, modifier = m, menu = true, labelColor = look.tileLabelOutlet)
        }
        Box(Modifier.zIndex(1f)) { AronTopBar(
            stringResource(R.string.core_ui_look_title_stock),
            icon = painterResource(R.drawable.aron_art_stock),
            onBack = {},
            backLabel = stringResource(R.string.core_ui_back),
            hanging = { AronPrinterBadge(connected = false, label = stringResource(R.string.core_ui_look_printer_off), onClick = {}) },
        ) }
        PackListHeader(stringResource(R.string.core_ui_look_col_sku), stringResource(R.string.core_ui_look_col_issue), stringResource(R.string.core_ui_look_col_stock))
        Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("MaxR-10S" to PackCategory.Cigarette, "SL-L1" to PackCategory.Lighter, "FB-M10" to PackCategory.Match).forEachIndexed { i, (code, category) ->
                PackCard(
                    skuId = code, skuCode = code,
                    quantity = if (i == 0) localizedDigits(qty) else localizedNumber(0),
                    onQuantityChange = { if (i == 0) qty = it }, onMinus = {}, onPlus = {},
                    stock = localizedNumber(if (i == 0) 0L else 394L),
                    minusLabel = stringResource(R.string.core_ui_look_less), plusLabel = stringResource(R.string.core_ui_look_more),
                    quantityLabel = stringResource(R.string.core_ui_look_quantity),
                    packBadge = localizedNumber(0), category = category,
                )
            }
        }
        AronScaffoldBottomSample()
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AronTargetCard(stringResource(R.string.core_ui_look_target_title), stringResource(R.string.core_ui_look_target_label), value = localizedDigits("4,771.50"))
            AronInfoCard(stringResource(R.string.core_ui_look_info), trailing = { AronPrinterBadge(connected = true, label = stringResource(R.string.core_ui_look_printer_on), onClick = {}) })
            RetailerPicker(retailer, listOf("FB", "SL", "MaxR-10S"), { it }, stringResource(R.string.core_ui_look_select_retailer), onSelect = { retailer = it })
            LetterFilterBar(listOf(all, "A", "B", "E", "J", "M", "N", "R", "S", "T", "a", "b", "c"), selected = letter.ifEmpty { all }, onSelect = { letter = it })
            SummaryTable(
                headers = listOf(stringResource(R.string.core_ui_look_memo), stringResource(R.string.core_ui_look_amount), stringResource(R.string.core_ui_look_value), stringResource(R.string.core_ui_look_return)),
                rows = listOf(
                    SummaryRow("MaxR-10S", listOf(localizedNumber(4), localizedNumber(375), localizedDigits("4,687.50"), localizedNumber(25))),
                    SummaryRow("FB", listOf(localizedNumber(1), localizedNumber(3), localizedDigits("84.00"), localizedNumber(197))),
                ),
                subtotals = listOf(TotalsLine(stringResource(R.string.core_ui_look_lighter), listOf(localizedNumber(375), localizedDigits("4,687.50"), localizedNumber(25)))),
                totals = listOf(SummaryTotal(stringResource(R.string.core_ui_look_grand_total), localizedDigits("4,771.50"))),
            )
            AronPrimaryButton(stringResource(R.string.core_ui_look_print), onClick = {}, modifier = Modifier.fillMaxWidth(), glyph = ButtonGlyph.Print, reverse = true)
            AronStatusDot(stringResource(R.string.core_ui_look_device_status), stringResource(R.string.core_ui_look_online), online = true)
            AronDataTable(
                headers = listOf(stringResource(R.string.core_ui_look_subject), stringResource(R.string.core_ui_look_device), stringResource(R.string.core_ui_look_server)),
                rows = listOf(
                    listOf(stringResource(R.string.core_ui_look_tile_outlet), localizedNumber(6), localizedNumber(0)),
                    listOf(stringResource(R.string.core_ui_look_tile_sale), localizedNumber(381), localizedNumber(0)),
                ),
            )
            AronNoticeCard(stringResource(R.string.core_ui_look_notice))
            AronPrimaryButton(stringResource(R.string.core_ui_look_sync), onClick = {}, modifier = Modifier.fillMaxWidth(), glyph = ButtonGlyph.Sync)
            AronPrimaryButton(stringResource(R.string.core_ui_look_tile_submit), onClick = {}, modifier = Modifier.fillMaxWidth(), glyph = ButtonGlyph.Submit, enabled = false)
        }
    }
}

@Composable
private fun AronScaffoldBottomSample() {
    val look = LocalAronLook.current
    Column(Modifier.padding(top = 12.dp).background(look.panel)) {
        TotalsPanel(
            header = TotalsLine(stringResource(R.string.core_ui_look_category), listOf(stringResource(R.string.core_ui_look_total_issue), stringResource(R.string.core_ui_look_col_stock))),
            lines = listOf(
                TotalsLine(stringResource(R.string.core_ui_look_cigarette), listOf(localizedNumber(0), localizedNumber(0))),
                TotalsLine(stringResource(R.string.core_ui_look_bidi), listOf(localizedNumber(0), localizedNumber(0))),
                TotalsLine(stringResource(R.string.core_ui_look_lighter), listOf(localizedNumber(400), localizedNumber(25))),
                TotalsLine(stringResource(R.string.core_ui_look_match), listOf(localizedNumber(400), localizedNumber(394))),
            ),
        )
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AronPrimaryButton(stringResource(R.string.core_ui_look_save), onClick = {}, modifier = Modifier.weight(1f), glyph = ButtonGlyph.Save)
            AronSecondaryButton(stringResource(R.string.core_ui_look_print), onClick = {}, modifier = Modifier.weight(1f), enabled = false, glyph = ButtonGlyph.Print)
        }
    }
}
