package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * V0014: the least-privilege roles of docs/16 s13.4b. Positive and negative privileges are both asserted (a missing
 * grant fails as much as an extra one), and the roles are exercised for real with SET ROLE.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DbRolesTest {
    private lateinit var db: TestDatabase
    private val roles = listOf("api_rw", "worker_rw", "jobs_rw", "web_ro", "bi_reader")

    @BeforeAll
    fun setUp() {
        db = TestPostgres.createDatabase().migrated()
        db.connect().use { c ->
            // Let the test login act as each role (PostgreSQL 16 membership with SET, without inheriting).
            TestPostgres.RoleDdlLock.hold { roles.forEach { c.exec("GRANT $it TO CURRENT_USER WITH INHERIT FALSE, SET TRUE") } }
            c.exec("INSERT INTO app.app_user (username, full_name, role) VALUES ('sr0001', 'SR', 'SR')")
        }
    }

    @AfterAll
    fun tearDown() {
        db.connect().use { c -> TestPostgres.RoleDdlLock.hold { roles.forEach { runCatching { c.exec("REVOKE $it FROM CURRENT_USER") } } } }
        db.close()
    }

    private fun can(c: Connection, role: String, table: String, privilege: String) =
        c.scalar("SELECT has_table_privilege(?, ?, ?)", role, table, privilege) == "t"

    @Test
    fun theRolesAreNologinGroupsWithoutSpecialPowers() = db.connect().use { c ->
        roles.forEach { r ->
            val inherit = if (r == "jobs_rw") "" else ""
            assertEquals("f|f|f|f|f|f$inherit", c.scalar("SELECT concat_ws('|', rolcanlogin, rolsuper, rolbypassrls, rolcreaterole, rolcreatedb, rolinherit) FROM pg_roles WHERE rolname = ?", r), r)
        }
    }

    @Test
    fun theGrantMatrixIsExactlyTheLeastPrivilegeMap() = db.connect().use { c ->
        val expected = mapOf(
            // role, table, privilege -> allowed
            Triple("api_rw", "app.memo", "SELECT") to true, Triple("api_rw", "app.memo", "INSERT") to true,
            Triple("api_rw", "app.memo", "UPDATE") to true, Triple("api_rw", "app.memo", "DELETE") to false,
            Triple("api_rw", "app.audit_log", "INSERT") to true, Triple("api_rw", "app.audit_log", "UPDATE") to false,
            Triple("api_rw", "app.due_ledger", "UPDATE") to false, Triple("api_rw", "app.domain_event", "UPDATE") to false,
            Triple("api_rw", "app.dirty_key", "UPDATE") to true, Triple("api_rw", "app.db_role_grant", "SELECT") to false,
            Triple("api_rw", "app.auth_lockout", "DELETE") to true, Triple("api_rw", "app.refresh_family", "UPDATE") to true,
            Triple("api_rw", "app.refresh_token", "INSERT") to true,
            Triple("bi_reader", "dw.fact_geo_fix", "SELECT") to false, Triple("bi_reader", "dw.fact_attendance", "SELECT") to false,
            Triple("web_ro", "dw.fact_geo_fix", "SELECT") to false, Triple("bi_reader", "dw.v_attendance", "SELECT") to true,
            Triple("api_rw", "dw.agg_daily_route", "SELECT") to true, Triple("api_rw", "dw.agg_daily_route", "INSERT") to false,
            Triple("worker_rw", "app.memo", "SELECT") to true, Triple("worker_rw", "app.memo", "INSERT") to false,
            Triple("worker_rw", "app.memo", "UPDATE") to false, Triple("worker_rw", "app.route_day", "UPDATE") to true,
            Triple("worker_rw", "app.ingest_registry", "DELETE") to true, Triple("worker_rw", "app.audit_log", "INSERT") to false,
            Triple("worker_rw", "dw.agg_daily_route", "INSERT") to true, Triple("worker_rw", "dw.fact_memo", "DELETE") to true,
            Triple("web_ro", "dw.v_daily_route", "SELECT") to true, Triple("web_ro", "dw.agg_daily_zone", "SELECT") to false,
            Triple("web_ro", "app.code_list_item", "SELECT") to true, Triple("web_ro", "app.memo", "SELECT") to false,
            Triple("web_ro", "app.app_user", "SELECT") to false, Triple("web_ro", "dw.agg_daily_route", "INSERT") to false,
            Triple("bi_reader", "dw.v_daily_sr", "SELECT") to true, Triple("bi_reader", "dw.fact_memo", "SELECT") to false,
            Triple("bi_reader", "app.code_list_item", "SELECT") to false, Triple("bi_reader", "app.outlet", "SELECT") to false,
            Triple("bi_reader", "dw.agg_daily_route", "UPDATE") to false,
            Triple("bi_reader", "dw.v_outlet_masked", "SELECT") to true, Triple("web_ro", "dw.v_outlet_masked", "SELECT") to true,
            // V0020: auth path, PII columns and L1 support.
            Triple("auth_rw", "app.refresh_token", "INSERT") to true, Triple("auth_rw", "app.auth_lockout", "DELETE") to true,
            Triple("auth_rw", "app.app_user", "UPDATE") to true, Triple("auth_rw", "app.app_user", "INSERT") to false,
            Triple("auth_rw", "app.memo", "SELECT") to false, Triple("auth_rw", "app.visit", "INSERT") to false,
            Triple("auth_rw", "app.audit_log", "INSERT") to true, Triple("auth_rw", "app.audit_log", "UPDATE") to false,
            Triple("pii_reader", "app.memo", "SELECT") to false, Triple("support_ro", "app.device", "SELECT") to true,
            Triple("support_ro", "app.device", "UPDATE") to false, Triple("support_ro", "app.outlet", "SELECT") to false,
            // V0029: the admin flows' deletes and the data-void tombstone; nothing wider.
            Triple("api_rw", "app.route_planned", "DELETE") to true, Triple("api_rw", "app.user_scope", "DELETE") to true,
            Triple("api_rw", "app.mfa_secret", "DELETE") to true, Triple("api_rw", "app.geo_fix", "UPDATE") to true,
            Triple("api_rw", "app.geo_fix", "DELETE") to false, Triple("api_rw", "app.route", "DELETE") to false,
            Triple("api_rw", "app.outlet", "DELETE") to false, Triple("worker_rw", "app.mfa_secret", "DELETE") to false,
        )
        val wrong = expected.filter { (k, allowed) -> can(c, k.first, k.second, k.third) != allowed }.keys
        assertEquals(emptySet(), wrong, "privileges that differ from the map")
        // No role touches a single partition directly, and every app and dw table is covered by the map.
        assertEquals("0", c.scalar("SELECT count(*) FROM information_schema.role_table_grants g JOIN pg_class k ON k.relname = g.table_name AND k.relispartition WHERE g.grantee IN ('api_rw','worker_rw','web_ro','bi_reader','jobs_rw','auth_rw','pii_reader','support_ro')"))
        assertEquals(
            emptyList(),
            c.column("SELECT n.nspname || '.' || k.relname FROM pg_class k JOIN pg_namespace n ON n.oid = k.relnamespace WHERE n.nspname IN ('app','dw') AND k.relkind IN ('r','p','v') AND NOT k.relispartition AND k.relname <> 'db_role_grant' AND NOT has_table_privilege('worker_rw', k.oid, 'SELECT') AND NOT (n.nspname = 'app' AND k.relname IN ('partition_policy', 'app_user', 'mfa_secret', 'device_otp', 'refresh_token', 'enrolment_token'))"),
        )
        // The worker reads users without their password hash and never reads credentials or one-time secrets.
        assertEquals("t", c.scalar("SELECT has_column_privilege('worker_rw', 'app.app_user', 'username', 'SELECT')"))
        assertEquals("f", c.scalar("SELECT has_column_privilege('worker_rw', 'app.app_user', 'password_hash', 'SELECT')"))
        assertEquals(
            listOf("f", "f", "f", "f"),
            c.column("SELECT has_table_privilege('worker_rw', t, 'SELECT') FROM unnest(ARRAY['app.mfa_secret','app.device_otp','app.refresh_token','app.enrolment_token']) t"),
        )
        assertEquals("t", c.scalar("SELECT has_column_privilege('api_rw', 'app.app_user', 'password_hash', 'SELECT')"))
        // pii_reader: outlet contact columns only, never the national ids; support_ro: the sync log without payloads.
        assertEquals("t", c.scalar("SELECT has_column_privilege('pii_reader', 'app.outlet', 'contact_number', 'SELECT')"))
        assertEquals("f", c.scalar("SELECT has_column_privilege('pii_reader', 'app.outlet', 'nid', 'SELECT')"))
        assertEquals("t", c.scalar("SELECT has_column_privilege('support_ro', 'app.sync_batch', 'record_count', 'SELECT')"))
        assertEquals("f", c.scalar("SELECT has_column_privilege('support_ro', 'app.sync_batch', 'response_gz', 'SELECT')"))
        // web_ro and bi_reader read only the stable views: no dw table at all.
        assertEquals(
            emptyList(),
            c.column("SELECT r || ' ' || k.relname FROM pg_class k, unnest(ARRAY['web_ro','bi_reader']) r WHERE k.relnamespace = 'dw'::regnamespace AND k.relkind IN ('r','p') AND has_table_privilege(r, k.oid, 'SELECT')"),
        )
    }

    @Test
    fun loginIdentitiesGetTheSessionLimitsOfTheirRoleAndCannotDropAnything(): Unit = TestPostgres.RoleDdlLock.hold {
        val login = "zz_api_" + java.util.UUID.randomUUID().toString().take(8)
        val password = java.util.UUID.randomUUID().toString()
        db.connect().use { c ->
            c.exec("CREATE ROLE $login LOGIN PASSWORD '$password'")
            c.exec("GRANT api_rw TO $login WITH INHERIT TRUE")
            assertTrue(c.scalar("SELECT app.apply_login_limits()")!!.toInt() >= 1)
        }
        try {
            val url = db.url.replace(Regex("[?&](user|password)=[^&]*"), "").let { u -> if ('?' !in u && '&' in u) u.replaceFirst('&', '?') else u }
            java.sql.DriverManager.getConnection(url, login, password).use { c ->
                assertEquals("15s", c.scalar("SHOW statement_timeout"))
                assertEquals("3s", c.scalar("SHOW lock_timeout"))
                assertEquals("30s", c.scalar("SHOW idle_in_transaction_session_timeout"))
                assertEquals("42501", assertFailsWith<SQLException> { c.exec("DROP TABLE app.memo") }.sqlState)
                assertEquals("42501", assertFailsWith<SQLException> { c.exec("ALTER TABLE app.audit_log DISABLE TRIGGER USER") }.sqlState)
                c.exec("SET statement_timeout = '50ms'")   // the cancel path of a limit, without waiting 15 s
                assertEquals("57014", assertFailsWith<SQLException> { c.exec("SELECT pg_sleep(1)") }.sqlState)
            }
        } finally {
            db.connect().use { it.exec("DROP ROLE $login") }
        }
    }

    /** The role block of V0014 (the DO statement), run as the migration runs it. */
    private val v0014RoleBlock: String = java.io.File(System.getProperty("aron.migrations"))
        .listFiles { f -> f.name.startsWith("V0014__") }!!.single().readText()
        .let { t -> t.substring(t.indexOf("DO $$"), t.indexOf("END $$;") + "END $$;".length) }

    /**
     * The roles are server-wide, so a broken attribute is set and repaired inside one transaction that is rolled back:
     * no other session (another test JVM migrating at the same time) ever sees bi_reader broken and races to fix it.
     */
    @Test
    fun aRoleLeftWithAWrongAttributeIsRepairedByTheNextMigration(): Unit = TestPostgres.RoleDdlLock.hold {
        db.connect().use { c ->
            c.autoCommit = false
            try {
                c.exec("ALTER ROLE bi_reader INHERIT")
                c.exec(v0014RoleBlock)
                assertEquals("f", c.scalar("SELECT rolinherit FROM pg_roles WHERE rolname = 'bi_reader'"))
            } finally {
                c.rollback()
            }
        }
        db.connect().use { c -> assertEquals("f", c.scalar("SELECT rolinherit FROM pg_roles WHERE rolname = 'bi_reader'")) }
    }

    /**
     * CI run 254: "tuple concurrently updated" on ALTER ROLE bi_reader when test processes migrated while a test changed
     * the role. Migrations of the harness and role changes of the tests are serialised by TestPostgres.RoleDdlLock;
     * here two databases migrate at the same time as repeated repairs, and every one succeeds.
     */
    @Test
    fun concurrentRoleMigrationsAndRepairsDoNotRace() {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val migrations = (1..2).map { pool.submit<String> { TestPostgres.createDatabase().use { d -> d.migrated(); d.name } } }
            val repairs = pool.submit { repeat(5) { aRoleLeftWithAWrongAttributeIsRepairedByTheNextMigration() } }
            migrations.forEach { assertTrue(it.get(10, java.util.concurrent.TimeUnit.MINUTES).startsWith("aron_db_test_")) }
            repairs.get(10, java.util.concurrent.TimeUnit.MINUTES)
        } finally {
            pool.shutdownNow()
        }
        db.connect().use { c -> assertEquals("f", c.scalar("SELECT rolinherit FROM pg_roles WHERE rolname = 'bi_reader'")) }
    }

    @Test
    fun anotherLoginMigratesANewDatabaseWhereTheRolesAlreadyExist(): Unit = TestPostgres.RoleDdlLock.hold {
        // The roles are server-wide: a second database migrated by a different CREATEROLE login (no ADMIN on the roles)
        // reuses them unchanged. The password is generated here and dropped with the login.
        val login = "zz_mig_" + java.util.UUID.randomUUID().toString().take(8)
        val password = java.util.UUID.randomUUID().toString()
        db.connect().use { it.exec("CREATE ROLE $login LOGIN CREATEROLE PASSWORD '$password'") }
        try {
            TestPostgres.createDatabase().use { other ->
                other.connect().use { it.exec("GRANT CREATE ON DATABASE ${other.name} TO $login"); it.exec("GRANT CREATE ON SCHEMA public TO $login") }
                val url = other.url.replace(Regex("[?&](user|password)=[^&]*"), "").let { u -> if ('?' !in u && '&' in u) u.replaceFirst('&', '?') else u }
                org.flywaydb.core.Flyway.configure().dataSource(url, login, password).locations(other.flyway().configuration.locations.first().toString())
                    .cleanDisabled(true).load().migrate()
                other.connect().use { c ->
                    // By oid: the checking login has no USAGE on the other login's schemas.
                    assertEquals("t", c.scalar("SELECT has_table_privilege('api_rw', k.oid, 'INSERT') FROM pg_class k JOIN pg_namespace n ON n.oid = k.relnamespace WHERE n.nspname = 'app' AND k.relname = 'memo'"))
                    assertEquals("f", c.scalar("SELECT has_function_privilege('public', p.oid, 'EXECUTE') FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'app' AND p.proname = 'apply_db_role_grants'"))
                }
            }
        } finally {
            db.connect().use { it.exec("DROP ROLE $login") }
        }
    }

    @Test
    fun objectsOfLaterMigrationsGetTheMapAndNothingForPublic() = db.connect().use { c ->
        c.autoCommit = false
        try {
            c.exec("CREATE FUNCTION app.zz_admin() RETURNS int LANGUAGE sql AS 'SELECT 1'")
            c.exec("CREATE TABLE app.zz_serial (id bigserial PRIMARY KEY, note text)")
            // Before any grant is applied: the global default privileges of the migrating login keep PUBLIC out.
            assertEquals("f", c.scalar("SELECT has_function_privilege('web_ro', 'app.zz_admin()', 'EXECUTE')"))
            c.exec("SELECT app.apply_db_role_grants()")
            assertEquals("f", c.scalar("SELECT has_function_privilege('web_ro', 'app.zz_admin()', 'EXECUTE')"))
            assertEquals("f", c.scalar("SELECT bool_or(has_function_privilege('public', p.oid, 'EXECUTE')) FROM pg_proc p WHERE p.pronamespace = 'app'::regnamespace AND p.proname = 'zz_admin'"))
            c.exec("SET ROLE api_rw")
            c.exec("INSERT INTO app.zz_serial (note) VALUES ('x')")
            c.exec("RESET ROLE")
            // The login flow's own SQL (backend:auth JdbiStores) runs under api_rw.
            c.exec("SET ROLE api_rw")
            c.exec("INSERT INTO app.auth_lockout (lock_key, failures) VALUES ('k', 1) ON CONFLICT (lock_key) DO UPDATE SET failures = app.auth_lockout.failures + 1")
            c.exec("DELETE FROM app.auth_lockout WHERE lock_key = 'k'")
            c.exec("RESET ROLE")
        } finally {
            c.rollback()
        }
    }

    /**
     * V0029 (docs/requests/db-runtime-roles-gaps.md): the statements the admin flows run, as api_rw. They match no row
     * here; PostgreSQL checks the privileges all the same, so a missing grant fails with 42501 exactly as on dev.
     */
    @Test
    fun theAdminFlowsRunAsApiRw() = db.connect().use { c ->
        c.autoCommit = false
        try {
            c.exec("SET ROLE api_rw")
            c.exec("DELETE FROM app.route_planned WHERE route_id = -1 AND valid_from >= DATE '2026-10-08'")              // AdminRoutes
            c.exec("UPDATE app.route_planned SET valid_to = DATE '2026-10-08' WHERE route_id = -1 AND valid_from < DATE '2026-10-08'")
            c.exec("DELETE FROM app.user_scope WHERE id = -1")                                                            // AdminUsers scope edit
            c.exec("UPDATE app.user_scope SET valid_to = DATE '2026-10-08' WHERE id = -1")
            c.exec("DELETE FROM app.mfa_secret WHERE user_id = -1")                                                       // AdminUsers MFA reset
            c.exec("DELETE FROM app.auth_lockout WHERE lower(lock_key) = 'x'")
            c.exec("UPDATE app.geo_fix SET voided_at = now() WHERE route_id = -1 AND business_date = DATE '2026-10-07' AND voided_at IS NULL") // DataVoidApi
            // DataVoidApi tombstones by table name at run time: its APP_VOID_TABLES (mirrored here; keep in step) plus geo_fix
            // each take the void update as api_rw. Append-only trails no void covers (indent_movement, submit_void_event, ...)
            // stay refused.
            val voidable = listOf(
                "attendance_event", "route_day_event", "day_exception", "stock_movement", "visit", "visit_skip", "memo", "memo_line", "memo_discount",
                "qc_entry_line", "print_event", "memo_void", "due_collection", "survey_response", "distribution_check", "distribution_check_line",
                "call_assessment", "call_assessment_answer", "outlet_change_request", "content_view", "redemption", "redemption_line", "gift_photo",
                "price_compliance_check", "sale_abort", "geo_fix",
            )
            voidable.forEach { t -> c.exec("UPDATE app.$t SET voided_at = now() WHERE false") }
            // Still refused: deletes nobody runs, and updates of append-only trails.
            for (sql in listOf("UPDATE app.indent_movement SET voided_at = now() WHERE false", "DELETE FROM app.memo WHERE false", "DELETE FROM app.route WHERE false", "UPDATE app.audit_log SET via = via WHERE false", "DELETE FROM app.geo_fix WHERE false")) {
                c.exec("SAVEPOINT s")
                assertEquals("42501", assertFailsWith<SQLException>(sql) { c.exec(sql) }.sqlState, sql)
                c.exec("ROLLBACK TO SAVEPOINT s")
            }
        } finally {
            c.rollback()
        }
    }

    @Test
    fun eachRoleWorksForRealUnderSetRole() = db.connect().use { c ->
        c.autoCommit = false
        try {
            c.exec("SET ROLE api_rw")
            c.exec("INSERT INTO app.audit_log (via, entity, entity_id, action) VALUES ('api', 'route', '1', 'update')")   // chain trigger runs as api_rw
            c.exec("SELECT app.mark_dirty('bundle_user', 1, '2026-10-07')")
            c.exec("SELECT app.mark_dirty('bundle_user', 1, '2026-10-07')")
            c.exec("SAVEPOINT s")
            assertEquals("42501", assertFailsWith<SQLException> { c.exec("SELECT app.ensure_partitions('2028-01-01', '2028-01-01')") }.sqlState)
            c.exec("ROLLBACK TO SAVEPOINT s")
            c.exec("RESET ROLE")

            c.exec("SET ROLE web_ro")
            c.exec("SELECT count(*) FROM dw.v_daily_route")
            c.exec("SELECT count(*) FROM dw.v_geo_integrity")
            c.exec("SAVEPOINT s")
            assertEquals("42501", assertFailsWith<SQLException> { c.exec("SELECT * FROM app.memo") }.sqlState)
            c.exec("ROLLBACK TO SAVEPOINT s")
            c.exec("RESET ROLE")

            c.exec("SET ROLE bi_reader")
            listOf("v_daily_route", "v_daily_sr", "v_daily_outlet", "v_daily_sku", "v_collections", "v_attendance", "v_geo_integrity")
                .forEach { c.exec("SELECT * FROM dw.$it LIMIT 1") }
            c.exec("RESET ROLE")

            c.exec("SET ROLE jobs_rw")
            assertTrue(c.scalar("SELECT app.ensure_partitions('2028-01-01', '2028-01-01')")!!.toInt() > 0, "jobs create partitions with the owner's rights")
            c.exec("RESET ROLE")
        } finally {
            c.rollback()
        }
    }
}
