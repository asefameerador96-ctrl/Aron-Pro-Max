package com.aktcl.aron.amo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.aktcl.aron.feature.amo.SkuInfo
import com.aktcl.aron.feature.amo.SrStock
import com.aktcl.aron.feature.amo.TeamStock
import com.aktcl.aron.feature.amo.TeamStockDetailContent
import com.aktcl.aron.feature.amo.TeamStockListContent

/**
 * F-AMO-021 SR Stock: the SRs (masked phones from the bundle's team) and each one's lifted stock by SKU for today, with per-category totals.
 * Online read with a kept copy; [selected] is the SR whose detail is open (owned by the hub so Back returns to the list).
 */
@Composable
fun AmoTeamStock(day: AmoDay, selected: Long?, onSelect: (Long) -> Unit, modifier: Modifier = Modifier) {
    var srs by remember { mutableStateOf<List<SrStock>?>(null) }
    var phones by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var skus by remember { mutableStateOf<Map<Long, SkuInfo>>(emptyMap()) }
    var stale by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val bangla = com.aktcl.aron.core.ui.LocalAppLanguage.current == com.aktcl.aron.core.common.AppLanguage.BN
    LaunchedEffect(day) {
        phones = TeamStock.phones(runCatching { day.reference.section("supervisor") }.getOrNull())
        skus = runCatching { day.skuInfo() }.getOrDefault(emptyMap())
        val f = day.api.teamStock(day.businessDate())
        stale = f is AmoFetch.Cached; failed = f is AmoFetch.Unavailable
        srs = TeamStock.parse(when (f) { is AmoFetch.Fresh -> f.json; is AmoFetch.Cached -> f.json; AmoFetch.Unavailable -> null })
    }
    val list = srs ?: run { Box(modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }; return }
    val sr = list.firstOrNull { it.userId == selected }
    if (sr == null) TeamStockListContent(list, phones, stale, failed, { onSelect(it.userId) }, modifier)
    else TeamStockDetailContent(sr, skus, TeamStock.totals(sr.lines, skus), bangla, modifier)
}
