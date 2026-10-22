package com.aktcl.aron.sr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F-SYS-074 acceptance: there is no always-on map in the SR app. The Maps SDK is referenced only by the on-tap
 * OutletMapActivity (N-041), which nothing starts except the Map button on the confirm step; the team-location lite map
 * (core-map LiteMapScreen) is not used by the SR flow, and the attendance address uses the geocoder, never a map.
 * D-MAP-02 (2026-10-22) adds one more place: the single-outlet lite map on the Sale confirm step, reached only through
 * SrGeoMap.kt (core-map OutletLiteMap) and composed only by that step.
 */
class NoAlwaysOnMapTest {
    private val src = File("src/main/kotlin")
    private val sources = src.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun onlyTheOnTapOutletMapReferencesTheMapsSdk() {
        val users = sources.filter { f -> f.readText().let { it.contains("com.google.maps.android") || it.contains("com.google.android.gms.maps") } }
        assertEquals(listOf("OutletMapActivity.kt"), users.map { it.name })
        assertTrue(sources.none { it.readText().contains("LiteMapScreen") })
    }

    @Test
    fun theInlineOutletMapIsComposedOnlyByTheConfirmStepSlot() {
        val users = sources.filter { it.readText().contains("OutletLiteMap") }.map { it.name }
        assertEquals(listOf("SrGeoMap.kt"), users)
        val slotUsers = sources.filter { it.name != "SrGeoMap.kt" && it.readText().contains("rememberOutletMapSlot") }.map { it.name }.sorted()
        assertEquals(listOf("SrApp.kt", "SrSaleHosts.kt"), slotUsers)
    }

    @Test
    fun theOutletMapStartsOnlyFromAnExplicitTap() {
        val starters = sources.filter { it.name != "OutletMapActivity.kt" && it.readText().contains("OutletMapActivity") }
        starters.forEach { f ->
            f.readLines().filter { it.contains("OutletMapActivity") }.forEach { line ->
                // Started only by an explicit startActivity in a click handler (SrApp: the Map button), never from onCreate/init.
                assertTrue("${f.name}: $line", line.trimStart().startsWith("import") || (line.contains("startActivity(") && line.trimStart().startsWith("{")))
            }
        }
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val entry = Regex("<activity[^>]*OutletMapActivity[^>]*/?>", RegexOption.DOT_MATCHES_ALL).find(manifest)?.value.orEmpty()
        assertTrue("not exported, no launcher: $entry", entry.isNotEmpty() && !entry.contains("exported=\"true\""))
    }
}
