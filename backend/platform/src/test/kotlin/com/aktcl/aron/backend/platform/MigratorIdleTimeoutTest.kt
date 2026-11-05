package com.aktcl.aron.backend.platform

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.api.FlywayException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D-OPS-MIGRATE-IDLE-TIMEOUT-01: Flyway keeps a second connection idle inside a transaction while a migration runs, so a
 * server idle_in_transaction_session_timeout shorter than the migration kills it and the migration fails on Flyway's next
 * write (deploys #308, #309 and #311 on dev, where the timeout is 30 s). The migrate role exempts its own sessions through
 * [Migrator.INIT_SQL]. Here the timeout is 2 s and the migration sleeps 5 s, as a connection option so no server privilege
 * is needed; the same migration passes with the exemption and fails without it.
 */
class MigratorIdleTimeoutTest {
    private fun shortTimeoutDataSource(): HikariDataSource {
        val base = TestPg.url()
        val sep = if ('?' in base) "&" else "?"
        return HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = base + sep + "options=-c%20idle_in_transaction_session_timeout%3D2000"
                maximumPoolSize = 4
            },
        )
    }

    private fun migrationDir(): String {
        val dir = Files.createTempDirectory("mig-idle")
        Files.writeString(dir.resolve("V1__sleep_longer_than_the_timeout.sql"), "SELECT pg_sleep(5);\nCREATE TABLE idle_probe (id int PRIMARY KEY);\n")
        return "filesystem:" + dir.toAbsolutePath()
    }

    private fun dropSchema(ds: HikariDataSource, schema: String) =
        ds.connection.use { c -> c.createStatement().use { s -> s.execute("drop schema if exists \"$schema\" cascade") } }

    @Test
    fun aMigrationLongerThanTheIdleTimeoutSurvivesWithTheExemption() {
        val schema = "mig_idle_ok_" + System.nanoTime()
        shortTimeoutDataSource().use { ds ->
            try {
                val n = Migrator.configure(ds, locations = arrayOf(migrationDir())).schemas(schema).createSchemas(true)
                    .load().migrate().migrationsExecuted
                assertEquals(1, n, "the 5 s migration applied although every session has a 2 s idle-in-transaction timeout")
                ds.connection.use { c ->
                    c.createStatement().use { s ->
                        s.executeQuery("select count(*) from information_schema.tables where table_schema = '$schema' and table_name = 'idle_probe'")
                            .use { r -> r.next(); assertEquals(1, r.getInt(1), "the migration's table exists") }
                    }
                }
            } finally {
                dropSchema(ds, schema)
            }
        }
    }

    @Test
    fun withoutTheExemptionTheSameMigrationFails() {
        val schema = "mig_idle_ko_" + System.nanoTime()
        shortTimeoutDataSource().use { ds ->
            try {
                val e = runCatching {
                    Migrator.configure(ds, locations = arrayOf(migrationDir())).schemas(schema).createSchemas(true).initSql(null)
                        .load().migrate()
                }.exceptionOrNull()
                assertTrue(e is FlywayException, "the idle schema-history connection is terminated at 2 s and Flyway fails on its next write: $e")
            } finally {
                dropSchema(ds, schema)
            }
        }
    }

    @Test
    fun theExemptionIsPartOfTheMigrateRoleConfiguration() {
        TestPg.dataSource().use { ds -> assertEquals(Migrator.INIT_SQL, Migrator.configure(ds).initSql) }
        assertEquals("SET idle_in_transaction_session_timeout = 0", Migrator.INIT_SQL)
    }
}
