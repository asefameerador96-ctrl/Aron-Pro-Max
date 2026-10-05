package com.aktcl.aron.db

import org.flywaydb.core.api.exception.FlywayValidateException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Row N-005 acceptance: migrations apply on an empty PostgreSQL 16; a second run is a no-op; a checksum guard
 * refuses an edited shipped migration. Also: every migration applies in order on a clean database (N-007).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigrationApplyTest {
    private val migrationFiles: List<File> =
        File(System.getProperty("aron.migrations")).listFiles { f -> f.isFile && f.name.endsWith(".sql") }!!
            .sortedBy { it.name }

    private lateinit var db: TestDatabase

    @BeforeAll
    fun createDatabase() {
        db = TestPostgres.createDatabase()
    }

    @AfterAll
    fun dropDatabase() = db.close()

    /** Every relation, column, constraint, index and function of the Aron schemas, as text, for before/after equality. */
    private fun schemaFingerprint(): List<String?> = db.connect().use { c ->
        c.column(
            """
            SELECT 'col ' || table_schema || '.' || table_name || '.' || column_name || ' ' || data_type || ' ' || is_nullable
              FROM information_schema.columns WHERE table_schema IN ('app','dw','stg')
            UNION ALL
            SELECT 'con ' || conrelid::regclass || ' ' || conname || ' ' || pg_get_constraintdef(oid)
              FROM pg_constraint WHERE connamespace IN ('app'::regnamespace, 'dw'::regnamespace)
            UNION ALL
            SELECT 'idx ' || schemaname || '.' || indexname || ' ' || indexdef FROM pg_indexes WHERE schemaname IN ('app','dw')
            UNION ALL
            SELECT 'fn ' || p.oid::regprocedure FROM pg_proc p WHERE p.pronamespace IN ('app'::regnamespace, 'dw'::regnamespace)
            ORDER BY 1
            """.trimIndent(),
        )
    }

    @Test
    fun appliesInOrderOnAnEmptyDatabaseAndASecondRunIsANoOp() {
        val first = db.flyway().migrate()
        assertTrue(first.success)
        assertEquals(migrationFiles.size, first.migrationsExecuted, "every migration file runs once on an empty database")
        val applied = db.connect().use { c ->
            c.column("SELECT script FROM flyway_schema_history WHERE success ORDER BY installed_rank")
        }
        assertEquals(migrationFiles.map { it.name }, applied, "migrations apply in version order")

        val before = schemaFingerprint()
        val second = db.flyway().migrate()
        assertTrue(second.success)
        assertEquals(0, second.migrationsExecuted, "second run applies nothing")
        assertEquals(before, schemaFingerprint(), "second run changes nothing in the schema")
        db.flyway().validate()
    }

    @Test
    fun checksumGuardRefusesAnEditedShippedMigration() {
        val dir = Files.createTempDirectory("aron-migrations").toFile()
        try {
            migrationFiles.forEach { it.copyTo(File(dir, it.name)) }
            TestPostgres.createDatabase().use { edited ->
                edited.flyway("filesystem:${dir.absolutePath}").migrate()
                // A shipped migration is edited after it ran (even a comment changes the checksum).
                val victim = File(dir, migrationFiles[1].name)
                victim.appendText("\n-- edited after shipping\n")
                assertFailsWith<FlywayValidateException> { edited.flyway("filesystem:${dir.absolutePath}").migrate() }
                assertFailsWith<FlywayValidateException> { edited.flyway("filesystem:${dir.absolutePath}").validate() }
                // Nothing was applied by the refused run.
                val count = edited.connect().use { it.scalar("SELECT count(*) FROM flyway_schema_history WHERE success") }
                assertEquals(migrationFiles.size.toString(), count)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aRemovedShippedMigrationIsAlsoRefused() {
        val dir = Files.createTempDirectory("aron-migrations").toFile()
        try {
            migrationFiles.forEach { it.copyTo(File(dir, it.name)) }
            TestPostgres.createDatabase().use { d ->
                d.flyway("filesystem:${dir.absolutePath}").migrate()
                // A shipped migration in the middle of the history disappears (the newest one missing reads as an
                // older image against a newer database, which Flyway's default "*:future" rule tolerates on purpose).
                File(dir, migrationFiles[2].name).delete()
                assertFailsWith<FlywayValidateException> { d.flyway("filesystem:${dir.absolutePath}").migrate() }
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
