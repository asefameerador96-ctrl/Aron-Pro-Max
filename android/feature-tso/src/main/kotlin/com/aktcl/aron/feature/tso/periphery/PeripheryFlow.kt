package com.aktcl.aron.feature.tso.periphery

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.aktcl.aron.feature.tso.rememberRetained

/** What My Periphery needs from the host: the reads, one fix on demand, connectivity and trusted time. */
class PeripheryDeps(
    val source: PeripherySource, val fix: PeripheryFix,
    val online: () -> Boolean, val nowMs: () -> Long, val settings: com.aktcl.aron.core.map.MapSettings = com.aktcl.aron.core.map.MapSettings(),
)

/** My Periphery (F-TSO-011/012): the hub, My Team and Retailer; moved out of TsoApp by a1-stubs with the behaviour unchanged. */
@Composable
fun PeripheryFlow(deps: PeripheryDeps, userKey: String, today: () -> String, onBack: () -> Unit) {
    var step by rememberSaveable { mutableStateOf("hub") }
    BackHandler(enabled = step != "hub") { step = "hub" }
    when (step) {
        "team" -> {
            val st = rememberRetained("tso-$userKey-pe-team") { TeamState(deps.source, today) }
            TeamScreen(st, deps.online, deps.nowMs, userKey, onBack = { step = "hub" }, settings = deps.settings)
        }
        "retailer" -> {
            val st = rememberRetained("tso-$userKey-pe-retailer") { RetailerState(deps.source, deps.fix) }
            RetailerScreen(st, deps.online, deps.nowMs, userKey, onBack = { step = "hub" }, settings = deps.settings)
        }
        else -> PeripheryHub({ step = "team" }, { step = "retailer" }, onBack)
    }
}
