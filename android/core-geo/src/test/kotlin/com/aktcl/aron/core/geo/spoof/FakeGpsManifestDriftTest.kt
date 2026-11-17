package com.aktcl.aron.core.geo.spoof

import com.aktcl.aron.core.geo.integrity.RootHints
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android hides every package the manifest does not declare. The fake-GPS list (one resource file) and the root, hook and
 * clone lists of RootHints must therefore equal the manifest `<queries>`; and QUERY_ALL_PACKAGES must not come back.
 */
class FakeGpsManifestDriftTest {
    private val moduleDir = File(System.getProperty("user.dir")!!)
    private val manifest = File(moduleDir, "src/main/AndroidManifest.xml").readText()
    private val resource = File(moduleDir, "src/main/res/values/fake_gps_packages.xml").readText()

    private fun queries(): Set<String> = Regex("""<queries>(.*?)</queries>""", RegexOption.DOT_MATCHES_ALL).find(manifest)!!.groupValues[1]
        .let { body -> Regex("""<package\s+android:name="([^"]+)"""").findAll(body.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")).map { it.groupValues[1] }.toSet() }

    @Test fun noQueryAllPackagesPermission() {
        val code = manifest.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        assertFalse("QUERY_ALL_PACKAGES is restricted by Google Play and not allowed (D-OWNER-MDM-02)", "QUERY_ALL_PACKAGES" in code)
    }

    @Test fun theResourceListEqualsTheFakeGpsPartOfTheQueries() {
        val fake = Regex("""<item>([^<]+)</item>""").findAll(resource).map { it.groupValues[1].trim() }.toList()
        assertTrue(fake.size >= 10 && fake.size == fake.toSet().size)
        val rootAndHook = (RootHints.ROOT_APPS + RootHints.HOOK_APPS + RootHints.CLONE_APPS).toSet()
        assertEquals("the manifest <queries> must be exactly the fake-GPS list plus RootHints' lists", fake.toSet() + rootAndHook, queries())
    }

    @Test fun everyEntryIsAPlainPackageName() {
        assertTrue(queries().all { Regex("^[a-z][a-z0-9_]*(\\.[A-Za-z0-9_]+)+$").matches(it) })
    }
}
