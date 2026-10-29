package com.aktcl.aron.core.ui.aron

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.AronLookColors
import com.aktcl.aron.core.ui.AronNightRoles
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.Glyph.chevronBack

/**
 * The TSO look's components (D-TSO-MAN-13, build contract s5 A1): the parts the TSO screens and the TSO login share. They read
 * [AronLookColors.night] ([AronNightRoles.Night], or [AronNightRoles.Sunlight] when the outdoor theme is on: solid surfaces, no
 * gradient or glow). Outside the TSO theme (a preview) the night set is used. Every figure is drawn by the caller from text that the
 * shared rules formatted (Percent, Qty); these components only draw.
 */
val AronLookColors.nightRoles: AronNightRoles get() = night ?: AronNightRoles.Night

/** The TSO roles in force. */
@Composable
@ReadOnlyComposable
fun nightRoles(): AronNightRoles = LocalAronLook.current.nightRoles

/** A glow halo (a coloured soft shadow) unless the roles are solid. */
fun Modifier.nightGlow(n: AronNightRoles, color: Color, shape: Shape, elevation: Dp = 12.dp): Modifier =
    if (n.solid) this else this.shadow(elevation, shape, clip = false, ambientColor = color, spotColor = color)

/** The green-to-yellow gradient title of every TSO screen (screenshots 8, 17, 24, 39); solid ink in Sunlight. A heading for TalkBack. */
@Composable
fun NightTitle(text: String, modifier: Modifier = Modifier, size: TextUnit = 22.sp, align: TextAlign = TextAlign.Center, maxLines: Int = 2) {
    val n = nightRoles()
    val base = lookStyle(size, semibold = true, align = align)
    val style = if (n.solid) base.copy(color = n.ink) else base.copy(brush = Brush.horizontalGradient(listOf(n.titleStart, n.titleEnd)))
    Text(text, modifier.semantics { heading() }, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** The grey round back button of the TSO screens (52 dp disc, white chevron; screenshots 8, 17, 24, 39). [label] is its TalkBack name. */
@Composable
fun NightBackButton(onClick: () -> Unit, label: String, modifier: Modifier = Modifier) {
    val n = nightRoles()
    Box(
        modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(n.backButton)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { GlyphCanvas(Modifier.size(22.dp)) { chevronBack(n.backInk) } }
}

private val CardShape = RoundedCornerShape(24.dp)
private val OutlineShape = RoundedCornerShape(22.dp)
private val RowShape = RoundedCornerShape(16.dp)

/** The purple glow card of the dashboard (Sales, Geo Fencing; screenshot 6): a diagonal purple gradient with a violet halo. */
@Composable
fun NightGlowCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    val n = nightRoles()
    Column(
        modifier
            .fillMaxWidth()
            .nightGlow(n, n.glow, CardShape, 14.dp)
            .clip(CardShape)
            .background(if (n.solid) SolidColor(n.glowCardStart) else Brush.linearGradient(listOf(n.glowCardStart, n.glowCardEnd)))
            .border(if (n.solid) 2.dp else 1.dp, if (n.solid) n.outlineEdge else n.glow.copy(alpha = 0.55f), CardShape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(contentPadding),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/** The navy card with the teal edge (channel, CPR, Brand CPR, Login status, Segment, Target Status; screenshots 7 and 39). */
@Composable
fun NightOutlineCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    fill: Color? = null,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    val n = nightRoles()
    Column(
        modifier
            .fillMaxWidth()
            .nightGlow(n, n.outlineEdge, OutlineShape, 8.dp)
            .clip(OutlineShape)
            .background(fill ?: n.outlineCard)
            .border(if (n.solid) 2.dp else 1.5.dp, n.outlineEdge, OutlineShape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(contentPadding),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/** One row card of a list (Not Logged In, tickets; screenshots 8 and 12): navy with a quiet edge. */
@Composable
fun NightRowCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val n = nightRoles()
    Column(
        modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(n.rowCard)
            .border(if (n.solid) 2.dp else 1.dp, n.rowEdge, RowShape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        content = content,
    )
}

/**
 * A text field of the TSO look (screenshots 5, 17, 24): the bold [label] ABOVE it (never in the outline notch), a navy fill with a
 * glowing violet edge, the [placeholder] inside. TalkBack names the field by [label] (or [placeholder]). [fieldModifier] goes on the
 * input itself (test tags).
 */
@Composable
fun NightGlowField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    isError: Boolean = false,
    supportingText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
    fieldModifier: Modifier = Modifier,
) {
    val n = nightRoles()
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label != null) Text(label, style = lookStyle(16.sp, bold = true, color = n.ink))
        OutlinedTextField(
            value, onValueChange,
            fieldModifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .nightGlow(n, n.fieldEdge, shape, 8.dp)
                .semantics { (label ?: placeholder)?.let { contentDescription = it } },
            enabled = enabled, singleLine = singleLine, minLines = minLines, isError = isError, shape = shape,
            textStyle = lookStyle(16.sp, color = n.ink),
            placeholder = if (placeholder == null) null else { { Text(placeholder, style = lookStyle(16.sp, color = n.placeholder)) } },
            trailingIcon = trailing,
            supportingText = if (supportingText == null) null else { { Text(supportingText, style = lookStyle(13.sp, color = if (isError) n.danger else n.inkMuted)) } },
            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, visualTransformation = visualTransformation,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = n.field, unfocusedContainerColor = n.field, disabledContainerColor = n.field, errorContainerColor = n.field,
                focusedTextColor = n.ink, unfocusedTextColor = n.ink, disabledTextColor = n.inkMuted, errorTextColor = n.ink,
                focusedBorderColor = n.fieldEdge, unfocusedBorderColor = n.fieldEdge.copy(alpha = if (n.solid) 1f else 0.75f),
                disabledBorderColor = n.fieldEdge.copy(alpha = 0.4f), errorBorderColor = n.danger, cursorColor = n.ink, errorCursorColor = n.danger,
            ),
        )
    }
}

/** The fill of a [NightButton]: Cyan (Login), Lime (Final Submit and plan actions), Blue (Submit ticket), Cancel (green), LogOut (orange). */
enum class NightButtonStyle { Cyan, Lime, Blue, Cancel, LogOut }

/**
 * A gradient action button of the TSO look (screenshots 5, 17, 24, 44): 12 dp corners, at least 52 dp tall, a bold label; the bright
 * fills carry the navy label and the blue one a white label, all at 7:1 (TokenContrastTest). Disabled or [busy]: the grey chip fill and
 * not pressable (the label stays). [fill] false sizes the button to its label (dialog buttons). [icon] draws a decorative glyph in the
 * label's colour before the label (Browse Gallery, screenshot 43); the label carries the meaning for TalkBack.
 */
@Composable
fun NightButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: NightButtonStyle = NightButtonStyle.Blue,
    enabled: Boolean = true,
    busy: Boolean = false,
    fill: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val n = nightRoles()
    val on = enabled && !busy
    val shape = RoundedCornerShape(12.dp)
    val (brush, ink, glow) = when {
        !on -> Triple(SolidColor(n.chip), n.chipInk, null)
        style == NightButtonStyle.Cyan -> Triple(grad(n, n.cyanStart, n.cyanEnd, vertical = true), n.onBright, n.cyanStart)
        style == NightButtonStyle.Lime -> Triple(grad(n, n.limeStart, n.limeEnd), n.onBright, null)
        style == NightButtonStyle.Cancel -> Triple(SolidColor(n.cancel), n.onBright, n.cancel)
        style == NightButtonStyle.LogOut -> Triple(grad(n, n.logoutStart, n.logoutEnd, vertical = true), n.onBright, n.logoutStart)
        else -> Triple(SolidColor(n.blue), n.onBlue, null)
    }
    Box(
        modifier
            .then(if (fill) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = 52.dp)
            .then(if (glow != null) Modifier.nightGlow(n, glow, shape, 10.dp) else Modifier)
            .clip(shape)
            .background(brush)
            .clickable(enabled = on, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (icon == null) {
            Text(text, style = lookStyle(18.sp, bold = true, color = ink, align = TextAlign.Center))
        } else {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            ) {
                androidx.compose.material3.Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
                Text(text, style = lookStyle(18.sp, bold = true, color = ink, align = TextAlign.Center))
            }
        }
    }
}

private fun grad(n: AronNightRoles, a: Color, b: Color, vertical: Boolean = false): Brush =
    if (n.solid) SolidColor(a) else if (vertical) Brush.verticalGradient(listOf(a, b)) else Brush.horizontalGradient(listOf(a, b))

/**
 * A progress ring (screenshots 6 and 7): the track, then the arc of [tenths] tenths of a percent from the top, clockwise, capped at a
 * full turn (287.6 % draws one full ring), with a round cap; [text] (already formatted, "2.4%") in the centre and an optional small
 * [caption] under it ("CPR"). [onOutline] picks the track of the navy card. TalkBack reads the text and caption.
 */
@Composable
fun NightRing(
    tenths: Long?,
    text: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    size: Dp = 128.dp,
    stroke: Dp = 13.dp,
    onOutline: Boolean = false,
    textSize: TextUnit = 24.sp,
) {
    val n = nightRoles()
    val sweep = ((tenths ?: 0L).coerceIn(0L, 1000L) * 360L / 1000L).toFloat()
    Box(modifier.size(size).semantics(mergeDescendants = true) { contentDescription = listOfNotNull(text, caption).joinToString(" ") }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = stroke.toPx()
            val inset = w / 2f
            val arcSize = Size(this.size.width - w, this.size.height - w)
            drawArc(if (onOutline) n.ringTrackOutline else n.ringTrack, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
            if (sweep > 0f) {
                val brush = if (n.solid) SolidColor(n.ringFill) else Brush.sweepGradient(listOf(n.figure, n.ringFill, n.figure))
                drawArc(brush, -90f, sweep, false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
        // the figure shrinks to the ring's inside (one line) instead of clipping: "73,486.6%" at 200 percent font scale
        Column(Modifier.padding(horizontal = stroke + 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text, style = lookStyle(textSize, bold = true, color = n.ink, align = TextAlign.Center), maxLines = 1, softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = textSize, stepSize = 1.sp),
            )
            if (caption != null) Text(caption, style = lookStyle(11.sp, bold = true, color = n.ink, align = TextAlign.Center), maxLines = 1)
        }
    }
}

/** A horizontal bar (Login and Bikroy Joma status, screenshot 7): the light track and the blue ramp to [percent] (0..100, capped). */
@Composable
fun NightBar(percent: Long?, modifier: Modifier = Modifier, height: Dp = 14.dp) {
    val n = nightRoles()
    val fraction = ((percent ?: 0L).coerceIn(0L, 100L)).toFloat() / 100f
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(n.barTrack)) {
        if (fraction > 0f) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(RoundedCornerShape(50))
                    .background(if (n.solid) SolidColor(n.barFillEnd) else Brush.horizontalGradient(listOf(n.barFillStart, n.barFillEnd))),
            )
        }
    }
}

/** The page of a TSO screen: the navy page colour (solid light grey in Sunlight). */
fun Modifier.nightPage(n: AronNightRoles): Modifier = this.background(n.page)

/** A small text of the TSO look in the bundled font (for the kit's callers outside core-ui). */
@Composable
fun nightText(size: TextUnit, color: Color, bold: Boolean = false, align: TextAlign = TextAlign.Unspecified, semibold: Boolean = false) =
    lookStyle(size, bold = bold, color = color, align = align, semibold = semibold)
