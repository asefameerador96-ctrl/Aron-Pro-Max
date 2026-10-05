package com.aktcl.aron.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Cross-check of docs/24-build-spec.md (the binding prose) against contract/openapi.yaml and the Kotlin mirror,
 * in both directions (docs/24 Appendix B). Fails the build when someone edits one without the other.
 */
class SpecCrossCheckTest {
    private val specFull: String by lazy {
        File(System.getProperty("aron.spec") ?: error("system property aron.spec is not set")).readText()
    }

    /** Sections 1 to 14 only; the appendices are generated from the contract. */
    private val body: String by lazy { specFull.substringBefore("## Appendix A") }

    private fun section(number: String): String {
        val start = Regex("""(?m)^### ${Regex.escape(number)} .*$""").find(body)?.range?.last
            ?: error("section $number not found in docs/24")
        val rest = body.substring(start)
        val end = Regex("""(?m)^#{2,3} """).find(rest, 1)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private fun firstCells(text: String, cellPattern: String): List<String> =
        Regex("""(?m)^\| ([^|]+) \|""").findAll(text).flatMap { row ->
            Regex(cellPattern).findAll(row.groupValues[1]).map { it.groupValues[1] }
        }.toList()

    @Test
    fun everyContractOperationIsInAppendixAAndBack() {
        val appendix = specFull.substringAfter("## Appendix A", "")
        val listed = Regex("""\| (GET|POST|PUT|PATCH|DELETE|HEAD) \| `(/v1/[^`]+)`""")
            .findAll(appendix).map { "${it.groupValues[1]} ${it.groupValues[2]}" }.toSet()
        assertEquals(OpenApi.operations.sorted(), listed.sorted(), "Appendix A of docs/24 is not the contract's operation list")
    }

    @Test
    fun everyPathNamedInTheSpecExists() {
        val normalised = OpenApi.paths.keys.map { it.replace(Regex("""\{[^}]+}"""), "{}") }
        val named = Regex("""/v1/[a-z0-9_{}/\-]+""").findAll(body).map { it.value.trimEnd('/', '.') }.toSet()
        val unknown = named.filterNot { p ->
            val n = p.replace(Regex("""\{[^}]+}"""), "{}")
            n == "/v1/devices/me" || normalised.any { it == n || it.startsWith("$n/") }
        }
        assertTrue(unknown.isEmpty()) { "paths named in docs/24 but absent from openapi.yaml: $unknown" }
    }

    @Test
    fun recordTypeTableEqualsEnum() {
        val rows = firstCells(section("4.2"), """^`([a-z_]+)`""").filter { it != "type" }
        assertEquals(RecordType.entries.map { it.wire }.sorted(), rows.sorted())
    }

    @Test
    fun problemCodeStatusesMatchSection34() {
        val table = section("3.4")
        val stated = mutableMapOf<String, Int>()
        Regex("""(?m)^\| (\d{3}) \| ([^|]+)\|""").findAll(table).forEach { row ->
            Regex("""`(ERR_[A-Z_]+)`""").findAll(row.groupValues[2]).forEach { stated[it.groupValues[1]] = row.groupValues[1].toInt() }
        }
        assertEquals(ProblemCode.entries.associate { it.wire to it.httpStatus }, stated.toMap())
    }

    @Test
    fun outcomeCodesMatchSection45() {
        val catalogue = section("4.5").substringAfter("| Code | Status |")
        val stated = mutableMapOf<String, Pair<String, Boolean?>>()
        Regex("""(?m)^\| ([^|]+) \| (rejected|quarantined) \| ([^|]+) \|""").findAll(catalogue).forEach { row ->
            val retryable = when (row.groupValues[3].trim()) { "yes" -> true; "no" -> false; else -> null }
            Regex("""`([a-z_]+)`""").findAll(row.groupValues[1]).forEach {
                stated[it.groupValues[1]] = row.groupValues[2] to retryable
            }
        }
        assertEquals(RecordOutcomeCode.entries.associate { it.wire to (it.status.wire to it.retryable) }, stated.toMap())
    }

    @Test
    fun riskSignalsAndReportsMatch() {
        assertEquals(RiskSignalCode.entries.map { it.wire }.sorted(), firstCells(section("11.4"), """^`([A-Z_]+)`""").sorted())
        assertEquals(ReportKey.entries.map { it.wire }.sorted(), firstCells(section("12.3"), """^`([a-z\-]+)`""").sorted())
    }

    @Test
    fun everyConfigKeyIsInTheRegistry() {
        val keyPattern = Regex("""cfg\.[a-z]+(?:\.[a-z0-9_]+){1,2}""")
        val registry = Regex("""(?m)^\| `(cfg\.[a-z0-9_.]+)`""").findAll(section("9.5")).map { it.groupValues[1] }.toSet()
        val inSpec = keyPattern.findAll(body).map { it.value }.filterNot { it.startsWith("cfg.edit.") }.toSet()
        val inContract = keyPattern.findAll(OpenApi.text).map { it.value }.toSet()
        assertTrue((inSpec - registry).isEmpty()) { "keys named in docs/24 but missing from s9.5: ${inSpec - registry}" }
        assertTrue((inContract - registry).isEmpty()) { "keys named in openapi.yaml but missing from s9.5: ${inContract - registry}" }
        val namePattern = Regex(OpenApi.schema("ConfigKeyName")["pattern"] as String)
        assertTrue(registry.all { namePattern.matches(it) }) { "registry keys violating ConfigKeyName: ${registry.filterNot { namePattern.matches(it) }}" }
        assertTrue(registry.size >= 60) { "the Phase 1 registry must list at least 60 keys, has ${registry.size}" }
    }

    @Test
    fun everyHeaderNamedInTheSpecExists() {
        val named = Regex("""`(X-[A-Za-z\-]+)`""").findAll(body).map { it.groupValues[1] }.toSet()
        val missing = named.filterNot { OpenApi.text.contains(it) }
        assertTrue(missing.isEmpty()) { "headers named in docs/24 but absent from openapi.yaml: $missing" }
    }
}
