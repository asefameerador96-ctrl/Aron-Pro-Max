package com.aktcl.aron.feature.sale.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronCard
import com.aktcl.aron.core.ui.AronConfirmDialog
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronStepper
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.feature.sale.R
import com.aktcl.aron.feature.sale.domain.ReviewProblem
import com.aktcl.aron.feature.sale.domain.SaleReview
import com.aktcl.aron.feature.sale.domain.SaleSku
import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.QtyUnit
import com.aktcl.aron.rules.UiLocale
import com.aktcl.aron.core.common.AppLanguage

/** Money in the current language's digits: `1,234.50 ৳` (docs/24 s7.1; the lead's ruling puts the sign after the amount). */
@Composable
fun money(mtk: Long, decimals: Int = 2): String =
    Formats.money(mtk, if (LocalAppLanguage.current == AppLanguage.BN) UiLocale.BN else UiLocale.EN, decimals)

@Composable
private fun unitLabel(unit: String): String = when (unit) {
    QtyUnit.PIECE.wire -> stringResource(R.string.sale_unit_piece)
    QtyUnit.DOZEN.wire -> stringResource(R.string.sale_unit_dozen)
    QtyUnit.PACK.wire -> stringResource(R.string.sale_unit_pack)
    else -> stringResource(R.string.sale_unit_stick)
}

/**
 * Sale: SKU quantity entry (F-SR-023). Quantities are in the SKU's own unit (sticks, pieces, dozens) with the unit shown,
 * a read-only pack badge, a running total and a stock warning that never blocks. Stateless: the caller owns the state
 * ([SaleFlow] in the domain package) and passes the current review.
 */
@Composable
fun SaleEntryScreen(
    skus: List<SaleSku>,
    review: SaleReview?,
    qtyOf: (Long) -> Long,
    onQuantity: (skuId: Long, qty: Long) -> Unit,
    onReview: () -> Unit,
    onZeroSaleConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var askZero by remember { mutableStateOf(false) }
    val lines = review?.lines.orEmpty().associateBy { it.sku.skuId }
    Column(modifier.fillMaxWidth()) {
        Text(stringResource(R.string.sale_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        if (skus.isEmpty() || review == null) AronEmptyState(stringResource(R.string.sale_empty))
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(skus, key = { it.skuId }) { sku ->
                val line = lines[sku.skuId]
                AronCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(if (LocalAppLanguage.current == AppLanguage.BN) (sku.nameBn ?: sku.name) else sku.name, style = MaterialTheme.typography.bodyLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AronStepper(
                                value = qtyOf(sku.skuId).toInt(), onValueChange = { onQuantity(sku.skuId, it.toLong()) },
                                minusLabel = stringResource(R.string.sale_less), plusLabel = stringResource(R.string.sale_more), min = 0, max = 999_999,
                            )
                            Text(unitLabel(sku.baseUnit), style = MaterialTheme.typography.bodyMedium)
                        }
                        if (line != null && line.packBadge.first > 0) {
                            Text(localizedDigits(stringResource(R.string.sale_pack_badge, line.packBadge.first.toString(), line.packBadge.second.toString())), style = MaterialTheme.typography.bodyMedium)
                        }
                        if (line?.exceedsStock == true) {
                            AronBanner(localizedDigits(stringResource(R.string.sale_stock_warning, (sku.stockBase ?: 0L).toString(), sku.name)), kind = BannerKind.Warning)
                        }
                    }
                }
            }
        }
        AronCard(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.sale_total), style = MaterialTheme.typography.titleMedium)
                Text(money(review?.totals?.grossMtk ?: 0), style = MaterialTheme.typography.titleMedium)
            }
        }
        val hasLines = review?.lines?.isNotEmpty() == true
        AronPrimaryButton(stringResource(R.string.sale_review), onReview, Modifier.padding(horizontal = 16.dp).fillMaxWidth(), enabled = hasLines)
        AronSecondaryButton(stringResource(R.string.sale_zero_sale), { askZero = true }, Modifier.padding(16.dp).fillMaxWidth(), enabled = !hasLines)
    }
    if (askZero) {
        AronConfirmDialog(
            stringResource(R.string.sale_zero_confirm_title), stringResource(R.string.sale_zero_confirm_message),
            stringResource(R.string.sale_yes), stringResource(R.string.sale_no),
            onConfirm = { askZero = false; onZeroSaleConfirmed() }, onDismiss = { askZero = false },
        )
    }
}

/** Review (নিরীক্ষণ) with category subtotals, every non-zero component and the net (F-SR-025). */
@Composable
fun ReviewScreen(review: SaleReview, onSave: () -> Unit, modifier: Modifier = Modifier) {
    AronCard(modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.review_title), style = MaterialTheme.typography.titleLarge)
            review.categories.forEach { c ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(c.categoryCode); Text("${localizedNumber(c.qtyBase)}  ${money(c.grossMtk)}")
                }
            }
            val t = review.totals
            Amount(R.string.review_gross, t.grossMtk)
            if (t.offerDiscountMtk != 0L) Amount(R.string.review_offer_discount, -t.offerDiscountMtk)
            if (t.drpDiscountMtk != 0L) Amount(R.string.review_drp_discount, -t.drpDiscountMtk)
            if (t.qcDeductionMtk != 0L) Amount(R.string.review_qc, -t.qcDeductionMtk)
            Amount(R.string.review_net, t.netMtk)
            if (review.settlement.isCredit) {
                Amount(R.string.review_paid, review.settlement.paidMtk)
                Amount(R.string.review_due, review.settlement.dueMtk)
            }
            review.problems.forEach { p -> AronBanner(problemText(p), kind = BannerKind.Error) }
            AronPrimaryButton(stringResource(R.string.review_save), onSave, Modifier.fillMaxWidth(), enabled = review.canCommit)
        }
    }
}

@Composable
private fun Amount(label: Int, mtk: Long) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(label)); Text(money(mtk))
    }
}

@Composable
private fun problemText(p: ReviewProblem): String = when (p) {
    is ReviewProblem.TooManyLines -> localizedDigits(stringResource(R.string.review_problem_too_many_lines, p.max.toString(), p.count.toString()))
    is ReviewProblem.UnitNotAllowed -> stringResource(R.string.review_problem_unit, p.skuId.toString())
    is ReviewProblem.PaidOutOfRange, is ReviewProblem.PaidNotPaisa, ReviewProblem.PaidNegative -> stringResource(R.string.review_problem_paid)
    ReviewProblem.NothingToSell -> stringResource(R.string.review_problem_nothing)
    else -> stringResource(R.string.review_problem_other)
}
