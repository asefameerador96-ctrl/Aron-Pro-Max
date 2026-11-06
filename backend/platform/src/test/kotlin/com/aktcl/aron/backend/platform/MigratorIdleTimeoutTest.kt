package com.aktcl.aron.backend.platform

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.api.FlywayException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D-OPS-MIGRATE-IDLE-TIMEOUT-01: Flyway keeps a second connection idle inside a transaction while a migration runs, so a
 * server idle_in_transaction_session_timeout shorter than the migration kills it and the migration fails on Flyway's next
 * write (deploys #308, #309 and #311 on dev, where the timeout is 30 s). The migrate role exempts its own sessions through
 * [Migrator.ExemptIdleInTransaction] (Flyway's afterConnect callback, which replaced the deprecated initSql). Here the timeout is 2 s and the migration sleeps 5 s, as a connection option so no server privilege
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

    private val tempDirs = mutableListOf<Path>()

    private fun migrationDir(): String {
        val dir = Files.createTempDirectory("mig-idle").also { tempDirs.add(it) }
        Files.writeString(dir.resolve("V1__sleep_longer_than_the_timeout.sql"), "SELECT pg_sleep(5);\nCREATE TABLE idle_probe (id int PRIMARY KEY);\n")
        return "filesystem:" + dir.toAbsolutePath()
    }

    @AfterTest
    fun removeTempDirs() {
        tempDirs.forEach { d -> runCatching { d.toFile().deleteRecursively() } }
        tempDirs.clear()
    }

    private fun causes(e: Throwable): Sequence<Throwable> = generateSequence(e) { it.cause }

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
                    Migrator.configure(ds, locations = arrayOf(migrationDir())).schemas(schema).createSchemas(true).callbacks(*emptyArray<org.flywaydb.core.api.callback.Callback>())
                        .load().migrate()
                }.exceptionOrNull()
                assertTrue(e is FlywayException, "the idle schema-history connection is terminated at 2 s and Flyway fails on its next write: $e")
                // The control fails for THE reason under test (the server terminated the idle session), not for any other.
                assertTrue(
                    causes(e).any { c ->
                        (c is java.sql.SQLException && c.sqlState == "08006") ||
                            c.message.orEmpty().contains("terminating connection due to idle-in-transaction timeout")
                    },
                    "SQLSTATE 08006 or the idle-in-transaction termination message in the cause chain: ${causes(e).map { it.javaClass.simpleName + ':' + it.message?.take(120) }.toList()}",
                )
            } finally {
                dropSchema(ds, schema)
            }
        }
    }

    @Test
    fun theExemptionIsPartOfTheMigrateRoleConfiguration() {
        TestPg.dataSource().use { ds ->
            val cfg = Migrator.configure(ds)
            assertTrue(cfg.callbacks.any { it === Migrator.ExemptIdleInTransaction }, "the afterConnect callback is registered")
            assertEquals(null, cfg.initSql, "the deprecated initSql is not used any more")
        }
        assertEquals("SET idle_in_transaction_session_timeout = 0", Migrator.INIT_SQL)
        // The value a migration SEES on its own connection, with the server option at 2 s: 0 (no timeout).
        val schema = "mig_idle_val_" + System.nanoTime()
        val dir = Files.createTempDirectory("mig-idle-val").also { tempDirs.add(it) }
        Files.writeString(dir.resolve("V1__read_setting.sql"), "CREATE TABLE seen AS SELECT current_setting('idle_in_transaction_session_timeout') AS v;\n")
        shortTimeoutDataSource().use { ds ->
            try {
                Migrator.configure(ds, locations = arrayOf("filesystem:" + dir.toAbsolutePath())).schemas(schema).createSchemas(true).load().migrate()
                ds.connection.use { c ->
                    c.createStatement().use { st ->
                        st.executeQuery("select v from \"$schema\".seen").use { r -> r.next(); assertEquals("0", r.getString(1)) }
                    }
                }
            } finally {
                dropSchema(ds, schema)
            }
        }
    }
}
