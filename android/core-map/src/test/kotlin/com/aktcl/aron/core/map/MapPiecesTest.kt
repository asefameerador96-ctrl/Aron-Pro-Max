package com.aktcl.aron.core.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** N-053 / F-SYS-074: config-driven settings, the bounded cache, the fix age and the map/list decision. */
class MapPiecesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun settingsComeFromConfigWithinTheRegistryBounds() {
        assertEquals(MapSettings(MapProvider.GOOGLE, 20), MapSettings.of(null, null))
        assertEquals(MapSettings(MapProvider.MAPLIBRE, 35), MapSettings.of(" MapLibre ", 35))
        assertEquals(MapSettings(MapProvider.GOOGLE, 5), MapSettings.of("google", 5))
        assertEquals(100, MapSettings.of("google", 100).tileCacheMb)
        listOf(4, 101, 0, -1).forEach { assertEquals(20, MapSettings.of("google", it).tileCacheMb) }
        assertEquals(MapProvider.GOOGLE, MapSettings.of("osm", null).provider) // unknown spelling: the default
        assertEquals(20L * 1024 * 1024, MapSettings().tileCacheBytes)
        assertFalse("MapLibre is not bundled: list only", MapSettings.of("maplibre", null).drawable)
    }

    @Test
    fun theCacheStaysWithinItsCapDroppingTheLeastRecentlyUsed() {
        var cap = 2_500L
        val cache = MapTileCache(File(tmp.root, "map"), { cap })
        cache.put("team-zone-3", ByteArray(1_000))
        cache.put("team-zone-4", ByteArray(1_000))
        assertNotNull(cache.get("team-zone-3")) // touched: zone 4 is now the least recently used
        cache.put("retailers-zone-3", ByteArray(1_000))
        assertNull(cache.get("team-zone-4"))
        assertNotNull(cache.get("team-zone-3"))
        assertNotNull(cache.get("retailers-zone-3"))
        assertTrue(cache.sizeBytes() <= cap)
        cap = 1_200 // the config shrank: the next write trims to it
        cache.put("team-zone-5", ByteArray(1_000))
        assertTrue(cache.sizeBytes() <= cap)
        assertNotNull(cache.get("team-zone-5"))
        cache.put("huge", ByteArray(5_000)) // larger than the cap: never stored
        assertNull(cache.get("huge"))
    }

    @Test
    fun cacheKeysNeverEscapeTheDirectoryAndTempFilesAreCleared() {
        val dir = File(tmp.root, "map")
        val cache = MapTileCache(dir, { 10_000L })
        cache.put("../../etc/passwd", byteArrayOf(1))
        assertEquals(listOf(dir), dir.parentFile!!.listFiles()!!.toList())
        assertTrue(dir.listFiles()!!.all { it.parentFile == dir && !it.name.contains("/") })
        File(dir, "x.webp.tmp").writeBytes(byteArrayOf(1, 2)) // a kill between write and rename
        cache.put("a", byteArrayOf(1))
        assertFalse(File(dir, "x.webp.tmp").exists())
    }

    @Test
    fun theFixAgeIsDhakaTimeWithMinutesAndGreysAfterTheLimit() {
        val fixAt = 1_791_342_600_000L // 2026-10-07T03:10:00Z = 09:10 Dhaka
        assertEquals(FixAge("09:10", 15, false), FixAge.of(fixAt, fixAt + 15 * 60_000))
        assertEquals(FixAge("09:10", 120, true), FixAge.of(fixAt, fixAt + 120 * 60_000))
        assertEquals(FixAge("09:10", 119, false), FixAge.of(fixAt, fixAt + 119 * 60_000 + 59_999))
        assertEquals(FixAge("09:10", 30, true), FixAge.of(fixAt, fixAt + 30 * 60_000, staleAfterMin = 30))
        assertEquals(0L, FixAge.of(fixAt, fixAt - 60_000)!!.minutesAgo) // a fix stamped ahead never reads negative
        assertNull(FixAge.of(null, fixAt))
    }

    @Test
    fun theMapLoadsOnlyOnlineWithAKeyAGoogleProviderAndALocatedPoint() {
        val p = listOf(MapPoint("1", "Rahim", 23.79, 90.40, 0L, "visit"))
        val g = MapSettings()
        assertEquals(MapMode.LIVE, mapModeOf(true, g, true, p))
        assertEquals(MapMode.OFFLINE, mapModeOf(false, g, true, p))
        assertEquals(MapMode.OFFLINE, mapModeOf(false, g, false, p))
        assertEquals(MapMode.UNAVAILABLE, mapModeOf(true, g, false, p))
        assertEquals(MapMode.UNAVAILABLE, mapModeOf(true, MapSettings.of("maplibre", null), true, p))
        assertEquals(MapMode.UNAVAILABLE, mapModeOf(true, g, true, listOf(MapPoint("2", "Karim", null, null))))
        assertEquals(MapMode.UNAVAILABLE, mapModeOf(true, g, true, listOf(MapPoint("3", "Bad", 123.0, 90.0))))
    }

    @Test
    fun theFrameFitsEveryLocatedPoint() {
        val one = MapFrame.of(listOf(MapPoint("1", "a", 23.79, 90.40)))!!
        assertEquals(17f, one.zoom) // a single pin: street level
        val zone = MapFrame.of(listOf(MapPoint("1", "a", 23.70, 90.35), MapPoint("2", "b", 23.90, 90.45), MapPoint("3", "c", null, null)))!!
        assertEquals(23.80, zone.lat, 1e-9)
        assertEquals(90.40, zone.lng, 1e-9)
        assertTrue(zone.zoom in 9f..12f)
        assertNull(MapFrame.of(listOf(MapPoint("3", "c", null, null))))
    }

    /** "Loaded only when its screen opens": only the screen composable touches Maps SDK classes; the geocoder never does. */
    @Test
    fun onlyTheScreenComposableReferencesTheMapsSdk() {
        val src = File("src/main/kotlin/com/aktcl/aron/core/map")
        val users = src.listFiles()!!.filter { f -> f.readText().contains("com.google.android.gms.maps") || f.readText().contains("com.google.maps") }
        assertEquals(listOf("LiteMapScreen.kt"), users.map { it.name })
    }
}
