package com.aktcl.aron.amo

import com.aktcl.aron.feature.amo.AmoListRow
import com.aktcl.aron.feature.amo.AmoSecondary
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import com.aktcl.aron.feature.amo.R as AmoR
import com.aktcl.aron.feature.amo.AmoFrame
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.AronTopBar
import com.aktcl.aron.core.ui.RowPosition
import com.aktcl.aron.feature.home.R as HomeR

private enum class SaleLeaf { LIST, SALE, STOCK, CORRECT, MEMO, EDIT, SUMMARY }

/**
 * The AMO Sale hub (docs/07): Sale (the geo-gated Control Call path with Sale preselected, F-AMO-012; there is no un-gated AMO
 * sale), Stock (F-AMO-029), Memo (F-AMO-014) and Summary (F-AMO-015). Each leaf is the SR's screen on the AMO's own data.
 */
@Composable
fun AmoSaleHub(day: AmoDay, onBack: () -> Unit, start: com.aktcl.aron.feature.amo.AmoLeaf? = null) {
    // D-AMO-MAN-02: Home has one tile each for Stock, Sales, Memo and Summary; the host opens on [start] and Back from that leaf leaves the host
    val direct = start != null
    val first = when (start) {
        com.aktcl.aron.feature.amo.AmoLeaf.STOCK -> SaleLeaf.STOCK; com.aktcl.aron.feature.amo.AmoLeaf.SALE -> SaleLeaf.SALE
        com.aktcl.aron.feature.amo.AmoLeaf.MEMO -> SaleLeaf.MEMO; com.aktcl.aron.feature.amo.AmoLeaf.SUMMARY -> SaleLeaf.SUMMARY; else -> SaleLeaf.LIST
    }
    var leaf by rememberSaveable { mutableStateOf(first) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val back: () -> Unit = {
        when (leaf) {
            SaleLeaf.LIST -> onBack()
            SaleLeaf.EDIT -> leaf = SaleLeaf.MEMO
            SaleLeaf.CORRECT -> leaf = SaleLeaf.STOCK
            else -> if (direct) onBack() else leaf = SaleLeaf.LIST
        }
    }
    when (leaf) {
        SaleLeaf.SALE -> ControlCallHost(day, onBack = { if (direct) onBack() else leaf = SaleLeaf.LIST }, startWithSale = true)
        else -> {
            BackHandler(onBack = back)
            // Stock, Memo and Summary show the printer status (manual: icon and "printer connected" banner), as the SR screens do.
            val printed = leaf == SaleLeaf.STOCK || leaf == SaleLeaf.MEMO || leaf == SaleLeaf.SUMMARY
            if (printed) { com.aktcl.aron.core.printing.ui.HoldPrinter(day.printerManager); com.aktcl.aron.core.printing.ui.PrinterConnectedToast(day.printerManager) }
            AmoFrame(
                stringResource(
                    when (leaf) {
                        SaleLeaf.STOCK -> HomeR.string.hm_tile_stock; SaleLeaf.CORRECT -> AmoR.string.amo_sc_title; SaleLeaf.MEMO, SaleLeaf.EDIT -> HomeR.string.hm_tile_memo
                        SaleLeaf.SUMMARY -> AmoR.string.amo_summary_title; else -> HomeR.string.hm_tile_sale
                    },
                ),
                back,
                icon = when (leaf) {
                    SaleLeaf.STOCK, SaleLeaf.CORRECT -> com.aktcl.aron.core.ui.R.drawable.aron_art_stock
                    SaleLeaf.MEMO, SaleLeaf.EDIT -> com.aktcl.aron.core.ui.R.drawable.aron_art_memo
                    SaleLeaf.SUMMARY -> com.aktcl.aron.core.ui.R.drawable.aron_art_summary
                    else -> com.aktcl.aron.core.ui.R.drawable.aron_art_sale
                },
                trailing = if (printed) ({ com.aktcl.aron.core.printing.ui.PrinterIcon(day.printerManager) }) else null,
            ) {
                if (printed) Column(Modifier.fillMaxSize()) {
                    com.aktcl.aron.core.printing.ui.PrinterBanner(day.printerManager)
                    Box(Modifier.weight(1f).fillMaxWidth()) { SaleLeafBody(day, leaf, { leaf = it }, editing, { editing = it }) }
                } else SaleLeafBody(day, leaf, { leaf = it }, editing, { editing = it })
            }
        }
    }
}

@Composable
private fun SaleLeafBody(day: AmoDay, leaf: SaleLeaf, setLeaf: (SaleLeaf) -> Unit, editing: String?, setEditing: (String?) -> Unit) {
    when (leaf) {
        SaleLeaf.LIST -> {
            // Manual order: Stock, Sale, Memo, Summary.
            val rows = listOf(
                Triple(SaleLeaf.STOCK, HomeR.string.hm_tile_stock, com.aktcl.aron.core.ui.R.drawable.aron_art_stock), Triple(SaleLeaf.SALE, HomeR.string.hm_tile_sale, com.aktcl.aron.core.ui.R.drawable.aron_art_sale),
                Triple(SaleLeaf.MEMO, HomeR.string.hm_tile_memo, com.aktcl.aron.core.ui.R.drawable.aron_art_memo), Triple(SaleLeaf.SUMMARY, HomeR.string.hm_tile_summary, com.aktcl.aron.core.ui.R.drawable.aron_art_summary),
            ).map { (l, label, icon) -> com.aktcl.aron.feature.amo.HubRow(stringResource(label), onClick = { setLeaf(l) }, icon = icon) }
            com.aktcl.aron.feature.amo.HubListContent(listOf(com.aktcl.aron.feature.amo.HubSection(null, rows)))
        }
        SaleLeaf.STOCK -> Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { AmoStockHost(day) }
            AmoSecondary(stringResource(AmoR.string.amo_sc_title), { setLeaf(SaleLeaf.CORRECT) }, Modifier.fillMaxWidth().padding(AronTokens.Space.Screen))
        }
        SaleLeaf.CORRECT -> AmoStockCorrectHost(day, onDone = { setLeaf(SaleLeaf.STOCK) })
        SaleLeaf.MEMO -> AmoMemoHost(day, onEdit = { setEditing(it); setLeaf(SaleLeaf.EDIT) })
        SaleLeaf.EDIT -> editing?.let { AmoEditHost(day, it, onDone = { setLeaf(SaleLeaf.MEMO) }) }
        SaleLeaf.SUMMARY -> AmoSummaryHost(day)
        SaleLeaf.SALE -> Unit
    }
}
