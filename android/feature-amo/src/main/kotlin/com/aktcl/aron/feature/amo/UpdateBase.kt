package com.aktcl.aron.feature.amo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.localizedNumber
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Pure geometry of Update Base (F-AMO-028): the exact point is the AMO's own fix moved in whole steps, never further than the limit. */
object UpdateBasePoint {
    const val STEP_M = 10
    private const val M_PER_DEG_LAT = 111_320.0

    /** The point [northM] metres north and [eastM] metres east of ([lat], [lng]). */
    fun move(lat: Double, lng: Double, northM: Int, eastM: Int): Pair<Double, Double> =
        (lat + northM / M_PER_DEG_LAT) to (lng + eastM / (M_PER_DEG_LAT * cos(Math.toRadians(lat))))

    /** Straight distance of the chosen offset from where the AMO stands, in metres. */
    fun offsetM(northM: Int, eastM: Int): Int = hypot(northM.toDouble(), eastM.toDouble()).roundToInt()

    /** Whether one more step in the given direction would stay within [maxM] of the fix. */
    fun canMove(northM: Int, eastM: Int, dn: Int, de: Int, maxM: Int): Boolean = offsetM(northM + dn, eastM + de) <= maxM
}

/**
 * The point step: the map of N-053 is lite (one still image, no gestures), so the exact point is chosen by moving the pin in
 * [UpdateBasePoint.STEP_M] steps from where the AMO stands (D-AMO-BASE-01). Shows how far the pin is from the AMO and the limit.
 */
@Composable
fun UpdateBasePointContent(
    northM: Int, eastM: Int, maxM: Int, hasFix: Boolean, onMove: (dn: Int, de: Int) -> Unit, onReset: () -> Unit, modifier: Modifier = Modifier,
) {
    val c = LocalAronColors.current
    val step = UpdateBasePoint.STEP_M
    fun can(dn: Int, de: Int) = hasFix && UpdateBasePoint.canMove(northM, eastM, dn, de, maxM)
    AmoCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            Text(stringResource(R.string.amo_base_point_title), style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
            Text(
                if (hasFix) stringResource(R.string.amo_base_point_offset, localizedNumber(UpdateBasePoint.offsetM(northM, eastM).toLong()), localizedNumber(maxM.toLong()))
                else stringResource(R.string.amo_base_point_nofix),
                style = MaterialTheme.typography.bodyMedium, color = c.textSecondary,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                AmoSecondary(stringResource(R.string.amo_base_north, localizedNumber(step.toLong())), { onMove(step, 0) }, Modifier.weight(1f), enabled = can(step, 0))
                AmoSecondary(stringResource(R.string.amo_base_south, localizedNumber(step.toLong())), { onMove(-step, 0) }, Modifier.weight(1f), enabled = can(-step, 0))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                AmoSecondary(stringResource(R.string.amo_base_west, localizedNumber(step.toLong())), { onMove(0, -step) }, Modifier.weight(1f), enabled = can(0, -step))
                AmoSecondary(stringResource(R.string.amo_base_east, localizedNumber(step.toLong())), { onMove(0, step) }, Modifier.weight(1f), enabled = can(0, step))
            }
            AmoSecondary(stringResource(R.string.amo_base_reset), onReset, Modifier.fillMaxWidth(), enabled = hasFix && (northM != 0 || eastM != 0))
        }
    }
}
