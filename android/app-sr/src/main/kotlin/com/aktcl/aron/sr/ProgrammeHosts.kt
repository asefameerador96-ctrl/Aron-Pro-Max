package com.aktcl.aron.sr

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.aron.AronLoaded
import com.aktcl.aron.core.ui.aron.rememberAsyncLoad
import com.aktcl.aron.feature.programmes.AsthaData
import com.aktcl.aron.feature.programmes.AsthaModel
import com.aktcl.aron.feature.programmes.AsthaTargetScreen
import com.aktcl.aron.feature.programmes.ShopInfo
import com.aktcl.aron.feature.programmes.ShopModel
import com.aktcl.aron.feature.programmes.ShopOutlet
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

/** What the Astha page reads in one pass: the programme rows, the year, and the shop-wise cards (outlets of every route of the bundle). */
internal data class AsthaLoad(val data: AsthaData?, val year: Int, val shops: List<ShopInfo>)

internal suspend fun readAsthaPage(day: SrDay, locale: UiLocale): AsthaLoad = withContext(Dispatchers.IO) {
    val data = readAstha(day)
    val date = day.reference.businessDate()
    val outlets = day.reference.routesOfDay(date ?: "").flatMap { it.outlets }.distinctBy { it.outletId }
        .map { ShopOutlet(it.outletId, com.aktcl.aron.feature.outlet.OutletPicker.label(it), it.code, it.ownerName, it.contactNumber, it.subChannelId) }
    val subs = ShopModel.subChannels(day.reference.section("code_lists"))
    AsthaLoad(data, programmeYear(date, System.currentTimeMillis()), ShopModel.build(outlets, data, subs, locale))
}

/** The Astha target page of the SR (manual 48 right to 51): downloaded data only, no network in the path. */
@Composable
fun AsthaHost(day: SrDay, locale: UiLocale) {
    val load = rememberAsyncLoad(locale) { readAsthaPage(day, locale) }
    AronLoaded(load, stringResource(ProgR.string.pg_load_failed), stringResource(HomeR.string.fb_retry)) { page ->
        AsthaTargetScreen(page.data, locale, page.year, shops = page.shops)
    }
}
