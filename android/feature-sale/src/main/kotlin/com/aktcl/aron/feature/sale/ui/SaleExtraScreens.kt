package com.aktcl.aron.feature.sale.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronStepper
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.feature.sale.R
import com.aktcl.aron.feature.sale.domain.PaidInput
import com.aktcl.aron.feature.sale.domain.SaleReview
import com.aktcl.aron.feature.sale.domain.SaleSku

/** Credit prompt: the paid amount (0 or more, below the total, two decimals) and the due to the paisa (F-SR-026). */
@Composable
fun CreditPrompt(review: SaleReview, onPaid: (Long?) -> Unit, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    val parsed = PaidInput.parse(text)
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.credit_title), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = text, onValueChange = { text = it }, label = { Text(stringResource(R.string.credit_paid_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, isError = text.isNotEmpty() && parsed == null,
            modifier = Modifier.fillMaxWidth(),
        )
        if (parsed != null && parsed < review.totals.netMtk) Text(stringResource(R.string.credit_due_label, money(review.totals.netMtk - parsed)))
        AronPrimaryButton(stringResource(R.string.credit_apply), { onPaid(parsed) }, Modifier.fillMaxWidth(), enabled = parsed != null && parsed < review.totals.netMtk)
    }
}

/** Slide (DRP): only SKUs with an active offer; empty packets by stepper; the reward shows as a deduction (F-SR-022). */
@Composable
fun SlideScreen(skus: List<SaleSku>, review: SaleReview, emptyOf: (Long) -> Long, onEmpty: (Long, Long) -> Unit, modifier: Modifier = Modifier) {
    val offered = skus.filter { it.drp != null }
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.slide_title), style = MaterialTheme.typography.titleLarge)
        if (offered.isEmpty()) AronEmptyState(stringResource(R.string.slide_none))
        offered.forEach { sku ->
            Text(sku.name, style = MaterialTheme.typography.bodyLarge)
            AronStepper(
                emptyOf(sku.skuId).toInt(), { onEmpty(sku.skuId, it.toLong()) }, stringResource(R.string.sale_less), stringResource(R.string.sale_more),
                min = 0, max = 999_999, step = sku.drp!!.emptyPerReward.coerceAtMost(10),
            )
            review.drp.firstOrNull { it.skuId == sku.skuId }?.let {
                Text(stringResource(R.string.slide_reward, localizedNumber(it.rewardPacks), "− " + money(it.valueMtk)))
            }
        }
    }
}

/** Product QC: production and transport faults per SKU; the deduction is defect sticks times price (F-SR-027). */
@Composable
fun QcScreen(
    skus: List<SaleSku>,
    review: SaleReview,
    qtyOf: (skuId: Long, group: String) -> Long,
    onQty: (skuId: Long, group: String, qty: Long) -> Unit,
    locked: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.qc_title), style = MaterialTheme.typography.titleLarge)
        if (locked) AronBanner(stringResource(R.string.qc_locked), kind = BannerKind.Info)
        skus.forEach { sku ->
            Text(sku.name, style = MaterialTheme.typography.bodyLarge)
            listOf(PRODUCTION to R.string.qc_production, TRANSPORT to R.string.qc_transport).forEach { (group, label) ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(label), Modifier.weight(1f))
                    AronStepper(
                        qtyOf(sku.skuId, group).toInt(), { if (!locked) onQty(sku.skuId, group, it.toLong()) },
                        stringResource(R.string.sale_less), stringResource(R.string.sale_more), min = 0, max = 999_999,
                    )
                }
            }
        }
        Text(stringResource(R.string.qc_deduction, "− " + money(review.totals.qcDeductionMtk)), style = MaterialTheme.typography.titleMedium)
        AronPrimaryButton(stringResource(R.string.qc_done), onDone, Modifier.fillMaxWidth(), enabled = !locked)
    }
}

const val PRODUCTION = "MFC"
const val TRANSPORT = "TFC"
