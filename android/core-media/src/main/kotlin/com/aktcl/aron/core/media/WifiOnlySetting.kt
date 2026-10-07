package com.aktcl.aron.core.media

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.localizedNumber

/**
 * The rep's "photos on Wi-Fi only" switch (F-SYS-037). Unset follows `cfg.media.wifi_only_default`; once the rep sets it,
 * their choice holds on this phone for every user. Evidence photos still fall back to mobile data after
 * `cfg.media.evidence_mobile_fallback_h` (the uploader applies that rule).
 */
class WifiOnlySetting(context: Context, private val configDefault: () -> Boolean) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun wifiOnly(): Boolean = if (prefs.contains(KEY)) prefs.getBoolean(KEY, true) else configDefault()

    /** Saves the choice (synchronously, so a kill right after the tap keeps it). */
    fun set(on: Boolean) { prefs.edit().putBoolean(KEY, on).commit() }

    companion object {
        const val PREFS = "aron-media"
        const val KEY = "wifi_only"
    }
}

object WifiOnlyTags { const val SWITCH = "media_wifi_only" }

/**
 * The Settings row: a solid, full-width touch target (docs/32 s2a). Call `scheduler.requestUpload()` in [onChange].
 * [evidenceFallbackHours] is `cfg.media.evidence_mobile_fallback_h` (0 = never).
 */
@Composable
fun WifiOnlyPhotosRow(on: Boolean, onChange: (Boolean) -> Unit, evidenceFallbackHours: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = AronTokens.Space.L).testTag(WifiOnlyTags.SWITCH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.media_wifi_only_title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.media_wifi_only_hint), style = MaterialTheme.typography.bodyMedium)
            Text(
                if (evidenceFallbackHours > 0) pluralStringResource(R.plurals.media_wifi_only_evidence_after, evidenceFallbackHours, localizedNumber(evidenceFallbackHours.toLong()))
                else stringResource(R.string.media_wifi_only_evidence_never),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Switch(checked = on, onCheckedChange = null)
    }
}
