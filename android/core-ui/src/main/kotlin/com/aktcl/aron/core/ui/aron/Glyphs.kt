package com.aktcl.aron.core.ui.aron

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.min

/**
 * The small UI glyphs of the existing Aron chrome (back chevron, gear, avatar person, caret, printer, minus and plus), drawn on a
 * Canvas so the kit needs no icon library and no bitmap. Every glyph is decorative: the touch target that holds it carries the label.
 */
internal object Glyph {
    fun DrawScope.chevronBack(color: Color) {
        val s = min(size.width, size.height)
        val w = s * 0.11f
        val p = Path().apply {
            moveTo(center.x + s * 0.12f, center.y - s * 0.24f)
            lineTo(center.x - s * 0.12f, center.y)
            lineTo(center.x + s * 0.12f, center.y + s * 0.24f)
        }
        drawPath(p, color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    fun DrawScope.gear(color: Color, hole: Color) {
        val s = min(size.width, size.height)
        val r = s * 0.30f
        val tooth = Size(s * 0.17f, s * 0.20f)
        repeat(8) { i ->
            rotate(i * 45f, center) {
                drawRoundRect(color, Offset(center.x - tooth.width / 2, center.y - s * 0.47f), tooth, CornerRadius(s * 0.03f))
            }
        }
        drawCircle(color, r, center)
        drawCircle(hole, s * 0.13f, center)
    }

    fun DrawScope.person(color: Color) {
        val s = min(size.width, size.height)
        drawCircle(color, s * 0.17f, Offset(center.x, center.y - s * 0.14f))
        val body = Path().apply {
            moveTo(center.x - s * 0.30f, center.y + s * 0.32f)
            cubicTo(center.x - s * 0.30f, center.y + s * 0.02f, center.x + s * 0.30f, center.y + s * 0.02f, center.x + s * 0.30f, center.y + s * 0.32f)
            close()
        }
        drawPath(body, color)
    }

    fun DrawScope.caretDown(color: Color) {
        val s = min(size.width, size.height)
        val p = Path().apply {
            moveTo(center.x - s * 0.25f, center.y - s * 0.12f)
            lineTo(center.x + s * 0.25f, center.y - s * 0.12f)
            lineTo(center.x, center.y + s * 0.14f)
            close()
        }
        drawPath(p, color)
    }

    /** A printer; [crossed] adds the diagonal bar of the "not connected" state (sr/stock.webp, sr/summary.png). */
    fun DrawScope.printer(color: Color, crossed: Boolean, gap: Color) {
        val s = min(size.width, size.height)
        val left = center.x - s * 0.36f
        drawRoundRect(color, Offset(center.x - s * 0.22f, center.y - s * 0.36f), Size(s * 0.44f, s * 0.18f), CornerRadius(s * 0.03f))
        drawRoundRect(color, Offset(left, center.y - s * 0.16f), Size(s * 0.72f, s * 0.34f), CornerRadius(s * 0.07f))
        drawRoundRect(gap, Offset(center.x - s * 0.24f, center.y + s * 0.06f), Size(s * 0.48f, s * 0.08f))
        drawRoundRect(color, Offset(center.x - s * 0.22f, center.y + s * 0.10f), Size(s * 0.44f, s * 0.24f), CornerRadius(s * 0.03f))
        if (crossed) {
            drawLine(gap, Offset(left - s * 0.02f, center.y - s * 0.42f), Offset(left + s * 0.76f, center.y + s * 0.42f), s * 0.16f, StrokeCap.Round)
            drawLine(color, Offset(left - s * 0.02f, center.y - s * 0.42f), Offset(left + s * 0.76f, center.y + s * 0.42f), s * 0.08f, StrokeCap.Round)
        }
    }

    fun DrawScope.bar(color: Color, vertical: Boolean) {
        val s = min(size.width, size.height)
        val w = s * 0.14f
        drawLine(color, Offset(center.x - s * 0.30f, center.y), Offset(center.x + s * 0.30f, center.y), w, StrokeCap.Round)
        if (vertical) drawLine(color, Offset(center.x, center.y - s * 0.30f), Offset(center.x, center.y + s * 0.30f), w, StrokeCap.Round)
    }

    /** The "i" of the info card: a dot over a stem. */
    fun DrawScope.info(color: Color) {
        val s = min(size.width, size.height)
        drawCircle(color, s * 0.08f, Offset(center.x, center.y - s * 0.20f))
        drawLine(color, Offset(center.x, center.y - s * 0.04f), Offset(center.x, center.y + s * 0.26f), s * 0.14f, StrokeCap.Round)
    }

    /** Two opposed arrows of the "sync data" button (sr/sales-submit.png). */
    fun DrawScope.sync(color: Color) {
        val s = min(size.width, size.height)
        val w = s * 0.12f
        drawLine(color, Offset(center.x - s * 0.30f, center.y - s * 0.14f), Offset(center.x + s * 0.28f, center.y - s * 0.14f), w, StrokeCap.Round)
        drawLine(color, Offset(center.x + s * 0.12f, center.y - s * 0.32f), Offset(center.x + s * 0.30f, center.y - s * 0.14f), w, StrokeCap.Round)
        drawLine(color, Offset(center.x + s * 0.30f, center.y + s * 0.14f), Offset(center.x - s * 0.28f, center.y + s * 0.14f), w, StrokeCap.Round)
        drawLine(color, Offset(center.x - s * 0.12f, center.y + s * 0.32f), Offset(center.x - s * 0.30f, center.y + s * 0.14f), w, StrokeCap.Round)
    }

    /** Floppy-disk "save" glyph of the reference Save button. */
    /** Map pin (sr/attendance.png location card). */
    fun DrawScope.pin(color: Color, hole: Color) {
        val s = min(size.width, size.height)
        val r = s * 0.26f
        val c = Offset(center.x, center.y - s * 0.08f)
        val p = Path().apply {
            moveTo(c.x - r * 0.92f, c.y + r * 0.40f)
            lineTo(center.x, center.y + s * 0.40f)
            lineTo(c.x + r * 0.92f, c.y + r * 0.40f)
            close()
        }
        drawCircle(color, r, c)
        drawPath(p, color)
        drawCircle(hole, r * 0.36f, c)
    }

    /** Circular refresh arrow (sr/attendance.png, red, end of the location card). */
    fun DrawScope.refresh(color: Color) {
        val s = min(size.width, size.height)
        val w = s * 0.11f
        val r = s * 0.30f
        drawArc(color, -60f, 300f, false, Offset(center.x - r, center.y - r), Size(r * 2, r * 2), style = Stroke(w, cap = StrokeCap.Round))
        val tip = Offset(center.x + r * 0.5f, center.y - r * 0.866f)
        val head = Path().apply {
            moveTo(tip.x + s * 0.17f, tip.y - s * 0.02f)
            lineTo(tip.x - s * 0.03f, tip.y - s * 0.17f)
            lineTo(tip.x - s * 0.01f, tip.y + s * 0.12f)
            close()
        }
        drawPath(head, color)
    }

    /** Arrow into (check-in) or out of (check-out) an open bracket (sr/attendance.png action buttons). */
    fun DrawScope.doorArrow(color: Color, out: Boolean) {
        val s = min(size.width, size.height)
        val w = s * 0.09f
        val style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val bx = if (out) center.x - s * 0.30f else center.x + s * 0.30f
        val dir = if (out) 1f else -1f
        val bracket = Path().apply {
            moveTo(bx + dir * s * 0.16f, center.y - s * 0.34f)
            lineTo(bx, center.y - s * 0.34f)
            lineTo(bx, center.y + s * 0.34f)
            lineTo(bx + dir * s * 0.16f, center.y + s * 0.34f)
        }
        drawPath(bracket, color, style = style)
        val from = if (out) center.x - s * 0.08f else center.x - s * 0.40f
        val to = if (out) center.x + s * 0.40f else center.x + s * 0.12f
        drawLine(color, Offset(from, center.y), Offset(to, center.y), w, StrokeCap.Round)
        val arrow = Path().apply {
            moveTo(to - s * 0.14f, center.y - s * 0.14f)
            lineTo(to, center.y)
            lineTo(to - s * 0.14f, center.y + s * 0.14f)
        }
        drawPath(arrow, color, style = style)
    }

    fun DrawScope.save(color: Color, gap: Color) {
        val s = min(size.width, size.height)
        drawRoundRect(color, Offset(center.x - s * 0.36f, center.y - s * 0.36f), Size(s * 0.72f, s * 0.72f), CornerRadius(s * 0.10f))
        drawRect(gap, Offset(center.x - s * 0.20f, center.y - s * 0.36f), Size(s * 0.34f, s * 0.22f))
        drawCircle(gap, s * 0.12f, Offset(center.x, center.y + s * 0.12f))
    }
}

/** A decorative glyph in a box of the modifier's size. */
@Composable
internal fun GlyphCanvas(modifier: Modifier, draw: DrawScope.() -> Unit) = Canvas(modifier) { draw() }
