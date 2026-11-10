package com.aktcl.aron.feature.amo

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.AronDropdown
import com.aktcl.aron.feature.outlet.GeoPhotoState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One choice of a drop-down: a server id and what the AMO reads. */
data class AmoOption(val id: Long, val label: String)

object OutletFormTags {
    const val SAVE = "of_save"
    const val CANCEL = "of_cancel"
    const val CAPTURE = "of_capture"
    const val THUMB = "of_thumb"
    const val OK = "of_dialog_ok"
    const val DIALOG = "of_dialog"
    fun field(key: String) = "of_field_$key"
    fun letter(l: String) = "of_letter_$l"
}

/**
 * A form is a list of these, top to bottom, exactly in the order of the screenshots (47, 52, 55, 57, 59): the screens differ only in the list
 * they pass, so every form shares the look, the error lines and the keyboard behaviour (D-AMO-OUTLET-01).
 */
sealed interface AmoFormItem {
    /** A closed drop-down (route, cluster, retailer, Sub-Channel, Geo Classification). */
    data class Select(
        val key: String, val label: String?, val placeholder: String, val options: List<AmoOption>, val selectedId: Long?, val onSelect: (Long) -> Unit,
        val enabled: Boolean = true, val error: String? = null,
    ) : AmoFormItem

    /** The alphabet bar over the retailer drop-down: "All" plus the letters present; [selected] is "" for All. */
    data class Letters(val letters: List<String>, val selected: String, val onSelect: (String) -> Unit) : AmoFormItem

    /** An editable text field with its label above (the vendor's form fields). */
    data class Field(val key: String, val label: String, val value: String, val onChange: (String) -> Unit, val keyboard: KeyboardType = KeyboardType.Text, val error: String? = null) : AmoFormItem

    /** A read-only `label: value` row (the close forms, 50 and 57). */
    data class ReadOnly(val key: String, val label: String, val value: String) : AmoFormItem

    /** The "GEO এবং ছবি ধারণ করুন" button with the thumbnail of the photo after the capture. */
    data class Capture(val state: GeoPhotoState, val onShutter: () -> Unit, val error: String? = null) : AmoFormItem

    data class Notice(val text: String, val kind: BannerKind = BannerKind.Info) : AmoFormItem
}

/** The drop-down the vendor uses everywhere: the kit's [AronDropdown] on [AmoOption]s, with an error line under it. */
@Composable
fun AmoSelect(item: AmoFormItem.Select, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    val chosen = item.options.firstOrNull { it.id == item.selectedId }
    Column(modifier.fillMaxWidth().testTag(OutletFormTags.field(item.key)), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AronDropdown(
            options = item.options, selected = chosen, onSelect = { item.onSelect(it.id) }, optionLabel = { it.label },
            label = item.label, placeholder = item.placeholder, enabled = item.enabled,
        )
        item.error?.let { Text(it, style = amoText(14, color = look.minus)) }
    }
}

/** "All" and the letters present, as chips in one scrolling row (63 and 59 show it above the retailer drop-down). */
@Composable
fun AmoLetterBar(item: AmoFormItem.Letters, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (listOf("") + item.letters).forEach { l ->
            val on = l == item.selected
            Box(
                Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).background(if (on) look.purple else look.card)
                    .border(1.dp, if (on) look.purple else look.underline, RoundedCornerShape(20.dp))
                    .clickable(role = Role.Button) { item.onSelect(l) }.testTag(OutletFormTags.letter(l)).padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text(l.ifEmpty { stringResource(R.string.amo_out_all) }, style = amoText(15, bold = on, color = if (on) Color.White else look.ink)) }
        }
    }
}

/** The thumbnail of the captured photo, decoded off the main thread; nothing is drawn until it is ready or when the file is gone. */
@Composable
private fun PhotoThumb(path: String, modifier: Modifier = Modifier) {
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull() }
    }
    bmp?.let { Image(it, stringResource(R.string.amo_out_thumb), modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).testTag(OutletFormTags.THUMB), contentScale = ContentScale.Crop) }
}

@Composable
private fun CaptureBlock(item: AmoFormItem.Capture) {
    val look = LocalAronLook.current
    val s = item.state
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                AmoYes(
                    stringResource(if (s.photo == null) com.aktcl.aron.feature.outlet.R.string.out_req_capture_geo_photo else com.aktcl.aron.feature.outlet.R.string.out_retake_photo),
                    item.onShutter, Modifier.fillMaxWidth().testTag(OutletFormTags.CAPTURE), enabled = !s.busy && (s.photo == null || s.retakesLeft > 0),
                )
            }
            s.photo?.let { PhotoThumb(it.thumbnailPath) }
        }
        if (s.geoMocked) AmoBanner(stringResource(R.string.amo_out_err_mock), kind = BannerKind.Error)
        item.error?.let { Text(it, style = amoText(14, color = look.minus)) }
    }
}

/**
 * The body of every outlet form of the AMO (D-AMO-OUTLET-01): the [items] in order, the validation line under the field it belongs to,
 * then the buttons: Save (green) and, when the vendor has it, Cancel next to it. Scrolls; the buttons stay docked under it.
 */
@Composable
fun AmoOutletFormBody(
    items: List<AmoFormItem>, onSave: () -> Unit, modifier: Modifier = Modifier, onCancel: (() -> Unit)? = null, saveEnabled: Boolean = true, busy: Boolean = false,
    header: (@Composable () -> Unit)? = null, bottom: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier.weight(1f, fill = true).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S),
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
        ) {
            header?.invoke()
            items.forEach { AmoFormItemView(it) }
        }
        if (bottom != null) bottom() else Row(Modifier.fillMaxWidth().padding(AronTokens.Space.Screen), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            if (onCancel != null) AmoNo(stringResource(R.string.amo_verify_cancel), onCancel, Modifier.weight(1f).testTag(OutletFormTags.CANCEL), enabled = !busy)
            AmoPrimary(stringResource(R.string.amo_verify_save), onSave, Modifier.weight(1f).testTag(OutletFormTags.SAVE), enabled = saveEnabled && !busy, busy = busy)
        }
    }
}

@Composable
fun AmoFormItemView(item: AmoFormItem) {
    val look = LocalAronLook.current
    when (item) {
        is AmoFormItem.Select -> AmoSelect(item)
        is AmoFormItem.Letters -> AmoLetterBar(item)
        is AmoFormItem.Field -> Column(Modifier.fillMaxWidth().testTag(OutletFormTags.field(item.key)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.label, style = amoText(16, bold = true))
            AmoField(item.value, item.onChange, "", Modifier.fillMaxWidth(), error = item.error != null, keyboardType = item.keyboard)
            item.error?.let { Text(it, style = amoText(14, color = look.minus)) }
        }
        is AmoFormItem.ReadOnly -> Row(Modifier.fillMaxWidth().testTag(OutletFormTags.field(item.key)), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.label + ":", Modifier.weight(0.45f), style = amoText(16, bold = true))
            Text(item.value, Modifier.weight(0.55f), style = amoText(16, color = look.ink))
        }
        is AmoFormItem.Capture -> CaptureBlock(item)
        is AmoFormItem.Notice -> AmoBanner(item.text, kind = item.kind)
    }
}

/** A card of `label : value` rows (the verification lists 47, 50, 52 and the read-only summary of 53). */
@Composable
fun AmoFactsCard(rows: List<Pair<String, String>>, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(look.card).border(1.dp, look.underline, shape)
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEach { (k, v) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(k, Modifier.weight(0.42f), style = amoText(15, bold = true))
                Text(":", style = amoText(15, bold = true))
                Text(v, Modifier.weight(0.58f), style = amoText(15, color = look.ink))
            }
        }
    }
}

/**
 * The success dialog of the screenshots (49, 51, 53, 56, 58, 61): a green round check, the message in blue, a green OK at the bottom right.
 * [onOk] runs once; the dialog cannot be dismissed any other way, so the host's cleared form is always reached through OK.
 */
@Composable
fun AmoSuccessDialog(message: String, okLabel: String, onOk: () -> Unit) {
    val look = LocalAronLook.current
    AlertDialog(
        onDismissRequest = {}, containerColor = look.card,
        icon = {
            Box(Modifier.size(56.dp).clip(CircleShape).background(look.online), contentAlignment = Alignment.Center) { Text("✓", style = amoText(32, bold = true, color = Color.White)) }
        },
        title = { Text(message, Modifier.fillMaxWidth().testTag(OutletFormTags.DIALOG), style = amoText(18, bold = true, color = look.purple), textAlign = androidx.compose.ui.text.style.TextAlign.Center) },
        confirmButton = { AmoPrimary(okLabel, onOk, Modifier.testTag(OutletFormTags.OK)) },
    )
}
