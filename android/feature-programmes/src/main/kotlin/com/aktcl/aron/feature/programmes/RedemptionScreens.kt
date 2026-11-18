package com.aktcl.aron.feature.programmes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.AronOutlinedButton
import com.aktcl.aron.core.ui.aron.AronSolidButton
import com.aktcl.aron.core.ui.aron.LetterFilterBar
import com.aktcl.aron.core.ui.aron.RetailerPicker
import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.UiLocale
import kotlinx.coroutines.launch

object RedemptionTags {
    const val PICKER = "redeem_picker"
    const val BANNER = "redeem_banner"
    const val OVERDRAWN = "redeem_overdrawn"
    const val CONFIRM = "redeem_confirm"
    const val DIALOG_OK = "redeem_dialog_ok"
    const val DIALOG_CANCEL = "redeem_dialog_cancel"
    const val SUCCESS_OK = "redeem_success_ok"
    const val EMPTY = "redeem_empty"
    const val FAILED = "redeem_failed"
    fun plus(giftId: Long) = "redeem_plus_$giftId"
    fun minus(giftId: Long) = "redeem_minus_$giftId"
    fun qty(giftId: Long) = "redeem_qty_$giftId"
}

/**
 * Gift redemption (manual 53 right, 54): pick the retailer (letter filter), see the programme banner and the points remaining, step gifts up and down,
 * confirm. The remaining points are the bundle balance minus what this phone redeemed since ([LocalRedeemed]) minus the basket. The phone never
 * blocks on the balance (the server accepts and flags an overdraw): it says so on the banner and in the confirm dialog. [onConfirm] writes the
 * records (idempotent by the redemption uuid fixed when the basket opens) and returns false when nothing could be saved; the success dialog says
 * "saved on this phone", never "posted".
 */
@Composable
fun RedemptionScreen(
    outlets: List<ShopOutlet>, data: LoyaltyData?, locale: UiLocale,
    balanceOf: (outletId: Long, programmeId: Long, bundleBalance: Long) -> Long,
    onConfirm: suspend (Basket, List<LoyaltyGift>) -> Boolean,
    modifier: Modifier = Modifier, initialOutlet: Long? = null, initialQty: Map<Long, Int> = emptyMap(),
) {
    val look = LocalAronLook.current
    var outletId by rememberSaveable { mutableStateOf(initialOutlet) }
    var letter by rememberSaveable { mutableStateOf("") }
    var qtys by rememberSaveable(outletId) { mutableStateOf(initialQty) }
    var asking by rememberSaveable { mutableStateOf(false) }
    var saved by rememberSaveable { mutableStateOf(false) }
    var failed by rememberSaveable { mutableStateOf(false) }
    var busy by androidx.compose.runtime.remember { mutableStateOf(false) } // one save at a time: a second tap while the first is running is ignored
    val scope = rememberCoroutineScope()
    val withPoints = data?.let { d -> outlets.filter { it.outletId in d.outletIds } }.orEmpty()
    Column(modifier.fillMaxSize().background(look.page).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (data == null || withPoints.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 80.dp).testTag(RedemptionTags.EMPTY), contentAlignment = Alignment.Center) {
                Text(stringResource(if (data == null) R.string.pg_loyalty_none else R.string.pg_loyalty_no_outlets), style = TextStyle(fontSize = 18.sp, color = look.ink, textAlign = TextAlign.Center))
            }
            return@Column
        }
        val all = stringResource(R.string.pg_all)
        val letters = listOf(all) + withPoints.map { it.chip }.filter { it.isNotBlank() }.distinct().sorted()
        val shownLetter = letter.ifEmpty { all }
        val shown = if (shownLetter == all) withPoints else withPoints.filter { it.chip == shownLetter }
        val chosen = withPoints.firstOrNull { it.outletId == outletId }
        RetailerPicker(chosen, shown, { it.nameLine }, stringResource(R.string.pg_loyalty_pick_outlet), { outletId = it.outletId }, Modifier.fillMaxWidth().testTag(RedemptionTags.PICKER))
        LetterFilterBar(letters, shownLetter, { letter = if (it == all) "" else it })
        if (chosen == null) return@Column
        val card = data.cardsOf(chosen.outletId).firstOrNull() ?: return@Column
        val gifts = data.gifts.filter { it.programmeId == card.programmeId && it.active && (it.pointsCost ?: 0L) >= 1L }
        val basket = Basket(chosen.outletId, card.programmeId, balanceOf(chosen.outletId, card.programmeId, card.balancePoints), qtys)
        val remaining = basket.remaining(gifts)
        val unit = stringResource(R.string.pg_pts)
        Row(Modifier.fillMaxWidth().testTag(RedemptionTags.BANNER).clip(RoundedCornerShape(12.dp)).background(look.red).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(data.title(card, locale), Modifier.weight(1f), style = TextStyle(fontSize = 16.sp, color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold))
            Text(stringResource(R.string.pg_remaining, LoyaltyModel.pointsText(remaining, locale, unit)), style = TextStyle(fontSize = 15.sp, color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold))
        }
        if (remaining < 0) Text(stringResource(R.string.pg_loyalty_overdrawn), Modifier.testTag(RedemptionTags.OVERDRAWN), style = TextStyle(fontSize = 14.sp, color = look.red, fontWeight = FontWeight.Medium))
        if (gifts.isEmpty()) {
            Text(stringResource(R.string.pg_no_gifts), Modifier.testTag(RedemptionTags.EMPTY), style = TextStyle(fontSize = 16.sp, color = look.ink))
        } else {
            val cols = if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f) 1 else 2
            gifts.chunked(cols).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { g -> GiftCard(g, basket.qtyOf(g), locale, { qtys = basket.plus(g).qty }, { qtys = basket.minus(g).qty }, Modifier.weight(1f)) }
                    if (row.size < cols) Box(Modifier.weight((cols - row.size).toFloat()))
                }
            }
        }
        AronSolidButton(stringResource(R.string.pg_confirm_redemption), { asking = true }, Modifier.fillMaxWidth().testTag(RedemptionTags.CONFIRM), enabled = !basket.isEmpty())
        if (failed) Text(stringResource(R.string.pg_redeem_failed), Modifier.testTag(RedemptionTags.FAILED), style = TextStyle(fontSize = 14.sp, color = look.red))
        if (asking) {
            AlertDialog(
                onDismissRequest = { asking = false },
                title = { Text(stringResource(R.string.pg_confirm_title), style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.pg_confirm_text))
                        if (remaining < 0) Text(stringResource(R.string.pg_loyalty_overdrawn), color = look.red, fontWeight = FontWeight.Medium)
                    }
                },
                confirmButton = {
                    AronSolidButton(stringResource(R.string.pg_confirm_yes), {
                        asking = false
                        if (!busy) {
                            busy = true
                            scope.launch {
                                try { if (onConfirm(basket, gifts)) { saved = true; failed = false; qtys = emptyMap() } else failed = true } finally { busy = false }
                            }
                        }
                    }, Modifier.testTag(RedemptionTags.DIALOG_OK))
                },
                dismissButton = { AronOutlinedButton(stringResource(R.string.pg_confirm_no), { asking = false }, Modifier.testTag(RedemptionTags.DIALOG_CANCEL)) },
            )
        }
        if (saved) {
            AlertDialog(
                onDismissRequest = { saved = false },
                title = { Text(stringResource(R.string.pg_redeem_saved), style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold)) },
                confirmButton = { AronSolidButton(stringResource(R.string.pg_ok), { saved = false }, Modifier.testTag(RedemptionTags.SUCCESS_OK)) },
            )
        }
    }
}

@Composable
private fun GiftCard(g: LoyaltyGift, qty: Int, locale: UiLocale, onPlus: () -> Unit, onMinus: () -> Unit, modifier: Modifier) {
    val look = LocalAronLook.current
    val unit = stringResource(R.string.pg_pts)
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(look.card).then(if (qty > 0) Modifier.border(2.dp, look.purple, RoundedCornerShape(12.dp)) else Modifier).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(g.name(locale), style = TextStyle(fontSize = 15.sp, color = look.ink, textAlign = TextAlign.Center))
        Text(LoyaltyModel.pointsText(g.pointsCost ?: 0L, locale, unit), style = TextStyle(fontSize = 14.sp, color = look.purple, fontWeight = FontWeight.Bold))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AronOutlinedButton("−", onMinus, Modifier.testTag(RedemptionTags.minus(g.id)), enabled = qty > 0)
            Text(Formats.integer(qty.toLong(), locale), Modifier.testTag(RedemptionTags.qty(g.id)), style = TextStyle(fontSize = 18.sp, color = look.ink, fontWeight = FontWeight.Bold))
            AronOutlinedButton("+", onPlus, Modifier.testTag(RedemptionTags.plus(g.id)), enabled = qty < Basket.MAX_QTY)
        }
    }
}
