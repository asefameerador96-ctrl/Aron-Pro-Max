package com.aktcl.aron.amo

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
import com.aktcl.aron.core.ui.AronListRow
import com.aktcl.aron.core.ui.AronScreen
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.AronTopBar
import com.aktcl.aron.core.ui.RowPosition
import com.aktcl.aron.feature.home.R as HomeR

private enum class SaleLeaf { LIST, SALE, STOCK, MEMO, EDIT, SUMMARY }

/**
 * The AMO Sale hub (docs/07): Sale (the geo-gated Control Call path with Sale preselected, F-AMO-012; there is no un-gated AMO
 * sale), Stock (F-AMO-029), Memo (F-AMO-014) and Summary (F-AMO-015). Each leaf is the SR's screen on the AMO's own data.
 */
@Composable
fun AmoSaleHub(day: AmoDay, onBack: () -> Unit) {
    var leaf by rememberSaveable { mutableStateOf(SaleLeaf.LIST) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val back: () -> Unit = {
        when (leaf) {
            SaleLeaf.LIST -> onBack()
            SaleLeaf.EDIT -> leaf = SaleLeaf.MEMO
            else -> leaf = SaleLeaf.LIST
        }
    }
    when (leaf) {
        SaleLeaf.SALE -> ControlCallHost(day, onBack = { leaf = SaleLeaf.LIST }, startWithSale = true)
        else -> {
            BackHandler(onBack = back)
            AronScreen(topBar = {
                AronTopBar(
                    stringResource(
                        when (leaf) {
                            SaleLeaf.STOCK -> HomeR.string.hm_tile_stock; SaleLeaf.MEMO, SaleLeaf.EDIT -> HomeR.string.hm_tile_memo
                            SaleLeaf.SUMMARY -> HomeR.string.hm_tile_summary; else -> HomeR.string.hm_tile_sale
                        },
                    ),
                    onBack = back,
                )
            }) {
                when (leaf) {
                    SaleLeaf.LIST -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen)) {
                        val rows = listOf(SaleLeaf.SALE to HomeR.string.hm_tile_sale, SaleLeaf.STOCK to HomeR.string.hm_tile_stock, SaleLeaf.MEMO to HomeR.string.hm_tile_memo, SaleLeaf.SUMMARY to HomeR.string.hm_tile_summary)
                        rows.forEachIndexed { i, (l, label) ->
                            AronListRow(
                                stringResource(label), onClick = { leaf = l },
                                position = when (i) { 0 -> RowPosition.First; rows.lastIndex -> RowPosition.Last; else -> RowPosition.Middle }, divider = i != rows.lastIndex, flat = true,
                            )
                        }
                    }
                    SaleLeaf.STOCK -> AmoStockHost(day)
                    SaleLeaf.MEMO -> AmoMemoHost(day, onEdit = { editing = it; leaf = SaleLeaf.EDIT })
                    SaleLeaf.EDIT -> editing?.let { AmoEditHost(day, it, onDone = { leaf = SaleLeaf.MEMO }) }
                    SaleLeaf.SUMMARY -> AmoSummaryHost(day)
                    SaleLeaf.SALE -> Unit
                }
            }
        }
    }
}
