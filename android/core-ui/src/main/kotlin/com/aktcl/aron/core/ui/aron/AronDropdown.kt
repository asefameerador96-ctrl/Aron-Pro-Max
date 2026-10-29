package com.aktcl.aron.core.ui.aron

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.Glyph.caretDown

/** Test tags of [AronDropdown]: the closed field, the open list and each option of it. */
object AronDropdownTags {
    const val FIELD = "aron_dropdown_field"
    const val MENU = "aron_dropdown_menu"
    fun option(index: Int) = "aron_dropdown_option_$index"
}

/**
 * A closed field that opens a list of [options] (issue type, leave type, route, the five zone levels; screenshots 17, 21, 23, 24,
 * 32): the bold [label] above, the chosen option's text (or [placeholder] in the hint colour) and the down caret. Choosing calls
 * [onSelect] and closes the list. In the TSO look it is the glow field (navy fill, violet glowing edge); in the SR and AMO looks the
 * white card with the grey edge. TalkBack reads the field's name and what it holds ("Zone, Banani - Test"). The open list is as wide
 * as its field and covers the fields below (screenshots 21 and 32; tso-manual X4b, R-W-12 (4)), whatever the longest label.
 */
@Composable
fun <T> AronDropdown(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    optionLabel: (T) -> String,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    enabled: Boolean = true,
) {
    val look = LocalAronLook.current
    val n = look.night
    val fill = n?.field ?: look.card
    val edge = n?.fieldEdge ?: look.underline
    val ink = n?.ink ?: look.ink
    val hint = n?.placeholder ?: look.hint
    val shape = RoundedCornerShape(if (n != null) 14.dp else 12.dp)
    var open by remember { mutableStateOf(false) }
    var fieldWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val shown = selected?.let(optionLabel)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label != null) Text(label, style = lookStyle(16.sp, bold = true, color = ink))
        Box {
            Row(
                modifier
                    .fillMaxWidth()
                    .onSizeChanged { fieldWidthPx = it.width }
                    .heightIn(min = 56.dp)
                    .then(if (n != null) Modifier.nightGlow(n, edge, shape, 8.dp) else Modifier)
                    .clip(shape)
                    .background(fill)
                    .border(if (n?.solid == true) 2.dp else 1.dp, if (enabled) edge else edge.copy(alpha = 0.4f), shape)
                    .clickable(enabled = enabled && options.isNotEmpty(), role = Role.DropdownList, onClickLabel = label) { open = true }
                    .semantics { contentDescription = listOfNotNull(label, shown ?: placeholder.ifEmpty { null }).joinToString(", ") }
                    .testTag(AronDropdownTags.FIELD)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(shown ?: placeholder, Modifier.weight(1f), style = lookStyle(16.sp, color = if (shown != null) ink else hint), maxLines = 2)
                GlyphCanvas(Modifier.size(14.dp)) { caretDown(if (enabled) ink else hint) }
            }
            DropdownMenu(
                expanded = open, onDismissRequest = { open = false }, containerColor = fill,
                modifier = (if (fieldWidthPx > 0) Modifier.width(with(density) { fieldWidthPx.toDp() }) else Modifier).testTag(AronDropdownTags.MENU),
            ) {
                options.forEachIndexed { i, o ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(o), style = lookStyle(16.sp, color = ink)) },
                        onClick = { open = false; onSelect(o) },
                        modifier = Modifier.testTag(AronDropdownTags.option(i)),
                    )
                }
            }
        }
    }
}
