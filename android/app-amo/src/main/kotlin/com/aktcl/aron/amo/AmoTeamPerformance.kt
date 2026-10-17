package com.aktcl.aron.amo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.aktcl.aron.feature.amo.PerfBar
import com.aktcl.aron.feature.amo.TeamPerformance
import com.aktcl.aron.feature.amo.TeamPerformanceContent

/** F-AMO-017 Team Performance: the category bars of the month (Monthly / Till Date tabs). Targets are deferred (docs/27): an empty answer shows the empty state. */
@Composable
fun AmoTeamPerformance(day: AmoDay, modifier: Modifier = Modifier) {
    var json by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var stale by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var tillDate by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(day) {
        val f = day.api.targets(day.businessDate().take(7))
        json = when (f) { is AmoFetch.Fresh -> f.json; is AmoFetch.Cached -> f.json; AmoFetch.Unavailable -> null }
        stale = f is AmoFetch.Cached; failed = f is AmoFetch.Unavailable; loaded = true
    }
    if (!loaded) { Box(modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }; return }
    val bars: List<PerfBar> = TeamPerformance.bars(json, "category", tillDate)
    TeamPerformanceContent(bars, tillDate, { tillDate = it }, stale, failed, modifier)
}
