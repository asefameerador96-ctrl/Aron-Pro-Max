package com.aktcl.aron.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Aron logo (D-BRAND-01: the owner-supplied artwork, a slanted letter A of ice shards with an orange core) as a square
 * [size] picture. One shared composable, so the mark is identical in ARON SR, AMO and TSO.
 *
 * Rules, all of them binding:
 * - It sits on a SOLID plate or card ([AronBrandPlate], or the kit's white `card`), never on the page gradient, a photo or
 *   behind text, and in Sunlight mode the plate stays solid, never translucent, blurred or a gradient (docs/32 s2a). The mark
 *   itself takes no tint, alpha, blur, shadow, gradient or animation: it is a fixed picture.
 * - It is decorative: `contentDescription = null`, so TalkBack skips it and no string is needed. The words beside it (the
 *   app title) carry the meaning.
 * - It is a bundled resource, never a network image, so it can never wait on, or fail with, the connection (offline-first).
 * - It belongs on the quiet screens only: sign-in, enrolment and OTP (the 72 dp brand block). Never on a dense working
 *   screen (sale, stock, review, memo, picker, visit, attendance, summary, submit, tasks), never in the default top bar,
 *   and never on a printed memo or slip.
 *
 * The 256 px source is a square with the glyph about 92 percent of its height, centred; its dark navy outline needs a light
 * plate (the navy all but disappears on a dark one), so give it the white of the parity look. Size it so the plate leaves a
 * few dp around the glyph: the 72 dp sign-in plate uses 64 dp.
 *
 * Downscaling: the picture is drawn with the default filter. On Android, Compose maps every filter quality above `None`
 * (Low, Medium, High) to the same bilinear `setFilterBitmap(true)`, so asking for `FilterQuality.Medium` would change
 * nothing; if the marks look rough on a low-density field phone (D-UI-04 device check), the remedy is a second, pre-scaled
 * drawable for those sites, not a filter flag.
 */
@Composable
fun AronBrandMark(modifier: Modifier = Modifier, size: Dp = 48.dp) {
    Image(
        painter = painterResource(R.drawable.aron_brand_mark),
        contentDescription = null,
        modifier = modifier.size(size),
        contentScale = ContentScale.Fit,
    )
}

/**
 * [AronBrandMark] on its plate: a solid square of [size] with corners of [cornerRadius] (half of [size] makes a disc), the
 * mark of [markSize] centred in it. The plate is opaque in every theme (no alpha, blur or gradient), which is what keeps the
 * logo legible outdoors (docs/32 s2a). Its colour is [fill], by default the parity look's own `card` white
 * ([LocalAronLook]): the same solid white in the standard look and in Sunlight mode, on which the navy outline carries the
 * glyph. Leave a few dp around the glyph, which fills about 92 percent of the picture's height and, being a slanted A, needs
 * the room at the corners of a disc most. Size as built: 64 dp in the 72 dp sign-in squircle.
 */
@Composable
fun AronBrandPlate(size: Dp, markSize: Dp, cornerRadius: Dp, modifier: Modifier = Modifier, fill: Color = LocalAronLook.current.card) {
    Box(
        modifier.size(size).drawBehind { drawRoundRect(fill, cornerRadius = CornerRadius(cornerRadius.toPx())) },
        contentAlignment = Alignment.Center,
    ) {
        AronBrandMark(size = markSize)
    }
}
