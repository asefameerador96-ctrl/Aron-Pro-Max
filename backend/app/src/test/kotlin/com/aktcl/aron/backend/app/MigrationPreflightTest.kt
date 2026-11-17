package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.MigrationPreflight
import com.aktcl.aron.backend.platform.Migrator
import com.aktcl.aron.backend.platform.PreflightFailed
import com.aktcl.aron.backend.platform.ServerEnv
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * D-SV2-12: the pre-flight of V0191 (the mirror of its raising guard) against a database migrated to V0190 with seeded near-duplicate clusters. Dev repairs the test
 * data and V0191 then applies; every other environment fails and changes nothing; no name appears in any output; a clean database passes silently; a database at
 * V0191 or later skips. One database, the scenarios in order (each cleans up the clusters it added).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigrationPreflightTest {
    private lateinit var fresh: FreshDb
    private val tokens = listOf("Zqx", "Wvk", "Ytp")   // distinctive: none of them may appear in any output

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create(migrate = false)
        val base = System.getenv("ARON_TEST_PG_URL").orEmpty()
        // role DDL of V0014/V0020 is cluster-wide: migrate under the same server-wide lock as every other test harness
        DriverManager.getConnection(base).use { lock ->
            lock.createStatement().use { it.execute("SELECT pg_advisory_lock(${FreshDb.ROLE_DDL_LOCK})") }
            try { Migrator.configure(fresh.dataSource).target("190").load().migrate() } finally { lock.createStatement().use { it.execute("SELECT pg_advisory_unlock(${FreshDb.ROLE_DDL_LOCK})") } }
        }
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
    }

    @AfterAll
    fun tearDown() = fresh.close()

    private fun scalar(sql: String): String? = fresh.dataSource.connection.use { c -> c.createStatement().use { st -> st.executeQuery(sql).use { if (it.next()) it.getString(1) else null } } }
    private fun exec(sql: String) = fresh.dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        private fun clusters(zone: Long) = fresh.dataSource.connection.use { c -> c.createStatement().use { st -> st.executeQuery("SELECT id, name, status FROM app.cluster WHERE zone_id = $zone AND (name ILIKE '%Zqx%' OR name ILIKE '%Wvk%' OR name ILIKE '%Ytp%') ORDER BY id").use { rs -> buildList { while (rs.next()) add(Triple(rs.getLong(1), rs.getString(2), rs.getString(3))) } } } }
    private fun snapshot() = scalar("SELECT coalesce(string_agg(id || ':' || zone_id || ':' || name || ':' || status, '|' ORDER BY id), '') FROM app.cluster")!!
    private fun cleanup() = exec("DELETE FROM app.cluster WHERE name ILIKE '%Zqx%' OR name ILIKE '%Wvk%' OR name ILIKE '%Ytp%'")
    private fun lines(env: ServerEnv): Pair<List<String>, Throwable?> {
        val out = ArrayList<String>()
        val err = runCatching { MigrationPreflight.run(fresh.dataSource, env, { out += it }) }.exceptionOrNull()
        return out to err
    }
    private fun assertNoNames(text: String) { for (t in tokens) assertTrue(!text.contains(t, ignoreCase = true), "a cluster name leaked into the output: $text") }

    /** Near-duplicates of every kind in Z-MIR (case, spacing, composition, an inactive one, three in a group, a name at the length limit) and one group in Z-GUL. */
    private fun seedDuplicates(): Map<String, List<Long>> {
        // the common seed has one zone: a second one for the groups of another zone
        exec("INSERT INTO app.zone (code, name, name_bn, territory_id, house_id, dep_name) SELECT 'Z-PRE', 'Pre-flight test zone', name_bn, territory_id, house_id, dep_name FROM app.zone WHERE code <> 'Z-PRE' ORDER BY id LIMIT 1 ON CONFLICT DO NOTHING")
        val zones = fresh.dataSource.connection.use { c -> c.createStatement().use { st -> st.executeQuery("SELECT id FROM app.zone ORDER BY id LIMIT 2").use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } } } }
        assertEquals(2, zones.size, "two zones")
        val mir = zones[0]; val gul = zones[1]
        fun add(zone: Long, name: String, status: String = "active") = scalar("INSERT INTO app.cluster (zone_id, name, status) VALUES ($zone, '${name.replace("'", "''")}', '$status') RETURNING id")!!.toLong()
        val long = "Zqx" + "L".repeat(117)   // exactly 120 characters
        assertEquals(120, long.length)
        return mapOf(
            "case" to listOf(add(mir, "Zqx Market"), add(mir, "ZQX MARKET")),
            "spacing" to listOf(add(mir, "Wvk Bazar"), add(mir, "Wvk  Bazar"), add(mir, " wvk bazar ")),        // three in one group, one with NBSP and edge spaces
            "composition" to listOf(add(mir, "Ytp Café"), add(mir, "Ytp Café")),                            // precomposed vs combining accent
            "inactive" to listOf(add(gul, "Zqx Circle"), add(gul, "zqx circle", "inactive")),
            "length" to listOf(add(gul, long), add(gul, long.lowercase()), add(gul, long.uppercase())),
        )
    }

    @Test
    fun theScenariosInOrder() {
        // 0. the seed has no near-duplicates: a clean database passes silently, in every environment, and writes nothing
        for (env in ServerEnv.entries) { val (out, err) = lines(env); assertEquals(emptyList(), out, "silent"); assertEquals(null, err) }

        // 1. non-dev: fails before any change; ids, zone codes and counts only
        val groups = seedDuplicates()
        val zone0 = scalar("SELECT code FROM app.zone ORDER BY id LIMIT 1")!!; val zone1 = scalar("SELECT code FROM app.zone ORDER BY id OFFSET 1 LIMIT 1")!!
        val before = snapshot()
        val (out, err) = lines(ServerEnv.PROD)
        val failed = assertFailsWith<PreflightFailed> { MigrationPreflight.run(fresh.dataSource, ServerEnv.PROD) }
        assertTrue(err is PreflightFailed)
        val text = out.joinToString("\n") + "\n" + failed.message
        assertNoNames(text)
        for (z in listOf(zone0, zone1)) assertTrue(text.contains("zone $z "), text)
        assertTrue(text.contains("5 group(s)"), text)
        for (g in groups.values) for (id in g) assertTrue(text.contains(id.toString()), "id $id is listed")
        assertTrue(text.contains("admin portal"), "it says what to do")
        assertEquals(before, snapshot(), "nothing changed")
        assertEquals("false", scalar("SELECT (to_regprocedure('app.norm_cluster_name(text)') IS NOT NULL)::text"), "V0191 not applied")

        // 2. dev: repairs the test data (lowest id kept, the others renamed with an id suffix), prints ids only, audits each rename, and V0191 then applies
        val (devOut, devErr) = lines(ServerEnv.DEV)
        assertEquals(null, devErr, devErr?.message)
        assertNoNames(devOut.joinToString("\n"))
        val zone0Id = scalar("SELECT id FROM app.zone ORDER BY id LIMIT 1")!!.toLong(); val zone1Id = scalar("SELECT id FROM app.zone ORDER BY id OFFSET 1 LIMIT 1")!!.toLong()
        val renamedExpected = groups.values.flatMap { it.drop(1) }
        assertTrue(devOut.any { it.contains("renamed cluster ids") && renamedExpected.all { id -> it.contains(id.toString()) } }, devOut.toString())
        for ((kind, ids) in groups) {
            val rows = (clusters(zone0Id) + clusters(zone1Id)).filter { it.first in ids }.associateBy { it.first }
            val keep = rows.getValue(ids.first())
            assertTrue(!keep.second.contains(" #"), "$kind: the lowest id keeps its name")
            for (id in ids.drop(1)) {
                val row = rows.getValue(id)
                assertTrue(row.second.endsWith(" #$id"), "$kind: id $id carries its id suffix")
                assertTrue(row.second.length <= 120, "$kind: within the length CHECK (${row.second.length})")
            }
            assertEquals(ids.size, rows.values.map { it.second.lowercase().trim() }.toSet().size, "$kind: distinct names")
        }
        assertEquals(renamedExpected.size.toString(), scalar("SELECT count(*) FROM app.audit_log WHERE entity = 'cluster' AND action = 'preflight_rename' AND actor_username = 'migrate-preflight' AND via = 'job'"))
        assertEquals(null, scalar("SELECT app.audit_verify()"), "the audit chain stays intact")
        assertEquals("inactive", scalar("SELECT status FROM app.cluster WHERE id = ${groups.getValue("inactive")[1]}"), "status untouched")
        val repaired = snapshot()
        // a second run finds nothing; and the real migration now applies
        assertEquals(emptyList(), lines(ServerEnv.DEV).first)
        Migrator.configure(fresh.dataSource).load().migrate()
        assertEquals("true", scalar("SELECT (to_regprocedure('app.norm_cluster_name(text)') IS NOT NULL)::text"), "V0191 applied after the repair")
        assertEquals(repaired, snapshot())

        // 3. a database already at V0191 or later skips: no check, no output, in every environment (V0191 makes a duplicate impossible, so nothing is seeded)
        for (env in ServerEnv.entries) assertEquals(emptyList(), lines(env).first)
        cleanup()
    }

    @Test
    fun theInlineNormalisationIsTheOneOfV0191() {
        val migration = File(System.getProperty("aron.repoRoot"), "db/migrations/V0191__sr_server_train2_review_fixes.sql").readText()
        val preflight = File(System.getProperty("aron.repoRoot"), "db/preflight/V0191__cluster_names.sql").readText()
        val cls = Regex("normalize\\((?:t|c\\.name), NFC\\), ('\\[\\\\s[^']*\\]\\+'), ' ', 'g'\\)")
        assertEquals(cls.find(migration)!!.groupValues[1], cls.find(preflight)!!.groupValues[1], "the character class must stay identical to V0191's")
    }
}
