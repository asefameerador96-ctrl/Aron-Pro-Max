package com.aktcl.aron.core.ui.aron

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import com.aktcl.aron.core.ui.aron.Glyph.check
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.AronLook
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.Glyph.doorArrow
import com.aktcl.aron.core.ui.aron.Glyph.person
import com.aktcl.aron.core.ui.aron.Glyph.pin
import com.aktcl.aron.core.ui.aron.Glyph.refresh

/**
 * The identity row at the top of Attendance (sr/attendance.png): a lavender rounded square with the purple person (or [avatar]), the
 * [title] (`Role - Name (login)`) in dark medium 17 sp over the [subtitle] (route and date) in 13 sp, and the red rule under it.
 */
@Composable
fun AronIdentityRow(title: String, subtitle: String, modifier: Modifier = Modifier, avatar: Painter? = null) {
    val look = LocalAronLook.current
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            InfoSquare(look.avatarSquare) {
                if (avatar != null) Image(avatar, contentDescription = null, modifier = Modifier.size(40.dp))
                else GlyphCanvas(Modifier.size(24.dp)) { person(look.purple) }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, style = lookStyle(17.sp, medium = true, color = look.ink))
                Text(subtitle, style = lookStyle(13.sp, medium = true, color = look.ink))
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(1.dp).background(look.red))
    }
}

/**
 * The location card of Attendance (sr/attendance.png): a grey raised card with the purple pin in a lavender square, the resolved
 * [address] (or the caller's "locating" / "no fix" text) and the red refresh at the end ([refreshLabel] is its TalkBack label;
 * [onRefresh] asks for ONE balanced-power fix, never continuous GPS).
 */
@Composable
fun AronLocationCard(address: String, onRefresh: () -> Unit, refreshLabel: String, modifier: Modifier = Modifier, refreshEnabled: Boolean = true) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(4.dp)
    Row(
        modifier.fillMaxWidth().shadow(6.dp, shape).clip(shape).background(look.locationCard).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InfoSquare(look.pinSquare) { GlyphCanvas(Modifier.size(24.dp)) { pin(look.purple, look.pinSquare) } }
        Text(address, style = lookStyle(15.sp, color = look.ink), modifier = Modifier.weight(1f).padding(horizontal = 14.dp))
        Box(
            Modifier
                .size(AronLook.Touch)
                .alpha(if (refreshEnabled) 1f else 0.4f)
                .clickable(enabled = refreshEnabled, role = Role.Button, onClick = onRefresh)
                .semantics { contentDescription = refreshLabel },
            contentAlignment = Alignment.Center,
        ) { GlyphCanvas(Modifier.size(28.dp)) { refresh(look.red) } }
    }
}

/** Tone of an [AronActionTile]: [Go] the green check-in gradient, [Neutral] the grey check-out gradient (sr/attendance.png). */
enum class ActionTone { Go, Neutral }

/** The glyph over the label of an [AronActionTile]: an arrow into ([CheckIn]) or out of ([CheckOut]) a door bracket. */
enum class ActionGlyph { CheckIn, CheckOut }

/**
 * The big action button of Attendance (sr/attendance.png): a horizontal gradient by [tone] (Sunlight: solid and dark), 8 dp radius, a
 * soft shadow, the white [glyph] over the white [text]. Put two side by side with `Modifier.weight(1f)` and a 12 dp gap.
 */
@Composable
fun AronActionTile(text: String, glyph: ActionGlyph, onClick: () -> Unit, modifier: Modifier = Modifier, tone: ActionTone = ActionTone.Go, enabled: Boolean = true) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(8.dp)
    val colors = if (tone == ActionTone.Go) listOf(look.goStart, look.goEnd) else listOf(look.neutralStart, look.neutralEnd)
    Column(
        modifier
            .heightIn(min = 88.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .shadow(4.dp, shape)
            .clip(shape)
            .background(Brush.horizontalGradient(colors))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        GlyphCanvas(Modifier.size(28.dp)) { doorArrow(Color.White, out = glyph == ActionGlyph.CheckOut) }
        Spacer(Modifier.height(6.dp))
        Text(text, style = lookStyle(17.sp, color = Color.White, align = TextAlign.Center))
    }
}

/**
 * The centred message of an empty screen (sr/tasks.png: `আপনার এএমও (AMO) কোনো কাজ বরাদ্দ করেননি।` alone on the grey page), 16 sp
 * dark grey. Give it the remaining space (it fills it and centres the text).
 */
@Composable
fun AronEmptyMessage(text: String, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = lookStyle(16.sp, color = look.secondaryText, align = TextAlign.Center))
    }
}

@Composable
private fun InfoSquare(fill: Color, content: @Composable () -> Unit) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(fill), contentAlignment = Alignment.Center) { content() }
}

/**
 * The one sync-status footer of the AMO and TSO homes (Vision QA: token colours only, the same on both roles): a purple check (or a grey
 * dot while not synced) with the [status] in dark medium 14 sp, then the [details] in grey 12 sp. Each detail stays whole: the line
 * breaks BETWEEN details, never at a `·`. Strings and digits come localised from the caller.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AronSyncFooter(status: String, details: List<String>, modifier: Modifier = Modifier, synced: Boolean = true) {
    val look = LocalAronLook.current
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (synced) GlyphCanvas(Modifier.size(18.dp)) { check(look.purple) }
            else Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(8.dp).clip(CircleShape).background(look.offlineDot)) }
            Text(status, style = lookStyle(14.sp, medium = true, color = look.ink), modifier = Modifier.padding(start = 8.dp))
        }
        if (details.isNotEmpty()) {
            FlowRow(Modifier.padding(start = 26.dp, top = 4.dp)) {
                details.forEachIndexed { i, d ->
                    Text(if (i < details.lastIndex) "$d · " else d, style = lookStyle(12.sp, color = look.inkMuted))
                }
            }
        }
    }
}
