package com.aktcl.aron.backend.platform

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.DriverManager
import java.util.UUID

/**
 * A fresh, migrated PostgreSQL 16 database per test class (docs/24 s2.5): created on the server named by
 * ARON_TEST_PG_URL (the role needs CREATEDB), migrated with the db module's Flyway migrations, dropped on close.
 */
class FreshDb private constructor(val name: String, val url: String, private val adminUrl: String) : AutoCloseable {
    val dataSource: HikariDataSource = HikariDataSource(HikariConfig().apply { jdbcUrl = url; maximumPoolSize = 8 })
    val db: Database = Database(dataSource)

    override fun close() {
        dataSource.close()
        DriverManager.getConnection(adminUrl).use { c -> c.createStatement().use { it.execute("DROP DATABASE IF EXISTS $name WITH (FORCE)") } }
    }

    companion object {
        /** Key of the db lane's TestPostgres.RoleDdlLock. */
        const val ROLE_DDL_LOCK = 7204190014L
        private val shape = Regex("^(jdbc:postgresql://[^/]+/)([^?]*)(\\?.*)?$")

        fun create(migrate: Boolean = true): FreshDb {
            val base = System.getenv("ARON_TEST_PG_URL").orEmpty().ifBlank { error("ARON_TEST_PG_URL is not set (docs/24 s2.5)") }
            val m = shape.matchEntire(base) ?: error("ARON_TEST_PG_URL is not a jdbc:postgresql URL")
            val name = "aron_be_" + UUID.randomUUID().toString().replace("-", "").take(16)
            DriverManager.getConnection(base).use { c -> c.createStatement().use { it.execute("CREATE DATABASE $name") } }
            val fresh = FreshDb(name, m.groupValues[1] + name + m.groupValues[3], base)
            // The V0014/V0020 roles are cluster-wide: migrations of two fresh databases at once must not both run role DDL
            // (CI "tuple concurrently updated"). Same server-wide advisory lock as the db harness (TestPostgres.RoleDdlLock),
            // taken in the shared base database (docs/requests/db-role-ddl-lock.md).
            if (migrate) DriverManager.getConnection(base).use { lock ->
                lock.createStatement().use { it.execute("SELECT pg_advisory_lock($ROLE_DDL_LOCK)") }
                try { Migrator.migrate(fresh.dataSource); PendingAuthzSchema.apply(fresh.dataSource) } finally { lock.createStatement().use { it.execute("SELECT pg_advisory_unlock($ROLE_DDL_LOCK)") } }
            }
            return fresh
        }
    }
}

/**
 * PENDING (backend-authz, D-ROLES-02): the role codes, module grants, brand scopes and the house scope level requested
 * from lane db in docs/requests/db-backend-authz-roles.md, applied to a test database only while db's migrations have
 * not landed (any piece missing). Delete this object and resources/pending/authz-wave4.sql with the push that picks
 * the migrations up; the API itself needs no change (it probes the schema at start-up).
 */
object PendingAuthzSchema {
    private val sql: String by lazy {
        PendingAuthzSchema::class.java.getResourceAsStream("/pending/authz-wave4.sql")!!.bufferedReader().readText()
    }

    fun missing(ds: javax.sql.DataSource): Boolean = ds.connection.use { c ->
        c.createStatement().use { st ->
            st.executeQuery(
                "SELECT to_regclass('app.user_module_grant') IS NULL OR to_regclass('app.user_brand_scope') IS NULL " +
                    "OR NOT EXISTS (SELECT 1 FROM app.role_def WHERE role = 'NATIONAL')",
            ).use { rs -> rs.next(); rs.getBoolean(1) }
        }
    }

    /** Applies the script in one transaction when a piece is missing; [force] runs it regardless (idempotence test). */
    fun apply(ds: javax.sql.DataSource, force: Boolean = false) {
        if (!force && !missing(ds)) return
        ds.connection.use { c ->
            c.autoCommit = false
            try { c.createStatement().use { it.execute(sql) }; c.commit() } catch (e: Exception) { c.rollback(); throw e }
        }
    }
}
