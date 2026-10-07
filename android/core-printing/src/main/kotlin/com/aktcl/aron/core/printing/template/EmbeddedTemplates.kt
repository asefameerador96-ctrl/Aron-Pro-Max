package com.aktcl.aron.core.printing.template

/**
 * The templates built into the app (version 1). They print when the bundle carries no template for a kind or
 * its template fails validation. Layout follows the on-screen memo (docs/ui-reference/sr/memo.md: items table
 * SKU / quantity / price, discount table SKU / quantity / value, total discount, total QC, grand total) until
 * the sponsor's photographed printout is compared (N-020).
 */
object EmbeddedTemplates {
    const val VERSION = 1

    /** Contract `PrintTemplate.kind` values, mapped to the embedded template that prints them. */
    fun forKind(kind: String): String = when (kind) {
        "cash_memo", "credit_memo", "offer_memo", "drp_memo", "zero_memo", "edited_memo" -> MEMO
        "stock_slip" -> STOCK_SLIP
        "day_summary" -> DAY_SUMMARY
        "void_slip" -> VOID_SLIP
        "due_receipt" -> DUE_RECEIPT
        else -> throw IllegalArgumentException("no template kind $kind")
    }

    val KINDS = listOf("cash_memo", "credit_memo", "offer_memo", "drp_memo", "zero_memo", "edited_memo", "stock_slip", "day_summary", "void_slip", "due_receipt")

    private const val HEADER_BLOCKS = """
      { "type": "text", "value": "{@print_company}", "align": "center", "bold": true, "size": 28 }"""

    val MEMO = """
    { "schema": 1, "size": 22, "digits": "latin", "blocks": [
      $HEADER_BLOCKS,
      { "type": "if", "when": "kind_cash", "blocks": [ { "type": "text", "value": "{@print_cash_memo}", "align": "center", "bold": true } ] },
      { "type": "if", "when": "kind_credit", "blocks": [ { "type": "text", "value": "{@print_credit_memo}", "align": "center", "bold": true } ] },
      { "type": "if", "when": "kind_offer", "blocks": [ { "type": "text", "value": "{@print_offer_memo}", "align": "center", "bold": true } ] },
      { "type": "if", "when": "kind_drp", "blocks": [ { "type": "text", "value": "{@print_drp_memo}", "align": "center", "bold": true } ] },
      { "type": "if", "when": "kind_zero", "blocks": [ { "type": "text", "value": "{@print_zero_memo}", "align": "center", "bold": true } ] },
      { "type": "if", "when": "kind_edited", "blocks": [ { "type": "text", "value": "{@print_edited_memo}", "align": "center", "bold": true } ] },
      { "type": "if", "when": "duplicate", "blocks": [
        { "type": "text", "value": "*** {@print_duplicate} ***", "align": "center", "bold": true, "size": 24 },
        { "type": "text", "value": "{@print_reprint}: {reprint_no}", "align": "center" } ] },
      { "type": "if", "when": "supersedes", "blocks": [
        { "type": "pair", "left": "{@print_supersedes}:", "right": "{supersedes_memo_no}" } ] },
      { "type": "rule", "style": "dashed" },
      { "type": "pair", "left": "{@print_memo_no}:", "right": "{memo_no}" },
      { "type": "pair", "left": "{@print_date}: {date}", "right": "{@print_time}: {time}" },
      { "type": "text", "value": "{@print_outlet}: {outlet}" },
      { "type": "text", "value": "{@print_sr}: {sr}" },
      { "type": "text", "value": "{@print_route}: {route}" },
      { "type": "rule" },
      { "type": "table", "rows": "lines", "header_bold": true, "columns": [
        { "header": "{@print_sku}", "value": "{sku}", "width": 48, "align": "left" },
        { "header": "{@print_qty}", "value": "{qty}", "width": 22, "align": "right" },
        { "header": "{@print_price}", "value": "{value}", "width": 30, "align": "right" } ] },
      { "type": "rule", "style": "dashed" },
      { "type": "table", "rows": "line_total", "columns": [
        { "header": "", "value": "{@print_total}", "width": 48, "align": "left" },
        { "header": "", "value": "{qty}", "width": 22, "align": "right" },
        { "header": "", "value": "{value}", "width": 30, "align": "right" } ] },
      { "type": "if", "when": "has_discounts", "blocks": [
        { "type": "space", "dots": 6 },
        { "type": "text", "value": "{@print_discount}", "bold": true },
        { "type": "table", "rows": "discounts", "header_bold": true, "columns": [
          { "header": "{@print_sku}", "value": "{sku}", "width": 48, "align": "left" },
          { "header": "{@print_qty}", "value": "{qty}", "width": 22, "align": "right" },
          { "header": "{@print_value}", "value": "{value}", "width": 30, "align": "right" } ] },
        { "type": "rule", "style": "dashed" },
        { "type": "table", "rows": "discount_total", "columns": [
          { "header": "", "value": "{@print_total}", "width": 48, "align": "left" },
          { "header": "", "value": "{qty}", "width": 22, "align": "right" },
          { "header": "", "value": "{value}", "width": 30, "align": "right" } ] } ] },
      { "type": "rule" },
      { "type": "pair", "left": "{@print_total_discount}", "right": "- {total_discount}" },
      { "type": "pair", "left": "{@print_total_qc}", "right": "- {total_qc}" },
      { "type": "if", "when": "has_rounding", "blocks": [ { "type": "pair", "left": "{@print_rounding}", "right": "{rounding}" } ] },
      { "type": "pair", "left": "{@print_grand_total}", "right": "{grand_total}", "bold": true, "size": 26 },
      { "type": "if", "when": "credit", "blocks": [
        { "type": "pair", "left": "{@print_paid}", "right": "{paid}" },
        { "type": "pair", "left": "{@print_due}", "right": "{due}", "bold": true } ] },
      { "type": "rule", "style": "dashed" },
      { "type": "text", "value": "{@print_thanks}", "align": "center" }
    ] }
    """.trimIndent()

    val STOCK_SLIP = """
    { "schema": 1, "size": 22, "digits": "latin", "blocks": [
      $HEADER_BLOCKS,
      { "type": "text", "value": "{@print_stock_slip}", "align": "center", "bold": true },
      { "type": "if", "when": "duplicate", "blocks": [
        { "type": "text", "value": "*** {@print_duplicate} ***", "align": "center", "bold": true } ] },
      { "type": "rule", "style": "dashed" },
      { "type": "pair", "left": "{@print_date}: {date}", "right": "{@print_time}: {time}" },
      { "type": "text", "value": "{@print_sr}: {sr}" },
      { "type": "text", "value": "{@print_route}: {route}" },
      { "type": "text", "value": "{@print_distributor}: {distributor}" },
      { "type": "rule" },
      { "type": "table", "rows": "lines", "header_bold": true, "columns": [
        { "header": "{@print_sku}", "value": "{sku}", "width": 64, "align": "left" },
        { "header": "{@print_qty}", "value": "{qty}", "width": 36, "align": "right" } ] },
      { "type": "rule" },
      { "type": "space", "dots": 40 },
      { "type": "pair", "left": "{@print_signature}", "right": "{@print_signature}" }
    ] }
    """.trimIndent()

    val DAY_SUMMARY = """
    { "schema": 1, "size": 20, "digits": "latin", "blocks": [
      $HEADER_BLOCKS,
      { "type": "text", "value": "{@print_day_summary}", "align": "center", "bold": true },
      { "type": "rule", "style": "dashed" },
      { "type": "pair", "left": "{@print_date}: {date}", "right": "{@print_time}: {time}" },
      { "type": "text", "value": "{@print_sr}: {sr}" },
      { "type": "text", "value": "{@print_route}: {route}" },
      { "type": "rule" },
      { "type": "table", "rows": "lines", "header_bold": true, "columns": [
        { "header": "{@print_sku}", "value": "{sku}", "width": 34, "align": "left" },
        { "header": "{@print_memo_count}", "value": "{memos}", "width": 16, "align": "right" },
        { "header": "{@print_qty}", "value": "{qty}", "width": 22, "align": "right" },
        { "header": "{@print_value}", "value": "{value}", "width": 28, "align": "right" } ] },
      { "type": "rule" },
      { "type": "pair", "left": "{@print_total}", "right": "{gross}" },
      { "type": "pair", "left": "{@print_total_discount}", "right": "- {total_discount}" },
      { "type": "pair", "left": "{@print_total_qc}", "right": "- {total_qc}" },
      { "type": "pair", "left": "{@print_grand_total}", "right": "{grand_total}", "bold": true },
      { "type": "pair", "left": "{@print_due}", "right": "{due}" },
      { "type": "pair", "left": "{@print_memo_count}", "right": "{memo_count}" }
    ] }
    """.trimIndent()

    val VOID_SLIP = """
    { "schema": 1, "size": 22, "digits": "latin", "blocks": [
      $HEADER_BLOCKS,
      { "type": "text", "value": "{@print_void_slip}", "align": "center", "bold": true },
      { "type": "rule", "style": "dashed" },
      { "type": "pair", "left": "{@print_voided_memo}:", "right": "{memo_no}" },
      { "type": "pair", "left": "{@print_date}: {date}", "right": "{@print_time}: {time}" },
      { "type": "text", "value": "{@print_outlet}: {outlet}" },
      { "type": "text", "value": "{@print_sr}: {sr}" },
      { "type": "text", "value": "{@print_reason}: {reason}" },
      { "type": "rule" },
      { "type": "pair", "left": "{@print_amount}", "right": "{amount}", "bold": true },
      { "type": "space", "dots": 40 },
      { "type": "pair", "left": "{@print_signature}", "right": "{@print_signature}" }
    ] }
    """.trimIndent()

    val DUE_RECEIPT = """
    { "schema": 1, "size": 22, "digits": "latin", "blocks": [
      $HEADER_BLOCKS,
      { "type": "text", "value": "{@print_due_receipt}", "align": "center", "bold": true },
      { "type": "if", "when": "duplicate", "blocks": [
        { "type": "text", "value": "*** {@print_duplicate} ***", "align": "center", "bold": true } ] },
      { "type": "rule", "style": "dashed" },
      { "type": "pair", "left": "{@print_memo_no}:", "right": "{memo_no}" },
      { "type": "pair", "left": "{@print_date}: {date}", "right": "{@print_time}: {time}" },
      { "type": "text", "value": "{@print_outlet}: {outlet}" },
      { "type": "text", "value": "{@print_sr}: {sr}" },
      { "type": "rule" },
      { "type": "pair", "left": "{@print_due_before}", "right": "{due_before}" },
      { "type": "pair", "left": "{@print_collected}", "right": "{collected}", "bold": true },
      { "type": "pair", "left": "{@print_due_after}", "right": "{due_after}" },
      { "type": "space", "dots": 40 },
      { "type": "pair", "left": "{@print_signature}", "right": "{@print_signature}" }
    ] }
    """.trimIndent()
}
