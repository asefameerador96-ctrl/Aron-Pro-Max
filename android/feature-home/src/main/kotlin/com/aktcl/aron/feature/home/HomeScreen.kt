package com.aktcl.aron.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronTile
import com.aktcl.aron.core.ui.AronTileGrid
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber

object HomeScreenTags {
    const val TITLE = "hm_title"
    const val SUBTITLE = "hm_subtitle"
    const val BANNER = "hm_banner"
    const val HEALTH = "hm_health"
    const val SETTINGS = "hm_settings"
    fun tile(t: HomeTile) = "hm_tile_${t.key}"
}

@Composable
private fun tileLabel(t: HomeTile): String = stringResource(
    when (t) {
        HomeTile.ATTENDANCE -> R.string.hm_tile_attendance
        HomeTile.STOCK -> R.string.hm_tile_stock
        HomeTile.SALE -> R.string.hm_tile_sale
        HomeTile.MEMO -> R.string.hm_tile_memo
        HomeTile.SUMMARY -> R.string.hm_tile_summary
        HomeTile.SALES_SUBMIT -> R.string.hm_tile_sales_submit
        HomeTile.OUTLET -> R.string.hm_tile_outlet
        HomeTile.TUTORIAL -> R.string.hm_tile_tutorial
        HomeTile.TASKS -> R.string.hm_tile_tasks
        HomeTile.PHOTO_CAPTURE -> R.string.hm_tile_photo_capture
        HomeTile.SALES_JOURNEY -> R.string.hm_tile_sales_journey
        HomeTile.KPI -> R.string.hm_tile_kpi
    },
)

@Composable
private fun routeKindLabel(k: RouteKind?): String? = k?.let {
    stringResource(when (it) { RouteKind.DAILY -> R.string.hm_route_daily; RouteKind.THREE_F -> R.string.hm_route_3f; RouteKind.TWO_F -> R.string.hm_route_2f })
}

/**
 * The SR Home (F-SR-008 header, F-SR-009 tiles with the task badge, F-SR-063 banners, F-SR-064 device-health line).
 * Everything comes from local state: nothing here waits for the network. When the bundle is expired or missing, the tiles
 * that need a sale are disabled; attendance always works.
 */
@Composable
fun HomeContent(
    header: HomeHeader,
    tiles: List<ResolvedTile>,
    freshness: BundleFreshness,
    offline: Boolean,
    health: DeviceHealth?,
    onTile: (HomeTile) -> Unit,
    modifier: Modifier = Modifier,
    onSettings: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = AronTokens.Space.M)) {
        Column(Modifier.padding(horizontal = AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
            Text(header.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag(HomeScreenTags.TITLE))
            Text(header.subtitle, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(HomeScreenTags.SUBTITLE))
            routeKindLabel(header.routeKind)?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
            if (onSettings != null) AronSecondaryButton(stringResource(R.string.set_open), onSettings, Modifier.testTag(HomeScreenTags.SETTINGS))
        }
        when (freshness) {
            BundleFreshness.Fresh -> Unit
            is BundleFreshness.Stale -> AronBanner(stringResource(R.string.hm_banner_stale, localizedNumber(freshness.ageDays.toLong())), Modifier.testTag(HomeScreenTags.BANNER), BannerKind.Warning)
            is BundleFreshness.Expired -> AronBanner(stringResource(R.string.hm_banner_expired, localizedNumber(freshness.ageDays.toLong())), Modifier.testTag(HomeScreenTags.BANNER), BannerKind.Error)
            BundleFreshness.Missing -> AronBanner(stringResource(R.string.hm_banner_missing), Modifier.testTag(HomeScreenTags.BANNER), BannerKind.Error)
        }
        if (offline) AronBanner(stringResource(R.string.hm_banner_offline), kind = BannerKind.Info)
        AronTileGrid(tiles, columns = 4) { rt, mod ->
            val needsSelling = rt.tile in SELLING_TILES
            AronTile(
                label = tileLabel(rt.tile), onClick = { onTile(rt.tile) }, badge = rt.badge ?: 0,
                modifier = mod.testTag(HomeScreenTags.tile(rt.tile)), enabled = !(needsSelling && !freshness.canSell),
            )
        }
        health?.let { h ->
            Column(Modifier.padding(horizontal = AronTokens.Space.L).testTag(HomeScreenTags.HEALTH), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
                Text(
                    listOf(
                        stringResource(R.string.hm_health_battery, localizedNumber(h.batteryPct.toLong())),
                        stringResource(R.string.hm_health_storage, localizedNumber(h.freeStorageMb)),
                        stringResource(R.string.hm_health_pending, localizedNumber(h.pendingRows.toLong())),
                        h.lastSyncAgeMin?.let { stringResource(R.string.hm_health_sync, localizedNumber(it)) } ?: stringResource(R.string.hm_health_never_synced),
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (h.anyWarning) AronBanner(stringResource(R.string.hm_health_warning), kind = BannerKind.Warning)
            }
        }
    }
}

/** Tiles that start or change a sale: disabled when the bundle cannot sell. Memo and Summary stay readable. */
private val SELLING_TILES = setOf(HomeTile.STOCK, HomeTile.SALE, HomeTile.SALES_SUBMIT)
