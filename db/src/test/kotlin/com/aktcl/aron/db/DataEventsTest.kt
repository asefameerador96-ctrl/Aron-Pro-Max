package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * docs/31 s3: domain-event payloads are versioned (V0017) and docs/data-events.md is the rendering of the catalogue
 * app.domain_event_type. Regenerate with `tools/data-dictionary/render.sh` (or `-Paron.writeDictionary=true`).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataEventsTest {
    private lateinit var db: TestDatabase
    private val file = File(System.getProperty("aron.migrations")).parentFile.parentFile.resolve("docs/data-events.md")

    @BeforeAll
    fun setUp() {
        db = TestPostgres.createDatabase().migrated()
    }

    @AfterAll
    fun tearDown() = db.close()

    private fun event(type: String, version: Int, payload: String) =
        "INSERT INTO app.domain_event (event_type, payload_version, aggregate_type, aggregate_id, business_date, payload) " +
            "VALUES ('$type', $version, 'memo', 'x', DATE '2026-10-07', '$payload'::jsonb)"

    private fun refused(c: Connection, sql: String): String {
        c.exec("SAVEPOINT s")
        val e = assertFailsWith<SQLException> { c.exec(sql) }
        c.exec("ROLLBACK TO SAVEPOINT s")
        return e.sqlState
    }

    @Test
    fun onlyCataloguedEventsWithTheirRequiredKeysAreWritten() = db.connect().use { c ->
        c.autoCommit = false
        try {
            c.exec(
                event(
                    "memo.voided", 1,
                    """{"memo_uuid":"6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10","route_id":1,"voided_at":"2026-10-07T10:00:00Z","extra":1}""",
                ),
            )
            assertEquals("1", c.scalar("SELECT payload_version FROM app.domain_event WHERE aggregate_id = 'x'"))
            assertEquals("23503", refused(c, event("memo.voided", 2, "{}")))                  // no such version
            assertEquals("23503", refused(c, event("memo.renamed", 1, "{}")))                 // no such type
            assertEquals("23503", refused(c, event("memo.voided", 1, "{}").replace(", 1, 'memo'", ", NULL, 'memo'")))  // no version
            assertEquals("23514", refused(c, event("memo.voided", 1, "[]")))                  // not an object
            // V0018: thin events (no version, empty payload) are accepted until the version enforces its required keys.
            c.exec("INSERT INTO app.domain_event (event_type, aggregate_type, aggregate_id, business_date) VALUES ('memo.voided', 'memo', 'y', DATE '2026-10-07')")
            assertEquals("1", c.scalar("SELECT payload_version FROM app.domain_event WHERE aggregate_id = 'y'"))
            c.exec("UPDATE app.domain_event_type SET enforce_required = true WHERE event_type = 'memo.voided'")
            assertEquals("23514", refused(c, event("memo.voided", 1, """{"memo_uuid":"x"}""")))  // required keys missing
        } finally {
            c.rollback()
        }
    }

    @Test
    fun aPublishedVersionIsFixed() = db.connect().use { c ->
        c.autoCommit = false
        try {
            assertEquals("42501", refused(c, "UPDATE app.domain_event_type SET payload_schema = payload_schema || '{\"x\":1}' WHERE event_type = 'memo.created'"))
            assertEquals("42501", refused(c, "DELETE FROM app.domain_event_type WHERE event_type = 'memo.created'"))
            c.exec("UPDATE app.domain_event_type SET description = 'reworded', deprecated_at = now() WHERE event_type = 'memo.created'")
            assertEquals("42501", refused(c, "UPDATE app.domain_event_type SET deprecated_at = NULL WHERE event_type = 'memo.created'"))
        } finally {
            c.rollback()
        }
    }

    @Test
    fun theApiWritesEventsAndReadsTheCatalogueButCannotChangeIt() = db.connect().use { c ->
        c.autoCommit = false
        try {
            c.exec("GRANT api_rw TO CURRENT_USER WITH INHERIT FALSE, SET TRUE")
            c.exec("SET ROLE api_rw")
            assertEquals("7", c.scalar("SELECT count(*) FROM app.domain_event_type"))
            c.exec(event("route_day.state_changed", 1, """{"route_day_id":1,"route_id":1,"from_state":null,"to_state":"logged_in"}"""))
            assertEquals("42501", refused(c, "UPDATE app.domain_event_type SET description = 'x'"))
            assertEquals("42501", refused(c, "INSERT INTO app.domain_event_type SELECT event_type, 9, aggregate_type, aggregate_id_is, producer, description, payload_schema, 'x', NULL FROM app.domain_event_type LIMIT 1"))
        } finally {
            c.rollback()
        }
    }

    @Test
    fun everySchemaListsItsRequiredKeysAsProperties() = db.connect().use { c ->
        assertEquals(
            emptyList(),
            c.column(
                "SELECT event_type || ' v' || payload_version || ': ' || k FROM app.domain_event_type, " +
                    "jsonb_array_elements_text(payload_schema -> 'required') k WHERE NOT jsonb_exists(payload_schema -> 'properties', k) ORDER BY 1",
            ),
        )
    }

    @Test
    fun theCommittedEventCatalogueMatchesTheDatabase() = db.connect().use { c ->
        val rendered = render(c)
        if (System.getProperty("aron.writeDictionary") == "true") file.writeText(rendered)
        assertEquals(rendered, if (file.exists()) file.readText() else "", "docs/data-events.md is stale: run tools/data-dictionary/render.sh")
    }

    private fun render(c: Connection): String = buildString {
        appendLine("# Domain events")
        appendLine()
        appendLine("Generated from `app.domain_event_type` by `tools/data-dictionary/render.sh` (db lane); do not edit by hand.")
        appendLine("The outbox `app.domain_event` is read in `id` order by the aggregate projector and Phase 2 consumers (docs/24 s6.3, s12.5).")
        appendLine()
        appendLine("## Versioning rules (V0017)")
        appendLine()
        appendLine("- Every outbox row has `event_type` and `payload_version` (default 1); the insert trigger refuses a pair that is")
        appendLine("  not a catalogue row and a payload that is not a JSON object. A version's required keys are enforced once its")
        appendLine("  `enforce_required` is on (a db migration after the producer sends them, V0018); until then the schema is the")
        appendLine("  target shape and consumers read the source row through `source_client_uuid`.")
        appendLine("- Payloads carry ids, codes, counts and amounts, never names, phone numbers, NIDs or coordinates.")
        appendLine("- Adding an optional key keeps the version. Removing or renaming a key, changing its type or meaning, or making")
        appendLine("  a key required adds a new version (a db migration; ask through docs/requests). A published schema never changes.")
        appendLine("- Producers write the newest version that is not deprecated; consumers handle every version that is not")
        appendLine("  deprecated and ignore keys they do not know.")
        appendLine()
        appendLine("| Event | Version | Aggregate | aggregate_id | Producer | Status | Required keys enforced |")
        appendLine("|---|---|---|---|---|---|---|")
        val types = c.column(
            "SELECT concat_ws(E'\\t', event_type, payload_version, aggregate_type, aggregate_id_is, producer, " +
                "CASE WHEN deprecated_at IS NULL THEN 'current' ELSE 'deprecated' END, introduced_in, description, " +
                "CASE WHEN enforce_required THEN 'yes' ELSE 'not yet' END) " +
                "FROM app.domain_event_type ORDER BY event_type, payload_version",
        ).map { it!!.split('\t') }
        types.forEach { t -> appendLine("| [`${t[0]}`](#${anchor(t[0], t[1])}) | ${t[1]} | `${t[2]}` | `${t[3]}` | ${t[4]} | ${t[5]} | ${t[8]} |") }
        types.forEach { t ->
            appendLine()
            appendLine("## ${t[0]} v${t[1]}")
            appendLine()
            appendLine("${t[7]} Introduced in ${t[6]}; ${t[5]}.")
            appendLine()
            appendLine("| Key | Type | Required | Description |")
            appendLine("|---|---|---|---|")
            c.column(
                "SELECT concat_ws(E'\\t', p.key, coalesce(p.value ->> 'format', ''), " +
                    "CASE jsonb_typeof(p.value -> 'type') WHEN 'array' THEN (SELECT string_agg(x, ' or ') FROM jsonb_array_elements_text(p.value -> 'type') x) ELSE p.value ->> 'type' END, " +
                    "jsonb_exists(t.payload_schema -> 'required', p.key), coalesce(p.value ->> 'description', ''), " +
                    "coalesce((SELECT string_agg(x, ', ') FROM jsonb_array_elements_text(p.value -> 'enum') x), '')) " +
                    "FROM app.domain_event_type t, jsonb_each(t.payload_schema -> 'properties') p " +
                    "WHERE t.event_type = ? AND t.payload_version = ?::smallint ORDER BY NOT (jsonb_exists(t.payload_schema -> 'required', p.key)), p.key",
                t[0], t[1],
            ).map { it!!.split('\t') }.forEach { p ->
                val type = p[2] + if (p[1].isNotEmpty()) " (${p[1]})" else ""
                val desc = listOf(p[4], if (p[5].isNotEmpty()) "one of ${p[5]}" else "").filter { it.isNotEmpty() }.joinToString("; ")
                appendLine("| `${p[0]}` | $type | ${if (p[3] == "t") "yes" else "no"} | $desc |")
            }
        }
    }

    private fun anchor(type: String, version: String) = "${type.replace(".", "")}-v$version"
}
