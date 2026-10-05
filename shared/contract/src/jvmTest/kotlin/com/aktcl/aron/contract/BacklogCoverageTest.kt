package com.aktcl.aron.contract

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Backlog-to-contract coverage gate (docs/24-build-spec-verification.md s10). Every BUILD row of
 * docs/25-build-backlog.csv whose lane is backend, android-* or web-*, or whose role is API, must have a row in the
 * coverage table, and every contract element the table says the row needs must exist in contract/openapi.yaml and in
 * docs/24-build-spec.md. A lane that removes an endpoint, record type, report key, config key or bundle member a
 * backlog row depends on fails `./gradlew :shared:contract:build`.
 */
class BacklogCoverageTest {
    private fun file(property: String) = File(System.getProperty(property) ?: error("system property $property is not set"))

    private val spec: String by lazy { file("aron.spec").readText() }
    private val body: String by lazy { spec.substringBefore("## Appendix A") }
    private val verification: String by lazy { file("aron.verification").readText() }

    private data class CoverageRow(val id: String, val needs: List<String>, val foundAfter: String)

    private val table: List<CoverageRow> by lazy {
        val section = verification.substringAfter("## 10. Backlog-to-contract coverage", "")
        require(section.isNotEmpty()) { "section 10 (coverage table) missing in the verification log" }
        Regex("""(?m)^\| ((?:F|N)-[A-Za-z0-9-]+) \| [^|]+ \| ([^|]+) \| (yes|no) \| (yes|NO) \|""").findAll(section).map { m ->
            val needs = m.groupValues[2].split(";").map { it.trim().removeSurrounding("`") }.filter { it.isNotEmpty() }
            CoverageRow(m.groupValues[1], needs, m.groupValues[4])
        }.toList()
    }

    /** In-scope BUILD rows of docs/25 (RFC 4180 CSV with quoted fields). */
    private val inScopeIds: List<String> by lazy {
        val records = parseCsv(file("aron.backlog").readText())
        val header = records.first()
        val col = { name: String -> header.indexOf(name).also { require(it >= 0) { "column $name missing" } } }
        val (id, decision, role, lane) = listOf(col("id"), col("decision"), col("role"), col("lane"))
        records.drop(1).filter { it.size > lane }.filter { r ->
            r[decision] == "BUILD" &&
                (r[lane] == "backend" || r[lane].startsWith("android") || r[lane].startsWith("web") || r[role] == "API")
        }.map { it[id] }
    }

    @Test
    fun everyInScopeBuildRowIsInTheTable() {
        val listed = table.map { it.id }.toSet()
        val missing = inScopeIds.filterNot { it in listed }
        val extra = listed - inScopeIds.toSet()
        assertTrue(missing.isEmpty() && extra.isEmpty()) { "coverage table out of date: missing $missing, extra $extra" }
        assertTrue(inScopeIds.size >= 400) { "expected the full backlog scope, found ${inScopeIds.size} rows" }
    }

    @Test
    fun everyNeedExistsInTheContractAndTheSpec() {
        val failures = table.flatMap { row -> row.needs.filterNot(::exists).map { "${row.id}: $it" } }
        assertTrue(failures.isEmpty()) { "backlog needs missing from openapi.yaml or docs/24: $failures" }
        assertTrue(table.all { it.foundAfter == "yes" }) { "rows still marked NO: ${table.filter { it.foundAfter != "yes" }.map { it.id }}" }
    }

    private val appendixOps: Set<String> by lazy {
        Regex("""\| (GET|POST|PUT|PATCH|DELETE|HEAD) \| `(/v1/[^`]+)`""").findAll(spec.substringAfter("## Appendix A", ""))
            .map { "${it.groupValues[1]} ${it.groupValues[2]}" }.toSet()
    }

    private fun section(number: String): String {
        val start = Regex("""(?m)^### ${Regex.escape(number)} .*$""").find(body)?.range?.last ?: return ""
        val rest = body.substring(start)
        val end = Regex("""(?m)^#{2,3} """).find(rest, 1)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private val recordRows by lazy { Regex("""(?m)^\| `([a-z_]+)`""").findAll(section("4.2")).map { it.groupValues[1] }.toSet() }
    private val reportRows by lazy { Regex("""(?m)^\| `([a-z\-]+)`""").findAll(section("12.3")).map { it.groupValues[1] }.toSet() }
    private val registry by lazy { Regex("""(?m)^\| `(cfg\.[a-z0-9_.]+)`""").findAll(section("9.5")).map { it.groupValues[1] }.toSet() }

    @Suppress("UNCHECKED_CAST")
    private fun properties(schema: Map<String, Any?>): Set<String> {
        (schema["\$ref"] as String?)?.let { return properties(OpenApi.schema(it.substringAfterLast('/'))) }
        val own = (schema["properties"] as Map<String, Any?>?)?.keys ?: emptySet()
        val inherited = (schema["allOf"] as List<Map<String, Any?>>?)?.flatMap { properties(it) } ?: emptyList()
        return own + inherited
    }

    @Suppress("UNCHECKED_CAST")
    private fun exists(token: String): Boolean {
        if (token == "local" || token == "internal") return true
        if (Regex("""^(GET|POST|PUT|PATCH|DELETE|HEAD) /v1/""").containsMatchIn(token)) {
            return token in OpenApi.operations && token in appendixOps
        }
        val kind = token.substringBefore(':')
        val value = token.substringAfter(':')
        return when (kind) {
            "rec" -> RecordType.entries.any { it.wire == value } && value in recordRows
            "report" -> ReportKey.entries.any { it.wire == value } && value in reportRows
            "cfg" -> value in registry
            "bundle" -> value in properties(OpenApi.schema("Bundle")) &&
                Regex("`" + Regex.escape(value) + """(\[\])?`""").containsMatchIn(section("4.10"))
            "enum" -> runCatching { value.substringAfter('.') in OpenApi.enumOf(value.substringBefore('.')) }.getOrDefault(false)
            "field" -> runCatching { value.substringAfter('.') in properties(OpenApi.schema(value.substringBefore('.'))) }.getOrDefault(false)
            "reportformat" -> {
                val output = (OpenApi.schema("ReportQuery")["properties"] as Map<String, Any?>)["output"] as Map<String, Any?>
                val format = (output["properties"] as Map<String, Any?>)["format"] as Map<String, Any?>
                value in (format["enum"] as List<Any?>).map { it.toString() }
            }
            "param" -> value in ((OpenApi.doc["components"] as Map<String, Any?>)["parameters"] as Map<String, Any?>).keys
            else -> error("unknown need token $token")
        }
    }

    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { row.add(cell.toString()); cell.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(cell.toString()); cell.clear(); rows.add(row); row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows.filter { r -> r.any { it.isNotEmpty() } }
    }
}
