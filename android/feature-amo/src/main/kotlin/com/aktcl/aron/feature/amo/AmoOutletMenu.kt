package com.aktcl.aron.feature.amo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.localizedNumber
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.R as UiR
import com.aktcl.aron.core.ui.aron.AronTile
import com.aktcl.aron.core.ui.aron.AronTileGrid
import com.aktcl.aron.feature.outlet.R as OutR

/** The five shop operations of the vendor's menu (screenshot 46), in its order. */
enum class AmoOutletOp { NEW, CLOSE, INFO, UPDATE_BASE, CLUSTER }

/** The three verification groups of the SRs' requests (the red badges of screenshot 46). */
enum class VerifyGroup(val types: Set<String>) {
    NEW(setOf("new")), CLOSE(setOf("close")), CHANGE(setOf("info", "cluster", "location", "route_add"));

    fun matches(type: String) = type in types

    companion object {
        fun of(type: String): VerifyGroup = entries.first { it.matches(type) }
    }
}

object OutletMenuTags {
    fun verify(g: VerifyGroup) = "om_verify_${g.name.lowercase()}"
    fun op(o: AmoOutletOp) = "om_op_${o.name.lowercase()}"
    fun badge(g: VerifyGroup) = "om_badge_${g.name.lowercase()}"
}

/**
 * The Outlet menu of the AMO (46): the heading "এস আর থেকে আসা অনুরোধ গুলো যাচাই করুন" over three tiles with red count badges, the heading
 * "দোকান সংক্রান্ত অপারেশন্স" over five tiles (new shop, permanent close, change information, update base, cluster), three to a row, two from
 * 130 % font and one from 190 % (as the SR menu). [counts] are the pending requests per group; a zero badge is drawn as the vendor does (0).
 */
@Composable
fun AmoOutletMenuContent(counts: Map<VerifyGroup, Int>, onVerify: (VerifyGroup) -> Unit, onOp: (AmoOutletOp) -> Unit, modifier: Modifier = Modifier, onOwn: (() -> Unit)? = null) {
    val look = LocalAronLook.current
    val scale = LocalDensity.current.fontScale
    val columns = if (scale >= AronTokens.Size.OneColumnFontScale) 1 else if (scale >= 1.3f) 2 else 3
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)).padding(AronTokens.Space.Screen),
        verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
    ) {
        Text(stringResource(R.string.amo_out_verify_heading), Modifier.semantics { heading() }, style = amoText(17, bold = true, color = look.ink))
        AronTileGrid(VerifyGroup.entries, columns = columns) { g, mod ->
            val n = counts[g] ?: 0
            Box(mod) {
                AronTile(
                    label = stringResource(when (g) { VerifyGroup.NEW -> R.string.amo_out_tile_verify_new; VerifyGroup.CLOSE -> R.string.amo_out_tile_verify_close; VerifyGroup.CHANGE -> R.string.amo_out_tile_verify_change }),
                    icon = painterResource(when (g) { VerifyGroup.NEW -> UiR.drawable.aron_art_outlet_new; VerifyGroup.CLOSE -> UiR.drawable.aron_art_outlet_close; VerifyGroup.CHANGE -> UiR.drawable.aron_art_outlet_edit }),
                    onClick = { onVerify(g) }, modifier = Modifier.fillMaxWidth().testTag(OutletMenuTags.verify(g)), badge = n, menu = true, labelColor = look.tileLabelOutlet,
                )
                // the vendor draws the badge at zero too (46); the kit tile draws it from 1, so the zero is ours
                if (n == 0) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(4.dp).sizeIn(minWidth = 20.dp, minHeight = 20.dp).clip(CircleShape).background(look.badge).testTag(OutletMenuTags.badge(g)),
                        contentAlignment = Alignment.Center,
                    ) { Text(localizedNumber(0L), style = amoText(11, bold = true, color = Color.White), modifier = Modifier.padding(horizontal = 4.dp)) }
                }
            }
        }
        Text(stringResource(R.string.amo_out_ops_heading), Modifier.semantics { heading() }, style = amoText(17, bold = true, color = look.ink))
        AronTileGrid(AmoOutletOp.entries, columns = columns) { o, mod ->
            AronTile(
                label = stringResource(when (o) {
                    AmoOutletOp.NEW -> OutR.string.out_req_new; AmoOutletOp.CLOSE -> OutR.string.out_req_close; AmoOutletOp.INFO -> OutR.string.out_req_info
                    AmoOutletOp.UPDATE_BASE -> R.string.amo_leaf_update_base; AmoOutletOp.CLUSTER -> OutR.string.out_req_cluster
                }),
                icon = painterResource(when (o) {
                    AmoOutletOp.NEW -> UiR.drawable.aron_art_outlet_new; AmoOutletOp.CLOSE -> UiR.drawable.aron_art_outlet_close; AmoOutletOp.INFO -> UiR.drawable.aron_art_outlet_edit
                    AmoOutletOp.UPDATE_BASE -> UiR.drawable.aron_ic_update_base; AmoOutletOp.CLUSTER -> UiR.drawable.aron_art_cluster
                }),
                onClick = { onOp(o) }, modifier = mod.testTag(OutletMenuTags.op(o)), menu = true, labelColor = look.tileLabelOutlet,
            )
        }
        // not in the vendor's menu: the AMO's own requests and their states (D-AMO-OUTLET-02)
        if (onOwn != null) AmoSecondary(stringResource(OutR.string.out_own_open), onOwn, Modifier.fillMaxWidth().testTag("om_own"))
    }
}
