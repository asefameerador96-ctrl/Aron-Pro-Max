package com.aktcl.aron.core.printing.render

import com.aktcl.aron.core.printing.BundleTemplate
import com.aktcl.aron.core.printing.Fixtures
import com.aktcl.aron.core.printing.TemplateSet
import com.aktcl.aron.core.printing.template.EmbeddedTemplates
import com.aktcl.aron.core.printing.template.InvalidTemplateException
import com.aktcl.aron.core.printing.template.PrintTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** N-018: templates are versioned data; a bad bundle template is refused and the embedded one prints. */
class TemplateTest {
    private fun refused(json: String) {
        try { PrintTemplate.parse(json); fail("accepted: $json") } catch (_: InvalidTemplateException) {}
    }

    @Test fun everyEmbeddedTemplateIsValidAndUsesKnownLabels() {
        for (kind in EmbeddedTemplates.KINDS) {
            val t = PrintTemplate.parse(EmbeddedTemplates.forKind(kind))
            val missingBn = t.labelKeys().filter { Fixtures.bn[it] == null }
            val missingEn = t.labelKeys().filter { Fixtures.en[it] == null }
            assertTrue("$kind bn $missingBn en $missingEn", missingBn.isEmpty() && missingEn.isEmpty())
        }
    }

    @Test fun schemaViolationsAreRefused() {
        refused("not json")
        refused("[]")
        refused("""{"schema":2,"blocks":[]}""")
        refused("""{"schema":1,"blocks":[{"type":"marquee"}]}""")
        refused("""{"schema":1,"size":99,"blocks":[]}""")
        refused("""{"schema":1,"digits":"roman","blocks":[]}""")
        refused("""{"schema":1,"blocks":[{"type":"table","rows":"lines","columns":[{"value":"{sku}","width":60},{"value":"{qty}","width":30}]}]}""")
        refused("""{"schema":1,"blocks":[{"type":"if","blocks":[]}]}""")
        refused("""{"schema":1,"blocks":[{"type":"if","when":"a","unless":"b","blocks":[]}]}""")
        refused("""{"schema":1,"blocks":[{"type":"text"}]}""")
        val deep = (1..6).fold("""{"type":"rule"}""") { acc, _ -> """{"type":"if","when":"x","blocks":[$acc]}""" }
        refused("""{"schema":1,"blocks":[$deep]}""")
        refused("""{"schema":1,"blocks":[${(1..201).joinToString(",") { """{"type":"rule"}""" }}]}""")
        refused("""{"schema":1,"blocks":[]}""" + " ".repeat(20_001))
    }

    @Test fun newestValidBundleVersionWinsAndIsReported() {
        val v2 = """{"schema":1,"blocks":[{"type":"text","value":"{@print_cash_memo} v2"}]}"""
        val v3 = """{"schema":1,"blocks":[{"type":"text","value":"{@print_cash_memo} v3"}]}"""
        val set = TemplateSet(listOf(BundleTemplate("cash_memo", 2, 32, v2), BundleTemplate("cash_memo", 3, 32, v3)), Fixtures.bnLabels)
        val r = set.get("cash_memo")
        assertTrue(r.fromBundle)
        assertEquals(3, r.version)
        assertEquals(EmbeddedTemplates.VERSION, set.get("credit_memo").version)
        val printed = Fixtures.renderer(bundle = listOf(BundleTemplate("cash_memo", 3, 32, v3))).memo(Fixtures.seededSale.copy(kind = "cash_memo"))
        assertEquals(3, printed.templateVersion)
    }

    @Test fun invalidBundleTemplateFallsBackToEmbedded() {
        val badLabel = """{"schema":1,"blocks":[{"type":"text","value":"{@print_nonexistent}"}]}"""
        val set = TemplateSet(listOf(BundleTemplate("cash_memo", 9, 32, badLabel), BundleTemplate("stock_slip", 4, 32, "{oops")), Fixtures.bnLabels)
        for (k in listOf("cash_memo", "stock_slip")) {
            val r = set.get(k)
            assertFalse(r.fromBundle)
            assertEquals(EmbeddedTemplates.VERSION, r.version)
            assertNotNull(r.rejectedReason)
        }
        // The fallback prints the same paper as no bundle at all.
        val fallback = Fixtures.renderer(bundle = listOf(BundleTemplate("cash_memo", 9, 32, badLabel))).memo(Fixtures.seededSale.copy(kind = "cash_memo"))
        assertEquals(Fixtures.renderer().memo(Fixtures.seededSale.copy(kind = "cash_memo")).bitmap, fallback.bitmap)
    }

    @Test fun fortyTwoColumnTemplatesDefaultToTheSmallerSize() {
        val j = """{"schema":1,"blocks":[{"type":"text","value":"x"}]}"""
        val set = TemplateSet(listOf(BundleTemplate("day_summary", 2, 42, j)), Fixtures.bnLabels)
        assertEquals(18, set.get("day_summary").template.sizePx)
        assertNotEquals(18, TemplateSet(listOf(BundleTemplate("day_summary", 2, 32, j)), Fixtures.bnLabels).get("day_summary").template.sizePx)
    }
}
