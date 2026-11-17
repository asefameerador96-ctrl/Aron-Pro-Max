package com.aktcl.aron.feature.amo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.aktcl.aron.core.ui.AronLook
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.R as CoreR

object AmoSaleHeaderTags { const val CARD = "amo_sale_header" }

/**
 * The header card of the AMO sale entry (owner screenshots 23 and 24, D-PAR-AMO-07): a white card with a grey outline, the shop icon at
 * the left, then "রুট:" with the route and "খুচরা বিক্রেতাঃ" with `name (code-phone-area)`. The route is left out when the call has none.
 * Colours and sizes come from the look and the tokens; the card is one reading unit for a screen reader.
 */
@Composable
fun AmoSaleHeaderCard(route: String?, retailer: String, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(AronLook.CardRadius)
    Row(
        modifier.padding(horizontal = AronTokens.Space.M, vertical = AronTokens.Space.S).fillMaxWidth().clip(shape).background(look.card)
            .border(BorderStroke(AronTokens.Stroke.Hairline, look.infoBorder), shape).padding(AronTokens.Space.M).testTag(AmoSaleHeaderTags.CARD)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
    ) {
        Image(painterResource(CoreR.drawable.aron_ic_outlet), contentDescription = null, modifier = Modifier.size(AronTokens.Size.IconLarge + AronTokens.Space.S))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
            if (!route.isNullOrBlank()) LabelLine(stringResource(R.string.amo_sale_hdr_route), route)
            LabelLine(stringResource(R.string.amo_sale_hdr_retailer), retailer)
        }
    }
}

@Composable
private fun LabelLine(label: String, value: String) {
    val look = LocalAronLook.current
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = look.ink)) { append(label) }
            append(" ")
            withStyle(SpanStyle(color = look.inkMuted)) { append(value) }
        },
        style = MaterialTheme.typography.bodyLarge,
    )
}
