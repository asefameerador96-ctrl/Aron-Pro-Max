package com.aktcl.aron.core.printing

import com.aktcl.aron.core.printing.doc.DaySummaryPrint
import com.aktcl.aron.core.printing.doc.DocumentBuilder
import com.aktcl.aron.core.printing.doc.DueReceiptPrint
import com.aktcl.aron.core.printing.doc.MemoPrint
import com.aktcl.aron.core.printing.doc.StockSlipPrint
import com.aktcl.aron.core.printing.doc.VoidSlipPrint
import com.aktcl.aron.core.printing.raster.MonoBitmap
import com.aktcl.aron.core.printing.render.PrintDocument
import com.aktcl.aron.core.printing.render.PrintFonts
import com.aktcl.aron.core.printing.render.TicketRenderer
import com.aktcl.aron.core.printing.template.DigitStyle
import com.aktcl.aron.core.printing.template.EmbeddedTemplates
import com.aktcl.aron.core.printing.template.InvalidTemplateException
import com.aktcl.aron.core.printing.template.PrintTemplate

/** A template as the bundle delivers it (contract `PrintTemplate`). */
data class BundleTemplate(val kind: String, val version: Int, val fontColumns: Int, val templateJson: String)

/** Localised paper labels (`print_*` string resources) in the print language. */
fun interface PrintLabels {
    /** The text of [key], or null when the app has no such label. */
    fun label(key: String): String?
}

/** The template chosen for a kind: from the bundle when valid, else the embedded default. */
class ResolvedTemplate(val kind: String, val template: PrintTemplate, val version: Int, val fromBundle: Boolean, val rejectedReason: String?)

/**
 * Chooses templates (N-018): the newest bundle version per kind that parses, passes the schema and uses only
 * labels the app has; otherwise the embedded template of that kind (version [EmbeddedTemplates.VERSION]).
 * A refused bundle template never stops printing; the reason is kept for the diagnostics log.
 */
class TemplateSet(bundle: List<BundleTemplate>, private val labels: PrintLabels) {
    private val resolved: Map<String, ResolvedTemplate>

    init {
        val m = HashMap<String, ResolvedTemplate>()
        for (kind in EmbeddedTemplates.KINDS) {
            val candidate = bundle.filter { it.kind == kind }.maxByOrNull { it.version }
            var reason: String? = null
            if (candidate != null) {
                try {
                    val t = PrintTemplate.parse(candidate.templateJson, defaultSize = if (candidate.fontColumns == 42) 18 else 22)
                    val missing = t.labelKeys().filter { labels.label(it) == null }
                    if (missing.isNotEmpty()) throw InvalidTemplateException("unknown labels $missing")
                    m[kind] = ResolvedTemplate(kind, t, candidate.version, fromBundle = true, rejectedReason = null)
                    continue
                } catch (e: InvalidTemplateException) {
                    reason = e.message
                }
            }
            m[kind] = ResolvedTemplate(kind, PrintTemplate.parse(EmbeddedTemplates.forKind(kind)), EmbeddedTemplates.VERSION, fromBundle = false, rejectedReason = reason)
        }
        resolved = m
    }

    fun get(kind: String): ResolvedTemplate = resolved[kind] ?: throw IllegalArgumentException("no template kind $kind")
}

/** A rendered paper document, ready for the printer, with the template version for `print_event`. */
class RenderedPaper(val bitmap: MonoBitmap, val templateVersion: Int)

/**
 * The one entry point the apps use to turn a memo, stock slip, day summary, void slip or due receipt into dots.
 * [digits] overrides the template's digit style when the app setting asks for it.
 */
class PaperRenderer(fonts: PrintFonts, private val templates: TemplateSet, private val labels: PrintLabels, widthDots: Int = 384) {
    private val renderer = TicketRenderer(fonts, widthDots)

    private fun render(kind: String, digits: DigitStyle?, build: (DocumentBuilder) -> PrintDocument): RenderedPaper {
        val r = templates.get(kind)
        val doc = build(DocumentBuilder(digits ?: r.template.digits))
        val bmp = renderer.render(r.template, doc) { key -> labels.label(key) ?: "" }
        return RenderedPaper(bmp, r.version)
    }

    fun memo(m: MemoPrint, digits: DigitStyle? = null) = render(m.kind, digits) { it.memo(m) }
    fun stockSlip(s: StockSlipPrint, digits: DigitStyle? = null) = render("stock_slip", digits) { it.stockSlip(s) }
    fun daySummary(d: DaySummaryPrint, digits: DigitStyle? = null) = render("day_summary", digits) { it.daySummary(d) }
    fun voidSlip(v: VoidSlipPrint, digits: DigitStyle? = null) = render("void_slip", digits) { it.voidSlip(v) }
    fun dueReceipt(r: DueReceiptPrint, digits: DigitStyle? = null) = render("due_receipt", digits) { it.dueReceipt(r) }
}
