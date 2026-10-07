package com.aktcl.aron.db

import java.sql.Connection

/**
 * Renders docs/data-dictionary.md from the PostgreSQL catalogue of a migrated database (docs/31 s3 item 8): every app
 * and dw table and view with its description, owner, capture class, retention and PII class (from the table comment's
 * metadata line), its columns with type, nullability, PII class and description, and its keys and references.
 * Partitions are folded into their parent.
 */
object DataDictionary {
    val META = Regex("^owner: (\\S+) \\| capture: (OFFLINE|ONLINE|SERVER|REFERENCE) \\| retention: (\\w+) \\| pii: (none|personal|sensitive|secret)$")
    private val PII = Regex("^\\[pii:(personal|sensitive|secret)] ")

    data class Column(val name: String, val type: String, val notNull: Boolean, val comment: String?)
    data class Relation(val schema: String, val name: String, val kind: String, val comment: String?, val columns: List<Column>,
                        val keys: List<String>, val references: List<String>)

    fun relations(c: Connection): List<Relation> {
        val rels = c.prepareStatement(
            """
            SELECT n.nspname, k.relname, k.relkind, obj_description(k.oid, 'pg_class'), k.oid
              FROM pg_class k JOIN pg_namespace n ON n.oid = k.relnamespace
             WHERE n.nspname IN ('app','dw') AND k.relkind IN ('r','p','v') AND NOT k.relispartition
             ORDER BY 1, 2
            """.trimIndent(),
        ).use { ps -> ps.executeQuery().use { rs -> buildList { while (rs.next()) add(listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5))) } } }
        return rels.map { r ->
            val oid = r[4] as Long
            val cols = c.prepareStatement(
                "SELECT a.attname, format_type(a.atttypid, a.atttypmod), a.attnotnull, col_description(a.attrelid, a.attnum) " +
                    "FROM pg_attribute a WHERE a.attrelid = ? AND a.attnum > 0 AND NOT a.attisdropped ORDER BY a.attnum",
            ).use { ps ->
                ps.setLong(1, oid)
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(Column(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(4))) } }
            }
            val cons = c.prepareStatement(
                "SELECT contype, pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = ? AND contype IN ('p','u','f') ORDER BY contype DESC, conname",
            ).use { ps ->
                ps.setLong(1, oid)
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) } }
            }
            Relation(r[0] as String, r[1] as String, if (r[2] == "v") "view" else if (r[2] == "p") "partitioned table" else "table",
                r[3] as String?, cols, cons.filter { it.first != "f" }.map { it.second }, cons.filter { it.first == "f" }.map { it.second })
        }
    }

    /** Problems that make the dictionary incomplete: missing comments, a missing or malformed metadata line. */
    fun problems(rels: List<Relation>): List<String> = buildList {
        rels.forEach { r ->
            val lines = r.comment?.lines().orEmpty()
            if (lines.size < 2 || lines.first().isBlank()) add("${r.schema}.${r.name}: no description")
            else if (!META.matches(lines.last())) add("${r.schema}.${r.name}: bad metadata line '${lines.last()}'")
            r.columns.filter { it.comment.isNullOrBlank() }.forEach { add("${r.schema}.${r.name}.${it.name}: no comment") }
            // The table's PII class is the highest class of its columns.
            val rank = listOf("none", "personal", "sensitive", "secret")
            val declared = lines.lastOrNull()?.let { META.matchEntire(it)?.groupValues?.get(4) }
            val highest = r.columns.mapNotNull { PII.find(it.comment.orEmpty())?.groupValues?.get(1) }.maxByOrNull(rank::indexOf) ?: "none"
            if (declared != null && declared != highest) add("${r.schema}.${r.name}: pii $declared but its columns say $highest")
        }
    }

    private fun cell(s: String) = s.replace("|", "\\|").replace("\n", " ")

    fun render(rels: List<Relation>): String = buildString {
        appendLine("# Data dictionary")
        appendLine()
        appendLine("Generated from the PostgreSQL catalogue by `tools/data-dictionary` (the `COMMENT ON` of every migration). Do not edit")
        appendLine("by hand: change the comment in a new migration and regenerate. `DataDictionaryTest` fails when a table, view or")
        appendLine("column has no comment or when this file is stale.")
        appendLine()
        appendLine("Conventions: money is integer milli-taka (`*_mtk`, 1 Tk = 1000 mtk); quantities are integers in the SKU base unit;")
        appendLine("instants are UTC `timestamptz`; `business_date` is the Asia/Dhaka date. **Capture**: OFFLINE (device record, synced),")
        appendLine("ONLINE (web or API write), SERVER (derived by the server or worker), REFERENCE (seeded by migration). **Retention**:")
        appendLine("the class of docs/16 s13.1. **PII**: none, personal, sensitive, secret. Other products read only the `dw` views")
        appendLine("(`v_*`, stable contract, additive changes only) and the domain-event outbox, never `app` tables (docs/31 s3).")
        appendLine()
        appendLine("| Schema | Relations | Columns |")
        appendLine("|---|---|---|")
        rels.groupBy { it.schema }.forEach { (s, rs) -> appendLine("| `$s` | ${rs.size} | ${rs.sumOf { it.columns.size }} |") }
        appendLine()
        appendLine("## Index")
        appendLine()
        appendLine("| Relation | Kind | Owner | Capture | Retention | PII | Description |")
        appendLine("|---|---|---|---|---|---|---|")
        rels.forEach { r ->
            val lines = r.comment.orEmpty().lines()
            val m = META.matchEntire(lines.last())
            val g = m?.groupValues ?: listOf("", "?", "?", "?", "?")
            appendLine("| [`${r.schema}.${r.name}`](#${r.schema}${r.name.replace("_", "_")}) | ${r.kind} | ${g[1]} | ${g[2]} | ${g[3]} | ${g[4]} | ${cell(lines.dropLast(1).joinToString(" "))} |")
        }
        rels.forEach { r ->
            val lines = r.comment.orEmpty().lines()
            appendLine()
            appendLine("## ${r.schema}.${r.name}")
            appendLine()
            appendLine(lines.dropLast(1).joinToString(" "))
            appendLine()
            appendLine("`${lines.last()}` · ${r.kind}")
            appendLine()
            appendLine("| Column | Type | Null | PII | Description |")
            appendLine("|---|---|---|---|---|")
            r.columns.forEach { col ->
                val text = col.comment.orEmpty()
                val pii = PII.find(text)?.groupValues?.get(1) ?: ""
                appendLine("| `${col.name}` | ${cell(col.type)} | ${if (col.notNull) "not null" else "null"} | $pii | ${cell(text.replace(PII, ""))} |")
            }
            if (r.keys.isNotEmpty()) { appendLine(); appendLine("Keys: " + r.keys.joinToString("; ") { "`${cell(it)}`" }) }
            if (r.references.isNotEmpty()) { appendLine(); appendLine("References: " + r.references.joinToString("; ") { "`${cell(it)}`" }) }
        }
    }
}
