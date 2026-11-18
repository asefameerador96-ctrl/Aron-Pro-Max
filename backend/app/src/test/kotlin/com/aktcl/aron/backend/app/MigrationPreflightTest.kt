package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.MigrationPreflight
import com.aktcl.aron.backend.platform.Migrator
import com.aktcl.aron.backend.platform.PreflightFailed
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
    /** The one line a run with nothing to do reports (lead, D-OPS-DIAGNOSE-API-01): the check ids with `clean` (applies, found nothing) or `not pending` (its migration is applied). Written out in full on purpose. */
    private fun quiet(cluster: String, titanium: String) = listOf("pre-flight: nothing to do (checks: V0191-cluster-names=$cluster, V0199-titanium-tier=$titanium)")

    private fun lines(env: String?): Pair<List<String>, Throwable?> {
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
        // 0. the seed has no near-duplicates: a clean database passes in every environment, changes nothing and reports the one nothing-to-do line
        for (env in listOf("dev", "prod", "staging", null)) { val (out, err) = lines(env); assertEquals(quiet("clean", "clean"), out, "one line, nothing else"); assertEquals(null, err) }

        // 1. non-dev: fails before any change; ids, zone codes and counts only
        val groups = seedDuplicates()
        val zone0 = scalar("SELECT code FROM app.zone ORDER BY id LIMIT 1")!!; val zone1 = scalar("SELECT code FROM app.zone ORDER BY id OFFSET 1 LIMIT 1")!!
        val before = snapshot()
        val (out, err) = lines("prod")
        val failed = assertFailsWith<PreflightFailed> { MigrationPreflight.run(fresh.dataSource, "prod") }
        assertTrue(err is PreflightFailed)
        val text = out.joinToString("\n") + "\n" + failed.message
        assertNoNames(text)
        for (z in listOf(zone0, zone1)) assertTrue(text.contains("zone $z "), text)
        assertTrue(text.contains("5 group(s)"), text)
        for (g in groups.values) for (id in g) assertTrue(text.contains(id.toString()), "id $id is listed")
        assertTrue(text.contains("admin portal"), "it says what to do")
        assertEquals(before, snapshot(), "nothing changed")
        // only the raw value "dev" repairs: staging, an unset value and a typo fail like prod and change nothing
        for (env in listOf("staging", null, "Dev", "development", "")) {
            assertTrue(lines(env).second is PreflightFailed, "env=$env must not repair")
            assertEquals(before, snapshot(), "env=$env changed nothing")
        }
        // a name that is already taken (the first candidate " #<id>" exists exactly): another suffix is picked
        val blocker = scalar("INSERT INTO app.cluster (zone_id, name) VALUES ((SELECT id FROM app.zone ORDER BY id LIMIT 1), 'ZQX MARKET #${groups.getValue("case")[1]}') RETURNING id")!!.toLong()
        assertEquals("false", scalar("SELECT (to_regprocedure('app.norm_cluster_name(text)') IS NOT NULL)::text"), "V0191 not applied")

        // 2. dev: repairs the test data (lowest id kept, the others renamed with an id suffix), prints ids only, audits each rename, and V0191 then applies
        val (devOut, devErr) = lines("dev")
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
                val tail = if (kind == "case" && id == ids[1]) " #$id-2" else " #$id"
                assertTrue(row.second.endsWith(tail), "$kind: id $id carries its id suffix ($tail), the first candidate being taken for the case group")
                assertTrue(row.second.length <= 120, "$kind: within the length CHECK (${row.second.length})")
            }
            assertEquals(ids.size, rows.values.map { it.second.lowercase().trim() }.toSet().size, "$kind: distinct names")
        }
        assertEquals(renamedExpected.size.toString(), scalar("SELECT count(*) FROM app.audit_log WHERE entity = 'cluster' AND action = 'preflight_rename' AND actor_user_id IS NULL AND via = 'job'"))
        assertEquals(null, scalar("SELECT app.audit_verify()"), "the audit chain stays intact")
        assertEquals("inactive", scalar("SELECT status FROM app.cluster WHERE id = ${groups.getValue("inactive")[1]}"), "status untouched")
        val repaired = snapshot()
        // a second run finds nothing; and the real migration now applies
        assertEquals(quiet("clean", "clean"), lines("dev").first)
        Migrator.configure(fresh.dataSource).load().migrate()
        assertEquals("true", scalar("SELECT (to_regprocedure('app.norm_cluster_name(text)') IS NOT NULL)::text"), "V0191 applied after the repair")
        assertEquals(repaired, snapshot())

        // 3. a database already at V0191 or later skips both checks: only the nothing-to-do line, in every environment (V0191 makes a duplicate impossible, so nothing is seeded)
        for (env in listOf("dev", "prod", null)) assertEquals(quiet("not pending", "not pending"), lines(env).first)
        // applicability is keyed on Flyway's history (a successful V0191 row), not on the function: with the row's success flag off V0191 is pending again, and back on it is not
        fresh.dataSource.connection.use { c ->
            assertEquals(false, MigrationPreflight.ClusterNames.applies(c))
            c.createStatement().use { it.execute("UPDATE flyway_schema_history SET success = false WHERE version ~ '^0*191$'") }
            assertEquals(true, MigrationPreflight.ClusterNames.applies(c))
            c.createStatement().use { it.execute("UPDATE flyway_schema_history SET success = true WHERE version ~ '^0*191$'") }
            assertEquals(false, MigrationPreflight.ClusterNames.applies(c))
        }
        cleanup()
    }

    @Test
    fun theInlineNormalisationIsTheOneOfV0191() {
        val migration = File(System.getProperty("aron.repoRoot"), "db/migrations/V0191__sr_server_train2_review_fixes.sql").readText()
        val preflight = File(System.getProperty("aron.repoRoot"), "db/preflight/V0191__cluster_names.sql").readText()
        val cls = Regex("normalize\\((?:t|c\\.name), NFC\\), ('\\[\\\\s[^']*\\]\\+'), ' ', 'g'\\)")
        assertEquals(cls.find(migration)!!.groupValues[1], cls.find(preflight)!!.groupValues[1], "the character class must stay identical to V0191's")
    }

    @Test
    fun aFailedRepairReportsTheSqlStateOnlyNeverTheDetailOrTheCause() {
        val leak = "duplicate key value violates unique constraint, Key (zone_id, name)=(1, Zqx Secret Market) already exists"
        val check = object : MigrationPreflight.Check {
            override val id = "test"
            override val advice = "n/a"
            override fun applies(c: java.sql.Connection) = true
            override fun lock(c: java.sql.Connection) {}
            override fun findings(c: java.sql.Connection) = listOf(MigrationPreflight.Finding("Z-TEST", listOf(1L, 2L)))
            override fun repair(c: java.sql.Connection, findings: List<MigrationPreflight.Finding>): List<Long> = throw java.sql.SQLException(leak, "23505")
        }
        val out = ArrayList<String>()
        val failed = assertFailsWith<PreflightFailed> { MigrationPreflight.run(fresh.dataSource, "dev", { out += it }, listOf(check)) }
        val text = out.joinToString("\n") + "\n" + failed.message + "\n" + (failed.cause?.toString() ?: "")
        assertTrue(text.contains("23505"), text)
        assertNoNames(text)
        assertEquals(null, failed.cause, "no cause chain that could print the detail")
    }
}
