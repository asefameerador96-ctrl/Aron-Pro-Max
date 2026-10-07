package com.aktcl.aron.core.map

import android.content.Context
import android.content.pm.PackageManager

/** `cfg.map.provider` (docs/19 s5: enum google, maplibre; D-08). */
enum class MapProvider { GOOGLE, MAPLIBRE }

/**
 * The map settings a map screen reads when it opens (docs/17 s10.5): the provider and the bounded cache from config,
 * 2D lite mode always (`cfg.map.3d_enabled` is false by D-08 and lite mode has no 3D). Built from the raw config values
 * so callers need no JSON; a value outside the registry bounds falls back to the default.
 */
data class MapSettings(val provider: MapProvider = MapProvider.GOOGLE, val tileCacheMb: Int = DEFAULT_TILE_CACHE_MB) {
    val tileCacheBytes: Long get() = tileCacheMb.toLong() * 1024 * 1024

    /**
     * True when this build can draw a map with [provider]. MapLibre is the D-08 fallback and is not bundled (it would
     * cost APK size the budget has not cleared), so a MapLibre setting shows the list, never a broken map.
     */
    val drawable: Boolean get() = provider == MapProvider.GOOGLE

    companion object {
        const val DEFAULT_TILE_CACHE_MB = 20
        val TILE_CACHE_RANGE = 5..100

        fun of(provider: String?, tileCacheMb: Int?): MapSettings = MapSettings(
            provider = when (provider?.trim()?.lowercase()) {
                "maplibre" -> MapProvider.MAPLIBRE
                else -> MapProvider.GOOGLE // the default, and an unknown spelling never disables the map
            },
            tileCacheMb = tileCacheMb?.takeIf { it in TILE_CACHE_RANGE } ?: DEFAULT_TILE_CACHE_MB,
        )
    }
}

/**
 * Whether the build carries a Maps key (the manifest placeholder `mapsApiKey`, injected at build from a GitHub secret).
 * Only presence is read: the value is never logged, returned or stored (sponsor rule 8).
 */
object MapsKey {
    private const val META = "com.google.android.geo.API_KEY"

    fun present(context: Context): Boolean = runCatching {
        val info = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        !info.metaData?.getString(META).isNullOrBlank()
    }.getOrDefault(false)
}
