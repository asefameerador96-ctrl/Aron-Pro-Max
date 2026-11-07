package com.aktcl.aron.feature.amo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.BannerKind

object TeamLocationTags { const val PICK = "team_loc_pick"; const val MAP = "team_loc_map" }

/** An SR that can be picked on Team Location: the name and the phone in clear (the AMO's own zone); a row without a name is never listed (R3: no "N/A"). */
data class LocationChoice(val userId: Long, val name: String, val phone: String?)

object TeamLocationRules {
    /** The drop-down rows: named SRs only, sorted by name; the phone joins from the members list by user id. */
    fun choices(pins: List<TeamPin>, phones: Map<Long, String>): List<LocationChoice> =
        pins.filter { it.name.isNotBlank() && !it.name.equals("N/A", ignoreCase = true) }.map { LocationChoice(it.userId, it.name.trim(), phones[it.userId]) }.sortedBy { it.name.lowercase() }

    /** "name · phone": the bubble text of a pin; the phone is in clear. */
    fun bubble(c: LocationChoice): String = if (c.phone.isNullOrBlank()) c.name else c.name + " · " + c.phone
}

/**
 * Team Location (screenshot 33): the SR drop-down over the interactive map. [map] draws the map of the host (the pins are the SRs' last
 * known fixes with their age, grey after 120 minutes; compass and my-location controls); choosing an SR centres it and opens its bubble.
 */
@Composable
fun TeamLocationContent(
    choices: List<LocationChoice>, selectedId: Long?, onSelect: (Long) -> Unit, stale: Boolean, failed: Boolean, refused: Boolean = false,
    modifier: Modifier = Modifier, map: @Composable (Modifier) -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AmoFetchBanner(failed, stale, refused)
            AmoDropdown(
                choices.firstOrNull { it.userId == selectedId }?.name ?: stringResource(R.string.amo_team_loc_select), choices.map { it.userId.toString() to it.name },
                { onSelect(it.toLong()) }, Modifier.testTag(TeamLocationTags.PICK),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth().testTag(TeamLocationTags.MAP)) { map(Modifier.fillMaxSize()) }
    }
}
