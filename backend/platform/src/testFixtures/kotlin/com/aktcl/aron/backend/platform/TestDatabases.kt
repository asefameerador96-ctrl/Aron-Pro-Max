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
                try { Migrator.migrate(fresh.dataSource) } finally { lock.createStatement().use { it.execute("SELECT pg_advisory_unlock($ROLE_DDL_LOCK)") } }
            }
            return fresh
        }
    }
}
