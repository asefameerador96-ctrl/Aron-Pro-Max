package com.aktcl.aron.core.printing.template

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * A print template (N-018): the layout of one paper document as data. Templates arrive in the bundle
 * (`templates[].template_json`, contract `PrintTemplate`) and are validated here against the embedded schema;
 * an invalid one is refused and the phone prints with its embedded default ([EmbeddedTemplates]).
 *
 * Schema 1, as JSON:
 * ```
 * { "schema": 1, "size": 22, "digits": "latin" | "bn", "blocks": [ block, ... ] }
 * block := { "type": "text",  "value": "...", "align": "left|center|right", "bold": bool, "size": n }
 *        | { "type": "pair",  "left": "...", "right": "...", "bold": bool, "size": n }
 *        | { "type": "rule",  "style": "solid|dashed" }
 *        | { "type": "space", "dots": n }
 *        | { "type": "table", "rows": "<table name>", "header_bold": bool, "size": n,
 *            "columns": [ { "header": "...", "value": "...", "width": percent, "align": "left|right|center" } ] }
 *        | { "type": "if", "when": "<flag>" | "unless": "<flag>", "blocks": [ ... ] }
 * ```
 * Text may hold `{field}` (a document field, or a column of the current table row) and `{@label}` (a localised
 * label from the app's string resources); nothing else is interpreted. Column widths add up to 100.
 */
class PrintTemplate(
    val schema: Int,
    val sizePx: Int,
    val digits: DigitStyle,
    val blocks: List<Block>,
) {
    enum class Align { LEFT, CENTER, RIGHT }

    sealed class Block {
        class Text(val value: String, val align: Align, val bold: Boolean, val sizePx: Int?) : Block()
        class Pair(val left: String, val right: String, val bold: Boolean, val sizePx: Int?) : Block()
        class Rule(val dashed: Boolean) : Block()
        class Space(val dots: Int) : Block()
        class Column(val header: String, val value: String, val widthPercent: Int, val align: Align)
        class Table(val rows: String, val columns: List<Column>, val headerBold: Boolean, val sizePx: Int?) : Block()
        class If(val flag: String, val negate: Boolean, val blocks: List<Block>) : Block()
    }

    /** Every `{@label}` key the template uses, for validation against the label set. */
    fun labelKeys(): Set<String> {
        val out = HashSet<String>()
        fun scan(s: String) = TOKEN.findAll(s).forEach { if (it.groupValues[1] == "@") out.add(it.groupValues[2]) }
        fun walk(bs: List<Block>) {
            for (b in bs) when (b) {
                is Block.Text -> scan(b.value)
                is Block.Pair -> { scan(b.left); scan(b.right) }
                is Block.Table -> b.columns.forEach { scan(it.header); scan(it.value) }
                is Block.If -> walk(b.blocks)
                else -> Unit
            }
        }
        walk(blocks)
        return out
    }

    companion object {
        const val SCHEMA = 1
        /** `{field}` or `{@label}`; the renderer fills exactly what this matches. */
        val TOKEN = Regex("""\{(@?)([^{}]{1,60})}""")
        private const val MAX_DEPTH = 4
        private const val MAX_BLOCKS = 200

        /** Parses and validates [json]; throws [InvalidTemplateException] with the first problem. */
        fun parse(json: String, defaultSize: Int = 22): PrintTemplate {
            if (json.length > 20_000) invalid("too_long")
            val root = try {
                Json.parseToJsonElement(json)
            } catch (e: Exception) {
                invalid("not_json")
            } as? JsonObject ?: invalid("root_not_object")
            val schema = root.int("schema") ?: invalid("schema_missing")
            if (schema != SCHEMA) invalid("schema_unsupported:$schema")
            val size = root.int("size") ?: defaultSize
            if (size !in 14..40) invalid("size_out_of_range:$size")
            val digits = when (val d = root.str("digits") ?: "latin") {
                "latin" -> DigitStyle.LATIN
                "bn" -> DigitStyle.BENGALI
                else -> invalid("digits_unknown:$d")
            }
            var count = 0
            fun blocks(arr: JsonElement?, depth: Int): List<Block> {
                if (depth > MAX_DEPTH) invalid("nesting_too_deep")
                val a = arr as? JsonArray ?: invalid("blocks_not_array")
                return a.map { el ->
                    if (++count > MAX_BLOCKS) invalid("too_many_blocks")
                    val o = el as? JsonObject ?: invalid("block_not_object")
                    val bsize = o.int("size")?.also { if (it !in 14..48) invalid("block_size_out_of_range:$it") }
                    when (val t = o.str("type")) {
                        "text" -> Block.Text(o.str("value") ?: invalid("text_value_missing"), align(o.str("align")), o.bool("bold"), bsize)
                        "pair" -> Block.Pair(o.str("left") ?: "", o.str("right") ?: "", o.bool("bold"), bsize)
                        "rule" -> Block.Rule(o.str("style") == "dashed")
                        "space" -> Block.Space((o.int("dots") ?: 8).also { if (it !in 1..200) invalid("space_dots_out_of_range:$it") })
                        "table" -> {
                            val cols = (o["columns"] as? JsonArray ?: invalid("table_columns_missing")).map { c ->
                                val co = c as? JsonObject ?: invalid("column_not_object")
                                Block.Column(co.str("header") ?: "", co.str("value") ?: invalid("column_value_missing"),
                                    co.int("width") ?: invalid("column_width_missing"), align(co.str("align")))
                            }
                            if (cols.isEmpty() || cols.size > 6) invalid("table_column_count")
                            if (cols.any { it.widthPercent !in 5..100 } || cols.sumOf { it.widthPercent } != 100) invalid("column_widths_not_100")
                            Block.Table(o.str("rows") ?: invalid("table_rows_missing"), cols, o.bool("header_bold"), bsize)
                        }
                        "if" -> {
                            val w = o.str("when")
                            val u = o.str("unless")
                            if ((w == null) == (u == null)) invalid("if_needs_when_or_unless")
                            Block.If(w ?: u!!, negate = u != null, blocks(o["blocks"], depth + 1))
                        }
                        else -> invalid("block_type_unknown:$t")
                    }
                }
            }
            return PrintTemplate(schema, size, digits, blocks(root["blocks"], 0))
        }

        private fun align(s: String?): Align = when (s) {
            null, "left" -> Align.LEFT
            "center" -> Align.CENTER
            "right" -> Align.RIGHT
            else -> invalid("align_unknown:$s")
        }

        private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
        private fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false

        private fun invalid(why: String): Nothing = throw InvalidTemplateException(why)
    }
}

class InvalidTemplateException(message: String) : IllegalArgumentException(message)

/** Digits on paper: Latin (as the current memo shows) or Bengali. Identifiers such as memo numbers never change. */
enum class DigitStyle { LATIN, BENGALI }
