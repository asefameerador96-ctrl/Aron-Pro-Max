package com.aktcl.aron.feature.tso.periphery

import androidx.compose.runtime.Composable
import com.aktcl.aron.feature.tso.TsoNav
import com.aktcl.aron.feature.tso.rememberRetained

/**
 * What My Periphery needs from the host: the reads, one fix on demand, connectivity, trusted time and the "as of" clock
 * of an answer worked out on the phone ([asOfNow], hh:mm Asia/Dhaka).
 */
class PeripheryDeps(
    val source: PeripherySource, val fix: PeripheryFix,
    val online: () -> Boolean, val nowMs: () -> Long, val settings: com.aktcl.aron.core.map.MapSettings = com.aktcl.aron.core.map.MapSettings(),
    val asOfNow: () -> String = { "" },
)

/**
 * My Team as its own drawer destination (TsoScreen.MY_TEAM, D-TSO-MAN-14/34). The state survives recreation and is per
 * user; back (system or on-screen) and Home go through [nav].
 */
@Composable
fun MyTeamEntry(deps: PeripheryDeps, userKey: String, today: () -> String, nav: TsoNav) {
    val st = rememberRetained("tso-$userKey-pe-team") { TeamState(deps.source, today) }
    TeamScreen(st, deps.online, deps.nowMs, userKey, onBack = { nav.back() }, settings = deps.settings, nav = nav)
}

/**
 * Retailer as its own drawer destination (TsoScreen.RETAILER): opening it takes one balanced fix for the preselected zone
 * and the first radius (D-TSO-MAN-34); a recreated screen keeps its answer and takes no new fix.
 */
@Composable
fun RetailerEntry(deps: PeripheryDeps, userKey: String, nav: TsoNav) {
    val st = rememberRetained("tso-$userKey-pe-retailer") { RetailerState(deps.source, deps.asOfNow, deps.fix) }
    RetailerScreen(st, deps.online, deps.nowMs, userKey, onBack = { nav.back() }, settings = deps.settings, nav = nav, findOnOpen = true)
}
