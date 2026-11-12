package com.aktcl.aron.feature.outlet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.AronField

/** One choice of a [SearchPickerField]: [key] is stable (a cluster or outlet id as text), [label] is what the rep reads and searches. */
data class PickItem(val key: String, val label: String)

object SearchPickerTags {
    const val DIALOG_SEARCH = "pk_search"
    const val DIALOG_CLEAR = "pk_clear"
    const val DIALOG_LIST = "pk_list"
    fun field(tag: String) = "pk_field_$tag"
    fun item(key: String) = "pk_item_$key"
}

/** The rows whose label contains [query] (trimmed, case-insensitive); an empty query keeps every row in its order. */
fun filterPickItems(items: List<PickItem>, query: String): List<PickItem> {
    val q = query.trim().lowercase()
    return if (q.isEmpty()) items else items.filter { it.label.lowercase().contains(q) }
}

/**
 * The manual's drop-down (screenshots 42-44): a white card holding the field [title] (or the chosen [selected] label) and a caret; a tap opens
 * a dialog titled [title] with a search box hinted [searchHint] (with a clear button) over a vertical list. Works offline, over the local lists.
 * [tag] names the field for tests. A chosen item closes the dialog and is handed to [onPick].
 */
@Composable
fun SearchPickerField(
    title: String, searchHint: String, items: List<PickItem>, selected: PickItem?, onPick: (PickItem) -> Unit, tag: String,
    modifier: Modifier = Modifier, enabled: Boolean = true, emptyText: String? = null, isError: Boolean = false,
) {
    val look = LocalAronLook.current
    var open by rememberSaveable(tag) { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.fillMaxWidth().heightIn(min = AronTokens.Touch.Min).clip(shape).background(look.card)
            .clickable(enabled = enabled, role = Role.DropdownList) { open = true }
            .padding(horizontal = AronTokens.Space.L, vertical = AronTokens.Space.M).testTag(SearchPickerTags.field(tag)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            selected?.label ?: title, Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge, color = if (selected != null) look.ink else if (isError) look.red else look.inkMuted,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Icon(AronIcons.ExpandMore, contentDescription = null, tint = look.inkMuted)
    }
    if (open) PickerDialog(title, searchHint, items, emptyText, onDismiss = { open = false }, onPick = { open = false; onPick(it) })
}

@Composable
private fun PickerDialog(title: String, searchHint: String, items: List<PickItem>, emptyText: String?, onDismiss: () -> Unit, onPick: (PickItem) -> Unit) {
    val look = LocalAronLook.current
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(items, query) { filterPickItems(items, query) }
    val clear = stringResource(R.string.out_pick_clear)
    val none = emptyText ?: stringResource(R.string.out_pick_none) // read outside the dialog window (its context may not carry the app language)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 520.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFFEBE5F0)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = look.ink)
            AronField(
                query, { query = it }, searchHint, Modifier.fillMaxWidth(), fieldModifier = Modifier.testTag(SearchPickerTags.DIALOG_SEARCH),
                trailing = if (query.isNotEmpty()) ({
                    Box(Modifier.clickable(role = Role.Button) { query = "" }.semantics { contentDescription = clear }.testTag(SearchPickerTags.DIALOG_CLEAR).padding(8.dp)) {
                        Icon(AronIcons.Close, contentDescription = null, tint = look.inkMuted)
                    }
                }) else null,
            )
            if (shown.isEmpty()) {
                Text(none, style = MaterialTheme.typography.bodyMedium, color = look.inkMuted, modifier = Modifier.padding(vertical = AronTokens.Space.M))
            } else {
                LazyColumn(Modifier.fillMaxWidth().testTag(SearchPickerTags.DIALOG_LIST)) {
                    items(shown, key = { it.key }) { it0 ->
                        Text(
                            it0.label,
                            Modifier.fillMaxWidth().heightIn(min = AronTokens.Touch.Min).clickable(role = Role.Button) { onPick(it0) }.padding(vertical = 12.dp, horizontal = 4.dp).testTag(SearchPickerTags.item(it0.key)),
                            style = MaterialTheme.typography.bodyLarge, color = look.ink,
                        )
                    }
                }
            }
        }
    }
}
