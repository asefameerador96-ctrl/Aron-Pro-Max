package com.aktcl.aron.feature.programmes

import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.AronDropdown
import com.aktcl.aron.core.ui.aron.AronOutlinedButton
import com.aktcl.aron.core.ui.aron.AronTile
import com.aktcl.aron.rules.UiLocale
import com.aktcl.aron.core.ui.R as CoreR

object LoyaltyTags {
    const val MENU_REDEEM = "loyalty_menu_redeem"
    const val MENU_POINTS = "loyalty_menu_points"
    const val OUTLET = "loyalty_outlet"
    const val EMPTY = "loyalty_empty"
    const val REDEEM = "loyalty_redeem"
    fun card(programmeId: Long) = "loyalty_card_$programmeId"
    fun overdrawn(programmeId: Long) = "loyalty_overdrawn_$programmeId"
}

/** The Loyalty Point menu (manual 53 left): Redemption; Points is the interim entry of the points page (the manual opens it from the Sales screen). A tile whose screen does not exist yet is left out. */
@Composable
fun LoyaltyMenuScreen(onPoints: (() -> Unit)?, onRedeem: (() -> Unit)?, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Box(modifier.fillMaxSize().background(look.page).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            if (onRedeem != null) AronTile(stringResource(R.string.pg_loyalty_redeem), painterResource(CoreR.drawable.aron_art_loyalty_point), onRedeem, Modifier.weight(1f).testTag(LoyaltyTags.MENU_REDEEM), menu = true, labelColor = look.tileLabelOutlet)
            if (onPoints != null) AronTile(stringResource(R.string.pg_loyalty_points), painterResource(CoreR.drawable.aron_art_loyalty_point), onPoints, Modifier.weight(1f).testTag(LoyaltyTags.MENU_POINTS), menu = true, labelColor = look.tileLabelOutlet)
            if (onRedeem == null || onPoints == null) Box(Modifier.weight(1f))
        }
    }
}

/**
 * The outlet points page (manual 55): an outlet picker, and for the chosen outlet one card per programme: the league label, the balance
 * ("110 Pts"), the points about to expire and the expiry date. The numbers are the bundle's balance as of [LoyaltyCard.asOfDate]; they are
 * real, a zero balance shows 0 and a negative one is flagged as overdrawn (never clamped). [onRedeem] opens the redemption of the outlet; null hides the button.
 */
@Composable
fun OutletPointsScreen(
    outlets: List<ShopOutlet>, data: LoyaltyData?, locale: UiLocale, modifier: Modifier = Modifier,
    onRedeem: ((Long) -> Unit)? = null, initialOutlet: Long? = null,
    /** The same balance function as the redemption screen: the bundle's balance minus this phone's own redemptions. */
    balanceOf: (outletId: Long, programmeId: Long, bundleBalance: Long) -> Long = { _, _, b -> b },
) {
    val look = LocalAronLook.current
    var outletId by rememberSaveable { mutableStateOf(initialOutlet) }
    val withPoints = data?.let { d -> outlets.filter { it.outletId in d.outletIds } }.orEmpty()
    Column(modifier.fillMaxSize().background(look.page).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (data == null || withPoints.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 80.dp).testTag(LoyaltyTags.EMPTY), contentAlignment = Alignment.Center) {
                Text(stringResource(if (data == null) R.string.pg_loyalty_none else R.string.pg_loyalty_no_outlets), style = TextStyle(fontSize = 18.sp, color = look.ink, textAlign = TextAlign.Center))
            }
            return@Column
        }
        val chosen = withPoints.firstOrNull { it.outletId == outletId }
        AronDropdown(withPoints, chosen, { outletId = it.outletId }, { it.nameLine }, Modifier.testTag(LoyaltyTags.OUTLET), placeholder = stringResource(R.string.pg_loyalty_pick_outlet))
        if (chosen == null) return@Column
        if (onRedeem != null) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(look.card).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.pg_loyalty_manage), style = TextStyle(fontSize = 14.sp, color = look.inkMuted))
                AronOutlinedButton(stringResource(R.string.pg_loyalty_redeem), { onRedeem(chosen.outletId) }, Modifier.testTag(LoyaltyTags.REDEEM))
            }
        }
        val unit = stringResource(R.string.pg_pts)
        data.cardsOf(chosen.outletId).forEach { c ->
            val balance = balanceOf(chosen.outletId, c.programmeId, c.balancePoints)
            Column(Modifier.fillMaxWidth().testTag(LoyaltyTags.card(c.programmeId)).clip(RoundedCornerShape(12.dp)).background(look.card).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(data.title(c, locale), Modifier.weight(1f), style = TextStyle(fontSize = 17.sp, color = look.purple, fontWeight = FontWeight.Bold))
                    Text(LoyaltyModel.pointsText(balance, locale, unit), style = TextStyle(fontSize = 18.sp, color = look.ink, fontWeight = FontWeight.Bold))
                }
                if (PointsBalance.overdrawn(balance)) Text(stringResource(R.string.pg_loyalty_overdrawn), Modifier.testTag(LoyaltyTags.overdrawn(c.programmeId)), style = TextStyle(fontSize = 14.sp, color = look.red, fontWeight = FontWeight.Medium))
                Line(R.string.pg_loyalty_expiring, LoyaltyModel.pointsText(c.expiringPoints, locale, unit))
                Line(R.string.pg_loyalty_expiry, LoyaltyModel.dateText(c.expiryDate, locale))
                c.asOfDate?.let { Text(stringResource(R.string.pg_as_of, LoyaltyModel.dateText(it, locale)), style = TextStyle(fontSize = 12.sp, color = look.inkMuted)) }
            }
        }
    }
}

@Composable
private fun Line(labelRes: Int, value: String) {
    val look = LocalAronLook.current
    val labelStyle = TextStyle(fontSize = 14.sp, color = look.inkMuted)
    val valueStyle = TextStyle(fontSize = 15.sp, color = look.ink, fontWeight = FontWeight.Bold)
    // at a large font (fontScale 1.5 and up) the value goes under its label, so neither is squeezed into a one-letter column
    if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f) {
        Column(Modifier.fillMaxWidth()) { Text(stringResource(labelRes), style = labelStyle); Text(value, style = valueStyle) }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(labelRes), Modifier.weight(1f), style = labelStyle); Text(value, style = valueStyle)
        }
    }
}
