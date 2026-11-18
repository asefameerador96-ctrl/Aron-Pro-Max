package com.aktcl.aron.sr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.aron.AronLoaded
import com.aktcl.aron.core.ui.aron.rememberAsyncLoad
import com.aktcl.aron.feature.programmes.AsthaData
import com.aktcl.aron.feature.programmes.AsthaModel
import com.aktcl.aron.feature.programmes.AsthaTargetScreen
import com.aktcl.aron.feature.programmes.GiftChoiceData
import com.aktcl.aron.feature.programmes.GiftChoiceModel
import com.aktcl.aron.feature.programmes.GiftSelectionScreen
import com.aktcl.aron.feature.programmes.LocalGiftChoice
import com.aktcl.aron.feature.programmes.LocalRedeemed
import com.aktcl.aron.feature.programmes.LoyaltyData
import com.aktcl.aron.feature.programmes.LocalRedemption
import com.aktcl.aron.feature.programmes.LoyaltyModel
import com.aktcl.aron.feature.programmes.RedemptionScreen
import com.aktcl.aron.feature.programmes.OutletPointsScreen
import com.aktcl.aron.feature.programmes.PointsBalance
import com.aktcl.aron.feature.programmes.SaveOutcome
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
internal data class AsthaLoad(val data: AsthaData?, val year: Int, val shops: List<ShopInfo>, val month: String?)

internal suspend fun readAsthaPage(day: SrDay, locale: UiLocale): AsthaLoad = withContext(Dispatchers.IO) {
    val data = readAstha(day)
    val date = day.reference.businessDate()
    val outlets = readShopOutlets(day)
    val subs = ShopModel.subChannels(day.reference.section("code_lists"))
    AsthaLoad(data, programmeYear(date, System.currentTimeMillis()), ShopModel.build(outlets, data, subs, locale), date?.take(7))
}

/** The Astha target page of the SR (manual 48 right to 51): downloaded data only, no network in the path. */
@Composable
fun AsthaHost(day: SrDay, locale: UiLocale) {
    val load = rememberAsyncLoad(locale) { readAsthaPage(day, locale) }
    AronLoaded(load, stringResource(ProgR.string.pg_load_failed), stringResource(HomeR.string.fb_retry)) { page ->
        AsthaTargetScreen(page.data, locale, page.year, shops = page.shops, currentMonth = page.month)
    }
}

/** The outlets of every route of the bundle, as the programme screens need them (picker label, owner, phone, sub-channel). */
internal suspend fun readShopOutlets(day: SrDay): List<ShopOutlet> = withContext(Dispatchers.IO) {
    day.reference.routesOfDay(day.reference.businessDate() ?: "").flatMap { r -> r.outlets.map { it to r.route.name } }
        .distinctBy { it.first.outletId }.sortedBy { if (it.first.routeId == day.currentRouteId()) 0 else 1 } // the route being worked first
        .map { (it, routeName) -> ShopOutlet(it.outletId, com.aktcl.aron.feature.outlet.OutletPicker.label(it), it.code, it.ownerName, it.contactNumber, it.subChannelId, com.aktcl.aron.feature.outlet.OutletPicker.chipOf(it.name), it.routeId, routeName, it.clusterName) }
}

/** The outlet points page (manual 55): the bundle's balances minus this phone's own redemptions (the same function as the redemption screen), offline. */
@Composable
fun OutletPointsHost(day: SrDay, locale: UiLocale, initialOutlet: Long? = null, onRedeem: ((Long) -> Unit)? = null) {
    val load = rememberAsyncLoad {
        val store = day.programmes
        RedemptionLoad(withContext(Dispatchers.IO) { LoyaltyModel.parse(day.reference.section("programmes")) }, readShopOutlets(day), store.redemptions(), store.bundleBuiltAt())
    }
    AronLoaded(load, stringResource(ProgR.string.pg_load_failed), stringResource(HomeR.string.fb_retry)) { page ->
        OutletPointsScreen(page.outlets, page.data, locale, onRedeem = onRedeem, initialOutlet = initialOutlet, balanceOf = PointsBalance.function(page.redemptions, page.builtAt))
    }
}

/**
 * Gift redemption (manual 53 right, 54). The basket's redemption uuid is fixed when the basket opens (and renewed only after a save), so a double
 * tap, a retry or a replay after a kill and relaunch is one redemption; a failed save leaves the same uuid for the next try. Offline: the balances are
 * the bundle's, less what this phone redeemed since (the server may accept an overdraw, so the screen warns and does not block).
 */
@Composable
fun RedemptionHost(day: SrDay, locale: UiLocale, initialOutlet: Long? = null) {
    val load = rememberAsyncLoad {
        val store = day.programmes
        RedemptionLoad(withContext(Dispatchers.IO) { LoyaltyModel.parse(day.reference.section("programmes")) }, readShopOutlets(day), store.redemptions(), store.bundleBuiltAt())
    }
    AronLoaded(load, stringResource(ProgR.string.pg_load_failed), stringResource(HomeR.string.fb_retry)) { page ->
        var redeemed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(page.redemptions) }
        var uuid by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(day.programmes.newUuid()) }
        RedemptionScreen(
            page.outlets, page.data, locale,
            balanceOf = PointsBalance.function(redeemed, page.builtAt),
            onConfirm = { basket, gifts ->
                val outlet = page.outlets.firstOrNull { it.outletId == basket.outletId }
                val rate = runCatching { day.reference.config("cfg.loyalty.cash_rate_mtk_per_point", com.aktcl.aron.core.sync.SyncEngine.iso(System.currentTimeMillis()))?.trim('"')?.toLongOrNull() }.getOrNull() ?: 0L
                val meta = day.programmes.metaFor(outlet?.routeId ?: 0L) // ONE meta per basket: the redemption, its lines and confirmed_at share one instant and one business date
                val planned = basket.plan(gifts, uuid, day.programmes::newUuid, meta.capturedAt, rate)
                val out = day.programmes.saveProgramme(meta, planned)
                when (out) {
                    SaveOutcome.SAVED, SaveOutcome.ALREADY_SAVED -> { redeemed = day.programmes.redemptions(); uuid = day.programmes.newUuid() }
                    SaveOutcome.CONFLICT -> uuid = day.programmes.newUuid() // a different basket held the uuid: the next confirm is a new redemption
                    else -> Unit
                }
                out
            },
            initialOutlet = initialOutlet,
        )
    }
}

internal data class RedemptionLoad(val data: LoyaltyData?, val outlets: List<ShopOutlet>, val redemptions: List<LocalRedemption>, val builtAt: String?)

internal data class GiftLoad(val data: GiftChoiceData?, val shops: List<ShopInfo>, val local: List<LocalGiftChoice>, val quarter: String)

/**
 * Astha gift selection (manual 52). The record uuid is fixed when the page opens and renewed only after a save, so a double tap or a replay is one
 * record; a later choice for the same outlet and quarter is a new record (the server keeps the later one until the hand-over photo locks it).
 */
@Composable
fun GiftSelectionHost(day: SrDay, locale: UiLocale) {
    val load = rememberAsyncLoad(locale) {
        withContext(Dispatchers.IO) {
            val prog = day.reference.section("programmes")
            val data = GiftChoiceModel.parse(prog)
            val outlets = readShopOutlets(day)
            val subs = ShopModel.subChannels(day.reference.section("code_lists"))
            val ids = (AsthaModel.parse(prog, null)?.outletIds.orEmpty() + data?.assignments?.map { it.outletId }.orEmpty()).toSet()
            val startMonth = day.reference.config("cfg.astha.quarter_start_month", com.aktcl.aron.core.sync.SyncEngine.iso(System.currentTimeMillis()))?.trim('"')?.toIntOrNull()?.takeIf { it in 1..12 } ?: 1
            val today = day.reference.businessDate() ?: com.aktcl.aron.rules.BusinessDate.of(System.currentTimeMillis()).toString()
            GiftLoad(data, ShopModel.buildFor(outlets, ids, subs, locale, day.currentRouteId()), day.programmes.giftChoices(), GiftChoiceModel.quarterOf(today, startMonth))
        }
    }
    AronLoaded(load, stringResource(ProgR.string.pg_load_failed), stringResource(HomeR.string.fb_retry)) { page ->
        var local by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(page.local) }
        var uuid by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(day.programmes.newUuid()) }
        GiftSelectionScreen(page.shops, page.data, page.quarter, local, locale, { outletId, giftId ->
            val shop = page.shops.firstOrNull { it.outletId == outletId }
            val out = day.programmes.saveProgramme(day.programmes.metaFor(shop?.routeId ?: 0L), listOf(GiftChoiceModel.plan(outletId, giftId, page.quarter, uuid)))
            when (out) {
                SaveOutcome.SAVED, SaveOutcome.ALREADY_SAVED -> { local = day.programmes.giftChoices(); uuid = day.programmes.newUuid() }
                SaveOutcome.CONFLICT -> uuid = day.programmes.newUuid()
                else -> Unit
            }
            out
        })
    }
}

/** The route being worked (0 when none), for the "route being worked first" order of the pickers. */
internal fun SrDay.currentRouteId(): Long? = metaProvider.meta(0L).routeId
