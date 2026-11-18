package com.aktcl.aron.sr

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.aron.AronLoaded
import com.aktcl.aron.core.ui.aron.rememberAsyncLoad
import com.aktcl.aron.feature.programmes.AsthaData
import com.aktcl.aron.feature.programmes.AsthaModel
import com.aktcl.aron.feature.programmes.AsthaTargetScreen
import com.aktcl.aron.rules.UiLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.aktcl.aron.feature.home.R as HomeR
import com.aktcl.aron.feature.programmes.R as ProgR

/** The year of the Dhaka business date (the first option of the year list); the bundle's business date, else the phone clock in Asia/Dhaka. */
internal fun programmeYear(businessDate: String?, nowMs: Long): Int =
    businessDate?.take(4)?.toIntOrNull()
        ?: java.time.Instant.ofEpochMilli(nowMs).atZone(java.time.ZoneId.of("Asia/Dhaka")).year

/** The stored sections the Astha screens read, parsed off the main thread; null is "no programme active", a throw is the error state with its retry. */
internal suspend fun readAstha(day: SrDay): AsthaData? = withContext(Dispatchers.IO) {
    AsthaModel.parse(day.reference.section("programmes"), day.reference.section("products"))
}

/** The Astha target page of the SR (manual 48 right to 50): downloaded data only, no network in the path. */
@Composable
fun AsthaHost(day: SrDay, locale: UiLocale) {
    val load = rememberAsyncLoad { readAstha(day) to programmeYear(day.reference.businessDate(), System.currentTimeMillis()) }
    AronLoaded(load, stringResource(ProgR.string.pg_load_failed), stringResource(HomeR.string.fb_retry)) { (data, year) ->
        AsthaTargetScreen(data, locale, year)
    }
}
