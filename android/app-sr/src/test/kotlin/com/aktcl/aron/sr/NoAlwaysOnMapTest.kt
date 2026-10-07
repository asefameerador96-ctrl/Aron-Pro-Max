package com.aktcl.aron.sr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F-SYS-074 acceptance: there is no always-on map in the SR app. The Maps SDK is referenced only by the on-tap
 * OutletMapActivity (N-041), which nothing starts except the Map button on the out-of-range screen; the shared lite map
 * (core-map) is not used by the SR flow at all, and the attendance address uses the geocoder, never a map.
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
    fun theOutletMapStartsOnlyFromAnExplicitTap() {
        val starters = sources.filter { it.name != "OutletMapActivity.kt" && it.readText().contains("OutletMapActivity") }
        starters.forEach { f ->
            f.readLines().filter { it.contains("OutletMapActivity") }.forEach { line ->
                assertTrue("${f.name}: $line", line.contains("startActivity") || line.contains("intent(") || line.trimStart().startsWith("import"))
            }
        }
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val entry = Regex("<activity[^>]*OutletMapActivity[^>]*/?>", RegexOption.DOT_MATCHES_ALL).find(manifest)?.value.orEmpty()
        assertTrue("not exported, no launcher: $entry", entry.isNotEmpty() && !entry.contains("exported=\"true\""))
    }
}
