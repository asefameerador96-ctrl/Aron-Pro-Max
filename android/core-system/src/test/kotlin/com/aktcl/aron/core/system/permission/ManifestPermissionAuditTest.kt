package com.aktcl.aron.core.system.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * F-SYS-023: no Aron app asks for the microphone, and location is in-use only. Checked on the MERGED manifests of the
 * three apps (every library's permissions included; the test task depends on processDebugMainManifest), and on every
 * module's source manifest. XML is parsed, so quoting style cannot hide a permission; a `tools:node="remove"` entry is a
 * removal, not a request.
 */
class ManifestPermissionAuditTest {
    private val root = File(System.getProperty("aron.androidRoot") ?: error("aron.androidRoot not set"))
    private val forbidden = setOf("android.permission.RECORD_AUDIO", "android.permission.ACCESS_BACKGROUND_LOCATION", "android.permission.CAPTURE_AUDIO_OUTPUT")

    // D-GEO-BG-01 (lead ruling 2026-10-07): background location is allowed for the optional breadcrumbs (N-035) in
    // exactly these two app manifests (source and merged), and nowhere else. The microphone rule has no exception.
    private val background = "android.permission.ACCESS_BACKGROUND_LOCATION"
    private val backgroundApps = setOf("app-sr", "app-amo")
    private val backgroundSourceManifests = setOf("app-sr/src/main/AndroidManifest.xml", "app-amo/src/main/AndroidManifest.xml")
    private fun forbiddenFor(app: String) = if (app in backgroundApps) forbidden - background else forbidden
    private fun forbiddenForSource(relPath: String) = if (relPath.replace('\\', '/') in backgroundSourceManifests) forbidden - background else forbidden

    private fun sourceManifests() = root.walkTopDown()
        .onEnter { it.name != "build" && it.name != "test" && it.name != "androidTest" && !it.name.startsWith(".") }
        .filter { it.name == "AndroidManifest.xml" }.toList()

    private fun mergedManifests(app: String): List<File> = File(root, "$app/build/intermediates").walkTopDown()
        .filter { it.name == "AndroidManifest.xml" && it.path.contains("merged_manifest") && it.path.contains("debug", ignoreCase = true) }.toList()

    @Test fun mergedAppManifestsRequestNoMicrophoneNorBackgroundLocation() {
        for (app in listOf("app-sr", "app-amo", "app-tso")) {
            val merged = mergedManifests(app)
            assertTrue("no merged manifest for $app: run :android:$app:processDebugMainManifest", merged.isNotEmpty())
            for (f in merged) {
                val requested = ManifestAudit.requestedPermissions(f.readText())
                assertTrue("$app requests ${requested intersect forbiddenFor(app)}", (requested intersect forbiddenFor(app)).isEmpty())
            }
        }
    }

    @Test fun mergedSrManifestHasTheFieldPermissions() {
        val requested = mergedManifests("app-sr").flatMap { ManifestAudit.requestedPermissions(it.readText()) }.toSet()
        assertTrue(requested.containsAll(setOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.CAMERA", "android.permission.BLUETOOTH_CONNECT")))
    }

    @Test fun noSourceManifestRequestsTheMicrophoneOrBackgroundLocation() {
        val manifests = sourceManifests()
        assertTrue("expected the app manifests under $root", manifests.any { it.path.contains("app-sr") })
        val hits = manifests.flatMap { f ->
            val rel = f.relativeTo(root).path
            (ManifestAudit.requestedPermissions(f.readText()) intersect forbiddenForSource(rel)).map { "$rel: $it" }
        }
        assertTrue(hits.joinToString("\n"), hits.isEmpty())
    }

    @Test fun backgroundLocationIsOnlyInTheSrAndAmoAppManifests() {
        val withBackground = sourceManifests().filter { background in ManifestAudit.requestedPermissions(it.readText()) }
            .map { it.relativeTo(root).path.replace('\\', '/') }.toSet()
        assertTrue("background location declared outside SR/AMO: ${withBackground - backgroundSourceManifests}", (withBackground - backgroundSourceManifests).isEmpty())
        val tsoMerged = mergedManifests("app-tso").flatMap { ManifestAudit.requestedPermissions(it.readText()) }.toSet()
        assertTrue("the TSO app must never get background location", background !in tsoMerged)
        // The exception is exactly one permission: the microphone stays forbidden in the SR and AMO apps too.
        assertTrue("android.permission.RECORD_AUDIO" in forbiddenFor("app-sr") && "android.permission.RECORD_AUDIO" in forbiddenForSource("app-sr/src/main/AndroidManifest.xml"))
        assertTrue(background in forbiddenFor("app-tso") && background in forbiddenForSource("core-geo/src/main/AndroidManifest.xml"))
    }

    @Test fun theParserSeesEveryQuotingStyleAndIgnoresRemovals() {
        val xml = """<manifest xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools">
            <uses-permission android:name='android.permission.RECORD_AUDIO'/>
            <uses-permission
                android:name = "android.permission.CAMERA" />
            <uses-permission-sdk-23 android:name="android.permission.ACCESS_BACKGROUND_LOCATION"/>
            <!-- <uses-permission android:name="android.permission.READ_CONTACTS"/> -->
            <uses-permission android:name="android.permission.READ_SMS" tools:node="remove"/>
        </manifest>"""
        assertEquals(
            setOf("android.permission.RECORD_AUDIO", "android.permission.CAMERA", "android.permission.ACCESS_BACKGROUND_LOCATION"),
            ManifestAudit.requestedPermissions(xml),
        )
    }
}

object ManifestAudit {
    private const val ANDROID = "http://schemas.android.com/apk/res/android"
    private const val TOOLS = "http://schemas.android.com/tools"

    fun requestedPermissions(xml: String): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(xml.byteInputStream())
        val out = mutableSetOf<String>()
        for (tag in listOf("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m")) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as org.w3c.dom.Element
                if (e.getAttributeNS(TOOLS, "node") == "remove") continue
                e.getAttributeNS(ANDROID, "name").takeIf { it.isNotEmpty() }?.let(out::add)
            }
        }
        return out
    }
}
