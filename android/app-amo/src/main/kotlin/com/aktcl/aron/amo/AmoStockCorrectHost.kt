package com.aktcl.aron.amo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.feature.amo.CorrectionReason
import com.aktcl.aron.feature.amo.CorrectionRow
import com.aktcl.aron.feature.amo.StockCorrectionContent
import com.aktcl.aron.feature.stock.StockAdjustments
import com.aktcl.aron.feature.stock.StockTracker
import kotlinx.coroutines.launch

/**
 * F-AMO-049: the SR's "correct total" path (F-SR-081, [StockAdjustments.correctTotal]) on the AMO's own stock. The balance is
 * recomputed from the ledger at Save, so a double tap or a retry after the commit finds nothing to correct and posts nothing.
 */
@Composable
fun AmoStockCorrectHost(day: AmoDay, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val bn = LocalAppLanguage.current == AppLanguage.BN
    var rows by remember { mutableStateOf<List<CorrectionRow>?>(null) }
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var text by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf<CorrectionReason?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    suspend fun load() {
        val bal = StockTracker.of(day.database, day.businessDate())
        // only SKUs that were ever loaded or sold today have a stock to correct
        rows = day.reference.skus().filter { bal[it.skuId]?.hasStockRow == true }.map { s ->
            CorrectionRow(s.skuId, if (bn) s.nameBn?.takeIf { it.isNotBlank() } ?: s.shortName else s.shortName, s.baseUnit, bal.getValue(s.skuId).current)
        }
    }
    LaunchedEffect(day, bn) { runCatching { load() }.onFailure { rows = emptyList() } }
    val list = rows ?: return
    StockCorrectionContent(
        list, selected, text, reason, saved, saving,
        onSelect = { selected = it; text = ""; reason = null; saved = false },
        onText = { text = it }, onReason = { reason = it },
        onSave = {
            val id = selected; val r = reason; val sku = list.firstOrNull { it.skuId == id }
            if (!saving && !saved && id != null && r != null && sku != null) {
                saving = true
                scope.launch {
                    val ok = runCatching {
                        val right = com.aktcl.aron.feature.amo.StockCorrection.parseTotal(text) ?: error("not a total")
                        day.correctStock(id, right, r.code, sku.unit)
                    }.isSuccess
                    saving = false; saved = ok
                    if (ok) { kotlinx.coroutines.delay(900); runCatching { load() }; day.forgetStock(); onDone() }
                }
            }
        },
    )
}
