package com.aktcl.aron.backend.platform

import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D-ROLES-02: the SQL requested from lane db (docs/requests/db-backend-authz-roles.md, applied by the fixtures from
 * resources/pending/authz-wave4.sql until db's migrations land) moves TOP users to NATIONAL with their role values and
 * grants, seeds every ADMIN with write on all fifteen modules, and ends in the same state when it runs twice.
 */
class PendingAuthzSchemaTest {
    private val fresh = FreshDb.create(migrate = false)

    @AfterTest fun close() = fresh.close()

    private fun migrateOnly() {
        DriverManager.getConnection(System.getenv("ARON_TEST_PG_URL")).use { lock ->
            lock.createStatement().use { it.execute("SELECT pg_advisory_lock(${FreshDb.ROLE_DDL_LOCK})") }
            try { Migrator.migrate(fresh.dataSource) } finally { lock.createStatement().use { it.execute("SELECT pg_advisory_unlock(${FreshDb.ROLE_DDL_LOCK})") } }
        }
    }

    private fun <T> q(sql: String, map: (java.sql.ResultSet) -> T): List<T> = fresh.dataSource.connection.use { c ->
        c.createStatement().use { st -> st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(map(rs)) } } }
    }

    private fun exec(sql: String) = fresh.dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }

    private fun snapshot(): List<String> =
        q("SELECT role || ':' || ordinal FROM app.role_def ORDER BY ordinal") { it.getString(1) } +
            q("SELECT username || ':' || role || ':' || scope_version FROM app.app_user ORDER BY id") { it.getString(1) } +
            q("SELECT key || ':' || scope_id || ':' || value::text || ':' || config_version FROM app.cfg_value WHERE scope_type = 'role' ORDER BY key, scope_id, id") { it.getString(1) } +
            q("SELECT max(config_version)::text FROM app.cfg_version") { it.getString(1) } +
            q("SELECT role || ':' || permission FROM app.role_grant_map ORDER BY 1") { it.getString(1) } +
            q("SELECT user_id || ':' || module || ':' || access FROM app.user_module_grant ORDER BY 1") { it.getString(1) } +
            q("SELECT (default_value -> 'NATIONAL' = default_value -> 'TOP')::text || ':' || (default_value ? 'BRAND')::text FROM app.cfg_key WHERE key = 'cfg.web.menu_by_role'") { it.getString(1) }

    @Test
    fun topUsersBecomeNationalWithTheirValuesAndGrantsAndASecondRunChangesNothing() {
        migrateOnly()
        assertTrue(PendingAuthzSchema.missing(fresh.dataSource))
        exec(
            """
            INSERT INTO app.app_user (username, full_name, role, status, scope_version) VALUES
              ('top1', 'Top One', 'TOP', 'active', 4), ('admin7', 'Admin Seven', 'ADMIN', 'active', 1), ('tso7', 'Tso Seven', 'TSO', 'active', 1);
            INSERT INTO app.role_grant_map (role, permission) VALUES ('TOP', 'reports.export_pii');
            INSERT INTO app.cfg_version (config_version, kind, committed_by, summary)
              SELECT (SELECT max(config_version) + 1 FROM app.cfg_version), 'change', id, 'test: TOP must use MFA' FROM app.app_user WHERE username = 'aron.system';
            INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, created_by, reason)
              SELECT 'cfg.auth.mfa_required_roles', 'global', 0, '["ADMIN", "SUPERADMIN", "SUPPORT", "TOP"]'::jsonb, now() - interval '1 day',
                     (SELECT max(config_version) FROM app.cfg_version), id, 'test: TOP must use MFA' FROM app.app_user WHERE username = 'aron.system';
            """.trimIndent(),
        )
        PendingAuthzSchema.apply(fresh.dataSource)
        val once = snapshot()
        PendingAuthzSchema.apply(fresh.dataSource, force = true)
        assertEquals(once, snapshot(), "a second run of the TOP migration changes nothing")
        assertFalse(PendingAuthzSchema.missing(fresh.dataSource))

        assertEquals(listOf("NATIONAL:5"), q("SELECT role || ':' || scope_version FROM app.app_user WHERE username = 'top1'") { it.getString(1) })
        assertEquals(0, q("SELECT count(*) FROM app.app_user WHERE role = 'TOP'") { it.getInt(1) }.single())
        assertTrue("NATIONAL:reports.export_pii" in once)
        // Checker round 2 (N3): moved, not copied, so a re-run cannot put back a grant narrowed on NATIONAL.
        assertEquals(0, q("SELECT count(*) FROM app.role_grant_map WHERE role = 'TOP'") { it.getInt(1) }.single(), "no TOP grant left")
        // Checker N4: a role list in effect that names TOP keeps meaning the migrated users (MFA must not loosen).
        val mfa = q("SELECT value::text, effective_to IS NULL FROM app.cfg_value WHERE key = 'cfg.auth.mfa_required_roles' ORDER BY id") { it.getString(1) to it.getBoolean(2) }
        assertEquals(listOf("""["ADMIN", "SUPERADMIN", "SUPPORT", "TOP"]""" to false, """["ADMIN", "SUPERADMIN", "SUPPORT", "NATIONAL"]""" to true), mfa)
        // NATIONAL holds every role value TOP holds (web-role password length and token TTL); BRAND the two web defaults.
        val values = q("SELECT key, scope_id, value::text FROM app.cfg_value WHERE scope_type = 'role' AND scope_id IN (6, 11, 12)") { Triple(it.getString(1), it.getLong(2), it.getString(3)) }
        val top = values.filter { it.second == 6L }.map { it.first to it.third }.toSet()
        assertEquals(top, values.filter { it.second == 11L }.map { it.first to it.third }.toSet())
        assertEquals(top.filter { it.first in setOf("cfg.auth.password_min_len", "cfg.auth.access_ttl_min") }.toSet(), values.filter { it.second == 12L }.map { it.first to it.third }.toSet())
        assertTrue(top.isNotEmpty())
        assertTrue("true:true" in once, "menu_by_role default: NATIONAL = TOP, BRAND present")
        // Every ADMIN keeps everything: w on all fifteen modules; nobody else gets a grant.
        val grants = q("SELECT u.username, g.module, g.access FROM app.user_module_grant g JOIN app.app_user u ON u.id = g.user_id") { Triple(it.getString(1), it.getString(2), it.getString(3)) }
        assertEquals(setOf("admin7"), grants.map { it.first }.toSet())
        assertEquals(15, grants.size)
        assertTrue(grants.all { it.third == "w" })
        // TOP can no longer be given to anyone; a brand scope must name a brand.
        val top2 = runCatching { exec("INSERT INTO app.app_user (username, full_name, role) VALUES ('top2', 'Top Two', 'TOP')") }
        assertTrue(top2.isFailure)
        exec("INSERT INTO app.product_node (level, name) VALUES ('category', 'Cigarettes')")
        val notBrand = runCatching { exec("INSERT INTO app.user_brand_scope (user_id, brand_id) SELECT u.id, n.id FROM app.app_user u, app.product_node n WHERE u.username = 'admin7'") }
        assertTrue(notBrand.isFailure)
        exec("INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from) SELECT id, 'house', 1, DATE '2026-10-01' FROM app.app_user WHERE username = 'tso7'")
    }
}
