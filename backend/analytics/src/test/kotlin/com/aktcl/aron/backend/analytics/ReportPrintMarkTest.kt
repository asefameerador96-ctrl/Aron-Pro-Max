package com.aktcl.aron.backend.analytics

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Aron mark in the header of the printed/PDF report (D-BRAND-01, CMP-4). No database: the print renderer is a pure function of the
 * definition and the rows. The web BFF serves this page under `img-src data:`, so the mark has to be embedded in the HTML itself.
 */
class ReportPrintMarkTest {
    private val def = ReportDefinition(
        "route-memo", "Route-wise Memo", "রুট ভিত্তিক মেমো", "sales", "route", listOf("json", "xlsx", "pdf", "print"),
        listOf(ReportColumn("route_code", "Route code", type = "string"), ReportColumn("gross_mtk", "Gross", type = "mtk")), listOf("period"),
    )
    private val rows: List<Map<String, JsonElement>> = listOf(
        mapOf("route_code" to JsonPrimitive("R1"), "gross_mtk" to JsonPrimitive(34_000)),
        mapOf("route_code" to JsonPrimitive("=HYPERLINK(\"http://x\")"), "gross_mtk" to JsonPrimitive(5_000)),
    )

    private fun render(mark: String?) = ReportOutputs.printHtml(def, def.columns, rows.iterator(), "Exported by u10", mark)

    private val markSrc = Regex("<img class=\"mark\" src=\"(data:image/png;base64,[A-Za-z0-9+/=]+)\" alt=\"\" width=\"28\" height=\"28\">")

    @Test
    fun headerCarriesTheBundledMarkAsADataUriBesideTheTitleWithEmptyAlt() {
        val (html, n) = ReportOutputs.printHtml(def, def.columns, rows.iterator(), "Exported by u10")   // the default mark: read from the classpath
        assertEquals(2, n)
        val m = assertNotNull(markSrc.find(html), "the header must hold the data: URI image with empty alt and a fixed size")
        // Inside the h2, left of the title; the table follows, so the A4 table layout is untouched.
        assertTrue(html.contains("<h2>" + m.value + "Route-wise Memo / রুট ভিত্তিক মেমো</h2><table>"), "mark sits in the heading, directly before the title text")
        assertEquals(1, Regex("<img").findAll(html).count(), "one image, not one per row")
        assertFalse(Regex("(?:src|href)=\"https?:", RegexOption.IGNORE_CASE).containsMatchIn(html), "no remote image: the CSP is img-src data: only")
        // The embedded bytes are the generated 64 px transparent PNG.
        val png = Base64.getDecoder().decode(m.groupValues[1].removePrefix("data:image/png;base64,"))
        val img = assertNotNull(ImageIO.read(ByteArrayInputStream(png)))
        assertEquals(64 to 64, img.width to img.height)
        assertTrue(img.colorModel.hasAlpha(), "transparent, so it sits on white paper without a plate")
        assertTrue(png.size < 8 * 1024, "the data URI stays small: ${png.size} bytes")
        // The rest of the report is unchanged by the mark.
        assertTrue(html.contains("<table><thead><tr><th>Route code</th><th>Gross</th></tr></thead>") && html.contains("<td class=\"n\">34000</td>"))
        assertTrue(html.contains("=HYPERLINK(&quot;http://x&quot;)") && html.contains("Exported by u10 &middot; rows: 2"))
    }

    @Test
    fun theBundledMarkIsReadOnceAndShared() {
        val first = assertNotNull(ReportOutputs.reportMark, "brand/aron-mark-64.png must be on the classpath (backend/analytics/src/main/resources)")
        assertSame(first, ReportOutputs.reportMark)
        assertTrue(first.startsWith("data:image/png;base64,"))
    }

    @Test
    fun aMissingResourceStillRendersTheWholeReportWithoutTheMark() {
        assertNull(ReportOutputs.loadMarkDataUri("brand/no-such-mark.png"))
        val (html, n) = render(ReportOutputs.loadMarkDataUri("brand/no-such-mark.png"))
        assertEquals(2, n)
        assertFalse(html.contains("<img"), "no mark, no broken image")
        assertTrue(html.contains("<h2>Route-wise Memo / রুট ভিত্তিক মেমো</h2><table>"))
        assertTrue(html.contains("<td>R1</td><td class=\"n\">34000</td>") && html.endsWith("Exported by u10 &middot; rows: 2</p></body></html>"))
        assertEquals(2, render(null).second)
    }

    @Test
    fun anUnreadableOrWrongResourceIsTreatedAsMissingNeverAsAnError() {
        fun loaderOf(open: () -> InputStream?) = object : ClassLoader() {
            override fun getResourceAsStream(name: String): InputStream? = open()
        }
        assertNull(ReportOutputs.loadMarkDataUri(loader = null))
        assertNull(ReportOutputs.loadMarkDataUri(loader = loaderOf { throw IOException("disk gone") }), "an exception while reading")
        assertNull(ReportOutputs.loadMarkDataUri(loader = loaderOf { ByteArrayInputStream(ByteArray(0)) }), "empty file")
        assertNull(ReportOutputs.loadMarkDataUri(loader = loaderOf { ByteArrayInputStream("<html>not a png</html>".toByteArray()) }), "not a PNG")
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertNull(ReportOutputs.loadMarkDataUri(loader = loaderOf { ByteArrayInputStream(signature + ByteArray(40 * 1024)) }), "far bigger than the mark")
        assertNotNull(ReportOutputs.loadMarkDataUri(loader = loaderOf { ByteArrayInputStream(signature + ByteArray(16)) }), "a small PNG-signed stream is accepted")
    }

    @Test
    fun theMarkAttributeIsEscapedLikeEveryOtherValue() {
        val (html, _) = render("data:image/png;base64,AAAA\" onerror=\"alert(1)")
        assertFalse(html.contains("\" onerror=\""), "a hostile mark cannot break out of the src attribute")
        assertTrue(html.contains("src=\"data:image/png;base64,AAAA&quot; onerror=&quot;alert(1)\""))
    }

    @Test
    fun theXlsxExportIsNotBranded() {
        val bytes = ReportOutputs.xlsxBytes(def, def.columns, rows, "Exported by u10")
        val tmp = java.io.File.createTempFile("rep", ".xlsx").also { it.writeBytes(bytes); it.deleteOnExit() }   // the writer streams entries: read the central directory
        val names = ZipFile(tmp).use { z -> z.entries().asSequence().map { it.name }.toList() }
        assertTrue(names.isNotEmpty() && names.none { it.contains("media") || it.contains("drawing") }, "no picture part in the workbook: $names")
    }
}
