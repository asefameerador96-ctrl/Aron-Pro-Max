package com.aktcl.aron.amo

import android.app.Application
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import org.xmlpull.v1.XmlPullParser
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import com.aktcl.aron.core.ui.R as CoreR

/**
 * F-SYS-033 and D-BRAND-01 for ARON AMO: the launcher icon is the shared Aron logo (core-ui) as an adaptive icon with a
 * monochrome layer for Android 13 themed icons, on this app's own plate colour, and ARON SR, AMO and TSO stay told apart
 * side by side by that colour alone. The logo layers are proved by identity: the icon XML is read through Resources and its
 * foreground and monochrome entries must be core-ui's own `aron_launcher_foreground` and `aron_launcher_monochrome` (and the
 * app must ship no copy of them). The three expected colours are repeated in each app's test on purpose, so changing one is a
 * deliberate edit in all three; the three apps' `ic_launcher_background.xml` sources are all read here, so a plate changed
 * or copied in any app fails that app's own test, and every app's test that runs (the siblings' files are read but are not
 * inputs of this test task, so Gradle may skip a sibling's test as up to date). The label and the package id are checked by
 * AppCoexistenceTest; the label is asserted here too because the icon change must not touch it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AppIconTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private val srPlate = 0xFF0B5D3B.toInt()
    private val amoPlate = 0xFF1F4E8C.toInt()
    private val tsoPlate = 0xFF8C1D18.toInt()
    private val ownPlate = amoPlate
    private val androidNs = "http://schemas.android.com/apk/res/android"

    /** A file of one of the three app modules: Gradle runs unit tests in the module directory, an IDE may run them from the repo root. */
    private fun appFile(app: String, path: String): File =
        listOf(File("../app-$app/$path"), File("android/app-$app/$path")).first { it.isFile }

    private fun adaptive(drawable: Drawable?, what: String): AdaptiveIconDrawable {
        assertTrue("$what is ${drawable?.javaClass?.name}, not an adaptive icon", drawable is AdaptiveIconDrawable)
        return drawable as AdaptiveIconDrawable
    }

    /** The `background`, `foreground` and `monochrome` layers of the adaptive-icon resource [iconRes], as the resource ids their `android:drawable` names. */
    private fun layerIds(iconRes: Int): Map<String, Int> {
        val layers = HashMap<String, Int>()
        val parser = context.resources.getXml(iconRes)
        try {
            var event = parser.eventType
            var root: String? = null
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    if (root == null) root = parser.name
                    if (parser.name in listOf("background", "foreground", "monochrome")) layers[parser.name] = parser.getAttributeResourceValue(androidNs, "drawable", 0)
                }
                event = parser.next()
            }
            assertEquals("root element of the icon", "adaptive-icon", root)
        } finally {
            parser.close()
        }
        return layers
    }

    private fun assertLogoOnThisAppsPlate(iconRes: Int, what: String) {
        // the identity of the layers: core-ui's logo files, not a blank, not an app copy, and the monochrome layer is its own file
        val layers = layerIds(iconRes)
        assertEquals("$what foreground is core-ui's aron_launcher_foreground", CoreR.mipmap.aron_launcher_foreground, layers["foreground"])
        assertEquals("$what monochrome layer is core-ui's aron_launcher_monochrome", CoreR.mipmap.aron_launcher_monochrome, layers["monochrome"])
        assertEquals("$what plate is this app's ic_launcher_background", R.color.ic_launcher_background, layers["background"])
        assertEquals("aron_launcher_foreground", context.resources.getResourceEntryName(layers.getValue("foreground")))
        assertEquals("aron_launcher_monochrome", context.resources.getResourceEntryName(layers.getValue("monochrome")))
        // two different pictures, not one file under two names (a copy of the full-colour art would theme as a solid blob)
        val foreground = context.resources.openRawResource(layers.getValue("foreground")).use { it.readBytes() }
        val monochrome = context.resources.openRawResource(layers.getValue("monochrome")).use { it.readBytes() }
        for ((layer, bytes) in listOf("foreground" to foreground, "monochrome" to monochrome)) {
            assertTrue("$what $layer layer is a WebP file", bytes.size > 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP")
        }
        assertFalse("$what foreground and monochrome layers are two different pictures", foreground.contentEquals(monochrome))
        // and the system builds the icon from them, on this app's plate
        val icon = adaptive(context.getDrawable(iconRes), what)
        assertNotNull("$what has a foreground", icon.foreground)
        assertNotNull("$what has a monochrome layer (Android 13 themed icon)", icon.monochrome)
        val plate = icon.background
        assertTrue("$what background is ${plate?.javaClass?.name}, not a plain colour", plate is ColorDrawable)
        assertEquals("$what plate colour", ownPlate, (plate as ColorDrawable).color)
    }

    /** The attribute of the `application` element in the source manifest, read as XML (a comment that quotes the text does not count). */
    private fun applicationAttribute(name: String): String {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(appFile("amo", "src/main/AndroidManifest.xml"))
        return (doc.getElementsByTagName("application").item(0) as Element).getAttributeNS(androidNs, name)
    }

    /** The plate colour a module declares in its `values/ic_launcher_background.xml`. */
    private fun plateOf(app: String): Int {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(appFile(app, "src/main/res/values/ic_launcher_background.xml"))
        val colors = doc.getElementsByTagName("color")
        val color = (0 until colors.length).map { colors.item(it) as Element }.single { it.getAttribute("name") == "ic_launcher_background" }
        val hex = color.textContent.trim()
        assertTrue("$app plate is #RRGGBB, not $hex", Regex("#[0-9A-Fa-f]{6}").matches(hex))
        return (0xFF000000L or hex.removePrefix("#").toLong(16)).toInt()
    }

    @Test
    fun theLauncherIconIsTheSharedLogoOnThisAppsPlate() {
        // the icon the merged manifest declares (Robolectric's PackageManager shadow does not resolve application icons)
        assertEquals(R.mipmap.ic_launcher, context.applicationInfo.icon)
        assertLogoOnThisAppsPlate(context.applicationInfo.icon, "the application icon")
    }

    @Test
    fun theManifestPointsIconAndRoundIconAtTheSameAdaptiveIcon() {
        val resources = context.resources
        val icon = context.applicationInfo.icon
        assertEquals("mipmap", resources.getResourceTypeName(icon))
        assertEquals("ic_launcher", resources.getResourceEntryName(icon))
        // ApplicationInfo has no public roundIcon, so the round entry is read from the source manifest (as XML) and by loading it
        assertEquals("@mipmap/ic_launcher", applicationAttribute("icon"))
        assertEquals("@mipmap/ic_launcher_round", applicationAttribute("roundIcon"))
        assertLogoOnThisAppsPlate(R.mipmap.ic_launcher_round, "the round icon")
    }

    @Test
    fun thePlateColourIsThisAppsOwnAndTheThreeAppsDiffer() {
        assertEquals(ownPlate, context.getColor(R.color.ic_launcher_background))
        // all three apps' sources are read against the three distinct pinned colours, so a plate changed or copied in any app fails
        val plates = mapOf("sr" to plateOf("sr"), "amo" to plateOf("amo"), "tso" to plateOf("tso"))
        assertEquals("the plate colours F-SYS-033 pins", mapOf("sr" to srPlate, "amo" to amoPlate, "tso" to tsoPlate), plates)
    }

    @Test
    fun theAppShipsNoCopyOfTheLogoLayersThePlaceholderVectorIsGoneAndTheLabelIsUnchanged() {
        val ownRes = appFile("amo", "src/main/AndroidManifest.xml").parentFile.resolve("res")
        val copies = ownRes.walkTopDown().filter { it.isFile && it.name.startsWith("aron_launcher_") }.map { it.name }.toList()
        assertTrue("the logo layers live in core-ui only, found $copies", copies.isEmpty())
        assertEquals(0, context.resources.getIdentifier("ic_launcher", "drawable", context.packageName))
        assertEquals("ARON AMO", context.packageManager.getApplicationLabel(context.applicationInfo).toString())
    }
}
