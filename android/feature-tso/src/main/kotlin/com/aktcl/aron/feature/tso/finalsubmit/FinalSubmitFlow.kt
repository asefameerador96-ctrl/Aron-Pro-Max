package com.aktcl.aron.feature.tso.finalsubmit

import androidx.compose.runtime.Composable
import com.aktcl.aron.feature.tso.RetainedCounter
import com.aktcl.aron.feature.tso.rememberRetained

/**
 * Final Submit (F-TSO-010/021, online only), moved out of TsoApp by a1-stubs with the behaviour unchanged: the state
 * survives recreation and leaving the screen (the on-screen back and the system back alike, FinalSubmitScreen routes both
 * through [onBack]) starts a fresh one next time (the epoch).
 */
@Composable
fun FinalSubmitFlow(api: FinalSubmitApi, userKey: String, userId: Long, today: () -> String, onBack: () -> Unit) {
    val counter = rememberRetained("tso-$userKey-fs-epoch") { RetainedCounter() }
    val st = rememberRetained("tso-$userKey-fs-${counter.n}") { FinalSubmitState(api, userId, today) }
    FinalSubmitScreen(st, onBack = { onBack(); counter.n++ })
}
