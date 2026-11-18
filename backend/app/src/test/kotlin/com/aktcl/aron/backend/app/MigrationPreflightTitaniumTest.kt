package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.MigrationPreflight
import com.aktcl.aron.backend.platform.Migrator
import com.aktcl.aron.backend.platform.PreflightFailed
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The pre-flight of V0199 (docs/30 s4a): the mirror of its two raising DATA guards (a `Titanium` sub-channel, or the `titanium` code-list item, under a channel
 * other than Astha), against a database migrated to V0198. The guard has no automatic repair: it fails in EVERY environment, dev included, changes nothing and
 * prints channel codes, counts and ids only. A clean database passes silently; one at V0199 or later skips. One database, the scenarios in order.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigrationPreflightTitaniumTest {
    private lateinit var fresh: FreshDb
    /** The raw ARON_ENV values a deploy can declare: dev (the only one that may repair anything), prod, staging, an unknown one, and none at all. */
    private val ENVS: List<String?> = listOf("dev", "prod", "staging", "typo", null)
    private val marker = "Zqtitan"   // distinctive text put into the offending rows' NAMES: it must never appear in any output

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create(migrate = false)
        val base = System.getenv("ARON_TEST_PG_URL").orEmpty()
        // role DDL of V0014/V0020 is cluster-wide: migrate under the same server-wide lock as every other test harness
        DriverManager.getConnection(base).use { lock ->
            lock.createStatement().use { it.execute("SELECT pg_advisory_lock(${FreshDb.ROLE_DDL_LOCK})") }
            try { Migrator.configure(fresh.dataSource).target("198").load().migrate() } finally { lock.createStatement().use { it.execute("SELECT pg_advisory_unlock(${FreshDb.ROLE_DDL_LOCK})") } }
        }
    }

    @AfterAll
    fun tearDown() = fresh.close()

    private fun scalar(sql: String): String? = fresh.dataSource.connection.use { c -> c.createStatement().use { st -> st.executeQuery(sql).use { if (it.next()) it.getString(1) else null } } }
    private fun exec(sql: String) = fresh.dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
    private fun snapshot() = scalar(
        "SELECT (SELECT coalesce(string_agg(id || code || channel || status, '|' ORDER BY id), '') FROM app.sub_channel) || '#' || " +
            "(SELECT coalesce(string_agg(id || list_key || code || attrs::text, '|' ORDER BY id), '') FROM app.code_list_item)",
    )!!

    private fun run(env: String?): Pair<List<String>, Throwable?> {
        val out = ArrayList<String>()
        val err = runCatching { MigrationPreflight.run(fresh.dataSource, env, { out += it }) }.exceptionOrNull()
        return out to err
    }

    @Test
    fun theScenariosInOrder() {
        // the code-list guard trigger refuses a DELETE (items are retired, never deleted): the scenarios add and remove offending rows, so it is off for them and back on before V0199 runs
        exec("ALTER TABLE app.code_list_item DISABLE TRIGGER USER")
        // 0. at V0198 the database has no Titanium: a clean database passes silently in every environment
        assertEquals("0", scalar("SELECT count(*) FROM app.sub_channel WHERE code = 'Titanium'"))
        for (env in ENVS) { val (out, err) = run(env); assertEquals(emptyList(), out, "silent"); assertEquals(null, err) }

        // 0b. rows that V0199 accepts are not findings: Titanium under Astha, and the item with channel Astha
        exec("INSERT INTO app.sub_channel (channel, code, name) VALUES ('Astha', 'Titanium', 'Titanium')")
        exec("INSERT INTO app.code_list_item (list_key, code, label_en, attrs) VALUES ('sub_channel', 'titanium', 'Titanium', '{\"channel\": \"Astha\"}')")
        for (env in ENVS) assertEquals(emptyList(), run(env).first)
        exec("DELETE FROM app.code_list_item WHERE code = 'titanium'")
        exec("DELETE FROM app.sub_channel WHERE code = 'Titanium'")

        // 1. a sub-channel Titanium under GT: fails in EVERY environment (dev included), changes nothing, channel code and id only
        exec("INSERT INTO app.sub_channel (channel, code, name) VALUES ('GT', 'Titanium', '$marker sub-channel name')")
        val subId = scalar("SELECT id FROM app.sub_channel WHERE code = 'Titanium'")!!
        val before = snapshot()
        for (env in ENVS) {
            val (out, err) = run(env)
            val failed = assertFailsWith<PreflightFailed>("$env") { throw err ?: error("no failure in $env") }
            val text = out.joinToString("\n") + "\n" + failed.message
            assertTrue(!text.contains(marker, ignoreCase = true), "a name leaked into the output: $text")
            assertTrue(text.contains("finding sub_channel under channel GT") && text.contains("[$subId]") && text.contains("1 group(s)"), text)
            assertTrue(text.contains("no automatic repair") && text.contains("admin portal"), "it says what to do: $text")
            assertEquals(before, snapshot(), "nothing changed in $env")
        }
        assertEquals("0", scalar("SELECT count(*) FROM flyway_schema_history WHERE success AND version ~ '^0*199$'"), "V0199 was not applied")

        // 2. the code-list item under DCC, and one with no channel at all, are findings too (V0199 treats a missing channel as 'other'); a channel attribute that is no code is reported as 'other'
        exec("DELETE FROM app.sub_channel WHERE code = 'Titanium'")
        exec("INSERT INTO app.code_list_item (list_key, code, label_en, attrs) VALUES ('sub_channel', 'titanium', '$marker item label', '{\"channel\": \"$marker\"}')")
        val itemId = scalar("SELECT id FROM app.code_list_item WHERE code = 'titanium'")!!
        for (env in ENVS) {
            val (out, err) = run(env)
            val text = out.joinToString("\n") + "\n" + (err?.message ?: "")
            assertTrue(err is PreflightFailed, "$env: $text")
            assertTrue(!text.contains(marker, ignoreCase = true), "a label or an attribute value leaked: $text")
            assertTrue(text.contains("code-list item under channel other") && text.contains("[$itemId]"), text)
        }
        exec("UPDATE app.code_list_item SET attrs = '{}' WHERE code = 'titanium'")
        assertTrue(run("prod").second?.message!!.contains("code-list item under channel none"))
        exec("UPDATE app.code_list_item SET attrs = '{\"channel\": \"DCC\"}' WHERE code = 'titanium'")
        assertTrue(run("dev").second?.message!!.contains("code-list item under channel DCC"))

        // 3. both at once: two groups, and the fix by hand (the portal advice) lets the deploy through; then V0199 itself applies cleanly
        exec("INSERT INTO app.sub_channel (channel, code, name) VALUES ('MT', 'Titanium', 'Titanium')")
        assertTrue(run("prod").second?.message!!.contains("2 group(s)"))
        exec("DELETE FROM app.sub_channel WHERE code = 'Titanium'")
        exec("DELETE FROM app.code_list_item WHERE code = 'titanium'")
        for (env in ENVS) assertEquals(emptyList(), run(env).first)
        exec("ALTER TABLE app.code_list_item ENABLE TRIGGER USER")
        Migrator.configure(fresh.dataSource).load().migrate()
        assertEquals("1", scalar("SELECT count(*) FROM app.sub_channel WHERE code = 'Titanium' AND channel = 'Astha'"), "V0199 applied")

        // 4. at V0199 or later the check skips: no output in any environment, even with a row it would otherwise report (V0199 is the record of what ran)
        exec("UPDATE app.sub_channel SET channel = 'GT' WHERE code = 'Titanium'")
        for (env in ENVS) { val (out, err) = run(env); assertEquals(emptyList(), out); assertEquals(null, err) }
    }
}
