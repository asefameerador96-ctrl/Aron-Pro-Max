package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * V0118 (review Low 13, D-DB-REUSE-SURFACE-01): the column contract of the stable dw.v_* views is pinned by
 * docs/dw-view-contract.md and versioned by dw.view_contract_version(). The file is the rendering of the catalogue; it
 * is regenerated with `tools/data-dictionary/render.sh` (`-Paron.writeDictionary=true`), and the regeneration refuses
 * a contract change the version does not announce: an added column needs a higher minor version, a removed or renamed
 * column (or a removed view) a higher major version.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DwViewContractTest {
    private lateinit var db: TestDatabase
    private val file = File(System.getProperty("aron.migrations")).parentFile.parentFile.resolve("docs/dw-view-contract.md")

    @BeforeAll
    fun setUp() {
        db = TestPostgres.createDatabase().migrated()
    }

    @AfterAll
    fun tearDown() = db.close()

    /**
     * Contract history: version -> SHA-256 of the rendered column table (names, order and types). A change of any view's
     * columns without a new version fails here even when docs/dw-view-contract.md is edited by hand; a new version adds
     * its line (the failure message prints the hash) in the same head as the migration that raises
     * dw.view_contract_version().
     */
    private val history = mapOf(
        "1.0" to "c9680e52564594847fed54155e522e7292dad3b6b053acc8d50aee4c369d7a45",
        "1.1" to "a9c369ff55c3053f1d5224c54836895cdc9de7e9a9e0ebf327e3feb3471e085d",           // V0119 master history dims
        "1.2" to "d05f4cc966c298227703c1210c1af3a91d1a2dae548d90979a1366a3d9368181",           // V0122 route-day views
    )

    private fun views(c: Connection): Map<String, List<String>> =
        c.column(
            "SELECT c.relname || E'\\t' || string_agg(a.attname || ' ' || format_type(a.atttypid, a.atttypmod), E'\\x1f' ORDER BY a.attnum) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
                "JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped " +
                "WHERE n.nspname = 'dw' AND c.relkind = 'v' AND c.relname LIKE 'v\\_%' GROUP BY c.relname ORDER BY c.relname",
        ).associate { it!!.substringBefore('\t') to it.substringAfter('\t').split('\u001f') }

    private fun render(version: String, v: Map<String, List<String>>): String = buildString {
        appendLine("# Stable dw views: column contract")
        appendLine()
        appendLine("Generated from the PostgreSQL catalogue by `tools/data-dictionary/render.sh` (db lane); do not edit by hand.")
        appendLine("Other products read only these views (docs/31 s3). Contract version **$version** (`dw.view_contract_version()`):")
        appendLine("the minor number rises when a column or view is added, the major number when one is removed, renamed or retyped;")
        appendLine("`DwViewContractTest` refuses to regenerate this file otherwise. Every view ends with `is_effective` (the row")
        appendLine("counts in an ordinary total) and, except the calendar views, carries `data_origin` (V0116). Column meanings are")
        appendLine("in docs/data-dictionary.md; each column shows its PostgreSQL type.")
        appendLine()
        appendLine("| View | Columns |")
        appendLine("|---|---|")
        v.forEach { (name, cols) -> appendLine("| `$name` | ${cols.joinToString(", ") { "`$it`" }} |") }
    }

    private fun table(v: Map<String, List<String>>): String = buildString {
        v.forEach { (name, cols) -> appendLine("$name: ${cols.joinToString(", ")}") }
    }

    private fun parse(text: String): Pair<String, Map<String, List<String>>> {
        val version = Regex("Contract version \\*\\*([0-9]+\\.[0-9]+)\\*\\*").find(text)?.groupValues?.get(1) ?: "0.0"
        val rows = text.lines().filter { it.startsWith("| `") }.associate { line ->
            val cells = line.split(" | ")
            cells[0].removePrefix("| ").trim('`') to cells[1].removeSuffix(" |").split("`, `").map { it.trim('`', ' ', '|') }
        }
        return version to rows
    }

    private fun parts(v: String) = v.split('.').map { it.toInt() }

    @Test
    fun theCommittedContractMatchesTheViewsAndEveryChangeIsVersioned() = db.connect().use { c ->
        val version = c.scalar("SELECT dw.view_contract_version()")!!
        val now = views(c)
        val rendered = render(version, now)
        val committed = if (file.exists()) file.readText() else ""
        if (System.getProperty("aron.writeDictionary") == "true" && committed != rendered) {
            val (oldVersion, old) = parse(committed)
            val (oMaj, oMin) = parts(oldVersion)
            val (nMaj, nMin) = parts(version)
            val removed = old.flatMap { (v, cols) -> cols.filter { it !in now[v].orEmpty() }.map { "$v.$it" } } + old.keys.filter { it !in now }
            val added = now.flatMap { (v, cols) -> cols.filter { it !in old[v].orEmpty() }.map { "$v.$it" } }
            if (removed.isNotEmpty() && nMaj <= oMaj) fail("removed or renamed $removed: raise the major version of dw.view_contract_version() in a migration first")
            if (added.isNotEmpty() && removed.isEmpty() && (nMaj < oMaj || (nMaj == oMaj && nMin <= oMin))) fail("added $added: raise the minor version of dw.view_contract_version() in a migration first")
            file.writeText(rendered)
        }
        assertEquals(rendered, if (file.exists()) file.readText() else "", "docs/dw-view-contract.md is stale: run tools/data-dictionary/render.sh")
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(table(now).toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(history[version], hash, "dw view columns changed under contract $version: raise dw.view_contract_version() in a migration and add \"<new version>\" to \"$hash\" to the history")
    }

    @Test
    fun everyViewEndsWithIsEffectiveAndCarriesItsOrigin() = db.connect().use { c ->
        val calendar = setOf("v_calendar", "v_calendar_month", "v_working_day")
        views(c).mapValues { (_, cols) -> cols.map { it.substringBefore(' ') } }.forEach { (v, cols) ->
            assertEquals("is_effective", cols.last(), v)
            assertEquals(v !in calendar, "data_origin" in cols, "$v data_origin")
        }
        assertEquals("boolean", c.scalar("SELECT string_agg(DISTINCT data_type, ',') FROM information_schema.columns WHERE table_schema = 'dw' AND table_name LIKE 'v\\_%' AND column_name = 'is_effective'"))
    }

    @Test
    fun theReuseViewsExposeNoPersonalDataAndNoCoordinates() = db.connect().use { c ->
        val reuse = listOf("v_memo", "v_memo_line", "v_visit", "v_due_ledger", "v_stock_movement", "v_outlet_sku_daily", "v_dim_geo", "v_dim_outlet", "v_dim_user", "v_dim_product",
            "v_dim_geo_history", "v_dim_outlet_history", "v_dim_product_history", "v_dim_user_history", "v_route_day", "v_route_day_channel")
        val all = views(c)
        val banned = Regex("(memo_no|_name_bn|full_name|username|owner_name|contact|phone|_lat|_lng|^lat$|^lng$|note|photo)")
        for (v in reuse) {
            val cols = all[v]?.map { it.substringBefore(' ') } ?: fail("$v missing")
            assertEquals(emptyList(), cols.filter { banned.containsMatchIn(it) && it !in setOf("outlet_name", "route_name", "zone_name", "house_name", "territory_name", "division_name", "wing_name") }, v)
            assertTrue(c.scalar("SELECT has_table_privilege('bi_reader', 'dw.$v', 'SELECT') AND has_table_privilege('web_ro', 'dw.$v', 'SELECT')") == "t", v)
            assertTrue(c.scalar("SELECT obj_description('dw.$v'::regclass)")!!.endsWith("pii: none"), v)
        }
    }
}
